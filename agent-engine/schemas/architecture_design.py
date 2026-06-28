from pydantic import BaseModel, ConfigDict, Field


class ModuleDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    responsibility: str = Field(min_length=1)
    depends_on: list[str] = Field(default_factory=list)


class DecisionRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1)
    context: str = Field(min_length=1)
    decision: str = Field(min_length=1)
    consequences: list[str] = Field(default_factory=list)


class NonFunctionalConstraint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str = Field(min_length=1)
    requirement: str = Field(min_length=1)


class ArchitectureDesignArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    system_context: str = Field(min_length=1)
    modules: list[ModuleDesign] = Field(min_length=1)
    decisions: list[DecisionRecord] = Field(default_factory=list)
    non_functional_constraints: list[NonFunctionalConstraint] = Field(default_factory=list)
    integration_risks: list[str] = Field(default_factory=list)
