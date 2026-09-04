from __future__ import annotations

import math
from typing import Iterable

from pydantic import BaseModel, ConfigDict, Field

from runtime.hybrid_rag import (
    CorpusType,
    HybridRetriever,
    RagDocument,
    RetrievalPolicy,
)


class RetrievalEvalCase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    query: str = Field(min_length=1)
    project_id: str = Field(min_length=1)
    user_id: str = Field(min_length=1)
    corpus: CorpusType
    gold_document_ids: list[str] = Field(min_length=1)
    top_k: int = Field(default=5, ge=1, le=50)


class RetrievalCaseResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    recall_at_k: float = Field(ge=0.0, le=1.0)
    reciprocal_rank: float = Field(ge=0.0, le=1.0)
    ndcg_at_k: float = Field(ge=0.0, le=1.0)
    rerank_hit: bool
    returned_document_ids: list[str] = Field(default_factory=list)
    failure_reason: str | None = None


class RetrievalEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dataset_version: str = Field(min_length=1)
    recall_at_k: float = Field(ge=0.0, le=1.0)
    mrr: float = Field(ge=0.0, le=1.0)
    ndcg_at_k: float = Field(ge=0.0, le=1.0)
    rerank_hit_rate: float = Field(ge=0.0, le=1.0)
    failure_cases: list[RetrievalCaseResult] = Field(default_factory=list)
    case_results: list[RetrievalCaseResult] = Field(default_factory=list)


RETRIEVAL_DATASET_VERSION = "autospec-rag-eval-v1"


def run_retrieval_evaluation(
    retriever: HybridRetriever,
    cases: Iterable[RetrievalEvalCase],
) -> RetrievalEvaluation:
    results: list[RetrievalCaseResult] = []
    for case in cases:
        result = retriever.retrieve(
            case.query,
            RetrievalPolicy(
                project_id=case.project_id,
                user_id=case.user_id,
                allowed_corpora=[case.corpus],
                top_k=case.top_k,
            ),
        )
        returned = [hit.document_id for hit in result.hits]
        gold = set(case.gold_document_ids)
        relevant_positions = [index + 1 for index, document_id in enumerate(returned) if document_id in gold]
        recall = len(set(returned) & gold) / len(gold)
        reciprocal_rank = 1.0 / relevant_positions[0] if relevant_positions else 0.0
        ndcg = _ndcg(returned, gold, case.top_k)
        failure_reason = result.trace.failure_reason
        if not relevant_positions:
            if result.trace.filtered_expired_count:
                failure_reason = "GOLD_DOCUMENT_EXPIRED_OR_FILTERED"
            elif result.trace.filtered_forbidden_count:
                failure_reason = "GOLD_DOCUMENT_UNAUTHORIZED_OR_FILTERED"
            elif returned:
                failure_reason = "WRONG_RECALL"
            else:
                failure_reason = failure_reason or "EMPTY_RECALL"
        case_result = RetrievalCaseResult(
            case_id=case.case_id,
            recall_at_k=round(recall, 6),
            reciprocal_rank=round(reciprocal_rank, 6),
            ndcg_at_k=round(ndcg, 6),
            rerank_hit=bool(relevant_positions),
            returned_document_ids=returned,
            failure_reason=failure_reason,
        )
        results.append(case_result)
    count = len(results)
    return RetrievalEvaluation(
        dataset_version=RETRIEVAL_DATASET_VERSION,
        recall_at_k=round(sum(item.recall_at_k for item in results) / count, 6) if count else 0.0,
        mrr=round(sum(item.reciprocal_rank for item in results) / count, 6) if count else 0.0,
        ndcg_at_k=round(sum(item.ndcg_at_k for item in results) / count, 6) if count else 0.0,
        rerank_hit_rate=round(sum(item.rerank_hit for item in results) / count, 6) if count else 0.0,
        failure_cases=[item for item in results if item.failure_reason is not None],
        case_results=results,
    )


def fixture_retrieval_evaluation() -> RetrievalEvaluation:
    retriever = HybridRetriever(
        [
            RagDocument(
                document_id="question-api-pagination",
                corpus=CorpusType.QUESTION,
                project_id="fixture",
                version="v1",
                content="Ask about API pagination, cursor stability, and duplicate delivery.",
            ),
            RagDocument(
                document_id="question-review-consistency",
                corpus=CorpusType.QUESTION,
                project_id="fixture",
                version="v1",
                content="Ask about cross-artifact consistency and traceable acceptance criteria.",
            ),
            RagDocument(
                document_id="rubric-contract",
                corpus=CorpusType.RUBRIC,
                project_id="fixture",
                version="v1",
                content="Evaluate schema validity, API coverage, data coverage, and UI coverage.",
            ),
            RagDocument(
                document_id="expired-question",
                corpus=CorpusType.QUESTION,
                project_id="fixture",
                version="v0",
                status="ACTIVE",
                expires_at_epoch_ms=1,
                content="Expired API question that must not be returned.",
            ),
            RagDocument(
                document_id="private-question",
                corpus=CorpusType.QUESTION,
                project_id="fixture",
                version="v1",
                allowed_user_ids=["another-user"],
                content="Private API question that must not cross the user boundary.",
            ),
        ]
    )
    return run_retrieval_evaluation(
        retriever,
        [
            RetrievalEvalCase(
                case_id="question-api",
                query="API pagination duplicate delivery",
                project_id="fixture",
                user_id="fixture-user",
                corpus=CorpusType.QUESTION,
                gold_document_ids=["question-api-pagination"],
            ),
            RetrievalEvalCase(
                case_id="rubric-coverage",
                query="schema API data UI coverage",
                project_id="fixture",
                user_id="fixture-user",
                corpus=CorpusType.RUBRIC,
                gold_document_ids=["rubric-contract"],
            ),
            RetrievalEvalCase(
                case_id="expired-and-private-filter",
                query="private expired API question",
                project_id="fixture",
                user_id="fixture-user",
                corpus=CorpusType.QUESTION,
                gold_document_ids=["expired-question", "private-question"],
            ),
        ],
    )


def _ndcg(returned: list[str], gold: set[str], top_k: int) -> float:
    ranked = returned[:top_k]
    dcg = sum(
        1.0 / math.log2(index + 2)
        for index, document_id in enumerate(ranked)
        if document_id in gold
    )
    ideal = sum(1.0 / math.log2(index + 2) for index in range(min(len(gold), top_k)))
    return dcg / ideal if ideal else 0.0
