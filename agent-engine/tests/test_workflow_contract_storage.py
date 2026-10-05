from pathlib import Path

import pytest

from evaluation.experiment_manifest import load_manifest, resolve_contract_path, validate_manifest_contracts
from runtime.workflow_contracts import workflow_contract_path


CONFIG_ROOT = Path(__file__).resolve().parents[1] / "evaluation" / "configs"


def test_current_and_archived_manifests_resolve_original_contract_hashes() -> None:
    for path in CONFIG_ROOT.rglob("experiment-manifest*.json"):
        manifest, _ = load_manifest(path)
        assert validate_manifest_contracts(path, manifest)


def test_archived_manifest_uses_single_snapshot_and_rejects_changed_hash() -> None:
    path = CONFIG_ROOT / "archive" / "p2-p3-20261003-explicit-v2" / "experiment-manifest-live-smoke-v5-20261004.json"
    manifest, _ = load_manifest(path)
    entry = manifest.groups[0]
    assert resolve_contract_path(path, entry.contract_path) == workflow_contract_path(entry.contract_path)
    altered = manifest.model_copy(update={
        "groups": [entry.model_copy(update={"contract_hash": "0" * 64})]
    })
    with pytest.raises(ValueError, match="checksum mismatch"):
        validate_manifest_contracts(path, altered)


@pytest.mark.parametrize("name", ["../outside.workflow.json", "/outside.workflow.json", "..\\outside.workflow.json"])
def test_contract_lookup_rejects_paths_outside_catalog(name: str) -> None:
    with pytest.raises(ValueError):
        workflow_contract_path(name)
    with pytest.raises(ValueError):
        resolve_contract_path(CONFIG_ROOT / "manifest.json", name)
