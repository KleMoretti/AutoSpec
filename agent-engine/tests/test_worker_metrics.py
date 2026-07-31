import pytest
from prometheus_client import CollectorRegistry

from runtime.worker import InvalidWorkflowCommandError, StreamMessage
from runtime.worker_metrics import WorkerMetrics
from runtime.worker_runner import WorkflowWorkerRunner


class FakeClient:
    def __init__(self):
        self.claimed_messages = []
        self.new_messages = []

    async def ensure_group(self, stream, group):
        pass

    async def claim_stale_commands(
        self, stream, group, consumer, minimum_idle_ms, count
    ):
        return self.claimed_messages

    async def read_commands(self, stream, group, consumer, block_ms, count):
        return self.new_messages

    async def publish_dead_letter(self, stream, source_stream, message, error):
        pass

    async def acknowledge(self, stream, group, message_id):
        pass


class OutcomeWorker:
    async def process(self, message):
        if message.message_id == "invalid-1":
            raise InvalidWorkflowCommandError("INVALID_JSON")


@pytest.mark.asyncio
async def test_runner_records_reclaimed_processed_and_dead_letter_outcomes():
    registry = CollectorRegistry()
    now = [100.0]
    metrics = WorkerMetrics(registry=registry, monotonic=lambda: now[0])
    client = FakeClient()
    client.claimed_messages = [StreamMessage("reclaimed-1", {"payload": "{}"})]
    runner = WorkflowWorkerRunner(
        client,
        OutcomeWorker(),
        consumer_name="worker-1",
        metrics=metrics,
    )

    assert await runner.run_once() == 1

    client.claimed_messages = []
    client.new_messages = [
        StreamMessage("invalid-1", {"payload": "{not-json"}),
        StreamMessage("fresh-1", {"payload": "{}"}),
    ]
    now[0] = 101.5

    assert await runner.run_once() == 2
    assert sample(registry, "autospec_worker_reclaimed_total") == 1
    assert sample(
        registry,
        "autospec_worker_commands_total",
        {"outcome": "processed"},
    ) == 2
    assert sample(
        registry,
        "autospec_worker_commands_total",
        {"outcome": "dead_lettered"},
    ) == 1
    assert sample(registry, "autospec_worker_dead_lettered_total") == 1
    assert sample(registry, "autospec_worker_inflight") == 0
    assert sample(registry, "autospec_worker_command_duration_seconds_count") == 3


def test_worker_liveness_and_heartbeat_delay_have_no_business_labels():
    registry = CollectorRegistry()
    now = [200.0]
    metrics = WorkerMetrics(registry=registry, monotonic=lambda: now[0])

    metrics.worker_started()
    now[0] = 207.0

    assert sample(registry, "autospec_worker_active") == 1
    assert sample(registry, "autospec_worker_heartbeat_delay_seconds") == 7

    metrics.pulse()
    metrics.worker_stopped()

    assert sample(registry, "autospec_worker_active") == 0
    assert sample(registry, "autospec_worker_heartbeat_delay_seconds") == 0
    for metric in registry.collect():
        for item in metric.samples:
            assert set(item.labels).issubset({"outcome", "le"})


def sample(registry, name, labels=None):
    value = registry.get_sample_value(name, labels or {})
    assert value is not None
    return value
