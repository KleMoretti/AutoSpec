import pytest

from runtime.execution_ledger import (
    ExecutionClaimStatus,
    InMemoryExecutionLedger,
)
from runtime.node_executor import NodeExecutionEvent


def terminal_event(fence: int) -> NodeExecutionEvent:
    return NodeExecutionEvent(
        event_id="7:fixture:1:1:succeeded",
        source_event_id="command-1",
        event_type="NODE_SUCCEEDED",
        workflow_run_id=7,
        node_run_id=11,
        node_id="fixture",
        revision=1,
        attempt=1,
        execution_id="7:fixture:1:1",
        duration_ms=12,
        output_payload={"doubled": 6},
        fencing_token=fence,
    )


@pytest.mark.asyncio
async def test_ledger_fences_expired_owner_and_replays_cached_terminal_result():
    current_time_ms = 1_000
    ledger = InMemoryExecutionLedger(lambda: current_time_ms)

    first = await ledger.claim("execution-1", "worker-a", lease_ms=1)
    busy = await ledger.claim("execution-1", "worker-b", lease_ms=1)
    assert first.status == ExecutionClaimStatus.ACQUIRED
    assert first.fencing_token == 1
    assert busy.status == ExecutionClaimStatus.BUSY

    current_time_ms += 2
    second = await ledger.claim("execution-1", "worker-b", lease_ms=1000)
    assert second.status == ExecutionClaimStatus.ACQUIRED
    assert second.fencing_token == 2
    assert await ledger.complete(
        "execution-1", "worker-a", first.fencing_token, terminal_event(1)
    ) is False
    assert await ledger.complete(
        "execution-1", "worker-b", second.fencing_token, terminal_event(2)
    ) is True

    replay = await ledger.claim("execution-1", "worker-c", lease_ms=1000)
    assert replay.status == ExecutionClaimStatus.CACHED
    assert replay.fencing_token == 2
    assert replay.cached_event == terminal_event(2)
