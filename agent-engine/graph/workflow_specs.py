from __future__ import annotations

import json
from pathlib import Path

from schemas.workflow_spec import WorkflowSpec


CURRENT_WORKFLOW_KEY = "autospec-v5"
CURRENT_CONTRACT = (
    Path(__file__).resolve().parents[1]
    / "contracts"
    / "autospec-v5.workflow.json"
)


def get_current_workflow_spec() -> WorkflowSpec:
    return WorkflowSpec.model_validate(
        json.loads(CURRENT_CONTRACT.read_text(encoding="utf-8"))
    )
