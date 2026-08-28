from __future__ import annotations

from dataclasses import dataclass, replace
from time import perf_counter
from typing import Any, Callable

from agents.architect import ArchitectAgent
from agents.backend_engineer import BackendEngineerAgent
from agents.base import ModelClient
from agents.frontend_engineer import FrontendEngineerAgent
from agents.product_manager import ProductManagerAgent
from agents.reviewer import ReviewerAgent
from schemas.architecture_design import ArchitectureDesignArtifact
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact


SUPPORTED_AGENT_NODES = {
    "product_manager",
    "architect",
    "backend_engineer",
    "frontend_engineer",
    "reviewer",
}


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
) -> AgentExecutionRecord:
    if node_name not in SUPPORTED_AGENT_NODES:
        raise ValueError(f"Unsupported Agent node: {node_name}")

    _output, record = _execute_node(
        node_name=node_name,
        agent_name=_agent_name_for_node(node_name),
        input_payload=payload,
        run=lambda: _run_node_output(node_name, payload, model_client),
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
) -> Any:
    requirement = (
        _require_str(payload, "requirement")
        if node_name != "reviewer"
        else payload.get("requirement", "")
    )
    retrieved_sources = payload.get("retrieved_sources", [])
    context_manifest = payload.get("context_manifest", {})

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
        )

    architecture_design = ArchitectureDesignArtifact.model_validate(
        payload["architecture_design"]
    )
    if node_name == "backend_engineer":
        return BackendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
        )

    backend_design = BackendDesignArtifact.model_validate(payload["backend_design"])
    if node_name == "frontend_engineer":
        return FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            backend_design,
            retrieved_sources=retrieved_sources,
            context_manifest=context_manifest,
        )

    frontend_skeleton = FrontendSkeletonArtifact.model_validate(
        payload["frontend_skeleton"]
    )
    return ReviewerAgent(model_client).run(
        prd,
        backend_design,
        architecture_design,
        frontend_skeleton,
        retrieved_sources=retrieved_sources,
        generated_files=payload.get("generated_files", []),
        model_invocations=payload.get("model_invocations", []),
        context_manifest=context_manifest,
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
        raise RuntimeError(f"{node_name} execution failed: {exc}") from exc

    output_payload = output.model_dump() if hasattr(output, "model_dump") else output
    return output, AgentExecutionRecord(
        node_name=node_name,
        agent_name=agent_name,
        input_payload=input_payload,
        output_payload=output_payload,
        status="SUCCEEDED",
        duration_ms=int((perf_counter() - started) * 1000),
    )
