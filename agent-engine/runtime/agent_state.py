from __future__ import annotations

import time
from typing import Any

from schemas.agent_state import AgentState, TerminationDecision, TerminationPolicy


def check_termination(
    state: AgentState,
    policy: TerminationPolicy,
    *,
    now_epoch_ms: int | None = None,
) -> TerminationDecision:
    """Apply hard stop conditions in a stable order before another node runs."""

    now = round(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
    if state.step_count >= policy.max_steps:
        return TerminationDecision(should_end=True, reason="STEP_LIMIT")
    questions = sum(
        1
        for item in state.qa_history
        if isinstance(item, dict) and item.get("role") == "assistant"
    )
    if policy.max_questions and questions >= policy.max_questions:
        return TerminationDecision(should_end=True, reason="QUESTION_LIMIT")
    if policy.max_tokens and state.consumed_tokens >= policy.max_tokens:
        return TerminationDecision(should_end=True, reason="TOKEN_LIMIT")
    deadlines = [
        deadline
        for deadline in (
            state.deadline_epoch_ms,
            state.started_at_epoch_ms + policy.max_wall_time_ms
            if policy.max_wall_time_ms
            else None,
        )
        if deadline is not None
    ]
    if deadlines and now >= min(deadlines):
        return TerminationDecision(should_end=True, reason="DEADLINE")
    if policy.min_coverage and state.coverage:
        covered = sum(1 for value in state.coverage.values() if value >= 1)
        if covered / len(state.coverage) >= policy.min_coverage:
            return TerminationDecision(should_end=True, reason="COVERAGE_REACHED")
    return TerminationDecision(should_end=False, reason="NO_LIMIT_REACHED")


def state_from_workflow_payload(
    *,
    workflow_key: str,
    workflow_version: str,
    session_id: str,
    current_stage: str,
    payload: dict[str, Any],
    max_steps: int = 64,
) -> AgentState:
    """Build a serializable state view without copying large artifact bodies."""

    artifact_ids = {
        key: value.get("artifact_id")
        for key, value in payload.items()
        if isinstance(value, dict) and value.get("artifact_id") is not None
    }
    return AgentState(
        workflow_key=workflow_key,
        workflow_version=workflow_version,
        session_id=session_id,
        project_id=_positive_int(payload.get("project_id")),
        resume_profile={"artifact_refs": artifact_ids},
        interview_plan=[current_stage],
        current_stage=current_stage,
        qa_history=list(payload.get("qa_history", []))
        if isinstance(payload.get("qa_history"), list)
        else [],
        weakness_profile=dict(payload.get("weakness_profile", {}))
        if isinstance(payload.get("weakness_profile"), dict)
        else {},
        tool_results=list(payload.get("tool_results", []))
        if isinstance(payload.get("tool_results"), list)
        else [],
        score=_score(payload.get("score")),
        token_budget=_positive_int(payload.get("token_budget")) or 0,
        consumed_tokens=_positive_int(payload.get("consumed_tokens")) or 0,
        started_at_epoch_ms=(
            _positive_int(payload.get("started_at_epoch_ms"))
            or round(time.time() * 1000)
        ),
        deadline_epoch_ms=_positive_int(payload.get("deadline_epoch_ms")),
        step_count=_positive_int(payload.get("step_count")) or 0,
        max_steps=max_steps,
    )


def _positive_int(value: Any) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _score(value: Any) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if 0 <= parsed <= 100 else None
