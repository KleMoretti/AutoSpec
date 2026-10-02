import json
from pathlib import Path

from schemas.workflow_spec import WorkflowSpec


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
