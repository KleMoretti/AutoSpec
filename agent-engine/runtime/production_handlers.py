from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from graph.workflow import run_v2_node
from review.evaluator import evaluate_artifacts
from runtime.handler_registry import HandlerRegistry
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


class FrontendNodeInput(PrdNodeInput):
    architecture_design: dict[str, Any]
    backend_design: dict[str, Any] | None = None


class ReviewerNodeInput(FrontendNodeInput):
    backend_design: dict[str, Any]
    frontend_skeleton: dict[str, Any]
    generated_files: list[dict[str, Any] | str] = Field(default_factory=list)
    model_invocations: list[dict[str, Any]] = Field(default_factory=list)


def build_production_registry() -> HandlerRegistry:
    registry = HandlerRegistry()
    _register_v2_node(
        registry,
        "ProductManagerAgent",
        "v1",
        "product_manager",
        ProductManagerInput,
        PrdArtifact,
    )
    _register_v2_node(
        registry,
        "ArchitectAgent",
        "v1",
        "architect",
        PrdNodeInput,
        ArchitectureDesignArtifact,
    )
    for version in ("v1", "v2"):
        _register_v2_node(
            registry,
            "BackendEngineerAgent",
            version,
            "backend_engineer",
            PrdNodeInput,
            BackendDesignArtifact,
        )
    _register_v2_node(
        registry,
        "FrontendEngineerAgent",
        "v1",
        "frontend_engineer",
        FrontendNodeInput,
        FrontendSkeletonArtifact,
    )
    _register_v2_node(
        registry,
        "ReviewerAgent",
        "v1",
        "reviewer",
        ReviewerNodeInput,
        ReviewReport,
    )
    registry.register(
        "EvaluatorAgent",
        "v1",
        EvaluationInput,
        EvaluationReport,
        _evaluate,
    )
    return registry


def _register_v2_node(
    registry: HandlerRegistry,
    handler_key: str,
    handler_version: str,
    node_name: str,
    input_model: type[BaseModel],
    output_model: type[BaseModel],
) -> None:
    def execute(input_payload: BaseModel) -> dict[str, Any]:
        record = run_v2_node(node_name, input_payload.model_dump(mode="json"))
        if record.status != "SUCCEEDED" or record.output_payload is None:
            raise RuntimeError(record.error_message or f"{node_name} execution failed")
        return record.output_payload

    registry.register(
        handler_key,
        handler_version,
        input_model,
        output_model,
        execute,
    )


def _evaluate(input_payload: EvaluationInput) -> EvaluationReport:
    return evaluate_artifacts(
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
        retrieved_sources=input_payload.retrieved_sources,
        generated_files=input_payload.generated_files,
    )
