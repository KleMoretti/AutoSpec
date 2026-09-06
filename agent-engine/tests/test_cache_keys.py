from runtime.cache_keys import (
    CacheLayer,
    InMemoryResultCache,
    canonical_hash,
    node_result_cache_key,
    rag_query_cache_key,
)
from runtime.hybrid_rag import CorpusType, HybridRetriever, RagDocument, RetrievalPolicy


def _hash(value: str) -> str:
    return canonical_hash(value)


def test_cache_keys_change_for_project_scope_epoch_and_policy() -> None:
    base = rag_query_cache_key("project-1", _hash("owner"), 1, _hash("api"), _hash("policy"), 5)

    assert base != rag_query_cache_key("project-2", _hash("owner"), 1, _hash("api"), _hash("policy"), 5)
    assert base != rag_query_cache_key("project-1", _hash("viewer"), 1, _hash("api"), _hash("policy"), 5)
    assert base != rag_query_cache_key("project-1", _hash("owner"), 2, _hash("api"), _hash("policy"), 5)
    assert base != rag_query_cache_key("project-1", _hash("owner"), 1, _hash("api"), _hash("other-policy"), 5)
    assert base.startswith("autospec-cache:rag_query:v1:")


def test_node_result_key_includes_all_execution_facts() -> None:
    values = [_hash(value) for value in ("bundle", "input", "snapshot", "tools")]
    base = node_result_cache_key(*values, "deepseek", "deepseek-v4-flash")

    assert base != node_result_cache_key(*values, "deepseek", "deepseek-v4-pro")
    assert base.startswith("autospec-cache:node_result:v1:")


def test_cache_refuses_failures_and_exposes_invalidation_reason() -> None:
    cache = InMemoryResultCache()
    key = rag_query_cache_key("project-1", _hash("owner"), 1, _hash("api"), _hash("policy"), 5)

    provenance = cache.put_success(
        key,
        {"error": "provider unavailable"},
        layer=CacheLayer.RAG_QUERY,
        source_execution_id="exec-1",
        result_status="FAILED",
    )

    assert provenance.invalidation_reason == "RESULT_NOT_SUCCESSFUL"
    assert cache.get(key) is None
    assert cache.invalidation_reason(key) == "RESULT_NOT_SUCCESSFUL"


def test_rag_cache_shadow_then_enabled_hit_preserves_source_provenance() -> None:
    cache = InMemoryResultCache()
    retriever = HybridRetriever(
        [
            RagDocument(
                document_id="artifact-1",
                corpus=CorpusType.PROJECT_ARTIFACT,
                project_id="project-1",
                version="v1",
                content="API search contract",
            )
        ],
        cache=cache,
    )
    policy = RetrievalPolicy(
        project_id="project-1",
        user_id="user-1",
        actor_scope_hash=_hash("project-1:user-1:EDITOR"),
        corpus_epoch=7,
        allowed_corpora=[CorpusType.PROJECT_ARTIFACT],
        cache_mode="SHADOW",
    )

    shadow = retriever.retrieve("API search", policy, source_execution_id="exec-1")
    assert shadow.trace.cache_hit is False
    assert shadow.trace.cache_key

    compared = retriever.retrieve("API search", policy, source_execution_id="exec-2")
    assert compared.trace.cache_shadow_match is True
    assert compared.trace.cache_source_execution_id == "exec-1"

    enabled = retriever.retrieve(
        "API search",
        policy.model_copy(update={"cache_mode": "ENABLED"}),
        source_execution_id="exec-3",
    )
    assert enabled.trace.cache_hit is True
    assert enabled.trace.cache_source_execution_id == "exec-2"
    assert [hit.document_id for hit in enabled.hits] == ["artifact-1"]
