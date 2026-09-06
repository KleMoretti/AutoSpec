from __future__ import annotations

import hashlib
import json
from typing import Any, Iterable

from pydantic import BaseModel, ConfigDict, Field, model_validator

from runtime.hybrid_rag import RetrievalResult


class NodeRetrievalPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    version: str = Field(default="retrieval-v1", min_length=1)
    enabled: bool = False
    allowed_corpora: tuple[str, ...] = ()
    top_n: int = Field(default=20, ge=1, le=100)
    top_k: int = Field(default=5, ge=1, le=50)
    max_per_artifact: int = Field(default=2, ge=1, le=10)
    token_budget: int = Field(default=0, ge=0, le=100_000)
    query_template: str = Field(default="node-aware-v1", min_length=1)
    retriever_version: str = Field(default="hybrid-rrf-rerank-v1", min_length=1)
    embedding_version: str = Field(default="hashing-ngram-v1", min_length=1)
    reranker_version: str = Field(default="deterministic-rerank-v1", min_length=1)
    timeout_ms: int = Field(default=5_000, ge=100, le=600_000)

    @model_validator(mode="after")
    def validate_top_k(self) -> "NodeRetrievalPolicy":
        if self.top_k > self.top_n:
            raise ValueError("top_k must not exceed top_n")
        return self


class NodeRetrievalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    node_id: str = Field(min_length=1)
    project_id: str | None = Field(default=None, min_length=1)
    actor_scope_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    query: str = Field(min_length=1)
    query_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    policy_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    policy: NodeRetrievalPolicy


class RetrievalSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    snapshot_version: str = Field(default="retrieval-snapshot-v1", min_length=1)
    query_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    policy_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    hit_ids: tuple[str, ...] = ()
    hit_versions: tuple[str, ...] = ()
    trace: dict[str, Any] = Field(default_factory=dict)
    snapshot_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


def build_node_retrieval_request(
    node_id: str,
    input_payload: dict[str, Any],
    policy: NodeRetrievalPolicy,
    *,
    project_id: str | None = None,
    actor_scope_hash: str | None = None,
) -> NodeRetrievalRequest:
    query = _query_for_node(node_id, input_payload)
    policy_hash = _hash(policy.model_dump(mode="json"))
    return NodeRetrievalRequest(
        node_id=node_id,
        project_id=project_id,
        actor_scope_hash=actor_scope_hash,
        query=query,
        query_hash=_hash(query),
        policy_hash=policy_hash,
        policy=policy,
    )


def freeze_retrieval_snapshot(
    request: NodeRetrievalRequest,
    result: RetrievalResult,
) -> RetrievalSnapshot:
    hit_ids = tuple(hit.document_id for hit in result.hits)
    hit_versions = tuple(hit.version for hit in result.hits)
    trace = result.trace.model_dump(mode="json")
    material = {
        "snapshot_version": "retrieval-snapshot-v1",
        "query_hash": request.query_hash,
        "policy_hash": request.policy_hash,
        "hit_ids": hit_ids,
        "hit_versions": hit_versions,
        "trace": trace,
    }
    return RetrievalSnapshot(
        query_hash=request.query_hash,
        policy_hash=request.policy_hash,
        hit_ids=hit_ids,
        hit_versions=hit_versions,
        trace=trace,
        snapshot_hash=_hash(material),
    )


def _query_for_node(node_id: str, payload: dict[str, Any]) -> str:
    fields = {
        "requirement": payload.get("requirement"),
        "prd": payload.get("prd") if node_id in {"architect", "backend_engineer", "frontend_engineer"} else None,
        "architecture_design": payload.get("architecture_design")
        if node_id in {"backend_engineer", "frontend_engineer", "reviewer", "evaluator"}
        else None,
        "backend_design": payload.get("backend_design")
        if node_id in {"frontend_engineer", "reviewer", "evaluator"}
        else None,
        "frontend_skeleton": payload.get("frontend_skeleton")
        if node_id in {"reviewer", "evaluator"}
        else None,
        "rework_directive": payload.get("rework_directive")
        if node_id in {"architect", "backend_engineer", "frontend_engineer"}
        else None,
    }
    compact = {
        key: value
        for key, value in fields.items()
        if value is not None and value != ""
    }
    query = json.dumps(compact, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return query if query else node_id


def _hash(value: Any) -> str:
    if isinstance(value, str):
        material = value
    else:
        material = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()
