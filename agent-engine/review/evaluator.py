from __future__ import annotations

from typing import Any

from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.evaluation import (
    EvaluationDimensionScore,
    EvaluationIssue,
    EvaluationReport,
    RequirementTrace,
)
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
    model_invocations: list[Any] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
    generated_files: list[dict[str, Any] | str] | None = None,
) -> EvaluationReport:
    issues: list[EvaluationIssue] = []
    traceability = _build_requirement_traceability(
        prd,
        architecture_design,
        backend_design,
        frontend_skeleton,
    )
    dimension_scores = [
        _schema_validity_score(),
        _requirement_coverage_score(traceability, issues),
        _cross_artifact_consistency_score(backend_design, frontend_skeleton, issues),
        _permission_coverage_score(backend_design, issues),
        _rag_citation_score(requirement, prd, retrieved_sources or [], issues),
        _runtime_reliability_score(records or [], model_invocations or [], issues),
        _export_readiness_score(generated_files or [], issues),
    ]
    if review_report.issues:
        issues.extend(
            EvaluationIssue(
                severity=issue.severity,
                issue_type=f"REVIEW_{issue.issue_type}",
                description=issue.description,
                suggestion=issue.suggestion,
                evidence=["ReviewerAgent_v1", *issue.evidence],
                requirement_id=issue.requirement_id,
                artifact_path=issue.artifact_path,
            )
            for issue in review_report.issues
        )

    normalized_issues = [
        issue.model_copy(
            update={
                "blocking": issue.blocking
                or issue.severity.upper() in {"CRITICAL", "HIGH"}
            }
        )
        for issue in issues
    ]
    blocking_issues = [issue for issue in normalized_issues if issue.blocking]
    overall_score = int(sum(score.score for score in dimension_scores) / len(dimension_scores))
    if any(issue.severity.upper() == "CRITICAL" for issue in blocking_issues):
        overall_score = min(overall_score, 49)
    elif blocking_issues:
        overall_score = min(overall_score, 69)
    return EvaluationReport(
        overall_score=overall_score,
        final_grade=_grade(overall_score),
        dimension_scores=dimension_scores,
        issues=normalized_issues,
        gate_status="BLOCKED" if blocking_issues else "PASSED",
        blocking_issue_count=len(blocking_issues),
        requirement_traceability=traceability,
    )


def _schema_validity_score() -> EvaluationDimensionScore:
    return EvaluationDimensionScore(
        dimension="SCHEMA_VALIDITY",
        score=100,
        rationale="Artifacts were parsed through strict Pydantic schemas before evaluation.",
    )


def _requirement_coverage_score(
    traceability: list[RequirementTrace],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    must_requirements = [trace for trace in traceability if trace.priority == "MUST"]
    if not must_requirements:
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="MISSING_MUST_REQUIREMENT",
                description="The PRD does not identify any MUST requirement.",
                suggestion="Classify at least one core capability as MUST before approval.",
                evidence=["prd.core_features"],
                artifact_path="PRD.core_features",
                blocking=True,
            )
        )
        return EvaluationDimensionScore(
            dimension="REQUIREMENT_COVERAGE",
            score=0,
            rationale="No mandatory requirement was declared, so trace coverage cannot be established.",
        )

    missing = [trace for trace in must_requirements if not trace.covered]
    for trace in missing:
        missing_layers = []
        if not trace.api_evidence:
            missing_layers.append("API")
        if not trace.data_evidence:
            missing_layers.append("data")
        if not trace.ui_evidence:
            missing_layers.append("UI")
        if not trace.acceptance_evidence:
            missing_layers.append("acceptance criteria")
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="MUST_REQUIREMENT_TRACE_GAP",
                description=(
                    f"{trace.requirement_id} is missing trace evidence for: "
                    + ", ".join(missing_layers)
                    + "."
                ),
                suggestion=(
                    "Link the mandatory requirement to a user story, acceptance criteria, "
                    "API, table/field, and UI page or binding."
                ),
                evidence=[trace.requirement],
                requirement_id=trace.requirement_id,
                artifact_path="requirement_traceability",
                blocking=True,
            )
        )

    score = round(
        100
        * (len(must_requirements) - len(missing))
        / len(must_requirements)
    )
    if missing:
        return EvaluationDimensionScore(
            dimension="REQUIREMENT_COVERAGE",
            score=score,
            rationale=(
                f"{len(must_requirements) - len(missing)}/{len(must_requirements)} "
                "MUST requirements have complete PRD/API/data/UI/acceptance trace evidence."
            ),
        )
    return EvaluationDimensionScore(
        dimension="REQUIREMENT_COVERAGE",
        score=100,
        rationale="Every MUST requirement has PRD/API/data/UI/acceptance trace evidence.",
    )


def _build_requirement_traceability(
    prd: PrdArtifact,
    architecture_design: ArchitectureDesignArtifact,
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
) -> list[RequirementTrace]:
    traces: list[RequirementTrace] = []
    for feature in prd.core_features:
        requirement_id = feature.requirement_id
        if requirement_id is None:
            raise ValueError("PRD requirement identity was not assigned")
        requirement = f"{feature.name}: {feature.description}"
        linked_stories = [
            story
            for story in prd.user_stories
            if requirement_id in story.requirement_refs
        ]
        acceptance_evidence = [
            f"{criterion.acceptance_id}:{criterion.criterion}"
            for story in linked_stories
            for criterion in story.acceptance_criteria
            if requirement_id in criterion.requirement_refs
        ]
        architecture_evidence = [
            *(f"module:{module.module_id}" for module in architecture_design.modules
              if requirement_id in module.requirement_refs),
            *(f"decision:{decision.decision_id}" for decision in architecture_design.decisions
              if requirement_id in decision.requirement_refs),
            *(f"constraint:{constraint.constraint_id}"
              for constraint in architecture_design.non_functional_constraints
              if requirement_id in constraint.requirement_refs),
        ]
        api_evidence = [
            f"api:{api.api_id}:{api.method} {api.path}"
            for api in backend_design.apis
            if requirement_id in api.requirement_refs
        ]
        data_evidence = [
            *(f"table:{table.table_id}:{table.name}" for table in backend_design.tables
              if requirement_id in table.requirement_refs),
            *(f"field:{field.field_id}:{table.name}.{field.name}"
              for table in backend_design.tables
              for field in table.fields
              if requirement_id in field.requirement_refs),
        ]
        ui_evidence = [
            *(f"route:{route.route_id}:{route.path}" for route in frontend_skeleton.routes
              if requirement_id in route.requirement_refs),
            *(f"page:{page.page_id}:{page.name}" for page in frontend_skeleton.pages
              if requirement_id in page.requirement_refs),
            *(f"component:{component.component_id}:{component.name}"
              for component in frontend_skeleton.components
              if requirement_id in component.requirement_refs),
            *(f"binding:{binding.binding_id}:{binding.method} {binding.path}"
              for binding in frontend_skeleton.api_bindings
              if requirement_id in binding.requirement_refs),
        ]
        traces.append(
            RequirementTrace(
                requirement_id=requirement_id,
                requirement=requirement,
                priority=feature.priority,
                prd_evidence=[
                    f"requirement:{requirement_id}",
                    *[
                        f"story:{story.story_id}"
                        for story in linked_stories
                    ],
                ],
                architecture_evidence=architecture_evidence,
                api_evidence=api_evidence,
                data_evidence=data_evidence,
                ui_evidence=ui_evidence,
                acceptance_evidence=acceptance_evidence,
                covered=bool(
                    linked_stories
                    and acceptance_evidence
                    and api_evidence
                    and data_evidence
                    and ui_evidence
                ),
            )
        )
    return traces


def _cross_artifact_consistency_score(
    backend_design: BackendDesignArtifact,
    frontend_skeleton: FrontendSkeletonArtifact,
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    frontend_operations = {
        (binding.method.upper(), binding.path.lower())
        for binding in frontend_skeleton.api_bindings
    }
    missing_bindings = [
        f"{api.method} {api.path}"
        for api in backend_design.apis
        if (api.method, api.path.lower()) not in frontend_operations
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
    public_endpoints = ("/login", "/register", "/health", "/public")
    uncovered_paths = [
        api.path
        for api in backend_design.apis
        if not any(marker in api.path.lower() for marker in public_endpoints)
        and (
            api.method in {"POST", "PUT", "PATCH", "DELETE"}
            or "{" in api.path
            or any(
                term in f"{api.path} {api.description}".lower()
                for term in ("admin", "approve", "audit", "owner", "permission")
            )
        )
        and (not api.auth_required or not api.required_roles)
    ]
    admin_role_gaps = [
        api.path
        for api in backend_design.apis
        if any(
            term in f"{api.path} {api.description}".lower()
            for term in ("admin", "approve", "audit")
        )
        and "ADMIN" not in {role.upper() for role in api.required_roles}
    ]
    uncovered_paths = list(dict.fromkeys([*uncovered_paths, *admin_role_gaps]))
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
    needs_sources = (
        any(term in text for term in ["history", "historical", "rag", "reuse"])
        or bool(retrieved_sources)
        or bool(prd.source_citations)
    )
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

    if needs_sources:
        citations = prd.source_citations
        if not citations:
            issues.append(
                EvaluationIssue(
                    severity="HIGH",
                    issue_type="RAG_CITATION_MISSING",
                    description="Historical source context is attached, but the PRD has no claim-level citations.",
                    suggestion="Cite the exact source id and a supported excerpt for every reused claim.",
                    evidence=["prd.source_citations"],
                )
            )
            return EvaluationDimensionScore(
                dimension="RAG_CITATION_QUALITY",
                score=60,
                rationale="Retrieved context is present without claim-level citation evidence.",
            )

        source_by_id = {
            str(source.get("citation_id")): source
            for source in retrieved_sources
            if isinstance(source, dict) and source.get("citation_id")
        }
        unknown = [citation.citation_id for citation in citations if citation.citation_id not in source_by_id]
        unfaithful = [
            citation.citation_id
            for citation in citations
            if citation.citation_id in source_by_id
            and not _excerpt_is_supported(
                citation.excerpt,
                str(source_by_id[citation.citation_id].get("content", "")),
            )
        ]
        if unknown or unfaithful:
            evidence = [f"unknown:{value}" for value in unknown]
            evidence.extend(f"unsupported:{value}" for value in unfaithful)
            issues.append(
                EvaluationIssue(
                    severity="HIGH",
                    issue_type="RAG_CITATION_UNFAITHFUL",
                    description="One or more source citations are unknown or not supported by the cited excerpt.",
                    suggestion="Use a server-issued citation id and copy a short excerpt from that exact source chunk.",
                    evidence=evidence,
                )
            )
            return EvaluationDimensionScore(
                dimension="RAG_CITATION_QUALITY",
                score=40,
                rationale="Citation ids or excerpts are not faithful to retrieved source chunks.",
            )

    return EvaluationDimensionScore(
        dimension="RAG_CITATION_QUALITY",
        score=100,
        rationale="No RAG citation is required, or retrieved sources are attached.",
    )


def _excerpt_is_supported(excerpt: str, content: str) -> bool:
    normalized_excerpt = " ".join(excerpt.lower().split())
    normalized_content = " ".join(content.lower().split())
    return len(normalized_excerpt) >= 3 and normalized_excerpt in normalized_content


def _runtime_reliability_score(
    records: list[Any],
    model_invocations: list[Any],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    if not records:
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="RUNTIME_EVIDENCE_MISSING",
                description="No Agent execution records were supplied to the evaluator.",
                suggestion="The control plane must attach every trusted node execution record before approving the run.",
                evidence=["records"],
                blocking=True,
            )
        )
        return EvaluationDimensionScore(
            dimension="RUNTIME_RELIABILITY",
            score=70,
            rationale="Runtime reliability is not verified because execution records are absent.",
        )
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

    failed_invocations = [
        invocation
        for invocation in model_invocations
        if _record_status(invocation) not in {"SUCCEEDED", "COMPLETED"}
    ]
    if failed_invocations:
        failed_models = [
            str(_record_value(invocation, "model", "model_name", "provider") or "unknown model")
            for invocation in failed_invocations
        ]
        issues.append(
            EvaluationIssue(
                severity="HIGH",
                issue_type="MODEL_INVOCATION_FAILURE",
                description=(
                    "Trusted model invocation evidence contains failed calls: "
                    + ", ".join(failed_models)
                    + "."
                ),
                suggestion="Resolve provider/model failures and rerun the affected nodes before approval.",
                evidence=failed_models,
                blocking=True,
            )
        )
        return EvaluationDimensionScore(
            dimension="RUNTIME_RELIABILITY",
            score=max(40, 100 - len(failed_invocations) * 30),
            rationale="One or more trusted model invocations failed.",
        )

    return EvaluationDimensionScore(
        dimension="RUNTIME_RELIABILITY",
        score=100,
        rationale=(
            f"All recorded Agent nodes succeeded; {len(model_invocations)} model invocations "
            "were checked when present."
        ),
    )


def _export_readiness_score(
    generated_files: list[dict[str, Any] | str],
    issues: list[EvaluationIssue],
) -> EvaluationDimensionScore:
    if not generated_files:
        return EvaluationDimensionScore(
            dimension="EXPORT_READINESS",
            score=100,
            rationale="Bundle verification is deferred to the post-generation Build/Delivery Gate.",
        )
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
                f"{story.role} {story.goal} {story.benefit} "
                f"{' '.join(criterion.criterion for criterion in story.acceptance_criteria)}"
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
    return any(
        source.get("artifact_id")
        or source.get("artifactId")
        or source.get("document_id")
        or source.get("documentId")
        or source.get("chunk_id")
        or source.get("chunkId")
        for source in retrieved_sources
        if isinstance(source, dict)
    )


def _record_status(record: Any) -> str:
    if isinstance(record, dict):
        return str(record.get("status", "")).upper()
    return str(getattr(record, "status", "")).upper()


def _record_node_name(record: Any) -> str:
    if isinstance(record, dict):
        return str(record.get("node_name", "unknown"))
    return str(getattr(record, "node_name", "unknown"))


def _record_value(record: Any, *names: str) -> Any:
    for name in names:
        value = record.get(name) if isinstance(record, dict) else getattr(record, name, None)
        if value is not None:
            return value
    return None


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
