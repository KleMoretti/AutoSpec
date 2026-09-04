from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class MemoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message_id: str = Field(min_length=1)
    role: Literal["system", "user", "assistant", "tool"]
    content: str
    created_at_epoch_ms: int = Field(ge=0)


class ConversationSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary_version: str = Field(min_length=1)
    model_version: str = Field(min_length=1)
    source_message_ids: list[str] = Field(min_length=1)
    content: str = Field(min_length=1)
    created_at_epoch_ms: int = Field(ge=0)


class MemoryRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(min_length=1)
    skill: str = Field(min_length=1)
    assessment: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)
    evidence_ref: str = Field(min_length=1)
    source_session_id: str = Field(min_length=1)
    created_at_epoch_ms: int = Field(ge=0)
    expires_at_epoch_ms: int | None = Field(default=None, ge=0)
    version: int = Field(default=1, ge=1)

