import pytest
from pydantic import ValidationError

from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewReport
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact


def test_campus_marketplace_prd_schema_accepts_structured_artifact():
    artifact = PrdArtifact.model_validate(
        {
            "project_name": "Campus Second-Hand Marketplace",
            "target_users": ["student", "admin"],
            "core_features": [
                {
                    "name": "Product publishing",
                    "description": "Students publish second-hand products with images.",
                    "priority": "MUST",
                }
            ],
            "user_stories": [
                {
                    "role": "student",
                    "goal": "publish an idle textbook",
                    "benefit": "find a buyer on campus",
                    "acceptance_criteria": ["Product is visible after approval."],
                }
            ],
            "business_boundaries": ["No off-campus delivery workflow in V1."],
            "non_functional_requirements": ["Every agent output is stored as JSON."],
            "risks": ["Listings may contain prohibited goods."],
        }
    )

    assert artifact.project_name == "Campus Second-Hand Marketplace"
    assert artifact.core_features[0].priority == "MUST"


def test_backend_design_schema_accepts_tables_and_rest_apis():
    artifact = BackendDesignArtifact.model_validate(
        {
            "tables": [
                {
                    "name": "product",
                    "description": "Second-hand product listing.",
                    "fields": [
                        {
                            "name": "id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Primary key.",
                        }
                    ],
                }
            ],
            "apis": [
                {
                    "method": "POST",
                    "path": "/api/products",
                    "description": "Publish a product.",
                    "request_params": [
                        {
                            "name": "title",
                            "type": "string",
                            "required": True,
                            "description": "Product title.",
                        }
                    ],
                    "response_fields": [
                        {
                            "name": "productId",
                            "type": "number",
                            "description": "Created product id.",
                        }
                    ],
                    "auth_required": True,
                    "required_roles": ["STUDENT"],
                }
            ],
        }
    )

    assert artifact.tables[0].fields[0].nullable is False
    assert artifact.apis[0].required_roles == ["STUDENT"]


def test_review_report_rejects_out_of_range_score():
    with pytest.raises(ValidationError):
        ReviewReport.model_validate({"score": 101, "issues": []})


def test_architecture_design_schema_accepts_modules_and_decisions():
    artifact = ArchitectureDesignArtifact.model_validate(
        {
            "system_context": "AutoSpec coordinates backend, frontend, and agent-engine services.",
            "modules": [
                {
                    "name": "backend",
                    "responsibility": "Persist projects, artifacts, events, and exports.",
                    "depends_on": ["agent-engine", "mysql", "redis"],
                }
            ],
            "decisions": [
                {
                    "title": "Backend owns artifact state",
                    "context": "Human approval and retry require durable state.",
                    "decision": "Spring Boot stores artifact versions and approval state.",
                    "consequences": ["Agent engine remains stateless between calls."],
                }
            ],
            "non_functional_constraints": [
                {
                    "category": "observability",
                    "requirement": "Every Agent node emits persisted events.",
                }
            ],
            "integration_risks": ["SSE connections can drop and recover from persisted events."],
        }
    )

    assert artifact.modules[0].name == "backend"


def test_frontend_skeleton_schema_accepts_routes_pages_components_and_api_bindings():
    artifact = FrontendSkeletonArtifact.model_validate(
        {
            "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
            "pages": [
                {
                    "name": "ProjectDetailPage",
                    "purpose": "Review PRD, monitor generation, and inspect artifacts.",
                    "components": ["PrdEditor", "AgentTimeline", "ArtifactTabs"],
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
                    "props": ["events"],
                    "state": [],
                },
                {
                    "name": "ArtifactTabs",
                    "type": "tabs",
                    "props": ["artifacts"],
                    "state": [],
                },
            ],
            "api_bindings": [
                {
                    "method": "POST",
                    "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                    "consumer": "PrdEditor",
                }
            ],
        }
    )

    assert artifact.api_bindings[0].consumer == "PrdEditor"


def test_requirement_ids_are_stable_across_array_reordering():
    first = PrdArtifact.model_validate(
        {
            "project_name": "Stable trace",
            "target_users": ["owner"],
            "core_features": [
                {"name": "Publish", "description": "Publish a listing", "priority": "MUST"},
                {"name": "Search", "description": "Search listings", "priority": "SHOULD"},
            ],
            "user_stories": [{
                "role": "owner",
                "goal": "publish a listing",
                "benefit": "make it visible",
                "acceptance_criteria": ["The listing is visible"],
            }],
        }
    )
    second = PrdArtifact.model_validate(
        {
            **first.model_dump(exclude={"core_features", "user_stories"}),
            "core_features": [
                {"name": "Search", "description": "Search listings", "priority": "SHOULD"},
                {"name": "Publish", "description": "Publish a listing", "priority": "MUST"},
            ],
            "user_stories": [{
                "role": "owner",
                "goal": "publish a listing",
                "benefit": "make it visible",
                "acceptance_criteria": ["The listing is visible"],
            }],
        }
    )

    first_ids = {feature.name: feature.requirement_id for feature in first.core_features}
    second_ids = {feature.name: feature.requirement_id for feature in second.core_features}
    assert first_ids == second_ids
    assert first.user_stories[0].story_id == second.user_stories[0].story_id
    assert (
        first.user_stories[0].acceptance_criteria[0].acceptance_id
        == second.user_stories[0].acceptance_criteria[0].acceptance_id
    )


def test_prd_rejects_unknown_explicit_requirement_reference():
    with pytest.raises(ValidationError, match="unknown PRD requirement refs"):
        PrdArtifact.model_validate(
            {
                "project_name": "Broken trace",
                "target_users": ["owner"],
                "core_features": [{
                    "requirement_id": "REQ-KNOWN",
                    "name": "Known",
                    "description": "Known requirement",
                    "priority": "MUST",
                }],
                "user_stories": [{
                    "story_id": "STORY-BROKEN",
                    "role": "owner",
                    "goal": "use an unknown requirement",
                    "benefit": "test validation",
                    "requirement_refs": ["REQ-UNKNOWN"],
                    "acceptance_criteria": [],
                }],
            }
        )
