from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT_ENGINE = ROOT / "agent-engine"
CURRENT = AGENT_ENGINE / "contracts/autospec-pm-schema-repair-v12.workflow.json"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"


def main() -> int:
    current = json.loads(CURRENT.read_text(encoding="utf-8"))
    seeded = []
    for path in sorted(MIGRATIONS.glob("V*.sql"), key=lambda p: int(p.name.split("__")[0][1:])):
        sql = path.read_text(encoding="utf-8")
        for match in re.finditer(
            r"definition\.id\s*,\s*'([^']+)'\s*,\s*'(\{.*?\})'\s*,\s*'([a-f0-9]{64})'",
            sql, re.DOTALL,
        ):
            if match[1] == current["version"]:
                seeded.append(json.loads(match[2]))
    if not seeded or seeded[-1] != current:
        raise ValueError("Current WorkflowSpec and database seed differ")

    sys.path.insert(0, str(AGENT_ENGINE))
    from runtime.production_handlers import build_production_registry
    from schemas.workflow_spec import WorkflowSpec

    registry = build_production_registry()
    # Older snapshots remain compatibility fixtures, not database bootstrap seeds.
    documents = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted((AGENT_ENGINE / "contracts").rglob("*.workflow.json"))
    ]
    prompts = set()
    for document in documents:
        WorkflowSpec.model_validate(document)
        for node in document["nodes"]:
            key, version = node["agent_name"].rsplit("_", 1)
            registration = registry.resolve(key, version)
            for field in (
                "input_schema", "input_schema_hash", "output_schema", "output_schema_hash",
                "prompt_key", "prompt_version", "prompt_checksum",
            ):
                if node[field] != getattr(registration, field):
                    raise ValueError(f"{document['version']}:{node['node_id']}.{field} drift")
            prompts.add(f"{node['prompt_key']}_{node['prompt_version']}.md")
    for name in sorted(prompts):
        worker = AGENT_ENGINE / "prompts" / name
        backend = ROOT / "backend/src/main/resources/prompts" / name
        # Legacy inline prompts are checked by the registry checksums above.
        if backend.exists() and worker.read_text(encoding="utf-8") != backend.read_text(encoding="utf-8"):
            raise ValueError(f"Prompt resource drift: {name}")
    print("AutoSpec workflow contracts are synchronized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
