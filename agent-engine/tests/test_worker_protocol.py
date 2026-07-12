import json
import asyncio

import pytest

from runtime.handler_registry import HandlerRegistry
from runtime.node_executor import NodeExecutionEvent
from runtime.worker import StreamMessage, WorkflowStreamWorker


class FakeStreamClient:
    def __init__(self):
        self.published = []
        self.acknowledged = []

    async def publish_event(self, stream, event):
        self.published.append((stream, event))

    async def acknowledge(self, stream, group, message_id):
        self.acknowledged.append((stream, group, message_id))


class StubExecutor:
    def __init__(self, event=None, error=None):
        self.event = event
        self.error = error

    async def execute(self, _command):
        if self.error:
            raise self.error
        return self.event


def message():
    return StreamMessage(
        message_id="1710000000000-0",
        fields={
            "payload": json.dumps(
                {
                    "event_id": "command-1",
                    "workflow_run_id": 7,
                    "node_run_id": 11,
                    "node_id": "fixture",
                    "revision": 1,
                    "attempt": 1,
                    "execution_id": "7:fixture:1:1",
                    "handler_key": "FixtureAgent",
                    "handler_version": "v1",
                    "timeout_ms": 1000,
                    "input_payload": {"value": 3},
                }
            )
        },
    )


def success_event():
    return NodeExecutionEvent(
        event_id="7:fixture:1:1:succeeded",
        source_event_id="command-1",
        event_type="NODE_SUCCEEDED",
        workflow_run_id=7,
        node_run_id=11,
        node_id="fixture",
        revision=1,
        attempt=1,
        execution_id="7:fixture:1:1",
        duration_ms=12,
        output_payload={"doubled": 6},
    )


@pytest.mark.asyncio
async def test_worker_publishes_terminal_event_before_acknowledging_command():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))

    await worker.process(message())

    assert client.published == [("autospec.workflow.events", success_event())]
    assert client.acknowledged == [
        ("autospec.workflow.commands", "autospec-workers", "1710000000000-0")
    ]


@pytest.mark.asyncio
async def test_worker_does_not_acknowledge_when_event_publication_fails():
    class FailingPublishClient(FakeStreamClient):
        async def publish_event(self, stream, event):
            raise ConnectionError("redis unavailable")

    client = FailingPublishClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))

    with pytest.raises(ConnectionError, match="redis unavailable"):
        await worker.process(message())

    assert client.acknowledged == []


@pytest.mark.asyncio
async def test_worker_does_not_acknowledge_when_executor_crashes():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(error=RuntimeError("executor crash")))

    with pytest.raises(RuntimeError, match="executor crash"):
        await worker.process(message())

    assert client.published == []
    assert client.acknowledged == []


@pytest.mark.asyncio
async def test_worker_rejects_message_without_payload_without_acknowledging():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))

    with pytest.raises(ValueError, match="payload"):
        await worker.process(StreamMessage(message_id="1-0", fields={}))

    assert client.acknowledged == []


@pytest.mark.asyncio
async def test_worker_publishes_heartbeat_before_terminal_event_for_long_execution():
    class SlowExecutor:
        async def execute(self, _command):
            await asyncio.sleep(0.03)
            return success_event()

    client = FakeStreamClient()
    worker = WorkflowStreamWorker(
        client,
        SlowExecutor(),
        heartbeat_interval_seconds=0.01,
    )

    await worker.process(message())

    event_types = [published.event_type for _, published in client.published]
    assert event_types[-1] == "NODE_SUCCEEDED"
    assert "NODE_HEARTBEAT" in event_types[:-1]
    assert len(client.acknowledged) == 1
