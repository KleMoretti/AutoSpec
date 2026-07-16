import pytest

from runtime.worker import InvalidWorkflowCommandError, StreamMessage
from runtime.worker_runner import WorkflowWorkerRunner


class FakeClient:
    def __init__(self):
        self.groups = []
        self.new_messages = [StreamMessage("1-0", {"payload": "{}"})]
        self.claimed_messages = [StreamMessage("2-0", {"payload": "{}"})]
        self.read_count = 0
        self.dead_letters = []
        self.acknowledged = []

    async def ensure_group(self, stream, group):
        self.groups.append((stream, group))

    async def read_commands(self, stream, group, consumer, block_ms, count):
        self.read_count += 1
        return self.new_messages

    async def claim_stale_commands(
        self, stream, group, consumer, minimum_idle_ms, count
    ):
        return self.claimed_messages

    async def publish_dead_letter(
        self, stream, source_stream, message, error
    ):
        self.dead_letters.append((stream, source_stream, message.message_id, error))

    async def acknowledge(self, stream, group, message_id):
        self.acknowledged.append((stream, group, message_id))


class RecordingWorker:
    def __init__(self, invalid_message_ids=None, error=None):
        self.processed = []
        self.invalid_message_ids = set(invalid_message_ids or [])
        self.error = error

    async def process(self, message):
        self.processed.append(message.message_id)
        if message.message_id in self.invalid_message_ids:
            raise InvalidWorkflowCommandError("INVALID_JSON")
        if self.error:
            raise self.error


@pytest.mark.asyncio
async def test_runner_processes_reclaimed_messages_without_blocking_for_fresh_messages():
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
    assert client.read_count == 0
    assert worker.processed == ["2-0"]
    assert processed == 1


@pytest.mark.asyncio
async def test_runner_quarantines_invalid_command_and_continues_batch():
    client = FakeClient()
    client.claimed_messages = []
    client.new_messages = [
        StreamMessage("1-0", {"payload": "{not-json"}),
        StreamMessage("2-0", {"payload": "{}"}),
    ]
    worker = RecordingWorker(invalid_message_ids={"1-0"})
    runner = WorkflowWorkerRunner(
        client,
        worker,
        consumer_name="worker-1",
        dead_letter_stream="custom.commands.dlq",
    )

    processed = await runner.run_once()

    assert worker.processed == ["1-0", "2-0"]
    assert client.dead_letters[0][0:3] == (
        "custom.commands.dlq",
        "autospec.workflow.commands",
        "1-0",
    )
    assert client.dead_letters[0][3].error_type == "INVALID_JSON"
    assert client.acknowledged == [
        ("autospec.workflow.commands", "autospec-workers", "1-0")
    ]
    assert processed == 2


@pytest.mark.asyncio
async def test_runner_does_not_acknowledge_when_dead_letter_publication_fails():
    class FailingDeadLetterClient(FakeClient):
        async def publish_dead_letter(
            self, stream, source_stream, message, error
        ):
            raise ConnectionError("redis unavailable")

    client = FailingDeadLetterClient()
    client.claimed_messages = []
    worker = RecordingWorker(invalid_message_ids={"1-0"})
    runner = WorkflowWorkerRunner(client, worker, consumer_name="worker-1")

    with pytest.raises(ConnectionError, match="redis unavailable"):
        await runner.run_once()

    assert client.acknowledged == []
