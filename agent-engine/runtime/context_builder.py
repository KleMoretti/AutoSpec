from __future__ import annotations

from typing import Any, Mapping

from runtime.context_policy import apply_context_policy
from runtime.memory import ShortTermMemory, LongTermMemory, rebuild_summary
from schemas.memory import ConversationSummary


class ContextBuilder:
    """Assemble a bounded context from structured state, recent turns and evidence."""

    def __init__(
        self,
        short_term: ShortTermMemory,
        long_term: LongTermMemory | None = None,
    ) -> None:
        self._short_term = short_term
        self._long_term = long_term

    def build(
        self,
        *,
        user_id: str,
        task: Mapping[str, Any],
        structured_profile: Mapping[str, Any] | None = None,
        plan: list[str] | None = None,
        summary: ConversationSummary | None = None,
        policy: Mapping[str, Any] | None = None,
        recent_turns: int = 5,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        memory = (
            [record.model_dump(mode="json") for record in self._long_term.recall(user_id)]
            if self._long_term is not None
            else []
        )
        summary = summary or rebuild_summary(self._short_term.all())
        payload: dict[str, Any] = {
            "task": dict(task),
            "structured_profile": dict(structured_profile or {}),
            "plan": list(plan or []),
            "recent_turns": [
                message.model_dump(mode="json")
                for message in self._short_term.recent(recent_turns)
            ],
            "summary": summary.model_dump(mode="json") if summary is not None else None,
            "long_term_memory": memory,
        }
        return apply_context_policy("agent_context", payload, "BALANCED", policy)

