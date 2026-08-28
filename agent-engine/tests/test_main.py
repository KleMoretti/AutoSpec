from fastapi.testclient import TestClient

from main import app


client = TestClient(app)


def test_health_and_current_evaluation_catalog() -> None:
    assert client.get("/health").json() == {"status": "UP"}

    response = client.get("/evaluation/cases")
    assert response.status_code == 200
    assert response.json()[0]["case_id"]


def test_experiment_compare_uses_current_workflow_runs() -> None:
    response = client.post(
        "/experiments/compare",
        json={
            "runs": [
                {
                    "run_id": "v5-fast",
                    "workflow_key": "autospec-v5",
                    "workflow_version": "v5",
                    "prompt_versions": {"reviewer": "v1", "evaluator": "v1"},
                    "model_config": {"quality_profile": "FAST"},
                    "overall_score": 82,
                    "duration_ms": 1200,
                    "status": "SUCCEEDED",
                    "estimated_cost": 0.08,
                    "failure_count": 0,
                },
                {
                    "run_id": "v5-balanced",
                    "workflow_key": "autospec-v5",
                    "workflow_version": "v5",
                    "prompt_versions": {"reviewer": "v1", "evaluator": "v1"},
                    "model_config": {"quality_profile": "BALANCED"},
                    "overall_score": 92,
                    "duration_ms": 1500,
                    "status": "SUCCEEDED",
                    "estimated_cost": 0.10,
                    "failure_count": 0,
                },
            ]
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["best_run_id"] == "v5-balanced"
    assert body["comparisons"][0]["score_delta"] == 10
