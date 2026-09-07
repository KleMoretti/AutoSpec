from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator

from schemas.agent_loop import AgentLoopResult, AgentStepRecord


_TRACE: ContextVar[AgentLoopResult | None] = ContextVar(
    "autospec_agent_loop_trace",
    default=None,
)


@contextmanager
def capture_agent_loop_trace() -> Iterator[list[AgentStepRecord]]:
    steps: list[AgentStepRecord] = []
    # A single-shot node must not emit a synthetic loop stop reason. The
    # bounded handler publishes a result only after it actually enters the
    # loop, while the executor still captures the same context boundary.
    token = _TRACE.set(None)
    try:
        yield steps
    finally:
        _TRACE.reset(token)


def publish_agent_loop_trace(result: AgentLoopResult) -> None:
    _TRACE.set(result)


def current_agent_loop_trace() -> AgentLoopResult | None:
    return _TRACE.get()
