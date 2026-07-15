from __future__ import annotations

import json
import asyncio
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Protocol

from runtime.node_executor import NodeCommand, NodeExecutionEvent, NodeExecutor


COMMAND_STREAM = "autospec.workflow.commands"
EVENT_STREAM = "autospec.workflow.events"
WORKER_GROUP = "autospec-workers"


@dataclass(frozen=True)
class StreamMessage:
    message_id: str
    fields: dict[str, Any]


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
    ) -> None:
        self._client = client
        self._executor = executor
        self._command_stream = command_stream
        self._event_stream = event_stream
        self._consumer_group = consumer_group
        self._heartbeat_interval_seconds = heartbeat_interval_seconds

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
            )
            await self._client.publish_event(self._event_stream, heartbeat)

    def _parse_command(self, message: StreamMessage) -> NodeCommand:
        if "payload" not in message.fields:
            raise ValueError("workflow command message requires payload")
        payload = message.fields["payload"]
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")
        if isinstance(payload, str):
            payload = json.loads(payload)
        if not isinstance(payload, dict):
            raise ValueError("workflow command payload must be a JSON object")
        return NodeCommand.model_validate(payload)
