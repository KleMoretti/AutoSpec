import json

from fastapi.testclient import TestClient

import spec_verifier.service as verifier_service
from schemas.verification import VerificationReport


def _client() -> TestClient:
    return TestClient(verifier_service.create_app(token="test-verifier-token"))


def _valid_l1_request() -> dict:
    return {
        "execution_id": "service-test",
        "contract": {},
        "scope": "BACKEND",
        "required_level": "L1",
        "timeout_ms": 30_000,
    }


def test_verifier_requires_dedicated_service_token() -> None:
    response = _client().post("/verify", json=_valid_l1_request())

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid verifier service token"


def test_verifier_rejects_request_and_timeout_over_limits() -> None:
    client = _client()
    oversized = b"{" + b"x" * verifier_service.MAX_VERIFY_REQUEST_BYTES + b"}"

    oversized_response = client.post(
        "/verify",
        content=oversized,
        headers={"X-AutoSpec-Service-Token": "test-verifier-token"},
    )
    timeout_response = client.post(
        "/verify",
        json={**_valid_l1_request(), "timeout_ms": 900},
        headers={"X-AutoSpec-Service-Token": "test-verifier-token"},
    )

    assert oversized_response.status_code == 413
    assert oversized_response.json()["detail"] == "verification request is too large"
    assert timeout_response.status_code == 422

    long_timeout_response = client.post(
        "/verify",
        json={**_valid_l1_request(), "timeout_ms": verifier_service.MAX_VERIFY_TIMEOUT_MS + 1},
        headers={"X-AutoSpec-Service-Token": "test-verifier-token"},
    )
    assert long_timeout_response.status_code == 422


def test_verifier_rejects_oversized_serialized_report(monkeypatch) -> None:
    report = VerificationReport(
        execution_id="service-test",
        contract_id="GeneratedSpec",
        scope="BACKEND",
        verifier_version="spec-verifier-v1",
        compiler_version="spec-compiler-v1",
        level="L1",
        status="PASSED",
        gate_status="PASSED",
        source_digest="a" * 64,
        generated_files=["x" * 1024 for _ in range(600)],
    )
    monkeypatch.setattr(verifier_service, "verify_payload", lambda _payload: report)

    response = _client().post(
        "/verify",
        content=json.dumps(_valid_l1_request()),
        headers={
            "Content-Type": "application/json",
            "X-AutoSpec-Service-Token": "test-verifier-token",
        },
    )

    assert response.status_code == 413
    assert response.json()["detail"] == "verification response is too large"
