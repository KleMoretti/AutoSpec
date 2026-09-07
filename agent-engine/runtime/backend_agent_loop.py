from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from pydantic import ValidationError

from agents.backend_engineer import BackendEngineerAgent
from agents.base import ModelClient
from runtime.execution_context import current_model_execution_contract
from runtime.model_telemetry import (
    ModelCallBudgetExceeded,
    ModelInvocationTelemetry,
    captured_invocation_count,
    record_model_invocation,
)
from runtime.tool_harness import ToolRuntimeError, execute_current_tool
from review.backend_validator import BackendValidationIssue, validate_backend_candidate
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.agent_loop import (
    AgentLoopResult,
    AgentStepRecord,
    AgentTurn,
    FinalCandidateTurn,
    LoopPolicy,
    PlanTurn,
    ReplanTurn,
    StepPhase,
    StepStatus,
    StopReason,
    ToolCallTurn,
    parse_agent_turn,
    stable_hash,
)
from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact


class BackendAgentLoopError(RuntimeError):
    def __init__(self, message: str, error_code: str = "VALIDATION_FAILED") -> None:
        super().__init__(message)
        self.error_code = error_code


@dataclass
class _LoopState:
    steps: list[AgentStepRecord]
    next_step: int = 1
    iterations: int = 0
    replans: int = 0
    repeated_paths: int = 0
    last_path_hash: str | None = None
    plan: PlanTurn | None = None
    observation: dict[str, Any] | None = None
    candidate: dict[str, Any] | None = None
    issues: list[BackendValidationIssue] | None = None


async def run_backend_agent_loop(
    *,
    requirement: str,
    prd: PrdArtifact,
    architecture_design: dict[str, Any],
    retrieved_sources: list[dict[str, Any]],
    context_manifest: dict[str, Any],
    rework_directive: dict[str, Any] | None,
    model_client: ModelClient | None,
    policy: LoopPolicy,
) -> AgentLoopResult:
    """Run the Backend Engineer's bounded plan/tool/validate loop.

    The loop owns only decisions. Permission, quotas, idempotency and tool
    execution remain in the existing ToolHarness/Gateway context.
    """

    state = _LoopState(steps=[])
    base_payload: dict[str, Any] = {
        "requirement": requirement,
        "prd": prd.model_dump(mode="json"),
        "architecture_design": architecture_design,
        "retrieved_sources": retrieved_sources,
        "context_manifest": context_manifest,
    }
    if rework_directive is not None:
        base_payload["rework_directive"] = rework_directive

    while state.iterations < policy.max_steps:
        if _deadline_elapsed():
            return _result(state, StopReason.DEADLINE_EXCEEDED)
        state.iterations += 1

        phase = _next_phase(state, policy)
        try:
            turn, model_call_ref = await _next_turn(
                phase=phase,
                state=state,
                base_payload=base_payload,
                model_client=model_client,
                policy=policy,
                prd=prd,
                requirement=requirement,
                architecture_design=architecture_design,
                retrieved_sources=retrieved_sources,
                context_manifest=context_manifest,
                rework_directive=rework_directive,
            )
        except ModelCallBudgetExceeded:
            return _result(state, StopReason.MODEL_BUDGET_EXHAUSTED)
        except (ValidationError, ValueError) as error:
            _append_step(
                state,
                StepPhase.PLAN if phase == "PLAN" else StepPhase.REPLAN,
                StepStatus.FAILED,
                reason_code="TURN_SCHEMA_INVALID",
                model_call_ref=None,
            )
            return _result(state, StopReason.VALIDATION_FAILED)
        except TimeoutError:
            return _result(state, StopReason.DEADLINE_EXCEEDED)

        if isinstance(turn, PlanTurn):
            state.plan = turn
            plan_hash = stable_hash(turn.model_dump(mode="json"))
            _append_step(
                state,
                StepPhase.PLAN,
                StepStatus.SUCCEEDED,
                reason_code="PLAN_ACCEPTED",
                plan_hash=plan_hash,
                model_call_ref=model_call_ref,
            )
            continue

        if isinstance(turn, ToolCallTurn):
            tool_policy = _tool_policy()
            if not tool_policy.get("enabled", False):
                _append_step(
                    state,
                    StepPhase.TOOL_CALL,
                    StepStatus.FAILED,
                    reason_code="TOOL_NOT_ALLOWED",
                    plan_hash=_plan_hash(state),
                    model_call_ref=model_call_ref,
                )
                return _result(state, StopReason.VALIDATION_FAILED)
            allowed = {
                (item.get("name"), item.get("version"))
                for item in tool_policy.get("allowed_tools", [])
                if isinstance(item, dict)
            }
            if (turn.name, turn.version) not in allowed:
                _append_step(
                    state,
                    StepPhase.TOOL_CALL,
                    StepStatus.FAILED,
                    reason_code="TOOL_NOT_ALLOWED",
                    plan_hash=_plan_hash(state),
                    model_call_ref=model_call_ref,
                )
                return _result(state, StopReason.VALIDATION_FAILED)
            request = {
                "name": turn.name,
                "version": turn.version,
                "arguments": turn.arguments,
                "idempotency_key": _tool_idempotency_key(turn),
            }
            tool_ref = request["idempotency_key"]
            path_hash = stable_hash(
                {
                    "plan": _plan_hash(state),
                    "name": turn.name,
                    "version": turn.version,
                    "arguments": turn.arguments,
                }
            )
            if path_hash == state.last_path_hash:
                state.repeated_paths += 1
            else:
                state.last_path_hash = path_hash
                state.repeated_paths = 0
            if state.repeated_paths > policy.no_progress_limit:
                _append_step(
                    state,
                    StepPhase.TOOL_CALL,
                    StepStatus.FAILED,
                    reason_code="PATH_OSCILLATION",
                    plan_hash=_plan_hash(state),
                    model_call_ref=model_call_ref,
                    tool_call_ref=tool_ref,
                )
                return _result(state, StopReason.PATH_OSCILLATION)
            _append_step(
                state,
                StepPhase.TOOL_CALL,
                StepStatus.SUCCEEDED,
                reason_code="TOOL_REQUESTED",
                plan_hash=_plan_hash(state),
                model_call_ref=model_call_ref,
                tool_call_ref=tool_ref,
            )
            started = time.perf_counter()
            try:
                result = await execute_current_tool(request)
            except ToolRuntimeError as error:
                _append_step(
                    state,
                    StepPhase.OBSERVATION,
                    StepStatus.FAILED,
                    reason_code=error.error_code,
                    plan_hash=_plan_hash(state),
                    model_call_ref=model_call_ref,
                    tool_call_ref=tool_ref,
                    duration_ms=_elapsed(started),
                )
                if error.error_code in {"TOOL_RATE_LIMITED", "TOOL_TIMEOUT"}:
                    return _result(state, StopReason.TOOL_BUDGET_EXHAUSTED)
                return _result(state, StopReason.VALIDATION_FAILED)
            if result.status != "SUCCEEDED":
                _append_step(
                    state,
                    StepPhase.OBSERVATION,
                    StepStatus.FAILED,
                    reason_code=result.error_code or "TOOL_FAILED",
                    plan_hash=_plan_hash(state),
                    model_call_ref=model_call_ref,
                    tool_call_ref=tool_ref,
                    duration_ms=_elapsed(started),
                )
                return _result(state, StopReason.VALIDATION_FAILED)
            state.observation = _observation(result.result)
            _append_step(
                state,
                StepPhase.OBSERVATION,
                StepStatus.SUCCEEDED,
                reason_code="TOOL_OBSERVED",
                plan_hash=_plan_hash(state),
                observation_hash=stable_hash(state.observation),
                model_call_ref=model_call_ref,
                tool_call_ref=tool_ref,
                duration_ms=_elapsed(started),
            )
            continue

        if isinstance(turn, ReplanTurn):
            state.replans += 1
            state.issues = [
                BackendValidationIssue(
                    code=code,
                    path="$",
                    message=turn.reason,
                    required_change=change,
                )
                for code, change in zip(
                    turn.issue_codes,
                    turn.required_changes,
                    strict=False,
                )
            ]
            _append_step(
                state,
                StepPhase.REPLAN,
                StepStatus.SUCCEEDED,
                reason_code="REPLAN_ACCEPTED",
                plan_hash=_plan_hash(state),
                observation_hash=_observation_hash(state),
                validation_issue_codes=turn.issue_codes,
                model_call_ref=model_call_ref,
            )
            if state.replans > policy.max_replans:
                return _result(state, StopReason.REPLAN_LIMIT)
            state.candidate = None
            continue

        if isinstance(turn, FinalCandidateTurn):
            state.candidate = turn.candidate
            _append_step(
                state,
                StepPhase.FINAL_CANDIDATE,
                StepStatus.SUCCEEDED,
                reason_code="CANDIDATE_READY",
                plan_hash=_plan_hash(state),
                observation_hash=_observation_hash(state),
                model_call_ref=model_call_ref,
            )
            validation = validate_backend_candidate(
                state.candidate,
                prd,
                profile=policy.validator_profile,
            )
            state.issues = validation.issues
            codes = [issue.code for issue in validation.issues]
            _append_step(
                state,
                StepPhase.VALIDATION,
                StepStatus.SUCCEEDED if validation.valid else StepStatus.FAILED,
                reason_code="VALIDATION_PASSED" if validation.valid else "VALIDATION_FAILED",
                plan_hash=_plan_hash(state),
                observation_hash=_observation_hash(state),
                validation_issue_codes=codes,
                model_call_ref=model_call_ref,
            )
            if validation.valid and validation.candidate is not None:
                _append_step(
                    state,
                    StepPhase.FINISH,
                    StepStatus.SUCCEEDED,
                    reason_code=StopReason.COMPLETED.value,
                    plan_hash=_plan_hash(state),
                    observation_hash=_observation_hash(state),
                    model_call_ref=model_call_ref,
                )
                return _result(
                    state,
                    StopReason.COMPLETED,
                    candidate=validation.candidate.model_dump(mode="json"),
                )
            if state.replans >= policy.max_replans:
                return _result(state, StopReason.REPLAN_LIMIT)
            continue

    return _result(state, StopReason.STEP_LIMIT)


async def _next_turn(
    *,
    phase: str,
    state: _LoopState,
    base_payload: dict[str, Any],
    model_client: ModelClient | None,
    policy: LoopPolicy,
    prd: PrdArtifact,
    requirement: str,
    architecture_design: dict[str, Any],
    retrieved_sources: list[dict[str, Any]],
    context_manifest: dict[str, Any],
    rework_directive: dict[str, Any] | None,
) -> tuple[AgentTurn, str | None]:
    if model_client is None:
        return (
            _fixture_turn(
                phase,
                state,
                prd,
                requirement,
                architecture_design,
                retrieved_sources,
                context_manifest,
                rework_directive,
                policy,
            ),
            _record_fixture_model_call(phase, state),
        )
    payload = dict(base_payload)
    payload["agent_loop"] = {
        "phase": phase,
        "strategy": policy.strategy.value,
        "plan": state.plan.model_dump(mode="json") if state.plan else None,
        "observation": state.observation,
        "validation_issues": [
            issue.model_dump(mode="json") for issue in (state.issues or [])
        ],
        "turn_schema": "AgentTurn@v1",
    }
    output = await asyncio.to_thread(
        model_client.generate_json,
        "BackendEngineerAgent_v1",
        payload,
    )
    turn = parse_agent_turn(output)
    return turn, _model_call_ref()


def _fixture_turn(
    phase: str,
    state: _LoopState,
    prd: PrdArtifact,
    requirement: str,
    architecture_design: dict[str, Any],
    retrieved_sources: list[dict[str, Any]],
    context_manifest: dict[str, Any],
    rework_directive: dict[str, Any] | None,
    policy: LoopPolicy,
) -> AgentTurn:
    if phase == "PLAN":
        return PlanTurn(
            goal="Produce a traceable backend design.",
            steps=["inspect the frozen contract", "draft and validate the backend artifact"],
            completion_conditions=["all MUST requirements have API or data evidence"],
        )
    if phase == "ACTION" and not state.observation and _tool_policy().get("enabled", False):
        return ToolCallTurn(
            name="contract.lookup",
            version="v1",
            arguments={"node_id": "backend_engineer"},
            reason="Verify the frozen Backend Engineer output contract before drafting.",
            expected_evidence=["output_schema", "agent_loop_policy"],
        )
    if phase == "REPLAN":
        return ReplanTurn(
            issue_codes=[issue.code for issue in (state.issues or [])] or ["VALIDATION_FAILED"],
            required_changes=[
                issue.required_change for issue in (state.issues or [])
            ] or ["Repair the backend candidate."],
            reason="Deterministic validation identified a blocking backend issue.",
        )
    candidate = BackendEngineerAgent().run(
        requirement,
        prd,
        architecture_design=ArchitectureDesignArtifact.model_validate(architecture_design),
        retrieved_sources=retrieved_sources,
        context_manifest=context_manifest,
        rework_directive=rework_directive,
    )
    return FinalCandidateTurn(
        candidate=candidate.model_dump(mode="json"),
        reason="Deterministic fixture candidate.",
    )


def _result(
    state: _LoopState,
    reason: StopReason,
    *,
    candidate: dict[str, Any] | None = None,
) -> AgentLoopResult:
    return AgentLoopResult(
        candidate=candidate if candidate is not None else state.candidate,
        steps=list(state.steps),
        stop_reason=reason,
        completed=reason == StopReason.COMPLETED,
    )


def _append_step(
    state: _LoopState,
    phase: StepPhase,
    status: StepStatus,
    *,
    reason_code: str,
    plan_hash: str | None = None,
    observation_hash: str | None = None,
    validation_issue_codes: list[str] | None = None,
    model_call_ref: str | None = None,
    tool_call_ref: str | None = None,
    duration_ms: int = 0,
) -> None:
    now = int(time.time() * 1000)
    state.steps.append(
        AgentStepRecord(
            step=state.next_step,
            phase=phase,
            status=status,
            reason_code=reason_code,
            plan_hash=plan_hash,
            observation_hash=observation_hash,
            validation_issue_codes=validation_issue_codes or [],
            model_call_ref=model_call_ref,
            tool_call_ref=tool_call_ref,
            started_at_epoch_ms=max(0, now - duration_ms),
            finished_at_epoch_ms=now,
            duration_ms=max(0, duration_ms),
        )
    )
    state.next_step += 1


def _next_phase(state: _LoopState, policy: LoopPolicy) -> str:
    if state.plan is None:
        return "PLAN"
    if state.candidate is not None and state.issues:
        return "REPLAN"
    if policy.enabled and _tool_policy().get("enabled", False) and not state.observation:
        return "ACTION"
    return "FINAL"


def _plan_hash(state: _LoopState) -> str | None:
    return stable_hash(state.plan.model_dump(mode="json")) if state.plan else None


def _observation_hash(state: _LoopState) -> str | None:
    return stable_hash(state.observation) if state.observation is not None else None


def _observation(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    return {"value": value}


def _tool_policy() -> dict[str, Any]:
    contract = current_model_execution_contract()
    return dict(contract.tool_policy) if contract is not None else {}


def _tool_idempotency_key(turn: ToolCallTurn) -> str:
    contract = current_model_execution_contract()
    execution = contract.execution_id if contract is not None else "standalone"
    return f"{execution}:backend-engineer:{turn.name}:{stable_hash(turn.arguments)}"


def _model_call_ref() -> str:
    contract = current_model_execution_contract()
    execution = contract.execution_id if contract is not None else "standalone"
    count = captured_invocation_count()
    return f"{execution}:model:{count}" if count else f"{execution}:model:pending"


def _record_fixture_model_call(phase: str, state: _LoopState) -> str:
    contract = current_model_execution_contract()
    execution = contract.execution_id if contract is not None else "standalone"
    output_hash = stable_hash({"phase": phase, "iteration": state.iterations})
    record_model_invocation(
        ModelInvocationTelemetry(
            call_id=f"{execution}:fixture:{state.next_step}",
            provider_key="local",
            model_name="deterministic-agent-loop-fixture",
            prompt_key="backend_engineer",
            result_hash=output_hash,
            status="SUCCEEDED",
        )
    )
    return _model_call_ref()


def _deadline_elapsed() -> bool:
    contract = current_model_execution_contract()
    return contract is not None and contract.deadline_epoch_ms > 0 and (
        contract.deadline_epoch_ms <= round(time.time() * 1000)
    )


def _elapsed(started: float) -> int:
    return max(0, round((time.perf_counter() - started) * 1000))
