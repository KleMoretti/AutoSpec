"""Explicit, hash-pinned experiment manifest for P2 collection."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from runtime.workflow_contracts import workflow_contract_path


class ManifestGroup(BaseModel):
    model_config = ConfigDict(extra="forbid")

    group: Literal["A", "B", "C", "D"]
    name: str = Field(min_length=1)
    workflow_version_id: int = Field(ge=1)
    contract_path: str = Field(min_length=1)
    contract_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    prompt_schema_versions: dict[str, str] = Field(default_factory=dict)


class ManifestBudget(BaseModel):
    model_config = ConfigDict(extra="forbid")

    repetitions: int = Field(ge=1, le=10)
    run_max_cost: float = Field(gt=0)
    total_max_cost: float = Field(gt=0)
    max_runs: int = Field(ge=1, le=500)
    run_max_tokens: int = Field(ge=1)
    run_max_model_calls: int = Field(ge=1)


class ExperimentManifest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    manifest_version: Literal["autospec-experiment-manifest-v1"]
    experiment_id: str = Field(min_length=1)
    workflow_key: str = Field(default="autospec-v5", min_length=1)
    dataset_version: str = Field(min_length=1)
    dataset_split: str = Field(min_length=1)
    random_seed: int
    groups: list[ManifestGroup] = Field(min_length=1)
    budget: ManifestBudget
    pricing_snapshot: dict = Field(default_factory=dict)

    def group_map(self) -> dict[str, ManifestGroup]:
        result = {entry.group: entry for entry in self.groups}
        if len(result) != len(self.groups):
            raise ValueError("experiment manifest contains duplicate groups")
        return result


def load_manifest(path: Path) -> tuple[ExperimentManifest, str]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    manifest = ExperimentManifest.model_validate(raw)
    return manifest, hashlib.sha256(
        json.dumps(raw, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def resolve_contract_path(manifest_path: Path, contract_path: str) -> Path:
    candidate = Path(contract_path)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError("manifest contract_path must be a relative non-parent path")
    resolved = (manifest_path.parent / candidate).resolve()
    if not resolved.is_file():
        # Existing manifests keep their original bytes and checksums. A removed
        # duplicate can reference the single current/archive snapshot by name.
        if len(candidate.parts) == 1 and contract_path.endswith(".workflow.json"):
            return workflow_contract_path(contract_path)
        raise ValueError(f"manifest contract does not exist: {contract_path}")
    return resolved


def validate_manifest_contracts(path: Path, manifest: ExperimentManifest) -> dict[str, dict]:
    checked: dict[str, dict] = {}
    for entry in manifest.groups:
        contract_path = resolve_contract_path(path, entry.contract_path)
        raw = json.loads(contract_path.read_text(encoding="utf-8"))
        contract_hash = hashlib.sha256(
            json.dumps(raw, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        if contract_hash != entry.contract_hash:
            raise ValueError(f"manifest contract checksum mismatch for group {entry.group}")
        checked[entry.group] = {"path": str(contract_path), "spec": raw, "entry": entry.model_dump(mode="json")}
    return checked
