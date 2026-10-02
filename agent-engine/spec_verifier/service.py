from __future__ import annotations

import hashlib
import json
import os
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from schemas.verification import VerificationIssue, VerificationReport
from spec_verifier.compiler import compile_spec
from spec_verifier.sandbox import run_l2
from spec_verifier.validators import validate_l1


MAX_VERIFY_REQUEST_BYTES = 512 * 1024
MAX_VERIFY_RESPONSE_BYTES = 512 * 1024


class VerifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    execution_id: str = Field(min_length=1, max_length=255)
    contract: dict[str, Any]
    scope: Literal["BACKEND", "FULL"] = "FULL"
    required_level: Literal["L1", "L2"]
    rule_profile: str = Field(default="spec-full-v1", min_length=1, max_length=128)
    source_digest: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    timeout_ms: int = Field(default=30_000, ge=1_000, le=900_000)


def verify_payload(payload: VerifyRequest) -> VerificationReport:
    report = validate_l1(
        payload.contract,
        execution_id=payload.execution_id,
        scope=payload.scope,
    )
    if payload.source_digest is not None and payload.source_digest != report.source_digest:
        report.issues.append(VerificationIssue(
            code="SOURCE_DIGEST_MISMATCH",
            severity="CRITICAL",
            message="verification input does not match the trusted source digest",
        ))
        report.status = "FAILED"
        report.gate_status = "BLOCKED"
    if report.gate_status == "BLOCKED" or payload.required_level == "L1":
        return report
    try:
        compiled = compile_spec(payload.contract)
    except Exception as exc:
        report.level = "L2"
        report.status = "ERROR"
        report.gate_status = "BLOCKED"
        report.issues.append(VerificationIssue(
            code="L2_COMPILER_FAILED",
            severity="CRITICAL",
            message=str(exc)[:1000],
        ))
        return report
    return run_l2(
        compiled,
        execution_id=payload.execution_id,
        timeout_ms=payload.timeout_ms,
        scope=payload.scope,
    )


def create_app(token: str | None = None) -> FastAPI:
    app = FastAPI(title="AutoSpec Spec Verifier", version="spec-verifier-v1")
    expected_token = token if token is not None else os.environ.get("SPEC_VERIFIER_SERVICE_TOKEN", "")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok", "service": "spec-verifier"}

    @app.post("/verify", response_model=VerificationReport)
    async def verify(request: Request, x_autospec_service_token: str | None = Header(default=None)) -> VerificationReport:
        body = await request.body()
        if len(body) > MAX_VERIFY_REQUEST_BYTES:
            raise HTTPException(status_code=413, detail="verification request is too large")
        if not expected_token or x_autospec_service_token != expected_token:
            raise HTTPException(status_code=401, detail="invalid verifier service token")
        try:
            payload = VerifyRequest.model_validate_json(body)
        except ValueError as exc:
            errors = exc.errors() if hasattr(exc, "errors") else []
            safe_errors = [
                {
                    "location": list(error.get("loc", ())),
                    "type": error.get("type", "validation_error"),
                    "reason": (
                        str(error.get("ctx", {}).get("error"))[:200]
                        if error.get("type") == "json_invalid"
                        and isinstance(error.get("ctx"), dict)
                        and error.get("ctx", {}).get("error")
                        else None
                    ),
                }
                for error in errors
            ]
            raise HTTPException(
                status_code=422,
                detail={
                    "message": "invalid verification request",
                    "errors": safe_errors,
                },
            ) from exc
        report = verify_payload(payload)
        response_body = json.dumps(
            report.model_dump(mode="json"),
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        if len(response_body) > MAX_VERIFY_RESPONSE_BYTES:
            raise HTTPException(status_code=413, detail="verification response is too large")
        return report

    return app


app = create_app()
