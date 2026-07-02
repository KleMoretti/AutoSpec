from review.experiments import compare_experiment_runs
from schemas.evaluation import ExperimentRun


def test_compare_experiment_runs_ranks_by_score_failures_cost_and_duration():
    baseline = ExperimentRun(
        run_id="baseline",
        workflow_key="autospec-v3",
        workflow_version="v3",
        prompt_versions={"reviewer": "v1"},
        model_config={"reviewer": "deterministic-fixture"},
        overall_score=82,
        duration_ms=1200,
        status="SUCCEEDED",
        estimated_cost=0.08,
        failure_count=0,
    )
    candidate = ExperimentRun(
        run_id="candidate",
        workflow_key="autospec-v4",
        workflow_version="v4",
        prompt_versions={"reviewer": "v1", "evaluator": "v1"},
        model_config={"reviewer": "deterministic-fixture", "evaluator": "deterministic-rules"},
        overall_score=92,
        duration_ms=1500,
        status="SUCCEEDED",
        estimated_cost=0.10,
        failure_count=0,
    )
    failed = ExperimentRun(
        run_id="failed",
        workflow_key="autospec-v4",
        workflow_version="v4",
        prompt_versions={"reviewer": "v1", "evaluator": "v1"},
        model_config={"reviewer": "deterministic-fixture", "evaluator": "deterministic-rules"},
        overall_score=92,
        duration_ms=900,
        status="FAILED",
        estimated_cost=0.05,
        failure_count=1,
    )

    report = compare_experiment_runs([baseline, candidate, failed])

    assert report.best_run_id == "candidate"
    assert [run.run_id for run in report.rankings] == ["candidate", "baseline", "failed"]
    assert report.baseline_run_id == "baseline"
    assert report.comparisons[0].candidate_run_id == "candidate"
    assert report.comparisons[0].score_delta == 10
    assert report.comparisons[0].duration_delta_ms == 300
    assert report.comparisons[0].cost_delta == 0.02
    assert report.comparisons[1].candidate_run_id == "failed"
    assert any(issue.issue_type == "EXPERIMENT_FAILURE" for issue in report.issues)


def test_compare_experiment_runs_requires_at_least_two_runs():
    baseline = ExperimentRun(
        run_id="baseline",
        workflow_key="autospec-v4",
        workflow_version="v4",
        prompt_versions={},
        model_config={},
        overall_score=90,
        duration_ms=100,
        status="SUCCEEDED",
        estimated_cost=0,
        failure_count=0,
    )

    try:
        compare_experiment_runs([baseline])
    except ValueError as exc:
        assert "at least two" in str(exc)
    else:
        raise AssertionError("expected comparison to reject single-run input")
