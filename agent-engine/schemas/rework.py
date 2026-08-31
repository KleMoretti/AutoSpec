from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ReworkArtifactReference(BaseModel):
    model_config = ConfigDict(extra="forbid")

    node_run_id: int | None = Field(default=None, ge=1)
    revision: int | None = Field(default=None, ge=1)
    artifact_id: int | None = Field(default=None, ge=1)
    artifact_version: int | None = Field(default=None, ge=1)
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


class ReworkPreservationPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    preserve_unaffected_stable_ids: bool = True
    preserve_unaffected_approved_decisions: bool = True
    preserved_node_ids: list[str] = Field(default_factory=list)


class ReworkAllowedChangeScope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mode: str = Field(pattern=r"^ISSUE_SCOPED$")
    issue_ids: list[str] = Field(min_length=1)
    artifact_paths: list[str] = Field(default_factory=list)


class ReworkDirective(BaseModel):
    model_config = ConfigDict(extra="forbid")

    review_round: int = Field(ge=1)
    target_node: str = Field(min_length=1)
    reviewer_output_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    reviewer_node_id: str = Field(min_length=1)
    reviewer_node_run_id: int | None = Field(default=None, ge=1)
    reviewer_revision: int | None = Field(default=None, ge=1)
    reviewer_execution_id: str | None = Field(default=None, min_length=1)
    issue_ids: list[str] = Field(min_length=1)
    required_changes: list[str] = Field(min_length=1)
    issues: list[dict[str, Any]] = Field(default_factory=list)
    evidence_paths: list[str] = Field(default_factory=list)
    preservation_policy: ReworkPreservationPolicy
    allowed_change_scope: ReworkAllowedChangeScope
    invalidated_downstream_node_ids: list[str] = Field(default_factory=list)
    previous_artifact: ReworkArtifactReference
    reviewer_artifact: ReworkArtifactReference | None = None
    directive_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
