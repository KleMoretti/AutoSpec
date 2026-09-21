from pydantic import BaseModel
import pytest

from runtime.agent_state import check_termination
from runtime.memory import InMemoryProjectMemory, ShortTermMemory, rebuild_summary
from runtime.model_telemetry import capture_model_invocations, summarize_model_invocations
from runtime.tool_harness import ToolHarness, ToolRegistry
from schemas.agent_state import AgentState, TerminationPolicy
from schemas.memory import MemoryProvenance, ProjectMemoryFact
from schemas.workflow_spec import ToolPolicy, ToolRef


class ToolInput(BaseModel):
    value: int


class ToolOutput(BaseModel):
    doubled: int


def test_agent_state_termination_and_project_memory_are_bounded_and_isolated() -> None:
    state = AgentState(
        workflow_key="autospec-v5",
        workflow_version="v5",
        session_id="session-1",
        current_stage="reviewer",
        step_count=2,
        started_at_epoch_ms=1_000,
    )
    assert check_termination(
        state, TerminationPolicy(max_steps=2), now_epoch_ms=1_001
    ).reason == "STEP_LIMIT"
    assert check_termination(
        state, TerminationPolicy(max_wall_time_ms=100), now_epoch_ms=1_100
    ).reason == "DEADLINE"

    short = ShortTermMemory()
    message = short.append(
        "user",
        "Decision: Keep the API decision traceable.\nOpen question: which roles approve it?",
        message_id="m-1",
    )
    summary = rebuild_summary(short.all(), now_epoch_ms=2_000)
    assert summary is not None
    assert summary.source_message_ids == [message.message_id]
    assert {entry.topic for entry in summary.entries} == {"DECISION", "OPEN_QUESTION"}

    short.append("assistant", "Constraint: approval requires an OWNER role.", message_id="m-2")
    updated = rebuild_summary(short.all(), previous=summary, now_epoch_ms=3_000)
    assert updated is not None
    assert updated.source_message_ids == ["m-1", "m-2"]
    assert {entry.topic for entry in updated.entries} == {
        "DECISION",
        "OPEN_QUESTION",
        "CONSTRAINT",
    }

    memory = InMemoryProjectMemory()
    memory.upsert(
        ProjectMemoryFact(
            project_id=1,
            fact_type="API",
            fact_key="POST /api/approvals",
            value={"method": "POST", "path": "/api/approvals"},
            provenance=MemoryProvenance(
                source_type="FIXTURE",
                source_ref="artifact:1:v1",
            ),
            valid_from_epoch_ms=2_000,
        )
    )
    memory.upsert(
        ProjectMemoryFact(
            project_id=2,
            fact_type="API",
            fact_key="POST /api/approvals",
            value={"method": "POST", "path": "/api/approvals"},
            provenance=MemoryProvenance(
                source_type="FIXTURE",
                source_ref="artifact:2:v1",
            ),
            valid_from_epoch_ms=2_000,
        )
    )
    assert [item.project_id for item in memory.recall(1)] == [1]


@pytest.mark.asyncio
async def test_tool_harness_blocks_bad_input_and_deduplicates_success() -> None:
    calls = 0

    def handler(value: ToolInput) -> ToolOutput:
        nonlocal calls
        calls += 1
        return ToolOutput(doubled=value.value * 2)

    registry = ToolRegistry()
    registry.register(
        "math.double",
        "v1",
        ToolInput,
        ToolOutput,
        handler,
        description="Double an integer.",
    )
    policy = ToolPolicy(
        enabled=True,
        allowed_tools=[ToolRef(name="math.double", version="v1")],
        max_calls=2,
    )
    harness = ToolHarness(registry)
    with capture_model_invocations(execution_id="tool-exec", attempt=1) as invocations:
        first = await harness.execute(
            {
                "name": "math.double",
                "version": "v1",
                "arguments": {"value": 3},
                "idempotency_key": "same-call",
            },
            policy=policy,
        )
        second = await harness.execute(
            {
                "name": "math.double",
                "version": "v1",
                "arguments": {"value": 3},
                "idempotency_key": "same-call",
            },
            policy=policy,
        )
        invalid = await harness.execute_safe(
            {
                "name": "math.double",
                "version": "v1",
                "arguments": {"value": "bad"},
            },
            policy=policy,
        )

    assert first.result == {"doubled": 6}
    assert second.cached is True
    assert calls == 1
    assert invalid.status == "FAILED"
    assert invalid.error_code == "TOOL_INPUT_INVALID"
    assert summarize_model_invocations(invocations)["tool_call_count"] == 3
