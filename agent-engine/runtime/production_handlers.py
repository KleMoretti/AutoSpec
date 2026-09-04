from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agents.base import ModelClient
from model_gateway import model_routing_request
from review.evaluator import evaluate_artifacts
from runtime.agent_node_runner import run_agent_node
from runtime.handler_registry import HandlerRegistry
from runtime.context_policy import ContextPolicyError, apply_context_policy
from runtime.execution_context import current_model_execution_contract
from runtime.model_telemetry import ModelInvocationTelemetry, record_model_invocation
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.evaluation import EvaluationInput, EvaluationReport
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.rework import ReworkDirective
from schemas.review import ReviewReport


class ProductManagerInput(BaseModel):
    model_config = ConfigDict(extra="allow")

    requirement: str = Field(min_length=1)
    retrieved_sources: list[dict[str, Any]] = Field(default_factory=list)


class PrdNodeInput(ProductManagerInput):
    prd: dict[str, Any]
    rework_directive: ReworkDirective | None = None


class BackendDesignInput(PrdNodeInput):
    architecture_design: dict[str, Any]


class FrontendNodeInput(PrdNodeInput):
    architecture_design: dict[str, Any]
    backend_design: dict[str, Any]


class ReviewerNodeInput(FrontendNodeInput):
    backend_design: dict[str, Any]
    frontend_skeleton: dict[str, Any]
    generated_files: list[dict[str, Any] | str] = Field(default_factory=list)
    model_invocations: list[dict[str, Any]] = Field(default_factory=list)


class QualityGateBlockedError(RuntimeError):
    error_code = "QUALITY_GATE_BLOCKED"


def build_production_registry(model_client: ModelClient | None = None) -> HandlerRegistry:
    registry = HandlerRegistry()
    _register_agent_node(
        registry,
        "ProductManagerAgent",
        "v1",
        "product_manager",
        ProductManagerInput,
        PrdArtifact,
        "GenerateRequest",
        "PrdArtifact",
        "product_manager",
        model_client,
    )
    _register_agent_node(
        registry,
        "ArchitectAgent",
        "v1",
        "architect",
        PrdNodeInput,
        ArchitectureDesignArtifact,
        "ArchitectureInput",
        "ArchitectureDesignArtifact",
        "architect",
        model_client,
    )
    _register_agent_node(
        registry,
        "BackendEngineerAgent",
        "v1",
        "backend_engineer",
        BackendDesignInput,
        BackendDesignArtifact,
        "BackendDesignInput",
        "BackendDesignArtifact",
        "backend_engineer",
        model_client,
    )
    _register_agent_node(
        registry,
        "FrontendEngineerAgent",
        "v1",
        "frontend_engineer",
        FrontendNodeInput,
        FrontendSkeletonArtifact,
        "FrontendSkeletonInput",
        "FrontendSkeletonArtifact",
        "frontend_engineer",
        model_client,
    )
    _register_agent_node(
        registry,
        "ReviewerAgent",
        "v1",
        "reviewer",
        ReviewerNodeInput,
        ReviewReport,
        "ReviewInput",
        "ReviewReport",
        "reviewer",
        model_client,
    )
    registry.register(
        "EvaluatorAgent",
        "v1",
        EvaluationInput,
        EvaluationReport,
        _execute_evaluator,
        input_schema="EvaluationInput",
        output_schema="EvaluationReport",
        prompt_key="evaluator",
        prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    return registry


def _register_agent_node(
    registry: HandlerRegistry,
    handler_key: str,
    handler_version: str,
    node_name: str,
    input_model: type[BaseModel],
    output_model: type[BaseModel],
    input_schema: str,
    output_schema: str,
    prompt_key: str,
    model_client: ModelClient | None,
) -> None:
    def execute(input_payload: BaseModel) -> dict[str, Any]:
        serialized_input = input_payload.model_dump(mode="json")
        execution_policy = serialized_input.get("execution_policy", {})
        quality_profile = (
            execution_policy.get("quality_profile")
            if isinstance(execution_policy, dict)
            else None
        )
        compacted_input = _compile_context(
            node_name,
            serialized_input,
            quality_profile,
            input_model,
        )
        with model_routing_request(quality_profile, node_name):
            record = run_agent_node(
                node_name,
                compacted_input,
                model_client=model_client,
            )
        if record.status != "SUCCEEDED" or record.output_payload is None:
            raise RuntimeError(record.error_message or f"{node_name} execution failed")
        if model_client is None:
            contract = current_model_execution_contract()
            model_policy = contract.model_policy if contract is not None else {}
            context_policy = contract.context_policy if contract is not None else {}
            record_model_invocation(
                ModelInvocationTelemetry(
                    provider_key=record.provider_key,
                    model_name=record.model_name,
                    prompt_key=prompt_key,
                    prompt_version="v1",
                    prompt_checksum=_prompt_checksum(prompt_key, "v1"),
                    schema_version=output_schema,
                    contract_hash=contract.contract_hash if contract is not None else None,
                    normalized_params_hash=_hash_json(compacted_input),
                    result_hash=_hash_json(record.output_payload),
                    status="SUCCEEDED",
                    duration_ms=record.duration_ms,
                    reserved_input_tokens=int(context_policy.get("max_input_tokens", 0)),
                    reserved_output_tokens=int(model_policy.get("max_output_tokens", 0)),
                    reserved_cost=_reserved_call_cost(context_policy, model_policy),
                )
            )
        return record.output_payload

    registry.register(
        handler_key,
        handler_version,
        input_model,
        output_model,
        execute,
        input_schema=input_schema,
        output_schema=output_schema,
        prompt_key=prompt_key,
        prompt_version="v1",
        prompt_checksum=_prompt_checksum(prompt_key, "v1"),
    )


def _compile_context(
    node_name: str,
    serialized_input: dict[str, Any],
    quality_profile: str | None,
    input_model: type[BaseModel],
) -> dict[str, Any]:
    contract = current_model_execution_contract()
    frozen_policy = (
        contract.context_policy
        if contract is not None and contract.protocol_version >= 2
        else None
    )
    compacted_input, context_manifest = apply_context_policy(
        node_name,
        serialized_input,
        quality_profile,
        frozen_policy,
    )
    compacted_input.pop("context_manifest", None)
    try:
        _validate_artifact_context(compacted_input)
        validated = input_model.model_validate(compacted_input).model_dump(mode="json")
    except ValidationError as exception:
        raise ContextPolicyError(
            "Compacted context failed the frozen node input schema"
        ) from exception
    if input_model.model_config.get("extra") == "allow":
        validated["context_manifest"] = context_manifest
    return validated


def _validate_artifact_context(payload: dict[str, Any]) -> None:
    artifact_models: dict[str, type[BaseModel]] = {
        "prd": PrdArtifact,
        "architecture_design": ArchitectureDesignArtifact,
        "backend_design": BackendDesignArtifact,
        "frontend_skeleton": FrontendSkeletonArtifact,
        "review_report": ReviewReport,
    }
    parsed: dict[str, BaseModel] = {}
    for field, model in artifact_models.items():
        value = payload.get(field)
        if value is not None:
            parsed[field] = model.model_validate(value)

    prd = parsed.get("prd")
    if isinstance(prd, PrdArtifact):
        known_requirements = {
            feature.requirement_id
            for feature in prd.core_features
            if feature.requirement_id is not None
        }
        referenced = {
            str(reference)
            for field in artifact_models
            if field != "prd" and field in payload
            for reference in _values_for_key(payload[field], "requirement_refs")
            if isinstance(reference, str)
        }
        unknown = sorted(referenced - known_requirements)
        if unknown:
            raise ContextPolicyError(
                "Compacted context contains unknown requirement references: "
                + ", ".join(unknown[:20])
            )

    backend = parsed.get("backend_design")
    frontend = parsed.get("frontend_skeleton")
    if isinstance(backend, BackendDesignArtifact) and isinstance(
        frontend, FrontendSkeletonArtifact
    ):
        known_api_ids = {api.api_id for api in backend.apis if api.api_id is not None}
        referenced_api_ids = {
            binding.backend_api_id
            for binding in frontend.api_bindings
            if binding.backend_api_id is not None
        }
        unknown_api_ids = sorted(referenced_api_ids - known_api_ids)
        if unknown_api_ids:
            raise ContextPolicyError(
                "Compacted frontend context references unknown backend APIs: "
                + ", ".join(unknown_api_ids[:20])
            )


def _values_for_key(value: Any, target: str) -> list[Any]:
    values: list[Any] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key == target and isinstance(item, list):
                values.extend(item)
            values.extend(_values_for_key(item, target))
    elif isinstance(value, list):
        for item in value:
            values.extend(_values_for_key(item, target))
    return values


def _execute_evaluator(input_payload: EvaluationInput) -> EvaluationReport:
    serialized = input_payload.model_dump(mode="json")
    execution_policy = serialized.get("execution_policy", {})
    quality_profile = (
        execution_policy.get("quality_profile")
        if isinstance(execution_policy, dict)
        else None
    )
    compiled = _compile_context(
        "evaluator",
        serialized,
        quality_profile,
        EvaluationInput,
    )
    return _evaluate(EvaluationInput.model_validate(compiled))


def _evaluate(input_payload: EvaluationInput) -> EvaluationReport:
    report = evaluate_artifacts(
        requirement=input_payload.requirement,
        prd=PrdArtifact.model_validate(input_payload.prd),
        architecture_design=ArchitectureDesignArtifact.model_validate(
            input_payload.architecture_design
        ),
        backend_design=BackendDesignArtifact.model_validate(input_payload.backend_design),
        frontend_skeleton=FrontendSkeletonArtifact.model_validate(
            input_payload.frontend_skeleton
        ),
        review_report=ReviewReport.model_validate(input_payload.review_report),
        records=input_payload.records,
        model_invocations=input_payload.model_invocations,
        retrieved_sources=input_payload.retrieved_sources,
        generated_files=input_payload.generated_files,
    )
    if report.gate_status == "BLOCKED":
        blockers = [
            f"{issue.issue_type}: {issue.description}"
            for issue in report.issues
            if issue.blocking
        ]
        raise QualityGateBlockedError("; ".join(blockers[:5]))
    return report


def _prompt_checksum(prompt_key: str, prompt_version: str) -> str:
    if prompt_key == "evaluator":
        material = f"deterministic:{prompt_key}:{prompt_version}".encode("utf-8")
    else:
        path = (
            Path(__file__).resolve().parents[1]
            / "prompts"
            / f"{prompt_key}_{prompt_version}.md"
        )
        material = path.read_text(encoding="utf-8").encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def _hash_json(value: Any) -> str:
    material = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _reserved_call_cost(
    context_policy: dict[str, Any],
    model_policy: dict[str, Any],
) -> float:
    input_tokens = max(0, int(context_policy.get("max_input_tokens", 0)))
    output_tokens = max(0, int(model_policy.get("max_output_tokens", 0)))
    input_rate = max(0.0, float(model_policy.get("input_cost_per_million", 0.0)))
    output_rate = max(0.0, float(model_policy.get("output_cost_per_million", 0.0)))
    return round(
        (input_tokens * input_rate + output_tokens * output_rate) / 1_000_000,
        8,
    )
