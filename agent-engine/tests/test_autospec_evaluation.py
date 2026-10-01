from __future__ import annotations

import pytest

from evaluation.ablation import ablation_configs, evaluate_release_gate, run_ablation_matrix
from evaluation.autospec_case_catalog import list_autospec_cases
from evaluation.metrics import aggregate_case_metrics
from schemas.evaluation import AutoSpecCaseResult, AutoSpecEvalRun


def test_autospec_case_catalog_covers_p0_failure_shapes() -> None:
    cases = list_autospec_cases()

    assert len(cases) >= 8
    assert {case.category for case in cases} == {
        "CRUD",
        "APPROVAL",
        "PERMISSION",
        "MULTI_ENTITY",
        "EXTERNAL_INTEGRATION",
        "AMBIGUOUS_REQUIREMENT",
        "CONFLICTING_CONSTRAINTS",
        "REWORK",
    }
    assert all(
        requirement.priority == "MUST"
        for case in cases
        for requirement in case.must_requirements
    )
    assert all(case.failure_conditions for case in cases)


@pytest.mark.asyncio
async def test_ablation_matrix_is_explicitly_unexecuted_without_live_adapter() -> None:
    matrix = await run_ablation_matrix()

    assert [(run.group, run.group_name) for run in matrix.runs] == [
        ("A", "single-shot"),
        ("B", "loop-no-tools"),
        ("C", "loop-with-tools"),
        ("D", "loop-tools-verify"),
    ]
    assert all(run.status == "NOT_EXECUTED" for run in matrix.runs)
    assert all(run.gate_status == "NOT_EVALUATED" for run in matrix.runs)
    assert all(metric.value is None for run in matrix.runs for metric in run.metrics)
    decision = evaluate_release_gate(matrix.runs[0], matrix.runs[3])
    assert decision.decision == "NOT_EVALUATED"


@pytest.mark.asyncio
async def test_ablation_matrix_accepts_validated_live_adapter() -> None:
    cases = list_autospec_cases()[:1]

    async def adapter(group: str, selected, config):
        from schemas.evaluation import AutoSpecEvalRun

        return AutoSpecEvalRun(
            run_id=f"live-{group}",
            dataset_version=selected[0].dataset_version,
            group=group,
            group_name=config["name"],
            execution_mode="LIVE_CONTROL_PLANE",
            workflow_version="v5-agent-execution",
            code_version="test",
            budget_version="test-budget",
            status="SUCCEEDED",
            gate_status="NOT_EVALUATED",
            decision="NOT_EVALUATED",
        )

    matrix = await run_ablation_matrix(cases, live_runner=adapter)

    assert [run.run_id for run in matrix.runs] == ["live-A", "live-B", "live-C", "live-D"]
    assert len(ablation_configs()) == 4


def measured_run(group: str, *, improved: bool = False, cost: float = 1.0) -> AutoSpecEvalRun:
    cases = [AutoSpecCaseResult(
        case_id=f"case-{i}", repetition=r, workflow_run_id=f"{group}-{i}-{r}",
        trace_id=f"trace-{group}-{i}-{r}", bundle_hash="a" * 64, rubric_ref="test-reviewed-rubric",
        status="SUCCEEDED" if improved else "FAILED", gate_pass=improved,
        must_trace_coverage=1, blocking_issue_count=0 if improved else 2,
        unauthorized_tool_requests=0, unauthorized_tool_executions=0,
        invalid_tool_arguments=0, tool_call_count=1 if group == "D" else 0,
        schema_invalid_count=0, duration_ms=100, tokens=100, cost=cost,
    ) for i in range(8) for r in range(1, 4)]
    return AutoSpecEvalRun(
        run_id=group, group=group, group_name="single-shot" if group == "A" else "loop-tools-verify",
        execution_mode="LIVE_CONTROL_PLANE", dataset_version="v2", dataset_hash="b" * 64,
        dataset_split="holdout", environment_hash="c" * 64, code_version="test",
        model_version="test-model", retriever_version="test-rag", budget_version="test",
        bundle_hash="a" * 64, prompt_schema_versions={"backend": "v2"}, workflow_version="test",
        status="SUCCEEDED", gate_status="NOT_EVALUATED", decision="NOT_EVALUATED",
        pricing_snapshot=dict(source="test-price", observed_at="2026-09-14", currency="CNY",
                              version="test", models={"test-model": dict(input_per_million=1,
                              cached_input_per_million=1, output_per_million=1)}),
        case_results=cases, metrics=aggregate_case_metrics(cases),
    )


def test_release_gate_requires_actual_improvement_and_supports_measured_success() -> None:
    a, d = measured_run("A"), measured_run("D", improved=True)
    assert evaluate_release_gate(a, d).decision == "PROMOTE"
    a = measured_run("A", improved=True)
    assert evaluate_release_gate(a, d).decision == "REVISE"


@pytest.mark.parametrize("mutation", ["fixture", "empty", "failed", "dataset", "duplicate", "nan", "aggregate", "sample", "rubric", "development"])
def test_release_gate_rejects_unreliable_evidence(mutation: str) -> None:
    a, d = measured_run("A"), measured_run("D", improved=True)
    if mutation == "fixture":
        d.execution_mode = "FIXTURE_BASELINE"
    elif mutation == "empty":
        d.case_results = []
    elif mutation == "failed":
        d.status = "FAILED"
    elif mutation == "dataset":
        d.dataset_hash = "other"
    elif mutation == "duplicate":
        d.metrics.append(d.metrics[0])
    elif mutation == "nan":
        d.metrics[0].value = float("nan")
    elif mutation == "aggregate":
        d.metrics[0].value = 0.5
    elif mutation == "rubric":
        d.case_results[0].rubric_ref = None
    elif mutation == "development":
        a.dataset_split = d.dataset_split = "development"
    else:
        d.case_results = d.case_results[:1]
    assert evaluate_release_gate(a, d).decision == "NOT_EVALUATED"


def test_zero_baseline_needs_explicit_absolute_budget() -> None:
    a, d = measured_run("A", cost=0), measured_run("D", improved=True, cost=100)
    assert evaluate_release_gate(a, d).decision == "NOT_EVALUATED"
    assert evaluate_release_gate(a, d, zero_baseline_limits={"cost_per_run": 1}).decision == "REVISE"


def test_denied_attack_is_distinct_from_unauthorized_execution() -> None:
    a, d = measured_run("A"), measured_run("D", improved=True)
    d.case_results[0].unauthorized_tool_requests = 1
    d.metrics = aggregate_case_metrics(d.case_results)
    assert evaluate_release_gate(a, d).decision == "PROMOTE"
    d.case_results[0].unauthorized_tool_executions = 1
    d.case_results[0].gate_pass = False
    d.metrics = aggregate_case_metrics(d.case_results)
    assert evaluate_release_gate(a, d).decision == "REJECT"
