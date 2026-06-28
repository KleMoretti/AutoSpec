from fastapi.testclient import TestClient

from main import app
from test_workflow import valid_architecture_payload, valid_backend_payload, valid_prd_payload


client = TestClient(app)


def test_generate_prd_endpoint_runs_product_manager_only():
    response = client.post("/generate/prd", json={"requirement": "Build AutoSpec V2."})

    assert response.status_code == 200
    body = response.json()
    assert body["prd"]["project_name"]
    assert [record["node_name"] for record in body["records"]] == ["product_manager"]


def test_generate_v2_continue_endpoint_uses_approved_prd_payload():
    response = client.post(
        "/generate/v2/continue",
        json={"requirement": "Build AutoSpec V2.", "prd": valid_prd_payload()},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["architecture_design"]["modules"][0]["name"] == "backend"
    assert body["frontend_skeleton"]["routes"][0]["path"] == "/projects/:projectId"
    assert [record["node_name"] for record in body["records"]] == [
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
    ]


def test_generate_v2_and_node_runner_endpoints_return_v2_artifacts():
    v2_response = client.post("/generate/v2", json={"requirement": "Build AutoSpec V2."})
    node_response = client.post(
        "/nodes/frontend_engineer/run",
        json={
            "requirement": "Build AutoSpec V2.",
            "prd": valid_prd_payload(),
            "architecture_design": valid_architecture_payload(),
            "backend_design": valid_backend_payload(),
        },
    )

    assert v2_response.status_code == 200
    assert v2_response.json()["frontend_skeleton"]["pages"][0]["name"] == "ProjectDetailPage"
    assert node_response.status_code == 200
    assert node_response.json()["node_name"] == "frontend_engineer"
