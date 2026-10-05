"""Locate current and archived immutable WorkflowSpec snapshots."""
from pathlib import Path


CONTRACT_ROOT = Path(__file__).resolve().parents[1] / "contracts"


def workflow_contract_path(name: str) -> Path:
    """Resolve a filename within the two repository-owned contract directories."""
    if Path(name).name != name or "/" in name or "\\" in name or not name.endswith(".workflow.json"):
        raise ValueError("workflow contract must be a .workflow.json filename")
    candidates = [root / name for root in (CONTRACT_ROOT, CONTRACT_ROOT / "archive")]
    matches = [path for path in candidates if path.is_file()]
    if len(matches) != 1:
        raise ValueError(f"workflow contract must have exactly one snapshot: {name}")
    return matches[0]
