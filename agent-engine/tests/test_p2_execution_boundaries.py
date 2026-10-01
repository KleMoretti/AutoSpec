import json

import pytest

from evaluation.dataset_freeze import freeze_all, generation_payload
from evaluation.experiment_dataset import experiment_cases
from evaluation.statistics import paired_cluster_bootstrap_difference, wilson_interval


def test_frozen_splits_have_business_domains_and_generation_excludes_gold() -> None:
    frozen = freeze_all()
    assert {split: record["case_count"] for split, record in frozen.items()} == {
        "smoke": 8,
        "development": 16,
        "holdout": 8,
    }
    assert all(record["repetitions"] == 3 for record in frozen.values())
    for split in frozen:
        for case in experiment_cases(split):
            payload = generation_payload(case)
            assert set(payload) == {"requirement"}
            assert "must_requirements" not in json.dumps(payload, ensure_ascii=False)


def test_wilson_and_paired_bootstrap_are_reproducible() -> None:
    lower, upper = wilson_interval(8, 10)
    assert 0.49 < lower < 0.56
    assert 0.87 < upper < 0.98
    baseline = {"case-a": [0.0, 0.0, 1.0], "case-b": [0.0, 0.0, 0.0]}
    candidate = {"case-a": [1.0, 1.0, 1.0], "case-b": [0.0, 1.0, 1.0]}
    first = paired_cluster_bootstrap_difference(baseline, candidate, seed=20260930, iterations=300)
    second = paired_cluster_bootstrap_difference(
        baseline=dict(reversed(list(baseline.items()))),
        candidate=dict(reversed(list(candidate.items()))),
        seed=20260930,
        iterations=300,
    )
    assert first == second
    assert first["sample_count"] == 2
    assert first["estimate"] > 0


def test_wilson_zero_trials_is_not_a_measured_zero() -> None:
    lower, upper = wilson_interval(0, 0)
    assert lower != lower  # NaN, intentionally NOT_EVALUATED rather than 0%.
    assert upper != upper
