from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Callable

from pydantic import BaseModel, ConfigDict, Field, model_validator


class CacheLayer(StrEnum):
    RAG_QUERY = "RAG_QUERY"
    NODE_RESULT = "NODE_RESULT"


class CacheMode(StrEnum):
    DISABLED = "DISABLED"
    SHADOW = "SHADOW"
    ENABLED = "ENABLED"


def rag_query_cache_key(
    project_id: str,
    actor_scope_hash: str,
    corpus_epoch: int,
    query_hash: str,
    retrieval_policy_hash: str,
    k: int,
) -> str:
    """Build a tenant- and authorization-scoped RAG cache key."""

    _require_text(project_id, "project_id")
    _require_hash(actor_scope_hash, "actor_scope_hash")
    _require_positive(corpus_epoch, "corpus_epoch")
    _require_hash(query_hash, "query_hash")
    _require_hash(retrieval_policy_hash, "retrieval_policy_hash")
    if not isinstance(k, int) or isinstance(k, bool) or not 1 <= k <= 100:
        raise ValueError("k must be an integer between 1 and 100")
    return _key(
        CacheLayer.RAG_QUERY,
        {
            "project_id": project_id,
            "actor_scope_hash": actor_scope_hash,
            "corpus_epoch": corpus_epoch,
            "query_hash": query_hash,
            "retrieval_policy_hash": retrieval_policy_hash,
            "k": k,
        },
    )


def node_result_cache_key(
    bundle_hash: str,
    canonical_input_hash: str,
    retrieval_snapshot_hash: str,
    tool_policy_hash: str,
    provider_key: str,
    model_name: str,
) -> str:
    """Build the optional validated node-result cache key."""

    for name, value in (
        ("bundle_hash", bundle_hash),
        ("canonical_input_hash", canonical_input_hash),
        ("retrieval_snapshot_hash", retrieval_snapshot_hash),
        ("tool_policy_hash", tool_policy_hash),
    ):
        _require_hash(value, name)
    _require_text(provider_key, "provider_key")
    _require_text(model_name, "model_name")
    return _key(
        CacheLayer.NODE_RESULT,
        {
            "bundle_hash": bundle_hash,
            "canonical_input_hash": canonical_input_hash,
            "retrieval_snapshot_hash": retrieval_snapshot_hash,
            "tool_policy_hash": tool_policy_hash,
            "provider_key": provider_key,
            "model_name": model_name,
        },
    )


class CacheProvenance(BaseModel):
    """Auditable explanation for a cache entry or cache lookup."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    layer: CacheLayer
    key: str = Field(min_length=1)
    hit: bool = False
    source_execution_id: str | None = None
    source_version: str | None = None
    source_snapshot_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    saved_input_tokens: int = Field(default=0, ge=0)
    saved_output_tokens: int = Field(default=0, ge=0)
    saved_cost: float = Field(default=0.0, ge=0.0)
    invalidation_reason: str | None = None
    schema_validated: bool = False
    citation_gate_passed: bool = False
    reviewer_gate_passed: bool = False
    side_effect_free: bool = True
    sensitive_data: bool = False
    result_status: str = "SUCCEEDED"
    shadow_read: bool = False
    created_at_epoch_ms: int = Field(
        default_factory=lambda: round(time.time() * 1000),
        ge=0,
    )

    @model_validator(mode="after")
    def validate_hit(self) -> "CacheProvenance":
        if self.hit and not self.source_execution_id:
            raise ValueError("cache hit must identify its source execution")
        if self.hit and self.result_status != "SUCCEEDED":
            raise ValueError("only successful results may be served from cache")
        if self.hit and (
            not self.schema_validated
            or not self.citation_gate_passed
            or not self.reviewer_gate_passed
            or not self.side_effect_free
            or self.sensitive_data
        ):
            raise ValueError("cache hit is missing required safety gates")
        return self


@dataclass(frozen=True)
class CacheEntry:
    value: Any
    provenance: CacheProvenance
    expires_at_epoch_ms: int | None = None


class InMemoryResultCache:
    """Safe cache adapter for fixture and shadow-read experiments.

    A production adapter can persist the same key and provenance fields without
    changing the safety contract.
    """

    def __init__(self, *, now_ms: Callable[[], int] | None = None) -> None:
        self._entries: dict[str, CacheEntry] = {}
        self._invalidation_reasons: dict[str, str] = {}
        self._now_ms = now_ms or (lambda: round(time.time() * 1000))

    def get(self, key: str) -> CacheEntry | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        if entry.expires_at_epoch_ms is not None and entry.expires_at_epoch_ms <= self._now_ms():
            self._entries.pop(key, None)
            self._invalidation_reasons[key] = "TTL_EXPIRED"
            return None
        return CacheEntry(
            value=entry.value,
            provenance=entry.provenance.model_copy(update={"hit": True}),
            expires_at_epoch_ms=entry.expires_at_epoch_ms,
        )

    def put_success(
        self,
        key: str,
        value: Any,
        *,
        layer: CacheLayer,
        source_execution_id: str,
        source_version: str | None = None,
        source_snapshot_hash: str | None = None,
        saved_input_tokens: int = 0,
        saved_output_tokens: int = 0,
        saved_cost: float = 0.0,
        schema_validated: bool = True,
        citation_gate_passed: bool = True,
        reviewer_gate_passed: bool = True,
        side_effect_free: bool = True,
        sensitive_data: bool = False,
        result_status: str = "SUCCEEDED",
        ttl_ms: int | None = None,
    ) -> CacheProvenance:
        reason = _rejection_reason(
            result_status=result_status,
            schema_validated=schema_validated,
            citation_gate_passed=citation_gate_passed,
            reviewer_gate_passed=reviewer_gate_passed,
            side_effect_free=side_effect_free,
            sensitive_data=sensitive_data,
        )
        if reason is not None:
            provenance = CacheProvenance(
                layer=layer,
                key=key,
                invalidation_reason=reason,
                schema_validated=schema_validated,
                citation_gate_passed=citation_gate_passed,
                reviewer_gate_passed=reviewer_gate_passed,
                side_effect_free=side_effect_free,
                sensitive_data=sensitive_data,
                result_status=result_status,
            )
            self._invalidation_reasons[key] = reason
            return provenance
        if not source_execution_id.strip():
            raise ValueError("source_execution_id is required for a cache entry")
        provenance = CacheProvenance(
            layer=layer,
            key=key,
            source_execution_id=source_execution_id,
            source_version=source_version,
            source_snapshot_hash=source_snapshot_hash,
            saved_input_tokens=saved_input_tokens,
            saved_output_tokens=saved_output_tokens,
            saved_cost=saved_cost,
            schema_validated=schema_validated,
            citation_gate_passed=citation_gate_passed,
            reviewer_gate_passed=reviewer_gate_passed,
            side_effect_free=side_effect_free,
            sensitive_data=sensitive_data,
            result_status=result_status,
        )
        expires_at = None if ttl_ms is None else self._now_ms() + max(1, ttl_ms)
        self._entries[key] = CacheEntry(
            value=value,
            provenance=provenance,
            expires_at_epoch_ms=expires_at,
        )
        self._invalidation_reasons.pop(key, None)
        return provenance

    def invalidate(
        self,
        *,
        key: str | None = None,
        reason: str = "EXPLICIT_INVALIDATION",
    ) -> int:
        if not reason.strip():
            raise ValueError("invalidation reason is required")
        if key is None:
            keys = list(self._entries)
            self._entries.clear()
        else:
            keys = [key] if key in self._entries else []
            self._entries.pop(key, None)
        for invalidated_key in keys:
            self._invalidation_reasons[invalidated_key] = reason
        return len(keys)

    def invalidation_reason(self, key: str) -> str | None:
        return self._invalidation_reasons.get(key)

    def shadow_matches(self, key: str, value: Any) -> bool | None:
        entry = self.get(key)
        if entry is None:
            return None
        return _canonical_json(entry.value) == _canonical_json(value)


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


def _key(layer: CacheLayer, fields: dict[str, Any]) -> str:
    return f"autospec-cache:{layer.value.lower()}:v1:{canonical_hash(fields)}"


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _require_text(value: str, name: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} is required")


def _require_hash(value: str, name: str) -> None:
    if not isinstance(value, str) or len(value) != 64:
        raise ValueError(f"{name} must be a SHA-256 hex digest")
    try:
        int(value, 16)
    except ValueError as error:
        raise ValueError(f"{name} must be a SHA-256 hex digest") from error


def _require_positive(value: int, name: str) -> None:
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ValueError(f"{name} must be a positive integer")


def _rejection_reason(
    *,
    result_status: str,
    schema_validated: bool,
    citation_gate_passed: bool,
    reviewer_gate_passed: bool,
    side_effect_free: bool,
    sensitive_data: bool,
) -> str | None:
    if result_status != "SUCCEEDED":
        return "RESULT_NOT_SUCCESSFUL"
    if not schema_validated:
        return "SCHEMA_GATE_FAILED"
    if not citation_gate_passed:
        return "CITATION_GATE_FAILED"
    if not reviewer_gate_passed:
        return "REVIEWER_GATE_FAILED"
    if not side_effect_free:
        return "SIDE_EFFECT_RESULT"
    if sensitive_data:
        return "SENSITIVE_RESULT"
    return None
