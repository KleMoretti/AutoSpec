from __future__ import annotations

from typing import Any

from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.evaluation import EvaluationDimensionScore, EvaluationIssue, EvaluationReport
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewReport


def evaluate_artifacts(
    *,
    requirement: str,
    prd: PrdArtifact,
    architecture_design: ArchitectureDesignArtifact,
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
    review_report: ReviewReport,
    records: list[Any] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
    generated_files: list[dict[str, Any] | str] | None = None,
) -> EvaluationReport:
    issues: list[EvaluationIssue] = []
    dimension_scores = [
        _schema_validity_score(),
        _requirement_coverage_score(prd, backend_design, frontend_skeleton, issues),
        _cross_artifact_consistency_score(backend_design, frontend_skeleton, issues),
        _permission_coverage_score(backend_design, issues),
        _rag_citation_score(requirement, prd, retrieved_sources or [], issues),
        _runtime_reliability_score(records or [], issues),
        _export_readiness_score(generated_files or [], issues),
    ]
    if review_report.issues:
        issues.extend(
            EvaluationIssue(
                severity=issue.severity,
                issue_type=f"REVIEW_{issue.issue_type}",
                description=issue.description,
                suggestion=issue.suggestion,
                evidence=["ReviewerAgent_v1"],
            )
            for issue in review_report.issues
        )

    overall_score = int(sum(score.score for score in dimension_scores) / len(dimension_scores))
    return EvaluationReport(
        overall_score=overall_score,
        final_grade=_grade(overall_score),
        dimension_scores=dimension_scores,
        issues=issues,
    )


def _schema_validity_score() -> EvaluationDimensionScore:
    return EvaluationDimensionScore(
        dimension="SCHEMA_VALIDITY",
        score=100,
        rationale="Artifacts were parsed through strict Pydantic schemas before evaluation.",
    )


def _requirement_coverage_score(
    prd: PrdArtifact,
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    prd_text = _prd_text(prd)
    backend_text = _backend_text(backend_design)
    frontend_text = _frontend_text(frontend_skeleton)
    missing_terms: list[str] = []
    for term in _important_terms(prd_text):
        if term not in backend_text and term not in frontend_text:
            missing_terms.append(term)

    if missing_terms:
        issues.append(
            EvaluationIssue(
                severity="MEDIUM",
                issue_type="REQUIREMENT_COVERAGE",
                description=f"Important requirement terms are missing from downstream artifacts: {', '.join(missing_terms)}.",
                suggestion="Reflect each core PRD capability in backend APIs or frontend pages.",
                evidence=missing_terms,
            )
        )
        return EvaluationDimensionScore(
            dimension="REQUIREMENT_COVERAGE",
            score=max(60, 100 - len(missing_terms) * 10),
            rationale="Some PRD capability terms are missing from backend and frontend artifacts.",
        )

    return EvaluationDimensionScore(
        dimension="REQUIREMENT_COVERAGE",
        score=100,
        rationale="Core PRD capability terms are represented in downstream artifacts.",
    )


def _cross_artifact_consistency_score(
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    frontend_paths = {binding.path.lower() for binding in frontend_skeleton.api_bindings}
    missing_bindings = [
        api.path for api in backend_design.apis if api.method in {"GET", "POST", "PUT", "PATCH", "DELETE"} and api.path.lower() not in frontend_paths
    ]
    if missing_bindings:
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="FRONTEND_COVERAGE",
                description=f"Frontend skeleton is missing API bindings for: {', '.join(missing_bindings)}.",
                suggestion="Add frontend api_bindings for backend APIs used by the user workflow.",
                evidence=missing_bindings,
            )
        )
        return EvaluationDimensionScore(
            dimension="CROSS_ARTIFACT_CONSISTENCY",
            score=max(50, 100 - len(missing_bindings) * 20),
            rationale="Some backend APIs are not consumed by frontend bindings.",
        )

    return EvaluationDimensionScore(
        dimension="CROSS_ARTIFACT_CONSISTENCY",
        score=100,
        rationale="Backend API paths are represented in frontend bindings.",
    )


def _permission_coverage_score(
    backend_design: BackendDesignArtifact,
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    uncovered_paths = [
        api.path for api in backend_design.apis if "/api/projects" in api.path.lower() and (not api.auth_required or not api.required_roles)
    ]
    if uncovered_paths:
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="PERMISSION_COVERAGE",
                description=f"Project-scoped APIs are missing authentication or roles: {', '.join(uncovered_paths)}.",
                suggestion="Require authentication and explicit project roles for project-scoped APIs.",
                evidence=uncovered_paths,
            )
        )
        return EvaluationDimensionScore(
            dimension="PERMISSION_COVERAGE",
            score=max(50, 100 - len(uncovered_paths) * 20),
            rationale="Some project APIs are outside an explicit permission boundary.",
        )

    return EvaluationDimensionScore(
        dimension="PERMISSION_COVERAGE",
        score=100,
        rationale="Project-scoped APIs require authentication and roles.",
    )


def _rag_citation_score(
    requirement: str,
    prd: PrdArtifact,
    retrieved_sources: list[dict[str, Any]],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    text = f"{requirement} {_prd_text(prd)}".lower()
    needs_sources = any(term in text for term in ["history", "historical", "rag", "reuse"])
    if needs_sources and not _has_valid_retrieved_source(retrieved_sources):
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="RAG_CITATION",
                description="Historical reuse or RAG is requested but no retrieved artifact source is attached.",
                suggestion="Attach approved artifact source references to the Agent task input.",
                evidence=["retrieved_sources"],
            )
        )
        return EvaluationDimensionScore(
            dimension="RAG_CITATION_QUALITY",
            score=60,
            rationale="RAG or historical reuse lacks source citation evidence.",
        )

    return EvaluationDimensionScore(
        dimension="RAG_CITATION_QUALITY",
        score=100,
        rationale="No RAG citation is required, or retrieved sources are attached.",
    )


def _runtime_reliability_score(
    records: list[Any],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    failed_records = [record for record in records if _record_status(record) != "SUCCEEDED"]
    if failed_records:
        failed_nodes = [_record_node_name(record) for record in failed_records]
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="RUNTIME_FAILURE",
                description=f"Agent runtime contains failed nodes: {', '.join(failed_nodes)}.",
                suggestion="Retry failed nodes or fix their schema/model errors before accepting the run.",
                evidence=failed_nodes,
            )
        )
        return EvaluationDimensionScore(
            dimension="RUNTIME_RELIABILITY",
            score=max(40, 100 - len(failed_records) * 30),
            rationale="One or more Agent nodes failed during execution.",
        )

    return EvaluationDimensionScore(
        dimension="RUNTIME_RELIABILITY",
        score=100,
        rationale="All recorded Agent nodes succeeded.",
    )


def _export_readiness_score(
    generated_files: list[dict[str, Any] | str],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    secret_files = [_file_path(file) for file in generated_files if _contains_secret_marker(file)]
    if secret_files:
        issues.append(
            EvaluationIssue(
                severity="CRITICAL",
                issue_type="EXPORT_READINESS",
                description=f"Generated files contain concrete secret-like values: {', '.join(secret_files)}.",
                suggestion="Use environment placeholders and .env.example entries instead of real secrets.",
                evidence=secret_files,
            )
        )
        return EvaluationDimensionScore(
            dimension="EXPORT_READINESS",
            score=60,
            rationale="Generated export contains concrete secret-like configuration.",
        )

    return EvaluationDimensionScore(
        dimension="EXPORT_READINESS",
        score=100,
        rationale="Generated files avoid concrete secret-like values.",
    )


def _important_terms(prd_text: str) -> list[str]:
    terms = []
    for term in ["artifact", "api", "frontend", "project"]:
        if term in prd_text:
            terms.append(term)
    return terms


def _prd_text(prd: PrdArtifact) -> str:
    return " ".join(
        [
            prd.project_name,
            " ".join(prd.target_users),
            " ".join(f"{feature.name} {feature.description}" for feature in prd.core_features),
            " ".join(
                f"{story.role} {story.goal} {story.benefit} {' '.join(story.acceptance_criteria)}"
                for story in prd.user_stories
            ),
            " ".join(prd.business_boundaries),
            " ".join(prd.non_functional_requirements),
            " ".join(prd.risks),
        ]
    ).lower()


def _backend_text(backend_design: BackendDesignArtifact) -> str:
    return " ".join(
        [
            " ".join(
                f"{table.name} {table.description} {' '.join(field.name for field in table.fields)}"
                for table in backend_design.tables
            ),
            " ".join(f"{api.method} {api.path} {api.description}" for api in backend_design.apis),
        ]
    ).lower()


def _frontend_text(frontend_skeleton: FrontendSkeletonArtifact) -> str:
    return " ".join(
        [
            " ".join(f"{route.path} {route.page}" for route in frontend_skeleton.routes),
            " ".join(f"{page.name} {page.purpose} {' '.join(page.components)}" for page in frontend_skeleton.pages),
            " ".join(f"{binding.method} {binding.path} {binding.consumer}" for binding in frontend_skeleton.api_bindings),
        ]
    ).lower()


def _has_valid_retrieved_source(retrieved_sources: list[dict[str, Any]]) -> bool:
    return any(source.get("artifact_id") or source.get("document_id") or source.get("chunk_id") for source in retrieved_sources)


def _record_status(record: Any) -> str:
    if isinstance(record, dict):
        return str(record.get("status", "")).upper()
    return str(getattr(record, "status", "")).upper()


def _record_node_name(record: Any) -> str:
    if isinstance(record, dict):
        return str(record.get("node_name", "unknown"))
    return str(getattr(record, "node_name", "unknown"))


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
    normalized = value.strip().lower()
    return (
        normalized in {"changeme", "change-me", "example", "placeholder", "replace_me", "replace-me", "todo"}
        or normalized.startswith("${")
        or normalized.startswith("<")
        or normalized.startswith("your-")
        or normalized.startswith("your_")
    )


def _file_path(generated_file: dict[str, Any] | str) -> str:
    if isinstance(generated_file, str):
        return "inline"
    return str(generated_file.get("path", "inline"))


def _grade(score: int) -> str:
    if score >= 90:
        return "A"
    if score >= 80:
        return "B"
    if score >= 70:
        return "C"
    if score >= 60:
        return "D"
    return "F"
