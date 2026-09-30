import pytest

from agents.architect import ArchitectAgent
from agents.backend_engineer import BackendEngineerAgent
from agents.frontend_engineer import FrontendEngineerAgent
from agents.product_manager import ProductManagerAgent
from evaluation.retrieval import load_gold_dataset, run_retrieval_evaluation
from fixtures.software_domains import get_fixture
from review.shared_contract import validate_backend_contract, validate_frontend_contract
from runtime.embedding_provider import HashEmbeddingProvider, OpenAIEmbeddingProvider, configured_embedding_provider
from runtime.context_policy import apply_context_policy
from runtime.hybrid_rag import HybridRetriever
from runtime.production_handlers import build_production_registry
from runtime.node_executor import NodeCommand, NodeExecutor
from schemas.workflow_spec import WorkflowSpec
import json
from pathlib import Path
from types import SimpleNamespace


def test_shared_contract_supports_parallel_backend_and_frontend() -> None:
    requirement = "Campus products: publish, search, favorite and audit"
    prd = ProductManagerAgent().run(requirement)
    architecture = ArchitectAgent().run(requirement, prd, shared_contract_required=True)
    backend = BackendEngineerAgent().run(requirement, prd, architecture, shared_contract_required=True)
    frontend = FrontendEngineerAgent().run(requirement, prd, architecture, shared_contract_required=True)
    validate_backend_contract(architecture, backend)
    validate_frontend_contract(architecture, frontend)
    broken = frontend.model_copy(deep=True)
    broken.api_bindings[0].path = "/api/not-in-shared-contract"
    with pytest.raises(ValueError, match="Shared Contract"):
        validate_frontend_contract(architecture, broken)


def test_shared_contract_allows_backend_detail_and_conventional_table_names() -> None:
    fixture = get_fixture("inventory_management")
    architecture = fixture.shared_architecture()
    architecture.shared_contract.domain_models[0].name = "InventoryItem"
    fixture.backend.apis[0].description += " Reject invalid item details with a typed error."

    validate_backend_contract(architecture, fixture.backend)


def test_canonical_graph_has_real_parallel_branch() -> None:
    path = Path(__file__).parents[1] / "contracts" / "autospec-v5-parallel.workflow.json"
    spec = WorkflowSpec.model_validate(json.loads(path.read_text(encoding="utf-8")))
    nodes = {node.node_id: node for node in spec.nodes}
    assert nodes["backend_engineer"].depends_on == ["architect"]
    assert nodes["frontend_engineer"].depends_on == ["architect"]
    assert set(nodes["reviewer"].depends_on) == {"backend_engineer", "frontend_engineer"}
    assert not any(edge.from_node == "backend_engineer" and edge.to_node == "frontend_engineer" for edge in spec.edges)
    registry = build_production_registry()
    for node in spec.nodes:
        key, version = node.agent_name.rsplit("_", 1)
        assert registry.resolve(key, version)


def test_frozen_frontend_context_preserves_shared_contract() -> None:
    requirement = "Campus products: publish, search, favorite and audit"
    prd = ProductManagerAgent().run(requirement)
    architecture = ArchitectAgent().run(requirement, prd, shared_contract_required=True)
    path = Path(__file__).parents[1] / "contracts" / "autospec-v5-parallel.workflow.json"
    node = next(item for item in json.loads(path.read_text(encoding="utf-8"))["nodes"] if item["node_id"] == "frontend_engineer")
    payload = {"requirement": requirement, "prd": prd.model_dump(mode="json"), "architecture_design": architecture.model_dump(mode="json")}
    compacted, _ = apply_context_policy("frontend_engineer", payload, "BALANCED", node["context_policy"])
    assert compacted["architecture_design"]["shared_contract"] == payload["architecture_design"]["shared_contract"]
    assert "backend_design" not in compacted


def test_gold_retrieval_scores_and_access_boundaries() -> None:
    documents, cases = load_gold_dataset()
    report = run_retrieval_evaluation(
        HybridRetriever(documents, embedding_provider=HashEmbeddingProvider()), cases
    )
    assert report.recall_at_k == 1.0
    assert report.mrr > 0.0
    assert report.ndcg_at_k > 0.0
    assert report.acl_leakage_rate == 0.0
    assert report.expired_document_hit_rate == 0.0


def test_filtered_documents_never_reach_embedding_provider() -> None:
    class RecordingProvider(HashEmbeddingProvider):
        def __init__(self):
            self.seen = []

        def embed(self, texts):
            self.seen.extend(texts)
            return super().embed(texts)

    provider = RecordingProvider()
    documents, cases = load_gold_dataset()
    run_retrieval_evaluation(HybridRetriever(documents, embedding_provider=provider), cases)
    assert not any("private" in text or "obsolete" in text or "secrets" in text for text in provider.seen)


def test_live_embedding_provider_validates_response_and_production_rejects_fixture(monkeypatch) -> None:
    provider = OpenAIEmbeddingProvider(base_url="https://example.test/v1", api_key="test-key", model="test-embedding")
    provider._client = SimpleNamespace(embeddings=SimpleNamespace(create=lambda **_: SimpleNamespace(data=[
        SimpleNamespace(index=1, embedding=[0.0, 1.0]), SimpleNamespace(index=0, embedding=[1.0, 0.0]),
    ])))
    assert provider.embed(["first", "second"]) == [[1.0, 0.0], [0.0, 1.0]]
    provider._client = SimpleNamespace(embeddings=SimpleNamespace(create=lambda **_: SimpleNamespace(data=[
        SimpleNamespace(index=0, embedding=[1.0]), SimpleNamespace(index=1, embedding=[1.0, 0.0]),
    ])))
    with pytest.raises(ValueError, match="dimensions"):
        provider.embed(["first", "second"])
    monkeypatch.setenv("AUTOSPEC_ENV", "production")
    monkeypatch.setenv("AUTOSPEC_EMBEDDING_MODE", "fixture")
    with pytest.raises(ValueError, match="requires"):
        configured_embedding_provider()


@pytest.mark.asyncio
async def test_parallel_handlers_need_no_backend_input_for_frontend() -> None:
    executor = NodeExecutor(build_production_registry())

    async def execute(key, version, node_id, payload):
        return await executor.execute(NodeCommand(
            event_id=f"event-{node_id}", workflow_run_id=1, node_run_id=1,
            node_id=node_id, revision=1, attempt=1, execution_id=f"execution-{node_id}",
            handler_key=key, handler_version=version, input_payload=payload,
        ))

    requirement = "Campus products: publish, search, favorite and audit"
    product = await execute("ProductManagerAgent", "v1", "product_manager", {"requirement": requirement})
    architecture = await execute("ArchitectAgent", "v2", "architect", {"requirement": requirement, "prd": product.output_payload})
    shared = {"requirement": requirement, "prd": product.output_payload, "architecture_design": architecture.output_payload}
    backend = await execute("BackendEngineerAgent", "v3", "backend_engineer", shared)
    frontend = await execute("FrontendEngineerAgent", "v2", "frontend_engineer", shared)
    assert backend.event_type == "NODE_SUCCEEDED", backend.error_message
    assert frontend.event_type == "NODE_SUCCEEDED", frontend.error_message
    assert "backend_design" not in shared
    reviewer = await execute("ReviewerAgent", "v2", "reviewer", {**shared, "backend_design": backend.output_payload, "frontend_skeleton": frontend.output_payload})
    assert reviewer.event_type == "NODE_SUCCEEDED", reviewer.error_message
