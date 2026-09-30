from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4

from pydantic import ValidationError

from agents.backend_engineer import BackendEngineerAgent
from agents.base import ModelClient
from runtime.execution_context import current_model_execution_contract
from runtime.model_telemetry import (
    ModelCallBudgetExceeded,
    ModelInvocationTelemetry,
    last_model_call_id,
    record_model_invocation,
)
from runtime.tool_harness import ToolRuntimeError, execute_current_tool, current_tool_harness
from review.backend_validator import BackendValidationIssue, validate_backend_candidate
from schemas.architecture_design import ArchitectureDesignArtifact, ArchitectureDesignArtifactV2
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
    agent_turn_schema,
    validate_loop_budget,
)
from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact
from schemas.tool import ToolCallRequest
from schemas.verification import VerificationFact, VerificationReport
from spec_verifier.compiler import compile_spec
from spec_verifier.fixtures import spec_contract_from_artifacts


class BackendAgentLoopError(RuntimeError):
    def __init__(self, message: str, error_code: str = "VALIDATION_FAILED") -> None:
        super().__init__(message)
        self.error_code = error_code


class BackendVerificationError(RuntimeError):
    def __init__(self, message: str, error_code: str = "SPEC_VERIFY_FAILED") -> None:
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
    observations: list[dict[str, Any]] = field(default_factory=list)
    candidate: dict[str, Any] | None = None
    rejected_candidate: dict[str, Any] | None = None
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
    contract = current_model_execution_contract()
    if contract is not None:
        validate_loop_budget(policy, int(contract.model_policy.get("max_calls", 1)),
                             bool(contract.tool_policy.get("enabled", False)))
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

        if policy.version == "agent-loop-v2" and turn.turn_type not in _allowed_turns(phase):
            _append_step(state, StepPhase.VALIDATION, StepStatus.FAILED,
                         reason_code="TURN_PHASE_INVALID", model_call_ref=model_call_ref)
            return _result(state, StopReason.VALIDATION_FAILED)

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
                if policy.version == "agent-loop-v2" and error.error_code == "TOOL_INPUT_INVALID":
                    state.observation = {"status": "FAILED", "error_code": error.error_code,
                                         "required_change": "Correct arguments using the tool input_schema."}
                    continue
                if error.error_code == "TOOL_RATE_LIMITED":
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
            state.observations.append({"tool": turn.name, "version": turn.version,
                                       "arguments": turn.arguments, "result": state.observation})
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
            if policy.version == "agent-loop-v2":
                actual_codes = {issue.code for issue in (state.issues or [])}
                if set(turn.issue_codes) != actual_codes:
                    _append_step(state, StepPhase.REPLAN, StepStatus.FAILED,
                                 reason_code="REPLAN_ISSUES_CHANGED", model_call_ref=model_call_ref)
                    return _result(state, StopReason.VALIDATION_FAILED)
            else:
                state.issues = [
                    BackendValidationIssue(code=code, path="$", message=turn.reason, required_change=change)
                    for code, change in zip(turn.issue_codes, turn.required_changes, strict=False)
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
            state.rejected_candidate = state.candidate
            state.candidate = None
            continue

        if isinstance(turn, FinalCandidateTurn):
            state.candidate = turn.candidate
            candidate_hash = stable_hash(turn.candidate)
            _append_step(
                state,
                StepPhase.FINAL_CANDIDATE,
                StepStatus.SUCCEEDED,
                reason_code="CANDIDATE_READY",
                plan_hash=_plan_hash(state),
                observation_hash=_observation_hash(state),
                candidate_hash=candidate_hash,
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
                candidate_hash=candidate_hash,
                validation_issue_codes=codes,
                model_call_ref=model_call_ref,
            )
            if validation.valid and validation.candidate is not None:
                verification_fact_ref: str | None = None
                if _verification_required():
                    started = time.perf_counter()
                    try:
                        report, fact = await _verify_backend_candidate(
                            validation.candidate,
                            prd,
                        )
                    except ToolRuntimeError as error:
                        _append_step(
                            state,
                            StepPhase.OBSERVATION,
                            StepStatus.FAILED,
                            reason_code=error.error_code,
                            plan_hash=_plan_hash(state),
                            observation_hash=_observation_hash(state),
                            candidate_hash=candidate_hash,
                            model_call_ref=model_call_ref,
                            duration_ms=_elapsed(started),
                        )
                        return _result(
                            state,
                            _verification_stop_reason(error.error_code),
                        )
                    except (BackendVerificationError, ValidationError, ValueError) as error:
                        error_code = getattr(error, "error_code", "SPEC_VERIFY_INVALID")
                        _append_step(
                            state,
                            StepPhase.OBSERVATION,
                            StepStatus.FAILED,
                            reason_code=error_code,
                            plan_hash=_plan_hash(state),
                            observation_hash=_observation_hash(state),
                            candidate_hash=candidate_hash,
                            model_call_ref=model_call_ref,
                            duration_ms=_elapsed(started),
                        )
                        return _result(
                            state,
                            _verification_stop_reason(error_code)
                            if isinstance(error, BackendVerificationError)
                            else StopReason.VERIFICATION_FAILED,
                        )

                    verification_fact_ref = fact.report_hash
                    state.observation = _verification_observation(report, fact)
                    state.observations.append(
                        {
                            "tool": "spec.verify",
                            "version": "v1",
                            "scope": report.scope,
                            "candidate_hash": candidate_hash,
                            "result": state.observation,
                        }
                    )
                    verification_codes = [issue.code for issue in report.issues]
                    if report.status == "FAILED" and report.gate_status == "BLOCKED":
                        state.issues = _verification_issues(report)
                        _append_step(
                            state,
                            StepPhase.OBSERVATION,
                            StepStatus.FAILED,
                            reason_code="SPEC_VERIFY_FAILED",
                            plan_hash=_plan_hash(state),
                            observation_hash=stable_hash(state.observation),
                            candidate_hash=candidate_hash,
                            verification_fact_ref=verification_fact_ref,
                            validation_issue_codes=verification_codes,
                            model_call_ref=model_call_ref,
                            duration_ms=_elapsed(started),
                        )
                        if state.replans >= policy.max_replans:
                            return _result(state, StopReason.REPLAN_LIMIT)
                        continue
                    if report.status != "PASSED" or report.gate_status != "PASSED":
                        _append_step(
                            state,
                            StepPhase.OBSERVATION,
                            StepStatus.FAILED,
                            reason_code="SPEC_VERIFY_NOT_PASSED",
                            plan_hash=_plan_hash(state),
                            observation_hash=stable_hash(state.observation),
                            candidate_hash=candidate_hash,
                            verification_fact_ref=verification_fact_ref,
                            validation_issue_codes=verification_codes,
                            model_call_ref=model_call_ref,
                            duration_ms=_elapsed(started),
                        )
                        return _result(state, StopReason.VERIFICATION_FAILED)
                    _append_step(
                        state,
                        StepPhase.OBSERVATION,
                        StepStatus.SUCCEEDED,
                        reason_code="SPEC_VERIFY_PASSED",
                        plan_hash=_plan_hash(state),
                        observation_hash=stable_hash(state.observation),
                        candidate_hash=candidate_hash,
                        verification_fact_ref=verification_fact_ref,
                        model_call_ref=model_call_ref,
                        duration_ms=_elapsed(started),
                    )
                _append_step(
                    state,
                    StepPhase.FINISH,
                    StepStatus.SUCCEEDED,
                    reason_code=StopReason.COMPLETED.value,
                    plan_hash=_plan_hash(state),
                    observation_hash=_observation_hash(state),
                    candidate_hash=candidate_hash,
                    verification_fact_ref=verification_fact_ref,
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
    payload = dict(base_payload)
    payload["agent_loop"] = {
        "phase": phase,
        "strategy": policy.strategy.value,
        "plan": state.plan.model_dump(mode="json") if state.plan else None,
        "observation": state.observation,
        "observations": state.observations,
        "candidate_to_repair": state.candidate or state.rejected_candidate,
        "validation_issues": [
            issue.model_dump(mode="json") for issue in (state.issues or [])
        ],
        "turn_schema": agent_turn_schema(),
        "candidate_schema": BackendDesignArtifact.model_json_schema(),
        "allowed_turn_types": _allowed_turns(phase),
        "tools": current_tool_harness().describe_allowed() if current_tool_harness() else [],
        "remaining_steps": policy.max_steps - state.iterations,
    }
    if model_client is None:
        turn = _fixture_turn(phase, state, prd, requirement, architecture_design,
                             retrieved_sources, context_manifest, rework_directive, policy)
        return turn, _record_fixture_model_call(payload, turn)
    output = await asyncio.to_thread(
        model_client.generate_json,
        "BackendEngineerAgent_v2" if policy.version == "agent-loop-v2" else "BackendEngineerAgent_v1",
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
        architecture_design=(ArchitectureDesignArtifactV2 if "shared_contract" in architecture_design else ArchitectureDesignArtifact).model_validate(architecture_design),
        shared_contract_required="shared_contract" in architecture_design,
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
    candidate_hash: str | None = None,
    verification_fact_ref: str | None = None,
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
            candidate_hash=candidate_hash,
            verification_fact_ref=verification_fact_ref,
            validation_issue_codes=list(dict.fromkeys(validation_issue_codes or [])),
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
    if (
        policy.enabled
        and not _verification_required()
        and _tool_policy().get("enabled", False)
        and (
            not state.observation
            or state.observation.get("error_code") == "TOOL_INPUT_INVALID"
        )
    ):
        return "ACTION"
    return "FINAL"


def _allowed_turns(phase: str) -> list[str]:
    return {"PLAN": ["PLAN"], "ACTION": ["TOOL_CALL", "FINAL_CANDIDATE"],
            "REPLAN": ["REPLAN"], "FINAL": ["FINAL_CANDIDATE", "TOOL_CALL"]}[phase]


def _plan_hash(state: _LoopState) -> str | None:
    return stable_hash(state.plan.model_dump(mode="json")) if state.plan else None


def _observation_hash(state: _LoopState) -> str | None:
    return stable_hash(state.observation) if state.observation is not None else None


def _observation(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    return {"value": value}


def _verification_policy() -> dict[str, Any]:
    contract = current_model_execution_contract()
    return dict(contract.verification_policy) if contract is not None else {}


def _verification_required() -> bool:
    policy = _verification_policy()
    return bool(policy.get("enabled")) and policy.get("required_level") in {"L1", "L2"}


async def _verify_backend_candidate(
    candidate: BackendDesignArtifact,
    prd: PrdArtifact,
) -> tuple[VerificationReport, VerificationFact]:
    execution = current_model_execution_contract()
    if execution is None or not _verification_required():
        raise BackendVerificationError(
            "backend verification requires a frozen execution contract",
            "SPEC_VERIFY_POLICY_MISSING",
        )
    policy = _verification_policy()
    if policy.get("scope") != "BACKEND":
        raise BackendVerificationError(
            "backend verification must use BACKEND scope",
            "SPEC_VERIFY_SCOPE_INVALID",
        )
    if _deadline_elapsed():
        raise BackendVerificationError(
            "backend verification deadline elapsed",
            "TOOL_DEADLINE_EXCEEDED",
        )

    contract = spec_contract_from_artifacts(
        prd,
        candidate,
        None,
        contract_id="GeneratedSpec",
    )
    compiled = compile_spec(contract)
    result = await execute_current_tool(
        ToolCallRequest(
            name="spec.verify",
            version="v1",
            arguments={
                "contract": contract.model_dump(mode="json"),
                "scope": "BACKEND",
                "required_level": policy["required_level"],
                "rule_profile": policy.get("rule_profile", "spec-backend-v1"),
                "source_digest": compiled.source_digest,
                "timeout_ms": policy.get("timeout_ms", 30_000),
            },
        )
    )
    if result.status != "SUCCEEDED" or not isinstance(result.result, dict):
        raise BackendVerificationError(
            result.error_message or "spec.verify returned a failed tool result",
            result.error_code or "SPEC_VERIFY_FAILED",
        )
    payload = result.result.get("result", result.result)
    if not isinstance(payload, dict):
        raise BackendVerificationError(
            "spec.verify returned an invalid result",
            "SPEC_VERIFY_PROTOCOL_ERROR",
        )
    report_payload = {
        key: value for key, value in payload.items() if key != "verification_fact"
    }
    report = VerificationReport.model_validate(report_payload)
    fact_payload = payload.get("verification_fact")
    if fact_payload is None:
        raise BackendVerificationError(
            "spec.verify did not return a trusted verification fact",
            "SPEC_VERIFY_FACT_MISSING",
        )
    fact = VerificationFact.model_validate(fact_payload)
    expected_policy_hash = policy.get("policy_hash") or stable_hash(
        {key: value for key, value in policy.items() if key != "policy_hash"}
    )
    if report.execution_id != execution.execution_id:
        raise BackendVerificationError(
            "verification report execution does not match the current execution",
            "SPEC_VERIFY_EXECUTION_MISMATCH",
        )
    if report.scope != "BACKEND" or fact.scope != "BACKEND":
        raise BackendVerificationError(
            "verification evidence scope does not match BACKEND",
            "SPEC_VERIFY_SCOPE_MISMATCH",
        )
    if report.source_digest != compiled.source_digest or fact.source_digest != report.source_digest:
        raise BackendVerificationError(
            "verification evidence source digest does not match the candidate",
            "SPEC_VERIFY_SOURCE_MISMATCH",
        )
    if fact.execution_id != execution.execution_id or fact.policy_hash != expected_policy_hash:
        raise BackendVerificationError(
            "verification fact is not bound to the frozen execution policy",
            "SPEC_VERIFY_FACT_MISMATCH",
        )
    if fact.report_hash != stable_hash(report.model_dump(mode="json")):
        raise BackendVerificationError(
            "verification fact report hash does not match the report",
            "SPEC_VERIFY_REPORT_HASH_MISMATCH",
        )
    if fact.expires_at_epoch_ms <= int(time.time() * 1000):
        raise BackendVerificationError(
            "verification fact has expired",
            "SPEC_VERIFY_FACT_EXPIRED",
        )
    return report, fact


def _verification_issues(report: VerificationReport) -> list[BackendValidationIssue]:
    issues = [
        BackendValidationIssue(
            code=issue.code,
            path=issue.path,
            message=issue.message,
            required_change=(
                f"Repair verifier issue {issue.code} at {issue.path}: {issue.message}"
            ),
        )
        for issue in report.issues
    ]
    if issues:
        return issues
    return [
        BackendValidationIssue(
            code="SPEC_VERIFY_FAILED",
            path="$",
            message="The backend candidate did not pass the frozen verifier.",
            required_change="Repair the backend candidate and rerun spec.verify.",
        )
    ]


def _verification_observation(
    report: VerificationReport,
    fact: VerificationFact,
) -> dict[str, Any]:
    return {
        "status": report.status,
        "gate_status": report.gate_status,
        "scope": report.scope,
        "level": report.level,
        "source_digest": report.source_digest,
        "fact_ref": fact.report_hash,
        "fact_status": fact.status,
        "issues": [
            {"code": issue.code, "path": issue.path, "message": issue.message}
            for issue in report.issues
        ],
    }


def _verification_stop_reason(error_code: str) -> StopReason:
    if error_code in {"TOOL_RATE_LIMITED", "TOOL_BUDGET_EXHAUSTED"}:
        return StopReason.TOOL_BUDGET_EXHAUSTED
    if error_code in {"TOOL_TIMEOUT", "TOOL_DEADLINE_EXCEEDED", "SPEC_VERIFY_DEADLINE_EXCEEDED"}:
        return StopReason.DEADLINE_EXCEEDED
    return StopReason.VERIFICATION_FAILED


def _tool_policy() -> dict[str, Any]:
    contract = current_model_execution_contract()
    return dict(contract.tool_policy) if contract is not None else {}


def _tool_idempotency_key(turn: ToolCallTurn) -> str:
    contract = current_model_execution_contract()
    execution = contract.execution_id if contract is not None else "standalone"
    return f"{execution}:backend-engineer:{turn.name}:{stable_hash(turn.arguments)}"


def _model_call_ref() -> str | None:
    return last_model_call_id()


def _record_fixture_model_call(payload: dict[str, Any], turn: AgentTurn) -> str | None:
    contract = current_model_execution_contract()
    record_model_invocation(
        ModelInvocationTelemetry(
            provider_key="local",
            model_name="deterministic-agent-loop-fixture",
            prompt_key=contract.prompt_key if contract is not None else "backend_engineer",
            normalized_params_hash=stable_hash(payload),
            result_hash=stable_hash(turn.model_dump(mode="json")),
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
