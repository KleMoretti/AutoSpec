from __future__ import annotations

import json
from typing import Any

from runtime.node_executor import NodeExecutionEvent
from runtime.worker import InvalidWorkflowCommandError, StreamMessage


DEFAULT_EVENT_STREAM_MAX_LENGTH = 1_000_000
DEFAULT_DEAD_LETTER_STREAM_MAX_LENGTH = 100_000


class RedisWorkflowStreamClient:
    def __init__(
        self,
        redis_client: Any,
        event_stream_max_length: int = DEFAULT_EVENT_STREAM_MAX_LENGTH,
        dead_letter_stream_max_length: int = DEFAULT_DEAD_LETTER_STREAM_MAX_LENGTH,
    ) -> None:
        if event_stream_max_length < 1:
            raise ValueError("event_stream_max_length must be positive")
        if dead_letter_stream_max_length < 1:
            raise ValueError("dead_letter_stream_max_length must be positive")
        self._redis = redis_client
        self._event_stream_max_length = event_stream_max_length
        self._dead_letter_stream_max_length = dead_letter_stream_max_length
        self._claim_cursors: dict[tuple[str, str, str], str] = {}

    @classmethod
    def from_url(
        cls,
        redis_url: str,
        event_stream_max_length: int = DEFAULT_EVENT_STREAM_MAX_LENGTH,
        dead_letter_stream_max_length: int = DEFAULT_DEAD_LETTER_STREAM_MAX_LENGTH,
    ) -> "RedisWorkflowStreamClient":
        from redis.asyncio import Redis

        return cls(
            Redis.from_url(redis_url, decode_responses=False),
            event_stream_max_length=event_stream_max_length,
            dead_letter_stream_max_length=dead_letter_stream_max_length,
        )

    async def close(self) -> None:
        await self._redis.aclose()

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
        await self._redis.xadd(
            stream,
            {"payload": event.model_dump_json()},
            maxlen=self._event_stream_max_length,
            approximate=True,
        )

    async def acknowledge(self, stream: str, group: str, message_id: str) -> None:
        await self._redis.xack(stream, group, message_id)

    async def publish_dead_letter(
        self,
        stream: str,
        source_stream: str,
        message: StreamMessage,
        error: InvalidWorkflowCommandError,
    ) -> None:
        await self._redis.xadd(
            stream,
            {
                "source_stream": source_stream,
                "source_message_id": message.message_id,
                "error_category": error.category,
                "error_type": error.error_type,
                "original_fields": json.dumps(
                    message.fields,
                    ensure_ascii=False,
                    separators=(",", ":"),
                    default=self._json_default,
                ),
            },
            maxlen=self._dead_letter_stream_max_length,
            approximate=True,
        )

    async def claim_stale_commands(
        self,
        stream: str,
        group: str,
        consumer: str,
        minimum_idle_ms: int,
        count: int = 10,
    ) -> list[StreamMessage]:
        cursor_key = (stream, group, consumer)
        start_id = self._claim_cursors.get(cursor_key, "0-0")
        response = await self._redis.xautoclaim(
            name=stream,
            groupname=group,
            consumername=consumer,
            min_idle_time=minimum_idle_ms,
            start_id=start_id,
            count=count,
        )
        next_start_id = (
            self._decode_value(response[0])
            if response and len(response) > 0
            else "0-0"
        )
        self._claim_cursors[cursor_key] = next_start_id
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

    def _json_default(self, value: Any) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="replace")
        return str(value)
