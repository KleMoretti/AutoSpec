import json
import asyncio
import time

import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import SpanKind

from runtime.handler_registry import HandlerRegistry
from runtime.node_executor import NodeExecutionEvent
from runtime.worker import (
    InvalidWorkflowCommandError,
    StreamMessage,
    WorkflowStreamWorker,
)
from runtime.workflow_log_context import workflow_log_context


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
                    "correlation_id": "123e4567-e89b-12d3-a456-426614174000",
                    "traceparent": "00-123e4567e89b12d3a456426614174000-123e4567e89b12d3-01",
                    "tracestate": "autospec=backend",
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
async def test_worker_binds_trace_context_during_execution_and_restores_it():
    class ContextCapturingExecutor:
        def __init__(self):
            self.context = None

        async def execute(self, _command):
            self.context = workflow_log_context()
            return success_event()

    client = FakeStreamClient()
    executor = ContextCapturingExecutor()
    worker = WorkflowStreamWorker(client, executor)

    await worker.process(message())

    assert executor.context == {
        "traceId": "123e4567e89b12d3a456426614174000",
        "correlationId": "123e4567-e89b-12d3-a456-426614174000",
        "workflowRunId": "7",
        "nodeRunId": "11",
        "executionId": "7:fixture:1:1",
    }
    assert workflow_log_context() == {}


@pytest.mark.asyncio
async def test_worker_span_continues_remote_w3c_trace():
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(
        client,
        StubExecutor(success_event()),
        tracer=provider.get_tracer("test.agent-worker"),
    )

    try:
        await worker.process(message())
    finally:
        provider.shutdown()

    spans = exporter.get_finished_spans()
    assert len(spans) == 1
    span = spans[0]
    assert span.name == "workflow.node.execute"
    assert span.kind == SpanKind.CONSUMER
    assert span.context.trace_id == int(
        "123e4567e89b12d3a456426614174000",
        16,
    )
    assert span.parent.span_id == int("123e4567e89b12d3", 16)
    assert span.attributes["messaging.system"] == "redis"
    assert span.attributes["autospec.workflow.run.id"] == 7
    assert span.attributes["autospec.workflow.node.run.id"] == 11
    assert span.attributes["autospec.workflow.event.type"] == "NODE_SUCCEEDED"


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
async def test_worker_exit_after_terminal_publish_replays_same_event_before_ack():
    class ExitBeforeFirstAckClient(FakeStreamClient):
        def __init__(self):
            super().__init__()
            self.ack_attempts = 0

        async def acknowledge(self, stream, group, message_id):
            self.ack_attempts += 1
            if self.ack_attempts == 1:
                raise ConnectionError("worker exited before command ack")
            await super().acknowledge(stream, group, message_id)

    client = ExitBeforeFirstAckClient()
    first_worker = WorkflowStreamWorker(client, StubExecutor(success_event()))
    failure_started_at = time.perf_counter()

    with pytest.raises(ConnectionError, match="worker exited before command ack"):
        await first_worker.process(message())
    failure_detected_at = time.perf_counter()

    recovery_worker = WorkflowStreamWorker(client, StubExecutor(success_event()))
    recovered_event = await recovery_worker.process(message())
    recovered_at = time.perf_counter()

    assert [event.event_id for _, event in client.published] == [
        "7:fixture:1:1:succeeded",
        "7:fixture:1:1:succeeded",
    ]
    assert recovered_event.event_id == "7:fixture:1:1:succeeded"
    assert client.acknowledged == [
        ("autospec.workflow.commands", "autospec-workers", "1710000000000-0")
    ]
    print(
        "failureDrill=worker-exit-after-terminal-publish "
        f"detectionMs={round((failure_detected_at - failure_started_at) * 1000)} "
        f"recoveryMs={round((recovered_at - failure_detected_at) * 1000)} "
        "duplicateDeliveries=1 eventCount=2 finalAckCount=1 "
        "manualRepairs=0 finalState=ACKNOWLEDGED"
    )


@pytest.mark.asyncio
async def test_worker_rejects_message_without_payload_without_acknowledging():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))

    with pytest.raises(InvalidWorkflowCommandError) as error:
        await worker.process(StreamMessage(message_id="1-0", fields={}))

    assert error.value.error_type == "MISSING_PAYLOAD"
    assert client.acknowledged == []


@pytest.mark.asyncio
async def test_worker_safely_classifies_invalid_json_without_acknowledging():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))

    with pytest.raises(InvalidWorkflowCommandError) as error:
        await worker.process(
            StreamMessage(message_id="1-0", fields={"payload": "{not-json"})
        )

    assert error.value.category == "PROTOCOL_VALIDATION"
    assert error.value.error_type == "INVALID_JSON"
    assert client.acknowledged == []


@pytest.mark.asyncio
async def test_worker_safely_classifies_invalid_trace_context():
    client = FakeStreamClient()
    worker = WorkflowStreamWorker(client, StubExecutor(success_event()))
    payload = json.loads(message().fields["payload"])
    payload["traceparent"] = "00-invalid"

    with pytest.raises(InvalidWorkflowCommandError) as error:
        await worker.process(
            StreamMessage(message_id="1-0", fields={"payload": json.dumps(payload)})
        )

    assert error.value.error_type == "SCHEMA_VALIDATION"
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
    heartbeat = next(
        published
        for _, published in client.published
        if published.event_type == "NODE_HEARTBEAT"
    )
    assert heartbeat.correlation_id == "123e4567-e89b-12d3-a456-426614174000"
    assert heartbeat.traceparent.endswith("-123e4567e89b12d3-01")
    assert len(client.acknowledged) == 1
