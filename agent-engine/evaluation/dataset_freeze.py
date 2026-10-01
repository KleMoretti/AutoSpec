"""Dataset split and payload freeze checks for the P2 evaluation batch."""
from __future__ import annotations

from typing import Any

from evaluation.control_plane import digest
from evaluation.experiment_dataset import DATASET_VERSION, experiment_cases
from schemas.evaluation import AutoSpecEvalCase


DATASET_FREEZE_VERSION = "autospec-dataset-freeze-v1"


def generation_payload(case: AutoSpecEvalCase) -> dict[str, Any]:
    """Return the only case content allowed into generation-side input."""

    return {"requirement": case.requirement}


def validate_split(split: str) -> dict[str, Any]:
    cases = experiment_cases(split)
    expected = {"smoke": 8, "development": 16, "holdout": 8}[split]
    if len(cases) != expected or len({case.case_id for case in cases}) != expected:
        raise ValueError(f"{split} must contain {expected} unique cases")
    domains = {case.business_domain for case in cases}
    if len(domains) < 5:
        raise ValueError(f"{split} must cover at least five business domains")
    if any(not case.must_requirements or any(requirement.priority != "MUST" for requirement in case.must_requirements)
           for case in cases):
        raise ValueError("every frozen case must have independently authored MUST facts")
    if any(generation_payload(case).keys() != {"requirement"} for case in cases):
        raise ValueError("gold fields must not enter the generation payload")
    return {
        "freeze_version": DATASET_FREEZE_VERSION,
        "dataset_version": DATASET_VERSION,
        "split": split,
        "case_count": len(cases),
        "business_domains": sorted(domains),
        "dataset_hash": digest([case.model_dump(mode="json") for case in cases]),
        "repetitions": 3,
        "case_ids": [case.case_id for case in cases],
    }


def freeze_all() -> dict[str, dict[str, Any]]:
    return {split: validate_split(split) for split in ("smoke", "development", "holdout")}
