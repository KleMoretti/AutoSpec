from agents.reviewer import ReviewerAgent
from review.rules import run_rule_checks, run_v2_rule_checks, run_v3_rule_checks, score_from_issues
from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.review import ReviewReport


def _prd_with_features(*features: str) -> PrdArtifact:
    return PrdArtifact.model_validate(
        {
            "project_name": "Campus Marketplace",
            "target_users": ["student", "admin"],
            "core_features": [
                {
                    "name": feature,
                    "description": f"{feature} for campus second-hand trading.",
                    "priority": "MUST",
                }
                for feature in features
            ],
            "user_stories": [
                {
                    "role": "student",
                    "goal": "trade idle goods",
                    "benefit": "complete campus transactions",
                    "acceptance_criteria": ["The feature is available through V1 APIs."],
                }
            ],
            "business_boundaries": ["No payment escrow in V1."],
            "non_functional_requirements": ["Structured artifacts are required."],
            "risks": ["Bad listings need moderation."],
        }
    )


def _backend_design(apis: list[dict], product_fields: list[dict] | None = None) -> BackendDesignArtifact:
    return BackendDesignArtifact.model_validate(
        {
            "tables": [
                {
                    "name": "product",
                    "description": "Second-hand product listing.",
                    "fields": product_fields
                    or [
                        {
                            "name": "id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Primary key.",
                        },
                        {
                            "name": "image_urls",
                            "type": "JSON",
                            "nullable": False,
                            "description": "Product image storage references.",
                        },
                        {
                            "name": "audit_status",
                            "type": "VARCHAR(32)",
                            "nullable": False,
                            "description": "Listing audit status.",
                        },
                    ],
                },
                {
                    "name": "favorite",
                    "description": "Product favorites.",
                    "fields": [
                        {
                            "name": "id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Primary key.",
                        },
                        {
                            "name": "product_id",
                            "type": "BIGINT",
                            "nullable": False,
                            "description": "Favorited product id.",
                        },
                    ],
                },
            ],
            "apis": apis,
        }
    )


def _api(
    method: str,
    path: str,
    description: str,
    required_roles: list[str] | None = None,
    auth_required: bool = True,
    request_params: list[dict] | None = None,
) -> dict:
    return {
        "method": method,
        "path": path,
        "description": description,
        "request_params": request_params or [],
        "response_fields": [
            {
                "name": "productId",
                "type": "number",
                "description": "Product id.",
            }
        ],
        "auth_required": auth_required,
        "required_roles": required_roles or ["STUDENT"],
    }


def _architecture_with_modules(*modules: str) -> ArchitectureDesignArtifact:
    return ArchitectureDesignArtifact.model_validate(
        {
            "system_context": "AutoSpec stores Agent event history for real-time progress.",
            "modules": [
                {
                    "name": module,
                    "responsibility": f"{module} responsibility includes event-aware V2 workflow support.",
                    "depends_on": [],
                }
                for module in modules
            ],
            "decisions": [
                {
                    "title": "Persist event history",
                    "context": "Real-time streams can disconnect.",
                    "decision": "Store Agent events in the backend.",
                    "consequences": ["Clients can reload progress."],
                }
            ],
            "non_functional_constraints": [],
            "integration_risks": [],
        }
    )


def _architecture_without_text(text: str) -> ArchitectureDesignArtifact:
    return ArchitectureDesignArtifact.model_validate(
        {
            "system_context": "AutoSpec coordinates services.",
            "modules": [
                {
                    "name": "backend",
                    "responsibility": "Persist projects and artifacts.",
                    "depends_on": [],
                }
            ],
            "decisions": [],
            "non_functional_constraints": [],
            "integration_risks": [],
        }
    )


def _backend_with_api(method: str, path: str, auth_required: bool = True) -> BackendDesignArtifact:
    return _backend_design([_api(method, path, f"{method} {path}", auth_required=auth_required)])


def _frontend_without_api_binding(path: str) -> FrontendSkeletonArtifact:
    return FrontendSkeletonArtifact.model_validate(
        {
            "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
            "pages": [
                {
                    "name": "ProjectDetailPage",
                    "purpose": "Review PRD and monitor generation.",
                    "components": ["PrdEditor"],
                }
            ],
            "components": [
                {
                    "name": "PrdEditor",
                    "type": "form",
                    "props": ["artifact"],
                    "state": ["draftContent"],
                }
            ],
            "api_bindings": [
                {
                    "method": "GET",
                    "path": path.replace("/approve", ""),
                    "consumer": "ArtifactTabs",
                }
            ],
        }
    )


def _frontend_with_event_stream() -> FrontendSkeletonArtifact:
    return FrontendSkeletonArtifact.model_validate(
        {
            "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
            "pages": [
                {
                    "name": "ProjectDetailPage",
                    "purpose": "Monitor real-time Agent progress.",
                    "components": ["AgentTimeline"],
                }
            ],
            "components": [
                {
                    "name": "AgentTimeline",
                    "type": "timeline",
                    "props": ["events"],
                    "state": [],
                }
            ],
            "api_bindings": [
                {
                    "method": "GET",
                    "path": "/api/projects/{projectId}/events",
                    "consumer": "AgentTimeline",
                }
            ],
        }
    )


def test_rule_engine_accepts_consistent_marketplace_design():
    prd = _prd_with_features("商品发布", "商品搜索", "收藏商品", "管理员审核")
    backend_design = _backend_design(
        [
            _api("POST", "/api/products", "商品发布"),
            _api("GET", "/api/products", "商品搜索"),
            _api(
                "POST",
                "/api/favorites",
                "收藏商品",
                request_params=[
                    {
                        "name": "productId",
                        "type": "number",
                        "required": True,
                        "description": "Product id.",
                    }
                ],
            ),
            _api("POST", "/api/admin/products/{productId}/audit", "管理员审核商品", ["ADMIN"]),
        ]
    )

    issues = run_rule_checks(prd, backend_design)

    assert issues == []
    assert score_from_issues(issues) == 100


def test_rule_engine_detects_missing_favorite_api():
    prd = _prd_with_features("收藏商品")
    backend_design = _backend_design([_api("POST", "/api/products", "商品发布")])

    issues = run_rule_checks(prd, backend_design)

    assert [issue.issue_type for issue in issues] == ["API_COVERAGE"]
    assert "favorite" in issues[0].suggestion.lower()


def test_rule_engine_detects_missing_product_image_storage():
    prd = _prd_with_features("商品发布")
    backend_design = _backend_design(
        [_api("POST", "/api/products", "商品发布，包含商品图片")],
        product_fields=[
            {
                "name": "id",
                "type": "BIGINT",
                "nullable": False,
                "description": "Primary key.",
            }
        ],
    )

    issues = run_rule_checks(prd, backend_design)

    assert [issue.issue_type for issue in issues] == ["DATA_COVERAGE"]
    assert "image" in issues[0].description.lower()


def test_rule_engine_detects_admin_api_without_admin_role():
    prd = _prd_with_features("管理员审核")
    backend_design = _backend_design(
        [_api("POST", "/api/admin/products/{productId}/audit", "Admin audit product", ["STUDENT"])]
    )

    issues = run_rule_checks(prd, backend_design)

    assert [issue.issue_type for issue in issues] == ["PERMISSION_COVERAGE"]
    assert "ADMIN" in issues[0].suggestion


def test_v2_reviewer_detects_frontend_missing_prd_edit_api_binding():
    prd = _prd_with_features("PRD editing")
    architecture = _architecture_with_modules("backend", "frontend")
    backend_design = _backend_with_api(
        "POST", "/api/projects/{projectId}/artifacts/{artifactId}/approve"
    )
    frontend = _frontend_without_api_binding(
        "/api/projects/{projectId}/artifacts/{artifactId}/approve"
    )

    issues = run_v2_rule_checks(prd, architecture, backend_design, frontend)

    assert [issue.issue_type for issue in issues] == ["FRONTEND_API_COVERAGE"]


def test_v2_reviewer_detects_architecture_missing_agent_event_storage():
    prd = _prd_with_features("real-time progress")
    architecture = _architecture_without_text("event")
    backend_design = _backend_with_api("GET", "/api/projects/{projectId}/events")
    frontend = _frontend_with_event_stream()

    issues = run_v2_rule_checks(prd, architecture, backend_design, frontend)

    assert "event" in issues[0].description.lower()


def test_v3_reviewer_detects_missing_permission_boundary():
    prd = _prd_with_features("user login", "private projects")
    backend_design = _backend_with_api(
        "GET",
        "/api/projects/{projectId}/artifacts",
        auth_required=False,
    )

    issues = run_v3_rule_checks(
        prd=prd,
        backend_design=backend_design,
        retrieved_sources=[],
        generated_files=[],
    )

    assert issues[0].issue_type == "PERMISSION_BOUNDARY"
    assert "authentication" in issues[0].suggestion.lower()


def test_v3_reviewer_requires_rag_source_citations():
    prd = _prd_with_features("historical project reuse")

    issues = run_v3_rule_checks(
        prd=prd,
        backend_design=_backend_with_api("GET", "/api/projects"),
        retrieved_sources=[],
        generated_files=[],
    )

    assert issues[0].issue_type == "RAG_SOURCE_CITATION"
    assert "source" in issues[0].description.lower()


def test_v3_reviewer_detects_secret_like_generated_code():
    prd = _prd_with_features("code skeleton export")

    issues = run_v3_rule_checks(
        prd=prd,
        backend_design=_backend_with_api("GET", "/api/projects"),
        retrieved_sources=[],
        generated_files=[
            {
                "path": "backend/src/main/resources/application.yml",
                "content": "spring.datasource.password=real-password",
            }
        ],
    )

    assert [issue.issue_type for issue in issues] == ["CODE_EXPORT_SECRET"]


def test_reviewer_agent_appends_v3_rules_when_v3_context_is_present():
    prd = _prd_with_features("historical project reuse")

    report = ReviewerAgent().run(
        prd,
        _backend_with_api("GET", "/api/projects"),
        retrieved_sources=[],
        generated_files=[],
    )

    assert [issue.issue_type for issue in report.issues] == ["RAG_SOURCE_CITATION"]


def test_reviewer_routes_backend_issues_to_structured_rework_target():
    report = ReviewerAgent().run(
        _prd_with_features("favorite products"),
        _backend_with_api("GET", "/api/projects"),
    )

    assert report.decision == "REWORK"
    assert [route.target_node for route in report.routes] == ["backend_engineer"]
    assert report.routes[0].issue_ids == ["R-1"]
    assert report.routes[0].required_changes == [report.issues[0].suggestion]
    assert report.routes[0].invalidate_downstream is True


def test_reviewer_pass_has_no_rework_routes():
    report = ReviewerAgent().run(
        _prd_with_features("Product publishing"),
        _backend_with_api("POST", "/api/products"),
    )

    assert report.decision == "PASS"
    assert report.routes == []


def test_review_report_rejects_rework_route_outside_v5_allowlist():
    payload = {
        "score": 60,
        "issues": [],
        "decision": "REWORK",
        "routes": [
            {
                "target_node": "reviewer",
                "issue_ids": ["R-1"],
                "required_changes": ["Review itself again."],
                "invalidate_downstream": True,
            }
        ],
    }

    import pytest

    with pytest.raises(ValueError, match="target_node"):
        ReviewReport.model_validate(payload)
