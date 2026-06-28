from typing import Any, Mapping

from agents.base import ModelClient
from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact


class BackendEngineerAgent:
    prompt_name = "BackendEngineerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(self, requirement: str, prd: PrdArtifact) -> BackendDesignArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
        }
        if self.model_client is not None:
            return BackendDesignArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        return BackendDesignArtifact.model_validate(
            {
                "tables": [
                    {
                        "name": "user_account",
                        "description": "Campus user identity and role data.",
                        "fields": [
                            {"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."},
                            {"name": "role", "type": "VARCHAR(32)", "nullable": False, "description": "STUDENT or ADMIN."},
                            {"name": "display_name", "type": "VARCHAR(128)", "nullable": False, "description": "Visible user name."},
                        ],
                    },
                    {
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
                    },
                    {
                        "name": "favorite",
                        "description": "Student product favorites.",
                        "fields": [
                            {"name": "id", "type": "BIGINT", "nullable": False, "description": "Primary key."},
                            {"name": "user_id", "type": "BIGINT", "nullable": False, "description": "Student user id."},
                            {"name": "product_id", "type": "BIGINT", "nullable": False, "description": "Favorited product id."},
                        ],
                    },
                ],
                "apis": [
                    {
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
                    },
                    {
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
                    },
                    {
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
                    },
                    {
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
                    },
                ],
            }
        )
