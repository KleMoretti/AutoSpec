import asyncio
import time
from time import perf_counter

import pytest
from pydantic import BaseModel

from runtime.handler_registry import HandlerRegistry, UnknownHandlerError
from runtime.model_telemetry import ModelInvocationTelemetry, record_model_invocation
from runtime.context_policy import apply_context_policy
from runtime.node_executor import NodeCommand, NodeExecutor, contract_fingerprint


class FixtureInput(BaseModel):
    value: int


class FixtureOutput(BaseModel):
    doubled: int


def command(**overrides):
    payload = {
        "event_id": "event-1",
        "workflow_run_id": 7,
        "node_run_id": 11,
        "node_id": "fixture",
        "revision": 1,
        "attempt": 1,
        "execution_id": "7:fixture:1:1",
        "handler_key": "FixtureAgent",
        "handler_version": "v1",
        "timeout_ms": 1000,
        "input_payload": {"value": 3},
        "correlation_id": "123e4567-e89b-12d3-a456-426614174000",
        "traceparent": "00-123e4567e89b12d3a456426614174000-123e4567e89b12d3-01",
        "tracestate": "autospec=backend",
    }
    payload.update(overrides)
    return NodeCommand.model_validate(payload)


def test_registry_rejects_unknown_handler_version():
    registry = HandlerRegistry()

    with pytest.raises(UnknownHandlerError, match="FixtureAgent:v9"):
        registry.resolve("FixtureAgent", "v9")


@pytest.mark.asyncio
async def test_executor_validates_input_and_output_and_returns_success_event():
    registry = HandlerRegistry()
    registry.register(
        "FixtureAgent",
        "v1",
        FixtureInput,
        FixtureOutput,
        lambda input_value: {"doubled": input_value.value * 2},
    )

    event = await NodeExecutor(registry).execute(command())

    assert event.event_type == "NODE_SUCCEEDED"
    assert event.execution_id == "7:fixture:1:1"
    assert event.output_payload == {"doubled": 6}
    assert event.error_code is None
    assert event.correlation_id == "123e4567-e89b-12d3-a456-426614174000"
    assert event.traceparent == command().traceparent
    assert event.tracestate == "autospec=backend"


@pytest.mark.asyncio
async def test_executor_emits_aggregated_model_usage_in_success_event():
    def handler(input_value):
        record_model_invocation(
            ModelInvocationTelemetry(
                provider_key="openai-compatible",
                model_name="model-a",
                prompt_key="FixtureAgent_v1",
                input_tokens=90,
                output_tokens=10,
                cache_tokens=15,
                estimated_cost=0.0123,
            )
        )
        return {"doubled": input_value.value * 2}

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, handler)

    event = await NodeExecutor(registry).execute(command())

    assert event.event_type == "NODE_SUCCEEDED"
    assert event.provider_key == "openai-compatible"
    assert event.model_name == "model-a"
    assert event.prompt_key == "FixtureAgent_v1"
    assert event.model_call_count == 1
    assert event.input_tokens == 90
    assert event.output_tokens == 10
    assert event.cache_tokens == 15
    assert event.estimated_cost == pytest.approx(0.0123)


@pytest.mark.asyncio
async def test_executor_emits_context_compaction_manifest() -> None:
    def handler(input_value):
        apply_context_policy(
            "fixture",
            {"requirement": "large " * 5_000},
            "FAST",
        )
        return {"doubled": input_value.value * 2}

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, handler)

    event = await NodeExecutor(registry).execute(command())

    assert event.context_manifest is not None
    assert event.context_manifest["policy"] == "fixture:FAST:v1"
    assert event.context_manifest["trimmed"] is True


@pytest.mark.asyncio
async def test_executor_classifies_invalid_input_without_calling_handler():
    called = False

    def handler(_input):
        nonlocal called
        called = True
        return {"doubled": 1}

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, handler)

    event = await NodeExecutor(registry).execute(command(input_payload={"value": "invalid"}))

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "VALIDATION_ERROR"
    assert called is False


@pytest.mark.asyncio
async def test_executor_classifies_invalid_handler_output():
    registry = HandlerRegistry()
    registry.register(
        "FixtureAgent",
        "v1",
        FixtureInput,
        FixtureOutput,
        lambda _input: {"missing": True},
    )

    event = await NodeExecutor(registry).execute(command())

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "OUTPUT_SCHEMA_ERROR"


@pytest.mark.asyncio
async def test_executor_times_out_slow_model_within_runtime_budget():
    async def slow_handler(_input):
        await asyncio.sleep(0.05)
        return {"doubled": 6}

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, slow_handler)

    started = perf_counter()
    event = await NodeExecutor(registry).execute(command(timeout_ms=10))
    elapsed_ms = round((perf_counter() - started) * 1000)

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "MODEL_TIMEOUT"
    assert event.error_message == "node exceeded timeout of 10 ms"
    assert event.duration_ms < 100
    assert elapsed_ms < 100


@pytest.mark.asyncio
async def test_executor_timeout_also_applies_to_synchronous_handlers():
    def slow_handler(_input):
        time.sleep(0.05)
        return {"doubled": 6}

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, slow_handler)

    started = perf_counter()
    event = await NodeExecutor(registry).execute(command(timeout_ms=10))
    elapsed_ms = round((perf_counter() - started) * 1000)

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "MODEL_TIMEOUT"
    assert elapsed_ms < 40


@pytest.mark.asyncio
async def test_executor_preserves_typed_handler_error_code():
    class QualityGateBlocked(RuntimeError):
        error_code = "QUALITY_GATE_BLOCKED"

    def blocked_handler(_input):
        raise QualityGateBlocked("REQ-001 has no API evidence")

    registry = HandlerRegistry()
    registry.register("FixtureAgent", "v1", FixtureInput, FixtureOutput, blocked_handler)

    event = await NodeExecutor(registry).execute(command())

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "QUALITY_GATE_BLOCKED"
    assert "REQ-001" in event.error_message


def contracted_command(registry: HandlerRegistry, **overrides) -> NodeCommand:
    registration = registry.resolve("FixtureAgent", "v1")
    payload = {
        "event_id": "event-contract-1",
        "workflow_run_id": 7,
        "node_run_id": 11,
        "node_id": "fixture",
        "revision": 1,
        "attempt": 1,
        "execution_id": "7:fixture:1:1",
        "handler_key": "FixtureAgent",
        "handler_version": "v1",
        "timeout_ms": 1000,
        "input_payload": {"value": 3},
        "protocol_version": 1,
        "contract_hash": "0" * 64,
        "input_schema": registration.input_schema,
        "input_schema_hash": registration.input_schema_hash,
        "output_schema": registration.output_schema,
        "output_schema_hash": registration.output_schema_hash,
        "prompt_key": registration.prompt_key,
        "prompt_version": registration.prompt_version,
        "prompt_checksum": registration.prompt_checksum,
        "model_policy": {"route_key": "balanced", "temperature": 0},
        "retry_policy": {"max_attempts": 1},
        "fallback": {},
        "deadline_epoch_ms": round(time.time() * 1000) + 1000,
        "fencing_token": 4,
        "worker_id": "worker-a",
    }
    payload.update(overrides)
    provisional = NodeCommand.model_validate(payload)
    if "contract_hash" not in overrides:
        provisional = provisional.model_copy(
            update={"contract_hash": contract_fingerprint(provisional)}
        )
    return provisional


@pytest.mark.asyncio
async def test_executor_enforces_and_echoes_frozen_contract():
    registry = HandlerRegistry()
    registry.register(
        "FixtureAgent",
        "v1",
        FixtureInput,
        FixtureOutput,
        lambda value: {"doubled": value.value * 2},
        input_schema="FixtureInput",
        output_schema="FixtureOutput",
        prompt_key="fixture",
        prompt_version="v1",
        prompt_checksum="1" * 64,
    )

    event = await NodeExecutor(registry).execute(contracted_command(registry))

    assert event.event_type == "NODE_SUCCEEDED"
    assert event.contract_hash is not None
    assert event.input_schema == "FixtureInput"
    assert event.output_schema == "FixtureOutput"
    assert event.prompt_key == "fixture"
    assert event.prompt_version == "v1"
    assert event.prompt_checksum == "1" * 64
    assert event.fencing_token == 4
    assert event.worker_id == "worker-a"


@pytest.mark.asyncio
async def test_executor_rejects_contract_hash_mismatch_without_calling_handler():
    called = False

    def handler(value):
        nonlocal called
        called = True
        return {"doubled": value.value * 2}

    registry = HandlerRegistry()
    registry.register(
        "FixtureAgent",
        "v1",
        FixtureInput,
        FixtureOutput,
        handler,
        input_schema="FixtureInput",
        output_schema="FixtureOutput",
        prompt_key="fixture",
        prompt_version="v1",
        prompt_checksum="1" * 64,
    )

    event = await NodeExecutor(registry).execute(
        contracted_command(registry, contract_hash="f" * 64)
    )

    assert event.event_type == "NODE_FAILED"
    assert event.error_code == "CONTRACT_MISMATCH"
    assert called is False
