from __future__ import annotations

import hashlib
import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


SHA256_PATTERN = r"^[0-9a-f]{64}$"
CONTROLLED_TOOL_NAMES = frozenset(
    {
        "knowledge.search",
        "artifact.get",
        "contract.lookup",
        "trace.query",
        "bundle.verify",
    }
)


class ToolGatewayRequest(BaseModel):
    """Worker-to-control-plane envelope for one bounded tool request."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    request_id: str = Field(min_length=1, max_length=255)
    execution_id: str = Field(min_length=1, max_length=255)
    workflow_run_id: int = Field(ge=1)
    node_run_id: int = Field(ge=1)
    node_id: str = Field(min_length=1, max_length=128)
    actor_user_id: str = Field(min_length=1, max_length=128)
    project_id: str = Field(min_length=1, max_length=128)
    fencing_token: int = Field(ge=1)
    deadline_epoch_ms: int = Field(ge=1)
    execution_bundle_hash: str | None = Field(default=None, pattern=SHA256_PATTERN)
    policy_hash: str = Field(pattern=SHA256_PATTERN)
    idempotency_key: str = Field(min_length=1, max_length=255)
    name: str = Field(min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=64)
    arguments: dict[str, Any] = Field(default_factory=dict)
    normalized_params_hash: str = Field(pattern=SHA256_PATTERN)
    max_result_bytes: int = Field(default=32_000, ge=256, le=1_000_000)
    correlation_id: str | None = Field(default=None, min_length=1, max_length=128)
    traceparent: str | None = None
    tracestate: str | None = Field(default=None, max_length=512)

    @model_validator(mode="after")
    def validate_tool_identity(self) -> "ToolGatewayRequest":
        if self.name not in CONTROLLED_TOOL_NAMES:
            raise ValueError("tool name is not in the controlled gateway catalog")
        if self.version != "v1":
            raise ValueError("controlled gateway tools must use version v1")
        material = json.dumps(
            self.arguments,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        expected_hash = hashlib.sha256(material.encode("utf-8")).hexdigest()
        if self.normalized_params_hash != expected_hash:
            raise ValueError("normalized_params_hash does not match arguments")
        return self


class ToolGatewayResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    request_id: str = Field(min_length=1, max_length=255)
    idempotency_key: str = Field(min_length=1, max_length=255)
    status: Literal["SUCCEEDED", "FAILED"]
    result: Any = None
    result_hash: str | None = Field(default=None, pattern=SHA256_PATTERN)
    error_code: str | None = Field(default=None, min_length=1, max_length=128)
    error_message: str | None = Field(default=None, min_length=1, max_length=1000)
    cached: bool = False
    source_execution_id: str | None = Field(default=None, min_length=1, max_length=255)
    attempts: int = Field(default=1, ge=1)
    duration_ms: int = Field(default=0, ge=0)
    usage: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_status_payload(self) -> "ToolGatewayResult":
        if self.status == "SUCCEEDED" and self.result_hash is None:
            raise ValueError("successful gateway results require result_hash")
        if self.status == "FAILED" and not self.error_code:
            raise ValueError("failed gateway results require error_code")
        return self
