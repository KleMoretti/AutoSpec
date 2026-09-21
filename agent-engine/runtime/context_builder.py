from __future__ import annotations

from typing import Any, Mapping

from runtime.context_policy import apply_context_policy
from runtime.memory import ProjectMemory, ShortTermMemory, rebuild_summary
from schemas.memory import ConversationSummary


class ContextBuilder:
    """Assemble a bounded context from structured state, recent turns and evidence."""

    def __init__(
        self,
        short_term: ShortTermMemory,
        project_memory: ProjectMemory | None = None,
    ) -> None:
        self._short_term = short_term
        self._project_memory = project_memory

    def build(
        self,
        *,
        project_id: int,
        node_id: str,
        task: Mapping[str, Any],
        structured_profile: Mapping[str, Any] | None = None,
        plan: list[str] | None = None,
        summary: ConversationSummary | None = None,
        policy: Mapping[str, Any] | None = None,
        recent_turns: int = 5,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        memory = (
            [
                fact.model_dump(mode="json")
                for fact in self._project_memory.recall(
                    project_id, fact_types=_fact_types_for_node(node_id)
                )
            ]
            if self._project_memory is not None
            else []
        )
        summary = rebuild_summary(self._short_term.all(), previous=summary)
        payload: dict[str, Any] = {
            "task": dict(task),
            "structured_profile": dict(structured_profile or {}),
            "plan": list(plan or []),
            "recent_turns": [
                message.model_dump(mode="json")
                for message in self._short_term.recent(recent_turns)
            ],
            "summary": summary.model_dump(mode="json") if summary is not None else None,
            "project_memory": memory,
        }
        return apply_context_policy("agent_context", payload, "BALANCED", policy)


def _fact_types_for_node(node_id: str) -> set[str]:
    return {
        "product_manager": {"REQUIREMENT", "CONSTRAINT", "ARTIFACT"},
        "architect": {"REQUIREMENT", "CONSTRAINT", "DECISION", "ARTIFACT"},
        "backend_engineer": {
            "REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"
        },
        "frontend_engineer": {
            "REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"
        },
        "reviewer": {"REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"},
        "evaluator": {"REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"},
    }.get(node_id, {"REQUIREMENT", "DECISION", "CONSTRAINT", "ARTIFACT"})
