from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT_ENGINE = ROOT / "agent-engine"
CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-v5.workflow.json"
PARALLEL_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-v5-parallel.workflow.json"
CANDIDATE_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-v5-agent-execution.workflow.json"
SPEC_REPAIR_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-spec-repair.workflow.json"
PM_SCHEMA_REPAIR_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair.workflow.json"
PM_SCHEMA_REPAIR_V2_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v2.workflow.json"
PM_SCHEMA_REPAIR_V3_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v3.workflow.json"
PM_SCHEMA_REPAIR_V4_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v4.workflow.json"
PM_SCHEMA_REPAIR_V5_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v5.workflow.json"
PM_SCHEMA_REPAIR_V6_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v6.workflow.json"
PM_SCHEMA_REPAIR_V7_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v7.workflow.json"
PM_SCHEMA_REPAIR_V8_CONTRACT = ROOT / "agent-engine" / "contracts" / "autospec-pm-schema-repair-v8.workflow.json"
MIGRATION_DIR = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION_PATTERN = re.compile(r"^V(?P<version>\d+)_.*\.sql$")


def latest_seeded_contract(version: str = "v5") -> tuple[Path, dict] | None:
    candidates: list[tuple[int, Path, dict]] = []
    for migration in MIGRATION_DIR.glob("V*.sql"):
        version_match = MIGRATION_PATTERN.match(migration.name)
        if version_match is None:
            continue
        sql = migration.read_text(encoding="utf-8")
        for match in re.finditer(
            r"spec_json\s*=\s*'(\{\s*\"workflow_key\"\s*:\s*\"autospec-v5\".*?\})'"
            r"\s*,\s*content_hash",
            sql,
            re.DOTALL,
        ):
            seeded = json.loads(match.group(1))
            if seeded.get("version") != version:
                continue
            candidates.append(
                (int(version_match.group("version")), migration, seeded)
            )
        for match in re.finditer(
            r"definition\.id\s*,\s*'(?P<contract_version>[^']+)'\s*,\s*"
            r"'(?P<spec>\{\s*\"workflow_key\"\s*:\s*\"autospec-v5\".*?\})'\s*,\s*"
            r"'(?P<content_hash>[^']+)'",
            sql,
            re.DOTALL,
        ):
            if match.group("contract_version") != version:
                continue
            seeded = json.loads(match.group("spec"))
            candidates.append(
                (int(version_match.group("version")), migration, seeded)
            )
    if not candidates:
        return None
    _, migration, seeded = max(candidates, key=lambda item: item[0])
    return migration, seeded


def verify_seeded_contract(path: Path, version: str) -> str | None:
    if not path.exists():
        return None
    seeded_contract = latest_seeded_contract(version)
    if seeded_contract is None:
        print(
            f"Unable to locate a seeded {version} workflow contract",
            file=sys.stderr,
        )
        return "missing"
    migration, seeded = seeded_contract
    canonical = json.loads(path.read_text(encoding="utf-8"))
    if seeded != canonical:
        print(
            f"{version} workflow contract drift: update the canonical contract and create a new migration together "
            f"(latest seeded migration: {migration.name})",
            file=sys.stderr,
        )
        return "drift"
    return None


def main() -> int:
    canonical = json.loads(CONTRACT.read_text(encoding="utf-8"))
    seeded_contract = latest_seeded_contract()
    if seeded_contract is None:
        print("Unable to locate a seeded autospec-v5 workflow contract", file=sys.stderr)
        return 1
    if verify_seeded_contract(PARALLEL_CONTRACT, "v5-parallel") is not None:
        return 1
    migration, seeded = seeded_contract
    if seeded != canonical:
        print(
            "autospec-v5 contract drift: update the canonical contract and create a new migration together "
            f"(latest seeded migration: {migration.name})",
            file=sys.stderr,
        )
        return 1
    candidate_error = verify_seeded_contract(
        CANDIDATE_CONTRACT,
        "v5-agent-execution",
    )
    if candidate_error is not None:
        return 1
    spec_repair_error = verify_seeded_contract(SPEC_REPAIR_CONTRACT, "spec-repair")
    if spec_repair_error is not None:
        return 1
    pm_schema_repair_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_CONTRACT,
        "pm-schema-repair-v1",
    )
    if pm_schema_repair_error is not None:
        return 1
    pm_schema_repair_v2_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V2_CONTRACT,
        "pm-schema-repair-v2",
    )
    if pm_schema_repair_v2_error is not None:
        return 1
    pm_schema_repair_v3_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V3_CONTRACT,
        "pm-schema-repair-v3",
    )
    if pm_schema_repair_v3_error is not None:
        return 1
    pm_schema_repair_v4_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V4_CONTRACT,
        "pm-schema-repair-v4",
    )
    if pm_schema_repair_v4_error is not None:
        return 1
    pm_schema_repair_v5_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V5_CONTRACT,
        "pm-schema-repair-v5",
    )
    if pm_schema_repair_v5_error is not None:
        return 1
    pm_schema_repair_v6_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V6_CONTRACT,
        "pm-schema-repair-v6",
    )
    if pm_schema_repair_v6_error is not None:
        return 1
    pm_schema_repair_v7_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V7_CONTRACT,
        "pm-schema-repair-v7",
    )
    if pm_schema_repair_v7_error is not None:
        return 1
    pm_schema_repair_v8_error = verify_seeded_contract(
        PM_SCHEMA_REPAIR_V8_CONTRACT,
        "pm-schema-repair-v8",
    )
    if pm_schema_repair_v8_error is not None:
        return 1
    experiment_contracts = sorted(
        CONTRACT.parent.glob("autospec-v5-agent-execution-v[23456]-*.workflow.json")
    )
    required_diagnostics = {
        "autospec-v5-agent-execution-v5-d.workflow.json",
        "autospec-v5-agent-execution-v6-d.workflow.json",
    }
    present_names = {path.name for path in experiment_contracts}
    missing_diagnostics = sorted(required_diagnostics - present_names)
    if missing_diagnostics:
        print(
            "Missing current diagnostic contracts: " + ", ".join(missing_diagnostics),
            file=sys.stderr,
        )
        return 1
    contracts = [
        CONTRACT,
        PARALLEL_CONTRACT,
        CANDIDATE_CONTRACT,
        SPEC_REPAIR_CONTRACT,
        PM_SCHEMA_REPAIR_CONTRACT,
        PM_SCHEMA_REPAIR_V2_CONTRACT,
        PM_SCHEMA_REPAIR_V3_CONTRACT,
        PM_SCHEMA_REPAIR_V4_CONTRACT,
        PM_SCHEMA_REPAIR_V5_CONTRACT,
        PM_SCHEMA_REPAIR_V6_CONTRACT,
        PM_SCHEMA_REPAIR_V7_CONTRACT,
        PM_SCHEMA_REPAIR_V8_CONTRACT,
        *experiment_contracts,
    ]
    for path in contracts[3:]:
        document = json.loads(path.read_text(encoding="utf-8"))
        if verify_seeded_contract(path, document["version"]) is not None:
            return 1
    sys.path.insert(0, str(AGENT_ENGINE))
    from runtime.production_handlers import build_production_registry
    from schemas.workflow_spec import WorkflowSpec

    registry = build_production_registry()
    capability_errors: list[str] = []
    documents = [json.loads(path.read_text(encoding="utf-8")) for path in contracts]
    for document in documents:
        WorkflowSpec.model_validate(document)
    for prompt_name in (
        "backend_engineer_loop_v1.md", "product_manager_schema_v1.md",
        "architect_shared_v1.md", "architect_schema_v1.md", "backend_engineer_shared_v1.md",
        "frontend_engineer_shared_v1.md", "frontend_schema_v1.md", "reviewer_shared_v1.md",
        "reviewer_schema_v1.md",
        "backend_engineer_loop_v2_v1.md",
        "backend_engineer_loop_v3_v1.md",
    ):
        prompt_path = AGENT_ENGINE / "prompts" / prompt_name
        if prompt_path.read_text(encoding="utf-8") != (ROOT / "backend/src/main/resources/prompts" / prompt_name).read_text(encoding="utf-8"):
            print(f"Prompt resource drift: {prompt_name}", file=sys.stderr)
            return 1
    for node in [node for document in documents for node in document["nodes"]]:
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
    print("autospec-v5 workflow contracts are synchronized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
