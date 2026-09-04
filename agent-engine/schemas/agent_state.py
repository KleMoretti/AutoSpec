from __future__ import annotations

import time
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


RouteAction = Literal[
    "CONTINUE",
    "SWITCH_SKILL",
    "APPEND_QUESTION",
    "REWORK",
    "WAIT_APPROVAL",
    "END",
    "FAIL",
]

NextAction = Literal["RUN_NODE", "REPLAN", "WAIT_APPROVAL", "END", "FAIL"]


class RouteDecision(BaseModel):
    """A replayable decision produced at a workflow boundary."""

    model_config = ConfigDict(extra="forbid")

    action: RouteAction
    from_node: str = Field(min_length=1)
    to_node: str | None = Field(default=None, min_length=1)
    reason: str = Field(min_length=1, max_length=1000)
    evidence: list[str] = Field(default_factory=list)


class AgentStep(BaseModel):
    model_config = ConfigDict(extra="forbid")

    step: int = Field(ge=1)
    node: str = Field(min_length=1)
    status: Literal["SUCCEEDED", "FAILED", "SKIPPED"]
    execution_id: str | None = Field(default=None, min_length=1)
    route_reason: str | None = Field(default=None, max_length=1000)


class AgentState(BaseModel):
    """Serializable state envelope shared by agent-oriented workflows.

    The interview-named fields remain optional so this envelope can be used by
    the roadmap's interview examples while AutoSpec maps its PRD and artifact
    context into the same state contract.
    """

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["agent-state-v1"] = "agent-state-v1"
    workflow_key: str = Field(min_length=1)
    workflow_version: str = Field(min_length=1)
    session_id: str = Field(min_length=1)
    project_id: int | None = Field(default=None, ge=1)

    resume_profile: dict[str, Any] = Field(default_factory=dict)
    target_position: str | None = Field(default=None, min_length=1)
    skill_graph: dict[str, list[str]] = Field(default_factory=dict)
    interview_plan: list[str] = Field(default_factory=list)
    current_stage: str = Field(min_length=1)
    qa_history: list[dict[str, Any]] = Field(default_factory=list)
    weakness_profile: dict[str, float] = Field(default_factory=dict)
    tool_results: list[dict[str, Any]] = Field(default_factory=list)
    score: float | None = Field(default=None, ge=0, le=100)
    next_action: NextAction = "RUN_NODE"
    step_count: int = Field(default=0, ge=0)
    max_steps: int = Field(default=64, ge=1, le=10_000)
    token_budget: int = Field(default=0, ge=0)
    consumed_tokens: int = Field(default=0, ge=0)
    started_at_epoch_ms: int = Field(
        default_factory=lambda: round(time.time() * 1000), ge=0
    )
    deadline_epoch_ms: int | None = Field(default=None, ge=0)
    coverage: dict[str, float] = Field(default_factory=dict)
    route_decisions: list[RouteDecision] = Field(default_factory=list)
    steps: list[AgentStep] = Field(default_factory=list)

    def record_step(
        self,
        *,
        node: str,
        status: Literal["SUCCEEDED", "FAILED", "SKIPPED"],
        execution_id: str | None = None,
        route_reason: str | None = None,
    ) -> None:
        self.step_count += 1
        self.steps.append(
            AgentStep(
                step=self.step_count,
                node=node,
                status=status,
                execution_id=execution_id,
                route_reason=route_reason,
            )
        )

    def record_route(self, decision: RouteDecision) -> None:
        self.route_decisions.append(decision)
        if decision.action == "END":
            self.next_action = "END"
        elif decision.action == "FAIL":
            self.next_action = "FAIL"
        elif decision.action == "WAIT_APPROVAL":
            self.next_action = "WAIT_APPROVAL"
        elif decision.action in {"REWORK", "SWITCH_SKILL", "APPEND_QUESTION"}:
            self.next_action = "REPLAN"
        else:
            self.next_action = "RUN_NODE"


class TerminationPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    max_steps: int = Field(default=64, ge=1, le=10_000)
    max_questions: int = Field(default=0, ge=0, le=10_000)
    max_tokens: int = Field(default=0, ge=0)
    max_wall_time_ms: int = Field(default=0, ge=0)
    min_coverage: float = Field(default=0.0, ge=0, le=1)


class TerminationDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    should_end: bool
    reason: Literal[
        "STEP_LIMIT",
        "QUESTION_LIMIT",
        "TOKEN_LIMIT",
        "DEADLINE",
        "COVERAGE_REACHED",
        "NO_LIMIT_REACHED",
    ]
