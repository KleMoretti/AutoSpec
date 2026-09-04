from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT_ENGINE = ROOT / "agent-engine"
CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-v5.workflow.json"
CONTRACT_MIGRATION = (
    ROOT
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V86__publish_autospec_v5_harness_h1_contract.sql"
)


def main() -> int:
    canonical = json.loads(CONTRACT.read_text(encoding="utf-8"))
    sql = CONTRACT_MIGRATION.read_text(encoding="utf-8")
    match = re.search(
        r"spec_json\s*=\s*'(\{\s*\"workflow_key\"\s*:\s*\"autospec-v5\".*?\})'"
        r"\s*,\s*content_hash",
        sql,
        re.DOTALL,
    )
    if match is None:
        print("Unable to locate the autospec-v5 JSON update in V86", file=sys.stderr)
        return 1
    seeded = json.loads(match.group(1))
    if seeded != canonical:
        print(
            "autospec-v5 contract drift: update the canonical contract and create a new migration together",
            file=sys.stderr,
        )
        return 1
    sys.path.insert(0, str(AGENT_ENGINE))
    from runtime.production_handlers import build_production_registry

    registry = build_production_registry()
    capability_errors: list[str] = []
    for node in canonical.get("nodes", []):
        agent_name = node["agent_name"]
        marker = agent_name.rfind("_v")
        handler_key = agent_name[:marker] if marker > 0 else agent_name
        handler_version = agent_name[marker + 1 :] if marker > 0 else "v1"
        registration = registry.resolve(handler_key, handler_version)
        comparisons = {
            "input_schema": registration.input_schema,
            "input_schema_hash": registration.input_schema_hash,
            "output_schema": registration.output_schema,
            "output_schema_hash": registration.output_schema_hash,
            "prompt_key": registration.prompt_key,
            "prompt_version": registration.prompt_version,
            "prompt_checksum": registration.prompt_checksum,
        }
        for field, worker_value in comparisons.items():
            if node.get(field) != worker_value:
                capability_errors.append(
                    f"{node['node_id']}.{field}: contract={node.get(field)!r} "
                    f"worker={worker_value!r}"
                )
    if capability_errors:
        print(
            "autospec-v5 worker capability drift:\n" + "\n".join(capability_errors),
            file=sys.stderr,
        )
        return 1
    print("autospec-v5 workflow contract is synchronized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
