import time

import pytest

from fixtures.software_domains import get_fixture
from runtime.execution_context import ModelExecutionContract, bind_model_execution_contract
from runtime.backend_agent_loop import _verify_backend_candidate
from runtime.production_handlers import build_production_registry
from runtime.tool_gateway import register_controlled_gateway_tools
from runtime.tool_harness import (
    ToolHarness,
    ToolRegistry,
    ToolRuntimeContext,
    bind_tool_runtime_context,
)
from schemas.tool_gateway import ToolGatewayResult
from schemas.workflow_spec import ToolPolicy
from spec_verifier.fixtures import spec_contract_from_artifacts
from spec_verifier.validators import validate_l1
from test_explicit_contract_adapter import _explicit_fixture


class FixtureVerifierGateway:
    async def execute(self, request, *, policy=None):
        del policy
        report = validate_l1(
            request.arguments["contract"],
            execution_id=request.execution_id,
            scope=request.arguments.get("scope", "FULL"),
        )
        return ToolGatewayResult(
            request_id=request.request_id,
            idempotency_key=request.idempotency_key,
            status="SUCCEEDED",
            result=report.model_dump(mode="json"),
            result_hash="a" * 64,
        )


@pytest.mark.asyncio
async def test_candidate_reviewer_passes_trusted_verification_fact_to_report() -> None:
    fixture = get_fixture("campus_marketplace")
    registry = build_production_registry()
    registration = registry.resolve("ReviewerAgent", "v3")

    tool_registry = ToolRegistry()
    register_controlled_gateway_tools(tool_registry, FixtureVerifierGateway())
    policy = ToolPolicy.model_validate(
        {
            "version": "tools-v1",
            "enabled": True,
            "allowed_tools": [{"name": "spec.verify", "version": "v1"}],
            "max_calls": 1,
            "per_call_timeout_ms": 30_000,
            "total_timeout_ms": 30_000,
            "max_result_bytes": 32_000,
            "allowed_side_effects": ["SANDBOXED"],
            "permission_policy": "workflow",
        }
    )
    verification_policy = {
        "enabled": True,
        "scope": "FULL",
        "required_level": "L1",
        "rule_profile": "spec-full-v1",
        "verifier_version": "spec-verifier-v1",
        "compiler_version": "spec-compiler-v1",
        "timeout_ms": 30_000,
    }
    execution = ModelExecutionContract(
        execution_id="1:reviewer:1:1",
        prompt_key="reviewer_shared",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={},
        deadline_epoch_ms=int(time.time() * 1000) + 30_000,
        tool_policy=policy.model_dump(mode="json"),
        verification_policy=verification_policy,
    )
    context = ToolRuntimeContext(
        execution_id=execution.execution_id,
        node_id="reviewer",
        attempt=1,
        policy=policy,
        deadline_epoch_ms=execution.deadline_epoch_ms,
        contract_hash="b" * 64,
        schema_version="ReviewReportV2",
        harness=ToolHarness(tool_registry),
        workflow_run_id=1,
        node_run_id=2,
        actor_user_id="3",
        project_id="4",
        fencing_token=1,
    )
    payload = registration.input_model.model_validate(
        {
            "requirement": "Build a campus marketplace",
            "prd": fixture.prd.model_dump(mode="json"),
            "architecture_design": fixture.shared_architecture().model_dump(mode="json"),
            "backend_design": fixture.backend.model_dump(mode="json"),
            "frontend_skeleton": fixture.frontend.model_dump(mode="json"),
        }
    )

    with bind_model_execution_contract(execution), bind_tool_runtime_context(context):
        result = await registration.handler(payload)

    assert result["verification_fact"]["status"] == "PASSED"
    assert result["verification_fact"]["achieved_level"] == "L1"
    assert result["verification_fact"]["workflow_run_id"] == 1


@pytest.mark.asyncio
async def test_candidate_reviewer_uses_explicit_contract_adapter_for_v2_policy() -> None:
    prd, backend, frontend = _explicit_fixture()
    fixture = get_fixture("campus_marketplace")
    registry = build_production_registry()
    registration = registry.resolve("ReviewerAgent", "v3")

    tool_registry = ToolRegistry()
    register_controlled_gateway_tools(tool_registry, FixtureVerifierGateway())
    policy = ToolPolicy.model_validate(
        {
            "version": "tools-v1",
            "enabled": True,
            "allowed_tools": [{"name": "spec.verify", "version": "v1"}],
            "max_calls": 1,
            "per_call_timeout_ms": 30_000,
            "total_timeout_ms": 30_000,
            "max_result_bytes": 32_000,
            "allowed_side_effects": ["SANDBOXED"],
            "permission_policy": "workflow",
        }
    )
    execution = ModelExecutionContract(
        execution_id="1:reviewer:explicit:1",
        prompt_key="reviewer_shared",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={},
        deadline_epoch_ms=int(time.time() * 1000) + 30_000,
        tool_policy=policy.model_dump(mode="json"),
        verification_policy={
            "enabled": True,
            "scope": "FULL",
            "required_level": "L1",
            "rule_profile": "spec-full-v1",
            "verifier_version": "spec-verifier-v2",
            "compiler_version": "spec-compiler-v1",
            "timeout_ms": 30_000,
        },
    )
    context = ToolRuntimeContext(
        execution_id=execution.execution_id,
        node_id="reviewer",
        attempt=1,
        policy=policy,
        deadline_epoch_ms=execution.deadline_epoch_ms,
        contract_hash="b" * 64,
        schema_version="ReviewReportV2",
        harness=ToolHarness(tool_registry),
        workflow_run_id=1,
        node_run_id=2,
        actor_user_id="3",
        project_id="4",
        fencing_token=1,
    )
    payload = registration.input_model.model_validate(
        {
            "requirement": "Build a campus marketplace",
            "prd": prd.model_dump(mode="json"),
            "architecture_design": fixture.shared_architecture().model_dump(mode="json"),
            "backend_design": backend.model_dump(mode="json"),
            "frontend_skeleton": frontend.model_dump(mode="json"),
        }
    )

    with bind_model_execution_contract(execution), bind_tool_runtime_context(context):
        result = await registration.handler(payload)

    assert result["verification_fact"]["status"] == "PASSED"
    assert result["verification_fact"]["achieved_level"] == "L1"


@pytest.mark.asyncio
async def test_backend_verifier_uses_explicit_contract_adapter_for_v2_policy() -> None:
    prd, backend, _frontend = _explicit_fixture()
    tool_registry = ToolRegistry()
    register_controlled_gateway_tools(tool_registry, FixtureVerifierGateway())
    policy = ToolPolicy.model_validate(
        {
            "version": "tools-v1",
            "enabled": True,
            "allowed_tools": [{"name": "spec.verify", "version": "v1"}],
            "max_calls": 1,
            "per_call_timeout_ms": 30_000,
            "total_timeout_ms": 30_000,
            "max_result_bytes": 32_000,
            "allowed_side_effects": ["SANDBOXED"],
            "permission_policy": "workflow",
        }
    )
    execution = ModelExecutionContract(
        execution_id="1:backend:explicit:1",
        prompt_key="backend_engineer_loop",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={},
        deadline_epoch_ms=int(time.time() * 1000) + 30_000,
        tool_policy=policy.model_dump(mode="json"),
        verification_policy={
            "enabled": True,
            "scope": "BACKEND",
            "required_level": "L1",
            "rule_profile": "spec-backend-v1",
            "verifier_version": "spec-verifier-v2",
            "compiler_version": "spec-compiler-v1",
            "timeout_ms": 30_000,
        },
    )
    context = ToolRuntimeContext(
        execution_id=execution.execution_id,
        node_id="backend_engineer",
        attempt=1,
        policy=policy,
        deadline_epoch_ms=execution.deadline_epoch_ms,
        contract_hash="b" * 64,
        schema_version="ExplicitBackendDesignArtifact",
        harness=ToolHarness(tool_registry),
        workflow_run_id=1,
        node_run_id=2,
        actor_user_id="3",
        project_id="4",
        fencing_token=1,
    )

    with bind_model_execution_contract(execution), bind_tool_runtime_context(context):
        report, fact = await _verify_backend_candidate(backend, prd)

    assert report.status == "PASSED"
    assert fact.status == "PASSED"


def test_backend_contract_infers_conventional_entity_primary_key_names() -> None:
    fixture = get_fixture("campus_marketplace")
    backend = fixture.backend.model_copy(deep=True)
    table = backend.tables[0]
    table.fields[0].name = f"{table.name.rsplit('_', 1)[-1]}_id"

    contract = spec_contract_from_artifacts(fixture.prd, backend)
    converted = next(item for item in contract.tables if item.name == table.name)

    assert [field.name for field in converted.fields if field.primary_key] == [
        table.fields[0].name
    ]


def test_backend_contract_uses_explicit_field_id_when_table_name_is_composite() -> None:
    fixture = get_fixture("campus_marketplace")
    backend = fixture.backend.model_copy(deep=True)
    table = backend.tables[0]
    table.name = "activity_entry"
    table.fields[0].name = "activity_id"
    table.fields[0].field_id = "FIELD-ACT-ID"
    table.fields[1].name = "workspace_id"
    table.fields[1].field_id = "FIELD-ACT-WORKSPACE-ID"

    contract = spec_contract_from_artifacts(fixture.prd, backend)
    converted = next(item for item in contract.tables if item.name == table.name)

    assert [field.name for field in converted.fields if field.primary_key] == ["activity_id"]


def test_backend_contract_does_not_treat_entity_primary_key_as_foreign_key() -> None:
    fixture = get_fixture("campus_marketplace")
    backend = fixture.backend.model_copy(deep=True)
    entity_table = backend.tables[0]
    entity_table.name = "delivery_report"
    entity_table.fields[0].name = "report_id"
    entity_table.fields[0].field_id = "FIELD-REP-ID"
    backend.tables[1].name = "report_item"

    contract = spec_contract_from_artifacts(fixture.prd, backend)
    converted = next(item for item in contract.tables if item.name == "delivery_report")
    primary_key = next(field for field in converted.fields if field.primary_key)

    assert primary_key.name == "report_id"
    assert primary_key.foreign_key is None
