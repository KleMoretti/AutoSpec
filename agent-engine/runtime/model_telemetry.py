from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from typing import Iterator


@dataclass(frozen=True)
class ModelInvocationTelemetry:
    provider_key: str
    model_name: str
    prompt_key: str
    prompt_version: str | None = None
    prompt_checksum: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    cache_tokens: int = 0
    estimated_cost: float = 0.0
    route_key: str | None = None
    route_reason: str | None = None
    fallback_used: bool = False


@dataclass(frozen=True)
class ModelRoutingDecision:
    route_key: str
    route_reason: str
    fallback_used: bool = False


_COLLECTOR: ContextVar[list[ModelInvocationTelemetry] | None] = ContextVar(
    "autospec_model_invocation_collector",
    default=None,
)
_ROUTING_DECISION: ContextVar[ModelRoutingDecision | None] = ContextVar(
    "autospec_model_routing_decision",
    default=None,
)


@contextmanager
def capture_model_invocations() -> Iterator[list[ModelInvocationTelemetry]]:
    collector: list[ModelInvocationTelemetry] = []
    token = _COLLECTOR.set(collector)
    try:
        yield collector
    finally:
        _COLLECTOR.reset(token)


@contextmanager
def bind_model_routing_decision(
    decision: ModelRoutingDecision,
) -> Iterator[None]:
    token = _ROUTING_DECISION.set(decision)
    try:
        yield
    finally:
        _ROUTING_DECISION.reset(token)


def record_model_invocation(invocation: ModelInvocationTelemetry) -> None:
    decision = _ROUTING_DECISION.get()
    if decision is not None and invocation.route_key is None:
        invocation = replace(
            invocation,
            route_key=decision.route_key,
            route_reason=decision.route_reason,
            fallback_used=decision.fallback_used,
        )
    collector = _COLLECTOR.get()
    if collector is not None:
        collector.append(invocation)


def summarize_model_invocations(
    invocations: list[ModelInvocationTelemetry],
) -> dict[str, int | float | str | bool | None]:
    if not invocations:
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
            "input_tokens": 0,
            "output_tokens": 0,
            "cache_tokens": 0,
            "estimated_cost": 0.0,
        }
    providers = {item.provider_key for item in invocations}
    models = {item.model_name for item in invocations}
    prompts = {item.prompt_key for item in invocations}
    prompt_versions = {item.prompt_version for item in invocations if item.prompt_version}
    prompt_checksums = {item.prompt_checksum for item in invocations if item.prompt_checksum}
    routes = {item.route_key for item in invocations if item.route_key}
    reasons = {item.route_reason for item in invocations if item.route_reason}
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
        "route_key": next(iter(routes)) if len(routes) == 1 else "multiple" if routes else None,
        "route_reason": next(iter(reasons)) if len(reasons) == 1 else "multiple" if reasons else None,
        "fallback_used": any(item.fallback_used for item in invocations),
        "model_call_count": len(invocations),
        "input_tokens": sum(max(0, item.input_tokens) for item in invocations),
        "output_tokens": sum(max(0, item.output_tokens) for item in invocations),
        "cache_tokens": sum(max(0, item.cache_tokens) for item in invocations),
        "estimated_cost": round(
            sum(max(0.0, item.estimated_cost) for item in invocations), 8
        ),
    }
