from typing import Any

from agents.base import ModelClient
from review.rules import run_rule_checks, run_v2_rule_checks, run_v3_rule_checks, score_from_issues
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewDecision, ReviewIssue, ReviewReport, ReworkRoute


_ISSUE_TARGETS = {
    "ARCHITECTURE_COVERAGE": "architect",
    "FRONTEND_API_COVERAGE": "frontend_engineer",
    "API_COVERAGE": "backend_engineer",
    "DATA_COVERAGE": "backend_engineer",
    "PERMISSION_COVERAGE": "backend_engineer",
    "PERMISSION_BOUNDARY": "backend_engineer",
    "CODE_EXPORT_SECRET": "backend_engineer",
    "RAG_SOURCE_CITATION": "architect",
}


class ReviewerAgent:
    prompt_name = "ReviewerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        prd: PrdArtifact,
        backend_design: BackendDesignArtifact,
        architecture_design: ArchitectureDesignArtifact | None = None,
        frontend_skeleton: FrontendSkeletonArtifact | None = None,
        retrieved_sources: list[dict[str, Any]] | None = None,
        generated_files: list[dict[str, Any] | str] | None = None,
        model_invocations: list[dict[str, Any]] | None = None,
    ) -> ReviewReport:
        if architecture_design is not None and frontend_skeleton is not None:
            rule_issues = run_v2_rule_checks(prd, architecture_design, backend_design, frontend_skeleton)
        else:
            rule_issues = run_rule_checks(prd, backend_design)

        if retrieved_sources is not None or generated_files is not None or model_invocations is not None:
            rule_issues.extend(
                run_v3_rule_checks(
                    prd=prd,
                    backend_design=backend_design,
                    retrieved_sources=retrieved_sources or [],
                    generated_files=generated_files or [],
                )
            )

        if self.model_client is not None:
            input_payload: dict[str, Any] = {
                "prd": prd.model_dump(),
                "backend_design": backend_design.model_dump(),
                "rule_issues": [issue.model_dump() for issue in rule_issues],
            }
            if architecture_design is not None:
                input_payload["architecture_design"] = architecture_design.model_dump()
            if frontend_skeleton is not None:
                input_payload["frontend_skeleton"] = frontend_skeleton.model_dump()
            if retrieved_sources is not None:
                input_payload["retrieved_sources"] = retrieved_sources
            if generated_files is not None:
                input_payload["generated_files"] = generated_files
            if model_invocations is not None:
                input_payload["model_invocations"] = model_invocations

            semantic_report = ReviewReport.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )
            combined_issues = [*rule_issues, *semantic_report.issues]
            routes = self._merge_routes(
                self._routes_for_issues(rule_issues), semantic_report.routes
            )
            return ReviewReport(
                score=min(semantic_report.score, score_from_issues(rule_issues)),
                issues=combined_issues,
                decision=ReviewDecision.REWORK if routes else ReviewDecision.PASS,
                routes=routes,
            )

        routes = self._routes_for_issues(rule_issues)
        return ReviewReport(
            score=score_from_issues(rule_issues),
            issues=rule_issues,
            decision=ReviewDecision.REWORK if routes else ReviewDecision.PASS,
            routes=routes,
        )

    def _routes_for_issues(self, issues: list[ReviewIssue]) -> list[ReworkRoute]:
        grouped: dict[str, list[tuple[str, ReviewIssue]]] = {}
        for index, issue in enumerate(issues, start=1):
            target = _ISSUE_TARGETS.get(issue.issue_type, "architect")
            grouped.setdefault(target, []).append((f"R-{index}", issue))
        return [
            ReworkRoute(
                target_node=target,
                issue_ids=[issue_id for issue_id, _ in target_issues],
                required_changes=[issue.suggestion for _, issue in target_issues],
                invalidate_downstream=True,
            )
            for target, target_issues in grouped.items()
        ]

    def _merge_routes(
        self,
        rule_routes: list[ReworkRoute],
        semantic_routes: list[ReworkRoute],
    ) -> list[ReworkRoute]:
        merged: dict[str, ReworkRoute] = {}
        for route in [*rule_routes, *semantic_routes]:
            current = merged.get(route.target_node)
            if current is None:
                merged[route.target_node] = route
                continue
            merged[route.target_node] = ReworkRoute(
                target_node=route.target_node,
                issue_ids=list(dict.fromkeys([*current.issue_ids, *route.issue_ids])),
                required_changes=list(
                    dict.fromkeys([*current.required_changes, *route.required_changes])
                ),
                invalidate_downstream=(
                    current.invalidate_downstream or route.invalidate_downstream
                ),
            )
        return list(merged.values())
