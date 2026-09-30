from __future__ import annotations

from dataclasses import replace
import time

import pytest

from runtime.execution_context import ModelExecutionContract, bind_model_execution_contract
from model_gateway import ModelOutputLimitError
from runtime.structured_output import StructuredOutputError, generate_structured_output
from schemas.prd import PrdArtifact


def _contract(max_calls: int = 2) -> ModelExecutionContract:
    return ModelExecutionContract(
        execution_id="structured-test",
        prompt_key="product_manager",
        prompt_version="v1",
        prompt_checksum="a" * 64,
        model_policy={
            "provider_key": "fixture",
            "model_name": "fixture",
            "max_calls": max_calls,
            "context_window_tokens": 4096,
            "max_output_tokens": 512,
            "structured_output_repair": {
                "enabled": True,
                "max_repairs": 1,
                "error_codes": ["STRUCTURED_OUTPUT_INVALID", "VALIDATION_ERROR"],
            },
        },
        deadline_epoch_ms=int(time.time() * 1000) + 10_000,
    )


def _valid() -> dict:
    return {
        "project_name": "Fixture",
        "target_users": ["user"],
        "core_features": [{"requirement_id": "REQ-TEST", "name": "Feature", "description": "A feature", "priority": "MUST"}],
        "user_stories": [{"story_id": "STORY-TEST", "role": "user", "goal": "use feature", "benefit": "value", "requirement_refs": ["REQ-TEST"], "acceptance_criteria": []}],
    }


class SequenceClient:
    def __init__(self, values):
        self.values = list(values)
        self.payloads = []

    def generate_json(self, _prompt_name, input_payload):
        self.payloads.append(dict(input_payload))
        return self.values.pop(0)


def test_invalid_output_gets_one_bounded_repair() -> None:
    client = SequenceClient([{"project_name": "missing required fields"}, _valid()])
    with bind_model_execution_contract(_contract()):
        result = generate_structured_output(client, "ProductManagerAgent_v1", {"requirement": "x"}, PrdArtifact)
    assert result.project_name == "Fixture"
    assert len(client.payloads) == 2
    assert client.payloads[1]["_structured_output_repair"]["issues"]


def test_repair_never_exceeds_one_attempt() -> None:
    client = SequenceClient([{}, {}])
    with bind_model_execution_contract(_contract()):
        with pytest.raises(StructuredOutputError):
            generate_structured_output(client, "ProductManagerAgent_v1", {"requirement": "x"}, PrdArtifact)
    assert len(client.payloads) == 2


def test_frozen_model_call_budget_can_disable_repair() -> None:
    client = SequenceClient([{}])
    with bind_model_execution_contract(_contract(max_calls=1)):
        with pytest.raises(StructuredOutputError):
            generate_structured_output(client, "ProductManagerAgent_v1", {"requirement": "x"}, PrdArtifact)
    assert len(client.payloads) == 1


def test_output_limit_is_not_retried_as_a_schema_repair() -> None:
    class LimitedClient(SequenceClient):
        def generate_json(self, _prompt_name, input_payload):
            self.payloads.append(dict(input_payload))
            raise ModelOutputLimitError("output allowance exhausted")

    client = LimitedClient([])
    with bind_model_execution_contract(_contract()):
        with pytest.raises(ModelOutputLimitError):
            generate_structured_output(client, "ProductManagerAgent_v2", {"requirement": "x"}, PrdArtifact)
    assert len(client.payloads) == 1


def test_repair_policy_can_restrict_error_categories() -> None:
    base_contract = _contract()
    contract = replace(
        base_contract,
        model_policy={
            **base_contract.model_policy,
            "structured_output_repair": {
                "enabled": True,
                "max_repairs": 1,
                "error_codes": ["STRUCTURED_OUTPUT_INVALID"],
            },
        },
    )
    client = SequenceClient([{}])
    with bind_model_execution_contract(contract):
        with pytest.raises(StructuredOutputError) as error:
            generate_structured_output(client, "ProductManagerAgent_v2", {"requirement": "x"}, PrdArtifact)
    assert error.value.error_code == "VALIDATION_ERROR"
    assert len(client.payloads) == 1
