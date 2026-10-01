"""python -m evaluation.run_control_plane --config FILE --output DIR"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

import httpx

from evaluation.ablation import run_ablation_matrix, evaluate_release_gate
from evaluation.control_plane import CollectionConfig, ControlPlaneCollector
from evaluation.experiment_dataset import experiment_cases


async def run(config_path: Path, output: Path, *, validate_only: bool = False, resume: bool = False) -> None:
    config = CollectionConfig.model_validate_json(config_path.read_text(encoding="utf-8"))
    if config.manifest_path and not Path(config.manifest_path).is_absolute():
        config = config.model_copy(update={
            "manifest_path": str((config_path.parent / config.manifest_path).resolve())
        })
    cases = select_cases(config)
    if validate_only:
        async with httpx.AsyncClient(base_url="http://127.0.0.1", timeout=5) as client:
            collector = ControlPlaneCollector(client, config, output / config.experiment_id, resume=resume)
            await collector.preflight(offline=True)
        print(json.dumps({"experiment_id": config.experiment_id, "status": "VALIDATED",
                          "groups": config.groups or sorted(config.version_ids),
                          "case_count": len(cases), "collection_scope": config.collection_scope}))
        return
    token = os.environ.get("AUTOSPEC_EVAL_SESSION_TOKEN", "")
    if not token:
        raise ValueError("AUTOSPEC_EVAL_SESSION_TOKEN is required (never put it in the config file)")
    base_url = os.environ.get("AUTOSPEC_EVAL_BASE_URL", "http://127.0.0.1:18080")
    async with httpx.AsyncClient(base_url=base_url, timeout=30,
                                headers={"X-AutoSpec-Session-Token": token}, follow_redirects=False) as client:
        collector = ControlPlaneCollector(client, config, output / config.experiment_id, resume=resume)
        matrix = await run_ablation_matrix(cases, live_runner=collector, groups=config.groups)
        destination = output / config.experiment_id
        (destination / "matrix.json").write_text(matrix.model_dump_json(indent=2), encoding="utf-8")
        by_group = {run.group: run for run in matrix.runs}
        if "A" in by_group and "D" in by_group:
            decision = evaluate_release_gate(by_group["A"], by_group["D"])
        else:
            from schemas.evaluation import AutoSpecGateDecision
            decision = AutoSpecGateDecision(
                decision="NOT_EVALUATED", gate_status="NOT_EVALUATED",
                reasons=["release gate requires both configured A baseline and D candidate"],
                baseline_run_id=by_group.get("A", matrix.runs[0]).run_id,
                candidate_run_id=by_group.get("D", matrix.runs[-1]).run_id,
            )
        (destination / "gate.json").write_text(decision.model_dump_json(indent=2), encoding="utf-8")
        print(json.dumps({"experiment_id": config.experiment_id, "decision": decision.decision,
                          "groups": [{"group": r.group, "status": r.status, "mode": r.execution_mode} for r in matrix.runs]}))


def select_cases(config: CollectionConfig):
    cases = experiment_cases(config.dataset_split)
    if config.case_ids is not None:
        identifiers = set(config.case_ids)
        if len(identifiers) != len(config.case_ids) or identifiers - {case.case_id for case in cases}:
            raise ValueError("case_ids must be unique IDs from the selected dataset split")
        cases = [case for case in cases if case.case_id in identifiers]
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description="Collect bounded experiments through the formal V5 API")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--validate-only", action="store_true",
                        help="validate the explicit manifest and dataset without creating a project or run")
    parser.add_argument("--resume", action="store_true",
                        help="resume only journals with durable RESERVED/PROJECT_CREATED/RUN_SUBMITTED/TERMINAL state")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.config, args.output, validate_only=args.validate_only, resume=args.resume))
    except httpx.HTTPError:
        raise SystemExit("Control-plane connection failed; inspect case journals before restarting.") from None
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
