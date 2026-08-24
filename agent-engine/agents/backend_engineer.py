from typing import Any, Mapping

from agents.base import ModelClient
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact
from schemas.traceability import fallback_requirement_mapping, remap_requirement_refs


class BackendEngineerAgent:
    prompt_name = "BackendEngineerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        prd: PrdArtifact,
        architecture_design: ArchitectureDesignArtifact | None = None,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
    ) -> BackendDesignArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "architecture_design": (
                architecture_design.model_dump()
                if architecture_design is not None
                else None
            ),
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if architecture_design is None:
            input_payload = {
                key: value
                for key, value in input_payload.items()
                if key != "architecture_design"
            }
        if self.model_client is not None:
            return BackendDesignArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        fallback = {
                "tables": [
                    {
                        "table_id": "TABLE-USER",
                        "name": "user_account",
                        "description": "Campus user identity and role data.",
                        "fields": [
                            {"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."},
                            {"name": "role", "type": "VARCHAR(32)", "nullable": False, "description": "STUDENT or ADMIN."},
                            {"name": "display_name", "type": "VARCHAR(128)", "nullable": False, "description": "Visible user name."},
                        ],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-FAVORITE", "REQ-AUDIT"],
                    },
                    {
                        "table_id": "TABLE-PRODUCT",
                        "name": "product",
                        "description": "Second-hand product listing.",
                        "fields": [
                            {"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."},
                            {"name": "seller_id", "type": "BIGINT", "nullable": False, "description": "Seller user id."},
                            {"name": "title", "type": "VARCHAR(128)", "nullable": False, "description": "Product title."},
                            {"name": "price", "type": "DECIMAL(10,2)", "nullable": False, "description": "Expected sale price."},
                            {"name": "image_urls", "type": "JSON", "nullable": False, "description": "Product image storage references."},
                            {"name": "audit_status", "type": "VARCHAR(32)", "nullable": False, "description": "PENDING, APPROVED, or REJECTED."},
                        ],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-AUDIT"],
                    },
                    {
                        "table_id": "TABLE-FAVORITE",
                        "name": "favorite",
                        "description": "Student product favorites.",
                        "fields": [
                            {"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."},
                            {"name": "user_id", "type": "BIGINT", "nullable": False, "description": "Student user id."},
                            {"name": "product_id", "type": "BIGINT", "nullable": False, "description": "Favorited product id."},
                        ],
                        "requirement_refs": ["REQ-FAVORITE"],
                    },
                ],
                "apis": [
                    {
                        "api_id": "API-PRODUCT-CREATE",
                        "method": "POST",
                        "path": "/api/products",
                        "description": "Publish a product and enter pending audit status.",
                        "request_params": [
                            {"name": "title", "type": "string", "required": True, "description": "Product title."},
                            {"name": "price", "type": "number", "required": True, "description": "Expected sale price."},
                            {"name": "imageUrls", "type": "string[]", "required": True, "description": "Uploaded image URLs."},
                        ],
                        "response_fields": [
                            {"name": "productId", "type": "number", "description": "Created product id."},
                            {"name": "auditStatus", "type": "string", "description": "Initial audit status."},
                        ],
                        "auth_required": True,
                        "required_roles": ["STUDENT"],
                        "requirement_refs": ["REQ-PUBLISH"],
                    },
                    {
                        "api_id": "API-PRODUCT-SEARCH",
                        "method": "GET",
                        "path": "/api/products",
                        "description": "Search approved products.",
                        "request_params": [
                            {"name": "keyword", "type": "string", "required": False, "description": "Search keyword."}
                        ],
                        "response_fields": [
                            {"name": "items", "type": "ProductSummary[]", "description": "Matched product summaries."}
                        ],
                        "auth_required": True,
                        "required_roles": ["STUDENT"],
                        "requirement_refs": ["REQ-SEARCH"],
                    },
                    {
                        "api_id": "API-FAVORITE-CREATE",
                        "method": "POST",
                        "path": "/api/favorites",
                        "description": "Favorite a product.",
                        "request_params": [
                            {"name": "productId", "type": "number", "required": True, "description": "Target product id."}
                        ],
                        "response_fields": [
                            {"name": "favoriteId", "type": "number", "description": "Created favorite id."}
                        ],
                        "auth_required": True,
                        "required_roles": ["STUDENT"],
                        "requirement_refs": ["REQ-FAVORITE"],
                    },
                    {
                        "api_id": "API-PRODUCT-AUDIT",
                        "method": "POST",
                        "path": "/api/admin/products/{productId}/audit",
                        "description": "Admin audit endpoint for approving or rejecting listings.",
                        "request_params": [
                            {"name": "productId", "type": "number", "required": True, "description": "Product id."},
                            {"name": "decision", "type": "string", "required": True, "description": "APPROVE or REJECT."},
                        ],
                        "response_fields": [
                            {"name": "auditStatus", "type": "string", "description": "Updated audit status."}
                        ],
                        "auth_required": True,
                        "required_roles": ["ADMIN"],
                        "requirement_refs": ["REQ-AUDIT"],
                    },
                ],
            }
        return BackendDesignArtifact.model_validate(
            remap_requirement_refs(
                fallback,
                fallback_requirement_mapping(
                    feature.requirement_id for feature in prd.core_features
                ),
            )
        )
