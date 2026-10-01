import json
from pathlib import Path

from schemas.workflow_spec import WorkflowSpec


def test_current_candidate_freezes_handlers_and_bounded_flash_budget() -> None:
    path = Path(__file__).parents[1] / "contracts/autospec-pm-schema-repair-v12.workflow.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    nodes = {node["node_id"]: node for node in document["nodes"]}
    assert {key: node["agent_name"] for key, node in nodes.items()} == {
        "product_manager": "ProductManagerAgent_v2",
        "architect": "ArchitectAgent_v3",
        "backend_engineer": "BackendEngineerAgent_v6",
        "frontend_engineer": "FrontendEngineerAgent_v3",
        "reviewer": "ReviewerAgent_v5",
        "evaluator": "EvaluatorAgent_v4",
    }
    for key, node in nodes.items():
        if key == "evaluator":
            continue
        policy = node["model_policy"]
        assert (policy["provider_key"], policy["model_name"], policy["thinking_mode"]) == (
            "deepseek", "deepseek-flash", "disabled",
        )
        assert (policy["input_cost_per_million"], policy["cached_input_cost_per_million"],
                policy["output_cost_per_million"]) == (2, 0.04, 8)
    pm = nodes["product_manager"]
    policy = pm["model_policy"]
    assert policy["max_output_tokens"] == 8000
    assert policy["max_calls"] == 2
    assert policy["structured_output_repair"] == {
        "enabled": True, "max_repairs": 1,
        "error_codes": ["STRUCTURED_OUTPUT_INVALID", "VALIDATION_ERROR"],
    }
    assert nodes["architect"]["model_policy"]["max_output_tokens"] == 8000
    assert nodes["backend_engineer"]["context_policy"]["max_input_tokens"] == 24000
    assert nodes["frontend_engineer"]["context_policy"]["max_input_tokens"] == 24000
    assert nodes["reviewer"]["context_policy"]["max_input_tokens"] == 30000
    assert nodes["reviewer"]["verification_policy"]["rule_profile"] == "spec-full-v2"
    assert nodes["evaluator"]["input_schema"] == "EvaluationInputV3"
    assert policy["max_calls"] * (
        pm["context_policy"]["max_input_tokens"] * policy["input_cost_per_million"]
        + policy["max_output_tokens"] * policy["output_cost_per_million"]
    ) / 1_000_000 == 0.176
