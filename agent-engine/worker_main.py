from __future__ import annotations

import asyncio
import logging
import os
import socket
from uuid import uuid4

from runtime.node_executor import NodeExecutor
from runtime.production_handlers import build_production_registry
from runtime.redis_stream_client import RedisWorkflowStreamClient
from runtime.worker import WorkflowStreamWorker
from runtime.worker_runner import WorkflowWorkerRunner


def build_runner() -> tuple[WorkflowWorkerRunner, RedisWorkflowStreamClient]:
    redis_url = os.getenv("REDIS_URL", "redis://localhost:6379/0")
    consumer_name = os.getenv(
        "WORKER_NAME", f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"
    )
    client = RedisWorkflowStreamClient.from_url(redis_url)
    worker = WorkflowStreamWorker(
        client,
        NodeExecutor(build_production_registry()),
        heartbeat_interval_seconds=float(os.getenv("WORKER_HEARTBEAT_SECONDS", "10")),
    )
    return WorkflowWorkerRunner(
        client,
        worker,
        consumer_name=consumer_name,
        claim_idle_ms=int(os.getenv("WORKER_CLAIM_IDLE_MS", "30000")),
        read_block_ms=int(os.getenv("WORKER_READ_BLOCK_MS", "5000")),
        batch_size=int(os.getenv("WORKER_BATCH_SIZE", "10")),
    ), client


async def run() -> None:
    runner, client = build_runner()
    try:
        await runner.run_forever()
    finally:
        await client.close()


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    asyncio.run(run())


if __name__ == "__main__":
    main()
