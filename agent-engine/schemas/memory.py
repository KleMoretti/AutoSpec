from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


ProjectFactType = Literal[
    "REQUIREMENT",
    "DECISION",
    "CONSTRAINT",
    "ENTITY",
    "API",
    "ARTIFACT",
]
MemoryConflictStatus = Literal["ACTIVE", "CONFLICTING", "SUPERSEDED"]
SummaryTopic = Literal[
    "REQUIREMENT",
    "DECISION",
    "CONSTRAINT",
    "OPEN_QUESTION",
    "ACTION",
    "EVIDENCE",
    "GENERAL",
]


class MemoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message_id: str = Field(min_length=1)
    role: Literal["system", "user", "assistant", "tool"]
    content: str
    created_at_epoch_ms: int = Field(ge=0)


class SummaryEntry(BaseModel):
    model_config = ConfigDict(extra="forbid")

    topic: SummaryTopic
    statement: str = Field(min_length=1)
    source_message_ids: list[str] = Field(min_length=1)
    updated_at_epoch_ms: int = Field(ge=0)


class ConversationSummary(BaseModel):
    """Replaceable, structured conversation state with source-level provenance."""

    model_config = ConfigDict(extra="forbid")

    summary_version: str = Field(min_length=1)
    model_version: str = Field(min_length=1)
    source_message_ids: list[str] = Field(min_length=1)
    entries: list[SummaryEntry] = Field(min_length=1)
    created_at_epoch_ms: int = Field(ge=0)


class MemoryProvenance(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source_type: Literal["WORKFLOW_ARTIFACT", "HUMAN_EDIT", "IMPORT", "FIXTURE"]
    source_ref: str = Field(min_length=1)
    workflow_run_id: int | None = Field(default=None, ge=1)
    node_run_id: int | None = Field(default=None, ge=1)
    artifact_id: int | None = Field(default=None, ge=1)
    artifact_type: str | None = Field(default=None, min_length=1)
    artifact_version: int | None = Field(default=None, ge=1)


class ProjectMemoryFact(BaseModel):
    """A versioned project fact suitable for durable control-plane storage."""

    model_config = ConfigDict(extra="forbid")

    project_id: int = Field(ge=1)
    fact_type: ProjectFactType
    fact_key: str = Field(min_length=1, max_length=255)
    value: dict[str, Any]
    provenance: MemoryProvenance
    version: int = Field(default=1, ge=1)
    valid_from_epoch_ms: int = Field(ge=0)
    valid_until_epoch_ms: int | None = Field(default=None, ge=0)
    expires_at_epoch_ms: int | None = Field(default=None, ge=0)
    conflict_status: MemoryConflictStatus = "ACTIVE"
    content_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @model_validator(mode="after")
    def validate_time_range(self) -> "ProjectMemoryFact":
        if (
            self.valid_until_epoch_ms is not None
            and self.valid_until_epoch_ms <= self.valid_from_epoch_ms
        ):
            raise ValueError("valid_until_epoch_ms must be after valid_from_epoch_ms")
        if (
            self.expires_at_epoch_ms is not None
            and self.expires_at_epoch_ms <= self.valid_from_epoch_ms
        ):
            raise ValueError("expires_at_epoch_ms must be after valid_from_epoch_ms")
        return self
