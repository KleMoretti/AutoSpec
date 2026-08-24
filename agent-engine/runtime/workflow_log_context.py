from __future__ import annotations

import logging
from contextlib import contextmanager
from contextvars import ContextVar, Token
from typing import Iterator, Protocol


LOG_FIELDS = (
    "traceId",
    "correlationId",
    "workflowRunId",
    "nodeRunId",
    "executionId",
)

_workflow_context: ContextVar[dict[str, str]] = ContextVar(
    "workflow_log_context",
    default={},
)


class WorkflowContextCarrier(Protocol):
    correlation_id: str | None
    traceparent: str | None
    workflow_run_id: int
    node_run_id: int
    execution_id: str


class WorkflowLogContextFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        context = _workflow_context.get()
        for field in LOG_FIELDS:
            if not hasattr(record, field):
                setattr(record, field, context.get(field, "-"))
        return True


@contextmanager
def bind_workflow_log_context(
    carrier: WorkflowContextCarrier,
) -> Iterator[None]:
    token = _workflow_context.set(
        {
            "traceId": _trace_id(carrier.traceparent),
            "correlationId": carrier.correlation_id or "-",
            "workflowRunId": str(carrier.workflow_run_id),
            "nodeRunId": str(carrier.node_run_id),
            "executionId": carrier.execution_id,
        }
    )
    try:
        yield
    finally:
        _workflow_context.reset(token)


def workflow_log_context() -> dict[str, str]:
    return dict(_workflow_context.get())


def install_workflow_log_filter() -> None:
    root_logger = logging.getLogger()
    for handler in root_logger.handlers:
        if not any(
            isinstance(existing, WorkflowLogContextFilter)
            for existing in handler.filters
        ):
            handler.addFilter(WorkflowLogContextFilter())


def _trace_id(traceparent: str | None) -> str:
    if traceparent is None:
        return "-"
    parts = traceparent.split("-")
    return parts[1] if len(parts) == 4 else "-"
