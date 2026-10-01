from __future__ import annotations

import json
from pathlib import Path

from evaluation.freeze_ablation_contracts import GROUPS, READ_ONLY_TOOLS, SPEC_VERIFY_TOOL, freeze_contracts
from schemas.workflow_spec import WorkflowSpec


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts" / "autospec-pm-schema-repair-v12.workflow.json"


def _node(spec: dict, node_id: str) -> dict:
    return next(node for node in spec["nodes"] if node["node_id"] == node_id)


TEST_OUTPUT = ROOT / "target" / "test-freeze-ablation"


def test_freeze_keeps_common_nodes_and_only_changes_backend_knobs() -> None:
    base = json.loads(BASE.read_text(encoding="utf-8"))
    frozen = freeze_contracts(BASE, TEST_OUTPUT, "test")

    assert set(frozen) == {group for group, *_ in GROUPS}
    for item in frozen.values():
        WorkflowSpec.model_validate(item["spec"])
        assert _node(item["spec"], "reviewer")["verification_policy"] == _node(
            base, "reviewer"
        )["verification_policy"]
        assert _node(item["spec"], "reviewer")["tool_policy"] == _node(
            base, "reviewer"
        )["tool_policy"]
        for node_id in {node["node_id"] for node in base["nodes"]} - {"backend_engineer"}:
            assert _node(item["spec"], node_id) == _node(base, node_id)

    expected = {
        "A": (False, False, False, "NONE"),
        "B": (True, False, False, "NONE"),
        "C": (True, True, False, "NONE"),
        "D": (True, True, True, "L1"),
    }
    for group, (loop, tools, verify, level) in expected.items():
        backend = _node(frozen[group]["spec"], "backend_engineer")
        assert backend["agent_loop_policy"]["enabled"] is loop
        assert backend["tool_policy"]["enabled"] is tools
        assert backend["verification_policy"]["enabled"] is verify
        assert backend["verification_policy"]["required_level"] == level
        expected_tools = (READ_ONLY_TOOLS + ([SPEC_VERIFY_TOOL] if verify else [])) if tools else []
        assert backend["tool_policy"]["allowed_tools"] == expected_tools


def test_freeze_does_not_modify_published_base() -> None:
    before = BASE.read_bytes()
    freeze_contracts(BASE, TEST_OUTPUT, "test")
    assert BASE.read_bytes() == before
