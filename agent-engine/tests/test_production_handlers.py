import pytest

from runtime.node_executor import NodeCommand, NodeExecutor
from runtime.production_handlers import build_production_registry


def command(handler_key: str, payload: dict, *, node_id: str) -> NodeCommand:
    return NodeCommand(
        event_id=f"event-{node_id}",
        workflow_run_id=1,
        node_run_id=1,
        node_id=node_id,
        revision=1,
        attempt=1,
        execution_id=f"execution-{node_id}",
        handler_key=handler_key,
        handler_version="v1",
        input_payload=payload,
    )


def test_registry_contains_all_builtin_v5_handlers() -> None:
    registry = build_production_registry()

    for handler_key in [
        "ProductManagerAgent",
        "ArchitectAgent",
        "BackendEngineerAgent",
        "FrontendEngineerAgent",
        "ReviewerAgent",
        "EvaluatorAgent",
    ]:
        assert registry.resolve(handler_key, "v1")
    assert registry.resolve("BackendEngineerAgent", "v2")


@pytest.mark.asyncio
async def test_parallel_engineering_handlers_accept_same_architect_output() -> None:
    executor = NodeExecutor(build_production_registry())
    product = await executor.execute(
        command("ProductManagerAgent", {"requirement": "Build an AutoSpec project workspace"}, node_id="product_manager")
    )
    architect = await executor.execute(
        command(
            "ArchitectAgent",
            {"requirement": "Build an AutoSpec project workspace", "prd": product.output_payload},
            node_id="architect",
        )
    )
    shared = {
        "requirement": "Build an AutoSpec project workspace",
        "prd": product.output_payload,
        "architecture_design": architect.output_payload,
    }

    backend = await executor.execute(
        command("BackendEngineerAgent", shared, node_id="backend_engineer")
    )
    frontend = await executor.execute(
        command("FrontendEngineerAgent", shared, node_id="frontend_engineer")
    )

    assert backend.event_type == "NODE_SUCCEEDED"
    assert frontend.event_type == "NODE_SUCCEEDED"
