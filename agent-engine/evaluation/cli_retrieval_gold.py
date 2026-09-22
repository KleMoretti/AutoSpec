"""Run the versioned retrieval gold set against the configured embedding provider."""

from __future__ import annotations

import json

from evaluation.retrieval import load_gold_dataset, run_retrieval_evaluation
from runtime.embedding_provider import configured_embedding_provider
from runtime.hybrid_rag import HybridRetriever


def main() -> None:
    documents, cases = load_gold_dataset()
    provider = configured_embedding_provider()
    report = run_retrieval_evaluation(
        HybridRetriever(documents, embedding_provider=provider), cases
    )
    print(json.dumps({
        "embedding_version": provider.model_version,
        **report.model_dump(mode="json"),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
