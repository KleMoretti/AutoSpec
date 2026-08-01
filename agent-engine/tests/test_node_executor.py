import asyncio
from time import perf_counter

import pytest
from pydantic import BaseModel

from runtime.handler_registry import HandlerRegistry, UnknownHandlerError
from runtime.node_executor import NodeCommand, NodeExecutor


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
