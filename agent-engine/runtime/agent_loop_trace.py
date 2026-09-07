from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator

from schemas.agent_loop import AgentLoopResult, AgentStepRecord, StopReason


_TRACE: ContextVar[AgentLoopResult | None] = ContextVar(
    "autospec_agent_loop_trace",
    default=None,
)


@contextmanager
def capture_agent_loop_trace() -> Iterator[list[AgentStepRecord]]:
    steps: list[AgentStepRecord] = []
    token = _TRACE.set(
        AgentLoopResult(
            candidate=None,
            steps=[],
            stop_reason=StopReason.VALIDATION_FAILED,
            completed=False,
        )
    )
    try:
        yield steps
    finally:
        _TRACE.reset(token)


def publish_agent_loop_trace(result: AgentLoopResult) -> None:
    _TRACE.set(result)


def current_agent_loop_trace() -> AgentLoopResult | None:
    return _TRACE.get()
