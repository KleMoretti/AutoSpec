from __future__ import annotations

import json
import asyncio
import logging
import uuid
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Protocol

from opentelemetry import trace
from opentelemetry.trace import SpanKind, Status, StatusCode, Tracer
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from pydantic import ValidationError

from runtime.node_executor import NodeCommand, NodeExecutionEvent, NodeExecutor
from runtime.execution_ledger import (
    ExecutionClaimStatus,
    ExecutionLedger,
    InMemoryExecutionLedger,
)
from runtime.worker_metrics import NO_OP_WORKER_METRICS, WorkerMetricsRecorder
from runtime.workflow_log_context import bind_workflow_log_context


COMMAND_STREAM = "autospec.workflow.commands"
COMMAND_DLQ_STREAM = "autospec.workflow.commands.dlq"
EVENT_STREAM = "autospec.workflow.events"
WORKER_GROUP = "autospec-workers"

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class StreamMessage:
    message_id: str
    fields: dict[str, Any]


class InvalidWorkflowCommandError(ValueError):
    """A safely classified command protocol error suitable for DLQ metadata."""

    category = "PROTOCOL_VALIDATION"

    def __init__(self, error_type: str) -> None:
        super().__init__(error_type)
        self.error_type = error_type


class ExecutionAlreadyClaimedError(RuntimeError):
    pass


class LostExecutionFenceError(RuntimeError):
    pass


class WorkflowStreamClient(Protocol):
    async def publish_event(
        self, stream: str, event: NodeExecutionEvent
    ) -> None: ...

    async def acknowledge(
        self, stream: str, group: str, message_id: str
    ) -> None: ...


class WorkflowStreamWorker:
    def __init__(
        self,
        client: WorkflowStreamClient,
        executor: NodeExecutor,
        command_stream: str = COMMAND_STREAM,
        event_stream: str = EVENT_STREAM,
        consumer_group: str = WORKER_GROUP,
        heartbeat_interval_seconds: float = 10.0,
        consumer_name: str | None = None,
        execution_ledger: ExecutionLedger | None = None,
        metrics: WorkerMetricsRecorder = NO_OP_WORKER_METRICS,
        tracer: Tracer | None = None,
    ) -> None:
        self._client = client
        self._executor = executor
        self._command_stream = command_stream
        self._event_stream = event_stream
        self._consumer_group = consumer_group
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._consumer_name = consumer_name or f"worker-{uuid.uuid4()}"
        shared_ledger = execution_ledger or getattr(client, "execution_ledger", None)
        if shared_ledger is None:
            shared_ledger = InMemoryExecutionLedger()
            try:
                setattr(client, "execution_ledger", shared_ledger)
            except (AttributeError, TypeError):
                pass
        self._execution_ledger = shared_ledger
        self._lease_ms = max(30_000, round(heartbeat_interval_seconds * 3_000))
        self._metrics = metrics
        self._tracer = tracer or trace.get_tracer("autospec.agent-worker")

    async def process(self, message: StreamMessage) -> NodeExecutionEvent:
        command = self._parse_command(message)
        parent_context = TraceContextTextMapPropagator().extract(
            {
                key: value
                for key, value in {
                    "traceparent": command.traceparent,
                    "tracestate": command.tracestate,
                }.items()
                if value is not None
            }
        )
        with self._tracer.start_as_current_span(
            "workflow.node.execute",
            context=parent_context,
            kind=SpanKind.CONSUMER,
            attributes={
                "messaging.system": "redis",
                "messaging.message.id": command.event_id,
                "autospec.workflow.run.id": command.workflow_run_id,
                "autospec.workflow.node.run.id": command.node_run_id,
                "autospec.workflow.node.id": command.node_id,
                "autospec.workflow.execution.id": command.execution_id,
                "autospec.workflow.handler.key": command.handler_key,
                "autospec.workflow.handler.version": command.handler_version,
                "autospec.correlation.id": command.correlation_id or "",
            },
        ) as span:
            with bind_workflow_log_context(command):
                try:
                    claim = await self._execution_ledger.claim(
                        command.execution_id,
                        self._consumer_name,
                        self._lease_ms,
                    )
                    if claim.status == ExecutionClaimStatus.BUSY:
                        raise ExecutionAlreadyClaimedError(
                            f"execution is owned by another live worker: {command.execution_id}"
                        )
                    if claim.status == ExecutionClaimStatus.CACHED:
                        if claim.cached_event is None:
                            raise RuntimeError("completed execution ledger entry has no cached event")
                        event = claim.cached_event
                        await self._client.publish_event(self._event_stream, event)
                        await self._client.acknowledge(
                            self._command_stream,
                            self._consumer_group,
                            message.message_id,
                        )
                        span.set_attribute("autospec.workflow.execution.cached", True)
                        span.set_attribute("autospec.workflow.event.type", event.event_type)
                        return event
                    command = command.model_copy(
                        update={
                            "fencing_token": claim.fencing_token,
                            "worker_id": self._consumer_name,
                        }
                    )
                    heartbeat_task = asyncio.create_task(
                        self._publish_heartbeats(
                            command,
                            message.message_id,
                            self._consumer_name,
                        )
                    )
                    try:
                        event = await self._executor.execute(command)
                    finally:
                        heartbeat_task.cancel()
                        with suppress(asyncio.CancelledError):
                            await heartbeat_task
                    completed = await self._execution_ledger.complete(
                        command.execution_id,
                        self._consumer_name,
                        command.fencing_token,
                        event,
                    )
                    if not completed:
                        raise LostExecutionFenceError(
                            f"execution fence was superseded: {command.execution_id}"
                        )
                    await self._client.publish_event(self._event_stream, event)
                    await self._client.acknowledge(
                        self._command_stream, self._consumer_group, message.message_id
                    )
                except Exception:
                    LOGGER.exception("workflow command processing failed")
                    raise
                span.set_attribute("autospec.workflow.event.type", event.event_type)
                if event.event_type == "NODE_FAILED":
                    span.set_status(
                        Status(StatusCode.ERROR, event.error_code or "NODE_FAILED")
                    )
                LOGGER.info(
                    "workflow command processed event_type=%s",
                    event.event_type,
                )
                return event

    async def _publish_heartbeats(
        self,
        command: NodeCommand,
        message_id: str,
        consumer_name: str | None,
    ) -> None:
        sequence = 0
        while True:
            await asyncio.sleep(self._heartbeat_interval_seconds)
            sequence += 1
            heartbeat = NodeExecutionEvent(
                event_id=f"{command.execution_id}:heartbeat:{sequence}",
                source_event_id=command.event_id,
                event_type="NODE_HEARTBEAT",
                workflow_run_id=command.workflow_run_id,
                node_run_id=command.node_run_id,
                node_id=command.node_id,
                revision=command.revision,
                attempt=command.attempt,
                execution_id=command.execution_id,
                duration_ms=round(sequence * self._heartbeat_interval_seconds * 1000),
                correlation_id=command.correlation_id,
                traceparent=command.traceparent,
                tracestate=command.tracestate,
                protocol_version=command.protocol_version,
                contract_hash=command.contract_hash,
                input_schema=command.input_schema,
                input_schema_hash=command.input_schema_hash,
                output_schema=command.output_schema,
                output_schema_hash=command.output_schema_hash,
                prompt_key=command.prompt_key,
                prompt_version=command.prompt_version,
                prompt_checksum=command.prompt_checksum,
                execution_bundle_hash=command.execution_bundle_hash,
                fencing_token=command.fencing_token,
                worker_id=command.worker_id,
            )
            renewed = await self._execution_ledger.renew(
                command.execution_id,
                self._consumer_name,
                command.fencing_token,
                self._lease_ms,
            )
            if not renewed:
                raise LostExecutionFenceError(
                    f"execution fence was superseded: {command.execution_id}"
                )
            touch_pending = getattr(self._client, "touch_pending", None)
            if touch_pending is not None and consumer_name is not None:
                try:
                    await touch_pending(
                        self._command_stream,
                        self._consumer_group,
                        consumer_name,
                        message_id,
                    )
                except Exception:  # Redis stream lease is secondary to the ledger fence.
                    LOGGER.warning("unable to refresh command pending lease", exc_info=True)
            try:
                await self._client.publish_event(self._event_stream, heartbeat)
            except Exception:  # Terminal publication is retried from the durable ledger.
                LOGGER.warning("unable to publish workflow heartbeat", exc_info=True)
            self._metrics.pulse()

    def _parse_command(self, message: StreamMessage) -> NodeCommand:
        if "payload" not in message.fields:
            raise InvalidWorkflowCommandError("MISSING_PAYLOAD")
        payload = message.fields["payload"]
        if isinstance(payload, bytes):
            try:
                payload = payload.decode("utf-8")
            except UnicodeDecodeError:
                raise InvalidWorkflowCommandError("INVALID_ENCODING") from None
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                raise InvalidWorkflowCommandError("INVALID_JSON") from None
        if not isinstance(payload, dict):
            raise InvalidWorkflowCommandError("PAYLOAD_NOT_OBJECT")
        try:
            return NodeCommand.model_validate(payload)
        except ValidationError:
            raise InvalidWorkflowCommandError("SCHEMA_VALIDATION") from None
