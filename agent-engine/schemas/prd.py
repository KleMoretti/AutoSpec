from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Priority = Literal["MUST", "SHOULD", "COULD"]


class CoreFeature(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    priority: Priority


class UserStory(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: str = Field(min_length=1)
    goal: str = Field(min_length=1)
    benefit: str = Field(min_length=1)
    acceptance_criteria: list[str] = Field(default_factory=list)


class PrdArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str = Field(min_length=1)
    target_users: list[str] = Field(min_length=1)
    core_features: list[CoreFeature] = Field(min_length=1)
    user_stories: list[UserStory] = Field(min_length=1)
    business_boundaries: list[str] = Field(default_factory=list)
    non_functional_requirements: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
