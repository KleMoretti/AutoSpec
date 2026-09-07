from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import re
import time
from time import perf_counter
from typing import Any, Literal

from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

from runtime.handler_registry import HandlerRegistry, UnknownHandlerError
from runtime.concurrency import ConcurrencyController
from runtime.execution_context import (
    ModelExecutionContract,
    bind_model_execution_contract,
)
from runtime.context_policy import capture_context_manifests
from runtime.model_telemetry import (
    capture_model_invocations,
    summarize_model_invocations,
)
from runtime.tool_harness import (
    ToolHarness,
    ToolRegistry,
    ToolRuntimeContext,
    bind_tool_runtime_context,
)
from runtime.agent_loop_trace import capture_agent_loop_trace, current_agent_loop_trace
from schemas.agent_loop import AgentStepRecord, LoopPolicy, StopReason
from schemas.workflow_spec import (
    ContextPolicy,
    FallbackPolicy,
    ModelPolicy,
    RetryPolicy,
    ToolPolicy,
)


TRACEPARENT_PATTERN = re.compile(
    r"^(?!ff-)[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class TraceContextEnvelope(BaseModel):
    correlation_id: str | None = Field(default=None, min_length=1, max_length=128)
    traceparent: str | None = None
    tracestate: str | None = Field(default=None, max_length=512)

    @field_validator("traceparent")
    @classmethod
    def validate_traceparent(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if not TRACEPARENT_PATTERN.fullmatch(value):
            raise ValueError("traceparent must be a valid W3C trace parent")
        _, trace_id, span_id, _ = value.split("-")
        if trace_id == "0" * 32 or span_id == "0" * 16:
            raise ValueError("traceparent IDs must not be all zeroes")
        return value


class BudgetReservation(BaseModel):
    reservation_id: str = Field(min_length=1)
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    model_calls: int = Field(ge=0)
    estimated_cost: float = Field(ge=0.0)


class InvocationRecord(BaseModel):
    call_id: str = Field(min_length=1, max_length=255)
    call_type: Literal["MODEL", "TOOL"] = "MODEL"
    execution_id: str | None = None
    call_sequence: int = Field(ge=1)
    attempt: int = Field(ge=1)
    provider_key: str = Field(min_length=1)
    model_name: str = Field(min_length=1)
    prompt_key: str = Field(min_length=1)
    prompt_version: str | None = Field(default=None, min_length=1)
    prompt_checksum: str | None = Field(
        default=None, pattern=r"^[0-9a-f]{64}$"
    )
    schema_version: str | None = Field(default=None, min_length=1)
    contract_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    cache_tokens: int = Field(default=0, ge=0)
    estimated_cost: float = Field(default=0.0, ge=0.0)
    reserved_input_tokens: int = Field(default=0, ge=0)
    reserved_output_tokens: int = Field(default=0, ge=0)
    reserved_cost: float = Field(default=0.0, ge=0.0)
    route_key: str | None = None
    route_reason: str | None = None
    fallback_used: bool = False
    normalized_params_hash: str | None = Field(
        default=None, pattern=r"^[0-9a-f]{64}$"
    )
    result_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    status: Literal["SUCCEEDED", "FAILED"]
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: int = Field(default=0, ge=0)
    deadline_epoch_ms: int | None = Field(default=None, ge=0)
    idempotency_key: str | None = None
    tool_name: str | None = None
    tool_version: str | None = None
    permission_policy: str | None = None
    reference_sources: list[str] | None = None
    redacted_params: dict[str, Any] | None = None

    def validate_frozen_call(self) -> None:
        if self.status == "FAILED" and not self.error_code:
            raise ValueError("failed invocation records require error_code")
        if self.call_type == "MODEL":
            required = {
                "prompt_version": self.prompt_version,
                "prompt_checksum": self.prompt_checksum,
                "schema_version": self.schema_version,
                "contract_hash": self.contract_hash,
                "normalized_params_hash": self.normalized_params_hash,
                "idempotency_key": self.idempotency_key,
                "deadline_epoch_ms": self.deadline_epoch_ms,
            }
            missing = [name for name, value in required.items() if not value]
            if missing:
                raise ValueError(
                    "model invocation record is incomplete: " + ", ".join(missing)
                )
            if self.status == "SUCCEEDED" and self.result_hash is None:
                raise ValueError("successful model invocation requires result_hash")
            if (
                self.input_tokens > self.reserved_input_tokens
                or self.output_tokens > self.reserved_output_tokens
                or self.estimated_cost - self.reserved_cost > 0.000001
            ):
                raise ValueError("model invocation exceeds its frozen call reservation")
        elif not all(
            (
                self.tool_name,
                self.tool_version,
                self.permission_policy,
                self.idempotency_key,
                self.normalized_params_hash,
                self.contract_hash,
                self.schema_version,
            )
        ):
            raise ValueError("tool invocation record is incomplete")


class NodeCommand(TraceContextEnvelope):
    event_id: str = Field(min_length=1)
    workflow_run_id: int
    node_run_id: int
    node_id: str = Field(min_length=1)
    revision: int = Field(ge=1)
    attempt: int = Field(ge=1)
    execution_id: str = Field(min_length=1)
    handler_key: str = Field(min_length=1)
    handler_version: str = Field(min_length=1)
    timeout_ms: int = Field(default=30000, ge=1)
    input_payload: dict[str, Any] = Field(default_factory=dict)
    protocol_version: int = Field(default=0, ge=0, le=2)
    contract_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    input_schema: str | None = None
    input_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    output_schema: str | None = None
    output_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    prompt_key: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    context_policy: dict[str, Any] = Field(default_factory=dict)
    model_policy: dict[str, Any] = Field(default_factory=dict)
    retry_policy: dict[str, Any] = Field(default_factory=dict)
    fallback: dict[str, Any] = Field(default_factory=dict)
    tool_policy: dict[str, Any] = Field(default_factory=dict)
    agent_loop_policy: dict[str, Any] = Field(default_factory=dict)
    budget_reservation: BudgetReservation | None = None
    deadline_epoch_ms: int = Field(default=0, ge=0)
    execution_bundle_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    fencing_token: int = Field(default=0, ge=0)
    worker_id: str | None = Field(default=None, min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_contract_envelope(self) -> "NodeCommand":
        if self.tool_policy:
            ToolPolicy.model_validate(self.tool_policy)
        if self.agent_loop_policy:
            LoopPolicy.model_validate(self.agent_loop_policy)
        if self.protocol_version == 0:
            if self.contract_hash is not None:
                raise ValueError("contract_hash requires protocol_version 1")
            if self.agent_loop_policy:
                raise ValueError("agent_loop_policy requires protocol_version 2")
            return self
        required = {
            "contract_hash": self.contract_hash,
            "input_schema": self.input_schema,
            "input_schema_hash": self.input_schema_hash,
            "output_schema": self.output_schema,
            "output_schema_hash": self.output_schema_hash,
            "prompt_key": self.prompt_key,
            "prompt_version": self.prompt_version,
            "prompt_checksum": self.prompt_checksum,
        }
        missing = [name for name, value in required.items() if not value]
        if missing or not self.model_policy or not self.retry_policy:
            raise ValueError(
                "protocol_version 1 command has incomplete executable contract: "
                + ", ".join(missing)
            )
        if self.deadline_epoch_ms < 1:
            raise ValueError("protocol_version 1 command requires deadline_epoch_ms")
        ModelPolicy.model_validate(self.model_policy)
        RetryPolicy.model_validate(self.retry_policy)
        FallbackPolicy.model_validate(self.fallback)
        if self.protocol_version == 2:
            if not self.context_policy or self.budget_reservation is None:
                raise ValueError(
                    "protocol_version 2 command requires context_policy and budget_reservation"
                )
            context = ContextPolicy.model_validate(self.context_policy)
            model = ModelPolicy.model_validate(self.model_policy)
            if context.max_input_tokens + model.max_output_tokens > model.context_window_tokens:
                raise ValueError("context and output budgets exceed model context window")
            reservation = self.budget_reservation
            if reservation.reservation_id != self.execution_id:
                raise ValueError("budget reservation_id must match execution_id")
            if reservation.model_calls != model.max_calls:
                raise ValueError("budget reservation model_calls does not match model policy")
            if reservation.input_tokens != context.max_input_tokens * model.max_calls:
                raise ValueError("budget reservation input_tokens does not match context policy")
            if reservation.output_tokens != model.max_output_tokens * model.max_calls:
                raise ValueError("budget reservation output_tokens does not match model policy")
            expected_cost = model.max_calls * (
                context.max_input_tokens
                * max(
                    model.input_cost_per_million,
                    model.cached_input_cost_per_million,
                )
                + model.max_output_tokens * model.output_cost_per_million
            ) / 1_000_000
            if abs(reservation.estimated_cost - expected_cost) > 0.000001:
                raise ValueError("budget reservation estimated_cost does not match model policy")
        return self


class NodeExecutionEvent(TraceContextEnvelope):
    event_id: str
    source_event_id: str
    event_type: Literal["NODE_HEARTBEAT", "NODE_SUCCEEDED", "NODE_FAILED"]
    workflow_run_id: int
    node_run_id: int
    node_id: str
    revision: int
    attempt: int
    execution_id: str
    duration_ms: int
    output_payload: dict[str, Any] | None = None
    error_code: str | None = None
    error_message: str | None = None
    provider_key: str | None = None
    model_name: str | None = None
    prompt_key: str | None = None
    route_key: str | None = None
    route_reason: str | None = None
    fallback_used: bool = False
    context_manifest: dict[str, Any] | None = None
    model_call_count: int = Field(default=0, ge=0)
    tool_call_count: int = Field(default=0, ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    cache_tokens: int = Field(default=0, ge=0)
    estimated_cost: float = Field(default=0.0, ge=0.0)
    call_records: list[InvocationRecord] = Field(default_factory=list)
    budget_reservation_id: str | None = None
    protocol_version: int = Field(default=0, ge=0, le=2)
    contract_hash: str | None = None
    input_schema: str | None = None
    input_schema_hash: str | None = None
    output_schema: str | None = None
    output_schema_hash: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = None
    execution_bundle_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    fencing_token: int = Field(default=0, ge=0)
    worker_id: str | None = None
    agent_steps: list[AgentStepRecord] = Field(default_factory=list)
    agent_loop_stop_reason: StopReason | None = None

    @model_validator(mode="after")
    def validate_call_records(self) -> "NodeExecutionEvent":
        if self.protocol_version < 2 or not self.is_terminal:
            return self
        for record in self.call_records:
            record.validate_frozen_call()
        call_ids = [record.call_id for record in self.call_records]
        sequences = [record.call_sequence for record in self.call_records]
        if len(call_ids) != len(set(call_ids)) or len(sequences) != len(set(sequences)):
            raise ValueError("call_records must have unique call ids and sequences")
        if any(
            record.execution_id != self.execution_id
            or record.attempt != self.attempt
            or record.contract_hash != self.contract_hash
            or record.call_type == "MODEL"
            and (
                record.prompt_key != self.prompt_key
                or record.prompt_version != self.prompt_version
                or record.prompt_checksum != self.prompt_checksum
            )
            for record in self.call_records
        ):
            raise ValueError("call_records do not belong to the terminal execution")
        models = [record for record in self.call_records if record.call_type == "MODEL"]
        tools = [record for record in self.call_records if record.call_type == "TOOL"]
        if len(models) != self.model_call_count or len(tools) != self.tool_call_count:
            raise ValueError("call record counts do not match terminal usage totals")
        if sum(record.input_tokens for record in models) != self.input_tokens:
            raise ValueError("call record input tokens do not match terminal usage")
        if sum(record.output_tokens for record in models) != self.output_tokens:
            raise ValueError("call record output tokens do not match terminal usage")
        if sum(record.cache_tokens for record in models) != self.cache_tokens:
            raise ValueError("call record cache tokens do not match terminal usage")
        if abs(
            sum(record.estimated_cost for record in self.call_records)
            - self.estimated_cost
        ) > 0.000001:
            raise ValueError("call record costs do not match terminal usage")
        return self

    @property
    def is_terminal(self) -> bool:
        return self.event_type in {"NODE_SUCCEEDED", "NODE_FAILED"}


class NodeExecutor:
    def __init__(
        self,
        registry: HandlerRegistry,
        tool_harness: ToolHarness | None = None,
        concurrency: ConcurrencyController | None = None,
    ) -> None:
        self._registry = registry
        self._tool_harness = tool_harness or ToolHarness(ToolRegistry())
        self._concurrency = concurrency

    async def execute(self, command: NodeCommand) -> NodeExecutionEvent:
        started = perf_counter()
        try:
            registration = self._registry.resolve(
                command.handler_key, command.handler_version
            )
        except UnknownHandlerError as exception:
            return self._failure(command, started, "HANDLER_NOT_FOUND", str(exception))

        contract_error = self._contract_error(command, registration)
        if contract_error is not None:
            return self._failure(command, started, "CONTRACT_MISMATCH", contract_error)

        remaining_ms = self._remaining_ms(command)
        if remaining_ms <= 0:
            return self._failure(
                command,
                started,
                "DEADLINE_EXCEEDED",
                "node command deadline elapsed before execution",
            )

        execution_payload = dict(command.input_payload)
        raw_user_key = execution_payload.pop("_autospec_actor_user_id", None)
        user_key = None if raw_user_key is None else str(raw_user_key)
        raw_project_key = execution_payload.pop("_autospec_project_id", None)
        project_key = (
            None
            if raw_project_key is None
            else str(raw_project_key)
        )
        if project_key is None and execution_payload.get("retrieval_project_id") is not None:
            project_key = str(execution_payload.get("retrieval_project_id"))
        try:
            validated_input = registration.input_model.model_validate(execution_payload)
        except ValidationError as exception:
            return self._failure(command, started, "VALIDATION_ERROR", str(exception))

        execution_contract = self._model_execution_contract(command)
        tool_policy = ToolPolicy.model_validate(command.tool_policy or {})
        tool_context = ToolRuntimeContext(
            execution_id=command.execution_id,
            node_id=command.node_id,
            attempt=command.attempt,
            policy=tool_policy,
            deadline_epoch_ms=(
                command.deadline_epoch_ms if command.deadline_epoch_ms > 0 else None
            ),
            contract_hash=command.contract_hash,
            schema_version=command.output_schema,
            harness=self._tool_harness,
            workflow_run_id=command.workflow_run_id,
            node_run_id=command.node_run_id,
            actor_user_id=user_key,
            project_id=project_key,
            fencing_token=command.fencing_token,
            execution_bundle_hash=command.execution_bundle_hash,
            correlation_id=command.correlation_id,
            traceparent=command.traceparent,
            tracestate=command.tracestate,
        )
        with bind_model_execution_contract(execution_contract):
            with bind_tool_runtime_context(tool_context):
                with capture_model_invocations(
                    execution_id=command.execution_id,
                    attempt=command.attempt,
                    max_model_calls=(
                        int(command.model_policy.get("max_calls", 1))
                        if command.protocol_version >= 2
                        else None
                    ),
                    deadline_epoch_ms=(
                        command.deadline_epoch_ms if command.deadline_epoch_ms > 0 else None
                    ),
                ) as invocations, capture_context_manifests() as manifests:
                    with capture_agent_loop_trace():
                        try:
                            if self._concurrency is None:
                                raw_output = await asyncio.wait_for(
                                    self._invoke(registration.handler, validated_input),
                                    timeout=remaining_ms / 1000,
                                )
                            else:
                                async with self._concurrency.hold(
                                    user_key=user_key,
                                    requires_model=bool(command.model_policy),
                                ):
                                    raw_output = await asyncio.wait_for(
                                        self._invoke(registration.handler, validated_input),
                                        timeout=remaining_ms / 1000,
                                    )
                        except asyncio.TimeoutError:
                            timeout_message = (
                                f"node exceeded timeout of {command.timeout_ms} ms"
                                if command.deadline_epoch_ms <= 0
                                else f"node exceeded execution deadline after {remaining_ms} ms"
                            )
                            return self._failure(
                                command,
                                started,
                                "MODEL_TIMEOUT",
                                timeout_message,
                                self._metadata(
                                    invocations, manifests, current_agent_loop_trace()
                                ),
                            )
                        except Exception as exception:  # noqa: BLE001 - converted to runtime envelope.
                            return self._failure(
                                command,
                                started,
                                getattr(exception, "error_code", "HANDLER_ERROR"),
                                str(exception),
                                self._metadata(
                                    invocations, manifests, current_agent_loop_trace()
                                ),
                            )
                        usage = self._metadata(
                            invocations, manifests, current_agent_loop_trace()
                        )

        try:
            validated_output = registration.output_model.model_validate(raw_output)
        except ValidationError as exception:
            return self._failure(
                command,
                started,
                "OUTPUT_SCHEMA_ERROR",
                str(exception),
                usage,
            )

        return NodeExecutionEvent(
            event_id=f"{command.execution_id}:succeeded",
            source_event_id=command.event_id,
            event_type="NODE_SUCCEEDED",
            workflow_run_id=command.workflow_run_id,
            node_run_id=command.node_run_id,
            node_id=command.node_id,
            revision=command.revision,
            attempt=command.attempt,
            execution_id=command.execution_id,
            duration_ms=self._duration_ms(started),
            output_payload=validated_output.model_dump(mode="json"),
            correlation_id=command.correlation_id,
            traceparent=command.traceparent,
            tracestate=command.tracestate,
            **self._execution_metadata(command, usage),
        )

    async def _invoke(self, handler, validated_input: BaseModel) -> Any:
        if inspect.iscoroutinefunction(handler):
            return await handler(validated_input)
        result = await asyncio.to_thread(handler, validated_input)
        if inspect.isawaitable(result):
            return await result
        return result

    def _failure(
        self,
        command: NodeCommand,
        started: float,
        error_code: str,
        error_message: str,
        usage: dict[str, Any] | None = None,
    ) -> NodeExecutionEvent:
        return NodeExecutionEvent(
            event_id=f"{command.execution_id}:failed:{error_code}",
            source_event_id=command.event_id,
            event_type="NODE_FAILED",
            workflow_run_id=command.workflow_run_id,
            node_run_id=command.node_run_id,
            node_id=command.node_id,
            revision=command.revision,
            attempt=command.attempt,
            execution_id=command.execution_id,
            duration_ms=self._duration_ms(started),
            error_code=error_code,
            error_message=error_message,
            correlation_id=command.correlation_id,
            traceparent=command.traceparent,
            tracestate=command.tracestate,
            **self._execution_metadata(command, usage),
        )

    def _duration_ms(self, started: float) -> int:
        return max(0, round((perf_counter() - started) * 1000))

    def _metadata(self, invocations, manifests, loop_trace=None) -> dict[str, Any]:
        metadata = summarize_model_invocations(invocations)
        metadata["context_manifest"] = manifests[-1] if manifests else None
        if loop_trace is not None:
            metadata["agent_steps"] = [
                step.model_dump(mode="json") for step in loop_trace.steps
            ]
            metadata["agent_loop_stop_reason"] = loop_trace.stop_reason.value
        return metadata

    def _remaining_ms(self, command: NodeCommand) -> int:
        if command.deadline_epoch_ms <= 0:
            return command.timeout_ms
        deadline_remaining = command.deadline_epoch_ms - round(time.time() * 1000)
        return min(command.timeout_ms, deadline_remaining)

    def _contract_error(self, command: NodeCommand, registration) -> str | None:
        if command.protocol_version == 0:
            return None
        expected_hash = contract_fingerprint(command)
        if command.contract_hash != expected_hash:
            return (
                "contract_hash does not match the canonical command contract: "
                f"expected {expected_hash}"
            )
        comparisons = {
            "input_schema": (command.input_schema, registration.input_schema),
            "input_schema_hash": (
                command.input_schema_hash,
                registration.input_schema_hash,
            ),
            "output_schema": (command.output_schema, registration.output_schema),
            "output_schema_hash": (
                command.output_schema_hash,
                registration.output_schema_hash,
            ),
            "prompt_key": (command.prompt_key, registration.prompt_key),
            "prompt_version": (command.prompt_version, registration.prompt_version),
            "prompt_checksum": (
                command.prompt_checksum,
                registration.prompt_checksum,
            ),
        }
        mismatches = [
            f"{name} command={actual!r} worker={expected!r}"
            for name, (actual, expected) in comparisons.items()
            if actual != expected
        ]
        return "; ".join(mismatches) if mismatches else None

    def _model_execution_contract(
        self,
        command: NodeCommand,
    ) -> ModelExecutionContract | None:
        if command.protocol_version == 0:
            return None
        return ModelExecutionContract(
            execution_id=command.execution_id,
            prompt_key=command.prompt_key or "",
            prompt_version=command.prompt_version or "",
            prompt_checksum=command.prompt_checksum or "",
            model_policy=dict(command.model_policy),
            deadline_epoch_ms=command.deadline_epoch_ms,
            protocol_version=command.protocol_version,
            node_id=command.node_id,
            attempt=command.attempt,
            contract_hash=command.contract_hash,
            context_policy=dict(command.context_policy),
            retry_policy=dict(command.retry_policy),
            fallback_policy=dict(command.fallback),
            schema_version=command.output_schema,
            tool_policy=dict(command.tool_policy),
            agent_loop_policy=dict(command.agent_loop_policy),
        )

    def _execution_metadata(
        self,
        command: NodeCommand,
        usage: dict[str, Any] | None,
    ) -> dict[str, Any]:
        metadata = dict(usage or {})
        if metadata.get("prompt_key") is None:
            metadata["prompt_key"] = command.prompt_key
        if metadata.get("prompt_version") is None:
            metadata["prompt_version"] = command.prompt_version
        if metadata.get("prompt_checksum") is None:
            metadata["prompt_checksum"] = command.prompt_checksum
        metadata.update(
            {
                "protocol_version": command.protocol_version,
                "contract_hash": command.contract_hash,
                "input_schema": command.input_schema,
                "input_schema_hash": command.input_schema_hash,
                "output_schema": command.output_schema,
                "output_schema_hash": command.output_schema_hash,
                "execution_bundle_hash": command.execution_bundle_hash,
                "fencing_token": command.fencing_token,
                "worker_id": command.worker_id,
                "budget_reservation_id": (
                    command.budget_reservation.reservation_id
                    if command.budget_reservation is not None
                    else None
                ),
                "agent_steps": metadata.get("agent_steps", []),
                "agent_loop_stop_reason": metadata.get("agent_loop_stop_reason"),
            }
        )
        return metadata


def contract_fingerprint(command: NodeCommand) -> str:
    material = {
        "fallback": command.fallback,
        "handler_key": command.handler_key,
        "handler_version": command.handler_version,
        "input_schema": command.input_schema,
        "input_schema_hash": command.input_schema_hash,
        "model_policy": command.model_policy,
        "output_schema": command.output_schema,
        "output_schema_hash": command.output_schema_hash,
        "prompt_checksum": command.prompt_checksum,
        "prompt_key": command.prompt_key,
        "prompt_version": command.prompt_version,
        "protocol_version": command.protocol_version,
        "retry_policy": command.retry_policy,
        "timeout_ms": command.timeout_ms,
    }
    if command.protocol_version >= 2:
        material["context_policy"] = command.context_policy
        if command.tool_policy:
            material["tool_policy"] = command.tool_policy
        if command.agent_loop_policy:
            material["agent_loop_policy"] = command.agent_loop_policy
    canonical = json.dumps(
        material,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
