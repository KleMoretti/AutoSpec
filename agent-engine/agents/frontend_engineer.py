from typing import Any, Mapping

from agents.base import ModelClient
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact


class FrontendEngineerAgent:
    prompt_name = "FrontendEngineerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        prd: PrdArtifact,
        architecture_design: ArchitectureDesignArtifact,
        backend_design: BackendDesignArtifact,
        retrieved_sources: list[dict[str, Any]] | None = None,
    ) -> FrontendSkeletonArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "architecture_design": architecture_design.model_dump(),
            "backend_design": backend_design.model_dump(),
            "retrieved_sources": retrieved_sources or [],
        }
        if self.model_client is not None:
            return FrontendSkeletonArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )

        return FrontendSkeletonArtifact.model_validate(
            {
                "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
                "pages": [
                    {
                        "name": "ProjectDetailPage",
                        "purpose": "Approve PRD, monitor Agent execution, inspect artifacts, and retry failed nodes.",
                        "components": [
                            "PrdEditor",
                            "AgentTimeline",
                            "ExecutionEventList",
                            "ArtifactTabs",
                            "ReviewIssueTable",
                        ],
                    }
                ],
                "components": [
                    {
                        "name": "PrdEditor",
                        "type": "form",
                        "props": ["artifact", "onSave", "onApprove"],
                        "state": ["draftContent", "saving"],
                    },
                    {
                        "name": "AgentTimeline",
                        "type": "timeline",
                        "props": ["progress", "events", "onRetry"],
                        "state": [],
                    },
                    {
                        "name": "ArtifactTabs",
                        "type": "tabs",
                        "props": ["artifacts", "activeType", "onChange"],
                        "state": ["activeType"],
                    },
                ],
                "api_bindings": [
                    {
                        "method": "POST",
                        "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                        "consumer": "PrdEditor",
                    },
                    {
                        "method": "GET",
                        "path": "/api/projects/{projectId}/events",
                        "consumer": "ExecutionEventList",
                    },
                    {
                        "method": "POST",
                        "path": "/api/projects/{projectId}/tasks/{taskId}/retry",
                        "consumer": "AgentTimeline",
                    },
                ],
            }
        )
