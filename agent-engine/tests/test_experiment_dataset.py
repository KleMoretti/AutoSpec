import pytest

from evaluation.control_plane import digest
from evaluation.experiment_dataset import experiment_cases
from evaluation.rubric_review import RubricReview, apply_rubric_reviews
from schemas.evaluation import AutoSpecCaseResult, AutoSpecEvalRun


def test_experiment_split_is_disjoint_and_balanced() -> None:
    dev, holdout = experiment_cases("development"), experiment_cases("holdout")
    assert len(dev) == 16 and len(holdout) == 8
    assert not {c.case_id for c in dev} & {c.case_id for c in holdout}
    assert len({c.case_id for c in dev + holdout}) == 24
    assert len({c.category for c in holdout}) == 8
    assert {c.dataset_version for c in dev} == {c.dataset_version for c in holdout}
    assert len(experiment_cases("smoke")) == 8


@pytest.mark.parametrize("mutation", [None, "hash", "path", "missing", "failure"])
def test_independent_rubric_is_bound_to_all_targets_and_exact_evidence(mutation) -> None:
    cases = experiment_cases("holdout")[:1]
    case = cases[0]
    content = {"features": ["barcode uniqueness", "archive history"]}
    result = AutoSpecCaseResult(case_id=case.case_id, status="SUCCEEDED", repetition=1,
                               workflow_run_id="1", bundle_hash="b" * 64, gate_pass=True,
                               blocking_issue_count=0, must_trace_coverage=1)
    run = AutoSpecEvalRun(run_id="test", dataset_version=case.dataset_version,
                         dataset_hash=digest([case.model_dump(mode="json")]), dataset_split="holdout",
                         group="D", group_name="loop-tools-replan", execution_mode="LIVE_CONTROL_PLANE",
                         workflow_version="test", code_version="test", budget_version="test",
                         status="SUCCEEDED", gate_status="NOT_EVALUATED", decision="NOT_EVALUATED",
                         case_results=[result])
    review = RubricReview(dataset_hash=run.dataset_hash, case_id=case.case_id, repetition=1,
                         workflow_run_id="1", bundle_hash="b" * 64, reviewer="synthetic test reviewer",
                         reviewed_at="2026-09-15", blocking_issue_count=0,
                         requirements=[dict(requirement_id=r.requirement_id, verdict="PASS", rationale="test",
                            evidence=[dict(artifact_id=1, content_hash=digest(content), json_pointer=f"/features/{i}")])
                            for i, r in enumerate(case.must_requirements)])
    if mutation == "hash":
        review.requirements[0].evidence[0].content_hash = "a" * 64
    elif mutation == "path":
        review.requirements[0].evidence[0].json_pointer = "/features/99"
    elif mutation == "missing":
        review.requirements.pop()
    elif mutation == "failure":
        review.requirements[0].verdict = "FAIL"
        review.requirements[0].evidence = []
    if mutation in {"hash", "path", "missing"}:
        with pytest.raises(ValueError):
            apply_rubric_reviews(run, cases, [review], {"1": {1: content}})
    else:
        updated = apply_rubric_reviews(run, cases, [review], {"1": {1: content}})
        assert updated.case_results[0].rubric_ref
        assert updated.case_results[0].gate_pass is (mutation != "failure")
        assert updated.case_results[0].must_trace_coverage == (0.5 if mutation == "failure" else 1)
        assert run.case_results[0].rubric_ref is None  # preserve raw collection
