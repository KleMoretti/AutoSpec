"""Compare the two explicit tool-call wire protocols on one controlled intent set.

This is a deterministic protocol/allowlist harness. It does not call a model and
cannot be used as evidence of provider quality, token savings, or live accuracy.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from runtime.model_protocol import normalize_native_tool_call
from schemas.agent_loop import parse_agent_turn


DATASET_PATH = Path(__file__).parent / "datasets" / "function_calling_gold_v1.json"
ALLOWED_TOOLS = {
    ("contract.lookup", "v1"),
    ("knowledge.search", "v1"),
}


def _alias(name: str, version: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", f"{name}__{version}")


def _load_cases() -> tuple[str, list[dict[str, Any]]]:
    payload = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    return str(payload["dataset_version"]), list(payload["cases"])


def _validate_turn(turn: Any) -> None:
    if turn.turn_type != "TOOL_CALL":
        return
    if (turn.name, turn.version) not in ALLOWED_TOOLS:
        raise ValueError("TOOL_NOT_ALLOWED")


def _parse_json_in_prompt(case: dict[str, Any]) -> Any:
    if case["expected"] == "REJECT" and case["case_id"] == "malformed-arguments-rejected":
        payload = {
            "type": "TOOL_CALL",
            "name": case["name"],
            "version": case["version"],
            "arguments": case["arguments"],
            "reason": "fixture",
        }
    elif case["expected"] == "REJECT":
        payload = {
            "type": "TOOL_CALL",
            "name": case["name"],
            "version": case["version"],
            "arguments": case["arguments"],
            "reason": "fixture",
        }
    elif case["expected"] == "FINAL_CANDIDATE":
        payload = {"type": "FINAL_CANDIDATE", "candidate": {}, "reason": "fixture"}
    else:
        payload = {
            "type": "TOOL_CALL",
            "name": case["name"],
            "version": case["version"],
            "arguments": case["arguments"],
            "reason": "fixture",
        }
    turn = parse_agent_turn(payload)
    _validate_turn(turn)
    return turn


def _parse_native(case: dict[str, Any]) -> Any:
    if case["expected"] == "FINAL_CANDIDATE":
        return parse_agent_turn({"type": "FINAL_CANDIDATE", "candidate": {}, "reason": "fixture"})
    if case["case_id"] == "malformed-arguments-rejected":
        raise ValueError("NATIVE_ARGUMENTS_INVALID")
    name = case["name"]
    if case["case_id"] == "unknown-tool-rejected":
        name = "bundle.verify"
    turn = parse_agent_turn(normalize_native_tool_call({
        "protocol": "NATIVE_TOOL_CALL",
        "id": f"fixture-{case['case_id']}",
        "name": name,
        "version": case["version"],
        "arguments": case["arguments"],
    }))
    _validate_turn(turn)
    return turn


def _evaluate(case: dict[str, Any], parser) -> dict[str, Any]:
    try:
        turn = parser(case)
    except (TypeError, ValueError) as error:
        return {"status": "REJECTED", "error": str(error)}
    if case["expected"] == "REJECT":
        return {"status": "ACCEPTED_UNEXPECTEDLY", "turn_type": turn.turn_type}
    if turn.turn_type != case["expected"]:
        return {"status": "WRONG_TURN_TYPE", "turn_type": turn.turn_type}
    if turn.turn_type == "TOOL_CALL":
        matches = (
            turn.name == case["name"]
            and turn.version == case["version"]
            and turn.arguments == case["arguments"]
        )
        return {"status": "PASSED" if matches else "ARGUMENT_OR_TOOL_MISMATCH", "turn_type": turn.turn_type}
    return {"status": "PASSED", "turn_type": turn.turn_type}


def build_comparison() -> dict[str, Any]:
    dataset_version, cases = _load_cases()
    reports: dict[str, dict[str, Any]] = {}
    for protocol, parser in (
        ("JSON_IN_PROMPT", _parse_json_in_prompt),
        ("NATIVE_TOOL_CALL", _parse_native),
    ):
        results = [{"case_id": case["case_id"], **_evaluate(case, parser)} for case in cases]
        reports[protocol] = {
            "case_count": len(results),
            "passed": sum(result["status"] == "PASSED" for result in results),
            "rejected": sum(result["status"] == "REJECTED" for result in results),
            "unexpected_accepts": sum(result["status"] == "ACCEPTED_UNEXPECTEDLY" for result in results),
            "results": results,
        }
    differences = []
    json_by_case = {item["case_id"]: item for item in reports["JSON_IN_PROMPT"]["results"]}
    native_by_case = {item["case_id"]: item for item in reports["NATIVE_TOOL_CALL"]["results"]}
    for case_id in sorted(json_by_case):
        if json_by_case[case_id]["status"] != native_by_case[case_id]["status"]:
            differences.append({
                "case_id": case_id,
                "json_in_prompt": json_by_case[case_id]["status"],
                "native_tool_call": native_by_case[case_id]["status"],
            })
    return {
        "dataset_version": dataset_version,
        "execution_mode": "FIXTURE_PROTOCOL_ADAPTER",
        "provider_quality": "NOT_MEASURED",
        "protocols": reports,
        "paired_differences": differences,
        "limitations": [
            "No provider or model was called.",
            "This compares parser, allowlist, and rejection semantics only; it does not measure tool selection quality, tokens, cost, or latency.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = build_comparison()
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
