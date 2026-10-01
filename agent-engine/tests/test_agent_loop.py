import pytest
import json
import time
from dataclasses import asdict
from pathlib import Path
from pydantic import BaseModel, ConfigDict, Field

from agents.architect import ArchitectAgent
from agents.backend_engineer import BackendEngineerAgent
from agents.product_manager import ProductManagerAgent
from runtime.agent_loop_trace import capture_agent_loop_trace, current_agent_loop_trace
from runtime.backend_agent_loop import run_backend_agent_loop
from runtime.execution_context import ModelExecutionContract, bind_model_execution_contract
from runtime.tool_harness import (
    ToolHarness,
    ToolRegistry,
    ToolRuntimeContext,
    bind_tool_runtime_context,
)
from schemas.agent_loop import LoopPolicy, StopReason, stable_hash
from schemas.workflow_spec import ToolPolicy, ToolRef
from schemas.workflow_spec import WorkflowSpec
from runtime.model_telemetry import capture_model_invocations, record_model_invocation, ModelInvocationTelemetry
from runtime.node_executor import InvocationRecord
from runtime.tool_gateway import register_controlled_gateway_tools
from schemas.tool_gateway import ToolGatewayResult
from spec_verifier.validators import validate_l1


def _inputs():
    requirement = "Build a campus marketplace with listings, favorites, orders, and admin audit."
    prd = ProductManagerAgent().run(requirement)
    architecture = ArchitectAgent().run(requirement, prd)
    return requirement, prd, architecture


@pytest.mark.asyncio
async def test_fixture_backend_loop_executes_allowlisted_tool_and_finishes() -> None:
    requirement, prd, architecture = _inputs()
    registry = ToolRegistry()

    class ContractInput(BaseModel):
        model_config = ConfigDict(extra="forbid")

        node_id: str = Field(min_length=1)

    class ContractOutput(BaseModel):
        node_id: str
        output_schema: str


    # The harness validates the tool boundary; this fixture only returns a
    # stable observation, like the production control-plane lookup.
    registry.register(
        "contract.lookup",
        "v1",
        ContractInput,
        ContractOutput,
        lambda _value: {"node_id": "backend_engineer", "output_schema": "BackendDesignArtifact"},
    )
    tool_policy = ToolPolicy(
        enabled=True,
        allowed_tools=[ToolRef(name="contract.lookup", version="v1")],
        max_calls=2,
    )
    harness = ToolHarness(registry)
    policy = LoopPolicy(enabled=True, max_steps=4, max_replans=1)
    contract = ModelExecutionContract(
        execution_id="loop-test",
        prompt_key="backend_engineer_loop",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={"route_key": "balanced", "max_calls": 4},
        deadline_epoch_ms=9_999_999_999_999,
        protocol_version=2,
        contract_hash="b" * 64,
        schema_version="BackendDesignArtifact",
        tool_policy=tool_policy.model_dump(mode="json"),
        agent_loop_policy=policy.model_dump(mode="json"),
    )
    tool_context = ToolRuntimeContext(
        execution_id="loop-test",
        node_id="backend_engineer",
        attempt=1,
        policy=tool_policy,
        deadline_epoch_ms=contract.deadline_epoch_ms,
        contract_hash=contract.contract_hash,
        schema_version="BackendDesignArtifact",
        harness=harness,
    )

    with bind_model_execution_contract(contract), bind_tool_runtime_context(tool_context), capture_model_invocations(
        execution_id=contract.execution_id, deadline_epoch_ms=contract.deadline_epoch_ms, max_model_calls=4,
    ) as invocations:
        result = await run_backend_agent_loop(
            requirement=requirement,
            prd=prd,
            architecture_design=architecture.model_dump(mode="json"),
            retrieved_sources=[],
            context_manifest={},
            rework_directive=None,
            model_client=None,
            policy=policy,
        )

    assert result.completed is True
    assert result.stop_reason == StopReason.COMPLETED
    assert {step.phase for step in result.steps} >= {"PLAN", "TOOL_CALL", "OBSERVATION", "VALIDATION"}
    models = [invocation for invocation in invocations if invocation.call_type == "MODEL"]
    assert len(models) == 3
    assert len({invocation.call_id for invocation in invocations}) == len(invocations)
    for invocation in models:
        InvocationRecord.model_validate(asdict(invocation)).validate_frozen_call()
        assert invocation.prompt_key == contract.prompt_key


@pytest.mark.asyncio
async def test_backend_loop_replans_after_deterministic_validation_failure() -> None:
    requirement, prd, architecture = _inputs()
    valid = BackendEngineerAgent().run(requirement, prd, architecture)
    invalid = valid.model_dump(mode="json")
    invalid["apis"] = []
    responses = iter(
        [
            {"type": "PLAN", "goal": "design", "steps": ["draft"]},
            {"type": "FINAL_CANDIDATE", "candidate": invalid},
            {
                "type": "REPLAN",
                "issue_codes": ["SCHEMA_INVALID"],
                "required_changes": ["restore API coverage"],
                "reason": "the candidate is incomplete",
            },
            {"type": "FINAL_CANDIDATE", "candidate": valid.model_dump(mode="json")},
        ]
    )

    class Model:
        def generate_json(self, _prompt_name, _payload):
            return next(responses)

    result = await run_backend_agent_loop(
        requirement=requirement,
        prd=prd,
        architecture_design=architecture.model_dump(mode="json"),
        retrieved_sources=[],
        context_manifest={},
        rework_directive=None,
        model_client=Model(),
        policy=LoopPolicy(enabled=True, max_steps=6, max_replans=2),
    )

    assert result.completed is True
    assert result.stop_reason == StopReason.COMPLETED
    assert any(step.phase == "REPLAN" for step in result.steps)


@pytest.mark.asyncio
async def test_candidate_backend_loop_replans_after_verifier_feedback() -> None:
    requirement, prd, architecture = _inputs()
    valid = BackendEngineerAgent().run(requirement, prd, architecture).model_dump(mode="json")
    invalid = json.loads(json.dumps(valid))
    product_id = next(
        field
        for table in invalid["tables"]
        for field in table["fields"]
        if field["name"] == "product_id"
    )
    product_id["type"] = "VARCHAR(64)"
    responses = iter(
        [
            {"type": "PLAN", "goal": "design", "steps": ["draft", "verify"]},
            {"type": "FINAL_CANDIDATE", "candidate": invalid},
            {
                "type": "REPLAN",
                "issue_codes": ["TABLE_FOREIGN_KEY_TYPE_MISMATCH"],
                "required_changes": ["match the foreign-key field type"],
                "reason": "the verifier found a foreign-key type mismatch",
            },
            {"type": "FINAL_CANDIDATE", "candidate": valid},
        ]
    )

    class Model:
        def generate_json(self, _prompt_name, _payload):
            return next(responses)

    tool_policy = ToolPolicy(
        enabled=True,
        allowed_tools=[ToolRef(name="spec.verify", version="v1")],
        max_calls=2,
        allowed_side_effects=["SANDBOXED"],
    )
    loop_policy = LoopPolicy(
        version="agent-loop-v2",
        enabled=True,
        max_steps=7,
        max_replans=1,
        validator_profile="backend-design-v2",
    )

    class Gateway:
        calls = 0

        async def execute(self, request, **_kwargs):
            self.calls += 1
            report = validate_l1(
                request.arguments["contract"],
                execution_id=request.execution_id,
                scope=request.arguments["scope"],
            )
            return ToolGatewayResult(
                request_id=request.request_id,
                idempotency_key=request.idempotency_key,
                status="SUCCEEDED",
                result=report.model_dump(mode="json"),
                result_hash="a" * 64,
            )

    gateway = Gateway()
    registry = ToolRegistry()
    register_controlled_gateway_tools(registry, gateway)
    execution = ModelExecutionContract(
        execution_id="backend-verifier-loop",
        prompt_key="backend_engineer_shared",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={"max_calls": 5},
        deadline_epoch_ms=int(time.time() * 1000) + 30_000,
        protocol_version=2,
        contract_hash="b" * 64,
        schema_version="BackendDesignArtifact",
        tool_policy=tool_policy.model_dump(mode="json"),
        agent_loop_policy=loop_policy.model_dump(mode="json"),
        verification_policy={
            "enabled": True,
            "scope": "BACKEND",
            "required_level": "L1",
            "rule_profile": "spec-backend-v1",
            "verifier_version": "spec-verifier-v1",
            "compiler_version": "spec-compiler-v1",
            "timeout_ms": 30_000,
        },
    )
    context = ToolRuntimeContext(
        execution_id=execution.execution_id,
        node_id="backend_engineer",
        attempt=1,
        policy=tool_policy,
        deadline_epoch_ms=execution.deadline_epoch_ms,
        contract_hash=execution.contract_hash,
        schema_version="BackendDesignArtifact",
        harness=ToolHarness(registry),
        workflow_run_id=1,
        node_run_id=2,
        actor_user_id="3",
        project_id="4",
        fencing_token=1,
    )

    with bind_model_execution_contract(execution), bind_tool_runtime_context(context):
        result = await run_backend_agent_loop(
            requirement=requirement,
            prd=prd,
            architecture_design=architecture.model_dump(mode="json"),
            retrieved_sources=[],
            context_manifest={},
            rework_directive=None,
            model_client=Model(),
            policy=loop_policy,
        )

    assert result.completed is True
    assert result.stop_reason == StopReason.COMPLETED
    assert gateway.calls == 2
    failed = [step for step in result.steps if step.reason_code == "SPEC_VERIFY_FAILED"]
    assert len(failed) == 1
    assert failed[0].candidate_hash == stable_hash(invalid)
    assert failed[0].verification_fact_ref
    assert any(step.phase == "REPLAN" for step in result.steps)
    assert result.steps[-1].verification_fact_ref


@pytest.mark.asyncio
@pytest.mark.parametrize("early_final,invalid_tool", [(False, False), (True, False), (False, True)])
async def test_v2_frozen_candidate_executes_tools_and_repairs_within_actual_budget(early_final: bool, invalid_tool: bool) -> None:
    document = json.loads((Path(__file__).resolve().parents[1] / "contracts" /
                          "autospec-v5-agent-execution-v2-d.workflow.json").read_text())
    spec = WorkflowSpec.model_validate(document)
    node = next(n for n in spec.nodes if n.node_id == "backend_engineer")
    requirement, prd, architecture = _inputs()
    valid = BackendEngineerAgent().run(requirement, prd, architecture).model_dump(mode="json")
    payloads = []

    class Model:
        def generate_json(self, prompt_name, payload):
            assert prompt_name == "BackendEngineerAgent_v2"
            loop = payload["agent_loop"]
            assert "$defs" in loop["turn_schema"]
            tools = {t["name"]: t for t in loop["tools"]}
            assert set(tools) == {"knowledge.search", "artifact.get", "contract.lookup"}
            assert "artifact_id" in tools["artifact.get"]["input_schema"]["required"]
            payloads.append(payload)
            record_model_invocation(ModelInvocationTelemetry(provider_key="scripted", model_name="test",
                                                             prompt_key="backend_engineer_loop", status="SUCCEEDED"))
            if early_final:
                return {"turn_type": "FINAL_CANDIDATE", "candidate": valid}
            phase = loop["phase"]
            if phase == "PLAN":
                return {"turn_type": "PLAN", "goal": "design", "steps": ["lookup", "validate"]}
            if phase == "ACTION":
                return {"turn_type": "TOOL_CALL", "name": "contract.lookup", "version": "v1",
                        "arguments": {"node_id": [] if invalid_tool and len(payloads) == 2 else "backend_engineer"},
                        "reason": "verify contract"}
            if phase == "REPLAN":
                assert loop["candidate_to_repair"]["apis"] == []
                return {"turn_type": "REPLAN", "issue_codes": [i["code"] for i in loop["validation_issues"]],
                        "required_changes": [i["required_change"] for i in loop["validation_issues"]],
                        "reason": "repair deterministic failures"}
            candidate = valid if len(payloads) >= 5 else {**valid, "apis": []}
            if len(payloads) >= 5:
                assert loop["candidate_to_repair"]["apis"] == []
            return {"turn_type": "FINAL_CANDIDATE", "candidate": candidate}

    class Gateway:
        async def execute(self, request, **_kwargs):
            return ToolGatewayResult(request_id=request.request_id, idempotency_key=request.idempotency_key,
                                     status="SUCCEEDED", result={"node_id": "backend_engineer"},
                                     result_hash="a" * 64)

    registry = ToolRegistry()
    register_controlled_gateway_tools(registry, Gateway())
    contract = ModelExecutionContract(
        execution_id="v2-test", prompt_key=node.prompt_key, prompt_version=node.prompt_version,
        prompt_checksum=node.prompt_checksum, model_policy=node.model_policy.model_dump(mode="json"),
        deadline_epoch_ms=9_999_999_999_999, protocol_version=2, contract_hash="b" * 64,
        schema_version="BackendDesignArtifact", tool_policy=node.tool_policy.model_dump(mode="json"),
        agent_loop_policy=node.agent_loop_policy.model_dump(mode="json"),
    )
    context = ToolRuntimeContext(execution_id="v2-test", node_id="backend_engineer", attempt=1,
                                 policy=node.tool_policy, deadline_epoch_ms=contract.deadline_epoch_ms,
                                 contract_hash="b" * 64, schema_version="BackendDesignArtifact",
                                 harness=ToolHarness(registry), workflow_run_id=1, node_run_id=2,
                                 actor_user_id="1", project_id="1", fencing_token=1)
    with bind_model_execution_contract(contract), bind_tool_runtime_context(context), capture_model_invocations(
        execution_id="v2-test", max_model_calls=node.model_policy.max_calls
    ) as invocations:
        result = await run_backend_agent_loop(requirement=requirement, prd=prd,
            architecture_design=architecture.model_dump(mode="json"), retrieved_sources=[], context_manifest={},
            rework_directive=None, model_client=Model(), policy=node.agent_loop_policy)
    assert result.completed is (not early_final), [(s.phase, s.reason_code) for s in result.steps]
    if early_final:
        assert result.steps[-1].reason_code == "TURN_PHASE_INVALID"
    else:
        assert len([i for i in invocations if i.call_type == "MODEL"]) == 5 + int(invalid_tool)
        assert payloads[-1]["agent_loop"]["observations"][0]["tool"] == "contract.lookup"
        assert all(s.model_call_ref in {i.call_id for i in invocations if i.call_type == "MODEL"}
                   for s in result.steps if s.model_call_ref)
        assert {s.phase.value for s in result.steps} >= {"TOOL_CALL", "OBSERVATION", "REPLAN", "FINISH"}


def test_v2_rejects_unreachable_model_call_budget() -> None:
    from schemas.agent_loop import validate_loop_budget
    with pytest.raises(ValueError, match="at least 7"):
        validate_loop_budget(LoopPolicy(version="agent-loop-v2", enabled=True, max_steps=7, max_replans=2), 2, True)


@pytest.mark.asyncio
async def test_repeated_issue_codes_preserve_each_problem_and_allow_repair() -> None:
    requirement, prd, architecture = _inputs()
    valid = BackendEngineerAgent().run(requirement, prd, architecture).model_dump(mode="json")
    import copy
    invalid = copy.deepcopy(valid)
    assert len(invalid["apis"]) >= 2
    for api in invalid["apis"]:
        api["auth_required"] = True
        api["required_roles"] = []
    turns = 0
    class Model:
        def generate_json(self, _name, payload):
            nonlocal turns
            turns += 1
            loop = payload["agent_loop"]
            if turns == 1:
                return {"turn_type": "PLAN", "goal": "repair roles", "steps": ["draft", "validate"]}
            if turns == 2:
                return {"turn_type": "FINAL_CANDIDATE", "candidate": invalid}
            if turns == 3:
                assert len([i for i in loop["validation_issues"] if i["code"] == "AUTH_ROLE_MISSING"]) >= 2
                return {"turn_type": "REPLAN", "issue_codes": ["AUTH_ROLE_MISSING"],
                        "required_changes": ["repair every API role"], "reason": "missing roles"}
            assert len(loop["validation_issues"]) >= 2
            return {"turn_type": "FINAL_CANDIDATE", "candidate": valid}
    result = await run_backend_agent_loop(requirement=requirement, prd=prd,
        architecture_design=architecture.model_dump(mode="json"), retrieved_sources=[], context_manifest={},
        rework_directive=None, model_client=Model(),
        policy=LoopPolicy(version="agent-loop-v2", enabled=True, max_steps=6, max_replans=2))
    assert result.completed
