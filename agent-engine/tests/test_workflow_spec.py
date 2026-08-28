from graph.workflow_specs import CURRENT_WORKFLOW_KEY, get_current_workflow_spec


def test_current_workflow_contract_contains_the_complete_product_pipeline() -> None:
    spec = get_current_workflow_spec()

    assert spec.workflow_key == CURRENT_WORKFLOW_KEY
    assert spec.version == "v5"
    assert {node.node_id for node in spec.nodes} == {
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    }
    assert any(edge.from_node == "architect" and edge.to_node == "backend_engineer" for edge in spec.edges)
    assert any(edge.from_node == "architect" and edge.to_node == "frontend_engineer" for edge in spec.edges)
