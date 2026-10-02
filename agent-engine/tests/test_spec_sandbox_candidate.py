import json
from pathlib import Path

from fixtures.software_domains import (
    explicit_backend_for_fixture,
    explicit_frontend_for_fixture,
    get_fixture,
)
from runtime.handler_registry import schema_fingerprint
from runtime.production_handlers import _explicit_contract_required, build_production_registry
from schemas.workflow_spec import WorkflowSpec
from spec_verifier.artifact_adapter import explicit_spec_contract_from_artifacts


ROOT = Path(__file__).resolve().parents[1]


def test_spec_sandbox_is_an_unactivated_l2_candidate() -> None:
    candidate_path = ROOT / "contracts" / "autospec-spec-sandbox.workflow.json"
    current_path = ROOT / "contracts" / "autospec-pm-schema-repair-v12.workflow.json"
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    current = json.loads(current_path.read_text(encoding="utf-8"))

    WorkflowSpec.model_validate(candidate)
    assert candidate["version"] == "spec-sandbox"
    assert candidate["version"] != current["version"]
    assert {node["node_id"] for node in candidate["nodes"]} == {
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    }

    nodes = {node["node_id"]: node for node in candidate["nodes"]}
    assert nodes["backend_engineer"]["verification_policy"]["required_level"] == "L2"
    assert nodes["backend_engineer"]["verification_policy"]["scope"] == "BACKEND"
    assert nodes["reviewer"]["verification_policy"]["required_level"] == "L2"
    assert nodes["reviewer"]["verification_policy"]["scope"] == "FULL"
    for node_id in ("product_manager", "architect", "backend_engineer", "frontend_engineer", "reviewer"):
        policy = nodes[node_id]["model_policy"]
        assert (policy["provider_key"], policy["model_name"], policy["thinking_mode"]) == (
            "deepseek", "deepseek-flash", "disabled"
        )
        assert (policy["input_cost_per_million"], policy["cached_input_cost_per_million"],
                policy["output_cost_per_million"]) == (2, 0.04, 8)

    baseline = (ROOT.parent / "backend" / "src" / "main" / "resources" / "db" / "migration"
                / "V1__autospec_baseline.sql").read_text(encoding="utf-8")
    assert "spec-sandbox" not in baseline


def test_explicit_v2_candidate_selects_registered_handlers_and_contract() -> None:
    candidate_path = ROOT / "contracts" / "autospec-spec-sandbox-explicit-v2.workflow.json"
    current_path = ROOT / "contracts" / "autospec-pm-schema-repair-v12.workflow.json"
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    current = json.loads(current_path.read_text(encoding="utf-8"))
    WorkflowSpec.model_validate(candidate)

    assert candidate["version"] == "spec-sandbox-explicit-v2"
    assert candidate["version"] not in {current["version"], "spec-sandbox"}
    nodes = {node["node_id"]: node for node in candidate["nodes"]}
    backend_policy = nodes["backend_engineer"]["verification_policy"]
    reviewer_policy = nodes["reviewer"]["verification_policy"]
    assert _explicit_contract_required(backend_policy)
    assert _explicit_contract_required(reviewer_policy)
    assert backend_policy["compiler_version"] == "spec-compiler-v2"
    assert reviewer_policy["compiler_version"] == "spec-compiler-v2"
    assert nodes["backend_engineer"]["output_schema"] == "ExplicitBackendDesignArtifact"
    assert nodes["frontend_engineer"]["output_schema"] == "ExplicitFrontendSkeletonArtifact"
    assert nodes["backend_engineer"]["agent_name"] == "BackendEngineerAgent_v7"
    assert nodes["frontend_engineer"]["agent_name"] == "FrontendEngineerAgent_v4"

    registry = build_production_registry()
    for node_id in ("backend_engineer", "frontend_engineer", "reviewer"):
        handler_key, handler_version = nodes[node_id]["agent_name"].rsplit("_", 1)
        registration = registry.resolve(handler_key, handler_version)
        assert nodes[node_id]["output_schema_hash"] == schema_fingerprint(registration.output_model)
        assert nodes[node_id]["prompt_key"] == registration.prompt_key
        assert nodes[node_id]["prompt_checksum"] == registration.prompt_checksum

    fixture = get_fixture("campus_marketplace")
    backend = explicit_backend_for_fixture(fixture)
    frontend = explicit_frontend_for_fixture(fixture, backend)
    contract = explicit_spec_contract_from_artifacts(fixture.prd, backend, frontend)
    assert contract.schema_version == "spec-contract-v2"
