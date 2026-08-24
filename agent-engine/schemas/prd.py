from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from schemas.citation import SourceCitation
from schemas.traceability import ComponentId, RequirementId, stable_id, unique_refs


Priority = Literal["MUST", "SHOULD", "COULD"]


class CoreFeature(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requirement_id: RequirementId | None = None
    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    priority: Priority

    @model_validator(mode="after")
    def assign_requirement_id(self) -> "CoreFeature":
        if self.requirement_id is None:
            self.requirement_id = stable_id("REQ", self.name, self.description)
        return self


class AcceptanceCriterion(BaseModel):
    model_config = ConfigDict(extra="forbid")

    acceptance_id: ComponentId | None = None
    criterion: str = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_acceptance_id(self) -> "AcceptanceCriterion":
        if self.acceptance_id is None:
            self.acceptance_id = stable_id("AC", self.criterion)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class UserStory(BaseModel):
    model_config = ConfigDict(extra="forbid")

    story_id: ComponentId | None = None
    role: str = Field(min_length=1)
    goal: str = Field(min_length=1)
    benefit: str = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)
    acceptance_criteria: list[AcceptanceCriterion] = Field(default_factory=list)

    @field_validator("acceptance_criteria", mode="before")
    @classmethod
    def accept_legacy_criteria(cls, value: object) -> object:
        if not isinstance(value, list):
            return value
        return [
            {"criterion": item} if isinstance(item, str) else item
            for item in value
        ]

    @model_validator(mode="after")
    def assign_story_identity(self) -> "UserStory":
        if self.story_id is None:
            self.story_id = stable_id("STORY", self.role, self.goal, self.benefit)
        self.requirement_refs = unique_refs(self.requirement_refs)
        for criterion in self.acceptance_criteria:
            if not criterion.requirement_refs:
                criterion.requirement_refs = list(self.requirement_refs)
        return self


class PrdArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str = Field(min_length=1)
    target_users: list[str] = Field(min_length=1)
    core_features: list[CoreFeature] = Field(min_length=1)
    user_stories: list[UserStory] = Field(min_length=1)
    business_boundaries: list[str] = Field(default_factory=list)
    non_functional_requirements: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    source_citations: list[SourceCitation] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_trace_identities(self) -> "PrdArtifact":
        requirement_ids = [feature.requirement_id for feature in self.core_features]
        if len(requirement_ids) != len(set(requirement_ids)):
            raise ValueError("core feature requirement_id values must be unique")
        known = set(requirement_ids)
        story_ids = [story.story_id for story in self.user_stories]
        if len(story_ids) != len(set(story_ids)):
            raise ValueError("user story story_id values must be unique")
        acceptance_ids = [
            criterion.acceptance_id
            for story in self.user_stories
            for criterion in story.acceptance_criteria
        ]
        if len(acceptance_ids) != len(set(acceptance_ids)):
            raise ValueError("acceptance_id values must be unique")
        referenced = {
            *(
                ref
                for story in self.user_stories
                for ref in story.requirement_refs
            ),
            *(
                ref
                for story in self.user_stories
                for criterion in story.acceptance_criteria
                for ref in criterion.requirement_refs
            ),
        }
        unknown = sorted(referenced - known)
        if unknown:
            raise ValueError(f"unknown PRD requirement refs: {', '.join(unknown)}")
        return self
