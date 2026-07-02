from typing import Any

from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.review import ReviewIssue


FEATURE_API_RULES = [
    {
        "feature_terms": ["收藏商品", "favorite"],
        "expected_terms": ["favorite", "/api/favorites", "收藏"],
        "suggestion": "Add a favorite API such as POST /api/favorites.",
    },
    {
        "feature_terms": ["商品发布", "product publishing", "publish"],
        "expected_terms": ["post /api/products", "发布", "publish"],
        "suggestion": "Add a product publishing API such as POST /api/products.",
    },
    {
        "feature_terms": ["商品搜索", "product search", "search"],
        "expected_terms": ["get /api/products", "搜索", "search"],
        "suggestion": "Add a product search API such as GET /api/products.",
    },
    {
        "feature_terms": ["订单交易", "order"],
        "expected_terms": ["order", "/api/orders", "交易", "订单"],
        "suggestion": "Add order transaction APIs such as POST /api/orders.",
    },
    {
        "feature_terms": ["管理员审核", "admin audit"],
        "expected_terms": ["admin", "audit", "审核"],
        "suggestion": "Add an admin audit API with ADMIN permission coverage.",
    },
]

API_DATA_RULES = [
    ("productid", ["product", "product_id", "id"]),
    ("orderid", ["order", "order_id"]),
    ("favorite", ["favorite", "favorite_id"]),
    ("message", ["message", "message_id"]),
    ("audit", ["audit", "audit_status"]),
    ("审核", ["audit", "audit_status"]),
]


def run_rule_checks(prd: PrdArtifact, backend_design: BackendDesignArtifact) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    feature_text = _feature_text(prd)
    api_text = _api_text(backend_design)
    table_text = _table_text(backend_design)

    issues.extend(_check_feature_api_coverage(feature_text, api_text))
    issues.extend(_check_api_database_coverage(api_text, table_text))
    issues.extend(_check_product_image_storage(feature_text, api_text, table_text))
    issues.extend(_check_admin_permissions(backend_design))

    return issues


def run_v2_rule_checks(
    prd: PrdArtifact,
    architecture_design: ArchitectureDesignArtifact,
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
) -> list[ReviewIssue]:
    issues = run_rule_checks(prd, backend_design)
    prd_text = _prd_text(prd)

    if _mentions_any(prd_text, ["prd edit", "prd editing", "human edit", "approve prd", "approval"]):
        issues.extend(
            _require_api_binding(
                frontend_skeleton,
                "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                "PRD editing requires a frontend binding for the PRD approval API.",
            )
        )

    if _mentions_any(prd_text, ["real-time", "realtime", "stream", "sse", "websocket", "event"]):
        issues.extend(
            _require_backend_api(
                backend_design,
                "/api/projects/{projectId}/events",
                "Real-time progress requires a backend event stream API.",
            )
        )
        issues.extend(
            _require_architecture_text(
                architecture_design,
                "event",
                "Real-time progress requires architecture coverage for Agent event storage or streaming.",
            )
        )

    if _mentions_any(prd_text, ["retry", "regeneration", "regenerate", "rerun"]):
        issues.extend(
            _require_backend_api(
                backend_design,
                "/api/projects/{projectId}/tasks/{taskId}/retry",
                "Retry/regeneration requires a backend failed-node retry API.",
            )
        )

    return issues


def run_v3_rule_checks(
    prd: PrdArtifact,
    backend_design: BackendDesignArtifact,
    retrieved_sources: list[dict[str, Any]] | None,
    generated_files: list[dict[str, Any] | str] | None,
) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    prd_text = _prd_text(prd)

    if _mentions_any(
        prd_text,
        ["login", "permission", "private project", "private projects", "auth", "鏉冮檺", "鐧诲綍"],
    ):
        issues.extend(_require_authenticated_project_apis(backend_design))

    if _mentions_any(
        prd_text,
        ["history", "rag", "historical", "reuse", "鍘嗗彶", "鐭ヨ瘑澶嶇敤"],
    ) and not _has_valid_retrieved_source(retrieved_sources or []):
        issues.append(
            _issue(
                severity="HIGH",
                issue_type="RAG_SOURCE_CITATION",
                description="Historical reuse is requested but no retrieved artifact source is attached.",
                suggestion="Attach approved artifact source references to Agent task input.",
            )
        )

    if generated_files and any(_contains_secret_marker(file) for file in generated_files):
        issues.append(
            _issue(
                severity="CRITICAL",
                issue_type="CODE_EXPORT_SECRET",
                description="Generated code skeleton contains secret-like configuration.",
                suggestion="Use placeholder variables and .env.example only.",
            )
        )

    return issues


def score_from_issues(issues: list[ReviewIssue]) -> int:
    severity_penalty = {"CRITICAL": 30, "HIGH": 20, "MEDIUM": 10, "LOW": 5}
    penalty = sum(severity_penalty.get(issue.severity.upper(), 5) for issue in issues)
    return max(0, 100 - penalty)


def _check_feature_api_coverage(feature_text: str, api_text: str) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    for rule in FEATURE_API_RULES:
        if any(term in feature_text for term in rule["feature_terms"]):
            if not any(term in api_text for term in rule["expected_terms"]):
                issues.append(
                    ReviewIssue(
                        severity="HIGH",
                        issue_type="API_COVERAGE",
                        description=f"Feature coverage is missing for: {rule['feature_terms'][0]}.",
                        suggestion=rule["suggestion"],
                    )
                )
    return issues


def _check_api_database_coverage(api_text: str, table_text: str) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    for api_term, table_terms in API_DATA_RULES:
        if api_term in api_text and not any(table_term in table_text for table_term in table_terms):
            issues.append(
                ReviewIssue(
                    severity="HIGH",
                    issue_type="DATA_COVERAGE",
                    description=f"API references '{api_term}' but no related table or field is defined.",
                    suggestion=f"Add database coverage for {api_term}.",
                )
            )
    return issues


def _check_product_image_storage(feature_text: str, api_text: str, table_text: str) -> list[ReviewIssue]:
    mentions_images = any(term in f"{feature_text} {api_text}" for term in ["image", "images", "图片", "照片"])
    stores_images = any(term in table_text for term in ["image", "images", "image_url", "image_urls", "图片"])
    if mentions_images and not stores_images:
        return [
            ReviewIssue(
                severity="MEDIUM",
                issue_type="DATA_COVERAGE",
                description="Product image requirements are not represented in the database design.",
                suggestion="Add image URL or attachment fields to the product data model.",
            )
        ]
    return []


def _check_admin_permissions(backend_design: BackendDesignArtifact) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    for api in backend_design.apis:
        api_path = api.path.lower()
        api_description = api.description.lower()
        is_admin_audit_api = (
            "/admin" in api_path
            or "admin audit" in api_description
            or "管理员审核" in api.description
            or ("audit" in api_description and ("approve" in api_description or "reject" in api_description))
        )
        if is_admin_audit_api and (not api.auth_required or "ADMIN" not in api.required_roles):
            issues.append(
                ReviewIssue(
                    severity="HIGH",
                    issue_type="PERMISSION_COVERAGE",
                    description=f"Admin/audit API '{api.path}' is missing ADMIN permission coverage.",
                    suggestion="Require authentication and include ADMIN in required_roles.",
                )
            )
    return issues


def _feature_text(prd: PrdArtifact) -> str:
    return " ".join(
        f"{feature.name} {feature.description}" for feature in prd.core_features
    ).lower()


def _api_text(backend_design: BackendDesignArtifact) -> str:
    return " ".join(
        f"{api.method} {api.path} {api.description} "
        f"{' '.join(param.name for param in api.request_params)} "
        f"{' '.join(param.description for param in api.request_params)} "
        f"{' '.join(field.name for field in api.response_fields)} "
        f"{' '.join(field.description for field in api.response_fields)}"
        for api in backend_design.apis
    ).lower()


def _table_text(backend_design: BackendDesignArtifact) -> str:
    return " ".join(
        f"{table.name} {table.description} "
        f"{' '.join(field.name for field in table.fields)} "
        f"{' '.join(field.description for field in table.fields)}"
        for table in backend_design.tables
    ).lower()


def _prd_text(prd: PrdArtifact) -> str:
    return " ".join(
        [
            prd.project_name,
            " ".join(prd.target_users),
            _feature_text(prd),
            " ".join(
                f"{story.role} {story.goal} {story.benefit} {' '.join(story.acceptance_criteria)}"
                for story in prd.user_stories
            ),
            " ".join(prd.business_boundaries),
            " ".join(prd.non_functional_requirements),
            " ".join(prd.risks),
        ]
    ).lower()


def _architecture_text(architecture_design: ArchitectureDesignArtifact) -> str:
    return " ".join(
        [
            architecture_design.system_context,
            " ".join(
                f"{module.name} {module.responsibility} {' '.join(module.depends_on)}"
                for module in architecture_design.modules
            ),
            " ".join(
                f"{decision.title} {decision.context} {decision.decision} {' '.join(decision.consequences)}"
                for decision in architecture_design.decisions
            ),
            " ".join(
                f"{constraint.category} {constraint.requirement}"
                for constraint in architecture_design.non_functional_constraints
            ),
            " ".join(architecture_design.integration_risks),
        ]
    ).lower()


def _mentions_any(text: str, terms: list[str]) -> bool:
    return any(term.lower() in text for term in terms)


def _require_backend_api(
    backend_design: BackendDesignArtifact,
    required_path: str,
    description: str,
) -> list[ReviewIssue]:
    if any(api.path.lower() == required_path.lower() for api in backend_design.apis):
        return []
    return [
        ReviewIssue(
            severity="HIGH",
            issue_type="API_COVERAGE",
            description=description,
            suggestion=f"Add backend API {required_path}.",
        )
    ]


def _require_api_binding(
    frontend_skeleton: FrontendSkeletonArtifact,
    required_path: str,
    description: str,
) -> list[ReviewIssue]:
    if any(binding.path.lower() == required_path.lower() for binding in frontend_skeleton.api_bindings):
        return []
    return [
        ReviewIssue(
            severity="HIGH",
            issue_type="FRONTEND_API_COVERAGE",
            description=description,
            suggestion=f"Bind a frontend component to {required_path}.",
        )
    ]


def _require_architecture_text(
    architecture_design: ArchitectureDesignArtifact,
    required_term: str,
    description: str,
) -> list[ReviewIssue]:
    if required_term.lower() in _architecture_text(architecture_design):
        return []
    return [
        ReviewIssue(
            severity="MEDIUM",
            issue_type="ARCHITECTURE_COVERAGE",
            description=description,
            suggestion=f"Document {required_term} ownership in the architecture design.",
        )
    ]


def _require_authenticated_project_apis(backend_design: BackendDesignArtifact) -> list[ReviewIssue]:
    issues: list[ReviewIssue] = []
    for api in backend_design.apis:
        if "/api/projects" not in api.path.lower():
            continue
        if api.auth_required and api.required_roles:
            continue
        issues.append(
            _issue(
                severity="HIGH",
                issue_type="PERMISSION_BOUNDARY",
                description=f"Project API '{api.path}' is outside the authenticated project/member boundary.",
                suggestion="Require authentication and at least one project member role for project-scoped APIs.",
            )
        )
    return issues


def _has_valid_retrieved_source(retrieved_sources: list[dict[str, Any]]) -> bool:
    for source in retrieved_sources:
        if not isinstance(source, dict):
            continue
        if source.get("artifact_id") or source.get("document_id") or source.get("chunk_id"):
            return True
        if source.get("artifactType") or source.get("artifact_type") or source.get("title"):
            return True
    return False


def _contains_secret_marker(generated_file: dict[str, Any] | str) -> bool:
    text = generated_file if isinstance(generated_file, str) else str(generated_file.get("content", ""))
    lower_text = text.lower()
    for marker in ["api_key", "api-key", "apikey", "secret", "password", "token"]:
        marker_index = lower_text.find(marker)
        while marker_index >= 0:
            value = _value_after_assignment(lower_text, marker_index + len(marker))
            if value and not _is_placeholder_value(value):
                return True
            marker_index = lower_text.find(marker, marker_index + len(marker))
    return False


def _value_after_assignment(text: str, start_index: int) -> str | None:
    remainder = text[start_index:].lstrip()
    if not remainder or remainder[0] not in [":", "="]:
        return None
    value = remainder[1:].strip().strip("'\"")
    if not value:
        return None
    return value.split()[0].strip(",").strip("'\"")


def _is_placeholder_value(value: str) -> bool:
    placeholder_terms = [
        "",
        "changeme",
        "change-me",
        "example",
        "placeholder",
        "replace_me",
        "replace-me",
        "todo",
        "your-value",
        "your_value",
        "***",
        "******",
    ]
    normalized = value.strip().lower()
    return (
        normalized in placeholder_terms
        or normalized.startswith("${")
        or normalized.startswith("<")
        or normalized.startswith("your-")
        or normalized.startswith("your_")
    )


def _issue(severity: str, issue_type: str, description: str, suggestion: str) -> ReviewIssue:
    return ReviewIssue(
        severity=severity,
        issue_type=issue_type,
        description=description,
        suggestion=suggestion,
    )
