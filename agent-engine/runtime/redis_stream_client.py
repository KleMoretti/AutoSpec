from __future__ import annotations

from typing import Any

from runtime.node_executor import NodeExecutionEvent
from runtime.worker import StreamMessage


class RedisWorkflowStreamClient:
    def __init__(self, redis_client: Any) -> None:
        self._redis = redis_client

    @classmethod
    def from_url(cls, redis_url: str) -> "RedisWorkflowStreamClient":
        from redis.asyncio import Redis

        return cls(Redis.from_url(redis_url, decode_responses=False))

    async def ensure_group(self, stream: str, group: str) -> None:
        try:
            await self._redis.xgroup_create(
                name=stream,
                groupname=group,
                id="0-0",
                mkstream=True,
            )
        except Exception as exception:  # redis exception type is adapter-owned.
            if "BUSYGROUP" not in str(exception):
                raise

    async def read_commands(
        self,
        stream: str,
        group: str,
        consumer: str,
        block_ms: int = 5000,
        count: int = 10,
    ) -> list[StreamMessage]:
        response = await self._redis.xreadgroup(
            groupname=group,
            consumername=consumer,
            streams={stream: ">"},
            count=count,
            block=block_ms,
        )
        return self._decode_read_response(response)

    async def publish_event(self, stream: str, event: NodeExecutionEvent) -> None:
        await self._redis.xadd(stream, {"payload": event.model_dump_json()})

    async def acknowledge(self, stream: str, group: str, message_id: str) -> None:
        await self._redis.xack(stream, group, message_id)

    async def claim_stale_commands(
        self,
        stream: str,
        group: str,
        consumer: str,
        minimum_idle_ms: int,
        count: int = 10,
    ) -> list[StreamMessage]:
        response = await self._redis.xautoclaim(
            name=stream,
            groupname=group,
            consumername=consumer,
            min_idle_time=minimum_idle_ms,
            start_id="0-0",
            count=count,
        )
        entries = response[1] if response and len(response) > 1 else []
        return [self._decode_message(message_id, fields) for message_id, fields in entries]

    def _decode_read_response(self, response: Any) -> list[StreamMessage]:
        messages: list[StreamMessage] = []
        for _stream, entries in response or []:
            messages.extend(
                self._decode_message(message_id, fields)
                for message_id, fields in entries
            )
        return messages

    def _decode_message(self, message_id: Any, fields: dict[Any, Any]) -> StreamMessage:
        return StreamMessage(
            message_id=self._decode_value(message_id),
            fields={
                self._decode_value(key): self._decode_value(value)
                for key, value in fields.items()
            },
        )

    def _decode_value(self, value: Any) -> Any:
        return value.decode("utf-8") if isinstance(value, bytes) else value
