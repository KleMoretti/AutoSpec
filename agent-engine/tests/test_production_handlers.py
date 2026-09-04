import pytest

from runtime.context_policy import ContextPolicyError
from runtime.node_executor import NodeCommand, NodeExecutor
from runtime.production_handlers import (
    _validate_artifact_context,
    build_production_registry,
)


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


@pytest.mark.asyncio
async def test_frontend_handler_consumes_backend_contract_output() -> None:
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
        command(
            "FrontendEngineerAgent",
            {**shared, "backend_design": backend.output_payload},
            node_id="frontend_engineer",
        )
    )

    assert backend.event_type == "NODE_SUCCEEDED"
    assert frontend.event_type == "NODE_SUCCEEDED"
    requirement_ids = {
        feature["requirement_id"]
        for feature in product.output_payload["core_features"]
    }
    assert _requirement_refs(architect.output_payload) <= requirement_ids
    assert _requirement_refs(backend.output_payload) <= requirement_ids
    assert _requirement_refs(frontend.output_payload) <= requirement_ids


def _requirement_refs(value: object) -> set[str]:
    if isinstance(value, dict):
        direct = set(value.get("requirement_refs", []))
        return direct | set().union(*(_requirement_refs(child) for child in value.values()))
    if isinstance(value, list):
        return set().union(*(_requirement_refs(child) for child in value))
    return set()


def test_compacted_context_revalidates_cross_artifact_api_references() -> None:
    payload = {
        "prd": {
            "project_name": "Orders",
            "target_users": ["operator"],
            "core_features": [
                {
                    "requirement_id": "REQ-ORDER",
                    "name": "Orders",
                    "description": "Manage orders",
                    "priority": "MUST",
                }
            ],
            "user_stories": [
                {
                    "story_id": "STORY-ORDER",
                    "role": "operator",
                    "goal": "manage orders",
                    "benefit": "complete work",
                    "requirement_refs": ["REQ-ORDER"],
                    "acceptance_criteria": [
                        {
                            "acceptance_id": "AC-ORDER",
                            "criterion": "order is persisted",
                            "requirement_refs": ["REQ-ORDER"],
                        }
                    ],
                }
            ],
        },
        "backend_design": {
            "tables": [
                {
                    "table_id": "TABLE-ORDER",
                    "name": "orders",
                    "description": "orders",
                    "requirement_refs": ["REQ-ORDER"],
                    "fields": [
                        {
                            "field_id": "FIELD-ORDER",
                            "name": "id",
                            "type": "string",
                            "nullable": False,
                            "description": "identifier",
                            "requirement_refs": ["REQ-ORDER"],
                        }
                    ],
                }
            ],
            "apis": [
                {
                    "api_id": "API-ORDER",
                    "method": "GET",
                    "path": "/orders",
                    "description": "list orders",
                    "auth_required": True,
                    "required_roles": ["EDITOR"],
                    "requirement_refs": ["REQ-ORDER"],
                }
            ],
        },
        "frontend_skeleton": {
            "routes": [
                {
                    "route_id": "ROUTE-ORDER",
                    "path": "/orders",
                    "page": "Orders",
                    "requirement_refs": ["REQ-ORDER"],
                }
            ],
            "pages": [
                {
                    "page_id": "PAGE-ORDER",
                    "name": "Orders",
                    "purpose": "manage orders",
                    "components": ["OrderTable"],
                    "requirement_refs": ["REQ-ORDER"],
                }
            ],
            "components": [
                {
                    "component_id": "COMP-ORDER",
                    "name": "OrderTable",
                    "type": "table",
                    "requirement_refs": ["REQ-ORDER"],
                }
            ],
            "api_bindings": [
                {
                    "binding_id": "BIND-ORDER",
                    "method": "GET",
                    "path": "/orders",
                    "consumer": "OrderTable",
                    "backend_api_id": "API-UNKNOWN",
                    "requirement_refs": ["REQ-ORDER"],
                }
            ],
        },
    }

    with pytest.raises(ContextPolicyError, match="unknown backend APIs"):
        _validate_artifact_context(payload)
