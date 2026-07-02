from graph.workflow import AgentExecutionRecord
from review.evaluator import evaluate_artifacts
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewReport


def test_evaluator_scores_complete_run_high():
    report = evaluate_artifacts(
        requirement="Build a project workspace with historical RAG reuse.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        retrieved_sources=[{"artifact_id": 9, "title": "Approved workspace PRD"}],
        generated_files=[{"path": "backend/src/main/resources/application.yml", "content": "password: ${DB_PASSWORD}"}],
    )

    assert report.overall_score >= 90
    assert report.final_grade == "A"
    assert {score.dimension for score in report.dimension_scores} == {
        "SCHEMA_VALIDITY",
        "REQUIREMENT_COVERAGE",
        "CROSS_ARTIFACT_CONSISTENCY",
        "PERMISSION_COVERAGE",
        "RAG_CITATION_QUALITY",
        "RUNTIME_RELIABILITY",
        "EXPORT_READINESS",
    }
    assert report.issues == []


def test_evaluator_flags_missing_frontend_api_binding():
    frontend = valid_frontend()
    frontend.api_bindings.clear()

    report = evaluate_artifacts(
        requirement="Build a project workspace.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=frontend,
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
    )

    assert any(issue.issue_type == "FRONTEND_COVERAGE" for issue in report.issues)
    consistency = report.dimension("CROSS_ARTIFACT_CONSISTENCY")
    assert consistency.score < 100


def test_evaluator_flags_missing_rag_source_when_history_reuse_requested():
    report = evaluate_artifacts(
        requirement="Build a project workspace with historical reuse and RAG.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        retrieved_sources=[],
    )

    assert any(issue.issue_type == "RAG_CITATION" for issue in report.issues)
    assert report.dimension("RAG_CITATION_QUALITY").score == 60


def test_evaluator_lowers_runtime_score_for_failed_records():
    records = successful_records()
    records.append(
        AgentExecutionRecord(
            node_name="backend_engineer",
            agent_name="BackendEngineerAgent_v1",
            input_payload={},
            output_payload=None,
            status="FAILED",
            duration_ms=50,
            error_message="schema validation failed",
        )
    )

    report = evaluate_artifacts(
        requirement="Build a project workspace.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=records,
    )

    assert report.dimension("RUNTIME_RELIABILITY").score < 100
    assert any(issue.issue_type == "RUNTIME_FAILURE" for issue in report.issues)


def test_evaluator_flags_concrete_secret_in_generated_files():
    report = evaluate_artifacts(
        requirement="Build a project workspace.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        generated_files=[
            {
                "path": "backend/src/main/resources/application.yml",
                "content": "openai.api_key=sk-live-real-value",
            }
        ],
    )

    assert report.dimension("EXPORT_READINESS").score == 60
    assert any(issue.issue_type == "EXPORT_READINESS" for issue in report.issues)


def valid_prd() -> PrdArtifact:
    return PrdArtifact.model_validate(
        {
            "project_name": "Project Workspace",
            "target_users": ["owner", "member"],
            "core_features": [
                {
                    "name": "Project artifacts",
                    "description": "Users can view project artifacts.",
                    "priority": "MUST",
                },
                {
                    "name": "Historical RAG reuse",
                    "description": "Users can reuse approved historical project knowledge.",
                    "priority": "SHOULD",
                },
            ],
            "user_stories": [
                {
                    "role": "owner",
                    "goal": "inspect generated artifacts",
                    "benefit": "review project quality",
                    "acceptance_criteria": ["Artifact API returns project artifacts."],
                }
            ],
            "business_boundaries": ["Only project members can read artifacts."],
            "non_functional_requirements": ["Every Agent node records status and duration."],
            "risks": ["Historical source citation may be missing."],
        }
    )


def valid_architecture() -> ArchitectureDesignArtifact:
    return ArchitectureDesignArtifact.model_validate(
        {
            "system_context": "Backend persists project artifacts and Agent evaluation reports.",
            "modules": [
                {
                    "name": "backend",
                    "responsibility": "Persist project artifacts and expose project APIs.",
                    "depends_on": ["agent-engine"],
                },
                {
                    "name": "agent-engine",
                    "responsibility": "Run Agent workflow and evaluator.",
                    "depends_on": [],
                },
            ],
            "decisions": [
                {
                    "title": "Persist evaluator reports",
                    "context": "Generated artifacts need quality evidence.",
                    "decision": "Store evaluator output as structured artifacts.",
                    "consequences": ["Runs can be compared later."],
                }
            ],
            "non_functional_constraints": [
                {
                    "category": "observability",
                    "requirement": "Record every Agent node status.",
                }
            ],
            "integration_risks": ["Agent Engine responses can fail schema validation."],
        }
    )


def valid_backend() -> BackendDesignArtifact:
    return BackendDesignArtifact.model_validate(
        {
            "tables": [
                {
                    "name": "artifact",
                    "description": "Stores project artifacts.",
                    "fields": [
                        {
                            "name": "id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Primary key.",
                        },
                        {
                            "name": "project_id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Owning project id.",
                        },
                    ],
                }
            ],
            "apis": [
                {
                    "method": "GET",
                    "path": "/api/projects/{projectId}/artifacts",
                    "description": "List project artifacts.",
                    "request_params": [],
                    "response_fields": [
                        {
                            "name": "artifacts",
                            "type": "Artifact[]",
                            "description": "Project artifacts.",
                        }
                    ],
                    "auth_required": True,
                    "required_roles": ["OWNER", "MEMBER"],
                }
            ],
        }
    )


def valid_frontend() -> FrontendSkeletonArtifact:
    return FrontendSkeletonArtifact.model_validate(
        {
            "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
            "pages": [
                {
                    "name": "ProjectDetailPage",
                    "purpose": "Inspect generated project artifacts.",
                    "components": ["ArtifactTabs"],
                }
            ],
            "components": [
                {
                    "name": "ArtifactTabs",
                    "type": "tabs",
                    "props": ["artifacts"],
                    "state": [],
                }
            ],
            "api_bindings": [
                {
                    "method": "GET",
                    "path": "/api/projects/{projectId}/artifacts",
                    "consumer": "ArtifactTabs",
                }
            ],
        }
    )


def successful_records() -> list[AgentExecutionRecord]:
    return [
        AgentExecutionRecord(
            node_name="product_manager",
            agent_name="ProductManagerAgent_v1",
            input_payload={},
            output_payload={},
            status="SUCCEEDED",
            duration_ms=10,
        ),
        AgentExecutionRecord(
            node_name="reviewer",
            agent_name="ReviewerAgent_v1",
            input_payload={},
            output_payload={},
            status="SUCCEEDED",
            duration_ms=10,
        ),
    ]
