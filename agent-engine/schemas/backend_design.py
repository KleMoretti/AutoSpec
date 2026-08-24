from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.citation import SourceCitation
from schemas.traceability import ComponentId, RequirementId, stable_id, unique_refs


HttpMethod = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]


class FieldDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field_id: ComponentId | None = None
    name: str = Field(min_length=1)
    type: str = Field(min_length=1)
    nullable: bool
    description: str = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def normalize_references(self) -> "FieldDesign":
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class TableDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    table_id: ComponentId | None = None
    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    fields: list[FieldDesign] = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "TableDesign":
        if self.table_id is None:
            self.table_id = stable_id("TABLE", self.name, self.description)
        self.requirement_refs = unique_refs(self.requirement_refs)
        for field in self.fields:
            if not field.requirement_refs:
                field.requirement_refs = list(self.requirement_refs)
            if field.field_id is None:
                field.field_id = stable_id("FIELD", self.table_id, field.name)
        return self


class RequestParam(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    type: str = Field(min_length=1)
    required: bool
    description: str = Field(min_length=1)


class ResponseField(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    type: str = Field(min_length=1)
    description: str = Field(min_length=1)


class ApiDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    api_id: ComponentId | None = None
    method: HttpMethod
    path: str = Field(pattern=r"^/.*")
    description: str = Field(min_length=1)
    request_params: list[RequestParam] = Field(default_factory=list)
    response_fields: list[ResponseField] = Field(default_factory=list)
    auth_required: bool
    required_roles: list[str] = Field(default_factory=list)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "ApiDesign":
        if self.api_id is None:
            self.api_id = stable_id("API", self.method, self.path)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class BackendDesignArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tables: list[TableDesign] = Field(min_length=1)
    apis: list[ApiDesign] = Field(min_length=1)
    source_citations: list[SourceCitation] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_component_ids(self) -> "BackendDesignArtifact":
        ids = [
            *(table.table_id for table in self.tables),
            *(field.field_id for table in self.tables for field in table.fields),
            *(api.api_id for api in self.apis),
        ]
        if len(ids) != len(set(ids)):
            raise ValueError("backend component ids must be unique")
        operations = [(api.method, api.path) for api in self.apis]
        if len(operations) != len(set(operations)):
            raise ValueError("backend API method/path pairs must be unique")
        return self
