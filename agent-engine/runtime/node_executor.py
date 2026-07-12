from __future__ import annotations

import asyncio
import inspect
from time import perf_counter
from typing import Any, Literal

from pydantic import BaseModel, Field, ValidationError

from runtime.handler_registry import HandlerRegistry, UnknownHandlerError


class NodeCommand(BaseModel):
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


class NodeExecutionEvent(BaseModel):
    event_id: str
    source_event_id: str
    event_type: Literal["NODE_SUCCEEDED", "NODE_FAILED"]
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

        try:
            validated_input = registration.input_model.model_validate(command.input_payload)
        except ValidationError as exception:
            return self._failure(command, started, "VALIDATION_ERROR", str(exception))

        try:
            raw_output = await asyncio.wait_for(
                self._invoke(registration.handler, validated_input),
                timeout=command.timeout_ms / 1000,
            )
        except asyncio.TimeoutError:
            return self._failure(
                command,
                started,
                "MODEL_TIMEOUT",
                f"node exceeded timeout of {command.timeout_ms} ms",
            )
        except Exception as exception:  # noqa: BLE001 - converted to runtime envelope.
            return self._failure(command, started, "HANDLER_ERROR", str(exception))

        try:
            validated_output = registration.output_model.model_validate(raw_output)
        except ValidationError as exception:
            return self._failure(command, started, "OUTPUT_SCHEMA_ERROR", str(exception))

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
        )

    async def _invoke(self, handler, validated_input: BaseModel) -> Any:
        result = handler(validated_input)
        if inspect.isawaitable(result):
            return await result
        return result

    def _failure(
        self,
        command: NodeCommand,
        started: float,
        error_code: str,
        error_message: str,
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
        )

    def _duration_ms(self, started: float) -> int:
        return max(0, round((perf_counter() - started) * 1000))
