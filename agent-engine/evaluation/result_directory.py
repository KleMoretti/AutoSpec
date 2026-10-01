"""Load an explicitly published evaluation result directory.

The dashboard may read a collected matrix, but it must never infer a matrix by
running agents.  Validation here also keeps fixture and live evidence in
separate matrices and rejects a fixture bundle that claims a promotion.
"""
from __future__ import annotations

import json
from pathlib import Path

from pydantic import ValidationError

from schemas.evaluation import (
    AutoSpecAblationMatrix,
    AutoSpecEvaluationComparison,
    AutoSpecGateDecision,
)


REQUIRED_GROUPS = {"A", "B", "C", "D"}


class EvaluationResultDirectoryError(ValueError):
    """The configured result directory is absent or fails closed validation."""


def load_result_directory(root: Path) -> AutoSpecEvaluationComparison:
    """Load and validate one complete A/B/C/D result directory."""

    if not root.is_dir():
        raise EvaluationResultDirectoryError("evaluation result directory is unavailable")
    try:
        matrix = AutoSpecAblationMatrix.model_validate(_read_json(root / "matrix.json"))
        decision = AutoSpecGateDecision.model_validate(_read_json(root / "gate.json"))
    except (OSError, json.JSONDecodeError, ValidationError) as error:
        raise EvaluationResultDirectoryError("evaluation result directory is invalid") from error

    groups = [run.group for run in matrix.runs]
    if len(groups) != len(set(groups)) or set(groups) != REQUIRED_GROUPS:
        raise EvaluationResultDirectoryError("evaluation matrix must contain exactly A/B/C/D")
    modes = {run.execution_mode for run in matrix.runs}
    if len(modes) != 1:
        raise EvaluationResultDirectoryError("fixture and live runs cannot share one matrix")
    if any(run.dataset_version != matrix.dataset_version for run in matrix.runs):
        raise EvaluationResultDirectoryError("evaluation matrix contains mixed datasets")

    run_ids = {run.run_id for run in matrix.runs}
    if decision.baseline_run_id not in run_ids or decision.candidate_run_id not in run_ids:
        raise EvaluationResultDirectoryError("evaluation gate references an unknown run")
    if "FIXTURE_BASELINE" in modes and decision.decision != "NOT_EVALUATED":
        raise EvaluationResultDirectoryError("fixture evidence cannot claim a release decision")

    return AutoSpecEvaluationComparison(
        status="MEASURED",
        source="RESULT_DIRECTORY",
        matrix=matrix,
        decision=decision,
        not_evaluated_reason=(
            decision.reasons[0] if decision.decision == "NOT_EVALUATED" else None
        ),
    )


def _read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))
