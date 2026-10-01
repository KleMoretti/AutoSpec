import asyncio
import hashlib
import json

import pytest
from pydantic import BaseModel

from evaluation.tool_injection_dataset import tool_injection_cases
from runtime.tool_gateway import InMemoryToolGateway
from runtime.tool_harness import ToolHarness, ToolRegistry
from schemas.tool_gateway import ToolGatewayRequest
from schemas.workflow_spec import ToolPolicy, ToolRef


class Input(BaseModel):
    query: str


class Output(BaseModel):
    answer: str


def _request(case, policy_hash: str, *, arguments: dict[str, object] | None = None) -> ToolGatewayRequest:
    payload = arguments if arguments is not None else case.arguments
    normalized = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return ToolGatewayRequest(
        request_id=f"request:{case.case_id}",
        execution_id="run:injection:1",
        workflow_run_id=7,
        node_run_id=8,
        node_id="architect",
        actor_user_id="user-1",
        project_id="project-7",
        fencing_token=1,
        deadline_epoch_ms=9_999_999_999_999,
        policy_hash=policy_hash,
        idempotency_key=case.idempotency_key,
        name=case.name,
        version=case.version,
        arguments=payload,
        normalized_params_hash=normalized,
        max_result_bytes=32_000,
    )


def _policy() -> ToolPolicy:
    return ToolPolicy(
        enabled=True,
        allowed_tools=[ToolRef(name="knowledge.search", version="v1")],
        max_calls=8,
    )


def test_injection_dataset_is_stable_and_balanced() -> None:
    cases = tool_injection_cases()
    assert len(cases) == 30
    assert len({case.case_id for case in cases}) == 30
    assert {case.family for case in cases} == {
        "UNKNOWN_TOOL", "INVALID_ARGUMENTS", "IDEMPOTENCY_CONFLICT", "SCOPE_ESCAPE", "PERMISSION_ESCAPE"
    }
    assert all(sum(case.family == family for case in cases) == 6 for family in {case.family for case in cases})


@pytest.mark.parametrize("case", tool_injection_cases(), ids=lambda case: case.case_id)
def test_injection_rows_are_rejected_at_the_declared_boundary(case) -> None:
    if case.family == "UNKNOWN_TOOL":
        with pytest.raises(ValueError, match="controlled gateway catalog"):
            _request(case, "a" * 64)
        return
    assert case.expected_error_code in {
        "TOOL_INPUT_INVALID", "IDEMPOTENCY_CONFLICT", "TOOL_SCOPE_DENIED", "TOOL_PERMISSION_DENIED"
    }


@pytest.mark.asyncio
async def test_representative_injection_boundaries_return_structured_errors() -> None:
    registry = ToolRegistry()
    registry.register("knowledge.search", "v1", Input, Output, lambda value: Output(answer=value.query))
    policy = _policy()

    invalid = next(case for case in tool_injection_cases() if case.family == "INVALID_ARGUMENTS")
    invalid_gateway = InMemoryToolGateway(ToolHarness(registry))
    invalid_result = await invalid_gateway.execute(_request(invalid, invalid_gateway.register_policy(policy)), policy=policy)
    assert invalid_result.error_code == "TOOL_INPUT_INVALID"

    scope = next(case for case in tool_injection_cases() if case.family == "SCOPE_ESCAPE")
    scope_gateway = InMemoryToolGateway(ToolHarness(registry), scope_checker=lambda _: False)
    scope_result = await scope_gateway.execute(_request(scope, scope_gateway.register_policy(policy)), policy=policy)
    assert scope_result.error_code == "TOOL_SCOPE_DENIED"

    permission = next(case for case in tool_injection_cases() if case.family == "PERMISSION_ESCAPE")
    permission_gateway = InMemoryToolGateway(
        ToolHarness(registry, permission_checker=lambda _: False)
    )
    permission_result = await permission_gateway.execute(
        _request(permission, permission_gateway.register_policy(policy)), policy=policy
    )
    assert permission_result.error_code == "TOOL_PERMISSION_DENIED"

    conflict = next(case for case in tool_injection_cases() if case.family == "IDEMPOTENCY_CONFLICT")
    conflict_gateway = InMemoryToolGateway(ToolHarness(registry))
    conflict_hash = conflict_gateway.register_policy(policy)
    first = await conflict_gateway.execute(
        _request(conflict, conflict_hash, arguments={"query": "first"}), policy=policy
    )
    second = await conflict_gateway.execute(
        _request(conflict, conflict_hash, arguments={"query": "second"}), policy=policy
    )
    assert first.status == "SUCCEEDED"
    assert second.error_code == "IDEMPOTENCY_CONFLICT"
