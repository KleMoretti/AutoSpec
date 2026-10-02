"""Strict, executable software-domain contract consumed by the verifier."""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


COMPONENT_ID = r"^[A-Za-z][A-Za-z0-9_-]{0,63}$"
SQL_IDENTIFIER = r"^[A-Za-z][A-Za-z0-9_]{0,63}$"
SAFE_PATH = r"^/[A-Za-z0-9_./{}-]*$"
METHODS = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
FIELD_KINDS = Literal[
    "string", "integer", "bigint", "decimal", "boolean", "date", "datetime", "json"
]
LOCATIONS = Literal["path", "query", "body"]


class SpecBase(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SpecFieldType(SpecBase):
    kind: FIELD_KINDS
    length: int | None = Field(default=None, ge=1, le=65_535)
    precision: int | None = Field(default=None, ge=1, le=65)
    scale: int | None = Field(default=None, ge=0, le=30)

    @model_validator(mode="after")
    def validate_shape(self) -> "SpecFieldType":
        if self.kind == "string" and self.length is None:
            raise ValueError("string fields require length")
        if self.kind == "decimal":
            if self.precision is None or self.scale is None:
                raise ValueError("decimal fields require precision and scale")
            if self.scale > self.precision:
                raise ValueError("decimal scale must not exceed precision")
        if self.kind != "string" and self.length is not None:
            raise ValueError("length is only valid for string fields")
        if self.kind != "decimal" and (self.precision is not None or self.scale is not None):
            raise ValueError("precision and scale are only valid for decimal fields")
        return self


class SpecForeignKey(SpecBase):
    table: str = Field(pattern=SQL_IDENTIFIER)
    field: str = Field(pattern=SQL_IDENTIFIER)


class SpecField(SpecBase):
    field_id: str = Field(pattern=COMPONENT_ID)
    name: str = Field(pattern=SQL_IDENTIFIER)
    type: SpecFieldType
    nullable: bool = True
    primary_key: bool = False
    unique: bool = False
    default: str | int | float | bool | None = None
    foreign_key: SpecForeignKey | None = None
    requirement_refs: list[str] = Field(default_factory=list)


class SpecTable(SpecBase):
    table_id: str = Field(pattern=COMPONENT_ID)
    name: str = Field(pattern=SQL_IDENTIFIER)
    fields: list[SpecField] = Field(min_length=1)
    requirement_refs: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_fields(self) -> "SpecTable":
        names = [field.name for field in self.fields]
        ids = [field.field_id for field in self.fields]
        if len(names) != len(set(names)):
            raise ValueError(f"duplicate table field names: {self.name}")
        if len(ids) != len(set(ids)):
            raise ValueError(f"duplicate table field ids: {self.name}")
        return self


class SpecParameter(SpecBase):
    name: str = Field(pattern=SQL_IDENTIFIER)
    location: LOCATIONS
    type: SpecFieldType
    required: bool = False
    requirement_refs: list[str] = Field(default_factory=list)


class SpecResponseField(SpecBase):
    name: str = Field(pattern=SQL_IDENTIFIER)
    type: SpecFieldType
    nullable: bool = False
    requirement_refs: list[str] = Field(default_factory=list)


class SpecApi(SpecBase):
    api_id: str = Field(pattern=COMPONENT_ID)
    method: METHODS
    path: str = Field(pattern=SAFE_PATH)
    parameters: list[SpecParameter] = Field(default_factory=list)
    responses: list[SpecResponseField] = Field(default_factory=list)
    auth_required: bool = False
    roles: list[str] = Field(default_factory=list)
    requirement_refs: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_parameters(self) -> "SpecApi":
        names = [(item.location, item.name) for item in self.parameters]
        if len(names) != len(set(names)):
            raise ValueError(f"duplicate API parameters: {self.api_id}")
        path_params = set(re.findall(r"\{([A-Za-z][A-Za-z0-9_]*)\}", self.path))
        declared_path_params = {
            item.name for item in self.parameters if item.location == "path"
        }
        if path_params != declared_path_params:
            raise ValueError(f"path parameters do not match API declaration: {self.api_id}")
        return self


class SpecApiV2(SpecApi):
    """API shape with an explicit successful response status."""

    success_status: int = Field(ge=200, le=299)


class SpecBindingParameter(SpecBase):
    name: str = Field(pattern=SQL_IDENTIFIER)
    source: str = Field(min_length=1, max_length=256)


class SpecFrontendBinding(SpecBase):
    binding_id: str = Field(pattern=COMPONENT_ID)
    component: str = Field(pattern=SQL_IDENTIFIER)
    api_id: str = Field(pattern=COMPONENT_ID)
    method: METHODS
    path: str = Field(pattern=SAFE_PATH)
    parameters: list[SpecBindingParameter] = Field(default_factory=list)
    requirement_refs: list[str] = Field(default_factory=list)


class SpecBindingParameterV2(SpecBindingParameter):
    location: LOCATIONS
    type: SpecFieldType
    required: bool


class SpecBindingResponseFieldV2(SpecBase):
    path: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_.]*$")
    type: SpecFieldType
    nullable: bool


class SpecFrontendBindingV2(SpecFrontendBinding):
    parameters: list[SpecBindingParameterV2]
    response_fields: list[SpecBindingResponseFieldV2]

    @model_validator(mode="after")
    def validate_mapping_ids(self) -> "SpecFrontendBindingV2":
        parameter_ids = [(item.location, item.name) for item in self.parameters]
        if len(parameter_ids) != len(set(parameter_ids)):
            raise ValueError(f"duplicate binding parameters: {self.binding_id}")
        response_paths = [item.path for item in self.response_fields]
        if len(response_paths) != len(set(response_paths)):
            raise ValueError(f"duplicate binding response paths: {self.binding_id}")
        return self


class ExplicitType(SpecFieldType):
    """Shared type shape used by the explicit artifact versions."""

    pass


class SpecRequirement(SpecBase):
    requirement_id: str = Field(pattern=COMPONENT_ID)
    name: str = Field(min_length=1, max_length=256)
    priority: Literal["MUST", "SHOULD", "COULD"] = "MUST"
    acceptance_criteria: list[str] = Field(default_factory=list)


class SpecContract(SpecBase):
    schema_version: Literal["spec-contract-v1"] = "spec-contract-v1"
    contract_id: str = Field(pattern=COMPONENT_ID)
    requirements: list[SpecRequirement] = Field(min_length=1)
    tables: list[SpecTable] = Field(default_factory=list)
    apis: list[SpecApi] = Field(default_factory=list)
    frontend_bindings: list[SpecFrontendBinding] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_identity(self) -> "SpecContract":
        groups = (
            ("requirements", [item.requirement_id for item in self.requirements]),
            ("tables", [item.table_id for item in self.tables]),
            ("apis", [item.api_id for item in self.apis]),
            ("bindings", [item.binding_id for item in self.frontend_bindings]),
        )
        for name, values in groups:
            if len(values) != len(set(values)):
                raise ValueError(f"{name} contain duplicate stable ids")
        return self


class SpecContractV2(SpecContract):
    """Explicit contract with independent frontend request/response mappings."""

    schema_version: Literal["spec-contract-v2"] = "spec-contract-v2"
    apis: list[SpecApiV2] = Field(default_factory=list)
    frontend_bindings: list[SpecFrontendBindingV2] = Field(default_factory=list)


def normalise_spec_contract(value: SpecContract | dict) -> SpecContract:
    if isinstance(value, SpecContract):
        return value
    if value.get("schema_version") == "spec-contract-v2":
        return SpecContractV2.model_validate(value)
    return SpecContract.model_validate(value)
