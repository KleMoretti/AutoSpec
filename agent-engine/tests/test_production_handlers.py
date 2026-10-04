import pytest

from fixtures.software_domains import get_fixture
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
    assert registry.resolve("BackendEngineerAgent", "v4")
    assert registry.resolve("BackendEngineerAgent", "v5")
    assert registry.resolve("BackendEngineerAgent", "v6")
    assert registry.resolve("BackendEngineerAgent", "v7")
    assert registry.resolve("BackendEngineerAgent", "v9")
    assert registry.resolve("BackendEngineerAgent", "v10")
    assert registry.resolve("FrontendEngineerAgent", "v3")
    assert registry.resolve("FrontendEngineerAgent", "v4")
    assert registry.resolve("FrontendEngineerAgent", "v8")
    assert registry.resolve("FrontendEngineerAgent", "v9")
    assert registry.resolve("FrontendEngineerAgent", "v10")
    assert registry.resolve("ArchitectAgent", "v4")
    assert registry.resolve("ArchitectAgent", "v5")
    assert registry.resolve("ArchitectAgent", "v6")
    assert registry.resolve("ReviewerAgent", "v4")
    assert registry.resolve("ReviewerAgent", "v5")
    assert registry.resolve("EvaluatorAgent", "v4")


@pytest.mark.asyncio
async def test_explicit_handlers_return_explicit_fixture_artifacts() -> None:
    fixture = get_fixture("campus_marketplace")
    executor = NodeExecutor(build_production_registry())
    shared = {
        "requirement": "Build a campus marketplace",
        "prd": fixture.prd.model_dump(mode="json"),
        "architecture_design": fixture.shared_architecture().model_dump(mode="json"),
    }

    backend = await executor.execute(
        command("BackendEngineerAgent", shared, node_id="backend_engineer").model_copy(
            update={"handler_version": "v7"}
        )
    )
    frontend = await executor.execute(
        command("FrontendEngineerAgent", shared, node_id="frontend_engineer").model_copy(
            update={"handler_version": "v4"}
        )
    )

    assert backend.event_type == "NODE_SUCCEEDED", backend.error_message
    assert frontend.event_type == "NODE_SUCCEEDED", frontend.error_message
    assert backend.output_payload["tables"][0]["fields"][0]["primary_key"] is True
    assert backend.output_payload["tables"][0]["fields"][0]["foreign_key"] is None
    assert backend.output_payload["apis"][0]["request_params"][0]["location"] == "body"
    assert backend.output_payload["apis"][0]["response_fields"][0]["nullable"] is False
    assert frontend.output_payload["api_bindings"][0]["parameters"]
    assert frontend.output_payload["api_bindings"][0]["response_fields"]


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

    review_input = {**shared, "backend_design": backend.output_payload, "frontend_skeleton": frontend.output_payload}
    reviewer = await executor.execute(command("ReviewerAgent", review_input, node_id="reviewer"))
    assert reviewer.event_type == "NODE_SUCCEEDED"
    runtime_input = {**review_input, "review_report": reviewer.output_payload,
        "retrieval_policy": {"version": "retrieval-v1", "enabled": True},
        "retrieval_project_id": 1, "retrieval_node_id": "evaluator", "corpus_epoch": 3,
        "actor_scope_hash": "scope", "retrieval_cache_key": "cache",
        "retrieval_cache": {"mode": "SHADOW", "hit": False},
        "retrieval_trace": {"hit_count": 0}, "retrieval_snapshot": {"project_id": 1, "hit_count": 0}}
    old = await executor.execute(command("EvaluatorAgent", runtime_input, node_id="evaluator"))
    assert old.error_code == "VALIDATION_ERROR"  # frozen v1 remains unchanged
    new = await executor.execute(command("EvaluatorAgent", runtime_input, node_id="evaluator").model_copy(
        update={"handler_version": "v2"}))
    assert new.error_code == "QUALITY_GATE_BLOCKED", new.error_message
    assert "RUNTIME_EVIDENCE_MISSING" in new.error_message  # reaches the real evaluator, still fails closed
    candidate = await executor.execute(command("EvaluatorAgent", runtime_input, node_id="evaluator").model_copy(
        update={"handler_version": "v4"}))
    assert candidate.error_code == "QUALITY_GATE_BLOCKED", candidate.error_message
    assert "RUNTIME_EVIDENCE_MISSING" in candidate.error_message
    runtime_input["unknown_business_field"] = "must not be silently accepted"
    rejected = await executor.execute(command("EvaluatorAgent", runtime_input, node_id="evaluator").model_copy(
        update={"handler_version": "v2"}))
    assert rejected.error_code == "VALIDATION_ERROR"


@pytest.mark.asyncio
async def test_architect_schema_handler_selects_shared_contract_output() -> None:
    executor = NodeExecutor(build_production_registry())
    product = await executor.execute(
        command(
            "ProductManagerAgent",
            {"requirement": "Build an inventory workspace"},
            node_id="product_manager",
        )
    )
    architect = await executor.execute(
        command(
            "ArchitectAgent",
            {"requirement": "Build an inventory workspace", "prd": product.output_payload},
            node_id="architect",
        ).model_copy(update={"handler_version": "v3"})
    )

    assert architect.event_type == "NODE_SUCCEEDED"
    assert "shared_contract" in architect.output_payload


@pytest.mark.asyncio
async def test_architect_schema_v5_handler_selects_shared_contract_output() -> None:
    executor = NodeExecutor(build_production_registry())
    product = await executor.execute(
        command(
            "ProductManagerAgent",
            {"requirement": "Build an inventory workspace"},
            node_id="product_manager",
        )
    )
    architect = await executor.execute(
        command(
            "ArchitectAgent",
            {"requirement": "Build an inventory workspace", "prd": product.output_payload},
            node_id="architect",
        ).model_copy(update={"handler_version": "v5"})
    )

    assert architect.event_type == "NODE_SUCCEEDED"
    assert "shared_contract" in architect.output_payload


@pytest.mark.asyncio
async def test_frontend_schema_handler_selects_shared_contract_output() -> None:
    executor = NodeExecutor(build_production_registry())
    product = await executor.execute(
        command(
            "ProductManagerAgent",
            {"requirement": "Build an inventory workspace"},
            node_id="product_manager",
        )
    )
    architect = await executor.execute(
        command(
            "ArchitectAgent",
            {"requirement": "Build an inventory workspace", "prd": product.output_payload},
            node_id="architect",
        ).model_copy(update={"handler_version": "v3"})
    )
    frontend = await executor.execute(
        command(
            "FrontendEngineerAgent",
            {
                "requirement": "Build an inventory workspace",
                "prd": product.output_payload,
                "architecture_design": architect.output_payload,
            },
            node_id="frontend_engineer",
        ).model_copy(update={"handler_version": "v3"})
    )

    assert frontend.event_type == "NODE_SUCCEEDED"
    assert "routes" in frontend.output_payload


@pytest.mark.asyncio
async def test_backend_loop_v6_single_shot_consumes_shared_architecture_contract() -> None:
    executor = NodeExecutor(build_production_registry())
    product = await executor.execute(
        command(
            "ProductManagerAgent",
            {"requirement": "Build an inventory workspace"},
            node_id="product_manager",
        )
    )
    architect = await executor.execute(
        command(
            "ArchitectAgent",
            {"requirement": "Build an inventory workspace", "prd": product.output_payload},
            node_id="architect",
        ).model_copy(update={"handler_version": "v3"})
    )
    backend = await executor.execute(
        command(
            "BackendEngineerAgent",
            {
                "requirement": "Build an inventory workspace",
                "prd": product.output_payload,
                "architecture_design": architect.output_payload,
            },
            node_id="backend_engineer",
        ).model_copy(update={"handler_version": "v6"})
    )

    assert backend.event_type == "NODE_SUCCEEDED", backend.error_message
    assert backend.output_payload is not None


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
