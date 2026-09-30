from __future__ import annotations

import hashlib
import json
from pathlib import Path

from schemas.workflow_spec import WorkflowSpec


ENGINE = Path(__file__).resolve().parents[1]
CANDIDATE = ENGINE / "contracts/autospec-pm-schema-repair.workflow.json"
CANDIDATE_V2 = ENGINE / "contracts/autospec-pm-schema-repair-v2.workflow.json"
CANDIDATE_V3 = ENGINE / "contracts/autospec-pm-schema-repair-v3.workflow.json"
CANDIDATE_V4 = ENGINE / "contracts/autospec-pm-schema-repair-v4.workflow.json"
CANDIDATE_V5 = ENGINE / "contracts/autospec-pm-schema-repair-v5.workflow.json"
CANDIDATE_V6 = ENGINE / "contracts/autospec-pm-schema-repair-v6.workflow.json"
CANDIDATE_V7 = ENGINE / "contracts/autospec-pm-schema-repair-v7.workflow.json"
CANDIDATE_V8 = ENGINE / "contracts/autospec-pm-schema-repair-v8.workflow.json"
CANDIDATE_V9 = ENGINE / "contracts/autospec-pm-schema-repair-v9.workflow.json"
CANDIDATE_V10 = ENGINE / "contracts/autospec-pm-schema-repair-v10.workflow.json"


def _document() -> dict:
    document = json.loads(CANDIDATE.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    return document


def test_candidate_freezes_schema_flash_pricing_and_repair_budget() -> None:
    document = _document()
    assert document["version"] == "pm-schema-repair-v1"

    product_manager = next(
        node for node in document["nodes"] if node["node_id"] == "product_manager"
    )
    policy = product_manager["model_policy"]
    assert product_manager["agent_name"] == "ProductManagerAgent_v2"
    assert product_manager["prompt_key"] == "product_manager_schema"
    assert product_manager["prompt_version"] == "v1"
    assert product_manager["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/product_manager_schema_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert product_manager["context_policy"]["prompt_token_reserve"] == 3072
    assert product_manager["timeout_ms"] == 120000
    assert policy["provider_key"] == "deepseek"
    assert policy["model_name"] == "deepseek-flash"
    assert policy["thinking_mode"] == "disabled"
    assert policy["max_output_tokens"] == 8000
    assert policy["max_calls"] == 2
    assert policy["input_cost_per_million"] == 2
    assert policy["cached_input_cost_per_million"] == 0.04
    assert policy["output_cost_per_million"] == 8
    assert policy["structured_output_repair"] == {
        "enabled": True,
        "max_repairs": 1,
        "error_codes": ["STRUCTURED_OUTPUT_INVALID", "VALIDATION_ERROR"],
    }
    assert "MODEL_OUTPUT_LIMIT" not in policy["structured_output_repair"]["error_codes"]

    worst_case_cost = policy["max_calls"] * (
        product_manager["context_policy"]["max_input_tokens"] * policy["input_cost_per_million"]
        + policy["max_output_tokens"] * policy["output_cost_per_million"]
    ) / 1_000_000
    assert worst_case_cost == 0.176


def test_candidate_freezes_flash_model_for_all_remote_nodes() -> None:
    document = _document()
    remote_nodes = [node for node in document["nodes"] if node["node_id"] != "evaluator"]
    assert len(remote_nodes) == 5
    for node in remote_nodes:
        policy = node["model_policy"]
        assert (policy["provider_key"], policy["model_name"], policy["thinking_mode"]) == (
            "deepseek",
            "deepseek-flash",
            "disabled",
        )
        assert policy["input_cost_per_million"] == 2
        assert policy["cached_input_cost_per_million"] == 0.04
        assert policy["output_cost_per_million"] == 8


def test_candidate_v2_freezes_explicit_architect_schema_handler() -> None:
    document = json.loads(CANDIDATE_V2.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    architect = next(node for node in document["nodes"] if node["node_id"] == "architect")
    assert document["version"] == "pm-schema-repair-v2"
    assert architect["agent_name"] == "ArchitectAgent_v3"
    assert architect["prompt_key"] == "architect_schema"
    assert architect["prompt_version"] == "v1"
    assert architect["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/architect_schema_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert architect["context_policy"]["prompt_token_reserve"] == 6000


def test_candidate_v3_freezes_loop_backend_and_schema_frontend_handlers() -> None:
    document = json.loads(CANDIDATE_V3.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    assert document["version"] == "pm-schema-repair-v3"
    backend = next(node for node in document["nodes"] if node["node_id"] == "backend_engineer")
    frontend = next(node for node in document["nodes"] if node["node_id"] == "frontend_engineer")
    assert backend["agent_name"] == "BackendEngineerAgent_v4"
    assert backend["prompt_key"] == "backend_engineer_loop"
    assert backend["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/backend_engineer_loop_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert frontend["agent_name"] == "FrontendEngineerAgent_v3"
    assert frontend["prompt_key"] == "frontend_schema"
    assert frontend["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/frontend_schema_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert frontend["context_policy"]["prompt_token_reserve"] == 2048


def test_candidate_v4_freezes_schema_reviewer_handler() -> None:
    document = json.loads(CANDIDATE_V4.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    reviewer = next(node for node in document["nodes"] if node["node_id"] == "reviewer")
    assert document["version"] == "pm-schema-repair-v4"
    assert reviewer["agent_name"] == "ReviewerAgent_v4"
    assert reviewer["prompt_key"] == "reviewer_schema"
    assert reviewer["prompt_version"] == "v1"
    assert reviewer["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/reviewer_schema_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert reviewer["context_policy"]["prompt_token_reserve"] == 2048


def test_candidate_v5_freezes_phase_safe_backend_loop_prompt() -> None:
    document = json.loads(CANDIDATE_V5.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    backend = next(node for node in document["nodes"] if node["node_id"] == "backend_engineer")
    assert document["version"] == "pm-schema-repair-v5"
    assert backend["agent_name"] == "BackendEngineerAgent_v5"
    assert backend["prompt_key"] == "backend_engineer_loop_v2"
    assert backend["prompt_version"] == "v1"
    assert backend["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/backend_engineer_loop_v2_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()


def test_candidate_v6_freezes_explicit_agent_turn_templates() -> None:
    document = json.loads(CANDIDATE_V6.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    backend = next(node for node in document["nodes"] if node["node_id"] == "backend_engineer")
    assert document["version"] == "pm-schema-repair-v6"
    assert backend["agent_name"] == "BackendEngineerAgent_v6"
    assert backend["prompt_key"] == "backend_engineer_loop_v3"
    assert backend["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/backend_engineer_loop_v3_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()


def test_candidate_v7_raises_architect_output_cap() -> None:
    document = json.loads(CANDIDATE_V7.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    architect = next(node for node in document["nodes"] if node["node_id"] == "architect")
    assert document["version"] == "pm-schema-repair-v7"
    assert architect["agent_name"] == "ArchitectAgent_v3"
    assert architect["model_policy"]["max_output_tokens"] == 8000
    assert architect["context_policy"]["prompt_token_reserve"] == 6000


def test_candidate_v8_expands_downstream_context_budget() -> None:
    document = json.loads(CANDIDATE_V8.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    assert document["version"] == "pm-schema-repair-v8"
    nodes = {node["node_id"]: node for node in document["nodes"]}
    assert nodes["backend_engineer"]["context_policy"]["max_input_tokens"] == 24000
    assert nodes["frontend_engineer"]["context_policy"]["max_input_tokens"] == 24000
    assert nodes["reviewer"]["context_policy"]["max_input_tokens"] == 30000


def test_candidate_v9_uses_domain_neutral_reviewer_profile_and_prompt() -> None:
    document = json.loads(CANDIDATE_V9.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    reviewer = next(node for node in document["nodes"] if node["node_id"] == "reviewer")
    assert document["version"] == "pm-schema-repair-v9"
    assert reviewer["agent_name"] == "ReviewerAgent_v5"
    assert reviewer["prompt_key"] == "reviewer_schema_v2"
    assert reviewer["prompt_checksum"] == hashlib.sha256(
        (ENGINE / "prompts/reviewer_schema_v2_v1.md")
        .read_text(encoding="utf-8")
        .replace("\r\n", "\n")
        .encode("utf-8")
    ).hexdigest()
    assert reviewer["verification_policy"]["rule_profile"] == "spec-full-v1"


def test_candidate_v10_uses_narrow_historical_reuse_gate() -> None:
    document = json.loads(CANDIDATE_V10.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(document)
    reviewer = next(node for node in document["nodes"] if node["node_id"] == "reviewer")
    assert document["version"] == "pm-schema-repair-v10"
    assert reviewer["agent_name"] == "ReviewerAgent_v5"
    assert reviewer["prompt_key"] == "reviewer_schema_v2"
    assert reviewer["verification_policy"]["rule_profile"] == "spec-full-v2"
