from __future__ import annotations

import time
import math
import re
from collections.abc import Awaitable, Callable, Sequence
from typing import Any
from uuid import uuid4

from evaluation.autospec_case_catalog import list_autospec_cases
from evaluation.metrics import aggregate_case_metrics
from schemas.evaluation import (
    AutoSpecAblationMatrix,
    AutoSpecEvalCase,
    AutoSpecEvalRun,
    AutoSpecGateDecision,
    AutoSpecMetric,
)


GROUPS: tuple[tuple[str, str], ...] = (
    ("A", "single-shot"),
    ("B", "loop-no-tools"),
    ("C", "loop-with-tools"),
    ("D", "loop-tools-replan"),
)

LiveAblationRunner = Callable[
    [str, Sequence[AutoSpecEvalCase], dict[str, Any]],
    Awaitable[AutoSpecEvalRun],
]


def ablation_configs() -> list[dict[str, Any]]:
    """Return the frozen A/B/C/D knobs without claiming they were executed."""

    return [
        {
            "group": "A",
            "name": "single-shot",
            "agent_loop_enabled": False,
            "tool_enabled": False,
            "replan_enabled": False,
        },
        {
            "group": "B",
            "name": "loop-no-tools",
            "agent_loop_enabled": True,
            "tool_enabled": False,
            "replan_enabled": True,
        },
        {
            "group": "C",
            "name": "loop-with-tools",
            "agent_loop_enabled": True,
            "tool_enabled": True,
            "replan_enabled": False,
        },
        {
            "group": "D",
            "name": "loop-tools-replan",
            "agent_loop_enabled": True,
            "tool_enabled": True,
            "replan_enabled": True,
        },
    ]


async def run_ablation_matrix(
    cases: Sequence[AutoSpecEvalCase] | None = None,
    *,
    live_runner: LiveAblationRunner | None = None,
    matrix_id: str | None = None,
    dataset_version: str = "autospec-v5-agent-execution-eval-v1",
    workflow_version: str = "v5-agent-execution",
    code_version: str = "workspace-v5",
    random_seed: int | None = None,
) -> AutoSpecAblationMatrix:
    """Run a supplied control-plane adapter or emit an explicit unexecuted matrix.

    The default is intentionally non-fabricating: it never maps deterministic
    fixture output onto live tool, cost, retrieval, or recovery metrics. A
    deployment-specific adapter must collect the four groups from the formal
    workflow API and return validated ``AutoSpecEvalRun`` records.
    """

    selected = list(list_autospec_cases() if cases is None else cases)
    if not selected:
        raise ValueError("AutoSpec ablation matrix requires at least one case")
    if len({case.case_id for case in selected}) != len(selected):
        raise ValueError("duplicate case IDs in ablation matrix")
    if len({case.dataset_version for case in selected}) != 1:
        raise ValueError("mixed datasets in ablation matrix")
    resolved_dataset = selected[0].dataset_version if cases else dataset_version
    runs: list[AutoSpecEvalRun] = []
    for config in ablation_configs():
        if live_runner is not None:
            run = await live_runner(config["group"], selected, config)
            if run.group != config["group"] or run.group_name != config["name"]:
                raise ValueError("live ablation runner returned a mismatched group")
            runs.append(run)
            continue
        runs.append(
            _not_executed_run(
                config,
                dataset_version=resolved_dataset,
                workflow_version=workflow_version,
                code_version=code_version,
                random_seed=random_seed,
            )
        )
    return AutoSpecAblationMatrix(
        matrix_id=matrix_id or f"ablation-{uuid4().hex}",
        dataset_version=resolved_dataset,
        generated_at_epoch_ms=int(time.time() * 1000),
        runs=runs,
    )


def evaluate_release_gate(
    baseline: AutoSpecEvalRun,
    candidate: AutoSpecEvalRun,
    *,
    max_p95_ratio: float = 1.25,
    max_token_ratio: float = 1.25,
    max_cost_ratio: float = 1.25,
    min_cases: int = 8,
    min_repetitions: int = 3,
    zero_baseline_limits: dict[str, float] | None = None,
) -> AutoSpecGateDecision:
    """Apply the predeclared deterministic D-vs-A promotion gate."""

    if baseline.group != "A" or candidate.group != "D":
        return AutoSpecGateDecision(
            decision="NOT_EVALUATED",
            gate_status="NOT_EVALUATED",
            reasons=["release gate requires group A baseline and group D candidate"],
            baseline_run_id=baseline.run_id,
            candidate_run_id=candidate.run_id,
        )
    def incomplete(reason: str) -> AutoSpecGateDecision:
        return AutoSpecGateDecision(decision="NOT_EVALUATED", gate_status="NOT_EVALUATED",
                                   reasons=[reason], baseline_run_id=baseline.run_id,
                                   candidate_run_id=candidate.run_id)

    if min_cases < 1 or min_repetitions < 1 or any(
        not math.isfinite(v) or v < 1 for v in (max_p95_ratio, max_token_ratio, max_cost_ratio)
    ):
        raise ValueError("invalid release gate thresholds")
    zero_limits = zero_baseline_limits or {}
    if any(not math.isfinite(v) or v < 0 for v in zero_limits.values()):
        raise ValueError("invalid absolute budget")
    comparable = ("dataset_version", "dataset_hash", "dataset_split", "environment_hash",
                  "code_version", "model_version", "retriever_version", "budget_version",
                  "pricing_snapshot")
    if any(not getattr(baseline, key) or getattr(baseline, key) != getattr(candidate, key) for key in comparable):
        return incomplete("A/D dataset, environment, model, retrieval, budget and pricing must be comparable")
    if baseline.dataset_split != "holdout":
        return incomplete("promotion requires the frozen holdout split")
    required = (
        "gate_pass_rate",
        "blocking_issue_median",
        "must_trace_coverage",
        "tool_argument_valid_rate",
        "unauthorized_execution_count",
        "tool_call_count",
        "schema_invalid_count",
        "p95_latency_ms",
        "tokens_per_run",
        "cost_per_run",
    )
    for run in (baseline, candidate):
        reason = _validate_evidence(run, min_cases, min_repetitions)
        if reason:
            return incomplete(reason)
    if {(c.case_id, c.repetition) for c in baseline.case_results} != {
        (c.case_id, c.repetition) for c in candidate.case_results
    }:
        return incomplete("A/D case/repetition pairs differ")
    if {c.workflow_run_id for c in baseline.case_results} & {c.workflow_run_id for c in candidate.case_results}:
        return incomplete("A/D reuse the same workflow execution")
    baseline_values = _measured_metrics(baseline, required)
    candidate_values = _measured_metrics(candidate, required)
    if baseline_values is None or candidate_values is None:
        return AutoSpecGateDecision(
            decision="NOT_EVALUATED",
            gate_status="NOT_EVALUATED",
            reasons=["required A/D metrics are not all measured"],
            baseline_run_id=baseline.run_id,
            candidate_run_id=candidate.run_id,
        )
    for run, observed in ((baseline, baseline_values), (candidate, candidate_values)):
        computed = {m.name: m.value for m in aggregate_case_metrics(run.case_results) if m.status == "MEASURED"}
        if any(name not in computed or not math.isclose(observed[name], computed[name], rel_tol=1e-8, abs_tol=1e-8)
               for name in required):
            return incomplete("aggregate metrics do not match the measured case facts")

    reasons: list[str] = []
    quality_improved = (
        candidate_values["gate_pass_rate"]
        >= baseline_values["gate_pass_rate"] + 0.08
        or (baseline_values["blocking_issue_median"] > 0
            and candidate_values["blocking_issue_median"] <= baseline_values["blocking_issue_median"] * 0.8)
    )
    if not quality_improved:
        reasons.append("D does not meet the predeclared quality improvement threshold")
    if candidate_values["gate_pass_rate"] < baseline_values["gate_pass_rate"]:
        reasons.append("D lowers gate pass rate")
    if candidate_values["must_trace_coverage"] < baseline_values["must_trace_coverage"]:
        reasons.append("D lowers MUST trace coverage")
    if candidate_values["tool_argument_valid_rate"] < 0.95:
        reasons.append("D tool argument validity is below 95%")
    if candidate_values["tool_call_count"] == 0:
        reasons.append("D has no measured tool execution evidence")
    if candidate_values["unauthorized_execution_count"] != 0:
        reasons.append("D contains unauthorized tool executions")
    if candidate_values["schema_invalid_count"] != 0:
        reasons.append("D contains schema-invalid outputs")
    for metric, limit in (
        ("p95_latency_ms", max_p95_ratio),
        ("tokens_per_run", max_token_ratio),
        ("cost_per_run", max_cost_ratio),
    ):
        baseline_value = baseline_values[metric]
        if baseline_value == 0:
            if metric not in zero_limits:
                return incomplete(f"zero baseline for {metric} requires a predeclared absolute budget")
            if candidate_values[metric] > zero_limits[metric]:
                reasons.append(f"D exceeds the {metric} absolute budget")
        if baseline_value > 0 and candidate_values[metric] > baseline_value * limit:
            reasons.append(f"D exceeds the {metric} budget ratio")
    if reasons:
        return AutoSpecGateDecision(
            decision="REVISE",
            gate_status="BLOCKED",
            reasons=reasons,
            baseline_run_id=baseline.run_id,
            candidate_run_id=candidate.run_id,
        )
    return AutoSpecGateDecision(
        decision="PROMOTE",
        gate_status="PASSED",
        reasons=["D satisfies the deterministic quality, safety, and budget gate"],
        baseline_run_id=baseline.run_id,
        candidate_run_id=candidate.run_id,
    )


def _measured_metrics(
    run: AutoSpecEvalRun,
    required: tuple[str, ...],
) -> dict[str, float] | None:
    if len({metric.name for metric in run.metrics}) != len(run.metrics):
        return None
    values = {
        metric.name: metric.value
        for metric in run.metrics
        if metric.status == "MEASURED" and metric.value is not None
    }
    if any(name not in values for name in required) or any(not math.isfinite(v) or v < 0 for v in values.values()):
        return None
    return {name: float(value) for name, value in values.items()}


def _validate_evidence(run: AutoSpecEvalRun, min_cases: int, min_repetitions: int) -> str | None:
    if run.execution_mode != "LIVE_CONTROL_PLANE" or run.status != "SUCCEEDED":
        return "only completed live collection runs are eligible for promotion"
    if not run.case_results or not run.prompt_schema_versions or not run.bundle_hash:
        return "missing case or frozen execution evidence"
    pricing = run.pricing_snapshot
    if not all(pricing.get(key) for key in ("source", "observed_at", "currency", "version")):
        return "pricing snapshot is not traceable"
    models = pricing.get("models")
    if not isinstance(models, dict) or not models:
        return "per-model pricing is unavailable"
    for rates in models.values():
        for key in ("input_per_million", "cached_input_per_million", "output_per_million"):
            value = rates.get(key) if isinstance(rates, dict) else None
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                return "pricing is unavailable or invalid"
    pairs: set[tuple[str, int]] = set()
    executions: set[str] = set()
    repetitions: dict[str, set[int]] = {}
    for case in run.case_results:
        pair = (case.case_id, case.repetition)
        if pair in pairs or case.workflow_run_id in executions:
            return "duplicate case or execution evidence"
        pairs.add(pair)
        executions.add(case.workflow_run_id)
        repetitions.setdefault(case.case_id, set()).add(case.repetition)
        if not case.rubric_ref:
            return "case lacks independent rubric evidence; generated self-evaluation is diagnostic only"
        if case.status == "NOT_EXECUTED" or not case.workflow_run_id or not case.trace_id or not re.fullmatch(r"[0-9a-f]{64}", case.bundle_hash or ""):
            return "case lacks terminal execution, trace or bundle evidence"
        if case.gate_pass and (case.status != "SUCCEEDED" or case.blocking_issue_count != 0
                              or case.must_trace_coverage != 1 or case.schema_invalid_count != 0
                              or case.unauthorized_tool_executions != 0):
            return "case success contradicts its hard quality facts"
    if len(repetitions) < min_cases or any(len(v) < min_repetitions for v in repetitions.values()):
        return "insufficient distinct cases or repetitions for the declared release gate"
    return None


def _not_executed_run(
    config: dict[str, Any],
    *,
    dataset_version: str,
    workflow_version: str,
    code_version: str,
    random_seed: int | None,
) -> AutoSpecEvalRun:
    return AutoSpecEvalRun(
        run_id=f"planned-{config['group'].lower()}-{uuid4().hex}",
        dataset_version=dataset_version,
        group=config["group"],
        group_name=config["name"],
        execution_mode="LIVE_CONTROL_PLANE",
        workflow_version=workflow_version,
        code_version=code_version,
        budget_version="workflow-budget-v2",
        random_seed=random_seed,
        status="NOT_EXECUTED",
        gate_status="NOT_EVALUATED",
        decision="NOT_EVALUATED",
        not_executed_reason=(
            "No live control-plane adapter was supplied; fixture output is not used "
            "as a live metric."
        ),
        metrics=[
            AutoSpecMetric(
                name="gate_pass_rate",
                status="NOT_EXECUTED",
                value=None,
                unit="ratio",
                source="live-control-plane",
                note="Run this group through POST /api/workflow-runs and Trace/Artifact APIs.",
            )
        ],
    )
