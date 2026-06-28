from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


HttpMethod = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]


class FieldDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    type: str = Field(min_length=1)
    nullable: bool
    description: str = Field(min_length=1)


class TableDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    fields: list[FieldDesign] = Field(min_length=1)


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

    method: HttpMethod
    path: str = Field(pattern=r"^/.*")
    description: str = Field(min_length=1)
    request_params: list[RequestParam] = Field(default_factory=list)
    response_fields: list[ResponseField] = Field(default_factory=list)
    auth_required: bool
    required_roles: list[str] = Field(default_factory=list)


class BackendDesignArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tables: list[TableDesign] = Field(min_length=1)
    apis: list[ApiDesign] = Field(min_length=1)
