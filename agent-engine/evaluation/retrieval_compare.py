"""Offline comparison report for hashing and semantic retrieval providers."""
from __future__ import annotations

from typing import Any

from evaluation.retrieval import RetrievalEvalCase, RetrievalEvaluation, run_retrieval_evaluation
from runtime.embedding_provider import EmbeddingProvider, HashEmbeddingProvider
from runtime.hybrid_rag import HybridRetriever, RagDocument


def compare_retrievers(
    documents: list[RagDocument],
    cases: list[RetrievalEvalCase],
    *,
    semantic_provider: EmbeddingProvider | None = None,
) -> dict[str, Any]:
    """Return comparable metrics without treating a missing live provider as zero."""

    hashing = run_retrieval_evaluation(
        HybridRetriever(documents, embedding_provider=HashEmbeddingProvider()), cases
    )
    result: dict[str, Any] = {"hashing": hashing.model_dump(mode="json"), "semantic": None}
    if semantic_provider is not None:
        semantic = run_retrieval_evaluation(
            HybridRetriever(documents, embedding_provider=semantic_provider), cases
        )
        result["semantic"] = semantic.model_dump(mode="json")
    else:
        result["semantic_status"] = "NOT_EVALUATED_EXTERNAL_PROVIDER_NOT_CONFIGURED"
    return result
