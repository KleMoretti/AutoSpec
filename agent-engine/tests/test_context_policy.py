from runtime.context_policy import apply_context_policy


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
