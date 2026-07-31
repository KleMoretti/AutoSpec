import pytest

from runtime.node_executor import NodeExecutionEvent
from runtime.redis_stream_client import RedisWorkflowStreamClient
from runtime.worker import InvalidWorkflowCommandError, StreamMessage


class ResponseError(Exception):
    pass


class FakeRedis:
    def __init__(self):
        self.calls = []
        self.read_response = []
        self.claim_response = (b"0-0", [], [])
        self.group_error = None

    async def xgroup_create(self, **kwargs):
        self.calls.append(("xgroup_create", kwargs))
        if self.group_error:
            raise self.group_error

    async def xreadgroup(self, **kwargs):
        self.calls.append(("xreadgroup", kwargs))
        return self.read_response

    async def xadd(self, stream, fields, **kwargs):
        self.calls.append(("xadd", stream, fields, kwargs))
        return b"1710000000001-0"

    async def xack(self, stream, group, message_id):
        self.calls.append(("xack", stream, group, message_id))
        return 1

    async def xautoclaim(self, **kwargs):
        self.calls.append(("xautoclaim", kwargs))
        return self.claim_response


def event():
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
        correlation_id="123e4567-e89b-12d3-a456-426614174000",
        traceparent="00-123e4567e89b12d3a456426614174000-123e4567e89b12d3-01",
    )


@pytest.mark.asyncio
async def test_ensure_group_creates_stream_and_ignores_busygroup():
    redis = FakeRedis()
    client = RedisWorkflowStreamClient(redis)

    await client.ensure_group("commands", "workers")
    redis.group_error = ResponseError("BUSYGROUP Consumer Group name already exists")
    await client.ensure_group("commands", "workers")

    assert redis.calls[0] == (
        "xgroup_create",
        {"name": "commands", "groupname": "workers", "id": "0-0", "mkstream": True},
    )


@pytest.mark.asyncio
async def test_read_commands_decodes_stream_message():
    redis = FakeRedis()
    redis.read_response = [
        (b"commands", [(b"171-0", {b"payload": b'{"event_id":"c1"}'})])
    ]
    client = RedisWorkflowStreamClient(redis)

    messages = await client.read_commands("commands", "workers", "worker-1", 5000, 10)

    assert messages[0].message_id == "171-0"
    assert messages[0].fields == {"payload": '{"event_id":"c1"}'}


@pytest.mark.asyncio
async def test_publish_and_acknowledge_use_stream_commands():
    redis = FakeRedis()
    client = RedisWorkflowStreamClient(redis, event_stream_max_length=250)

    await client.publish_event("events", event())
    await client.acknowledge("commands", "workers", "171-0")

    xadd = redis.calls[0]
    assert xadd[0:2] == ("xadd", "events")
    assert '"event_type":"NODE_SUCCEEDED"' in xadd[2]["payload"]
    assert '"correlation_id":"123e4567-e89b-12d3-a456-426614174000"' in xadd[2]["payload"]
    assert '"traceparent":"00-123e4567e89b12d3a456426614174000-123e4567e89b12d3-01"' in xadd[2]["payload"]
    assert xadd[3] == {"maxlen": 250, "approximate": True}
    assert redis.calls[1] == ("xack", "commands", "workers", "171-0")


@pytest.mark.asyncio
async def test_publish_dead_letter_preserves_source_and_original_fields_safely():
    redis = FakeRedis()
    client = RedisWorkflowStreamClient(redis, dead_letter_stream_max_length=50)
    message = StreamMessage("171-0", {"payload": "{not-json", "trace_id": "t-1"})

    await client.publish_dead_letter(
        "commands.dlq",
        "commands",
        message,
        InvalidWorkflowCommandError("INVALID_JSON"),
    )

    fields = redis.calls[0][2]
    assert fields == {
        "source_stream": "commands",
        "source_message_id": "171-0",
        "error_category": "PROTOCOL_VALIDATION",
        "error_type": "INVALID_JSON",
        "original_fields": '{"payload":"{not-json","trace_id":"t-1"}',
    }
    assert "exception" not in fields
    assert redis.calls[0][3] == {"maxlen": 50, "approximate": True}


@pytest.mark.asyncio
async def test_claim_stale_commands_decodes_messages_and_advances_cursor():
    redis = FakeRedis()
    redis.claim_response = (
        b"173-0",
        [(b"172-0", {b"payload": b'{"event_id":"c2"}'})],
        [],
    )
    client = RedisWorkflowStreamClient(redis)

    messages = await client.claim_stale_commands(
        "commands", "workers", "worker-2", minimum_idle_ms=30000, count=5
    )

    assert [message.message_id for message in messages] == ["172-0"]
    assert redis.calls[-1][1]["start_id"] == "0-0"

    redis.claim_response = (b"0-0", [], [])
    await client.claim_stale_commands(
        "commands", "workers", "worker-2", minimum_idle_ms=30000, count=5
    )

    assert redis.calls[-1][1]["start_id"] == "173-0"
