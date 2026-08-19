from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from graph.workflow import run_v2_node
from agents.base import ModelClient
from model_gateway import model_routing_request
from review.evaluator import evaluate_artifacts
from runtime.handler_registry import HandlerRegistry
from runtime.context_policy import apply_context_policy
from runtime.model_telemetry import ModelInvocationTelemetry, record_model_invocation
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.evaluation import EvaluationInput, EvaluationReport
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.review import ReviewReport


class ProductManagerInput(BaseModel):
    model_config = ConfigDict(extra="allow")

    requirement: str = Field(min_length=1)
    retrieved_sources: list[dict[str, Any]] = Field(default_factory=list)


class PrdNodeInput(ProductManagerInput):
    prd: dict[str, Any]


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
    _register_v2_node(
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
    _register_v2_node(
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
    for version in ("v1", "v2"):
        _register_v2_node(
            registry,
            "BackendEngineerAgent",
            version,
            "backend_engineer",
            BackendDesignInput,
            BackendDesignArtifact,
            "BackendDesignInput",
            "BackendDesignArtifact",
            "backend_engineer",
            model_client,
        )
    _register_v2_node(
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
    _register_v2_node(
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
        _evaluate,
        input_schema="EvaluationInput",
        output_schema="EvaluationReport",
        prompt_key="evaluator",
        prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    return registry


def _register_v2_node(
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
        compacted_input, _context_manifest = apply_context_policy(
            node_name,
            serialized_input,
            quality_profile,
        )
        with model_routing_request(quality_profile, node_name):
            record = run_v2_node(
                node_name,
                compacted_input,
                model_client=model_client,
            )
        if record.status != "SUCCEEDED" or record.output_payload is None:
            raise RuntimeError(record.error_message or f"{node_name} execution failed")
        if model_client is None:
            record_model_invocation(
                ModelInvocationTelemetry(
                    provider_key=record.provider_key,
                    model_name=record.model_name,
                    prompt_key=prompt_key,
                    prompt_version="v1",
                    prompt_checksum=_prompt_checksum(prompt_key, "v1"),
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
        material = path.read_bytes()
    return hashlib.sha256(material).hexdigest()
