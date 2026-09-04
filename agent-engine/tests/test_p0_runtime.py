from pydantic import BaseModel
import pytest

from evaluation.case_catalog import list_evaluation_cases
from evaluation.runner import run_fixture_baseline
from runtime.agent_state import check_termination
from runtime.memory import InMemoryLongTermMemory, ShortTermMemory, rebuild_summary
from runtime.model_telemetry import capture_model_invocations, summarize_model_invocations
from runtime.tool_harness import ToolHarness, ToolRegistry
from schemas.agent_state import AgentState, TerminationPolicy
from schemas.memory import MemoryRecord
from schemas.workflow_spec import ToolPolicy, ToolRef


class ToolInput(BaseModel):
    value: int


class ToolOutput(BaseModel):
    doubled: int


def test_agent_state_termination_and_memory_are_bounded_and_isolated() -> None:
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
    message = short.append("user", "Keep the API decision traceable.", message_id="m-1")
    summary = rebuild_summary(short.all(), now_epoch_ms=2_000)
    assert summary is not None
    assert summary.source_message_ids == [message.message_id]

    long = InMemoryLongTermMemory()
    long.upsert(
        MemoryRecord(
            user_id="user-1",
            skill="api-design",
            assessment="strong",
            confidence=0.9,
            evidence_ref="m-1",
            source_session_id="session-1",
            created_at_epoch_ms=2_000,
        )
    )
    long.upsert(
        MemoryRecord(
            user_id="user-2",
            skill="api-design",
            assessment="weak",
            confidence=0.9,
            evidence_ref="m-2",
            source_session_id="session-2",
            created_at_epoch_ms=2_000,
        )
    )
    assert [item.user_id for item in long.recall("user-1")] == ["user-1"]


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


@pytest.mark.asyncio
async def test_offline_baseline_emits_six_node_trace() -> None:
    result = await run_fixture_baseline(
        [list_evaluation_cases()[0]],
        run_id="baseline-p0-test",
    )
    case_result = result.case_results[0]
    assert result.dataset_version == "autospec-v5-eval-v1"
    assert len(case_result.trace) >= 6
    assert {record["node"] for record in case_result.trace} >= {
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    }
