"""Versioned aggregation of measured case facts; missing facts remain unavailable."""
from __future__ import annotations

import math
from statistics import mean, median
from typing import Sequence

from schemas.evaluation import AutoSpecCaseResult, AutoSpecMetric

METRICS_VERSION = "autospec-case-metrics-v2"


def percentile(values: Sequence[float], quantile: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def aggregate_case_metrics(cases: Sequence[AutoSpecCaseResult]) -> list[AutoSpecMetric]:
    def values(field: str) -> list[float] | None:
        result = [getattr(case, field) for case in cases]
        if not result or any(v is None or not math.isfinite(v) for v in result):
            return None
        return [float(v) for v in result]

    definitions = {
        "gate_pass_rate": ("gate_pass", mean, "ratio"),
        "blocking_issue_median": ("blocking_issue_count", median, "count"),
        "must_trace_coverage": ("must_trace_coverage", mean, "ratio"),
        "unauthorized_request_count": ("unauthorized_tool_requests", sum, "count"),
        "unauthorized_execution_count": ("unauthorized_tool_executions", sum, "count"),
        "schema_invalid_count": ("schema_invalid_count", sum, "count"),
        "tool_call_count": ("tool_call_count", sum, "count"),
        "p50_latency_ms": ("duration_ms", lambda v: percentile(v, .50), "ms"),
        "p95_latency_ms": ("duration_ms", lambda v: percentile(v, .95), "ms"),
        "tokens_per_run": ("tokens", mean, "tokens"),
        "cost_per_run": ("cost", mean, "currency"),
    }
    metrics = []
    for name, (field, reducer, unit) in definitions.items():
        observed = values(field)
        metrics.append(AutoSpecMetric(
            name=name, status="MEASURED" if observed is not None else "UNAVAILABLE",
            value=float(reducer(observed)) if observed is not None else None,
            unit=unit, source=METRICS_VERSION,
        ))
    calls, invalid = values("tool_call_count"), values("invalid_tool_arguments")
    valid = calls is not None and invalid is not None and all(i <= c for i, c in zip(invalid, calls))
    rate = (1 - sum(invalid) / sum(calls) if sum(calls) else 1.0) if valid else None
    metrics.append(AutoSpecMetric(name="tool_argument_valid_rate", status="MEASURED" if valid else "UNAVAILABLE",
                                  value=rate, unit="ratio", source=METRICS_VERSION))
    return metrics
