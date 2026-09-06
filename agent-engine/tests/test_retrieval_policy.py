from runtime.hybrid_rag import CorpusType, HybridRetriever, RagDocument, RetrievalPolicy
from runtime.retrieval_policy import (
    NodeRetrievalPolicy,
    build_node_retrieval_request,
    freeze_retrieval_snapshot,
)


def test_node_retrieval_request_is_scoped_and_canonical() -> None:
    policy = NodeRetrievalPolicy(
        enabled=True,
        allowed_corpora=("PROJECT_ARTIFACT",),
        top_n=10,
        top_k=2,
    )
    first = build_node_retrieval_request(
        "architect",
        {"requirement": "设计 API", "prd": {"features": ["search"]}},
        policy,
        project_id="project-7",
    )
    second = build_node_retrieval_request(
        "architect",
        {"prd": {"features": ["search"]}, "requirement": "设计 API"},
        policy,
        project_id="project-7",
    )

    assert first.query_hash == second.query_hash
    assert first.policy_hash == second.policy_hash
    assert first.project_id == "project-7"


def test_retrieval_snapshot_hash_changes_when_hits_change() -> None:
    retriever = HybridRetriever(
        [
            RagDocument(
                document_id="artifact-a",
                corpus=CorpusType.PROJECT_ARTIFACT,
                project_id="project-7",
                version="v1",
                content="API search",
            ),
        ]
    )
    policy = NodeRetrievalPolicy(enabled=True, allowed_corpora=("PROJECT_ARTIFACT",))
    request = build_node_retrieval_request(
        "backend_engineer",
        {"requirement": "API search"},
        policy,
        project_id="project-7",
    )
    result = retriever.retrieve(
        "API search",
        RetrievalPolicy(project_id="project-7", allowed_corpora=[CorpusType.PROJECT_ARTIFACT]),
    )
    snapshot = freeze_retrieval_snapshot(request, result)

    assert snapshot.hit_ids == ("artifact-a",)
    assert snapshot.snapshot_hash
