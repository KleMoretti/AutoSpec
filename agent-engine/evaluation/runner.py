from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import time
from time import perf_counter
from typing import Any
from uuid import uuid4

from runtime.agent_router import route_after_evaluation, route_after_review
from evaluation.ablation import run_ablation_matrix
from runtime.node_executor import NodeCommand, NodeExecutionEvent, NodeExecutor
from runtime.production_handlers import build_production_registry
from runtime.trace import TraceRecorder
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.evaluation import (
    EvalCase,
    EvalCaseResult,
    EvalRun,
    EvaluationReport,
    MetricSnapshot,
)
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewReport
from review.evaluator import evaluate_artifacts
from evaluation.case_catalog import list_evaluation_cases


DATASET_VERSION = "autospec-v5-eval-v1"
WORKFLOW_KEY = "autospec-v5"
WORKFLOW_VERSION = "v5"
NODE_ORDER = (
    ("product_manager", "ProductManagerAgent"),
    ("architect", "ArchitectAgent"),
    ("backend_engineer", "BackendEngineerAgent"),
    ("frontend_engineer", "FrontendEngineerAgent"),
    ("reviewer", "ReviewerAgent"),
    ("evaluator", "EvaluatorAgent"),
)


async def run_fixture_baseline(
    cases: list[EvalCase] | None = None,
    *,
    run_id: str | None = None,
    baseline_run_id: str | None = None,
) -> EvalRun:
    """Run the deterministic V5 implementation against the versioned catalog.

    This is deliberately offline: it uses the existing fixture handlers and
    records the same node/event boundaries as the Redis worker.
    """

    started_at = round(time.time() * 1000)
    started = perf_counter()
    selected = list_evaluation_cases() if cases is None else cases
    run_key = run_id or f"baseline-{uuid4().hex}"
    executor = NodeExecutor(build_production_registry())
    results: list[EvalCaseResult] = []
    latencies: list[int] = []

    for index, case in enumerate(selected, start=1):
        result, durations = await _run_case(executor, case, index, run_key)
        results.append(result)
        latencies.extend(durations)

    total = len(results)
    succeeded = sum(result.status == "SUCCEEDED" for result in results)
    coverage = sum(result.skill_coverage for result in results) / total if total else 0.0
    duration_ms = max(0, round((perf_counter() - started) * 1000))
    return EvalRun(
        run_id=run_key,
        dataset_version=DATASET_VERSION,
        workflow_key=WORKFLOW_KEY,
        workflow_version=WORKFLOW_VERSION,
        baseline_run_id=baseline_run_id,
        code_version=os.getenv("AUTOSPEC_CODE_VERSION", "workspace-v5"),
        model_versions={"default": "deterministic-fixture"},
        prompt_versions={node: "v1" for node, _ in NODE_ORDER},
        started_at_epoch_ms=started_at,
        duration_ms=duration_ms,
        status="SUCCEEDED" if all(result.status == "SUCCEEDED" for result in results) else "PARTIAL",
        metrics=MetricSnapshot(
            agent={
                "task_success_rate": _ratio(succeeded, total),
                "plan_completion_rate": _ratio(succeeded, total),
                "tool_selection_accuracy": None,
                "tool_argument_valid_rate": None,
                "loop_rate": 0.0,
                "replan_rate": 0.0,
            },
            business={
                "question_relevance": None,
                "skill_coverage": round(coverage, 4),
                "duplicate_question_rate": None,
                "difficulty_match": None,
                "evaluation_consistency": None,
            },
            rag={
                "recall_at_k": None,
                "mrr": None,
                "ndcg": None,
                "rerank_hit_rate": None,
            },
            engineering={
                "p50_latency": float(_percentile(latencies, 0.50)),
                "p95_latency": float(_percentile(latencies, 0.95)),
                "tokens_per_interview": 0.0,
                "cost_per_interview": 0.0,
                "tool_failure_rate": 0.0,
                "retry_rate": 0.0,
                "recovery_rate": None,
            },
        ),
        case_results=results,
    )


async def _run_case(
    executor: NodeExecutor,
    case: EvalCase,
    case_index: int,
    run_id: str,
) -> tuple[EvalCaseResult, list[int]]:
    trace_id = f"{run_id}:{case.case_id}"
    recorder = TraceRecorder(trace_id=trace_id, session_id=case.case_id)
    outputs: dict[str, dict[str, Any]] = {}
    node_records: list[dict[str, Any]] = []
    model_invocations: list[dict[str, Any]] = []
    durations: list[int] = []
    failed_nodes = 0
    evaluator_report = None

    for step, (node_id, handler_key) in enumerate(NODE_ORDER, start=1):
        payload = _payload_for(
            node_id,
            case,
            outputs,
            node_records,
            model_invocations,
        )
        command = NodeCommand(
            event_id=f"{trace_id}:command:{step}",
            workflow_run_id=case_index,
            node_run_id=case_index * 100 + step,
            node_id=node_id,
            revision=1,
            attempt=1,
            execution_id=f"{trace_id}:{node_id}:1",
            handler_key=handler_key,
            handler_version="v1",
            timeout_ms=120_000,
            input_payload=payload,
        )
        event = await executor.execute(command)
        durations.append(event.duration_ms)
        _record_event(recorder, event, step)
        node_records.append(
            {
                "node_name": node_id,
                "status": "SUCCEEDED" if event.event_type == "NODE_SUCCEEDED" else "FAILED",
                "duration_ms": event.duration_ms,
            }
        )
        model_invocations.extend(
            record.model_dump(mode="json") for record in event.call_records
            if record.call_type == "MODEL"
        )
        if event.event_type != "NODE_SUCCEEDED" or event.output_payload is None:
            failed_nodes += 1
            break
        outputs[node_id] = event.output_payload

        if node_id == "reviewer":
            review = ReviewReport.model_validate(event.output_payload)
            for route_index, decision in enumerate(route_after_review(review), start=1):
                recorder.record(
                    {
                        "span_id": f"{event.execution_id}:route:{route_index}",
                        "parent_span_id": f"{event.execution_id}:node",
                        "node": "router",
                        "step": step,
                        "status": "DECIDED",
                        "route_action": decision.action,
                        "route_reason": decision.reason,
                        "input_ref": f"{event.execution_id}:input",
                        "output_ref": f"{event.execution_id}:route:{route_index}",
                    }
                )
        if node_id == "evaluator":
            evaluator_report = event.output_payload

    if evaluator_report is None and _has_artifacts(outputs):
        evaluator_report = _fallback_evaluation(case, outputs, node_records, model_invocations)
    if evaluator_report is not None:
        decision = route_after_evaluation(EvaluationReport.model_validate(evaluator_report))
        last_step = len(NODE_ORDER)
        recorder.record(
            {
                "span_id": f"{trace_id}:terminal-route",
                "node": "router",
                "step": last_step,
                "status": "DECIDED",
                "route_action": decision.action,
                "route_reason": decision.reason,
            }
        )

    completed_nodes = len(outputs)
    if completed_nodes == len(NODE_ORDER) and failed_nodes == 0:
        status = "SUCCEEDED"
    elif completed_nodes:
        status = "PARTIAL"
    else:
        status = "FAILED"
    score = (
        float(evaluator_report.get("overall_score", 0))
        if isinstance(evaluator_report, dict)
        else 0.0
    )
    return (
        EvalCaseResult(
            case_id=case.case_id,
            status=status,
            overall_score=score,
            completed_nodes=completed_nodes,
            failed_nodes=failed_nodes,
            skill_coverage=_capability_coverage(case, outputs),
            trace_id=trace_id,
            trace=recorder.snapshot(),
        ),
        durations,
    )


def _payload_for(
    node_id: str,
    case: EvalCase,
    outputs: dict[str, dict[str, Any]],
    node_records: list[dict[str, Any]],
    model_invocations: list[dict[str, Any]],
) -> dict[str, Any]:
    base = {
        "requirement": case.requirement,
        "retrieved_sources": [],
    }
    if node_id == "product_manager":
        return base
    payload = {**base, "prd": outputs["product_manager"]}
    if node_id == "architect":
        return payload
    payload["architecture_design"] = outputs["architect"]
    if node_id == "backend_engineer":
        return payload
    payload["backend_design"] = outputs["backend_engineer"]
    if node_id == "frontend_engineer":
        return payload
    payload["frontend_skeleton"] = outputs["frontend_engineer"]
    if node_id == "reviewer":
        return {
            **payload,
            "generated_files": [],
            "model_invocations": model_invocations,
        }
    return {
        "requirement": case.requirement,
        "prd": outputs["product_manager"],
        "architecture_design": outputs["architect"],
        "backend_design": outputs["backend_engineer"],
        "frontend_skeleton": outputs["frontend_engineer"],
        "review_report": outputs["reviewer"],
        "records": node_records,
        "model_invocations": model_invocations,
        "retrieved_sources": [],
        "generated_files": [],
    }


def _record_event(recorder: TraceRecorder, event: NodeExecutionEvent, step: int) -> None:
    tool_calls = [
        {"name": record.tool_name or "", "version": record.tool_version or ""}
        for record in event.call_records
        if record.call_type == "TOOL"
    ]
    recorder.record(
        {
            "span_id": f"{event.execution_id}:node",
            "node": event.node_id,
            "step": step,
            "execution_id": event.execution_id,
            "status": event.event_type,
            "model_version": event.model_name or "deterministic-fixture",
            "prompt_version": event.prompt_version or "unfrozen",
            "input_schema_version": event.input_schema or "unfrozen",
            "output_schema_version": event.output_schema or "unfrozen",
            "input_ref": f"{event.execution_id}:input",
            "output_ref": (
                f"{event.execution_id}:output"
                if event.output_payload is not None
                else None
            ),
            "token_usage": {
                "input_tokens": event.input_tokens,
                "output_tokens": event.output_tokens,
                "cache_tokens": event.cache_tokens,
            },
            "cost": event.estimated_cost,
            "latency_ms": event.duration_ms,
            "tool_call_count": event.tool_call_count,
            "tool_calls": tool_calls,
            "error_type": event.error_code,
        }
    )


def _fallback_evaluation(
    case: EvalCase,
    outputs: dict[str, dict[str, Any]],
    records: list[dict[str, Any]],
    model_invocations: list[dict[str, Any]],
) -> dict[str, Any]:
    report = evaluate_artifacts(
        requirement=case.requirement,
        prd=PrdArtifact.model_validate(outputs["product_manager"]),
        architecture_design=ArchitectureDesignArtifact.model_validate(outputs["architect"]),
        backend_design=BackendDesignArtifact.model_validate(outputs["backend_engineer"]),
        frontend_skeleton=FrontendSkeletonArtifact.model_validate(outputs["frontend_engineer"]),
        review_report=ReviewReport.model_validate(outputs["reviewer"]),
        records=records,
        model_invocations=model_invocations,
        retrieved_sources=[],
        generated_files=[],
    )
    return report.model_dump(mode="json")


def _has_artifacts(outputs: dict[str, dict[str, Any]]) -> bool:
    return all(node in outputs for node in ("product_manager", "architect", "backend_engineer", "frontend_engineer", "reviewer"))


def _capability_coverage(case: EvalCase, outputs: dict[str, dict[str, Any]]) -> float:
    if not case.expected_capabilities:
        return 1.0
    material = json.dumps(outputs, ensure_ascii=False).lower()
    matched = sum(
        all(word in material for word in capability.lower().split() if len(word) > 2)
        for capability in case.expected_capabilities
    )
    return round(matched / len(case.expected_capabilities), 4)


def _ratio(value: int, total: int) -> float:
    return round(value / total, 4) if total else 0.0


def _percentile(values: list[int], fraction: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, round((len(ordered) - 1) * fraction))
    return ordered[index]


async def _main(args: argparse.Namespace) -> None:
    cases = list_evaluation_cases()
    if args.case_id:
        wanted = set(args.case_id)
        cases = [case for case in cases if case.case_id in wanted]
    result = await run_fixture_baseline(cases, run_id=args.run_id)
    serialized = json.dumps(result.model_dump(mode="json"), ensure_ascii=False, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the offline AutoSpec V5 evaluation baseline")
    parser.add_argument("--output", help="optional JSON output path")
    parser.add_argument("--run-id")
    parser.add_argument("--case-id", action="append")
    asyncio.run(_main(parser.parse_args()))


if __name__ == "__main__":
    main()
