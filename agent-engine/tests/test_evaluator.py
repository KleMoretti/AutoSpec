from runtime.agent_node_runner import AgentExecutionRecord
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
        retrieved_sources=[{
            "citation_id": "artifact:9:v2:chunk:0",
            "artifact_id": 9,
            "title": "Approved workspace PRD",
            "content": "approved historical project knowledge",
        }],
        generated_files=[{"path": "backend/src/main/resources/application.yml", "content": "password: ${DB_PASSWORD}"}],
    )

    assert report.overall_score >= 90
    assert report.final_grade == "A"
    assert report.gate_status == "PASSED"
    assert report.blocking_issue_count == 0
    assert report.requirement_traceability[0].requirement_id == "REQ-001"
    assert report.requirement_traceability[0].covered is True
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
    assert report.gate_status == "BLOCKED"
    assert report.overall_score <= 69


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


def test_evaluator_rejects_citation_excerpt_not_found_in_source_chunk():
    prd = valid_prd()
    prd.source_citations[0].excerpt = "invented source statement"

    report = evaluate_artifacts(
        requirement="Build a project workspace with historical reuse and RAG.",
        prd=prd,
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        retrieved_sources=[{
            "citation_id": "artifact:9:v2:chunk:0",
            "artifact_id": 9,
            "content": "approved historical project knowledge",
        }],
    )

    assert report.dimension("RAG_CITATION_QUALITY").score == 40
    assert any(issue.issue_type == "RAG_CITATION_UNFAITHFUL" for issue in report.issues)


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


def test_evaluator_blocks_failed_trusted_model_invocation():
    report = evaluate_artifacts(
        requirement="Build a project workspace.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        model_invocations=[{
            "status": "FAILED",
            "provider": "openai",
            "model": "gpt-test",
        }],
    )

    assert report.gate_status == "BLOCKED"
    assert any(issue.issue_type == "MODEL_INVOCATION_FAILURE" for issue in report.issues)


def test_evaluator_does_not_infer_traceability_from_similar_words():
    backend = valid_backend()
    backend.tables[0].requirement_refs.clear()
    for field in backend.tables[0].fields:
        field.requirement_refs.clear()
    backend.apis[0].requirement_refs.clear()

    report = evaluate_artifacts(
        requirement="Build a project artifacts workspace.",
        prd=valid_prd(),
        architecture_design=valid_architecture(),
        backend_design=backend,
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
    )

    trace = next(value for value in report.requirement_traceability if value.requirement_id == "REQ-001")
    assert trace.data_evidence == []
    assert trace.api_evidence == []
    assert report.gate_status == "BLOCKED"


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
    assert report.gate_status == "BLOCKED"
    assert report.overall_score <= 49


def test_evaluator_blocks_must_requirement_without_acceptance_trace():
    prd = valid_prd()
    prd.user_stories[0].acceptance_criteria.clear()

    report = evaluate_artifacts(
        requirement="Build a project workspace.",
        prd=prd,
        architecture_design=valid_architecture(),
        backend_design=valid_backend(),
        frontend_skeleton=valid_frontend(),
        review_report=ReviewReport(score=100, issues=[]),
        records=successful_records(),
        generated_files=[{"path": "README.md", "content": "build instructions"}],
    )

    blocker = next(
        issue for issue in report.issues
        if issue.issue_type == "MUST_REQUIREMENT_TRACE_GAP"
    )
    assert blocker.requirement_id == "REQ-001"
    assert blocker.blocking is True
    assert report.gate_status == "BLOCKED"


def valid_prd() -> PrdArtifact:
    return PrdArtifact.model_validate(
        {
            "project_name": "Project Workspace",
            "target_users": ["owner", "member"],
            "core_features": [
                {
                    "requirement_id": "REQ-001",
                    "name": "Project artifacts",
                    "description": "Users can view project artifacts.",
                    "priority": "MUST",
                },
                {
                    "requirement_id": "REQ-002",
                    "name": "Historical RAG reuse",
                    "description": "Users can reuse approved historical project knowledge.",
                    "priority": "SHOULD",
                },
            ],
            "user_stories": [
                {
                    "story_id": "STORY-001",
                    "role": "owner",
                    "goal": "inspect generated artifacts",
                    "benefit": "review project quality",
                    "requirement_refs": ["REQ-001"],
                    "acceptance_criteria": [{
                        "acceptance_id": "AC-001",
                        "criterion": "Artifact API returns project artifacts.",
                        "requirement_refs": ["REQ-001"],
                    }],
                }
            ],
            "business_boundaries": ["Only project members can read artifacts."],
            "non_functional_requirements": ["Every Agent node records status and duration."],
            "risks": ["Historical source citation may be missing."],
            "source_citations": [
                {
                    "citation_id": "artifact:9:v2:chunk:0",
                    "claim": "Approved historical project knowledge can be reused.",
                    "excerpt": "approved historical project knowledge",
                }
            ],
        }
    )


def valid_architecture() -> ArchitectureDesignArtifact:
    return ArchitectureDesignArtifact.model_validate(
        {
            "system_context": "Backend persists project artifacts and Agent evaluation reports.",
            "modules": [
                {
                    "module_id": "MOD-BACKEND",
                    "name": "backend",
                    "responsibility": "Persist project artifacts and expose project APIs.",
                    "depends_on": ["agent-engine"],
                    "requirement_refs": ["REQ-001"],
                },
                {
                    "module_id": "MOD-AGENT",
                    "name": "agent-engine",
                    "responsibility": "Run Agent workflow and evaluator.",
                    "depends_on": [],
                    "requirement_refs": [],
                },
            ],
            "decisions": [
                {
                    "decision_id": "ADR-PERSIST-EVALUATION",
                    "title": "Persist evaluator reports",
                    "context": "Generated artifacts need quality evidence.",
                    "decision": "Store evaluator output as structured artifacts.",
                    "consequences": ["Runs can be compared later."],
                    "requirement_refs": ["REQ-001"],
                }
            ],
            "non_functional_constraints": [
                {
                    "constraint_id": "NFR-OBSERVABILITY",
                    "category": "observability",
                    "requirement": "Record every Agent node status.",
                    "requirement_refs": [],
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
                    "table_id": "TABLE-ARTIFACT",
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
                    "requirement_refs": ["REQ-001"],
                }
            ],
            "apis": [
                {
                    "api_id": "API-ARTIFACT-LIST",
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
                    "requirement_refs": ["REQ-001"],
                }
            ],
        }
    )


def valid_frontend() -> FrontendSkeletonArtifact:
    return FrontendSkeletonArtifact.model_validate(
        {
            "routes": [{
                "route_id": "ROUTE-PROJECT-DETAIL",
                "path": "/projects/:projectId",
                "page": "ProjectDetailPage",
                "requirement_refs": ["REQ-001"],
            }],
            "pages": [
                {
                    "page_id": "PAGE-PROJECT-DETAIL",
                    "name": "ProjectDetailPage",
                    "purpose": "Inspect generated project artifacts.",
                    "components": ["ArtifactTabs"],
                    "requirement_refs": ["REQ-001"],
                }
            ],
            "components": [
                {
                    "component_id": "COMP-ARTIFACT-TABS",
                    "name": "ArtifactTabs",
                    "type": "tabs",
                    "props": ["artifacts"],
                    "state": [],
                    "requirement_refs": ["REQ-001"],
                }
            ],
            "api_bindings": [
                {
                    "binding_id": "BIND-ARTIFACT-LIST",
                    "method": "GET",
                    "path": "/api/projects/{projectId}/artifacts",
                    "consumer": "ArtifactTabs",
                    "backend_api_id": "API-ARTIFACT-LIST",
                    "requirement_refs": ["REQ-001"],
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
