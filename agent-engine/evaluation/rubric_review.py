"""Offline independent rubric import. Review decisions never enter model inputs."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from evaluation.control_plane import digest
from evaluation.metrics import aggregate_case_metrics
from schemas.evaluation import AutoSpecEvalCase, AutoSpecEvalRun


class ArtifactEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    artifact_id: int
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    json_pointer: str  # RFC 6901 pointer into parsed artifact content; empty means root


class RequirementReview(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requirement_id: str
    verdict: Literal["PASS", "FAIL"]
    rationale: str = Field(min_length=1)
    evidence: list[ArtifactEvidence] = Field(default_factory=list)


class RubricReview(BaseModel):
    model_config = ConfigDict(extra="forbid")
    dataset_hash: str
    case_id: str
    repetition: int = Field(ge=1)
    workflow_run_id: str
    bundle_hash: str
    reviewer: str = Field(min_length=1)
    reviewed_at: str = Field(min_length=1)
    requirements: list[RequirementReview] = Field(min_length=1)
    blocking_issue_count: int = Field(ge=0)


def _resolve_pointer(value: object, pointer: str) -> object:
    if pointer and not pointer.startswith("/"):
        raise ValueError("artifact evidence requires a JSON pointer")
    for part in pointer.split("/")[1:]:
        part = part.replace("~1", "/").replace("~0", "~")
        if isinstance(value, list):
            if not part.isdigit():
                raise ValueError("invalid array evidence pointer")
            value = value[int(part)]
        elif isinstance(value, dict):
            value = value[part]
        else:
            raise ValueError("evidence pointer cannot traverse a scalar")
    return value


def apply_rubric_reviews(run: AutoSpecEvalRun, cases: list[AutoSpecEvalCase],
                        reviews: list[RubricReview], artifacts_by_run: dict[str, dict[int, object]]) -> AutoSpecEvalRun:
    """Bind externally authored reviews to collected runs and exact artifact content.

    artifacts_by_run contains parsed JSON content from the collector's journals.
    Hashes/paths prove what was reviewed, not that a human judgement is correct.
    All MUST targets must be judged, including omissions marked FAIL without evidence.
    """
    catalog = {c.case_id: c for c in cases}
    if run.dataset_hash != digest([c.model_dump(mode="json") for c in cases]):
        raise ValueError("rubric catalog differs from the collected dataset")
    expected = {(r.case_id, r.repetition) for r in run.case_results}
    receipts = {(r.case_id, r.repetition): r for r in reviews}
    if len(receipts) != len(reviews) or set(receipts) != expected:
        raise ValueError("reviews must cover each collected case/repetition exactly once")
    updated = run.model_copy(deep=True)
    for result in updated.case_results:
        receipt = receipts[(result.case_id, result.repetition)]
        if (receipt.dataset_hash != run.dataset_hash or receipt.workflow_run_id != result.workflow_run_id
                or receipt.bundle_hash != result.bundle_hash):
            raise ValueError("review provenance differs from the collected execution")
        targets = {r.requirement_id for r in catalog[result.case_id].must_requirements if r.priority == "MUST"}
        decisions = {r.requirement_id: r for r in receipt.requirements}
        if not targets or len(decisions) != len(receipt.requirements) or set(decisions) != targets:
            raise ValueError("rubric must judge every MUST requirement exactly once")
        artifacts = artifacts_by_run.get(receipt.workflow_run_id, {})
        for decision in receipt.requirements:
            if decision.verdict == "PASS" and not decision.evidence:
                raise ValueError("PASS requires artifact evidence")
            for evidence in decision.evidence:
                if evidence.artifact_id not in artifacts or digest(artifacts[evidence.artifact_id]) != evidence.content_hash:
                    raise ValueError("reviewed artifact content differs from collected evidence")
                try:
                    _resolve_pointer(artifacts[evidence.artifact_id], evidence.json_pointer)
                except (KeyError, IndexError, ValueError) as error:
                    raise ValueError("reviewed artifact path does not exist") from error
        missing = sum(d.verdict == "FAIL" for d in decisions.values())
        result.must_trace_coverage = 1 - missing / len(targets)
        # External review can only lower the product gate; missing product evidence stays missing.
        if result.blocking_issue_count is not None:
            result.blocking_issue_count = max(result.blocking_issue_count, receipt.blocking_issue_count, missing)
        result.gate_pass = bool(result.gate_pass and missing == 0 and receipt.blocking_issue_count == 0)
        result.rubric_ref = digest(receipt.model_dump(mode="json"))
    updated.metrics = aggregate_case_metrics(updated.case_results)
    return updated


def main() -> None:
    import argparse
    import json
    from pathlib import Path
    from evaluation.ablation import evaluate_release_gate
    from evaluation.experiment_dataset import experiment_cases
    from schemas.evaluation import AutoSpecAblationMatrix

    parser = argparse.ArgumentParser(description="Apply independent reviews to immutable collection journals")
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--reviews", type=Path, required=True)
    parser.add_argument("--journals", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    matrix = AutoSpecAblationMatrix.model_validate_json(args.matrix.read_text(encoding="utf-8"))
    reviews = [RubricReview.model_validate(r) for r in json.loads(args.reviews.read_text(encoding="utf-8"))]
    artifacts_by_run: dict[str, dict[int, object]] = {}
    for path in args.journals.glob("*.json"):
        journal = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(journal, dict) or "result" not in journal or "artifacts" not in journal:
            continue
        run_id = journal["result"]["workflow_run_id"]
        if run_id in artifacts_by_run:
            raise ValueError("duplicate execution journal")
        artifacts_by_run[run_id] = {a["id"]: json.loads(a["content"]) for a in journal["artifacts"]}
    used = set()
    for index, run in enumerate(matrix.runs):
        ids = {c.workflow_run_id for c in run.case_results}
        selected = [r for r in reviews if r.workflow_run_id in ids]
        used.update(r.workflow_run_id for r in selected)
        matrix.runs[index] = apply_rubric_reviews(run, experiment_cases(run.dataset_split), selected, artifacts_by_run)
    if used != {r.workflow_run_id for r in reviews}:
        raise ValueError("reviews contain unrelated executions")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / "reviewed-matrix.json"
    if destination.exists() or (args.output / "reviewed-gate.json").exists():
        raise ValueError("review output already exists; use a new directory")
    destination.write_text(matrix.model_dump_json(indent=2), encoding="utf-8")
    groups = {r.group: r for r in matrix.runs}
    gate = evaluate_release_gate(groups["A"], groups["D"])
    (args.output / "reviewed-gate.json").write_text(gate.model_dump_json(indent=2), encoding="utf-8")
    print(gate.model_dump_json())


if __name__ == "__main__":
    main()
