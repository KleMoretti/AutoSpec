from __future__ import annotations

import asyncio
import logging
import os
import socket
from uuid import uuid4

from prometheus_client import start_http_server

from runtime.node_executor import NodeExecutor
from runtime.production_handlers import build_production_registry
from runtime.redis_stream_client import (
    DEFAULT_DEAD_LETTER_STREAM_MAX_LENGTH,
    DEFAULT_EVENT_STREAM_MAX_LENGTH,
    RedisWorkflowStreamClient,
)
from runtime.worker import COMMAND_DLQ_STREAM, WorkflowStreamWorker
from runtime.worker_metrics import WorkerMetrics
from runtime.worker_runner import WorkflowWorkerRunner
from runtime.workflow_log_context import install_workflow_log_filter
from runtime.worker_tracing import configure_worker_tracer


def build_runner(
    metrics: WorkerMetrics | None = None,
) -> tuple[WorkflowWorkerRunner, RedisWorkflowStreamClient]:
    redis_url = os.getenv("REDIS_URL", "redis://localhost:6379/0")
    consumer_name = os.getenv(
        "WORKER_NAME", f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"
    )
    metrics = metrics or WorkerMetrics()
    client = RedisWorkflowStreamClient.from_url(
        redis_url,
        event_stream_max_length=int(
            os.getenv(
                "WORKFLOW_EVENT_STREAM_MAX_LENGTH",
                str(DEFAULT_EVENT_STREAM_MAX_LENGTH),
            )
        ),
        dead_letter_stream_max_length=int(
            os.getenv(
                "WORKFLOW_COMMAND_DLQ_MAX_LENGTH",
                str(DEFAULT_DEAD_LETTER_STREAM_MAX_LENGTH),
            )
        ),
    )
    worker = WorkflowStreamWorker(
        client,
        NodeExecutor(build_production_registry()),
        heartbeat_interval_seconds=float(os.getenv("WORKER_HEARTBEAT_SECONDS", "10")),
        metrics=metrics,
        tracer=configure_worker_tracer(),
    )
    return WorkflowWorkerRunner(
        client,
        worker,
        consumer_name=consumer_name,
        dead_letter_stream=os.getenv("WORKER_DLQ_STREAM", COMMAND_DLQ_STREAM),
        claim_idle_ms=int(os.getenv("WORKER_CLAIM_IDLE_MS", "30000")),
        read_block_ms=int(os.getenv("WORKER_READ_BLOCK_MS", "5000")),
        batch_size=int(os.getenv("WORKER_BATCH_SIZE", "10")),
        metrics=metrics,
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
        format=(
            "%(asctime)s %(levelname)s %(name)s "
            "traceId=%(traceId)s correlationId=%(correlationId)s "
            "workflowRunId=%(workflowRunId)s nodeRunId=%(nodeRunId)s "
            "executionId=%(executionId)s %(message)s"
        ),
    )
    install_workflow_log_filter()
    metrics = WorkerMetrics()
    start_http_server(
        port=int(os.getenv("WORKER_METRICS_PORT", "9100")),
        addr=os.getenv("WORKER_METRICS_HOST", "127.0.0.1"),
        registry=metrics.registry,
    )
    runner, client = build_runner(metrics)
    asyncio.run(run_with(runner, client))


async def run_with(
    runner: WorkflowWorkerRunner,
    client: RedisWorkflowStreamClient,
) -> None:
    try:
        await runner.run_forever()
    finally:
        await client.close()


if __name__ == "__main__":
    main()
