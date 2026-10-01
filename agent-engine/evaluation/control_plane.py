"""Bounded experiment collection through the public V5 API, never a second executor."""
from __future__ import annotations

import asyncio
import hashlib
import json
import math
import time
from pathlib import Path
from typing import Any, Literal, Sequence

import httpx
from pydantic import BaseModel, ConfigDict, Field

from evaluation.metrics import aggregate_case_metrics
from evaluation.budget_ledger import BudgetLedger, DEFAULT_LEDGER_PATH, money_units
from evaluation.experiment_manifest import load_manifest, validate_manifest_contracts
from schemas.evaluation import AutoSpecEvalCase, AutoSpecEvalRun, AutoSpecCaseResult


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


class CollectionConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    experiment_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,64}$")
    version_ids: dict[str, int]
    code_version: str = Field(min_length=1)
    environment: dict[str, str]
    dataset_split: str = "smoke"
    random_seed: int = 20261001
    fixture_approval_policy: Literal["MANUAL", "AUTO_APPROVE_FOR_TEST"] = "MANUAL"
    fixture_approval_reason: str | None = None
    case_ids: list[str] | None = Field(default=None, min_length=1)
    contract_family: str = Field(default="v3", pattern=r"^v[3456]$")
    manifest_path: str | None = None
    manifest_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    groups: list[str] | None = Field(default=None, min_length=1)
    collection_scope: str = Field(default="WORKFLOW", pattern=r"^(WORKFLOW|NODE)$")
    budget_authorization_id: str | None = Field(default=None, pattern=r"^[a-zA-Z0-9_-]{1,64}$")
    repetitions: int = Field(default=1, ge=1, le=10)
    run_max_cost: float = Field(gt=0)
    total_max_cost: float = Field(gt=0)
    max_runs: int = Field(default=32, ge=1, le=500)
    run_max_tokens: int = Field(default=500000, ge=1)
    run_max_model_calls: int = Field(default=32, ge=1)
    run_timeout_seconds: int = Field(default=600, ge=1, le=3600)
    poll_seconds: float = Field(default=2, gt=0, le=30)
    pricing_snapshot: dict[str, Any] = Field(default_factory=dict)


class ControlPlaneCollector:
    """The caller supplies an authenticated HTTP client; credentials are never serialized.

    Each case/repetition/group gets a fresh project to prevent generated answers
    from contaminating the next case's retrieval corpus. A journal makes partial
    runs reviewable and forbids blindly restarting an interrupted experiment.
    """

    def __init__(self, client: httpx.AsyncClient, config: CollectionConfig, output_dir: Path,
                 *, budget_ledger_path: Path = DEFAULT_LEDGER_PATH, resume: bool = False):
        self.client, self.config, self.output_dir = client, config, output_dir
        self.started_runs = 0
        self.reserved_cost = 0.0
        self.versions: dict[str, dict[str, Any]] = {}
        self.budget_ledger_path = budget_ledger_path
        self.budget_ledger: BudgetLedger | None = None
        self.resume = resume
        self.output_dir.mkdir(parents=True, exist_ok=True)

    async def request(self, method: str, path: str, **kwargs: Any) -> Any:
        response = await self.client.request(method, path, **kwargs)
        if response.status_code >= 300:
            # Do not expose response bodies, cookies, headers or request input.
            raise RuntimeError(f"control plane returned HTTP {response.status_code} for {path}")
        return response.json()

    async def preflight(self, *, offline: bool = False) -> None:
        checked_versions: dict[str, dict[str, Any]] = {}
        if not self.config.version_ids or set(self.config.version_ids) - {"A", "B", "C", "D"}:
            raise ValueError("version_ids must use experimental groups A/B/C/D")
        if self.config.groups is not None:
            if len(set(self.config.groups)) != len(self.config.groups) or any(
                group not in self.config.version_ids for group in self.config.groups
            ):
                raise ValueError("groups must be a unique subset of configured version_ids")
        if self.config.fixture_approval_policy == "AUTO_APPROVE_FOR_TEST":
            if self.config.environment.get("model_mode") != "fixture":
                raise ValueError("automatic approval is restricted to fixture model mode")
            if not self.config.fixture_approval_reason:
                raise ValueError("fixture automatic approval requires a pre-registered reason")
        if self.config.environment.get("model_mode") == "live":
            if not self.config.budget_authorization_id:
                raise ValueError("live collection requires a shared budget_authorization_id")
            pricing = self.config.pricing_snapshot
            if pricing.get("currency") != "CNY":
                raise ValueError("reviewed live experiments are priced in CNY")
            rates = pricing.get("models", {})
            if not all(pricing.get(k) for k in ("source", "observed_at", "currency", "version")) or not rates:
                raise ValueError("live collection requires a dated per-model pricing snapshot")
            if not all(valid_rates(rate) for rate in rates.values()):
                raise ValueError("invalid per-model pricing")
            upper_price = max(rate[key] for rate in rates.values()
                              for key in ("input_per_million", "cached_input_per_million", "output_per_million"))
            if upper_price * self.config.run_max_tokens / 1_000_000 > self.config.run_max_cost:
                raise ValueError("token allowance exceeds the conservative per-run price budget")
        manifest_entries: dict[str, dict] = {}
        manifest = None
        if self.config.manifest_path:
            manifest_path = Path(self.config.manifest_path)
            manifest, manifest_digest = load_manifest(manifest_path)
            if self.config.manifest_hash and self.config.manifest_hash != manifest_digest:
                raise ValueError("experiment manifest checksum mismatch")
            manifest_entries = validate_manifest_contracts(manifest_path, manifest)
            configured = set(self.config.version_ids)
            if configured - set(manifest_entries):
                raise ValueError("manifest does not contain every configured experiment group")
            if manifest.workflow_key != "autospec-v5":
                raise ValueError("experiment manifest must target workflow key autospec-v5")
            if manifest.dataset_split != self.config.dataset_split:
                raise ValueError("experiment manifest dataset split does not match config")
            if manifest.random_seed != self.config.random_seed:
                raise ValueError("experiment manifest random seed does not match config")
            expected_budget = {
                "repetitions": self.config.repetitions,
                "run_max_cost": self.config.run_max_cost,
                "total_max_cost": self.config.total_max_cost,
                "max_runs": self.config.max_runs,
                "run_max_tokens": self.config.run_max_tokens,
                "run_max_model_calls": self.config.run_max_model_calls,
            }
            if manifest.budget.model_dump() != expected_budget:
                raise ValueError("experiment manifest budget does not match config")
            if manifest.pricing_snapshot != self.config.pricing_snapshot:
                raise ValueError("experiment manifest pricing snapshot does not match config")
            if offline:
                for group, identifier in self.config.version_ids.items():
                    entry = manifest_entries[group]["entry"]
                    if entry["workflow_version_id"] != identifier:
                        raise ValueError(f"manifest version ID mismatch for group {group}")
                    spec = manifest_entries[group]["spec"]
                    checked_versions[group] = {
                        "id": identifier, "status": "PUBLISHED", "specJson": json.dumps(spec),
                        "contentHash": digest(spec),
                    }
                self.versions = checked_versions
                return
        if offline:
            raise ValueError("--validate-only requires an explicit manifest_path")
        versions = await self.request("GET", "/api/workflows/autospec-v5/versions")
        for group, identifier in self.config.version_ids.items():
            version = next((v for v in versions if v["id"] == identifier), None)
            if version is None or version["status"] != "PUBLISHED":
                raise ValueError(f"group {group} requires a published version; drafts cannot run")
            spec = json.loads(version["specJson"])
            if digest(spec) != version["contentHash"]:
                raise ValueError("published spec checksum mismatch")
            if manifest is not None:
                entry = manifest_entries[group]["entry"]
                if entry["workflow_version_id"] != identifier or manifest_entries[group]["spec"] != spec:
                    raise ValueError(f"group {group} does not match the explicit experiment manifest")
            else:
                # Compatibility for the pre-manifest tests and historical local
                # examples. New collection configs must use manifest_path.
                expected = Path(__file__).resolve().parents[1] / "contracts" / f"autospec-v5-agent-execution-{self.config.contract_family}-{group.lower()}.workflow.json"
                if not expected.exists() or json.loads(expected.read_text(encoding="utf-8")) != spec:
                    raise ValueError(f"group {group} is not the reviewed experimental contract")
            if self.config.environment.get("model_mode") == "live":
                validate_live_prices(spec, self.config.pricing_snapshot)
            checked_versions[group] = version
        if self.config.environment.get("model_mode") == "live":
            self.budget_ledger = BudgetLedger(self.budget_ledger_path, self.config.budget_authorization_id,
                                              self.config.pricing_snapshot["currency"], self.config.total_max_cost)
        self.versions = checked_versions

    async def __call__(self, group: str, cases: Sequence[AutoSpecEvalCase], knobs: dict[str, Any]) -> AutoSpecEvalRun:
        if not self.versions:
            await self.preflight()
        if group not in self.versions:
            raise ValueError(f"no published version configured for group {group}")
        results: list[AutoSpecCaseResult] = []
        traces: list[dict[str, Any]] = []
        for case in cases:
            for repetition in range(1, self.config.repetitions + 1):
                result, trace = await self.collect_case(group, case, repetition)
                results.append(result)
                traces.append(trace)
        calls = [call for trace in traces for node in trace.get("nodes", []) for call in node.get("invocations", [])
                 if call.get("callType") == "MODEL"]
        is_live = bool(calls) and all(call.get("providerKey") not in {None, "local", "fixture"}
                                   and "fixture" not in str(call.get("modelName", "")).lower() for call in calls)
        spec = json.loads(self.versions[group]["specJson"])
        run = AutoSpecEvalRun(
            run_id=f"{self.config.experiment_id}-{group}", group=group, group_name=knobs["name"],
            dataset_version=cases[0].dataset_version, dataset_hash=digest([c.model_dump(mode="json") for c in cases]),
            dataset_split=self.config.dataset_split, environment_hash=digest(self.config.environment),
            code_version=self.config.code_version, workflow_version=spec["version"],
            model_version=digest(sorted({f'{c.get("providerKey")}:{c.get("modelName")}' for c in calls})) if calls else None,
            retriever_version=digest([n.get("retrieval_policy") for n in spec["nodes"]]),
            budget_version=digest({k: v for k, v in self.config.model_dump().items() if k.startswith("run_")}),
            pricing_snapshot=self.config.pricing_snapshot,
            bundle_hash=digest(sorted({c.bundle_hash for c in results if c.bundle_hash})),
            prompt_schema_versions={n["node_id"]: f'{n["prompt_key"]}:{n["prompt_version"]}:{n["output_schema_hash"]}' for n in spec["nodes"]},
            execution_mode="LIVE_CONTROL_PLANE" if is_live else "FIXTURE_BASELINE",
            status="SUCCEEDED" if all(c.status != "NOT_EXECUTED" for c in results) else "PARTIAL",
            gate_status="NOT_EVALUATED", decision="NOT_EVALUATED", case_results=results,
            metrics=aggregate_case_metrics(results),
        )
        (self.output_dir / f"{group}-results.json").write_text(run.model_dump_json(indent=2), encoding="utf-8")
        return run

    async def collect_case(self, group: str, case: AutoSpecEvalCase, repetition: int) -> tuple[AutoSpecCaseResult, dict]:
        key = f"{self.config.experiment_id}-{group}-{digest(case.case_id)[:12]}-{repetition}"
        journal = self.output_dir / f"{key}.json"
        journal_data: dict[str, Any] | None = None
        if journal.exists():
            if not self.resume:
                raise ValueError(f"existing case journal {journal.name}; inspect it before resuming")
            journal_data = json.loads(journal.read_text(encoding="utf-8"))
            if journal_data.get("case_id") != case.case_id or journal_data.get("group") != group:
                raise ValueError(f"journal identity mismatch for {journal.name}")
            if journal_data.get("status") == "COLLECTED" and journal_data.get("result"):
                return AutoSpecCaseResult.model_validate(journal_data["result"]), journal_data.get("trace", {})

        if journal_data is None:
            exhausted = (self.started_runs >= self.config.max_runs
                         or (self.started_runs + 1) * money_units(self.config.run_max_cost)
                         > money_units(self.config.total_max_cost))
            if not exhausted and self.budget_ledger is not None:
                exhausted = not self.budget_ledger.reserve(key, self.config.run_max_cost)
            if exhausted:
                return AutoSpecCaseResult(case_id=case.case_id, repetition=repetition, status="NOT_EXECUTED",
                                          failure_codes=["EXPERIMENT_BUDGET_EXHAUSTED"]), {}
            self.started_runs += 1
            self.reserved_cost += self.config.run_max_cost
            journal_data = {"case_id": case.case_id, "group": group, "key": key, "status": "RESERVED"}
            journal.write_text(json.dumps(journal_data), encoding="utf-8")

        project_id = journal_data.get("project_id")
        if project_id is None:
            project = await self.request("POST", "/api/projects", json={"name": key, "requirement": case.requirement})
            project_id = project["projectId"]
            journal_data.update({"project_id": project_id, "status": "PROJECT_CREATED"})
            journal.write_text(json.dumps(journal_data), encoding="utf-8")

        run_id = journal_data.get("workflow_run_id")
        if run_id is None:
            run = await self.request("POST", "/api/workflow-runs", json={
                "projectId": project_id, "workflowVersionId": self.versions[group]["id"],
                "idempotencyKey": key, "input": {"requirement": case.requirement},
                "executionPolicy": {"qualityProfile": "BALANCED", "maxTokens": self.config.run_max_tokens,
                                    "maxModelCalls": self.config.run_max_model_calls, "maxCost": self.config.run_max_cost,
                                    "maxWallTimeMs": self.config.run_timeout_seconds * 1000},
            })
            run_id = run["id"]
            journal_data.update({"workflow_run_id": run_id, "status": "RUN_SUBMITTED", "run": run})
            journal.write_text(json.dumps(journal_data), encoding="utf-8")
        else:
            run = journal_data.get("run")
            if not isinstance(run, dict):
                run = await self.request("GET", f"/api/workflow-runs/{run_id}")

        started = time.monotonic()
        while run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED", "COMPLETED"}:
            if self.config.fixture_approval_policy == "AUTO_APPROVE_FOR_TEST":
                approval_event = await self.approve_fixture(project_id, journal_data)
                if approval_event is not None:
                    journal_data.setdefault("fixture_approvals", []).append(approval_event)
                    journal.write_text(json.dumps(journal_data, ensure_ascii=False, indent=2), encoding="utf-8")
                    await asyncio.sleep(self.config.poll_seconds)
                    run = await self.request("GET", f"/api/workflow-runs/{run_id}")
                    continue
            if run["status"] in {"WAITING_APPROVAL", "PAUSED"} or time.monotonic() - started >= self.config.run_timeout_seconds:
                # Never silently approve a human gate. Cancel only the experiment's own run.
                await self.request("POST", f"/api/projects/{project_id}/workflow-runs/{run_id}/cancel")
                run = await self.request("GET", f"/api/workflow-runs/{run_id}")
                break
            await asyncio.sleep(self.config.poll_seconds)
            run = await self.request("GET", f"/api/workflow-runs/{run_id}")
        elapsed_ms = (time.monotonic() - started) * 1000
        journal_data.update({"status": "TERMINAL", "run": run})
        journal.write_text(json.dumps(journal_data), encoding="utf-8")
        trace = await self.request("GET", f"/api/workflow-runs/{run_id}/trace")
        if str(trace.get("workflowRunId")) != str(run_id):
            raise ValueError("trace belongs to another workflow execution")
        nodes = await self.request("GET", f"/api/workflow-runs/{run_id}/nodes")
        artifacts = []
        offset = 0
        while True:
            page = await self.request("GET", f"/api/projects/{project_id}/artifacts", params={"limit": 100, "offset": offset})
            artifacts.extend(page)
            if len(page) < 100:
                break
            offset += len(page)
        current_node_ids = {n["id"] for n in nodes}
        artifacts = [a for a in artifacts if a.get("workflowNodeRunId") in current_node_ids]
        result = measure_case(case, repetition, run, trace, artifacts, self.config.pricing_snapshot,
                              elapsed_ms)
        # Only bounded, authorized experiment artifacts/trace are written; never raw HTTP metadata.
        journal_data.update({"status": "COLLECTED", "project_id": project_id,
                             "result": result.model_dump(mode="json"),
                             "trace": trace, "artifacts": artifacts})
        journal.write_text(json.dumps(journal_data, ensure_ascii=False, indent=2), encoding="utf-8")
        return result, trace

    async def approve_fixture(self, project_id: int, journal_data: dict[str, Any]) -> dict[str, Any] | None:
        """Apply only the explicitly pre-registered fixture approval rule.

        Live collections and ordinary fixture runs remain manual.  The journal
        records the approval IDs, policy and reason so an automatic decision is
        distinguishable from human approval in the collected evidence.
        """

        approvals = await self.request("GET", f"/api/projects/{project_id}/workflow-approvals")
        pending = [approval for approval in approvals if approval.get("status") == "PENDING"]
        if not pending:
            return None
        approved_ids: list[int] = []
        for approval in pending:
            if "APPROVE" not in (approval.get("allowedActions") or []):
                raise RuntimeError("fixture automatic approval is not allowed for a pending approval")
            approval_id = approval.get("id")
            lock_version = approval.get("lockVersion")
            if approval_id is None or lock_version is None:
                raise RuntimeError("fixture approval lacks a stable ID or lock version")
            await self.request("POST", f"/api/workflow-approvals/{approval_id}/decide", json={
                "decision": "APPROVE",
                "reason": self.config.fixture_approval_reason,
                "idempotencyKey": f"fixture-auto-approve:{self.config.experiment_id}:{approval_id}",
                "expectedLockVersion": lock_version,
            })
            approved_ids.append(int(approval_id))
        return {
            "policy": self.config.fixture_approval_policy,
            "reason": self.config.fixture_approval_reason,
            "approval_ids": approved_ids,
        }


def validate_live_prices(spec: dict, pricing: dict) -> None:
    """Zero-cost fixture contracts cannot enforce a monetary live budget."""
    for node in spec["nodes"]:
        policy = node["model_policy"]
        if policy.get("provider_key") == "local" and node["agent_name"] == "EvaluatorAgent_v2":
            continue
        rate = pricing["models"].get(f'{policy.get("provider_key")}:{policy.get("model_name")}')
        if not valid_rates(rate):
            raise ValueError("live contract must pin a provider/model with known prices")
        for frozen, observed in (("input_cost_per_million", "input_per_million"),
                                 ("cached_input_cost_per_million", "cached_input_per_million"),
                                 ("output_cost_per_million", "output_per_million")):
            if policy.get(frozen, 0) <= 0 or policy[frozen] < rate[observed]:
                raise ValueError("frozen contract underprices the live model")
        if node.get("fallback", {}).get("enabled"):
            raise ValueError("priced experiments require fallback disabled")


def measure_case(case: AutoSpecEvalCase, repetition: int, run: dict, trace: dict,
                 artifacts: list[dict], pricing: dict, elapsed_ms: float) -> AutoSpecCaseResult:
    calls = [c for n in trace.get("nodes", []) for c in n.get("invocations", [])]
    model_calls = [c for c in calls if c.get("callType") == "MODEL"]
    tool_calls = [c for c in calls if c.get("callType") == "TOOL"]
    reports = [a for a in artifacts if a.get("type") == "EVALUATION_REPORT"]
    report = json.loads(max(reports, key=lambda a: a.get("version", 0))["content"]) if reports else None
    covered = [t for t in (report or {}).get("requirement_traceability", []) if t.get("priority") == "MUST"]
    # Traceability of generated requirements is diagnostic only. External rubric
    # agreement is not inferred from string matches against expected endpoint names.
    coverage = None
    if covered:
        coverage = sum(bool(t.get("covered")) for t in covered) / len(covered) if all("covered" in t for t in covered) else None
    steps = [s for n in trace.get("nodes", []) for s in n.get("steps", [])]
    errors = ([c["errorCode"] for c in calls if c.get("errorCode")]
              + [n["errorCode"] for n in trace.get("nodes", []) if n.get("errorCode")]
              + [s["reasonCode"] for s in steps if s.get("status") == "FAILED" and s.get("reasonCode")])
    denied = {"TOOL_SCOPE_DENIED", "TOOL_PERMISSION_DENIED", "TOOL_NOT_ALLOWED"}
    unknown_tool_status = any(c.get("status") not in {"SUCCEEDED", "FAILED"} for c in tool_calls)
    allowed = {name.split(":")[0] for name in case.allowed_tools}
    prohibited = {name.split(":")[0] for name in case.prohibited_tools}
    unauthorized_executions = sum(c.get("status") == "SUCCEEDED" and (
        str(c.get("toolName", "")).split(":")[0] in prohibited
        or str(c.get("toolName", "")).split(":")[0] not in allowed) for c in tool_calls)
    tokens = sum(c["inputTokens"] + c["outputTokens"] for c in model_calls) if model_calls and all(
        c.get("inputTokens") is not None and c.get("outputTokens") is not None for c in model_calls) else None
    cost = 0.0 if model_calls else None
    rates = pricing.get("models", {})
    for call in model_calls:
        rate = rates.get(f'{call.get("providerKey")}:{call.get("modelName")}')
        if not valid_rates(rate) or any(call.get(k) is None for k in ("inputTokens", "outputTokens", "cacheTokens")):
            cost = None
            break
        cached = call["cacheTokens"]
        if not 0 <= cached <= call["inputTokens"]:
            cost = None
            break
        cost += ((call["inputTokens"] - cached) * rate["input_per_million"] + cached * rate["cached_input_per_million"]
                 + call["outputTokens"] * rate["output_per_million"]) / 1_000_000
    terminal_success = run["status"] in {"SUCCEEDED", "COMPLETED"}
    gate = terminal_success and bool(report and report.get("gate_status") == "PASSED")
    return AutoSpecCaseResult(
        case_id=case.case_id, repetition=repetition, workflow_run_id=str(run["id"]),
        trace_id=trace.get("correlationId"), bundle_hash=trace.get("executionBundleHash"),
        status="SUCCEEDED" if terminal_success else "FAILED", gate_pass=gate,
        must_trace_coverage=coverage, blocking_issue_count=report.get("blocking_issue_count") if report else None,
        unauthorized_tool_requests=sum(c.get("errorCode") in denied for c in tool_calls),
        unauthorized_tool_executions=None if unknown_tool_status else unauthorized_executions,
        invalid_tool_arguments=sum(c.get("errorCode") == "TOOL_INPUT_INVALID" for c in tool_calls),
        tool_call_count=len(tool_calls), schema_invalid_count=sum(e in {
            "SCHEMA_INVALID", "OUTPUT_SCHEMA_INVALID", "OUTPUT_SCHEMA_ERROR", "TURN_SCHEMA_INVALID",
            "VALIDATION_ERROR", "CONTRACT_MISMATCH"
        } for e in errors),
        duration_ms=elapsed_ms, tokens=tokens, cost=cost, steps=len(steps),
        replans=sum(s.get("phase") == "REPLAN" for s in steps),
        path_oscillations=sum(s.get("reasonCode") == "PATH_OSCILLATION" for s in steps), failure_codes=sorted(set(errors)),
    )


def valid_rates(rate: Any) -> bool:
    return isinstance(rate, dict) and all(
        isinstance(rate.get(k), (int, float)) and not isinstance(rate[k], bool)
        and math.isfinite(rate[k]) and rate[k] >= 0
        for k in ("input_per_million", "cached_input_per_million", "output_per_million")
    )
