from __future__ import annotations

import hashlib
import json
from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator


class LoopStrategy(StrEnum):
    PLAN_ACT_OBSERVE_VALIDATE_V1 = "plan-act-observe-validate-v1"


class StopReason(StrEnum):
    COMPLETED = "COMPLETED"
    STEP_LIMIT = "STEP_LIMIT"
    REPLAN_LIMIT = "REPLAN_LIMIT"
    MODEL_BUDGET_EXHAUSTED = "MODEL_BUDGET_EXHAUSTED"
    TOOL_BUDGET_EXHAUSTED = "TOOL_BUDGET_EXHAUSTED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    PATH_OSCILLATION = "PATH_OSCILLATION"
    VALIDATION_FAILED = "VALIDATION_FAILED"


class LoopPolicy(BaseModel):
    """Frozen, provider-neutral limits for one bounded agent execution."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    version: str = Field(default="agent-loop-v1", min_length=1)
    enabled: bool = False
    strategy: LoopStrategy = LoopStrategy.PLAN_ACT_OBSERVE_VALIDATE_V1
    max_steps: int = Field(default=4, ge=1, le=32)
    max_replans: int = Field(default=2, ge=0, le=16)
    no_progress_limit: int = Field(default=1, ge=0, le=8)
    validator_profile: str = Field(default="backend-design-v1", min_length=1)


class PlanTurn(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_type: Literal["PLAN"] = "PLAN"
    goal: str = Field(min_length=1)
    steps: list[str] = Field(min_length=1, max_length=16)
    completion_conditions: list[str] = Field(default_factory=list, max_length=16)


class ToolCallTurn(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_type: Literal["TOOL_CALL"] = "TOOL_CALL"
    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)
    reason: str = Field(min_length=1)
    expected_evidence: list[str] = Field(default_factory=list, max_length=16)


class FinalCandidateTurn(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_type: Literal["FINAL_CANDIDATE"] = "FINAL_CANDIDATE"
    candidate: dict[str, Any]
    reason: str = Field(default="candidate is ready", min_length=1)


class ReplanTurn(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_type: Literal["REPLAN"] = "REPLAN"
    issue_codes: list[str] = Field(min_length=1, max_length=32)
    required_changes: list[str] = Field(min_length=1, max_length=32)
    reason: str = Field(min_length=1)


AgentTurn = Annotated[
    PlanTurn | ToolCallTurn | FinalCandidateTurn | ReplanTurn,
    Field(discriminator="turn_type"),
]
_AGENT_TURN_ADAPTER = TypeAdapter(AgentTurn)


def parse_agent_turn(value: Any) -> AgentTurn:
    """Parse a model response while accepting the wire-level ``type`` alias.

    The canonical field is ``turn_type``. Accepting ``type`` keeps the provider
    prompt ergonomic without weakening the structured boundary: the normalized
    value is still validated against the discriminated union.
    """

    if not isinstance(value, dict):
        raise ValueError("agent turn must be a JSON object")
    normalized = dict(value)
    if "turn_type" not in normalized and isinstance(normalized.get("type"), str):
        normalized["turn_type"] = normalized.pop("type")
    if "turn_type" not in normalized:
        if "tables" in normalized and "apis" in normalized:
            normalized = {
                "turn_type": "FINAL_CANDIDATE",
                "candidate": value,
                "reason": "provider returned a backend candidate",
            }
        else:
            raise ValueError("agent turn is missing turn_type")
    return _AGENT_TURN_ADAPTER.validate_python(normalized)


class StepPhase(StrEnum):
    PLAN = "PLAN"
    TOOL_CALL = "TOOL_CALL"
    OBSERVATION = "OBSERVATION"
    FINAL_CANDIDATE = "FINAL_CANDIDATE"
    VALIDATION = "VALIDATION"
    REPLAN = "REPLAN"
    FINISH = "FINISH"


class StepStatus(StrEnum):
    STARTED = "STARTED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class AgentStepRecord(BaseModel):
    """De-identified fact for one semantic step in a bounded agent loop."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    step: int = Field(ge=1)
    phase: StepPhase
    status: StepStatus
    reason_code: str | None = Field(default=None, min_length=1)
    plan_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    observation_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    validation_issue_codes: list[str] = Field(default_factory=list, max_length=32)
    model_call_ref: str | None = Field(default=None, min_length=1)
    tool_call_ref: str | None = Field(default=None, min_length=1)
    started_at_epoch_ms: int = Field(ge=0)
    finished_at_epoch_ms: int = Field(ge=0)
    duration_ms: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_timing(self) -> "AgentStepRecord":
        if self.finished_at_epoch_ms < self.started_at_epoch_ms:
            raise ValueError("agent step finished time must not precede start time")
        if self.finished_at_epoch_ms - self.started_at_epoch_ms < self.duration_ms:
            raise ValueError("agent step duration exceeds its wall-clock interval")
        if len(set(self.validation_issue_codes)) != len(self.validation_issue_codes):
            raise ValueError("validation_issue_codes must not contain duplicates")
        return self


class AgentLoopResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate: dict[str, Any] | None = None
    steps: list[AgentStepRecord] = Field(default_factory=list)
    stop_reason: StopReason
    completed: bool = False

    @model_validator(mode="after")
    def validate_completion(self) -> "AgentLoopResult":
        if self.completed != (self.stop_reason == StopReason.COMPLETED):
            raise ValueError("completed must match the COMPLETED stop reason")
        if self.completed and self.candidate is None:
            raise ValueError("a completed loop requires a candidate")
        steps = [(item.step, index) for index, item in enumerate(self.steps)]
        if len({item[0] for item in steps}) != len(steps):
            raise ValueError("agent step numbers must be unique")
        return self


def stable_hash(value: Any) -> str:
    material = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()
