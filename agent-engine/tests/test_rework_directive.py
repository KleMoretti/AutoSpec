from typing import Any, Mapping

from agents.architect import ArchitectAgent
from agents.product_manager import ProductManagerAgent
from runtime.context_policy import apply_context_policy
from runtime.production_handlers import build_production_registry
from schemas.prd import PrdArtifact
from schemas.rework import ReworkDirective


def directive(required_change: str) -> dict[str, Any]:
    return {
        "review_round": 1,
        "target_node": "architect",
        "reviewer_output_hash": "a" * 64,
        "reviewer_node_id": "reviewer",
        "reviewer_node_run_id": 21,
        "reviewer_revision": 1,
        "reviewer_execution_id": "run:reviewer:1:1",
        "issue_ids": ["ISS-BOUNDARY"],
        "required_changes": [required_change],
        "issues": [
            {
                "issue_id": "ISS-BOUNDARY",
                "artifact_path": "architecture.modules[0]",
                "evidence": ["REQ-BOUNDARY"],
            }
        ],
        "evidence_paths": ["architecture.modules[0]"],
        "preservation_policy": {
            "preserve_unaffected_stable_ids": True,
            "preserve_unaffected_approved_decisions": True,
            "preserved_node_ids": ["product_manager"],
        },
        "allowed_change_scope": {
            "mode": "ISSUE_SCOPED",
            "issue_ids": ["ISS-BOUNDARY"],
            "artifact_paths": ["architecture.modules[0]"],
        },
        "invalidated_downstream_node_ids": [
            "backend_engineer",
            "frontend_engineer",
            "reviewer",
            "evaluator",
        ],
        "previous_artifact": {
            "node_run_id": 11,
            "revision": 1,
            "artifact_id": 31,
            "artifact_version": 1,
            "content_hash": "b" * 64,
        },
        "reviewer_artifact": {
            "artifact_id": 41,
            "artifact_version": 1,
            "content_hash": "c" * 64,
        },
        "directive_hash": "d" * 64,
    }


def test_rework_directive_is_never_compacted_and_changes_normalized_input_hash() -> None:
    base = {
        "requirement": "Build a project workspace",
        "prd": {"large": "value " * 5_000},
    }
    first = directive("Split the module boundary")
    second = directive("Keep the module but add an explicit port")

    compacted_first, first_manifest = apply_context_policy(
        "architect", {**base, "rework_directive": first}, "FAST"
    )
    compacted_second, second_manifest = apply_context_policy(
        "architect", {**base, "rework_directive": second}, "FAST"
    )

    assert compacted_first["rework_directive"] == first
    assert compacted_second["rework_directive"] == second
    assert "rework_directive" in first_manifest["preserved_fields"]
    assert "$.rework_directive" not in first_manifest["trimmed_paths"]
    assert first_manifest["source_hash"] != second_manifest["source_hash"]


def test_architect_model_receives_the_trusted_rework_directive() -> None:
    requirement = "Build a project workspace"
    prd = ProductManagerAgent().run(requirement)
    model_client = CapturingArchitectModelClient()
    registration = build_production_registry(model_client).resolve(
        "ArchitectAgent", "v1"
    )
    expected = directive("Split the module boundary")
    validated = registration.input_model.model_validate(
        {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "rework_directive": expected,
            "execution_policy": {"quality_profile": "FAST"},
        }
    )

    registration.handler(validated)

    assert model_client.input_payload is not None
    assert model_client.input_payload["rework_directive"] == ReworkDirective.model_validate(
        expected
    ).model_dump(mode="json")
    assert "rework_directive" in model_client.input_payload["context_manifest"][
        "preserved_fields"
    ]


class CapturingArchitectModelClient:
    provider_key = "test"
    model_name = "capturing-architect"

    def __init__(self) -> None:
        self.input_payload: Mapping[str, Any] | None = None

    def generate_json(
        self,
        prompt_name: str,
        input_payload: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        assert prompt_name == "ArchitectAgent_v1"
        self.input_payload = input_payload
        return ArchitectAgent().run(
            str(input_payload["requirement"]),
            PrdArtifact.model_validate(input_payload["prd"]),
            retrieved_sources=list(input_payload.get("retrieved_sources", [])),
            context_manifest=dict(input_payload.get("context_manifest", {})),
        ).model_dump()
