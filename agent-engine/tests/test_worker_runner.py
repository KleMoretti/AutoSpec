import pytest

from runtime.worker import StreamMessage
from runtime.worker_runner import WorkflowWorkerRunner


class FakeClient:
    def __init__(self):
        self.groups = []
        self.new_messages = [StreamMessage("1-0", {"payload": "{}"})]
        self.claimed_messages = [StreamMessage("2-0", {"payload": "{}"})]

    async def ensure_group(self, stream, group):
        self.groups.append((stream, group))

    async def read_commands(self, stream, group, consumer, block_ms, count):
        return self.new_messages

    async def claim_stale_commands(
        self, stream, group, consumer, minimum_idle_ms, count
    ):
        return self.claimed_messages


class RecordingWorker:
    def __init__(self):
        self.processed = []

    async def process(self, message):
        self.processed.append(message.message_id)


@pytest.mark.asyncio
async def test_runner_creates_group_and_processes_new_and_reclaimed_messages():
    client = FakeClient()
    worker = RecordingWorker()
    runner = WorkflowWorkerRunner(
        client,
        worker,
        consumer_name="worker-1",
        claim_idle_ms=30000,
    )

    processed = await runner.run_once()

    assert client.groups == [("autospec.workflow.commands", "autospec-workers")]
    assert worker.processed == ["2-0", "1-0"]
    assert processed == 2
