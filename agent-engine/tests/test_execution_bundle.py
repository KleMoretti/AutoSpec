import pytest
from pydantic import ValidationError

from schemas.execution_bundle import ExecutionBundle


def bundle(*, protocol_version: int = 1, nodes: list[dict] | None = None) -> dict:
    return {
        "schema_version": "execution-bundle-v1",
        "bundle_version": "v1",
        "workflow_key": "autospec-v5",
        "workflow_version": "v5",
        "workflow_spec_hash": "a" * 64,
        "protocol_version": protocol_version,
        "runtime": {"max_parallel_nodes": 4, "max_review_rounds": 2},
        "nodes": nodes
        or [
            {
                "node_id": "product_manager",
                "handler_key": "product_manager",
                "handler_version": "v1",
                "input_schema": "WorkflowInput",
                "input_schema_hash": "b" * 64,
                "output_schema": "PrdArtifact",
                "output_schema_hash": "c" * 64,
                "artifact_type": "PRD",
                "prompt_key": "product_manager",
                "prompt_version": "v1",
                "prompt_checksum": "d" * 64,
                "prompt": {
                    "key": "product_manager",
                    "version": "v1",
                    "checksum": "d" * 64,
                    "content": "prompt-v1",
                },
            }
        ],
    }


def test_execution_bundle_requires_prompt_snapshot_to_match_node_metadata() -> None:
    payload = bundle()
    payload["nodes"][0]["prompt"]["checksum"] = "e" * 64

    with pytest.raises(ValidationError, match="checksum does not match"):
        ExecutionBundle.model_validate(payload)


def test_execution_bundle_rejects_duplicate_node_ids() -> None:
    payload = bundle()
    payload["nodes"].append(dict(payload["nodes"][0]))

    with pytest.raises(ValidationError, match="node ids must be unique"):
        ExecutionBundle.model_validate(payload)


def test_protocol_zero_bundle_can_represent_legacy_nodes() -> None:
    payload = bundle(
        protocol_version=0,
        nodes=[
            {
                "node_id": "fixture",
                "handler_key": "FixtureAgent",
                "handler_version": "v1",
            }
        ],
    )

    parsed = ExecutionBundle.model_validate(payload)

    assert parsed.protocol_version == 0
    assert parsed.nodes[0].input_schema is None
