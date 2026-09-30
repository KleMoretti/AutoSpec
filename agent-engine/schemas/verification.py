"""Typed verification evidence shared by the worker, sidecar and evaluator."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class VerificationIssue(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str = Field(min_length=1, max_length=128)
    severity: Literal["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
    message: str = Field(min_length=1, max_length=1000)
    path: str = Field(default="$", min_length=1, max_length=256)


class VerificationCheck(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str = Field(min_length=1, max_length=128)
    status: Literal["PASSED", "FAILED", "NOT_RUN", "ERROR"]
    issue_codes: list[str] = Field(default_factory=list)


class VerificationReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["verification-report-v1"] = "verification-report-v1"
    execution_id: str = Field(min_length=1)
    contract_id: str = Field(min_length=1)
    scope: Literal["BACKEND", "FULL"] = "FULL"
    verifier_version: str = Field(min_length=1)
    compiler_version: str = Field(min_length=1)
    level: Literal["L1", "L2"]
    status: Literal["PASSED", "FAILED", "BLOCKED", "ERROR", "NOT_RUN"]
    gate_status: Literal["PASSED", "BLOCKED"]
    source_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    checks: list[VerificationCheck] = Field(default_factory=list)
    issues: list[VerificationIssue] = Field(default_factory=list)
    generated_files: list[str] = Field(default_factory=list)


class VerificationFact(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    execution_id: str = Field(min_length=1)
    workflow_run_id: int = Field(ge=1)
    node_run_id: int = Field(ge=1)
    fencing_token: int = Field(ge=1)
    policy_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    scope: Literal["BACKEND", "FULL"] = "FULL"
    verifier_version: str = Field(min_length=1)
    compiler_version: str = Field(min_length=1)
    achieved_level: Literal["L1", "L2"]
    status: Literal["PASSED", "FAILED", "BLOCKED", "ERROR"]
    expires_at_epoch_ms: int = Field(ge=1)
    report_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
