from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Iterator


@dataclass(frozen=True)
class ModelExecutionContract:
    execution_id: str
    prompt_key: str
    prompt_version: str
    prompt_checksum: str
    model_policy: dict[str, Any]
    deadline_epoch_ms: int
    protocol_version: int = 0
    node_id: str = "unknown"
    attempt: int = 1
    contract_hash: str | None = None
    context_policy: dict[str, Any] = field(default_factory=dict)
    retry_policy: dict[str, Any] = field(default_factory=dict)
    fallback_policy: dict[str, Any] = field(default_factory=dict)
    schema_version: str | None = None


_CONTRACT: ContextVar[ModelExecutionContract | None] = ContextVar(
    "autospec_model_execution_contract",
    default=None,
)


@contextmanager
def bind_model_execution_contract(
    contract: ModelExecutionContract | None,
) -> Iterator[None]:
    token = _CONTRACT.set(contract)
    try:
        yield
    finally:
        _CONTRACT.reset(token)


def current_model_execution_contract() -> ModelExecutionContract | None:
    return _CONTRACT.get()
