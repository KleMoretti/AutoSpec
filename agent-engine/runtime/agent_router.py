from __future__ import annotations

from schemas.agent_state import RouteDecision
from schemas.evaluation import EvaluationReport
from schemas.review import ReviewReport


def route_after_review(
    report: ReviewReport,
    *,
    from_node: str = "reviewer",
    continuation_node: str = "evaluator",
) -> list[RouteDecision]:
    """Turn the reviewed, schema-validated decision into replayable routes."""

    if report.decision == "PASS":
        return [
            RouteDecision(
                action="CONTINUE",
                from_node=from_node,
                to_node=continuation_node,
                reason="Reviewer passed the artifact set; continue to the deterministic evaluator.",
                evidence=["decision:PASS"],
            )
        ]
    return [
        RouteDecision(
            action="REWORK",
            from_node=from_node,
            to_node=route.target_node,
            reason=(
                f"Reviewer requested issue-scoped rework for {route.target_node}; "
                f"required changes: {', '.join(route.required_changes)}."
            )[:1000],
            evidence=route.issue_ids,
        )
        for route in report.routes
    ]


def route_after_evaluation(
    report: EvaluationReport,
    *,
    from_node: str = "evaluator",
) -> RouteDecision:
    if report.gate_status == "PASSED":
        return RouteDecision(
            action="END",
            from_node=from_node,
            reason="Evaluator passed the delivery gate with complete traceability.",
            evidence=["gate_status:PASSED"],
        )
    blockers = [
        issue.issue_type
        for issue in report.issues
        if issue.blocking
    ]
    return RouteDecision(
        action="REWORK",
        from_node=from_node,
        reason=(
            "Evaluator blocked delivery; resolve the blocking findings before completion: "
            + ", ".join(blockers[:10])
        )[:1000],
        evidence=blockers[:10] or ["gate_status:BLOCKED"],
    )
