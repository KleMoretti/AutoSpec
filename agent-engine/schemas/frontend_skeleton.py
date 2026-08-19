from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.citation import SourceCitation
from schemas.backend_design import HttpMethod
from schemas.traceability import ComponentId, RequirementId, stable_id, unique_refs


class RouteDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    route_id: ComponentId | None = None
    path: str = Field(pattern=r"^/.*")
    page: str = Field(min_length=1)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "RouteDesign":
        if self.route_id is None:
            self.route_id = stable_id("ROUTE", self.path, self.page)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class PageDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    page_id: ComponentId | None = None
    name: str = Field(min_length=1)
    purpose: str = Field(min_length=1)
    components: list[str] = Field(default_factory=list)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "PageDesign":
        if self.page_id is None:
            self.page_id = stable_id("PAGE", self.name, self.purpose)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class ComponentDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    component_id: ComponentId | None = None
    name: str = Field(min_length=1)
    type: Literal["form", "table", "timeline", "tabs", "preview", "toolbar", "layout"]
    props: list[str] = Field(default_factory=list)
    state: list[str] = Field(default_factory=list)
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "ComponentDesign":
        if self.component_id is None:
            self.component_id = stable_id("COMP", self.name, self.type)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class ApiBinding(BaseModel):
    model_config = ConfigDict(extra="forbid")

    binding_id: ComponentId | None = None
    method: HttpMethod
    path: str = Field(pattern=r"^/.*")
    consumer: str = Field(min_length=1)
    backend_api_id: ComponentId | None = None
    requirement_refs: list[RequirementId] = Field(default_factory=list)

    @model_validator(mode="after")
    def assign_identity(self) -> "ApiBinding":
        if self.binding_id is None:
            self.binding_id = stable_id("BIND", self.method, self.path, self.consumer)
        self.requirement_refs = unique_refs(self.requirement_refs)
        return self


class FrontendSkeletonArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    routes: list[RouteDesign] = Field(min_length=1)
    pages: list[PageDesign] = Field(min_length=1)
    components: list[ComponentDesign] = Field(min_length=1)
    api_bindings: list[ApiBinding] = Field(default_factory=list)
    source_citations: list[SourceCitation] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_internal_references(self) -> "FrontendSkeletonArtifact":
        pages = {page.name for page in self.pages}
        components = {component.name for component in self.components}
        unknown_pages = sorted({route.page for route in self.routes} - pages)
        unknown_components = sorted(
            {
                *(
                    component
                    for page in self.pages
                    for component in page.components
                ),
                *(binding.consumer for binding in self.api_bindings),
            }
            - components
        )
        if unknown_pages:
            raise ValueError(f"routes reference unknown pages: {', '.join(unknown_pages)}")
        if unknown_components:
            raise ValueError(
                f"pages or bindings reference unknown components: {', '.join(unknown_components)}"
            )
        ids = [
            *(route.route_id for route in self.routes),
            *(page.page_id for page in self.pages),
            *(component.component_id for component in self.components),
            *(binding.binding_id for binding in self.api_bindings),
        ]
        if len(ids) != len(set(ids)):
            raise ValueError("frontend component ids must be unique")
        return self
