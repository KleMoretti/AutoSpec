from typing import Any, Mapping

from agents.base import ModelClient
from schemas.prd import PrdArtifact


class ProductManagerAgent:
    prompt_name = "ProductManagerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
    ) -> PrdArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if self.model_client is not None:
            return PrdArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        return PrdArtifact.model_validate(
            {
                "project_name": _infer_project_name(requirement),
                "target_users": ["student", "admin"],
                "core_features": [
                    {
                        "requirement_id": "REQ-PUBLISH",
                        "name": "Product publishing",
                        "description": "Students can publish second-hand products with structured listing details.",
                        "priority": "MUST",
                    },
                    {
                        "requirement_id": "REQ-SEARCH",
                        "name": "Product search",
                        "description": "Students can search and browse available campus listings.",
                        "priority": "MUST",
                    },
                    {
                        "requirement_id": "REQ-FAVORITE",
                        "name": "Favorite products",
                        "description": "Students can save products they are interested in.",
                        "priority": "SHOULD",
                    },
                    {
                        "requirement_id": "REQ-AUDIT",
                        "name": "Admin audit",
                        "description": "Admins can review listings before they become visible.",
                        "priority": "MUST",
                    },
                ],
                "user_stories": [
                    {
                        "story_id": "STORY-PUBLISH",
                        "role": "student",
                        "goal": "publish an idle item",
                        "benefit": "find a buyer inside the campus community",
                        "requirement_refs": ["REQ-PUBLISH"],
                        "acceptance_criteria": [
                            {
                                "acceptance_id": "AC-PUBLISH-DETAILS",
                                "criterion": "The listing stores title, price, category, description, and images.",
                                "requirement_refs": ["REQ-PUBLISH"],
                            },
                            {
                                "acceptance_id": "AC-PUBLISH-PENDING",
                                "criterion": "The listing enters a pending audit state after submission.",
                                "requirement_refs": ["REQ-PUBLISH"],
                            },
                        ],
                    },
                    {
                        "story_id": "STORY-AUDIT",
                        "role": "admin",
                        "goal": "audit product listings",
                        "benefit": "keep prohibited goods out of the marketplace",
                        "requirement_refs": ["REQ-AUDIT"],
                        "acceptance_criteria": [
                            {
                                "acceptance_id": "AC-AUDIT-ROLE",
                                "criterion": "Only admins can approve or reject pending listings.",
                                "requirement_refs": ["REQ-AUDIT"],
                            },
                            {
                                "acceptance_id": "AC-AUDIT-REASON",
                                "criterion": "Rejected listings include a visible reason.",
                                "requirement_refs": ["REQ-AUDIT"],
                            },
                        ],
                    },
                ],
                "business_boundaries": [
                    "V1 does not support payment escrow.",
                    "V1 does not support off-campus logistics.",
                ],
                "non_functional_requirements": [
                    "Every agent input, output, status, duration, and error is observable.",
                    "Agent artifacts must be valid JSON matching the published schema.",
                ],
                "risks": [
                    "Listings may include prohibited or unsafe goods.",
                    "Generated API and database designs may drift without reviewer checks.",
                ],
            }
        )


def _infer_project_name(requirement: str) -> str:
    lowered = requirement.lower()
    if "marketplace" in lowered or "second-hand" in lowered:
        return "Campus Second-Hand Marketplace"
    return "AutoSpec Generated Project"
