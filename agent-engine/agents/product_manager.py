from typing import Any, Mapping

from agents.base import ModelClient
from schemas.prd import PrdArtifact


class ProductManagerAgent:
    prompt_name = "ProductManagerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(self, requirement: str) -> PrdArtifact:
        input_payload: Mapping[str, Any] = {"requirement": requirement}
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
                        "name": "Product publishing",
                        "description": "Students can publish second-hand products with structured listing details.",
                        "priority": "MUST",
                    },
                    {
                        "name": "Product search",
                        "description": "Students can search and browse available campus listings.",
                        "priority": "MUST",
                    },
                    {
                        "name": "Favorite products",
                        "description": "Students can save products they are interested in.",
                        "priority": "SHOULD",
                    },
                    {
                        "name": "Admin audit",
                        "description": "Admins can review listings before they become visible.",
                        "priority": "MUST",
                    },
                ],
                "user_stories": [
                    {
                        "role": "student",
                        "goal": "publish an idle item",
                        "benefit": "find a buyer inside the campus community",
                        "acceptance_criteria": [
                            "The listing stores title, price, category, description, and images.",
                            "The listing enters a pending audit state after submission.",
                        ],
                    },
                    {
                        "role": "admin",
                        "goal": "audit product listings",
                        "benefit": "keep prohibited goods out of the marketplace",
                        "acceptance_criteria": [
                            "Only admins can approve or reject pending listings.",
                            "Rejected listings include a visible reason.",
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
