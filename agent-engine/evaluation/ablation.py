from __future__ import annotations

import time
from collections.abc import Awaitable, Callable, Sequence
from typing import Any
from uuid import uuid4

from evaluation.autospec_case_catalog import list_autospec_cases
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

    selected = list(cases or list_autospec_cases())
    if not selected:
        raise ValueError("AutoSpec ablation matrix requires at least one case")
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
    required = (
        "gate_pass_rate",
        "blocking_issue_median",
        "must_trace_coverage",
        "tool_argument_valid_rate",
        "unauthorized_request_count",
        "schema_invalid_count",
        "p95_latency_ms",
        "tokens_per_run",
        "cost_per_run",
    )
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

    reasons: list[str] = []
    quality_improved = (
        candidate_values["gate_pass_rate"]
        >= baseline_values["gate_pass_rate"] + 0.08
        or candidate_values["blocking_issue_median"]
        <= baseline_values["blocking_issue_median"] * 0.8
    )
    if not quality_improved:
        reasons.append("D does not meet the predeclared quality improvement threshold")
    if candidate_values["must_trace_coverage"] < baseline_values["must_trace_coverage"]:
        reasons.append("D lowers MUST trace coverage")
    if candidate_values["tool_argument_valid_rate"] < 0.95:
        reasons.append("D tool argument validity is below 95%")
    if candidate_values["unauthorized_request_count"] != 0:
        reasons.append("D contains unauthorized tool requests")
    if candidate_values["schema_invalid_count"] != 0:
        reasons.append("D contains schema-invalid outputs")
    for metric, limit in (
        ("p95_latency_ms", max_p95_ratio),
        ("tokens_per_run", max_token_ratio),
        ("cost_per_run", max_cost_ratio),
    ):
        baseline_value = baseline_values[metric]
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
    values = {
        metric.name: metric.value
        for metric in run.metrics
        if metric.status == "MEASURED" and metric.value is not None
    }
    if any(name not in values for name in required):
        return None
    if "blocking_issue_median" not in values:
        values["blocking_issue_median"] = 0.0
    return {name: float(value) for name, value in values.items()}


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
