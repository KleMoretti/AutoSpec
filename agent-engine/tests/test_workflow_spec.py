import pytest

from graph.workflow_specs import get_workflow_spec
from schemas.workflow_spec import WorkflowSpec


def test_autospec_v4_workflow_spec_declares_evaluator_contract():
    spec = get_workflow_spec("autospec-v4")

    assert isinstance(spec, WorkflowSpec)
    assert spec.workflow_key == "autospec-v4"
    assert spec.version == "v4"
    assert [node.node_id for node in spec.nodes] == [
        "product_manager",
        "human_prd_approval",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    ]
    assert [edge.model_dump() for edge in spec.edges] == [
        {"from_node": "product_manager", "to_node": "human_prd_approval", "edge_type": "NORMAL", "condition": None},
        {"from_node": "human_prd_approval", "to_node": "architect", "edge_type": "NORMAL", "condition": None},
        {"from_node": "architect", "to_node": "backend_engineer", "edge_type": "NORMAL", "condition": None},
        {"from_node": "backend_engineer", "to_node": "frontend_engineer", "edge_type": "NORMAL", "condition": None},
        {"from_node": "frontend_engineer", "to_node": "reviewer", "edge_type": "NORMAL", "condition": None},
        {"from_node": "reviewer", "to_node": "evaluator", "edge_type": "NORMAL", "condition": None},
    ]
    evaluator = spec.node("evaluator")
    assert spec.node("backend_engineer").depends_on == ["human_prd_approval", "architect"]
    assert spec.node("frontend_engineer").depends_on == [
        "human_prd_approval",
        "architect",
        "backend_engineer",
    ]
    assert evaluator.agent_name == "EvaluatorAgent_v1"
    assert evaluator.artifact_type == "EVALUATION_REPORT"
    assert evaluator.input_schema == "EvaluationInput"
    assert evaluator.output_schema == "EvaluationReport"
    assert evaluator.depends_on == ["reviewer"]
    assert evaluator.model_policy.provider_key == "local"
    assert evaluator.retry_policy.max_attempts == 1


def test_workflow_spec_rejects_edges_to_unknown_nodes():
    payload = {
        "workflow_key": "broken",
        "version": "v1",
        "nodes": [
            {
                "node_id": "product_manager",
                "agent_name": "ProductManagerAgent_v1",
                "input_schema": "GenerateRequest",
                "output_schema": "PrdArtifact",
                "artifact_type": "PRD",
                "prompt_key": "product_manager",
                "prompt_version": "v1",
                "model_policy": {
                    "provider_key": "local",
                    "model_name": "deterministic-fixture",
                },
                "retry_policy": {"max_attempts": 1},
                "timeout_ms": 30000,
                "requires_human_approval": False,
                "depends_on": [],
            }
        ],
        "edges": [{"from_node": "product_manager", "to_node": "missing"}],
    }

    with pytest.raises(ValueError, match="unknown node"):
        WorkflowSpec.model_validate(payload)


def test_workflow_spec_rejects_unknown_dependencies():
    payload = {
        "workflow_key": "broken",
        "version": "v1",
        "nodes": [
            {
                "node_id": "reviewer",
                "agent_name": "ReviewerAgent_v1",
                "input_schema": "ReviewInput",
                "output_schema": "ReviewReport",
                "artifact_type": "REVIEW_REPORT",
                "prompt_key": "reviewer",
                "prompt_version": "v1",
                "model_policy": {
                    "provider_key": "local",
                    "model_name": "deterministic-fixture",
                },
                "retry_policy": {"max_attempts": 1},
                "timeout_ms": 30000,
                "requires_human_approval": False,
                "depends_on": ["missing"],
            }
        ],
        "edges": [],
    }

    with pytest.raises(ValueError, match="unknown dependency"):
        WorkflowSpec.model_validate(payload)


def test_autospec_v5_declares_parallel_nodes_and_bounded_rework():
    spec = get_workflow_spec("autospec-v5")

    assert spec.version == "v5"
    assert spec.runtime.max_parallel_nodes == 4
    assert spec.runtime.max_review_rounds == 2
    assert spec.node("product_manager").approval.mode == "AFTER_NODE"
    assert spec.node("backend_engineer").depends_on == ["architect"]
    assert spec.node("frontend_engineer").depends_on == ["architect"]
    rework_edges = [edge for edge in spec.edges if edge.edge_type == "REWORK"]
    assert {edge.to_node for edge in rework_edges} == {
        "architect",
        "backend_engineer",
        "frontend_engineer",
    }


def test_workflow_spec_rejects_ordinary_cycles():
    payload = _minimal_v5_payload()
    payload["nodes"][0]["depends_on"] = ["reviewer"]

    with pytest.raises(ValueError, match="cycle"):
        WorkflowSpec.model_validate(payload)


def test_workflow_spec_rejects_rework_without_review_round_limit():
    payload = _minimal_v5_payload()
    payload["runtime"]["max_review_rounds"] = 0
    payload["edges"].append(
        {
            "from_node": "reviewer",
            "to_node": "product_manager",
            "edge_type": "REWORK",
            "condition": {"path": "$.decision", "operator": "EQ", "value": "REWORK"},
        }
    )

    with pytest.raises(ValueError, match="max_review_rounds"):
        WorkflowSpec.model_validate(payload)


def _minimal_v5_payload():
    common = {
        "agent_name": "FixtureAgent_v1",
        "input_schema": "FixtureInput",
        "output_schema": "FixtureOutput",
        "artifact_type": "FIXTURE",
        "prompt_key": "fixture",
        "prompt_version": "v1",
        "model_policy": {"provider_key": "local", "model_name": "fixture"},
        "retry_policy": {"max_attempts": 1},
    }
    return {
        "workflow_key": "minimal-v5",
        "version": "v5",
        "runtime": {"max_parallel_nodes": 2, "max_review_rounds": 2},
        "nodes": [
            {**common, "node_id": "product_manager", "depends_on": []},
            {**common, "node_id": "reviewer", "depends_on": ["product_manager"]},
        ],
        "edges": [
            {"from_node": "product_manager", "to_node": "reviewer", "edge_type": "NORMAL"}
        ],
    }
