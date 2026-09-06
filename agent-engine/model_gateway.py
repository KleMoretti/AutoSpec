from __future__ import annotations

import json
import hashlib
import os
import time
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Iterator, Mapping

from openai import OpenAI

from runtime.context_policy import estimate_tokens
from runtime.model_telemetry import (
    ModelInvocationTelemetry,
    ModelRoutingDecision,
    begin_model_invocation,
    bind_model_routing_decision,
    captured_invocation_count,
    record_model_invocation,
)
from runtime.execution_context import (
    ModelExecutionContract,
    bind_model_execution_contract,
    current_model_execution_contract,
)


PROMPT_FILES = {
    "ProductManagerAgent_v1": "product_manager_v1.md",
    "ArchitectAgent_v1": "architect_v1.md",
    "BackendEngineerAgent_v1": "backend_engineer_v1.md",
    "FrontendEngineerAgent_v1": "frontend_engineer_v1.md",
    "ReviewerAgent_v1": "reviewer_v1.md",
}


class ModelConfigurationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ModelRoutingRequest:
    quality_profile: str = "BALANCED"
    node_name: str = "unknown"


_ROUTING_REQUEST: ContextVar[ModelRoutingRequest] = ContextVar(
    "autospec_model_routing_request",
    default=ModelRoutingRequest(),
)


@contextmanager
def model_routing_request(
    quality_profile: str | None,
    node_name: str,
) -> Iterator[None]:
    profile = (quality_profile or "BALANCED").strip().upper()
    token = _ROUTING_REQUEST.set(ModelRoutingRequest(profile, node_name))
    try:
        yield
    finally:
        _ROUTING_REQUEST.reset(token)


class OpenAICompatibleModelClient:
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model_name: str,
        provider_key: str = "openai-compatible",
        timeout_seconds: float = 45.0,
        temperature: float = 0.1,
        input_cost_per_million: float = 0.0,
        cached_input_cost_per_million: float | None = None,
        output_cost_per_million: float = 0.0,
        max_output_tokens: int = 4096,
        context_window_tokens: int = 128_000,
        capabilities: set[str] | None = None,
        prompt_dir: Path | None = None,
        client: Any | None = None,
    ) -> None:
        self.provider_key = provider_key
        self.model_name = model_name
        self._temperature = temperature
        self._input_cost_per_million = max(0.0, input_cost_per_million)
        self._cached_input_cost_per_million = max(
            0.0,
            (
                input_cost_per_million
                if cached_input_cost_per_million is None
                else cached_input_cost_per_million
            ),
        )
        self._output_cost_per_million = max(0.0, output_cost_per_million)
        self._max_output_tokens = max(1, max_output_tokens)
        self._context_window_tokens = max(1024, context_window_tokens)
        self._capabilities = capabilities or {
            "json_object",
            "usage",
            "idempotency",
        }
        self._prompt_dir = prompt_dir or Path(__file__).resolve().parent / "prompts"
        self._client = client or OpenAI(
            api_key=api_key,
            base_url=base_url,
            timeout=timeout_seconds,
            # SDK retries are opaque physical requests. Workflow retries and
            # fallback stay explicit so every provider call has its own ledger row.
            max_retries=0,
        )

    def generate_json(
        self,
        prompt_name: str,
        input_payload: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        execution_contract = current_model_execution_contract()
        resolved_prompt_key = prompt_name
        resolved_prompt_version = None
        resolved_prompt_checksum = None
        temperature = self._temperature
        policy: Mapping[str, Any] = {}
        context_policy: Mapping[str, Any] = {}
        request_options: dict[str, Any] = {}
        if execution_contract is not None:
            prompt = self._load_contract_prompt(
                execution_contract.prompt_key,
                execution_contract.prompt_version,
                execution_contract.prompt_checksum,
            )
            resolved_prompt_key = execution_contract.prompt_key
            resolved_prompt_version = execution_contract.prompt_version
            resolved_prompt_checksum = execution_contract.prompt_checksum
            policy = execution_contract.model_policy
            context_policy = execution_contract.context_policy
            provider_key = policy.get("provider_key")
            model_name = policy.get("model_name")
            if provider_key is not None and provider_key != self.provider_key:
                raise ModelConfigurationError(
                    f"Contract provider {provider_key} does not match {self.provider_key}"
                )
            if model_name is not None and model_name != self.model_name:
                raise ModelConfigurationError(
                    f"Contract model {model_name} does not match {self.model_name}"
                )
            temperature = float(policy.get("temperature", self._temperature))
            remaining_seconds = (
                execution_contract.deadline_epoch_ms - round(time.time() * 1000)
            ) / 1000
            if remaining_seconds <= 0:
                raise TimeoutError("model request deadline elapsed")
            request_options["timeout"] = remaining_seconds
        else:
            prompt = self._load_prompt(prompt_name)
        required_capabilities = {
            str(capability) for capability in policy.get("required_capabilities", [])
        }
        missing_capabilities = sorted(required_capabilities - self._capabilities)
        if missing_capabilities:
            raise ModelConfigurationError(
                "Provider lacks frozen capabilities: "
                + ", ".join(missing_capabilities)
            )

        max_output_tokens = int(
            policy.get("max_output_tokens", self._max_output_tokens)
        )
        context_window_tokens = int(
            policy.get("context_window_tokens", self._context_window_tokens)
        )
        tokenizer = str(
            context_policy.get("tokenizer", "conservative-multilingual-v1")
        )
        max_input_tokens = int(
            context_policy.get(
                "max_input_tokens",
                context_window_tokens - max_output_tokens,
            )
        )
        system_content = (
            prompt
            + "\nReturn exactly one JSON object. Do not include Markdown fences or commentary."
        )
        user_content = json.dumps(
            input_payload,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        estimated_input_tokens = estimate_tokens(
            system_content + "\n" + user_content,
            tokenizer,
        )
        if estimated_input_tokens > max_input_tokens:
            raise ModelConfigurationError(
                "Normalized model input exceeds the frozen input token limit"
            )
        if estimated_input_tokens + max_output_tokens > context_window_tokens:
            raise ModelConfigurationError(
                "Model request would exceed the frozen context window"
            )

        messages = [
            {"role": "system", "content": system_content},
            {"role": "user", "content": user_content},
        ]
        response_format = {"type": "json_object"}
        params_hash = hashlib.sha256(
            json.dumps(
                {
                    "model": self.model_name,
                    "temperature": temperature,
                    "max_tokens": max_output_tokens,
                    "response_format": response_format,
                    "system_hash": hashlib.sha256(
                        system_content.encode("utf-8")
                    ).hexdigest(),
                    "input_hash": hashlib.sha256(
                        user_content.encode("utf-8")
                    ).hexdigest(),
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        permit = begin_model_invocation()
        if execution_contract is not None:
            request_options["extra_headers"] = {
                "Idempotency-Key": (
                    permit.call_id
                    if execution_contract.protocol_version >= 2
                    else execution_contract.execution_id
                )
            }
        request_options["max_tokens"] = max_output_tokens

        input_rate = float(
            policy.get("input_cost_per_million", self._input_cost_per_million)
        )
        cached_rate = float(
            policy.get(
                "cached_input_cost_per_million",
                self._cached_input_cost_per_million,
            )
        )
        output_rate = float(
            policy.get("output_cost_per_million", self._output_cost_per_million)
        )
        reserved_cost = (
            max_input_tokens * max(input_rate, cached_rate)
            + max_output_tokens * output_rate
        ) / 1_000_000
        input_tokens = 0
        output_tokens = 0
        cache_tokens = 0
        content: str | None = None
        started = time.perf_counter()
        try:
            completion = self._client.chat.completions.create(
                model=self.model_name,
                temperature=temperature,
                response_format=response_format,
                messages=messages,
                **request_options,
            )
            usage = getattr(completion, "usage", None)
            input_tokens = int(getattr(usage, "prompt_tokens", 0) or 0)
            output_tokens = int(getattr(usage, "completion_tokens", 0) or 0)
            prompt_details = getattr(usage, "prompt_tokens_details", None)
            cache_tokens = min(
                input_tokens,
                int(getattr(prompt_details, "cached_tokens", 0) or 0),
            )
            if not completion.choices:
                raise RuntimeError("Model returned no choices")
            content = completion.choices[0].message.content
            if content is None or not content.strip():
                raise RuntimeError("Model returned an empty response")
            try:
                parsed = json.loads(content)
            except json.JSONDecodeError as exc:
                raise RuntimeError("Model response was not valid JSON") from exc
            if not isinstance(parsed, dict):
                raise RuntimeError("Model response must be a JSON object")
        except Exception as exception:
            estimated_cost = _invocation_cost(
                input_tokens,
                output_tokens,
                cache_tokens,
                input_rate,
                cached_rate,
                output_rate,
            )
            record_model_invocation(
                ModelInvocationTelemetry(
                    call_id=permit.call_id,
                    execution_id=permit.execution_id,
                    call_sequence=permit.call_sequence,
                    attempt=permit.attempt,
                    provider_key=self.provider_key,
                    model_name=self.model_name,
                    prompt_key=resolved_prompt_key,
                    prompt_version=resolved_prompt_version,
                    prompt_checksum=resolved_prompt_checksum,
                    schema_version=(
                        execution_contract.schema_version
                        if execution_contract is not None
                        else None
                    ),
                    contract_hash=(
                        execution_contract.contract_hash
                        if execution_contract is not None
                        else None
                    ),
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    cache_tokens=cache_tokens,
                    estimated_cost=estimated_cost,
                    reserved_input_tokens=max_input_tokens,
                    reserved_output_tokens=max_output_tokens,
                    reserved_cost=reserved_cost,
                    normalized_params_hash=params_hash,
                    result_hash=(
                        hashlib.sha256(content.encode("utf-8")).hexdigest()
                        if content
                        else None
                    ),
                    status="FAILED",
                    error_code=exception.__class__.__name__.upper(),
                    error_message=str(exception)[:1000],
                    duration_ms=max(0, round((time.perf_counter() - started) * 1000)),
                    deadline_epoch_ms=permit.deadline_epoch_ms,
                    idempotency_key=permit.call_id,
                )
            )
            raise

        estimated_cost = _invocation_cost(
            input_tokens,
            output_tokens,
            cache_tokens,
            input_rate,
            cached_rate,
            output_rate,
        )
        record_model_invocation(
            ModelInvocationTelemetry(
                call_id=permit.call_id,
                execution_id=permit.execution_id,
                call_sequence=permit.call_sequence,
                attempt=permit.attempt,
                provider_key=self.provider_key,
                model_name=self.model_name,
                prompt_key=resolved_prompt_key,
                prompt_version=resolved_prompt_version,
                prompt_checksum=resolved_prompt_checksum,
                schema_version=(
                    execution_contract.schema_version
                    if execution_contract is not None
                    else None
                ),
                contract_hash=(
                    execution_contract.contract_hash
                    if execution_contract is not None
                    else None
                ),
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cache_tokens=cache_tokens,
                estimated_cost=estimated_cost,
                reserved_input_tokens=max_input_tokens,
                reserved_output_tokens=max_output_tokens,
                reserved_cost=reserved_cost,
                normalized_params_hash=params_hash,
                result_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
                status="SUCCEEDED",
                duration_ms=max(0, round((time.perf_counter() - started) * 1000)),
                deadline_epoch_ms=permit.deadline_epoch_ms,
                idempotency_key=permit.call_id,
            )
        )
        return parsed

    def _load_prompt(self, prompt_name: str) -> str:
        file_name = PROMPT_FILES.get(prompt_name)
        if file_name is None:
            raise ModelConfigurationError(f"No prompt is registered for {prompt_name}")
        prompt_path = self._prompt_dir / file_name
        try:
            return prompt_path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ModelConfigurationError(f"Unable to load prompt {prompt_name}") from exc

    def _load_contract_prompt(
        self,
        prompt_key: str,
        prompt_version: str,
        expected_checksum: str,
    ) -> str:
        prompt_path = self._prompt_dir / f"{prompt_key}_{prompt_version}.md"
        try:
            prompt_text = prompt_path.read_text(encoding="utf-8").replace("\r\n", "\n")
        except (OSError, UnicodeDecodeError) as exc:
            raise ModelConfigurationError(
                f"Unable to load prompt {prompt_key}:{prompt_version}"
            ) from exc
        material = prompt_text.encode("utf-8")
        actual_checksum = hashlib.sha256(material).hexdigest()
        if actual_checksum != expected_checksum:
            raise ModelConfigurationError(
                "Prompt checksum mismatch for "
                f"{prompt_key}:{prompt_version}; expected {expected_checksum}, "
                f"got {actual_checksum}"
            )
        return prompt_text


class RoutedModelClient:
    def __init__(
        self,
        routes: Mapping[str, OpenAICompatibleModelClient],
        fallback: OpenAICompatibleModelClient | None = None,
    ) -> None:
        if "balanced" not in routes:
            raise ValueError("A balanced model route is required")
        self._routes = dict(routes)
        self._fallback = fallback

    def generate_json(
        self,
        prompt_name: str,
        input_payload: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        request = _ROUTING_REQUEST.get()
        route_key, reason = self._select_route(request)
        primary = self._routes.get(route_key, self._routes["balanced"])
        invocation_count_before = captured_invocation_count()
        try:
            with bind_model_routing_decision(
                ModelRoutingDecision(route_key=route_key, route_reason=reason)
            ):
                return primary.generate_json(prompt_name, input_payload)
        except Exception as exception:
            if (
                self._fallback is None
                or not self._is_transient(exception)
                or not self._frozen_fallback_allowed()
            ):
                raise
            if captured_invocation_count() == invocation_count_before:
                record_model_invocation(
                    ModelInvocationTelemetry(
                        provider_key=primary.provider_key,
                        model_name=primary.model_name,
                        prompt_key=prompt_name,
                        route_key=route_key,
                        route_reason=reason + ":primary_transient_failure",
                        status="FAILED",
                        error_code=exception.__class__.__name__.upper(),
                        error_message=str(exception)[:1000],
                        normalized_params_hash=hashlib.sha256(
                            json.dumps(
                                {
                                    "provider": primary.provider_key,
                                    "model": primary.model_name,
                                    "prompt": prompt_name,
                                    "input": input_payload,
                                },
                                ensure_ascii=False,
                                sort_keys=True,
                                separators=(",", ":"),
                                default=str,
                            ).encode("utf-8")
                        ).hexdigest(),
                    )
                )
            fallback_reason = reason + ":provider_fallback"
            execution_contract = current_model_execution_contract()
            fallback_context = self._fallback_contract(execution_contract)
            with bind_model_execution_contract(fallback_context):
                with bind_model_routing_decision(
                    ModelRoutingDecision(
                        route_key="fallback",
                        route_reason=fallback_reason,
                        fallback_used=True,
                    )
                ):
                    return self._fallback.generate_json(prompt_name, input_payload)

    def _frozen_fallback_allowed(self) -> bool:
        contract = current_model_execution_contract()
        if contract is None or contract.protocol_version < 2:
            return True
        policy = contract.fallback_policy
        return bool(policy.get("enabled")) and isinstance(
            policy.get("model_policy"), dict
        )

    def _fallback_contract(
        self,
        contract: ModelExecutionContract | None,
    ) -> ModelExecutionContract | None:
        if contract is None or contract.protocol_version < 2:
            return contract
        policy = contract.fallback_policy.get("model_policy")
        if not isinstance(policy, dict):
            raise ModelConfigurationError(
                "Frozen fallback policy does not declare a model policy"
            )
        provider = policy.get("provider_key")
        model = policy.get("model_name")
        route = policy.get("route_key")
        if provider is not None and provider != self._fallback.provider_key:
            raise ModelConfigurationError(
                "Configured fallback provider does not match the frozen policy"
            )
        if model is not None and model != self._fallback.model_name:
            raise ModelConfigurationError(
                "Configured fallback model does not match the frozen policy"
            )
        if route is not None and route != "fallback":
            raise ModelConfigurationError(
                "Frozen fallback route must target the fallback provider"
            )
        return replace(contract, model_policy=dict(policy))

    def _select_route(self, request: ModelRoutingRequest) -> tuple[str, str]:
        execution_contract = current_model_execution_contract()
        if execution_contract is not None:
            policy = execution_contract.model_policy
            explicit_route = policy.get("route_key")
            if explicit_route is not None:
                normalized_route = str(explicit_route).strip().lower()
                if normalized_route not in self._routes:
                    raise ModelConfigurationError(
                        f"Contract model route is unavailable: {normalized_route}"
                    )
                return normalized_route, f"contract_route={normalized_route}"
            provider = policy.get("provider_key")
            model = policy.get("model_name")
            matching = [
                key
                for key, client in self._routes.items()
                if client.provider_key == provider and client.model_name == model
            ]
            if not matching:
                raise ModelConfigurationError(
                    f"Contract model target is unavailable: {provider}:{model}"
                )
            route = sorted(matching)[0]
            return route, f"contract_model={provider}:{model};route={route}"
        profile = request.quality_profile if request.quality_profile in {
            "FAST",
            "BALANCED",
            "DEEP",
        } else "BALANCED"
        node_name = request.node_name.lower()
        if profile == "FAST":
            route = "fast"
        elif profile == "DEEP":
            route = "deep"
        elif node_name in {"reviewer", "product_manager"}:
            route = "deep"
        elif node_name == "frontend_engineer":
            route = "fast"
        else:
            route = "balanced"
        if route not in self._routes:
            route = "balanced"
        return route, f"profile={profile};node={request.node_name};route={route}"

    def _is_transient(self, exception: Exception) -> bool:
        name = exception.__class__.__name__.lower()
        message = str(exception).lower()
        markers = (
            "timeout",
            "timed out",
            "rate limit",
            "connection",
            "temporarily unavailable",
            "429",
            "502",
            "503",
            "504",
        )
        return any(marker in name or marker in message for marker in markers)


def build_model_client(
    environ: Mapping[str, str] | None = None,
) -> RoutedModelClient | None:
    values: Mapping[str, str] = os.environ if environ is None else environ
    runtime_environment = values.get("AUTOSPEC_ENV", "development").strip().lower()
    mode = values.get("AGENT_MODEL_MODE", "fixture").strip().lower()

    if mode == "fixture":
        if runtime_environment in {"production", "prod"}:
            raise ModelConfigurationError(
                "AGENT_MODEL_MODE=fixture is forbidden in production"
            )
        return None
    if mode not in {"live", "openai-compatible"}:
        raise ModelConfigurationError(f"Unsupported AGENT_MODEL_MODE: {mode}")

    required = {
        "MODEL_API_KEY": values.get("MODEL_API_KEY", "").strip(),
        "MODEL_BASE_URL": values.get("MODEL_BASE_URL", "").strip(),
        "MODEL_NAME": values.get("MODEL_NAME", "").strip(),
    }
    missing = [name for name, value in required.items() if not value]
    if missing:
        raise ModelConfigurationError(
            "Missing live model configuration: " + ", ".join(sorted(missing))
        )

    balanced = _configured_client(values, "MODEL", required)
    fast = _configured_client(values, "MODEL_FAST", required) if _has_route_override(values, "MODEL_FAST") else balanced
    deep = _configured_client(values, "MODEL_DEEP", required) if _has_route_override(values, "MODEL_DEEP") else balanced
    fallback = (
        _configured_client(values, "MODEL_FALLBACK", required)
        if _has_route_override(values, "MODEL_FALLBACK")
        else None
    )
    return RoutedModelClient(
        routes={"fast": fast, "balanced": balanced, "deep": deep},
        fallback=fallback,
    )


def _invocation_cost(
    input_tokens: int,
    output_tokens: int,
    cache_tokens: int,
    input_rate: float,
    cached_rate: float,
    output_rate: float,
) -> float:
    cached = min(max(0, cache_tokens), max(0, input_tokens))
    uncached = max(0, input_tokens) - cached
    return (
        uncached * max(0.0, input_rate)
        + cached * max(0.0, cached_rate)
        + max(0, output_tokens) * max(0.0, output_rate)
    ) / 1_000_000


def _has_route_override(values: Mapping[str, str], prefix: str) -> bool:
    return any(
        values.get(f"{prefix}_{suffix}", "").strip()
        for suffix in ("API_KEY", "BASE_URL", "NAME", "PROVIDER_KEY")
    )


def _configured_client(
    values: Mapping[str, str],
    prefix: str,
    defaults: Mapping[str, str],
) -> OpenAICompatibleModelClient:
    def value(suffix: str, default: str) -> str:
        return values.get(f"{prefix}_{suffix}", "").strip() or default

    return OpenAICompatibleModelClient(
        api_key=value("API_KEY", defaults["MODEL_API_KEY"]),
        base_url=value("BASE_URL", defaults["MODEL_BASE_URL"]),
        model_name=value("NAME", defaults["MODEL_NAME"]),
        provider_key=value(
            "PROVIDER_KEY",
            values.get("MODEL_PROVIDER_KEY", "openai-compatible").strip()
            or "openai-compatible",
        ),
        timeout_seconds=float(value("TIMEOUT_SECONDS", values.get("MODEL_TIMEOUT_SECONDS", "45"))),
        temperature=float(value("TEMPERATURE", values.get("MODEL_TEMPERATURE", "0.1"))),
        input_cost_per_million=float(
            value("INPUT_COST_PER_1M", values.get("MODEL_INPUT_COST_PER_1M", "0"))
        ),
        cached_input_cost_per_million=float(
            value(
                "CACHED_INPUT_COST_PER_1M",
                values.get("MODEL_CACHED_INPUT_COST_PER_1M", "").strip()
                or value(
                    "INPUT_COST_PER_1M",
                    values.get("MODEL_INPUT_COST_PER_1M", "0"),
                ),
            )
        ),
        output_cost_per_million=float(
            value("OUTPUT_COST_PER_1M", values.get("MODEL_OUTPUT_COST_PER_1M", "0"))
        ),
        max_output_tokens=int(
            value("MAX_OUTPUT_TOKENS", values.get("MODEL_MAX_OUTPUT_TOKENS", "4096"))
        ),
        context_window_tokens=int(
            value(
                "CONTEXT_WINDOW_TOKENS",
                values.get("MODEL_CONTEXT_WINDOW_TOKENS", "128000"),
            )
        ),
    )
