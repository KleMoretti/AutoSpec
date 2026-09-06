from __future__ import annotations

import hashlib
import json
import time
from typing import Any, Callable, Protocol
from uuid import uuid4

import httpx
from pydantic import BaseModel, ConfigDict

from runtime.tool_harness import (
    ToolHarness,
    ToolRegistry,
    ToolRuntimeError,
    current_tool_runtime_context,
)
from schemas.tool import ToolCallRequest
from schemas.tool_gateway import ToolGatewayRequest, ToolGatewayResult
from schemas.workflow_spec import ToolPolicy, ToolRef


class ToolGatewayError(ToolRuntimeError):
    error_code = "TOOL_GATEWAY_ERROR"

    def __init__(self, message: str, error_code: str | None = None) -> None:
        super().__init__(message)
        if error_code:
            self.error_code = error_code


class ToolGatewayClient(Protocol):
    async def execute(
        self,
        request: ToolGatewayRequest,
        *,
        policy: ToolPolicy | None = None,
    ) -> ToolGatewayResult: ...


class InMemoryToolGateway:
    """Deterministic gateway used by tests and offline experiments.

    The production implementation is the HTTP client below. This adapter keeps
    the same envelope checks so local tests cannot accidentally bypass the
    control-plane boundary.
    """

    def __init__(
        self,
        harness: ToolHarness,
        *,
        clock: Callable[[], int] | None = None,
        scope_checker: Callable[[ToolGatewayRequest], bool] | None = None,
    ) -> None:
        self._harness = harness
        self._clock = clock or (lambda: int(time.time() * 1000))
        self._scope_checker = scope_checker or (lambda _request: True)
        self._policies: dict[str, ToolPolicy] = {}
        self._results: dict[str, tuple[str, ToolGatewayResult]] = {}

    def register_policy(self, policy: ToolPolicy) -> str:
        policy_hash = _hash_json(policy.model_dump(mode="json"))
        self._policies[policy_hash] = policy
        return policy_hash

    async def execute(
        self,
        request: ToolGatewayRequest,
        *,
        policy: ToolPolicy | None = None,
    ) -> ToolGatewayResult:
        resolved = policy or self._policies.get(request.policy_hash)
        if resolved is None:
            return _failed(request, "POLICY_NOT_FOUND", "frozen tool policy is not registered")
        if _hash_json(resolved.model_dump(mode="json")) != request.policy_hash:
            return _failed(request, "POLICY_HASH_MISMATCH", "tool policy hash does not match the frozen policy")
        if request.deadline_epoch_ms <= self._clock():
            return _failed(request, "TOOL_DEADLINE_EXCEEDED", "tool gateway deadline elapsed")
        if not self._scope_checker(request):
            return _failed(request, "TOOL_SCOPE_DENIED", "tool request is outside its project scope")

        fingerprint = _hash_json(
            {
                "name": request.name,
                "version": request.version,
                "arguments": request.arguments,
                "policy_hash": request.policy_hash,
                "execution_id": request.execution_id,
            }
        )
        existing = self._results.get(request.idempotency_key)
        if existing is not None:
            existing_fingerprint, existing_result = existing
            if existing_fingerprint != fingerprint:
                return _failed(
                    request,
                    "IDEMPOTENCY_CONFLICT",
                    "idempotency key was already used for another tool request",
                )
            return existing_result.model_copy(update={"cached": True})

        try:
            call = ToolCallRequest(
                name=request.name,
                version=request.version,
                arguments=request.arguments,
                idempotency_key=request.idempotency_key,
            )
            result = await self._harness.execute(call, policy=resolved)
        except ToolRuntimeError as error:
            gateway_result = _failed(request, error.error_code, str(error))
        else:
            gateway_result = ToolGatewayResult(
                request_id=request.request_id,
                idempotency_key=request.idempotency_key,
                status=result.status,
                result=result.result,
                result_hash=_hash_json(result.result),
                cached=result.cached,
                attempts=result.attempts,
            )
        self._results[request.idempotency_key] = (fingerprint, gateway_result)
        return gateway_result


class HttpToolGatewayClient:
    """Worker client for the internal Spring Boot Tool Gateway endpoint."""

    def __init__(
        self,
        base_url: str,
        service_token: str,
        *,
        timeout_seconds: float = 5.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not base_url.strip() or not service_token.strip():
            raise ValueError("tool gateway base_url and service_token are required")
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._timeout = timeout_seconds
        self._transport = transport

    async def execute(
        self,
        request: ToolGatewayRequest,
        *,
        policy: ToolPolicy | None = None,
    ) -> ToolGatewayResult:
        del policy  # The control plane resolves the policy from the immutable bundle.
        try:
            async with httpx.AsyncClient(
                base_url=self._base_url,
                timeout=self._timeout,
                transport=self._transport,
            ) as client:
                response = await client.post(
                    "/internal/tool-gateway/requests",
                    headers={"X-AutoSpec-Service-Token": self._service_token},
                    json=request.model_dump(mode="json"),
                )
        except httpx.HTTPError as error:
            raise ToolGatewayError(
                "tool gateway transport failed",
                "TOOL_GATEWAY_UNAVAILABLE",
            ) from error
        if response.status_code >= 400:
            raise ToolGatewayError(
                "tool gateway rejected the request",
                f"TOOL_GATEWAY_HTTP_{response.status_code}",
            )
        try:
            return ToolGatewayResult.model_validate(response.json())
        except (ValueError, TypeError) as error:
            raise ToolGatewayError(
                "tool gateway returned an invalid response",
                "TOOL_GATEWAY_PROTOCOL_ERROR",
            ) from error


class GatewayToolArguments(BaseModel):
    model_config = ConfigDict(extra="allow")


class GatewayToolOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    result: Any = None


def register_controlled_gateway_tools(
    registry: ToolRegistry,
    gateway: ToolGatewayClient,
) -> None:
    """Register only the fixed read-only catalog on a production Worker."""

    definitions = (
        ("knowledge.search", "workflow", "READ_ONLY"),
        ("artifact.get", "workflow", "READ_ONLY"),
        ("contract.lookup", "workflow", "DETERMINISTIC"),
        ("trace.query", "workflow", "READ_ONLY"),
        ("bundle.verify", "workflow", "DETERMINISTIC"),
    )
    for name, permission_policy, side_effect in definitions:
        async def handler(
            arguments: GatewayToolArguments,
            *,
            _name: str = name,
        ) -> GatewayToolOutput:
            context = current_tool_runtime_context()
            if context is None:
                raise ToolGatewayError("tool gateway call is outside a node context")
            policy_hash = _hash_json(context.policy.model_dump(mode="json"))
            request = ToolGatewayRequest(
                request_id=str(uuid4()),
                execution_id=context.execution_id or "unknown",
                workflow_run_id=context.workflow_run_id,
                node_run_id=context.node_run_id,
                node_id=context.node_id,
                actor_user_id=context.actor_user_id or "unknown",
                project_id=context.project_id or "unknown",
                fencing_token=context.fencing_token,
                deadline_epoch_ms=context.deadline_epoch_ms or int(time.time() * 1000),
                execution_bundle_hash=context.execution_bundle_hash,
                policy_hash=policy_hash,
                idempotency_key=(
                    f"{context.execution_id}:{context.node_id}:{context.attempt}:"
                    f"{_name}:{_hash_json(arguments.model_dump(mode='json'))}"
                ),
                name=_name,
                version="v1",
                arguments=arguments.model_dump(mode="json"),
                normalized_params_hash=_hash_json(arguments.model_dump(mode="json")),
                max_result_bytes=context.policy.max_result_bytes,
                correlation_id=context.correlation_id,
                traceparent=context.traceparent,
                tracestate=context.tracestate,
            )
            response = await gateway.execute(request, policy=context.policy)
            if response.status == "FAILED":
                raise ToolGatewayError(
                    response.error_message or "tool gateway execution failed",
                    response.error_code,
                )
            return GatewayToolOutput(result=response.result)

        registry.register(
            name,
            "v1",
            GatewayToolArguments,
            GatewayToolOutput,
            handler,
            description=f"Controlled {name} gateway tool.",
            permission_policy=permission_policy,
            side_effect=side_effect,
        )


def _failed(
    request: ToolGatewayRequest,
    error_code: str,
    message: str,
) -> ToolGatewayResult:
    return ToolGatewayResult(
        request_id=request.request_id,
        idempotency_key=request.idempotency_key,
        status="FAILED",
        error_code=error_code,
        error_message=message[:1000],
    )


def _hash_json(value: Any) -> str:
    material = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()
