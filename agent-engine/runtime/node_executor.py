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
from runtime.execution_context import (
    ModelExecutionContract,
    bind_model_execution_contract,
)
from runtime.context_policy import capture_context_manifests
from runtime.model_telemetry import (
    capture_model_invocations,
    summarize_model_invocations,
)
from schemas.workflow_spec import FallbackPolicy, ModelPolicy, RetryPolicy


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
    protocol_version: int = Field(default=0, ge=0, le=1)
    contract_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    input_schema: str | None = None
    input_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    output_schema: str | None = None
    output_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    prompt_key: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    model_policy: dict[str, Any] = Field(default_factory=dict)
    retry_policy: dict[str, Any] = Field(default_factory=dict)
    fallback: dict[str, Any] = Field(default_factory=dict)
    deadline_epoch_ms: int = Field(default=0, ge=0)
    fencing_token: int = Field(default=0, ge=0)
    worker_id: str | None = Field(default=None, min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_contract_envelope(self) -> "NodeCommand":
        if self.protocol_version == 0:
            if self.contract_hash is not None:
                raise ValueError("contract_hash requires protocol_version 1")
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
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    cache_tokens: int = Field(default=0, ge=0)
    estimated_cost: float = Field(default=0.0, ge=0.0)
    protocol_version: int = Field(default=0, ge=0, le=1)
    contract_hash: str | None = None
    input_schema: str | None = None
    input_schema_hash: str | None = None
    output_schema: str | None = None
    output_schema_hash: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = None
    fencing_token: int = Field(default=0, ge=0)
    worker_id: str | None = None


class NodeExecutor:
    def __init__(self, registry: HandlerRegistry) -> None:
        self._registry = registry

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

        try:
            validated_input = registration.input_model.model_validate(command.input_payload)
        except ValidationError as exception:
            return self._failure(command, started, "VALIDATION_ERROR", str(exception))

        execution_contract = self._model_execution_contract(command)
        with bind_model_execution_contract(execution_contract):
            with capture_model_invocations() as invocations, capture_context_manifests() as manifests:
                try:
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
                        self._metadata(invocations, manifests),
                    )
                except Exception as exception:  # noqa: BLE001 - converted to runtime envelope.
                    return self._failure(
                        command,
                        started,
                        getattr(exception, "error_code", "HANDLER_ERROR"),
                        str(exception),
                        self._metadata(invocations, manifests),
                    )
                usage = self._metadata(invocations, manifests)

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

    def _metadata(self, invocations, manifests) -> dict[str, Any]:
        metadata = summarize_model_invocations(invocations)
        metadata["context_manifest"] = manifests[-1] if manifests else None
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
                "fencing_token": command.fencing_token,
                "worker_id": command.worker_id,
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
    canonical = json.dumps(
        material,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
