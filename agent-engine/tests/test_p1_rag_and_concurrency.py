import asyncio

import pytest

from evaluation.retrieval import fixture_retrieval_evaluation
from runtime.concurrency import FairSemaphore
from runtime.hybrid_rag import CorpusType, HybridRetriever, RagDocument, RetrievalPolicy


def test_hybrid_retrieval_keeps_corpus_acl_and_expiry_in_trace() -> None:
    retriever = HybridRetriever(
        [
            RagDocument(
                document_id="owned-question",
                corpus=CorpusType.QUESTION,
                project_id="project-1",
                owner_user_id="user-1",
                version="v1",
                content="cursor pagination and duplicate delivery",
            ),
            RagDocument(
                document_id="private-question",
                corpus=CorpusType.QUESTION,
                project_id="project-1",
                allowed_user_ids=["user-2"],
                version="v1",
                content="cursor pagination private notes",
            ),
            RagDocument(
                document_id="expired-question",
                corpus=CorpusType.QUESTION,
                project_id="project-1",
                version="v1",
                expires_at_epoch_ms=999,
                content="cursor pagination expired notes",
            ),
            RagDocument(
                document_id="other-project",
                corpus=CorpusType.QUESTION,
                project_id="project-2",
                version="v1",
                content="cursor pagination from another project",
            ),
        ]
    )

    result = retriever.retrieve(
        "cursor pagination",
        RetrievalPolicy(
            project_id="project-1",
            user_id="user-1",
            allowed_corpora=[CorpusType.QUESTION],
            top_k=1,
        ),
        now_epoch_ms=1000,
    )

    assert [hit.document_id for hit in result.hits] == ["owned-question"]
    assert result.trace.filtered_forbidden_count == 2
    assert result.trace.filtered_expired_count == 1
    assert result.trace.reranker_version == "deterministic-rerank-v1"
    assert fixture_retrieval_evaluation().rerank_hit_rate > 0


@pytest.mark.asyncio
async def test_fair_semaphore_releases_waiters_in_fifo_order() -> None:
    semaphore = FairSemaphore(1)
    await semaphore.acquire()
    order: list[int] = []

    async def wait_and_record(value: int) -> None:
        async with semaphore.hold():
            order.append(value)
            await asyncio.sleep(0)

    tasks = []
    for value in range(3):
        tasks.append(asyncio.create_task(wait_and_record(value)))
        await asyncio.sleep(0)
    await semaphore.release()
    await asyncio.gather(*tasks)

    assert order == [0, 1, 2]
