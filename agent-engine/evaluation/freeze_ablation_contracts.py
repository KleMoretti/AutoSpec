"""Freeze the current AutoSpec contract into the four P2 A/B/C/D candidates.

The generator deliberately changes only the Backend Engineer execution-policy
knobs.  Product Manager, Architect, Frontend Engineer, Reviewer and Evaluator
remain byte-for-byte equivalent after JSON parsing, so the final grading path
stays common to all groups.  Generated snapshots are suitable for an explicit
experiment manifest; they never change the published default contract.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any

from schemas.workflow_spec import WorkflowSpec


GROUPS: tuple[tuple[str, str, bool, bool, bool], ...] = (
    ("A", "single-shot", False, False, False),
    ("B", "loop-no-tools", True, False, False),
    ("C", "loop-with-tools", True, True, False),
    ("D", "loop-tools-verify", True, True, True),
)

READ_ONLY_TOOLS = [
    {"name": "knowledge.search", "version": "v1"},
    {"name": "artifact.get", "version": "v1"},
    {"name": "contract.lookup", "version": "v1"},
]
SPEC_VERIFY_TOOL = {"name": "spec.verify", "version": "v1"}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def content_hash(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _verification_policy_hash(policy: dict[str, Any]) -> str:
    material = {key: value for key, value in policy.items() if key != "policy_hash"}
    return content_hash(material)


def freeze_contracts(base_path: Path, output_dir: Path, version_suffix: str) -> dict[str, dict[str, Any]]:
    base = json.loads(base_path.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(base)
    output_dir.mkdir(parents=True, exist_ok=True)
    frozen: dict[str, dict[str, Any]] = {}
    for group, name, loop_enabled, tools_enabled, verify_enabled in GROUPS:
        spec = copy.deepcopy(base)
        spec["version"] = f"{base['version']}-ablation-{group.lower()}-{version_suffix}"
        backend = next(node for node in spec["nodes"] if node["node_id"] == "backend_engineer")
        backend["agent_loop_policy"]["enabled"] = loop_enabled
        backend["tool_policy"]["enabled"] = tools_enabled
        backend["tool_policy"]["allowed_tools"] = (
            copy.deepcopy(READ_ONLY_TOOLS)
            + ([copy.deepcopy(SPEC_VERIFY_TOOL)] if verify_enabled else [])
            if tools_enabled else []
        )
        backend["tool_policy"]["allowed_side_effects"] = (
            ["READ_ONLY", "DETERMINISTIC", "SANDBOXED"]
            if tools_enabled else []
        )
        backend["verification_policy"]["enabled"] = verify_enabled
        backend["verification_policy"]["required_level"] = "L1" if verify_enabled else "NONE"
        backend["verification_policy"]["policy_hash"] = _verification_policy_hash(
            backend["verification_policy"]
        )
        WorkflowSpec.model_validate(spec)
        path = output_dir / f"autospec-pm-schema-repair-v12-ablation-{group.lower()}-{version_suffix}.workflow.json"
        path.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        frozen[group] = {
            "group": group,
            "name": name,
            "path": path,
            "spec": spec,
            "hash": content_hash(spec),
        }
    return frozen


def _parse_version_ids(values: list[str]) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        group, separator, identifier = value.partition("=")
        if separator != "=" or group not in {"A", "B", "C", "D"} or not identifier.isdigit():
            raise ValueError("--version-id must use GROUP=INTEGER for A/B/C/D")
        result[group] = int(identifier)
    if set(result) != {"A", "B", "C", "D"}:
        raise ValueError("--version-id must provide A, B, C and D")
    return result


def write_manifest(
    manifest_path: Path,
    *,
    experiment_id: str,
    dataset_version: str,
    dataset_split: str,
    random_seed: int,
    version_ids: dict[str, int],
    frozen: dict[str, dict[str, Any]],
    repetitions: int = 1,
    run_max_cost: float = 0.01,
    total_max_cost: float = 0.04,
    max_runs: int = 4,
    run_max_tokens: int = 50_000,
    run_max_model_calls: int = 16,
) -> None:
    groups = []
    for group, name, *_ in GROUPS:
        item = frozen[group]
        groups.append({
            "group": group,
            "name": name,
            "workflow_version_id": version_ids[group],
            "contract_path": item["path"].relative_to(manifest_path.parent).as_posix(),
            "contract_hash": item["hash"],
            "prompt_schema_versions": {
                node["node_id"]: f"{node['prompt_key']}:{node['prompt_version']}:{node['output_schema_hash']}"
                for node in item["spec"]["nodes"]
            },
        })
    manifest = {
        "manifest_version": "autospec-experiment-manifest-v1",
        "experiment_id": experiment_id,
        "workflow_key": "autospec-v5",
        "dataset_version": dataset_version,
        "dataset_split": dataset_split,
        "random_seed": random_seed,
        "budget": {
            "repetitions": repetitions,
            "run_max_cost": run_max_cost,
            "total_max_cost": total_max_cost,
            "max_runs": max_runs,
            "run_max_tokens": run_max_tokens,
            "run_max_model_calls": run_max_model_calls,
        },
        "groups": groups,
        "pricing_snapshot": {
            "mode": "FIXTURE_BASELINE",
            "cost_status": "NOT_APPLICABLE_EXTERNAL",
            "source": "local-fixture",
            "observed_at": "2026-10-01",
        },
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version-suffix", required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--experiment-id", default="autospec-p2-p4-fixture-ablation-20261001-r1")
    parser.add_argument("--dataset-version", default="autospec-v5-agent-execution-eval-v1")
    parser.add_argument("--dataset-split", default="smoke")
    parser.add_argument("--random-seed", type=int, default=20261001)
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--run-max-cost", type=float, default=0.01)
    parser.add_argument("--total-max-cost", type=float, default=0.04)
    parser.add_argument("--max-runs", type=int, default=4)
    parser.add_argument("--run-max-tokens", type=int, default=50_000)
    parser.add_argument("--run-max-model-calls", type=int, default=16)
    parser.add_argument("--version-id", action="append", default=[])
    args = parser.parse_args()
    frozen = freeze_contracts(args.base, args.output_dir, args.version_suffix)
    if args.manifest:
        if not args.version_id:
            raise SystemExit("--manifest requires four --version-id arguments")
        write_manifest(
            args.manifest,
            experiment_id=args.experiment_id,
            dataset_version=args.dataset_version,
            dataset_split=args.dataset_split,
            random_seed=args.random_seed,
            version_ids=_parse_version_ids(args.version_id),
            frozen=frozen,
            repetitions=args.repetitions,
            run_max_cost=args.run_max_cost,
            total_max_cost=args.total_max_cost,
            max_runs=args.max_runs,
            run_max_tokens=args.run_max_tokens,
            run_max_model_calls=args.run_max_model_calls,
        )
    print(json.dumps({group: item["hash"] for group, item in frozen.items()}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
