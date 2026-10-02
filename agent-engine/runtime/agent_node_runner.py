from __future__ import annotations

from dataclasses import dataclass, replace
from time import perf_counter
from typing import Any, Callable

from pydantic import ValidationError

from agents.architect import ArchitectAgent
from agents.backend_engineer import BackendEngineerAgent
from agents.base import ModelClient
from agents.frontend_engineer import FrontendEngineerAgent
from agents.product_manager import ProductManagerAgent
from agents.reviewer import ReviewerAgent
from schemas.architecture_design import ArchitectureDesignArtifact, ArchitectureDesignArtifactV2
from review.shared_contract import (
    validate_backend_contract,
    validate_explicit_backend_contract,
    validate_frontend_contract,
)
from schemas.backend_design import (
    BackendDesignArtifact,
    ExplicitBackendDesignArtifact,
)
from schemas.frontend_skeleton import (
    ExplicitFrontendSkeletonArtifact,
    FrontendSkeletonArtifact,
)
from schemas.prd import PrdArtifact


SUPPORTED_AGENT_NODES = {
    "product_manager",
    "architect",
    "backend_engineer",
    "frontend_engineer",
    "reviewer",
}


class AgentNodeExecutionError(RuntimeError):
    """Keep node context without discarding schema/provider failure categories."""

    def __init__(self, node_name: str, cause: Exception):
        self.error_code = (
            "VALIDATION_ERROR" if isinstance(cause, ValidationError)
            else getattr(cause, "error_code", "HANDLER_ERROR")
        )
        super().__init__(f"{node_name} execution failed: {cause}")


@dataclass(frozen=True)
class AgentExecutionRecord:
    node_name: str
    agent_name: str
    input_payload: dict[str, Any]
    output_payload: dict[str, Any] | None
    status: str
    duration_ms: int
    error_message: str | None = None
    provider_key: str = "local"
    model_name: str = "deterministic-fixture"


def run_agent_node(
    node_name: str,
    payload: dict[str, Any],
    model_client: ModelClient | None = None,
    verification_fact: dict[str, Any] | None = None,
) -> AgentExecutionRecord:
    if node_name not in SUPPORTED_AGENT_NODES:
        raise ValueError(f"Unsupported Agent node: {node_name}")

    _output, record = _execute_node(
        node_name=node_name,
        agent_name=_agent_name_for_node(node_name),
        input_payload=payload,
        run=lambda: _run_node_output(
            node_name,
            payload,
            model_client,
            verification_fact=verification_fact,
        ),
    )
    if model_client is None:
        return record
    return replace(
        record,
        provider_key=getattr(model_client, "provider_key", "model-gateway"),
        model_name=getattr(model_client, "model_name", "configured-model"),
    )


def _agent_name_for_node(node_name: str) -> str:
    return {
        "product_manager": ProductManagerAgent.prompt_name,
        "architect": ArchitectAgent.prompt_name,
        "backend_engineer": BackendEngineerAgent.prompt_name,
        "frontend_engineer": FrontendEngineerAgent.prompt_name,
        "reviewer": ReviewerAgent.prompt_name,
    }[node_name]


def _run_node_output(
    node_name: str,
    payload: dict[str, Any],
    model_client: ModelClient | None,
    *,
    verification_fact: dict[str, Any] | None = None,
) -> Any:
    requirement = (
        _require_str(payload, "requirement")
        if node_name != "reviewer"
        else payload.get("requirement", "")
    )
    retrieved_sources = payload.get("retrieved_sources", [])
    context_manifest = payload.get("context_manifest", {})
    rework_directive = payload.get("rework_directive")
    shared_contract_required = payload.get("shared_contract_required", False)
    rule_profile = payload.get("rule_profile", "legacy-marketplace-v1")
    explicit_contract_required = bool(payload.get("explicit_contract_required", False))

    if node_name == "product_manager":
        return ProductManagerAgent(model_client).run(
            requirement,
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
        )

    prd = PrdArtifact.model_validate(payload["prd"])
    if node_name == "architect":
        return ArchitectAgent(model_client).run(
            requirement,
            prd,
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
            rework_directive=rework_directive,
            shared_contract_required=shared_contract_required,
        )

    architecture_model = ArchitectureDesignArtifactV2 if shared_contract_required else ArchitectureDesignArtifact
    architecture_design = architecture_model.model_validate(
        payload["architecture_design"]
    )
    if node_name == "backend_engineer":
        result = BackendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
            rework_directive=rework_directive,
            shared_contract_required=shared_contract_required,
            explicit_contract_required=explicit_contract_required,
        )
        if shared_contract_required:
            if explicit_contract_required:
                validate_explicit_backend_contract(architecture_design, result)
            else:
                validate_backend_contract(architecture_design, result)
        return result

    if node_name == "frontend_engineer":
        result = FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            None if shared_contract_required else BackendDesignArtifact.model_validate(payload["backend_design"]),
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
            rework_directive=rework_directive,
            shared_contract_required=shared_contract_required,
            explicit_contract_required=explicit_contract_required,
        )
        if shared_contract_required:
            validate_frontend_contract(architecture_design, result)
        return result

    backend_model = (
        ExplicitBackendDesignArtifact
        if explicit_contract_required
        else BackendDesignArtifact
    )
    frontend_model = (
        ExplicitFrontendSkeletonArtifact
        if explicit_contract_required
        else FrontendSkeletonArtifact
    )
    backend_design = backend_model.model_validate(payload["backend_design"])
    frontend_skeleton = frontend_model.model_validate(
        payload["frontend_skeleton"]
    )
    return ReviewerAgent(
        model_client,
        prompt_name=payload.get("reviewer_prompt_name"),
    ).run(
        prd,
        backend_design,
        architecture_design,
        frontend_skeleton,
        retrieved_sources=retrieved_sources,
        generated_files=payload.get("generated_files", []),
        model_invocations=payload.get("model_invocations", []),
        context_manifest=context_manifest,
        shared_contract_required=shared_contract_required,
        rule_profile=rule_profile,
        verification_fact=verification_fact,
    )


def _require_str(payload: dict[str, Any], key: str) -> str:
    value = payload[key]
    if not isinstance(value, str) or not value:
        raise ValueError(f"'{key}' must be a non-empty string")
    return value


def _execute_node(
    node_name: str,
    agent_name: str,
    input_payload: dict[str, Any],
    run: Callable[[], Any],
) -> tuple[Any, AgentExecutionRecord]:
    started = perf_counter()
    try:
        output = run()
    except Exception as exc:
        raise AgentNodeExecutionError(node_name, exc) from exc

    output_payload = output.model_dump() if hasattr(output, "model_dump") else output
    return output, AgentExecutionRecord(
        node_name=node_name,
        agent_name=agent_name,
        input_payload=input_payload,
        output_payload=output_payload,
        status="SUCCEEDED",
        duration_ms=int((perf_counter() - started) * 1000),
    )
