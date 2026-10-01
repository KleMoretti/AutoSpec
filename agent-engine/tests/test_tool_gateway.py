import asyncio
import hashlib
import json
from pathlib import Path

import httpx
import pytest
from pydantic import BaseModel

from runtime.tool_gateway import (
    HttpToolGatewayClient,
    InMemoryToolGateway,
    register_controlled_gateway_tools,
)
from runtime.tool_harness import ToolHarness, ToolRegistry
from runtime.tool_harness import ToolRuntimeContext, bind_tool_runtime_context, execute_current_tool
from runtime.execution_context import ModelExecutionContract, bind_model_execution_contract
from schemas.tool_gateway import ToolGatewayRequest
from schemas.workflow_spec import ToolPolicy, ToolRef


class Input(BaseModel):
    query: str


class Output(BaseModel):
    answer: str


def policy() -> ToolPolicy:
    return ToolPolicy(
        enabled=True,
        allowed_tools=[ToolRef(name="knowledge.search", version="v1")],
        max_calls=2,
    )


def request(policy_hash: str, *, key: str = "tool-call-1", deadline: int = 9_999_999_999_999) -> ToolGatewayRequest:
    arguments = {"query": "approved API"}
    normalized = hashlib.sha256(
        json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return ToolGatewayRequest(
        request_id=f"request:{key}",
        execution_id="run:node:1",
        workflow_run_id=7,
        node_run_id=8,
        node_id="architect",
        actor_user_id="user-1",
        project_id="project-7",
        fencing_token=1,
        deadline_epoch_ms=deadline,
        policy_hash=policy_hash,
        idempotency_key=key,
        name="knowledge.search",
        version="v1",
        arguments=arguments,
        normalized_params_hash=normalized,
        max_result_bytes=32_000,
    )


@pytest.mark.asyncio
async def test_gateway_enforces_policy_and_returns_one_cached_result() -> None:
    calls = 0
    registry = ToolRegistry()

    def handler(value: Input) -> Output:
        nonlocal calls
        calls += 1
        return Output(answer=value.query.upper())

    registry.register(
        "knowledge.search",
        "v1",
        Input,
        Output,
        handler,
    )
    gateway = InMemoryToolGateway(ToolHarness(registry))
    frozen = policy()
    policy_hash = gateway.register_policy(frozen)

    first = await gateway.execute(request(policy_hash), policy=frozen)
    second = await gateway.execute(request(policy_hash), policy=frozen)

    assert first.status == "SUCCEEDED"
    assert first.result == {"answer": "APPROVED API"}
    assert second.cached is True
    assert calls == 1


@pytest.mark.asyncio
async def test_gateway_rejects_deadline_and_idempotency_conflict() -> None:
    registry = ToolRegistry()
    registry.register("knowledge.search", "v1", Input, Output, lambda value: Output(answer=value.query))
    gateway = InMemoryToolGateway(ToolHarness(registry), clock=lambda: 10_000)
    frozen = policy()
    policy_hash = gateway.register_policy(frozen)

    expired = await gateway.execute(request(policy_hash, deadline=10_000), policy=frozen)
    assert expired.error_code == "TOOL_DEADLINE_EXCEEDED"

    valid = request(policy_hash, key="same-key", deadline=99_999)
    first = await gateway.execute(valid, policy=frozen)
    conflict = valid.model_copy(update={"request_id": "request:other", "arguments": {"query": "different"}})
    conflict = conflict.model_copy(update={
        "normalized_params_hash": hashlib.sha256(b'{"query":"different"}').hexdigest(),
    })
    second = await gateway.execute(conflict, policy=frozen)

    assert first.status == "SUCCEEDED"
    assert second.error_code == "IDEMPOTENCY_CONFLICT"


@pytest.mark.asyncio
async def test_http_gateway_sends_internal_service_token_and_envelope() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured["token"] = request.headers["X-AutoSpec-Service-Token"]
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "request_id": "request:1",
                "idempotency_key": "tool-call-1",
                "status": "SUCCEEDED",
                "result": {"sources": []},
                "result_hash": "a" * 64,
            },
        )

    client = HttpToolGatewayClient(
        "http://backend:8080",
        "service-token",
        transport=httpx.MockTransport(handler),
    )
    response = await client.execute(request("b" * 64))

    assert response.status == "SUCCEEDED"
    assert captured["token"] == "service-token"
    assert captured["body"]["project_id"] == "project-7"
    assert captured["body"]["name"] == "knowledge.search"


def test_production_registry_only_contains_fixed_gateway_catalog() -> None:
    registry = ToolRegistry()
    register_controlled_gateway_tools(registry, object())

    names = {(item["name"], item["version"]) for item in registry.describe()}
    assert names == {
        ("artifact.get", "v1"),
        ("bundle.verify", "v1"),
        ("contract.lookup", "v1"),
        ("knowledge.search", "v1"),
        ("spec.verify", "v1"),
        ("trace.query", "v1"),
    }


@pytest.mark.asyncio
async def test_in_memory_gateway_serializes_concurrent_duplicate_idempotency_keys() -> None:
    calls = 0
    registry = ToolRegistry()

    async def handler(value: Input) -> Output:
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.01)
        return Output(answer=value.query)

    registry.register("knowledge.search", "v1", Input, Output, handler)
    gateway = InMemoryToolGateway(ToolHarness(registry))
    frozen = policy()
    policy_hash = gateway.register_policy(frozen)
    first, second = await asyncio.gather(
        gateway.execute(request(policy_hash, key="concurrent"), policy=frozen),
        gateway.execute(request(policy_hash, key="concurrent"), policy=frozen),
    )
    assert {first.status, second.status} == {"SUCCEEDED"}
    assert sum(result.cached for result in (first, second)) == 1
    assert calls == 1


@pytest.mark.asyncio
async def test_http_tool_hash_includes_defaults_matching_control_plane_normalization() -> None:
    spec = json.loads((Path(__file__).resolve().parents[1] / "contracts" /
                       "autospec-v5-agent-execution-v3-c.workflow.json").read_text())
    node = next(node for node in spec["nodes"] if node["node_id"] == "backend_engineer")
    raw_policy = node["tool_policy"]
    typed_policy = ToolPolicy.model_validate(raw_policy)
    expected = "c917e219df519108526e1dda8091c26d2060dd1a5743ef58dd07e0f830d490bd"
    assert typed_policy.model_dump(mode="json") != raw_policy  # Java and Python both include retry defaults
    calls = []
    async def receive(request):
        body = json.loads(request.content)
        calls.append(body)
        assert body["policy_hash"] == expected
        return httpx.Response(200, json={"request_id": body["request_id"], "idempotency_key": body["idempotency_key"],
            "status": "SUCCEEDED", "result": {"node_id": "backend_engineer"}, "result_hash": "a" * 64})
    gateway = HttpToolGatewayClient("http://backend", "test-token", transport=httpx.MockTransport(receive))
    registry = ToolRegistry()
    register_controlled_gateway_tools(registry, gateway)
    harness = ToolHarness(registry)
    contract = ModelExecutionContract(execution_id="frozen-call", prompt_key=node["prompt_key"],
        prompt_version=node["prompt_version"], prompt_checksum=node["prompt_checksum"], model_policy=node["model_policy"],
        deadline_epoch_ms=9_999_999_999_999, protocol_version=2, contract_hash="b" * 64, tool_policy=raw_policy)
    context = ToolRuntimeContext(execution_id=contract.execution_id, node_id="backend_engineer", attempt=1,
        policy=typed_policy, deadline_epoch_ms=contract.deadline_epoch_ms, contract_hash=contract.contract_hash,
        schema_version=node["output_schema"], harness=harness, workflow_run_id=1, node_run_id=3,
        actor_user_id="1", project_id="1", fencing_token=1)
    with bind_model_execution_contract(contract), bind_tool_runtime_context(context):
        result = await execute_current_tool({"name": "contract.lookup", "version": "v1",
                                             "arguments": {"node_id": "backend_engineer"}, "idempotency_key": "lookup"})
    assert result.status == "SUCCEEDED"
    assert len(calls) == 1
