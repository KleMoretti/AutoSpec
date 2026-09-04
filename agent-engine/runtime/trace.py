from __future__ import annotations

from typing import Any, Iterable
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field


class TraceRecord(BaseModel):
    """De-identified, replayable record for one node or tool boundary."""

    model_config = ConfigDict(extra="forbid")

    trace_id: str = Field(min_length=1)
    session_id: str = Field(min_length=1)
    span_id: str = Field(default_factory=lambda: uuid4().hex, min_length=1)
    parent_span_id: str | None = Field(default=None, min_length=1)
    node: str = Field(min_length=1)
    step: int = Field(ge=1)
    execution_id: str | None = Field(default=None, min_length=1)
    status: str = Field(min_length=1)
    model_version: str | None = Field(default=None, min_length=1)
    prompt_version: str | None = Field(default=None, min_length=1)
    tool_version: str | None = Field(default=None, min_length=1)
    input_schema_version: str | None = Field(default=None, min_length=1)
    output_schema_version: str | None = Field(default=None, min_length=1)
    input_ref: str | None = Field(default=None, min_length=1)
    output_ref: str | None = Field(default=None, min_length=1)
    token_usage: dict[str, int] = Field(default_factory=dict)
    cost: float = Field(default=0.0, ge=0.0)
    latency_ms: int = Field(default=0, ge=0)
    retry_count: int = Field(default=0, ge=0)
    tool_call_count: int = Field(default=0, ge=0)
    retrieved_document_ids: list[str] = Field(default_factory=list)
    tool_calls: list[dict[str, str]] = Field(default_factory=list)
    route_action: str | None = Field(default=None, min_length=1)
    route_reason: str | None = Field(default=None, max_length=1000)
    error_type: str | None = Field(default=None, min_length=1)


class TraceRecorder:
    """Collect trace metadata without retaining prompts, answers, or resumes."""

    def __init__(self, trace_id: str | None = None, session_id: str | None = None) -> None:
        self.trace_id = trace_id or uuid4().hex
        self.session_id = session_id or self.trace_id
        self._records: list[TraceRecord] = []

    def record(self, record: TraceRecord | dict[str, Any], **overrides: Any) -> TraceRecord:
        values = record.model_dump(mode="python") if isinstance(record, TraceRecord) else dict(record)
        values.update(overrides)
        values.setdefault("trace_id", self.trace_id)
        values.setdefault("session_id", self.session_id)
        parsed = TraceRecord.model_validate(values)
        self._records.append(parsed)
        return parsed

    def snapshot(self) -> list[dict[str, Any]]:
        return [record.model_dump(mode="json") for record in self.replay()]

    def replay(self) -> list[TraceRecord]:
        return replay_trace(self._records)


def replay_trace(records: Iterable[TraceRecord | dict[str, Any]]) -> list[TraceRecord]:
    parsed = [
        record if isinstance(record, TraceRecord) else TraceRecord.model_validate(record)
        for record in records
    ]
    return sorted(parsed, key=lambda record: (record.step, record.span_id))
