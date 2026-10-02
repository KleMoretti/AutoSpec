from typing import Any

from agents.base import ModelClient
from fixtures.software_domains import (
    explicit_backend_for_fixture,
    explicit_frontend_for_fixture,
    fixture_for_requirement,
)
from runtime.structured_output import generate_structured_output
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import (
    ExplicitFrontendSkeletonArtifact,
    FrontendSkeletonArtifact,
)
from schemas.prd import PrdArtifact
from schemas.traceability import fallback_requirement_mapping, remap_requirement_refs


class FrontendEngineerAgent:
    prompt_name = "FrontendEngineerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        prd: PrdArtifact,
        architecture_design: ArchitectureDesignArtifact,
        backend_design: BackendDesignArtifact | None = None,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
        rework_directive: dict[str, Any] | None = None,
        shared_contract_required: bool = False,
        explicit_contract_required: bool = False,
    ) -> FrontendSkeletonArtifact | ExplicitFrontendSkeletonArtifact:
        input_payload: dict[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "architecture_design": architecture_design.model_dump(),
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if rework_directive is not None:
            input_payload["rework_directive"] = rework_directive
        if backend_design is not None and not shared_contract_required:
            input_payload["backend_design"] = backend_design.model_dump()
        if self.model_client is not None:
            return generate_structured_output(
                self.model_client,
                (
                    "FrontendEngineerAgent_v4"
                    if explicit_contract_required
                    else "FrontendEngineerAgent_v2"
                    if shared_contract_required
                    else self.prompt_name
                ),
                input_payload,
                ExplicitFrontendSkeletonArtifact if explicit_contract_required else FrontendSkeletonArtifact,
            )

        fixture = fixture_for_requirement(requirement)
        if explicit_contract_required:
            return explicit_frontend_for_fixture(
                fixture, explicit_backend_for_fixture(fixture)
            )
        return fixture.frontend

        fallback = {
                "routes": [
                    {
                        "route_id": "ROUTE-PRODUCTS",
                        "path": "/products",
                        "page": "MarketplacePage",
                        "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE"],
                    },
                    {
                        "route_id": "ROUTE-ADMIN-AUDIT",
                        "path": "/admin/audit",
                        "page": "AdminAuditPage",
                        "requirement_refs": ["REQ-AUDIT"],
                    },
                ],
                "pages": [
                    {
                        "page_id": "PAGE-MARKETPLACE",
                        "name": "MarketplacePage",
                        "purpose": "Publish, search, browse, and favorite campus product listings.",
                        "components": ["ProductPublishForm", "ProductSearchList", "FavoriteButton"],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE"],
                    },
                    {
                        "page_id": "PAGE-ADMIN-AUDIT",
                        "name": "AdminAuditPage",
                        "purpose": "Allow administrators to approve or reject pending listings with reasons.",
                        "components": ["AdminAuditTable"],
                        "requirement_refs": ["REQ-AUDIT"],
                    },
                ],
                "components": [
                    {
                        "component_id": "COMP-PRODUCT-PUBLISH",
                        "name": "ProductPublishForm",
                        "type": "form",
                        "props": ["onSubmit"],
                        "state": ["draft", "submitting", "error"],
                        "requirement_refs": ["REQ-PUBLISH"],
                    },
                    {
                        "component_id": "COMP-PRODUCT-SEARCH",
                        "name": "ProductSearchList",
                        "type": "table",
                        "props": ["items", "onSearch"],
                        "state": ["keyword", "loading", "error"],
                        "requirement_refs": ["REQ-SEARCH"],
                    },
                    {
                        "component_id": "COMP-FAVORITE",
                        "name": "FavoriteButton",
                        "type": "toolbar",
                        "props": ["productId", "onFavorite"],
                        "state": ["saving", "error"],
                        "requirement_refs": ["REQ-FAVORITE"],
                    },
                    {
                        "component_id": "COMP-ADMIN-AUDIT",
                        "name": "AdminAuditTable",
                        "type": "table",
                        "props": ["pendingProducts", "onDecide"],
                        "state": ["submitting", "error"],
                        "requirement_refs": ["REQ-AUDIT"],
                    },
                ],
                "api_bindings": [
                    {
                        "binding_id": "BIND-PRODUCT-CREATE",
                        "method": "POST",
                        "path": "/api/products",
                        "consumer": "ProductPublishForm",
                        "backend_api_id": "API-PRODUCT-CREATE",
                        "requirement_refs": ["REQ-PUBLISH"],
                    },
                    {
                        "binding_id": "BIND-PRODUCT-SEARCH",
                        "method": "GET",
                        "path": "/api/products",
                        "consumer": "ProductSearchList",
                        "backend_api_id": "API-PRODUCT-SEARCH",
                        "requirement_refs": ["REQ-SEARCH"],
                    },
                    {
                        "binding_id": "BIND-FAVORITE-CREATE",
                        "method": "POST",
                        "path": "/api/favorites",
                        "consumer": "FavoriteButton",
                        "backend_api_id": "API-FAVORITE-CREATE",
                        "requirement_refs": ["REQ-FAVORITE"],
                    },
                    {
                        "binding_id": "BIND-PRODUCT-AUDIT",
                        "method": "POST",
                        "path": "/api/admin/products/{productId}/audit",
                        "consumer": "AdminAuditTable",
                        "backend_api_id": "API-PRODUCT-AUDIT",
                        "requirement_refs": ["REQ-AUDIT"],
                    },
                ],
            }
        return FrontendSkeletonArtifact.model_validate(
            remap_requirement_refs(
                fallback,
                fallback_requirement_mapping(
                    feature.requirement_id for feature in prd.core_features
                ),
            )
        )
