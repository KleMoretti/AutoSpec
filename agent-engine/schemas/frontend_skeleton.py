from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class RouteDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path: str = Field(pattern=r"^/.*")
    page: str = Field(min_length=1)


class PageDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    purpose: str = Field(min_length=1)
    components: list[str] = Field(default_factory=list)


class ComponentDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    type: Literal["form", "table", "timeline", "tabs", "preview", "toolbar", "layout"]
    props: list[str] = Field(default_factory=list)
    state: list[str] = Field(default_factory=list)


class ApiBinding(BaseModel):
    model_config = ConfigDict(extra="forbid")

    method: str = Field(min_length=1)
    path: str = Field(pattern=r"^/.*")
    consumer: str = Field(min_length=1)


class FrontendSkeletonArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    routes: list[RouteDesign] = Field(min_length=1)
    pages: list[PageDesign] = Field(min_length=1)
    components: list[ComponentDesign] = Field(min_length=1)
    api_bindings: list[ApiBinding] = Field(default_factory=list)
