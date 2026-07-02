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
        {"from_node": "product_manager", "to_node": "human_prd_approval"},
        {"from_node": "human_prd_approval", "to_node": "architect"},
        {"from_node": "architect", "to_node": "backend_engineer"},
        {"from_node": "backend_engineer", "to_node": "frontend_engineer"},
        {"from_node": "frontend_engineer", "to_node": "reviewer"},
        {"from_node": "reviewer", "to_node": "evaluator"},
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
