from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from time import monotonic
from typing import Any, Callable, Iterator, Protocol
from contextvars import ContextVar

from pydantic import BaseModel, ValidationError

from runtime.model_telemetry import (
    ModelInvocationTelemetry,
    begin_model_invocation,
    record_model_invocation,
)
from schemas.tool import ToolCallRequest, ToolExecutionResult
from schemas.workflow_spec import ToolPolicy


class ToolRuntimeError(RuntimeError):
    error_code = "TOOL_RUNTIME_ERROR"


class ToolInputError(ToolRuntimeError):
    error_code = "TOOL_INPUT_INVALID"


class ToolNotAllowedError(ToolRuntimeError):
    error_code = "TOOL_NOT_ALLOWED"


class ToolPermissionError(ToolRuntimeError):
    error_code = "TOOL_PERMISSION_DENIED"


class ToolTimeoutError(ToolRuntimeError):
    error_code = "TOOL_TIMEOUT"


class ToolRateLimitError(ToolRuntimeError):
    error_code = "TOOL_RATE_LIMITED"


class ToolCircuitOpenError(ToolRuntimeError):
    error_code = "TOOL_CIRCUIT_OPEN"


class ToolResultError(ToolRuntimeError):
    error_code = "TOOL_RESULT_INVALID"


class ToolIdempotencyStore(Protocol):
    async def get(self, key: str) -> ToolExecutionResult | None: ...

    async def put(self, key: str, result: ToolExecutionResult) -> None: ...


class InMemoryToolIdempotencyStore:
    def __init__(self) -> None:
        self._values: dict[str, ToolExecutionResult] = {}
        self._lock = asyncio.Lock()

    async def get(self, key: str) -> ToolExecutionResult | None:
        async with self._lock:
            value = self._values.get(key)
            return value.model_copy(deep=True) if value is not None else None

    async def put(self, key: str, result: ToolExecutionResult) -> None:
        async with self._lock:
            self._values.setdefault(key, result.model_copy(deep=True))


@dataclass(frozen=True)
class ToolRegistration:
    name: str
    version: str
    description: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    handler: Callable[[BaseModel], Any]
    permission_policy: str = "workflow"
    side_effect: str = "READ_ONLY"


class ToolRegistry:
    def __init__(self) -> None:
        self._registrations: dict[tuple[str, str], ToolRegistration] = {}

    def register(
        self,
        name: str,
        version: str,
        input_model: type[BaseModel],
        output_model: type[BaseModel],
        handler: Callable[[BaseModel], Any],
        *,
        description: str = "",
        permission_policy: str = "workflow",
        side_effect: str = "READ_ONLY",
    ) -> None:
        key = (name, version)
        if key in self._registrations:
            raise ValueError(f"tool already registered: {name}:{version}")
        if side_effect not in {"READ_ONLY", "DETERMINISTIC", "WRITE"}:
            raise ValueError(f"unsupported tool side effect: {side_effect}")
        self._registrations[key] = ToolRegistration(
            name,
            version,
            description,
            input_model,
            output_model,
            handler,
            permission_policy,
            side_effect,
        )

    def describe(self) -> list[dict[str, Any]]:
        return [
            {
                "name": registration.name,
                "version": registration.version,
                "description": registration.description,
                "input_schema": registration.input_model.model_json_schema(),
                "output_schema": registration.output_model.model_json_schema(),
                "permission_policy": registration.permission_policy,
                "side_effect": registration.side_effect,
            }
            for registration in sorted(
                self._registrations.values(), key=lambda item: (item.name, item.version)
            )
        ]

    def resolve(self, name: str, version: str) -> ToolRegistration:
        try:
            return self._registrations[(name, version)]
        except KeyError as exc:
            raise ToolNotAllowedError(f"unknown tool: {name}:{version}") from exc


class SlidingWindowRateLimiter:
    def __init__(
        self,
        limit: int,
        window_seconds: float = 1.0,
        *,
        clock: Callable[[], float] | None = None,
    ) -> None:
        if limit < 1 or window_seconds <= 0:
            raise ValueError("rate limiter limit and window must be positive")
        self._limit = limit
        self._window_seconds = window_seconds
        self._clock = clock or monotonic
        self._timestamps: list[float] = []
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        now = self._clock()
        async with self._lock:
            self._timestamps = [
                value
                for value in self._timestamps
                if now - value < self._window_seconds
            ]
            if len(self._timestamps) >= self._limit:
                raise ToolRateLimitError("tool runtime rate limit exceeded")
            self._timestamps.append(now)


@dataclass
class ToolRuntimeContext:
    execution_id: str | None
    node_id: str
    attempt: int
    policy: ToolPolicy
    deadline_epoch_ms: int | None
    contract_hash: str | None
    schema_version: str | None
    harness: "ToolHarness"
    started_at: float = field(default_factory=monotonic)
    calls_used: int = 0
    workflow_run_id: int = 0
    node_run_id: int = 0
    actor_user_id: str | None = None
    project_id: str | None = None
    fencing_token: int = 0
    execution_bundle_hash: str | None = None
    correlation_id: str | None = None
    traceparent: str | None = None
    tracestate: str | None = None


_TOOL_CONTEXT: ContextVar[ToolRuntimeContext | None] = ContextVar(
    "autospec_tool_runtime_context",
    default=None,
)


@contextmanager
def bind_tool_runtime_context(context: ToolRuntimeContext) -> Iterator[None]:
    token = _TOOL_CONTEXT.set(context)
    try:
        yield
    finally:
        _TOOL_CONTEXT.reset(token)


def current_tool_harness() -> "ToolHarness | None":
    context = _TOOL_CONTEXT.get()
    return context.harness if context is not None else None


def current_tool_runtime_context() -> ToolRuntimeContext | None:
    return _TOOL_CONTEXT.get()


async def execute_current_tool(
    request: ToolCallRequest | dict[str, Any],
    *,
    repair: Callable[[ToolCallRequest, str], Any] | None = None,
) -> ToolExecutionResult:
    context = _TOOL_CONTEXT.get()
    if context is None:
        raise ToolRuntimeError("tool execution is only available inside a node context")
    return await context.harness.execute(request, repair=repair)


class ToolHarness:
    """Bounded tool execution for worker handlers.

    Tools are executable only when registered and explicitly present in the
    frozen node policy. No shell, HTTP, or database fallback is provided.
    """

    def __init__(
        self,
        registry: ToolRegistry,
        *,
        idempotency_store: ToolIdempotencyStore | None = None,
        permission_checker: Callable[[ToolRegistration], bool] | None = None,
        rate_limiter: SlidingWindowRateLimiter | None = None,
        circuit_failure_threshold: int = 3,
        circuit_cooldown_seconds: float = 30.0,
        sleep: Callable[[float], Any] | None = None,
    ) -> None:
        if circuit_failure_threshold < 1 or circuit_cooldown_seconds <= 0:
            raise ValueError("circuit breaker settings must be positive")
        self._registry = registry
        self._idempotency = idempotency_store or InMemoryToolIdempotencyStore()
        self._permission_checker = permission_checker or (lambda _registration: True)
        self._rate_limiter = rate_limiter
        self._circuit_failure_threshold = circuit_failure_threshold
        self._circuit_cooldown_seconds = circuit_cooldown_seconds
        self._sleep = sleep or asyncio.sleep
        self._failures: dict[tuple[str, str], int] = {}
        self._open_until: dict[tuple[str, str], float] = {}
        self._circuit_lock = asyncio.Lock()

    async def execute(
        self,
        request: ToolCallRequest | dict[str, Any],
        *,
        policy: ToolPolicy | dict[str, Any] | None = None,
        repair: Callable[[ToolCallRequest, str], Any] | None = None,
    ) -> ToolExecutionResult:
        context = _TOOL_CONTEXT.get()
        frozen_policy = (
            context.policy
            if context is not None
            else ToolPolicy.model_validate(policy or {})
        )
        call = ToolCallRequest.model_validate(request)
        registration = self._registry.resolve(call.name, call.version)
        try:
            self._check_policy(frozen_policy, registration)
            if registration.permission_policy != frozen_policy.permission_policy:
                raise ToolPermissionError(
                    "tool permission policy does not match the frozen node policy"
                )
            if not self._permission_checker(registration):
                raise ToolPermissionError(
                    f"permission policy denied tool {registration.name}:{registration.version}"
                )
            call = await self._validate_or_repair(call, registration, repair)
        except ToolRuntimeError as error:
            self._record_error(context, registration, call, error, 0)
            raise
        idempotency_key = call.idempotency_key or self._derived_key(call, context)
        call = call.model_copy(update={"idempotency_key": idempotency_key})
        cached = await self._idempotency.get(idempotency_key)
        if cached is not None:
            cached.cached = True
            self._record(
                context,
                registration,
                call,
                status="SUCCEEDED",
                result=cached.result,
                duration_ms=0,
            )
            return cached

        if context is not None:
            context.calls_used += 1
            if context.calls_used > frozen_policy.max_calls:
                error = ToolRateLimitError("frozen tool call limit was exhausted")
                self._record_error(context, registration, call, error, 0)
                raise error

        try:
            await self._check_circuit(registration)
        except ToolRuntimeError as error:
            self._record_error(context, registration, call, error, 0)
            raise
        attempts = frozen_policy.retry_policy.max_attempts
        for attempt in range(1, attempts + 1):
            started = monotonic()
            try:
                if self._rate_limiter is not None:
                    await self._rate_limiter.acquire()
                timeout = self._timeout_seconds(frozen_policy, context)
                value = await asyncio.wait_for(
                    self._invoke(registration.handler, registration.input_model, call.arguments),
                    timeout=timeout,
                )
                result = self._validate_result(registration, value, frozen_policy)
            except asyncio.TimeoutError as error:
                failure: ToolRuntimeError = ToolTimeoutError(
                    f"tool exceeded timeout of {frozen_policy.per_call_timeout_ms} ms"
                )
                self._record_error(context, registration, call, failure, _elapsed(started))
                await self._record_failure(registration)
                if attempt < attempts and self._retryable(failure, frozen_policy):
                    await self._backoff(frozen_policy, attempt)
                    continue
                raise failure from error
            except ToolRuntimeError as error:
                self._record_error(context, registration, call, error, _elapsed(started))
                await self._record_failure(registration)
                if attempt < attempts and self._retryable(error, frozen_policy):
                    await self._backoff(frozen_policy, attempt)
                    continue
                raise
            except Exception as error:  # noqa: BLE001 - normalized at the tool boundary.
                failure = ToolRuntimeError(str(error)[:1000])
                self._record_error(context, registration, call, failure, _elapsed(started))
                await self._record_failure(registration)
                if attempt < attempts and self._retryable(failure, frozen_policy):
                    await self._backoff(frozen_policy, attempt)
                    continue
                raise failure from error
            else:
                await self._record_success(registration)
                result = result.model_copy(update={"attempts": attempt})
                await self._idempotency.put(idempotency_key, result)
                self._record(
                    context,
                    registration,
                    call,
                    status="SUCCEEDED",
                    result=result.result,
                    duration_ms=_elapsed(started),
                )
                return result
        raise ToolRuntimeError("tool execution did not produce a result")

    async def execute_safe(
        self,
        request: ToolCallRequest | dict[str, Any],
        *,
        policy: ToolPolicy | dict[str, Any] | None = None,
        repair: Callable[[ToolCallRequest, str], Any] | None = None,
    ) -> ToolExecutionResult:
        try:
            return await self.execute(request, policy=policy, repair=repair)
        except ToolRuntimeError as error:
            call = ToolCallRequest.model_validate(request)
            return ToolExecutionResult(
                name=call.name,
                version=call.version,
                status="FAILED",
                attempts=1,
                error_code=error.error_code,
                error_message=str(error),
            )

    def _check_policy(
        self,
        policy: ToolPolicy,
        registration: ToolRegistration,
    ) -> None:
        if not policy.enabled:
            raise ToolNotAllowedError("tool policy is disabled")
        if (registration.name, registration.version) not in {
            (item.name, item.version) for item in policy.allowed_tools
        }:
            raise ToolNotAllowedError(
                f"tool is not in the frozen allowlist: {registration.name}:{registration.version}"
            )
        if registration.side_effect not in policy.allowed_side_effects:
            raise ToolPermissionError(
                f"tool side effect is not allowed: {registration.side_effect}"
            )

    async def _validate_or_repair(
        self,
        call: ToolCallRequest,
        registration: ToolRegistration,
        repair: Callable[[ToolCallRequest, str], Any] | None,
    ) -> ToolCallRequest:
        try:
            registration.input_model.model_validate(call.arguments)
            return call
        except ValidationError as error:
            if repair is None:
                raise ToolInputError(str(error)) from error
            repaired = repair(call, str(error))
            if inspect.isawaitable(repaired):
                repaired = await repaired
            if isinstance(repaired, ToolCallRequest):
                candidate = repaired
            else:
                candidate = call.model_copy(update={"arguments": repaired})
            try:
                registration.input_model.model_validate(candidate.arguments)
            except ValidationError as repair_error:
                raise ToolInputError(str(repair_error)) from repair_error
            return candidate

    async def _invoke(
        self,
        handler: Callable[[BaseModel], Any],
        input_model: type[BaseModel],
        arguments: dict[str, Any],
    ) -> Any:
        value = input_model.model_validate(arguments)
        if inspect.iscoroutinefunction(handler):
            return await handler(value)
        result = await asyncio.to_thread(handler, value)
        return await result if inspect.isawaitable(result) else result

    def _validate_result(
        self,
        registration: ToolRegistration,
        value: Any,
        policy: ToolPolicy,
    ) -> ToolExecutionResult:
        try:
            parsed = registration.output_model.model_validate(value)
        except ValidationError as error:
            raise ToolResultError(str(error)) from error
        result = parsed.model_dump(mode="json")
        serialized = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
        if len(serialized.encode("utf-8")) > policy.max_result_bytes:
            raise ToolResultError("tool result exceeds the frozen size limit")
        return ToolExecutionResult(
            name=registration.name,
            version=registration.version,
            status="SUCCEEDED",
            result=result,
        )

    def _timeout_seconds(
        self,
        policy: ToolPolicy,
        context: ToolRuntimeContext | None,
    ) -> float:
        timeout_ms = policy.per_call_timeout_ms
        if context is not None:
            remaining_total = policy.total_timeout_ms - round(
                (monotonic() - context.started_at) * 1000
            )
            timeout_ms = min(timeout_ms, remaining_total)
            if context.deadline_epoch_ms is not None:
                timeout_ms = min(
                    timeout_ms,
                    context.deadline_epoch_ms - round(time.time() * 1000),
                )
        if timeout_ms <= 0:
            raise ToolTimeoutError("tool execution deadline elapsed")
        return timeout_ms / 1000

    async def _backoff(self, policy: ToolPolicy, attempt: int) -> None:
        delay_ms = min(
            policy.retry_policy.max_delay_ms,
            round(
                policy.retry_policy.initial_delay_ms
                * policy.retry_policy.multiplier ** (attempt - 1)
            ),
        )
        if delay_ms > 0:
            await self._sleep(delay_ms / 1000)

    def _retryable(self, error: ToolRuntimeError, policy: ToolPolicy) -> bool:
        return (
            error.error_code in policy.retry_policy.retryable_errors
            or error.__class__.__name__ in policy.retry_policy.retryable_errors
        )

    async def _check_circuit(self, registration: ToolRegistration) -> None:
        key = (registration.name, registration.version)
        async with self._circuit_lock:
            opened_until = self._open_until.get(key, 0)
            if opened_until > monotonic():
                raise ToolCircuitOpenError(
                    f"tool circuit is open: {registration.name}:{registration.version}"
                )

    async def _record_failure(self, registration: ToolRegistration) -> None:
        key = (registration.name, registration.version)
        async with self._circuit_lock:
            failures = self._failures.get(key, 0) + 1
            self._failures[key] = failures
            if failures >= self._circuit_failure_threshold:
                self._open_until[key] = monotonic() + self._circuit_cooldown_seconds

    async def _record_success(self, registration: ToolRegistration) -> None:
        key = (registration.name, registration.version)
        async with self._circuit_lock:
            self._failures.pop(key, None)
            self._open_until.pop(key, None)

    def _derived_key(
        self,
        call: ToolCallRequest,
        context: ToolRuntimeContext | None,
    ) -> str:
        prefix = context.execution_id if context is not None else "standalone"
        return f"{prefix}:{call.name}:{_sha256(call.arguments)}"

    def _record(
        self,
        context: ToolRuntimeContext | None,
        registration: ToolRegistration,
        call: ToolCallRequest,
        *,
        status: str,
        result: Any,
        duration_ms: int,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> None:
        permit = begin_model_invocation("TOOL")
        record_model_invocation(
            ModelInvocationTelemetry(
                call_id=permit.call_id,
                call_type="TOOL",
                provider_key="tool-runtime",
                model_name=registration.name,
                prompt_key=f"tool:{registration.name}",
                input_tokens=0,
                output_tokens=0,
                estimated_cost=0,
                status=status,
                duration_ms=duration_ms,
                idempotency_key=call.idempotency_key,
                tool_name=registration.name,
                tool_version=registration.version,
                permission_policy=registration.permission_policy,
                normalized_params_hash=_sha256(call.arguments),
                result_hash=_sha256(result) if status == "SUCCEEDED" else None,
                error_code=error_code,
                error_message=error_message,
                redacted_params=_redact(call.arguments),
            )
        )

    def _record_error(
        self,
        context: ToolRuntimeContext | None,
        registration: ToolRegistration,
        call: ToolCallRequest,
        error: ToolRuntimeError,
        duration_ms: int,
    ) -> None:
        self._record(
            context,
            registration,
            call,
            status="FAILED",
            result=None,
            duration_ms=duration_ms,
            error_code=error.error_code,
            error_message=str(error)[:1000],
        )


def _elapsed(started: float) -> int:
    return max(0, round((monotonic() - started) * 1000))


def _sha256(value: Any) -> str:
    material = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: "[REDACTED]"
            if any(marker in key.lower() for marker in ("password", "secret", "token", "key"))
            else _redact(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_redact(item) for item in value]
    return value
