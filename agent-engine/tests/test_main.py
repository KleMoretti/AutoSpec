import asyncio
import json
import shutil
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from main import app
from evaluation.ablation import run_ablation_matrix


client = TestClient(app)


def test_health_and_current_evaluation_catalog() -> None:
    assert client.get("/health").json() == {"status": "UP"}

    response = client.get("/evaluation/cases")
    assert response.status_code == 200
    assert response.json()[0]["case_id"]


def test_release_gate_uses_autospec_evaluation_runs() -> None:
    common = {
        "dataset_version": "autospec-v5-agent-execution-eval-v1",
        "execution_mode": "FIXTURE_BASELINE",
        "workflow_version": "v5",
        "code_version": "test",
        "budget_version": "test",
        "status": "NOT_EXECUTED",
        "gate_status": "NOT_EVALUATED",
        "decision": "NOT_EVALUATED",
    }
    response = client.post(
        "/evaluation/release-gate",
        json={
            "baseline": {
                **common,
                "run_id": "baseline-a",
                "group": "A",
                "group_name": "single-shot",
            },
            "candidate": {
                **common,
                "run_id": "candidate-d",
                "group": "D",
                "group_name": "loop-tools-replan",
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "NOT_EVALUATED"
    assert body["baseline_run_id"] == "baseline-a"
    assert body["candidate_run_id"] == "candidate-d"


def test_ablation_comparison_keeps_missing_live_evidence_explicit() -> None:
    response = client.get("/evaluation/ablation")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "NOT_EVALUATED"
    assert body["source"] == "NONE"
    assert len(body["matrix"]["runs"]) == 4
    assert all(run["status"] == "NOT_EXECUTED" for run in body["matrix"]["runs"])
    assert [run["run_id"] for run in body["matrix"]["runs"]] == [
        "not-executed-a",
        "not-executed-b",
        "not-executed-c",
        "not-executed-d",
    ]
    assert all(
        metric["value"] is None
        for run in body["matrix"]["runs"]
        for metric in run["metrics"]
    )
    assert body["decision"]["decision"] == "NOT_EVALUATED"


def test_ablation_comparison_loads_explicit_result_directory(monkeypatch) -> None:
    result_dir = Path(__file__).resolve().parents[1] / "target" / f"main-result-{uuid4().hex}"
    result_dir.mkdir(parents=True)
    matrix = asyncio.run(run_ablation_matrix(matrix_id="fixture-matrix"))
    matrix = matrix.model_copy(update={
        "runs": [
            run.model_copy(update={
                "execution_mode": "FIXTURE_BASELINE",
                "status": "SUCCEEDED",
                "not_executed_reason": None,
            })
            for run in matrix.runs
        ]
    })
    decision = {
        "decision": "NOT_EVALUATED",
        "gate_status": "NOT_EVALUATED",
        "reasons": ["fixture evidence is diagnostic only"],
        "baseline_run_id": matrix.runs[0].run_id,
        "candidate_run_id": matrix.runs[-1].run_id,
    }
    try:
        (result_dir / "matrix.json").write_text(
            json.dumps(matrix.model_dump(mode="json")), encoding="utf-8"
        )
        (result_dir / "gate.json").write_text(json.dumps(decision), encoding="utf-8")
        monkeypatch.setenv("AUTOSPEC_EVALUATION_RESULT_DIR", str(result_dir))

        response = client.get("/evaluation/ablation")

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "MEASURED"
        assert body["source"] == "RESULT_DIRECTORY"
        assert body["matrix"]["matrix_id"] == "fixture-matrix"
        assert {run["execution_mode"] for run in body["matrix"]["runs"]} == {"FIXTURE_BASELINE"}
        assert body["decision"]["decision"] == "NOT_EVALUATED"
    finally:
        shutil.rmtree(result_dir, ignore_errors=True)


def test_ablation_comparison_rejects_mixed_result_modes(monkeypatch) -> None:
    result_dir = Path(__file__).resolve().parents[1] / "target" / f"main-result-{uuid4().hex}"
    result_dir.mkdir(parents=True)
    matrix = asyncio.run(run_ablation_matrix(matrix_id="mixed-matrix"))
    matrix = matrix.model_copy(update={
        "runs": [
            matrix.runs[0].model_copy(update={"execution_mode": "FIXTURE_BASELINE"}),
            *matrix.runs[1:],
        ]
    })
    decision = {
        "decision": "NOT_EVALUATED",
        "gate_status": "NOT_EVALUATED",
        "reasons": ["not comparable"],
        "baseline_run_id": matrix.runs[0].run_id,
        "candidate_run_id": matrix.runs[-1].run_id,
    }
    try:
        (result_dir / "matrix.json").write_text(
            json.dumps(matrix.model_dump(mode="json")), encoding="utf-8"
        )
        (result_dir / "gate.json").write_text(json.dumps(decision), encoding="utf-8")
        monkeypatch.setenv("AUTOSPEC_EVALUATION_RESULT_DIR", str(result_dir))

        body = client.get("/evaluation/ablation").json()

        assert body["status"] == "NOT_EVALUATED"
        assert body["source"] == "NONE"
        assert "unavailable or invalid" in body["not_evaluated_reason"]
    finally:
        shutil.rmtree(result_dir, ignore_errors=True)
