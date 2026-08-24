from __future__ import annotations

import json
import hashlib
import os
import time
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator, Mapping

from openai import OpenAI

from runtime.model_telemetry import (
    ModelInvocationTelemetry,
    ModelRoutingDecision,
    bind_model_routing_decision,
    record_model_invocation,
)
from runtime.execution_context import current_model_execution_contract


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
        max_retries: int = 2,
        input_cost_per_million: float = 0.0,
        output_cost_per_million: float = 0.0,
        prompt_dir: Path | None = None,
        client: Any | None = None,
    ) -> None:
        self.provider_key = provider_key
        self.model_name = model_name
        self._temperature = temperature
        self._input_cost_per_million = max(0.0, input_cost_per_million)
        self._output_cost_per_million = max(0.0, output_cost_per_million)
        self._prompt_dir = prompt_dir or Path(__file__).resolve().parent / "prompts"
        self._client = client or OpenAI(
            api_key=api_key,
            base_url=base_url,
            timeout=timeout_seconds,
            max_retries=max_retries,
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
            request_options["extra_headers"] = {
                "Idempotency-Key": execution_contract.execution_id
            }
        else:
            prompt = self._load_prompt(prompt_name)
        completion = self._client.chat.completions.create(
            model=self.model_name,
            temperature=temperature,
            response_format={"type": "json_object"},
            messages=[
                {
                    "role": "system",
                    "content": prompt
                    + "\nReturn exactly one JSON object. Do not include Markdown fences or commentary.",
                },
                {
                    "role": "user",
                    "content": json.dumps(input_payload, ensure_ascii=False, separators=(",", ":")),
                },
            ],
            **request_options,
        )
        usage = getattr(completion, "usage", None)
        input_tokens = int(getattr(usage, "prompt_tokens", 0) or 0)
        output_tokens = int(getattr(usage, "completion_tokens", 0) or 0)
        prompt_details = getattr(usage, "prompt_tokens_details", None)
        cache_tokens = int(getattr(prompt_details, "cached_tokens", 0) or 0)
        estimated_cost = (
            input_tokens * self._input_cost_per_million
            + output_tokens * self._output_cost_per_million
        ) / 1_000_000
        record_model_invocation(
            ModelInvocationTelemetry(
                provider_key=self.provider_key,
                model_name=self.model_name,
                prompt_key=resolved_prompt_key,
                prompt_version=resolved_prompt_version,
                prompt_checksum=resolved_prompt_checksum,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cache_tokens=cache_tokens,
                estimated_cost=estimated_cost,
            )
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
            material = prompt_path.read_bytes()
        except OSError as exc:
            raise ModelConfigurationError(
                f"Unable to load prompt {prompt_key}:{prompt_version}"
            ) from exc
        actual_checksum = hashlib.sha256(material).hexdigest()
        if actual_checksum != expected_checksum:
            raise ModelConfigurationError(
                "Prompt checksum mismatch for "
                f"{prompt_key}:{prompt_version}; expected {expected_checksum}, "
                f"got {actual_checksum}"
            )
        try:
            return material.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ModelConfigurationError(
                f"Prompt is not valid UTF-8: {prompt_key}:{prompt_version}"
            ) from exc


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
        try:
            with bind_model_routing_decision(
                ModelRoutingDecision(route_key=route_key, route_reason=reason)
            ):
                return primary.generate_json(prompt_name, input_payload)
        except Exception as exception:
            if self._fallback is None or not self._is_transient(exception):
                raise
            record_model_invocation(
                ModelInvocationTelemetry(
                    provider_key=primary.provider_key,
                    model_name=primary.model_name,
                    prompt_key=prompt_name,
                    route_key=route_key,
                    route_reason=reason + ":primary_transient_failure",
                )
            )
            fallback_reason = reason + ":provider_fallback"
            with bind_model_routing_decision(
                ModelRoutingDecision(
                    route_key="fallback",
                    route_reason=fallback_reason,
                    fallback_used=True,
                )
            ):
                return self._fallback.generate_json(prompt_name, input_payload)

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
        max_retries=int(value("MAX_RETRIES", values.get("MODEL_MAX_RETRIES", "2"))),
        input_cost_per_million=float(
            value("INPUT_COST_PER_1M", values.get("MODEL_INPUT_COST_PER_1M", "0"))
        ),
        output_cost_per_million=float(
            value("OUTPUT_COST_PER_1M", values.get("MODEL_OUTPUT_COST_PER_1M", "0"))
        ),
    )
