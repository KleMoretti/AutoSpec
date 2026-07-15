import pytest

from runtime.node_executor import NodeExecutionEvent
from runtime.redis_stream_client import RedisWorkflowStreamClient


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

    async def xadd(self, stream, fields):
        self.calls.append(("xadd", stream, fields))
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
    client = RedisWorkflowStreamClient(redis)

    await client.publish_event("events", event())
    await client.acknowledge("commands", "workers", "171-0")

    xadd = redis.calls[0]
    assert xadd[0:2] == ("xadd", "events")
    assert '"event_type":"NODE_SUCCEEDED"' in xadd[2]["payload"]
    assert redis.calls[1] == ("xack", "commands", "workers", "171-0")


@pytest.mark.asyncio
async def test_claim_stale_commands_returns_decoded_messages():
    redis = FakeRedis()
    redis.claim_response = (
        b"0-0",
        [(b"172-0", {b"payload": b'{"event_id":"c2"}'})],
        [],
    )
    client = RedisWorkflowStreamClient(redis)

    messages = await client.claim_stale_commands(
        "commands", "workers", "worker-2", minimum_idle_ms=30000, count=5
    )

    assert [message.message_id for message in messages] == ["172-0"]
