from typing import Any, Mapping

from agents.base import ModelClient
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.prd import PrdArtifact


class ArchitectAgent:
    prompt_name = "ArchitectAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(self, requirement: str, prd: PrdArtifact) -> ArchitectureDesignArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
        }
        if self.model_client is not None:
            return ArchitectureDesignArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        return ArchitectureDesignArtifact.model_validate(
            {
                "system_context": "AutoSpec coordinates frontend, backend, and agent-engine services with persisted Agent events.",
                "modules": [
                    {
                        "name": "backend",
                        "responsibility": "Persist projects, artifacts, Agent tasks, events, approvals, retries, and exports.",
                        "depends_on": ["agent-engine", "mysql", "redis"],
                    },
                    {
                        "name": "frontend",
                        "responsibility": "Render PRD editing, artifact previews, retry controls, and real-time Agent event progress.",
                        "depends_on": ["backend"],
                    },
                    {
                        "name": "agent-engine",
                        "responsibility": "Run typed Agent nodes and return structured artifacts.",
                        "depends_on": [],
                    },
                ],
                "decisions": [
                    {
                        "title": "Backend owns workflow state",
                        "context": "PRD approval, real-time progress, and retry require durable state.",
                        "decision": "Store artifact versions, Agent tasks, and event history in Spring Boot.",
                        "consequences": ["Agent engine can remain stateless between requests."],
                    }
                ],
                "non_functional_constraints": [
                    {
                        "category": "observability",
                        "requirement": "Every Agent node records input, output, duration, status, errors, and event history.",
                    }
                ],
                "integration_risks": [
                    "Dropped SSE connections must recover from persisted event history.",
                    "Retry must use the stored node input to avoid artifact drift.",
                ],
            }
        )
