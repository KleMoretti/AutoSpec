import pytest
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
from schemas.agent_loop import LoopPolicy, StopReason
from schemas.workflow_spec import ToolPolicy, ToolRef


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
        prompt_key="backend_engineer",
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

    with bind_model_execution_contract(contract), bind_tool_runtime_context(tool_context):
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
