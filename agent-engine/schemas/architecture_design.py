from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.citation import SourceCitation
from schemas.traceability import ComponentId, RequirementId, stable_id, unique_refs


class ModuleDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    module_id: ComponentId | None = None
    name: str = Field(min_length=1)
    responsibility: str = Field(min_length=1)
    depends_on: list[str] = Field(default_factory=list)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "ModuleDesign":
        if self.module_id is None:
            self.module_id = stable_id("MOD", self.name, self.responsibility)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class DecisionRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision_id: ComponentId | None = None
    title: str = Field(min_length=1)
    context: str = Field(min_length=1)
    decision: str = Field(min_length=1)
    consequences: list[str] = Field(default_factory=list)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "DecisionRecord":
        if self.decision_id is None:
            self.decision_id = stable_id("ADR", self.title, self.decision)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class NonFunctionalConstraint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    constraint_id: ComponentId | None = None
    category: str = Field(min_length=1)
    requirement: str = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "NonFunctionalConstraint":
        if self.constraint_id is None:
            self.constraint_id = stable_id("NFR", self.category, self.requirement)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class ArchitectureDesignArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    system_context: str = Field(min_length=1)
    modules: list[ModuleDesign] = Field(min_length=1)
    decisions: list[DecisionRecord] = Field(default_factory=list)
    non_functional_constraints: list[NonFunctionalConstraint] = Field(default_factory=list)
    integration_risks: list[str] = Field(default_factory=list)
    source_citations: list[SourceCitation] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_component_ids(self) -> "ArchitectureDesignArtifact":
        ids = [
            *(module.module_id for module in self.modules),
            *(decision.decision_id for decision in self.decisions),
            *(constraint.constraint_id for constraint in self.non_functional_constraints),
        ]
        if len(ids) != len(set(ids)):
            raise ValueError("architecture component ids must be unique")
        return self
