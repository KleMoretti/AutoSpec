import pytest

from evaluation.case_catalog import get_evaluation_case, list_evaluation_cases


def test_v4_evaluation_case_catalog_contains_three_reproducible_cases():
    cases = list_evaluation_cases()

    assert len(cases) >= 3
    assert len({case.case_id for case in cases}) == len(cases)
    for case in cases:
        assert case.requirement
        assert len(case.expected_capabilities) >= 3
        assert case.required_artifact_types == [
            "PRD",
            "ARCHITECTURE_DESIGN",
            "BACKEND_DESIGN",
            "FRONTEND_SKELETON",
            "REVIEW_REPORT",
            "EVALUATION_REPORT",
        ]
        assert "CROSS_ARTIFACT_CONSISTENCY" in case.scoring_dimensions
        assert "RUNTIME_RELIABILITY" in case.scoring_dimensions


def test_get_evaluation_case_returns_case_by_id():
    case = get_evaluation_case("campus_marketplace")

    assert case.title == "Campus Second-Hand Marketplace"
    assert "product publishing" in case.expected_capabilities


def test_get_evaluation_case_rejects_unknown_id():
    with pytest.raises(KeyError, match="unknown evaluation case"):
        get_evaluation_case("missing")
