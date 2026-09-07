from __future__ import annotations

import pytest

from evaluation.ablation import ablation_configs, evaluate_release_gate, run_ablation_matrix
from evaluation.autospec_case_catalog import list_autospec_cases


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
        ("D", "loop-tools-replan"),
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
