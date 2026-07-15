from __future__ import annotations

import asyncio
import logging

from runtime.redis_stream_client import RedisWorkflowStreamClient
from runtime.worker import COMMAND_STREAM, WORKER_GROUP, WorkflowStreamWorker


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
    ) -> None:
        self._client = client
        self._worker = worker
        self._consumer_name = consumer_name
        self._command_stream = command_stream
        self._consumer_group = consumer_group
        self._claim_idle_ms = claim_idle_ms
        self._read_block_ms = read_block_ms
        self._batch_size = batch_size

    async def run_once(self) -> int:
        await self._client.ensure_group(self._command_stream, self._consumer_group)
        reclaimed = await self._client.claim_stale_commands(
            self._command_stream,
            self._consumer_group,
            self._consumer_name,
            self._claim_idle_ms,
            self._batch_size,
        )
        fresh = await self._client.read_commands(
            self._command_stream,
            self._consumer_group,
            self._consumer_name,
            self._read_block_ms,
            self._batch_size,
        )
        messages = [*reclaimed, *fresh]
        for message in messages:
            await self._worker.process(message)
        return len(messages)

    async def run_forever(self, retry_delay_seconds: float = 1.0) -> None:
        while True:
            try:
                await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                logging.getLogger(__name__).exception("workflow worker iteration failed")
                await asyncio.sleep(retry_delay_seconds)
