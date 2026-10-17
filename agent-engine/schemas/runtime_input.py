"""Control-plane envelope, separate from frozen historical Agent schemas."""

from functools import lru_cache
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, create_model


class RequirementBaselineInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    baseline_id: str = Field(min_length=1)
    version: int = Field(ge=1)
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    original_requirement: str
    answers: list[dict[str, Any]]
    assumptions: list[dict[str, Any]]
    conflict_resolutions: list[dict[str, Any]]
    context_conflicts: list[dict[str, Any]]
    scope: list[Any]
    constraints: list[Any]
    prd_artifact_id: int = Field(ge=1)
    prd_version: int = Field(ge=1)
    prd_content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    confirmed_by_user_id: int = Field(ge=1)
    confirmed_at: str


@lru_cache
def runtime_input_model(frozen_model: type[BaseModel]) -> type[BaseModel]:
    """Accept declared trusted metadata without changing published schema hashes."""
    return create_model(
        f"{frozen_model.__name__}EnvelopeV1",
        __base__=frozen_model,
        requirement_baseline=(RequirementBaselineInput | None, None),
        artifact_bindings=(dict[str, dict[str, Any]], Field(default_factory=dict)),
        project_memory=(list[dict[str, Any]], Field(default_factory=list)),
        context_conflicts=(list[dict[str, Any]], Field(default_factory=list)),
    )


def decision_context(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        key: payload[key]
        for key in ("requirement_baseline", "project_memory", "context_conflicts", "clarification_policy")
        if payload.get(key) is not None
    }
