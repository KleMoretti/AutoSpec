import json
import hashlib
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from model_gateway import (
    ModelConfigurationError,
    OpenAICompatibleModelClient,
    RoutedModelClient,
    build_model_client,
    model_routing_request,
)
from runtime.model_telemetry import (
    ModelInvocationTelemetry,
    capture_model_invocations,
    record_model_invocation,
)
from runtime.execution_context import (
    ModelExecutionContract,
    bind_model_execution_contract,
)


def test_fixture_mode_is_explicitly_forbidden_in_production() -> None:
    with pytest.raises(ModelConfigurationError, match="forbidden in production"):
        build_model_client({"AUTOSPEC_ENV": "production", "AGENT_MODEL_MODE": "fixture"})


def test_live_mode_fails_closed_when_credentials_are_missing() -> None:
    with pytest.raises(ModelConfigurationError, match="MODEL_API_KEY"):
        build_model_client({"AGENT_MODEL_MODE": "live"})


def test_explicit_empty_environment_does_not_inherit_process_model_configuration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_MODEL_MODE", "live")
    monkeypatch.setenv("MODEL_API_KEY", "process-key")
    monkeypatch.setenv("MODEL_BASE_URL", "https://model.invalid/v1")
    monkeypatch.setenv("MODEL_NAME", "process-model")

    assert build_model_client({}) is None


def test_openai_compatible_gateway_loads_versioned_prompt_and_parses_json() -> None:
    prompt_dir = Path(__file__).resolve().parents[1] / "prompts"
    calls = []

    class Completions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content='{"project_name":"Clinic"}'))]
            )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    gateway = OpenAICompatibleModelClient(
        api_key="test-key",
        base_url="https://model.invalid/v1",
        model_name="test-model",
        prompt_dir=prompt_dir,
        client=fake_client,
    )

    output = gateway.generate_json(
        "ProductManagerAgent_v1",
        {"requirement": "Build a clinic scheduling system"},
    )

    assert output == {"project_name": "Clinic"}
    assert calls[0]["model"] == "test-model"
    assert "Stay within that requirement's domain" in calls[0]["messages"][0]["content"]
    assert json.loads(calls[0]["messages"][1]["content"])["requirement"].startswith("Build")


def test_openai_compatible_gateway_records_real_usage_and_cost() -> None:
    prompt_dir = Path(__file__).resolve().parents[1] / "prompts"
    usage = SimpleNamespace(
        prompt_tokens=120,
        completion_tokens=30,
        prompt_tokens_details=SimpleNamespace(cached_tokens=20),
    )

    class Completions:
        def create(self, **_kwargs):
            return SimpleNamespace(
                usage=usage,
                choices=[SimpleNamespace(message=SimpleNamespace(content='{"ok":true}'))],
            )

    gateway = OpenAICompatibleModelClient(
        api_key="test-key",
        base_url="https://model.invalid/v1",
        model_name="test-model",
        provider_key="test-provider",
        input_cost_per_million=2.0,
        output_cost_per_million=8.0,
        prompt_dir=prompt_dir,
        client=SimpleNamespace(chat=SimpleNamespace(completions=Completions())),
    )

    with capture_model_invocations() as invocations:
        gateway.generate_json("ProductManagerAgent_v1", {"requirement": "Build a clinic"})

    assert len(invocations) == 1
    assert invocations[0].provider_key == "test-provider"
    assert invocations[0].model_name == "test-model"
    assert invocations[0].prompt_key == "ProductManagerAgent_v1"
    assert invocations[0].input_tokens == 120
    assert invocations[0].output_tokens == 30
    assert invocations[0].cache_tokens == 20
    assert invocations[0].estimated_cost == pytest.approx(0.00048)


def test_frozen_contract_controls_prompt_temperature_deadline_and_idempotency() -> None:
    prompt_dir = Path(__file__).resolve().parents[1] / "prompts"
    prompt_material = (prompt_dir / "architect_v1.md").read_bytes()
    calls = []

    class Completions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content='{"ok":true}'))]
            )

    gateway = OpenAICompatibleModelClient(
        api_key="test-key",
        base_url="https://model.invalid/v1",
        model_name="test-model",
        prompt_dir=prompt_dir,
        client=SimpleNamespace(chat=SimpleNamespace(completions=Completions())),
    )
    contract = ModelExecutionContract(
        execution_id="7:architect:1:1",
        prompt_key="architect",
        prompt_version="v1",
        prompt_checksum=hashlib.sha256(prompt_material).hexdigest(),
        model_policy={"route_key": "balanced", "temperature": 0},
        deadline_epoch_ms=round(time.time() * 1000) + 5000,
    )

    with capture_model_invocations() as invocations:
        with bind_model_execution_contract(contract):
            output = gateway.generate_json("WrongHardCodedAgent_v9", {})

    assert output == {"ok": True}
    assert calls[0]["temperature"] == 0
    assert calls[0]["extra_headers"] == {"Idempotency-Key": "7:architect:1:1"}
    assert calls[0]["timeout"] > 0
    assert "software architect" in calls[0]["messages"][0]["content"].lower()
    assert invocations[0].prompt_key == "architect"
    assert invocations[0].prompt_version == "v1"
    assert invocations[0].prompt_checksum == contract.prompt_checksum


def test_routed_gateway_uses_quality_profile_and_persists_reason() -> None:
    class FakeRoute:
        def __init__(self, provider_key: str, model_name: str) -> None:
            self.provider_key = provider_key
            self.model_name = model_name

        def generate_json(self, prompt_name, _input_payload):
            record_model_invocation(
                ModelInvocationTelemetry(
                    provider_key=self.provider_key,
                    model_name=self.model_name,
                    prompt_key=prompt_name,
                )
            )
            return {"route": self.model_name}

    routed = RoutedModelClient(
        {
            "fast": FakeRoute("provider-fast", "model-fast"),
            "balanced": FakeRoute("provider-main", "model-balanced"),
            "deep": FakeRoute("provider-deep", "model-deep"),
        }
    )

    with capture_model_invocations() as invocations:
        with model_routing_request("FAST", "architect"):
            output = routed.generate_json("ArchitectAgent_v1", {})

    assert output == {"route": "model-fast"}
    assert invocations[0].route_key == "fast"
    assert invocations[0].route_reason == "profile=FAST;node=architect;route=fast"


def test_routed_gateway_falls_back_once_for_transient_provider_failure() -> None:
    class FailingRoute:
        provider_key = "primary-provider"
        model_name = "primary-model"

        def generate_json(self, _prompt_name, _input_payload):
            raise TimeoutError("provider timed out")

    class FallbackRoute:
        provider_key = "fallback-provider"
        model_name = "fallback-model"

        def generate_json(self, prompt_name, _input_payload):
            record_model_invocation(
                ModelInvocationTelemetry(
                    provider_key=self.provider_key,
                    model_name=self.model_name,
                    prompt_key=prompt_name,
                )
            )
            return {"recovered": True}

    primary = FailingRoute()
    routed = RoutedModelClient(
        {"fast": primary, "balanced": primary, "deep": primary},
        fallback=FallbackRoute(),
    )

    with capture_model_invocations() as invocations:
        with model_routing_request("DEEP", "reviewer"):
            output = routed.generate_json("ReviewerAgent_v1", {})

    assert output == {"recovered": True}
    assert len(invocations) == 2
    assert invocations[0].route_key == "deep"
    assert invocations[1].route_key == "fallback"
    assert invocations[1].fallback_used is True
