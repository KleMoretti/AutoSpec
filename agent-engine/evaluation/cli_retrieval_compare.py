"""Compare hashing retrieval with an explicit local semantic model."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from time import perf_counter

from evaluation.retrieval import (
    RETRIEVAL_DATASET_VERSION,
    load_gold_dataset,
    run_retrieval_evaluation,
)
from runtime.embedding_provider import LocalSentenceTransformerEmbeddingProvider
from runtime.embedding_provider import HashEmbeddingProvider
from runtime.hybrid_rag import HybridRetriever


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model-path",
        default=os.getenv("EMBEDDING_LOCAL_MODEL_PATH", ""),
        help="local sentence-transformers model directory",
    )
    parser.add_argument("--output", type=Path, help="optional JSON report path")
    args = parser.parse_args()
    if not args.model_path:
        raise SystemExit("--model-path or EMBEDDING_LOCAL_MODEL_PATH is required")

    documents, cases = load_gold_dataset(include_quick_regression=False)
    if {case.top_k for case in cases} != {5}:
        raise SystemExit("P3-K3 requires a frozen top_k=5 retrieval dataset")
    provider = LocalSentenceTransformerEmbeddingProvider(model_path=args.model_path)

    hashing_started = perf_counter()
    hashing_report = run_retrieval_evaluation(
        HybridRetriever(documents, embedding_provider=HashEmbeddingProvider()), cases
    )
    hashing_elapsed_ms = round((perf_counter() - hashing_started) * 1000, 3)

    semantic_started = perf_counter()
    semantic_report = run_retrieval_evaluation(
        HybridRetriever(documents, embedding_provider=provider), cases
    )
    semantic_elapsed_ms = round((perf_counter() - semantic_started) * 1000, 3)
    payload = {
        "dataset": {
            "version": RETRIEVAL_DATASET_VERSION,
            "case_count": len(cases),
            "document_count": len(documents),
            "top_k": 5,
        },
        "semantic_model": {
            "provider": "sentence-transformers-local",
            "model_version": provider.model_version,
            "dimensions": provider.dimensions,
        },
        "comparison": {
            "hashing": hashing_report.model_dump(mode="json"),
            "semantic": semantic_report.model_dump(mode="json"),
        },
        "timing": {
            "hashing_elapsed_ms": hashing_elapsed_ms,
            "semantic_elapsed_ms": semantic_elapsed_ms,
            "measurement": "single-process wall-clock smoke; not a production SLA",
        },
        "cost": {
            "hashing": "NOT_APPLICABLE_LOCAL_FIXTURE",
            "semantic": "NOT_APPLICABLE_LOCAL_MODEL",
        },
    }
    serialized = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
