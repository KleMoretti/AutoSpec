from __future__ import annotations

import asyncio
import logging

from runtime.redis_stream_client import RedisWorkflowStreamClient
from runtime.worker import (
    COMMAND_DLQ_STREAM,
    COMMAND_STREAM,
    WORKER_GROUP,
    InvalidWorkflowCommandError,
    WorkflowStreamWorker,
)
from runtime.worker_metrics import NO_OP_WORKER_METRICS, WorkerMetricsRecorder


class WorkflowWorkerRunner:
    def __init__(
        self,
        client: RedisWorkflowStreamClient,
        worker: WorkflowStreamWorker,
        consumer_name: str,
        command_stream: str = COMMAND_STREAM,
        consumer_group: str = WORKER_GROUP,
        claim_idle_ms: int = 30000,
        read_block_ms: int = 5000,
        batch_size: int = 10,
        dead_letter_stream: str = COMMAND_DLQ_STREAM,
        metrics: WorkerMetricsRecorder = NO_OP_WORKER_METRICS,
    ) -> None:
        self._client = client
        self._worker = worker
        self._consumer_name = consumer_name
        self._command_stream = command_stream
        self._dead_letter_stream = dead_letter_stream
        self._consumer_group = consumer_group
        self._claim_idle_ms = claim_idle_ms
        self._read_block_ms = read_block_ms
        self._batch_size = batch_size
        self._metrics = metrics

    async def run_once(self) -> int:
        self._metrics.pulse()
        await self._client.ensure_group(self._command_stream, self._consumer_group)
        reclaimed = await self._client.claim_stale_commands(
            self._command_stream,
            self._consumer_group,
            self._consumer_name,
            self._claim_idle_ms,
            self._batch_size,
        )
        self._metrics.record_reclaimed(len(reclaimed))
        fresh = []
        if not reclaimed:
            fresh = await self._client.read_commands(
                self._command_stream,
                self._consumer_group,
                self._consumer_name,
                self._read_block_ms,
                self._batch_size,
            )
        messages = [*reclaimed, *fresh]
        for message in messages:
            started_at = self._metrics.command_started()
            outcome = "failed"
            try:
                await self._worker.process(message)
            except InvalidWorkflowCommandError as error:
                await self._client.publish_dead_letter(
                    self._dead_letter_stream,
                    self._command_stream,
                    message,
                    error,
                )
                await self._client.acknowledge(
                    self._command_stream,
                    self._consumer_group,
                    message.message_id,
                )
                self._metrics.record_dead_letter()
                outcome = "dead_lettered"
            else:
                outcome = "processed"
            finally:
                self._metrics.command_finished(started_at, outcome)
        return len(messages)

    async def run_forever(self, retry_delay_seconds: float = 1.0) -> None:
        self._metrics.worker_started()
        try:
            while True:
                try:
                    await self.run_once()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logging.getLogger(__name__).exception(
                        "workflow worker iteration failed"
                    )
                    await asyncio.sleep(retry_delay_seconds)
        finally:
            self._metrics.worker_stopped()
