from typing import Any, Mapping

from agents.base import ModelClient
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.prd import PrdArtifact
from schemas.traceability import fallback_requirement_mapping, remap_requirement_refs


class ArchitectAgent:
    prompt_name = "ArchitectAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        prd: PrdArtifact,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
    ) -> ArchitectureDesignArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if self.model_client is not None:
            return ArchitectureDesignArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        fallback = {
                "system_context": "AutoSpec coordinates frontend, backend, and agent-engine services with persisted Agent events.",
                "modules": [
                    {
                        "module_id": "MOD-BACKEND",
                        "name": "backend",
                        "responsibility": "Persist projects, artifacts, Agent tasks, events, approvals, retries, and exports.",
                        "depends_on": ["agent-engine", "mysql", "redis"],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE", "REQ-AUDIT"],
                    },
                    {
                        "module_id": "MOD-FRONTEND",
                        "name": "frontend",
                        "responsibility": "Render PRD editing, artifact previews, retry controls, and real-time Agent event progress.",
                        "depends_on": ["backend"],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE", "REQ-AUDIT"],
                    },
                    {
                        "module_id": "MOD-AGENT-ENGINE",
                        "name": "agent-engine",
                        "responsibility": "Run typed Agent nodes and return structured artifacts.",
                        "depends_on": [],
                        "requirement_refs": [],
                    },
                ],
                "decisions": [
                    {
                        "decision_id": "ADR-BACKEND-STATE",
                        "title": "Backend owns workflow state",
                        "context": "PRD approval, real-time progress, and retry require durable state.",
                        "decision": "Store artifact versions, Agent tasks, and event history in Spring Boot.",
                        "consequences": ["Agent engine can remain stateless between requests."],
                        "requirement_refs": ["REQ-PUBLISH", "REQ-AUDIT"],
                    }
                ],
                "non_functional_constraints": [
                    {
                        "constraint_id": "NFR-OBSERVABILITY",
                        "category": "observability",
                        "requirement": "Every Agent node records input, output, duration, status, errors, and event history.",
                        "requirement_refs": [],
                    }
                ],
                "integration_risks": [
                    "Dropped SSE connections must recover from persisted event history.",
                    "Retry must use the stored node input to avoid artifact drift.",
                ],
            }
        return ArchitectureDesignArtifact.model_validate(
            remap_requirement_refs(
                fallback,
                fallback_requirement_mapping(
                    feature.requirement_id for feature in prd.core_features
                ),
            )
        )
