from __future__ import annotations

import json
import asyncio
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import ValidationError

from runtime.node_executor import NodeCommand, NodeExecutionEvent, NodeExecutor
from runtime.worker_metrics import NO_OP_WORKER_METRICS, WorkerMetricsRecorder


COMMAND_STREAM = "autospec.workflow.commands"
COMMAND_DLQ_STREAM = "autospec.workflow.commands.dlq"
EVENT_STREAM = "autospec.workflow.events"
WORKER_GROUP = "autospec-workers"


@dataclass(frozen=True)
class StreamMessage:
    message_id: str
    fields: dict[str, Any]


class InvalidWorkflowCommandError(ValueError):
    """A safely classified command protocol error suitable for DLQ metadata."""

    category = "PROTOCOL_VALIDATION"

    def __init__(self, error_type: str) -> None:
        super().__init__(error_type)
        self.error_type = error_type


class WorkflowStreamClient(Protocol):
    async def publish_event(
        self, stream: str, event: NodeExecutionEvent
    ) -> None: ...

    async def acknowledge(
        self, stream: str, group: str, message_id: str
    ) -> None: ...


class WorkflowStreamWorker:
    def __init__(
        self,
        client: WorkflowStreamClient,
        executor: NodeExecutor,
        command_stream: str = COMMAND_STREAM,
        event_stream: str = EVENT_STREAM,
        consumer_group: str = WORKER_GROUP,
        heartbeat_interval_seconds: float = 10.0,
        metrics: WorkerMetricsRecorder = NO_OP_WORKER_METRICS,
    ) -> None:
        self._client = client
        self._executor = executor
        self._command_stream = command_stream
        self._event_stream = event_stream
        self._consumer_group = consumer_group
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._metrics = metrics

    async def process(self, message: StreamMessage) -> NodeExecutionEvent:
        command = self._parse_command(message)
        heartbeat_task = asyncio.create_task(self._publish_heartbeats(command))
        try:
            event = await self._executor.execute(command)
        finally:
            heartbeat_task.cancel()
            with suppress(asyncio.CancelledError):
                await heartbeat_task
        await self._client.publish_event(self._event_stream, event)
        await self._client.acknowledge(
            self._command_stream, self._consumer_group, message.message_id
        )
        return event

    async def _publish_heartbeats(self, command: NodeCommand) -> None:
        sequence = 0
        while True:
            await asyncio.sleep(self._heartbeat_interval_seconds)
            sequence += 1
            heartbeat = NodeExecutionEvent(
                event_id=f"{command.execution_id}:heartbeat:{sequence}",
                source_event_id=command.event_id,
                event_type="NODE_HEARTBEAT",
                workflow_run_id=command.workflow_run_id,
                node_run_id=command.node_run_id,
                node_id=command.node_id,
                revision=command.revision,
                attempt=command.attempt,
                execution_id=command.execution_id,
                duration_ms=round(sequence * self._heartbeat_interval_seconds * 1000),
                correlation_id=command.correlation_id,
                traceparent=command.traceparent,
                tracestate=command.tracestate,
            )
            await self._client.publish_event(self._event_stream, heartbeat)
            self._metrics.pulse()

    def _parse_command(self, message: StreamMessage) -> NodeCommand:
        if "payload" not in message.fields:
            raise InvalidWorkflowCommandError("MISSING_PAYLOAD")
        payload = message.fields["payload"]
        if isinstance(payload, bytes):
            try:
                payload = payload.decode("utf-8")
            except UnicodeDecodeError:
                raise InvalidWorkflowCommandError("INVALID_ENCODING") from None
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                raise InvalidWorkflowCommandError("INVALID_JSON") from None
        if not isinstance(payload, dict):
            raise InvalidWorkflowCommandError("PAYLOAD_NOT_OBJECT")
        try:
            return NodeCommand.model_validate(payload)
        except ValidationError:
            raise InvalidWorkflowCommandError("SCHEMA_VALIDATION") from None
