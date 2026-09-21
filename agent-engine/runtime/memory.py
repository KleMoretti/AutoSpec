from __future__ import annotations

import json
import time
import uuid
from collections import defaultdict
from typing import Iterable, Protocol

from schemas.memory import (
    ConversationSummary,
    MemoryMessage,
    ProjectFactType,
    ProjectMemoryFact,
    SummaryEntry,
    SummaryTopic,
)


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


class ProjectMemory(Protocol):
    def upsert(self, fact: ProjectMemoryFact) -> ProjectMemoryFact: ...

    def recall(
        self,
        project_id: int,
        *,
        fact_types: set[ProjectFactType] | None = None,
        now_epoch_ms: int | None = None,
    ) -> list[ProjectMemoryFact]: ...


class InMemoryProjectMemory:
    """Fixture adapter with project isolation and immutable fact versions."""

    def __init__(self) -> None:
        self._facts: dict[int, list[ProjectMemoryFact]] = defaultdict(list)

    def upsert(self, fact: ProjectMemoryFact) -> ProjectMemoryFact:
        values = self._facts[fact.project_id]
        versions = [
            item.version
            for item in values
            if item.fact_type == fact.fact_type and item.fact_key == fact.fact_key
        ]
        if versions:
            fact = fact.model_copy(update={"version": max(versions) + 1})
        values.append(fact)
        return fact

    def recall(
        self,
        project_id: int,
        *,
        fact_types: set[ProjectFactType] | None = None,
        now_epoch_ms: int | None = None,
    ) -> list[ProjectMemoryFact]:
        now = round(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
        candidates = [
            fact
            for fact in self._facts.get(project_id, [])
            if (fact_types is None or fact.fact_type in fact_types)
            and fact.valid_from_epoch_ms <= now
            and (fact.valid_until_epoch_ms is None or fact.valid_until_epoch_ms > now)
            and (fact.expires_at_epoch_ms is None or fact.expires_at_epoch_ms > now)
            and fact.conflict_status != "SUPERSEDED"
        ]
        latest: dict[tuple[str, str], ProjectMemoryFact] = {}
        for fact in sorted(candidates, key=lambda item: (item.version, item.valid_from_epoch_ms)):
            latest[(fact.fact_type, fact.fact_key)] = fact
        return sorted(latest.values(), key=lambda item: (item.fact_type, item.fact_key))


def rebuild_summary(
    messages: Iterable[MemoryMessage],
    *,
    previous: ConversationSummary | None = None,
    model_version: str = "deterministic-structured-summary-v2",
    summary_version: str = "summary-v2",
    max_entries_per_topic: int = 8,
    now_epoch_ms: int | None = None,
) -> ConversationSummary | None:
    """Incrementally merge typed summary entries without character truncation."""

    values = list(messages)
    if not values and previous is None:
        return None
    now = round(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
    source_ids = list(previous.source_message_ids if previous is not None else [])
    seen_sources = set(source_ids)
    entries = list(previous.entries if previous is not None else [])
    known_statements = {
        (_normalize(entry.statement), entry.topic): index
        for index, entry in enumerate(entries)
    }

    for message in values:
        if message.message_id in seen_sources:
            continue
        seen_sources.add(message.message_id)
        source_ids.append(message.message_id)
        for statement in _statements(message.content):
            topic = _topic(statement)
            key = (_normalize(statement), topic)
            existing_index = known_statements.get(key)
            if existing_index is not None:
                existing = entries[existing_index]
                entries[existing_index] = existing.model_copy(
                    update={
                        "source_message_ids": [*existing.source_message_ids, message.message_id],
                        "updated_at_epoch_ms": max(
                            existing.updated_at_epoch_ms, message.created_at_epoch_ms
                        ),
                    }
                )
                continue
            known_statements[key] = len(entries)
            entries.append(
                SummaryEntry(
                    topic=topic,
                    statement=statement,
                    source_message_ids=[message.message_id],
                    updated_at_epoch_ms=message.created_at_epoch_ms,
                )
            )

    bounded: list[SummaryEntry] = []
    for topic in (
        "REQUIREMENT",
        "DECISION",
        "CONSTRAINT",
        "OPEN_QUESTION",
        "ACTION",
        "EVIDENCE",
        "GENERAL",
    ):
        selected = sorted(
            (entry for entry in entries if entry.topic == topic),
            key=lambda entry: entry.updated_at_epoch_ms,
        )[-max_entries_per_topic:]
        bounded.extend(selected)
    if not bounded:
        return None
    return ConversationSummary(
        summary_version=summary_version,
        model_version=model_version,
        source_message_ids=source_ids,
        entries=bounded,
        created_at_epoch_ms=now,
    )


def _statements(content: str) -> list[str]:
    text = content.strip()
    if not text:
        return []
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = None
    if isinstance(parsed, dict):
        return [
            f"{key}: {json.dumps(value, ensure_ascii=False, sort_keys=True)}"
            for key, value in sorted(parsed.items())
        ]
    return [line.strip(" -\t") for line in text.splitlines() if line.strip(" -\t")]


def _topic(statement: str) -> SummaryTopic:
    normalized = statement.casefold()
    rules: tuple[tuple[SummaryTopic, tuple[str, ...]], ...] = (
        ("OPEN_QUESTION", ("?", "？", "待确认", "open question", "unknown")),
        ("DECISION", ("决定", "decision", "采用", "choose", "selected")),
        ("CONSTRAINT", ("必须", "禁止", "constraint", "must", "cannot", "limit")),
        ("REQUIREMENT", ("需求", "requirement", "需要", "should", "user wants")),
        ("ACTION", ("todo", "下一步", "action", "follow up", "implement")),
        ("EVIDENCE", ("证据", "evidence", "source", "trace", "artifact")),
    )
    for topic, markers in rules:
        if any(marker in normalized for marker in markers):
            return topic
    return "GENERAL"


def _normalize(value: str) -> str:
    return " ".join(value.casefold().split())
