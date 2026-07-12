from __future__ import annotations

import json
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
    ) -> None:
        self._client = client
        self._executor = executor
        self._command_stream = command_stream
        self._event_stream = event_stream
        self._consumer_group = consumer_group

    async def process(self, message: StreamMessage) -> NodeExecutionEvent:
        command = self._parse_command(message)
        event = await self._executor.execute(command)
        await self._client.publish_event(self._event_stream, event)
        await self._client.acknowledge(
            self._command_stream, self._consumer_group, message.message_id
        )
        return event

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
