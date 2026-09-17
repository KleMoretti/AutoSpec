import json
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from model_gateway import OpenAICompatibleModelClient
from runtime.context_policy import estimate_tokens
from runtime.node_executor import NodeCommand, NodeExecutor, contract_fingerprint
from runtime.production_handlers import build_production_registry
from schemas.prd import PrdArtifact


ENGINE = Path(__file__).resolve().parents[1]


def frozen_command() -> NodeCommand:
    document = json.loads((ENGINE / "contracts/autospec-v5-agent-execution-v6-d.workflow.json").read_text())
    node = next(item for item in document["nodes"] if item["node_id"] == "product_manager")
    policy, context = node["model_policy"], node["context_policy"]
    calls = policy["max_calls"]
    value = NodeCommand(
        **{key: value for key, value in node.items() if key in NodeCommand.model_fields},
        event_id="prd-schema-event", workflow_run_id=1, node_run_id=1,
        revision=1, attempt=1, execution_id="prd-schema-test",
        handler_key="ProductManagerAgent", handler_version="v2", protocol_version=2,
        contract_hash="a" * 64, deadline_epoch_ms=round(time.time() * 1000) + 60000,
        input_payload={"requirement": "Allow warehouse operators to create stock items."},
        budget_reservation={
            "reservation_id": "prd-schema-test", "input_tokens": calls * context["max_input_tokens"],
            "output_tokens": calls * policy["max_output_tokens"], "model_calls": calls,
            "estimated_cost": calls * (context["max_input_tokens"] * policy["input_cost_per_million"]
                                      + policy["max_output_tokens"] * policy["output_cost_per_million"]) / 1_000_000,
        },
    )
    return value.model_copy(update={"contract_hash": contract_fingerprint(value)})


def test_versioned_prompt_contains_exact_prd_schema_and_fits_frozen_reserve() -> None:
    prompt = (ENGINE / "prompts/product_manager_schema_v1.md").read_text(encoding="utf-8")
    embedded_schema = json.loads(prompt.split("```json\n", 1)[1].split("\n```", 1)[0])
    assert embedded_schema == PrdArtifact.model_json_schema()
    assert "acceptance_criteria" not in embedded_schema["properties"]
    assert "acceptance_criteria" in embedded_schema["$defs"]["UserStory"]["properties"]
    assert estimate_tokens(prompt) <= frozen_command().context_policy["prompt_token_reserve"]


@pytest.mark.asyncio
@pytest.mark.parametrize("reply", ["valid", "root_criteria", "truncated"])
async def test_frozen_product_manager_preserves_failure_category_and_paid_usage(reply: str) -> None:
    requests = []
    criterion = {"acceptance_id": "AC-STOCK-CREATE", "criterion": "Created item can be retrieved.",
                 "requirement_refs": ["REQ-STOCK-CREATE"]}
    output = {
        "project_name": "Stock", "target_users": ["operator"],
        "core_features": [{"requirement_id": "REQ-STOCK-CREATE", "name": "Create stock item",
                           "description": "Store a stock item", "priority": "MUST"}],
        "user_stories": [{"story_id": "STORY-STOCK-CREATE", "role": "operator", "goal": "create an item",
                          "benefit": "track stock", "requirement_refs": ["REQ-STOCK-CREATE"],
                          "acceptance_criteria": [criterion]}],
    }
    if reply == "root_criteria":
        output["acceptance_criteria"] = output["user_stories"][0].pop("acceptance_criteria")

    class Completions:
        def create(self, **kwargs):
            requests.append(kwargs)
            return SimpleNamespace(
                usage=SimpleNamespace(prompt_tokens=2500, completion_tokens=4000 if reply == "truncated" else 300),
                choices=[SimpleNamespace(finish_reason="length" if reply == "truncated" else "stop",
                                         message=SimpleNamespace(content=None if reply == "truncated" else json.dumps(output)))],
            )

    gateway = OpenAICompatibleModelClient(
        api_key="test-key", base_url="https://model.invalid", provider_key="deepseek", model_name="deepseek-v4-flash",
        client=SimpleNamespace(chat=SimpleNamespace(completions=Completions())),
    )
    event = await NodeExecutor(build_production_registry(gateway)).execute(frozen_command())
    assert len(requests) == 1
    assert requests[0]["extra_body"] == {"thinking": {"type": "disabled"}}
    assert requests[0]["messages"][0]["content"].startswith("# ProductManagerAgent_v2")
    assert event.model_call_count == 1
    assert event.input_tokens == 2500
    assert event.estimated_cost > 0
    assert event.call_records[0].prompt_key == "product_manager_schema"
    if reply == "valid":
        assert event.event_type == "NODE_SUCCEEDED"
        assert event.output_payload["user_stories"][0]["acceptance_criteria"] == [criterion]
    else:
        assert event.event_type == "NODE_FAILED"
        assert event.output_payload is None
        expected = "VALIDATION_ERROR" if reply == "root_criteria" else "MODEL_OUTPUT_LIMIT"
        assert event.error_code == expected
        if reply == "root_criteria":
            assert "acceptance_criteria" in event.error_message
        else:
            assert event.call_records[0].error_code == expected
            assert event.output_tokens == 4000
