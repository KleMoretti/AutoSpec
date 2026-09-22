from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.citation import SourceCitation
from schemas.backend_design import ApiDesign, FieldDesign
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


class SharedDomainModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    fields: list[FieldDesign] = Field(min_length=1)


class SharedDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    fields: list[FieldDesign] = Field(min_length=1)


class SharedErrorCode(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str = Field(min_length=1)
    http_status: int = Field(ge=400, le=599)
    meaning: str = Field(min_length=1)


class SharedPermission(BaseModel):
    model_config = ConfigDict(extra="forbid")

    api_id: ComponentId
    roles: list[str] = Field(default_factory=list)
    auth_required: bool


class SharedContract(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = Field(min_length=1)
    domain_models: list[SharedDomainModel] = Field(min_length=1)
    api_signatures: list[ApiDesign] = Field(min_length=1)
    dtos: list[SharedDto] = Field(min_length=1)
    error_codes: list[SharedErrorCode] = Field(min_length=1)
    permission_matrix: list[SharedPermission] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_permissions(self) -> "SharedContract":
        apis = {api.api_id: api for api in self.api_signatures}
        if len(apis) != len(self.api_signatures):
            raise ValueError("Shared API ids must be unique")
        if len({model.name for model in self.domain_models}) != len(self.domain_models):
            raise ValueError("Shared domain model names must be unique")
        if len({error.code for error in self.error_codes}) != len(self.error_codes):
            raise ValueError("Shared error codes must be unique")
        dtos = {dto.name: dto for dto in self.dtos}
        if len(dtos) != len(self.dtos):
            raise ValueError("Shared DTO names must be unique")
        for api in self.api_signatures:
            response_dto = dtos.get(f"{api.api_id}Response")
            if response_dto is None or [(field.name, field.type) for field in response_dto.fields] != [
                (field.name, field.type) for field in api.response_fields
            ]:
                raise ValueError(f"Shared response DTO conflicts with API {api.api_id}")
        if {item.api_id for item in self.permission_matrix} != set(apis):
            raise ValueError("Permission matrix must cover every shared API")
        for item in self.permission_matrix:
            api = apis[item.api_id]
            if item.auth_required != api.auth_required or set(item.roles) != set(api.required_roles):
                raise ValueError("Permission matrix conflicts with shared API")
        return self


class ArchitectureDesignArtifactV2(ArchitectureDesignArtifact):
    shared_contract: SharedContract
