from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import asdict, dataclass, replace
from typing import Any, Iterator


class ModelCallBudgetExceeded(RuntimeError):
    error_code = "MODEL_CALL_BUDGET_EXCEEDED"


@dataclass(frozen=True)
class ModelInvocationTelemetry:
    provider_key: str
    model_name: str
    prompt_key: str
    call_id: str | None = None
    call_type: str = "MODEL"
    execution_id: str | None = None
    call_sequence: int | None = None
    attempt: int | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = None
    schema_version: str | None = None
    contract_hash: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    cache_tokens: int = 0
    estimated_cost: float = 0.0
    reserved_input_tokens: int = 0
    reserved_output_tokens: int = 0
    reserved_cost: float = 0.0
    route_key: str | None = None
    route_reason: str | None = None
    fallback_used: bool = False
    normalized_params_hash: str | None = None
    result_hash: str | None = None
    status: str = "SUCCEEDED"
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: int = 0
    deadline_epoch_ms: int | None = None
    idempotency_key: str | None = None
    tool_name: str | None = None
    tool_version: str | None = None
    permission_policy: str | None = None
    reference_sources: list[str] | None = None
    redacted_params: dict[str, Any] | None = None


@dataclass(frozen=True)
class ModelRoutingDecision:
    route_key: str
    route_reason: str
    fallback_used: bool = False


@dataclass(frozen=True)
class InvocationPermit:
    call_id: str
    call_sequence: int
    execution_id: str | None
    attempt: int
    deadline_epoch_ms: int | None


@dataclass
class _CollectorState:
    invocations: list[ModelInvocationTelemetry]
    execution_id: str | None
    attempt: int
    max_model_calls: int | None
    deadline_epoch_ms: int | None
    next_sequence: int = 0


_STATE: ContextVar[_CollectorState | None] = ContextVar(
    "autospec_model_invocation_collector",
    default=None,
)
_ROUTING_DECISION: ContextVar[ModelRoutingDecision | None] = ContextVar(
    "autospec_model_routing_decision",
    default=None,
)


@contextmanager
def capture_model_invocations(
    *,
    execution_id: str | None = None,
    attempt: int = 1,
    max_model_calls: int | None = None,
    deadline_epoch_ms: int | None = None,
) -> Iterator[list[ModelInvocationTelemetry]]:
    state = _CollectorState(
        invocations=[],
        execution_id=execution_id,
        attempt=max(1, attempt),
        max_model_calls=max_model_calls,
        deadline_epoch_ms=deadline_epoch_ms,
    )
    token = _STATE.set(state)
    try:
        yield state.invocations
    finally:
        _STATE.reset(token)


@contextmanager
def bind_model_routing_decision(
    decision: ModelRoutingDecision,
) -> Iterator[None]:
    token = _ROUTING_DECISION.set(decision)
    try:
        yield
    finally:
        _ROUTING_DECISION.reset(token)


def begin_model_invocation(call_type: str = "MODEL") -> InvocationPermit:
    state = _STATE.get()
    if state is None:
        return InvocationPermit(
            call_id=f"standalone:{call_type.lower()}:1",
            call_sequence=1,
            execution_id=None,
            attempt=1,
            deadline_epoch_ms=None,
        )
    if (
        call_type.upper() == "MODEL"
        and state.max_model_calls is not None
        and sum(
            1
            for invocation in state.invocations
            if invocation.call_type.upper() == "MODEL"
        )
        + 1
        > state.max_model_calls
    ):
        raise ModelCallBudgetExceeded(
            "Frozen model call limit was exhausted before provider invocation"
        )
    state.next_sequence += 1
    prefix = state.execution_id or "standalone"
    return InvocationPermit(
        call_id=f"{prefix}:{call_type.lower()}:{state.next_sequence}",
        call_sequence=state.next_sequence,
        execution_id=state.execution_id,
        attempt=state.attempt,
        deadline_epoch_ms=state.deadline_epoch_ms,
    )


def record_model_invocation(invocation: ModelInvocationTelemetry) -> None:
    permit: InvocationPermit | None = None
    if invocation.call_sequence is None or invocation.call_id is None:
        permit = begin_model_invocation(invocation.call_type)
    decision = _ROUTING_DECISION.get()
    state = _STATE.get()
    from runtime.execution_context import current_model_execution_contract

    contract = current_model_execution_contract()
    call_id = invocation.call_id or (permit.call_id if permit else None)
    reserved_input_tokens = invocation.reserved_input_tokens
    reserved_output_tokens = invocation.reserved_output_tokens
    reserved_cost = invocation.reserved_cost
    if (
        invocation.call_type.upper() == "MODEL"
        and contract is not None
        and contract.protocol_version >= 2
    ):
        reserved_input_tokens = reserved_input_tokens or int(
            contract.context_policy.get("max_input_tokens", 0)
        )
        reserved_output_tokens = reserved_output_tokens or int(
            contract.model_policy.get("max_output_tokens", 0)
        )
        input_rate = max(
            float(contract.model_policy.get("input_cost_per_million", 0.0)),
            float(
                contract.model_policy.get(
                    "cached_input_cost_per_million",
                    contract.model_policy.get("input_cost_per_million", 0.0),
                )
            ),
        )
        output_rate = float(
            contract.model_policy.get("output_cost_per_million", 0.0)
        )
        reserved_cost = reserved_cost or (
            reserved_input_tokens * input_rate
            + reserved_output_tokens * output_rate
        ) / 1_000_000
    invocation = replace(
        invocation,
        call_id=call_id,
        execution_id=(
            invocation.execution_id
            or (permit.execution_id if permit else None)
            or (state.execution_id if state else None)
        ),
        call_sequence=(
            invocation.call_sequence
            or (permit.call_sequence if permit else None)
        ),
        attempt=(
            invocation.attempt
            or (permit.attempt if permit else None)
            or (state.attempt if state else 1)
        ),
        prompt_version=(
            invocation.prompt_version
            or (contract.prompt_version if contract is not None else None)
        ),
        prompt_checksum=(
            invocation.prompt_checksum
            or (contract.prompt_checksum if contract is not None else None)
        ),
        schema_version=(
            invocation.schema_version
            or (contract.schema_version if contract is not None else None)
        ),
        contract_hash=(
            invocation.contract_hash
            or (contract.contract_hash if contract is not None else None)
        ),
        reserved_input_tokens=reserved_input_tokens,
        reserved_output_tokens=reserved_output_tokens,
        reserved_cost=reserved_cost,
        deadline_epoch_ms=(
            invocation.deadline_epoch_ms
            or (permit.deadline_epoch_ms if permit else None)
            or (state.deadline_epoch_ms if state else None)
        ),
        route_key=(
            invocation.route_key
            or (decision.route_key if decision is not None else None)
        ),
        route_reason=(
            invocation.route_reason
            or (decision.route_reason if decision is not None else None)
        ),
        fallback_used=(
            invocation.fallback_used
            or (decision.fallback_used if decision is not None else False)
        ),
        idempotency_key=(invocation.idempotency_key or call_id),
    )
    if state is not None:
        state.invocations.append(invocation)


def captured_invocation_count() -> int:
    state = _STATE.get()
    return len(state.invocations) if state is not None else 0


def serialize_invocations(
    invocations: list[ModelInvocationTelemetry],
) -> list[dict[str, Any]]:
    return [asdict(invocation) for invocation in invocations]


def summarize_model_invocations(
    invocations: list[ModelInvocationTelemetry],
) -> dict[str, int | float | str | bool | None | list[dict[str, Any]]]:
    model_invocations = [
        invocation
        for invocation in invocations
        if invocation.call_type.upper() == "MODEL"
    ]
    tool_invocations = [
        invocation
        for invocation in invocations
        if invocation.call_type.upper() == "TOOL"
    ]
    if not model_invocations:
        return {
            "provider_key": None,
            "model_name": None,
            "prompt_key": None,
            "prompt_version": None,
            "prompt_checksum": None,
            "route_key": None,
            "route_reason": None,
            "fallback_used": False,
            "model_call_count": 0,
            "tool_call_count": len(tool_invocations),
            "input_tokens": 0,
            "output_tokens": 0,
            "cache_tokens": 0,
            "estimated_cost": round(
                sum(max(0.0, item.estimated_cost) for item in tool_invocations),
                8,
            ),
            "call_records": serialize_invocations(invocations),
        }
    providers = {item.provider_key for item in model_invocations}
    models = {item.model_name for item in model_invocations}
    prompts = {item.prompt_key for item in model_invocations}
    prompt_versions = {
        item.prompt_version for item in model_invocations if item.prompt_version
    }
    prompt_checksums = {
        item.prompt_checksum for item in model_invocations if item.prompt_checksum
    }
    routes = {item.route_key for item in model_invocations if item.route_key}
    reasons = {item.route_reason for item in model_invocations if item.route_reason}
    return {
        "provider_key": next(iter(providers)) if len(providers) == 1 else "multiple",
        "model_name": next(iter(models)) if len(models) == 1 else "multiple",
        "prompt_key": next(iter(prompts)) if len(prompts) == 1 else "multiple",
        "prompt_version": (
            next(iter(prompt_versions))
            if len(prompt_versions) == 1
            else "multiple" if prompt_versions else None
        ),
        "prompt_checksum": (
            next(iter(prompt_checksums))
            if len(prompt_checksums) == 1
            else "multiple" if prompt_checksums else None
        ),
        "route_key": (
            next(iter(routes)) if len(routes) == 1 else "multiple" if routes else None
        ),
        "route_reason": (
            next(iter(reasons))
            if len(reasons) == 1
            else "multiple" if reasons else None
        ),
        "fallback_used": any(item.fallback_used for item in model_invocations),
        "model_call_count": len(model_invocations),
        "tool_call_count": len(tool_invocations),
        "input_tokens": sum(max(0, item.input_tokens) for item in model_invocations),
        "output_tokens": sum(max(0, item.output_tokens) for item in model_invocations),
        "cache_tokens": sum(max(0, item.cache_tokens) for item in model_invocations),
        "estimated_cost": round(
            sum(max(0.0, item.estimated_cost) for item in invocations),
            8,
        ),
        "call_records": serialize_invocations(invocations),
    }
