from __future__ import annotations

import time
import uuid
from collections import defaultdict
from typing import Iterable, Protocol

from schemas.memory import ConversationSummary, MemoryMessage, MemoryRecord


class ShortTermMemory:
    """A bounded view of the current conversation; raw messages remain intact."""

    def __init__(self, messages: Iterable[MemoryMessage] | None = None) -> None:
        self._messages = list(messages or [])

    def append(
        self,
        role: str,
        content: str,
        *,
        message_id: str | None = None,
        created_at_epoch_ms: int | None = None,
    ) -> MemoryMessage:
        message = MemoryMessage(
            message_id=message_id or uuid.uuid4().hex,
            role=role,
            content=content,
            created_at_epoch_ms=(
                round(time.time() * 1000)
                if created_at_epoch_ms is None
                else created_at_epoch_ms
            ),
        )
        self._messages.append(message)
        return message

    def all(self) -> list[MemoryMessage]:
        return list(self._messages)

    def recent(self, limit: int = 5) -> list[MemoryMessage]:
        if limit < 1:
            return []
        return self._messages[-limit:]

    def snapshot(self) -> dict[str, object]:
        return {"messages": [message.model_dump(mode="json") for message in self._messages]}


class LongTermMemory(Protocol):
    def upsert(self, record: MemoryRecord) -> MemoryRecord: ...

    def recall(
        self,
        user_id: str,
        *,
        skill: str | None = None,
        now_epoch_ms: int | None = None,
    ) -> list[MemoryRecord]: ...


class InMemoryLongTermMemory:
    """Fixture implementation with user isolation and evidence-preserving merges."""

    def __init__(self) -> None:
        self._records: dict[str, list[MemoryRecord]] = defaultdict(list)

    def upsert(self, record: MemoryRecord) -> MemoryRecord:
        values = self._records[record.user_id]
        current = [item for item in values if item.skill == record.skill]
        if current:
            record = record.model_copy(
                update={"version": max(item.version for item in current) + 1}
            )
        values.append(record)
        return record

    def recall(
        self,
        user_id: str,
        *,
        skill: str | None = None,
        now_epoch_ms: int | None = None,
    ) -> list[MemoryRecord]:
        now = round(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
        records = [
            record
            for record in self._records.get(user_id, [])
            if (skill is None or record.skill == skill)
            and (record.expires_at_epoch_ms is None or record.expires_at_epoch_ms > now)
        ]
        # One current observation per skill is enough for prompt context. Older
        # conflicting observations stay stored and can be inspected by evidence_ref.
        latest: dict[str, MemoryRecord] = {}
        for record in sorted(records, key=lambda item: (item.version, item.created_at_epoch_ms)):
            previous = latest.get(record.skill)
            if previous is None or (record.confidence, record.version) >= (
                previous.confidence,
                previous.version,
            ):
                latest[record.skill] = record
        return sorted(latest.values(), key=lambda item: item.skill)


def rebuild_summary(
    messages: Iterable[MemoryMessage],
    *,
    model_version: str = "deterministic-summary-v1",
    summary_version: str = "summary-v1",
    max_characters: int = 2_000,
    now_epoch_ms: int | None = None,
) -> ConversationSummary | None:
    """Create a replaceable summary while retaining message ids as its source range."""

    values = list(messages)
    if not values:
        return None
    content = "\n".join(f"{message.role}: {message.content}" for message in values)
    if len(content) > max_characters:
        content = content[: max_characters - 1].rstrip() + "…"
    return ConversationSummary(
        summary_version=summary_version,
        model_version=model_version,
        source_message_ids=[message.message_id for message in values],
        content=content,
        created_at_epoch_ms=(
            round(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
        ),
    )

