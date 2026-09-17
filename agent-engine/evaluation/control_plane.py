"""Bounded experiment collection through the public V5 API, never a second executor."""
from __future__ import annotations

import asyncio
import hashlib
import json
import math
import time
from pathlib import Path
from typing import Any, Sequence

import httpx
from pydantic import BaseModel, ConfigDict, Field

from evaluation.metrics import aggregate_case_metrics
from evaluation.budget_ledger import BudgetLedger, DEFAULT_LEDGER_PATH, money_units
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
    case_ids: list[str] | None = Field(default=None, min_length=1)
    contract_family: str = Field(default="v3", pattern=r"^v[3456]$")
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
                 *, budget_ledger_path: Path = DEFAULT_LEDGER_PATH):
        self.client, self.config, self.output_dir = client, config, output_dir
        self.started_runs = 0
        self.reserved_cost = 0.0
        self.versions: dict[str, dict[str, Any]] = {}
        self.budget_ledger_path = budget_ledger_path
        self.budget_ledger: BudgetLedger | None = None
        self.output_dir.mkdir(parents=True, exist_ok=True)

    async def request(self, method: str, path: str, **kwargs: Any) -> Any:
        response = await self.client.request(method, path, **kwargs)
        if response.status_code >= 300:
            # Do not expose response bodies, cookies, headers or request input.
            raise RuntimeError(f"control plane returned HTTP {response.status_code} for {path}")
        return response.json()

    async def preflight(self) -> None:
        checked_versions: dict[str, dict[str, Any]] = {}
        if not self.config.version_ids or set(self.config.version_ids) - {"A", "B", "C", "D"}:
            raise ValueError("version_ids must use experimental groups A/B/C/D")
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
        versions = await self.request("GET", "/api/workflows/autospec-v5/versions")
        for group, identifier in self.config.version_ids.items():
            version = next((v for v in versions if v["id"] == identifier), None)
            if version is None or version["status"] != "PUBLISHED":
                raise ValueError(f"group {group} requires a published version; drafts cannot run")
            spec = json.loads(version["specJson"])
            if digest(spec) != version["contentHash"]:
                raise ValueError("published spec checksum mismatch")
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
        if journal.exists():
            raise ValueError(f"existing case journal {journal.name}; inspect it before resuming")
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
        journal.write_text(json.dumps({"case_id": case.case_id, "group": group, "status": "STARTING"}), encoding="utf-8")
        project = await self.request("POST", "/api/projects", json={"name": key, "requirement": case.requirement})
        project_id = project["projectId"]
        journal.write_text(json.dumps({"case_id": case.case_id, "project_id": project_id, "status": "PROJECT_CREATED"}), encoding="utf-8")
        started = time.monotonic()
        run = await self.request("POST", "/api/workflow-runs", json={
            "projectId": project_id, "workflowVersionId": self.versions[group]["id"],
            "idempotencyKey": key, "input": {"requirement": case.requirement},
            "executionPolicy": {"qualityProfile": "BALANCED", "maxTokens": self.config.run_max_tokens,
                                "maxModelCalls": self.config.run_max_model_calls, "maxCost": self.config.run_max_cost,
                                "maxWallTimeMs": self.config.run_timeout_seconds * 1000},
        })
        run_id = run["id"]
        journal.write_text(json.dumps({"case_id": case.case_id, "project_id": project_id,
                                       "workflow_run_id": run_id, "status": "RUNNING"}), encoding="utf-8")
        while run["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED", "COMPLETED"}:
            if run["status"] in {"WAITING_APPROVAL", "PAUSED"} or time.monotonic() - started >= self.config.run_timeout_seconds:
                # Never silently approve a human gate. Cancel only the experiment's own run.
                await self.request("POST", f"/api/projects/{project_id}/workflow-runs/{run_id}/cancel")
                run = await self.request("GET", f"/api/workflow-runs/{run_id}")
                break
            await asyncio.sleep(self.config.poll_seconds)
            run = await self.request("GET", f"/api/workflow-runs/{run_id}")
        elapsed_ms = (time.monotonic() - started) * 1000
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
        journal.write_text(json.dumps({"project_id": project_id, "result": result.model_dump(mode="json"),
                                       "trace": trace, "artifacts": artifacts}, ensure_ascii=False, indent=2), encoding="utf-8")
        return result, trace


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
