from __future__ import annotations

from schemas.evaluation import (
    EvaluationIssue,
    ExperimentComparison,
    ExperimentComparisonReport,
    ExperimentRun,
)


def compare_experiment_runs(runs: list[ExperimentRun]) -> ExperimentComparisonReport:
    if len(runs) < 2:
        raise ValueError("Experiment comparison requires at least two runs.")

    baseline = runs[0]
    rankings = sorted(runs, key=_ranking_key)
    comparisons = [
        ExperimentComparison(
            baseline_run_id=baseline.run_id,
            candidate_run_id=run.run_id,
            score_delta=run.overall_score - baseline.overall_score,
            duration_delta_ms=run.duration_ms - baseline.duration_ms,
            cost_delta=round(run.estimated_cost - baseline.estimated_cost, 6),
            failure_delta=run.failure_count - baseline.failure_count,
            human_quality_score_delta=_human_score_delta(baseline, run),
            changed_prompt_keys=_changed_keys(
                baseline.prompt_versions,
                run.prompt_versions,
            ),
            changed_model_keys=_changed_keys(
                baseline.model_configurations,
                run.model_configurations,
            ),
        )
        for run in runs[1:]
    ]
    return ExperimentComparisonReport(
        baseline_run_id=baseline.run_id,
        best_run_id=rankings[0].run_id,
        rankings=rankings,
        comparisons=comparisons,
        issues=_comparison_issues(runs),
    )


def _ranking_key(run: ExperimentRun) -> tuple[int, int, float, float, int]:
    status_penalty = 0 if run.status.upper() == "SUCCEEDED" else 1
    human_score = run.human_quality_score if run.human_quality_score is not None else -1.0
    return (
        run.failure_count + status_penalty,
        -run.overall_score,
        -human_score,
        run.estimated_cost,
        run.duration_ms,
    )


def _human_score_delta(baseline: ExperimentRun, candidate: ExperimentRun) -> float | None:
    if baseline.human_quality_score is None or candidate.human_quality_score is None:
        return None
    return round(candidate.human_quality_score - baseline.human_quality_score, 2)


def _changed_keys(baseline: dict[str, str], candidate: dict[str, str]) -> list[str]:
    return sorted(
        key
        for key in baseline.keys() | candidate.keys()
        if baseline.get(key) != candidate.get(key)
    )


def _comparison_issues(runs: list[ExperimentRun]) -> list[EvaluationIssue]:
    issues: list[EvaluationIssue] = []
    for run in runs:
        if run.status.upper() != "SUCCEEDED" or run.failure_count > 0:
            issues.append(
                EvaluationIssue(
                    severity="HIGH",
                    issue_type="EXPERIMENT_FAILURE",
                    description=f"Experiment run '{run.run_id}' has failed status or node failures.",
                    suggestion="Inspect failed Agent nodes before using this run as a quality baseline.",
                    evidence=[run.run_id],
                )
            )
    return issues
