import pytest

from runtime.context_policy import (
    ContextPolicyError,
    apply_context_policy,
    estimate_tokens,
)


def test_fast_context_policy_compacts_large_inputs_and_records_provenance() -> None:
    requirement = "start-constraint " + ("workflow detail " * 3000) + " final-constraint"
    payload = {
        "requirement": requirement,
        "retrieved_sources": [
            {
                "citation_id": f"source-{index}",
                "content": "approved source content " * 300,
            }
            for index in range(20)
        ],
        "execution_policy": {"quality_profile": "FAST"},
    }

    compacted, manifest = apply_context_policy("product_manager", payload, "FAST")

    assert manifest["policy"] == "product_manager:FAST:v1"
    assert manifest["trimmed"] is True
    assert manifest["final_characters"] < manifest["original_characters"]
    assert manifest["estimated_tokens"] > 0
    assert "$.requirement" in manifest["trimmed_paths"]
    assert compacted["requirement"].startswith("start-constraint")
    assert compacted["requirement"].endswith("final-constraint")
    assert len(compacted["retrieved_sources"]) <= 8
    assert compacted["context_manifest"]["source_hash"] == manifest["source_hash"]


def test_frozen_context_policy_preserves_required_regions_ids_and_rag_quota() -> None:
    requirement = "构建可审计的需求平台，原始约束不得改写。"
    execution_policy = {"quality_profile": "BALANCED", "max_tokens": 50000}
    rework_directive = {"issue_ids": ["ISSUE-7"], "feedback": "保留 API-ORDER"}
    payload = {
        "requirement": requirement,
        "execution_policy": execution_policy,
        "retrieval_policy": "approved-artifacts-only",
        "rework_directive": rework_directive,
        "retrieved_sources": [
            {
                "citation_id": f"source-{index}",
                "content": ("历史批准内容 " * 200) + " REQ-ORDER API-ORDER",
            }
            for index in range(6)
        ],
        "notes": (
            "REQ-ORDER STORY-ORDER AC-ORDER API-ORDER TABLE-ORDER UI-ORDER "
            + "可压缩说明 " * 500
        ),
    }
    policy = {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 1400,
        "prompt_token_reserve": 100,
        "manifest_token_reserve": 512,
        "field_priority": [
            "requirement",
            "execution_policy",
            "rework_directive",
            "retrieved_sources",
            "notes",
        ],
        "required_paths": [
            "$.requirement",
            "$.execution_policy",
            "$.retrieval_policy",
            "$.rework_directive",
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 120,
        "long_text_token_budget": 180,
        "max_single_source_ratio": 0.25,
    }

    compacted, manifest = apply_context_policy(
        "reviewer", payload, "BALANCED", policy
    )

    assert compacted["requirement"] == requirement
    assert compacted["execution_policy"] == execution_policy
    assert compacted["rework_directive"] == rework_directive
    assert list(compacted)[:3] == [
        "requirement",
        "execution_policy",
        "rework_directive",
    ]
    compacted_text = str(compacted)
    for identifier in (
        "REQ-ORDER",
        "STORY-ORDER",
        "AC-ORDER",
        "API-ORDER",
        "TABLE-ORDER",
        "UI-ORDER",
    ):
        assert identifier in compacted_text
    assert manifest["policy_version"] == "context-v2"
    assert manifest["quota_tokens"]["rag_used"] <= 120
    assert manifest["quota_tokens"]["long_text_used"] <= 180
    assert manifest["trimmed"] is True
    assert manifest["source_hashes"]["requirement"]
    assert manifest["output_tokens"] <= 1300


def test_frozen_context_policy_fails_before_call_when_required_region_is_too_large() -> None:
    policy = {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 256,
        "prompt_token_reserve": 64,
        "manifest_token_reserve": 64,
        "field_priority": ["requirement"],
        "required_paths": ["$.requirement"],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 0,
        "long_text_token_budget": 64,
        "max_single_source_ratio": 0.35,
    }

    with pytest.raises(ContextPolicyError, match="Required context exceeds"):
        apply_context_policy(
            "product_manager",
            {"requirement": "不可丢弃的原始需求" * 100},
            "FAST",
            policy,
        )


def test_conservative_multilingual_estimator_counts_cjk_individually() -> None:
    assert estimate_tokens("需求管理平台") == len("需求管理平台")
