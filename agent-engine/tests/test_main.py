from fastapi.testclient import TestClient

from main import app


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
