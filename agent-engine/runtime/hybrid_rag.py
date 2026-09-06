from __future__ import annotations

import hashlib
import math
import re
import time
import unicodedata
from collections import Counter, defaultdict
from enum import StrEnum
from typing import Any, Iterable, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from runtime.cache_keys import (
    CacheLayer,
    CacheMode,
    InMemoryResultCache,
    canonical_hash,
    rag_query_cache_key,
)


class CorpusType(StrEnum):
    RESUME = "RESUME"
    QUESTION = "QUESTION"
    RUBRIC = "RUBRIC"
    PROJECT_ARTIFACT = "PROJECT_ARTIFACT"


class RagDocument(BaseModel):
    """A tenant-scoped document stored in exactly one logical corpus."""

    model_config = ConfigDict(extra="forbid")

    document_id: str = Field(min_length=1)
    corpus: CorpusType
    project_id: str | None = Field(default=None, min_length=1)
    owner_user_id: str | None = Field(default=None, min_length=1)
    allowed_user_ids: list[str] = Field(default_factory=list)
    version: str = Field(min_length=1)
    status: str = Field(default="ACTIVE", min_length=1)
    content: str = Field(min_length=1)
    metadata: dict[str, str] = Field(default_factory=dict)
    expires_at_epoch_ms: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def normalize_acl(self) -> "RagDocument":
        self.allowed_user_ids = sorted(set(self.allowed_user_ids))
        self.status = self.status.strip().upper()
        return self


class RetrievalPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_id: str | None = Field(default=None, min_length=1)
    user_id: str | None = Field(default=None, min_length=1)
    allowed_corpora: list[CorpusType] = Field(
        default_factory=lambda: [
            CorpusType.RESUME,
            CorpusType.QUESTION,
            CorpusType.RUBRIC,
            CorpusType.PROJECT_ARTIFACT,
        ]
    )
    top_n: int = Field(default=20, ge=1, le=100)
    top_k: int = Field(default=5, ge=1, le=50)
    max_per_document: int = Field(default=2, ge=1, le=10)
    rrf_constant: int = Field(default=60, ge=1, le=500)
    query_rewrite_version: str = Field(default="query-rewrite-v1", min_length=1)
    embedding_version: str = Field(default="hashing-ngram-v1", min_length=1)
    reranker_version: str = Field(default="deterministic-rerank-v1", min_length=1)
    access_policy_version: str = Field(default="project-user-v1", min_length=1)
    actor_scope_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    corpus_epoch: int = Field(default=1, ge=1)
    cache_mode: Literal["DISABLED", "SHADOW", "ENABLED"] = "DISABLED"

    @model_validator(mode="after")
    def normalize_corpora(self) -> "RetrievalPolicy":
        self.allowed_corpora = list(dict.fromkeys(self.allowed_corpora))
        return self


class QueryRewrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = Field(min_length=1)
    original_length: int = Field(ge=0)
    queries: list[str] = Field(min_length=1)


class RetrievalHit(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str = Field(min_length=1)
    corpus: CorpusType
    version: str = Field(min_length=1)
    metadata: dict[str, str] = Field(default_factory=dict)
    score: float
    lexical_score: float = 0.0
    vector_score: float = 0.0
    rrf_score: float = 0.0
    rank: int = Field(ge=1)


class RetrievalTrace(BaseModel):
    model_config = ConfigDict(extra="forbid")

    retriever_version: str = Field(min_length=1)
    query_rewrite_version: str = Field(min_length=1)
    embedding_version: str = Field(min_length=1)
    reranker_version: str = Field(min_length=1)
    access_policy_version: str = Field(min_length=1)
    candidate_count: int = Field(ge=0)
    eligible_count: int = Field(ge=0)
    filtered_forbidden_count: int = Field(ge=0)
    filtered_expired_count: int = Field(ge=0)
    filtered_inactive_count: int = Field(ge=0)
    hit_count: int = Field(ge=0)
    failure_reason: str | None = None
    corpus_epoch: int = Field(default=1, ge=1)
    cache_key: str | None = None
    cache_mode: Literal["DISABLED", "SHADOW", "ENABLED"] = "DISABLED"
    cache_hit: bool = False
    cache_shadow_match: bool | None = None
    cache_source_execution_id: str | None = None
    cache_source_version: str | None = None
    cache_saved_input_tokens: int = Field(default=0, ge=0)
    cache_saved_output_tokens: int = Field(default=0, ge=0)
    cache_saved_cost: float = Field(default=0.0, ge=0.0)
    cache_invalidation_reason: str | None = None


class RetrievalResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    hits: list[RetrievalHit] = Field(default_factory=list)
    rewrite: QueryRewrite
    trace: RetrievalTrace


_TOKEN = re.compile(r"[\u3400-\u9fff]|[A-Za-z0-9_]+")


class HybridRetriever:
    """Small deterministic hybrid retriever used by fixtures and local runtime.

    Each corpus has its own index map. Retrieval filters authorization and document
    lifecycle before scoring, then combines lexical and vector ranks with RRF and
    applies a bounded deterministic reranker. A production vector provider can
    replace `_embedding` without changing the result or trace contract.
    """

    RETRIEVER_VERSION = "hybrid-rrf-rerank-v1"
    EMBEDDING_DIMENSIONS = 128

    def __init__(
        self,
        documents: Iterable[RagDocument] = (),
        *,
        cache: InMemoryResultCache | None = None,
    ) -> None:
        self._indexes: dict[CorpusType, dict[str, RagDocument]] = {
            corpus: {} for corpus in CorpusType
        }
        self._cache = cache
        for document in documents:
            self.upsert(document)

    def upsert(self, document: RagDocument) -> None:
        self._indexes[document.corpus][document.document_id] = document

    def remove(self, document_id: str, corpus: CorpusType) -> None:
        self._indexes[corpus].pop(document_id, None)

    def retrieve(
        self,
        query: str,
        policy: RetrievalPolicy | None = None,
        *,
        now_epoch_ms: int | None = None,
        source_execution_id: str | None = None,
    ) -> RetrievalResult:
        resolved_policy = policy or RetrievalPolicy()
        cache_key = self._cache_key(query, resolved_policy)
        cached_entry = self._cached_result(cache_key, resolved_policy)
        if cached_entry is not None and resolved_policy.cache_mode == CacheMode.ENABLED:
            cached_result, provenance = cached_entry
            return cached_result.model_copy(
                update={
                    "trace": cached_result.trace.model_copy(
                        update={
                            "cache_key": cache_key,
                            "cache_mode": resolved_policy.cache_mode,
                            "cache_hit": True,
                            "cache_source_execution_id": provenance.source_execution_id,
                            "cache_source_version": provenance.source_version,
                            "cache_saved_input_tokens": provenance.saved_input_tokens,
                            "cache_saved_output_tokens": provenance.saved_output_tokens,
                            "cache_saved_cost": provenance.saved_cost,
                        }
                    )
                }
            )
        rewrite = rewrite_query(query, resolved_policy.query_rewrite_version)
        now = int(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
        eligible: list[RagDocument] = []
        forbidden = expired = inactive = 0
        for corpus in resolved_policy.allowed_corpora:
            for document in self._indexes[corpus].values():
                if not _same_project(document, resolved_policy):
                    forbidden += 1
                    continue
                if not _allowed_user(document, resolved_policy.user_id):
                    forbidden += 1
                    continue
                if document.status != "ACTIVE":
                    inactive += 1
                    continue
                if document.expires_at_epoch_ms is not None and document.expires_at_epoch_ms <= now:
                    expired += 1
                    continue
                eligible.append(document)

        if not eligible:
            return self._finalize_cache(RetrievalResult(
                hits=[],
                rewrite=rewrite,
                trace=self._trace(
                    resolved_policy,
                    candidate_count=0,
                    eligible_count=0,
                    filtered_forbidden_count=forbidden,
                    filtered_expired_count=expired,
                    filtered_inactive_count=inactive,
                    hit_count=0,
                    failure_reason="NO_ELIGIBLE_DOCUMENTS",
                ),
            ), cache_key, resolved_policy, cached_entry, source_execution_id)

        lexical_scores = {
            _document_key(document): _bm25_score(rewrite.queries, document.content, eligible)
            for document in eligible
        }
        vector_scores = {
            _document_key(document): max(
                (_cosine(_embedding(variant), _embedding(document.content)) for variant in rewrite.queries),
                default=0.0,
            )
            for document in eligible
        }
        lexical_rank = _rank(eligible, lexical_scores)
        vector_rank = _rank(eligible, vector_scores)
        candidates = sorted(
            eligible,
            key=lambda document: (
                -(
                    1.0 / (resolved_policy.rrf_constant + lexical_rank[_document_key(document)])
                    + 1.0 / (resolved_policy.rrf_constant + vector_rank[_document_key(document)])
                ),
                document.corpus.value,
                document.document_id,
            ),
        )[: resolved_policy.top_n]

        reranked: list[tuple[RagDocument, float, float]] = []
        for document in candidates:
            document_key = _document_key(document)
            rrf = 1.0 / (resolved_policy.rrf_constant + lexical_rank[document_key])
            rrf += 1.0 / (resolved_policy.rrf_constant + vector_rank[document_key])
            phrase_bonus = _phrase_bonus(rewrite.queries, document.content)
            final_score = (
                rrf * 1_000
                + lexical_scores[document_key] * 0.35
                + max(0.0, vector_scores[document_key]) * 2.0
                + phrase_bonus
            )
            reranked.append((document, final_score, rrf))
        reranked.sort(key=lambda item: (-item[1], item[0].corpus.value, item[0].document_id))

        hits: list[RetrievalHit] = []
        per_document: Counter[str] = Counter()
        for document, score, rrf in reranked:
            document_key = f"{document.corpus.value}:{document.document_id}"
            if per_document[document_key] >= resolved_policy.max_per_document:
                continue
            per_document[document_key] += 1
            hits.append(
                RetrievalHit(
                    document_id=document.document_id,
                    corpus=document.corpus,
                    version=document.version,
                    metadata=dict(document.metadata),
                    score=round(score, 6),
                    lexical_score=round(lexical_scores[_document_key(document)], 6),
                    vector_score=round(vector_scores[_document_key(document)], 6),
                    rrf_score=round(rrf, 6),
                    rank=len(hits) + 1,
                )
            )
            if len(hits) >= resolved_policy.top_k:
                break

        return self._finalize_cache(RetrievalResult(
            hits=hits,
            rewrite=rewrite,
            trace=self._trace(
                resolved_policy,
                candidate_count=len(candidates),
                eligible_count=len(eligible),
                filtered_forbidden_count=forbidden,
                filtered_expired_count=expired,
                filtered_inactive_count=inactive,
                hit_count=len(hits),
                failure_reason="EMPTY_RECALL" if not hits else None,
            ),
        ), cache_key, resolved_policy, cached_entry, source_execution_id)

    def _cache_key(self, query: str, policy: RetrievalPolicy) -> str | None:
        if (
            self._cache is None
            or policy.cache_mode == CacheMode.DISABLED
            or not policy.project_id
            or not policy.actor_scope_hash
        ):
            return None
        policy_hash = canonical_hash(
            policy.model_dump(
                mode="json",
                exclude={"actor_scope_hash", "corpus_epoch", "cache_mode"},
            )
        )
        return rag_query_cache_key(
            policy.project_id,
            policy.actor_scope_hash,
            policy.corpus_epoch,
            canonical_hash(query),
            policy_hash,
            policy.top_k,
        )

    def _cached_result(
        self,
        cache_key: str | None,
        policy: RetrievalPolicy,
    ) -> tuple[RetrievalResult, Any] | None:
        if cache_key is None or policy.cache_mode == CacheMode.DISABLED or self._cache is None:
            return None
        entry = self._cache.get(cache_key)
        if entry is None:
            return None
        try:
            return RetrievalResult.model_validate(entry.value), entry.provenance
        except Exception:
            self._cache.invalidate(key=cache_key, reason="CACHE_SCHEMA_INVALID")
            return None

    def _finalize_cache(
        self,
        result: RetrievalResult,
        cache_key: str | None,
        policy: RetrievalPolicy,
        cached_entry: tuple[RetrievalResult, Any] | None,
        source_execution_id: str | None,
    ) -> RetrievalResult:
        trace_update: dict[str, Any] = {
            "corpus_epoch": policy.corpus_epoch,
            "cache_key": cache_key,
            "cache_mode": policy.cache_mode,
        }
        if cached_entry is not None and policy.cache_mode == CacheMode.SHADOW:
            cached_result, provenance = cached_entry
            trace_update.update(
                {
                    "cache_shadow_match": canonical_hash(cached_result.model_dump(mode="json"))
                    == canonical_hash(result.model_dump(mode="json")),
                    "cache_source_execution_id": provenance.source_execution_id,
                    "cache_source_version": provenance.source_version,
                    "cache_saved_input_tokens": provenance.saved_input_tokens,
                    "cache_saved_output_tokens": provenance.saved_output_tokens,
                    "cache_saved_cost": provenance.saved_cost,
                }
            )
        if (
            self._cache is not None
            and cache_key is not None
            and policy.cache_mode in {CacheMode.SHADOW, CacheMode.ENABLED}
            and result.trace.failure_reason is None
        ):
            self._cache.put_success(
                cache_key,
                result.model_dump(mode="json"),
                layer=CacheLayer.RAG_QUERY,
                source_execution_id=source_execution_id or "retrieval-fixture",
                source_version=f"corpus-epoch:{policy.corpus_epoch}",
            )
        invalidation_reason = self._cache.invalidation_reason(cache_key) if (
            self._cache is not None and cache_key is not None
        ) else None
        if invalidation_reason:
            trace_update["cache_invalidation_reason"] = invalidation_reason
        return result.model_copy(update={"trace": result.trace.model_copy(update=trace_update)})

    def _trace(
        self,
        policy: RetrievalPolicy,
        *,
        candidate_count: int,
        eligible_count: int,
        filtered_forbidden_count: int,
        filtered_expired_count: int,
        filtered_inactive_count: int,
        hit_count: int,
        failure_reason: str | None,
    ) -> RetrievalTrace:
        return RetrievalTrace(
            retriever_version=self.RETRIEVER_VERSION,
            query_rewrite_version=policy.query_rewrite_version,
            embedding_version=policy.embedding_version,
            reranker_version=policy.reranker_version,
            access_policy_version=policy.access_policy_version,
            candidate_count=candidate_count,
            eligible_count=eligible_count,
            filtered_forbidden_count=filtered_forbidden_count,
            filtered_expired_count=filtered_expired_count,
            filtered_inactive_count=filtered_inactive_count,
            hit_count=hit_count,
            failure_reason=failure_reason,
            corpus_epoch=policy.corpus_epoch,
            cache_mode=policy.cache_mode,
        )


def rewrite_query(query: str, version: str = "query-rewrite-v1") -> QueryRewrite:
    normalized = unicodedata.normalize("NFKC", query or "").strip().lower()
    tokens = _tokens(normalized)
    if not tokens:
        return QueryRewrite(version=version, original_length=len(query or ""), queries=[""])
    compact = " ".join(tokens)
    variants = [compact]
    if len(tokens) > 1:
        variants.append(" ".join(dict.fromkeys(tokens)))
    return QueryRewrite(
        version=version,
        original_length=len(query or ""),
        queries=list(dict.fromkeys(variants)),
    )


def _same_project(document: RagDocument, policy: RetrievalPolicy) -> bool:
    return policy.project_id is None or document.project_id == policy.project_id


def _allowed_user(document: RagDocument, user_id: str | None) -> bool:
    if document.owner_user_id is not None:
        if document.owner_user_id == user_id:
            return True
        return user_id is not None and user_id in document.allowed_user_ids
    if not document.allowed_user_ids:
        return True
    return user_id is not None and user_id in document.allowed_user_ids


def _document_key(document: RagDocument) -> str:
    return f"{document.corpus.value}:{document.document_id}"


def _tokens(value: str) -> list[str]:
    return [token for token in _TOKEN.findall(value) if len(token) > 1 or "\u3400" <= token <= "\u9fff"]


def _bm25_score(queries: list[str], content: str, documents: list[RagDocument]) -> float:
    source_tokens = _tokens(content)
    if not source_tokens:
        return 0.0
    source_counts = Counter(source_tokens)
    average_length = sum(len(_tokens(document.content)) for document in documents) / max(1, len(documents))
    score = 0.0
    for query in queries:
        query_counts = Counter(_tokens(query))
        for term, query_frequency in query_counts.items():
            document_frequency = sum(term in _tokens(document.content) for document in documents)
            if document_frequency == 0:
                continue
            idf = math.log(1.0 + (len(documents) - document_frequency + 0.5) / (document_frequency + 0.5))
            frequency = source_counts[term]
            length_norm = 1.0 - 0.75 + 0.75 * len(source_tokens) / max(1.0, average_length)
            score += idf * (frequency * 2.2 / (frequency + 1.2 * length_norm)) * query_frequency
    return score


def _rank(documents: list[RagDocument], scores: dict[str, float]) -> dict[str, int]:
    ordered = sorted(
        documents,
        key=lambda document: (-scores[_document_key(document)], document.corpus.value, document.document_id),
    )
    return {_document_key(document): index + 1 for index, document in enumerate(ordered)}


def _embedding(value: str) -> list[float]:
    vector = [0.0] * HybridRetriever.EMBEDDING_DIMENSIONS
    for token in _tokens(unicodedata.normalize("NFKC", value or "").lower()):
        features = [f"token:{token}"]
        features.extend(
            f"ngram:{token[index:index + width]}"
            for width in (2, 3)
            for index in range(max(0, len(token) - width + 1))
        )
        for feature in features:
            digest = hashlib.sha256(feature.encode("utf-8")).digest()
            index = int.from_bytes(digest[:2], "big") % len(vector)
            sign = 1.0 if digest[2] % 2 == 0 else -1.0
            vector[index] += sign * (1.5 if feature.startswith("token:") else 0.8)
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm for value in vector] if norm else vector


def _cosine(left: list[float], right: list[float]) -> float:
    return max(-1.0, min(1.0, sum(a * b for a, b in zip(left, right))))


def _phrase_bonus(queries: list[str], content: str) -> float:
    normalized = unicodedata.normalize("NFKC", content or "").lower()
    return 1.5 if any(query and query in normalized for query in queries) else 0.0
