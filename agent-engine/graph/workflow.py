from __future__ import annotations

from dataclasses import dataclass, field
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
from schemas.evaluation import EvaluationReport
from schemas.review import ReviewReport
from review.evaluator import evaluate_artifacts

try:
    from langgraph.graph import END, StateGraph
except ImportError:  # pragma: no cover - exercised when dependency is absent.
    END = None
    StateGraph = None


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


@dataclass(frozen=True)
class WorkflowResult:
    prd: PrdArtifact
    backend_design: BackendDesignArtifact
    review_report: ReviewReport
    records: list[AgentExecutionRecord] = field(default_factory=list)


@dataclass(frozen=True)
class WorkflowV2Result:
    prd: PrdArtifact
    architecture_design: ArchitectureDesignArtifact
    backend_design: BackendDesignArtifact
    frontend_skeleton: FrontendSkeletonArtifact
    review_report: ReviewReport
    records: list[AgentExecutionRecord] = field(default_factory=list)


@dataclass(frozen=True)
class WorkflowV4Result:
    prd: PrdArtifact
    architecture_design: ArchitectureDesignArtifact
    backend_design: BackendDesignArtifact
    frontend_skeleton: FrontendSkeletonArtifact
    review_report: ReviewReport
    evaluation_report: EvaluationReport
    records: list[AgentExecutionRecord] = field(default_factory=list)


SUPPORTED_V2_NODES = {
    "product_manager",
    "architect",
    "backend_engineer",
    "frontend_engineer",
    "reviewer",
}


Callback = Callable[[AgentExecutionRecord], None]


def run_v1_workflow(
    requirement: str,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
) -> WorkflowResult:
    workflow = build_v1_workflow(model_client=model_client, callbacks=callbacks or [])
    state = workflow.invoke({"requirement": requirement, "retrieved_sources": retrieved_sources or [], "records": []})
    return WorkflowResult(
        prd=state["prd"],
        backend_design=state["backend_design"],
        review_report=state["review_report"],
        records=state["records"],
    )


def run_prd_workflow(
    requirement: str,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
) -> tuple[PrdArtifact, list[AgentExecutionRecord]]:
    prd, record = _execute_node(
        node_name="product_manager",
        agent_name=ProductManagerAgent.prompt_name,
        input_payload={"requirement": requirement, "retrieved_sources": retrieved_sources or []},
        run=lambda: ProductManagerAgent(model_client).run(requirement, retrieved_sources=retrieved_sources or []),
        callbacks=callbacks or [],
    )
    return prd, [record]


def run_v2_workflow(
    requirement: str,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
) -> WorkflowV2Result:
    callback_list = callbacks or []
    prd, records = run_prd_workflow(
        requirement,
        model_client=model_client,
        callbacks=callback_list,
        retrieved_sources=retrieved_sources,
    )
    downstream = run_v2_continue_workflow(
        requirement,
        prd,
        model_client=model_client,
        callbacks=callback_list,
        retrieved_sources=retrieved_sources,
    )
    return WorkflowV2Result(
        prd=prd,
        architecture_design=downstream.architecture_design,
        backend_design=downstream.backend_design,
        frontend_skeleton=downstream.frontend_skeleton,
        review_report=downstream.review_report,
        records=[*records, *downstream.records],
    )


def run_v4_workflow(
    requirement: str,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
    generated_files: list[dict[str, Any] | str] | None = None,
) -> WorkflowV4Result:
    callback_list = callbacks or []
    v2_result = run_v2_workflow(
        requirement,
        model_client=model_client,
        callbacks=callback_list,
        retrieved_sources=retrieved_sources,
    )
    evaluation_report, evaluator_record = _execute_node(
        node_name="evaluator",
        agent_name="EvaluatorAgent_v1",
        input_payload={
            "requirement": requirement,
            "prd": v2_result.prd.model_dump(),
            "architecture_design": v2_result.architecture_design.model_dump(),
            "backend_design": v2_result.backend_design.model_dump(),
            "frontend_skeleton": v2_result.frontend_skeleton.model_dump(),
            "review_report": v2_result.review_report.model_dump(),
            "records": [record_response(record) for record in v2_result.records],
            "retrieved_sources": retrieved_sources or [],
            "generated_files": generated_files or [],
        },
        run=lambda: evaluate_artifacts(
            requirement=requirement,
            prd=v2_result.prd,
            architecture_design=v2_result.architecture_design,
            backend_design=v2_result.backend_design,
            frontend_skeleton=v2_result.frontend_skeleton,
            review_report=v2_result.review_report,
            records=v2_result.records,
            retrieved_sources=retrieved_sources or [],
            generated_files=generated_files or [],
        ),
        callbacks=callback_list,
    )
    return WorkflowV4Result(
        prd=v2_result.prd,
        architecture_design=v2_result.architecture_design,
        backend_design=v2_result.backend_design,
        frontend_skeleton=v2_result.frontend_skeleton,
        review_report=v2_result.review_report,
        evaluation_report=evaluation_report,
        records=[*v2_result.records, evaluator_record],
    )


def run_v2_continue_workflow(
    requirement: str,
    prd: PrdArtifact,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
    retrieved_sources: list[dict[str, Any]] | None = None,
) -> WorkflowV2Result:
    callback_list = callbacks or []
    records: list[AgentExecutionRecord] = []

    architecture_design, record = _execute_node(
        node_name="architect",
        agent_name=ArchitectAgent.prompt_name,
        input_payload={"requirement": requirement, "prd": prd.model_dump(), "retrieved_sources": retrieved_sources or []},
        run=lambda: ArchitectAgent(model_client).run(requirement, prd, retrieved_sources=retrieved_sources or []),
        callbacks=callback_list,
    )
    records.append(record)

    backend_design, record = _execute_node(
        node_name="backend_engineer",
        agent_name=BackendEngineerAgent.prompt_name,
        input_payload={"requirement": requirement, "prd": prd.model_dump(), "retrieved_sources": retrieved_sources or []},
        run=lambda: BackendEngineerAgent(model_client).run(requirement, prd, retrieved_sources=retrieved_sources or []),
        callbacks=callback_list,
    )
    records.append(record)

    frontend_skeleton, record = _execute_node(
        node_name="frontend_engineer",
        agent_name=FrontendEngineerAgent.prompt_name,
        input_payload={
            "requirement": requirement,
            "prd": prd.model_dump(),
            "architecture_design": architecture_design.model_dump(),
            "backend_design": backend_design.model_dump(),
            "retrieved_sources": retrieved_sources or [],
        },
        run=lambda: FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            backend_design,
            retrieved_sources=retrieved_sources or [],
        ),
        callbacks=callback_list,
    )
    records.append(record)

    review_report, record = _execute_node(
        node_name="reviewer",
        agent_name=ReviewerAgent.prompt_name,
        input_payload={
            "prd": prd.model_dump(),
            "architecture_design": architecture_design.model_dump(),
            "backend_design": backend_design.model_dump(),
            "frontend_skeleton": frontend_skeleton.model_dump(),
            "retrieved_sources": retrieved_sources or [],
        },
        run=lambda: ReviewerAgent(model_client).run(
            prd,
            backend_design,
            architecture_design,
            frontend_skeleton,
            retrieved_sources=retrieved_sources or [],
        ),
        callbacks=callback_list,
    )
    records.append(record)

    return WorkflowV2Result(
        prd=prd,
        architecture_design=architecture_design,
        backend_design=backend_design,
        frontend_skeleton=frontend_skeleton,
        review_report=review_report,
        records=records,
    )


def run_v2_node(
    node_name: str,
    payload: dict[str, Any],
    model_client: ModelClient | None = None,
) -> AgentExecutionRecord:
    if node_name not in SUPPORTED_V2_NODES:
        raise ValueError(f"Unsupported V2 node: {node_name}")

    _output, record = _execute_node(
        node_name=node_name,
        agent_name=_agent_name_for_node(node_name),
        input_payload=payload,
        run=lambda: _run_v2_node_output(node_name, payload, model_client),
        callbacks=[],
    )
    return record


def build_v1_workflow(model_client: ModelClient | None, callbacks: list[Callback]):
    if StateGraph is None:
        return SequentialV1Workflow(model_client=model_client, callbacks=callbacks)

    graph = StateGraph(dict)

    def product_manager_node(state: dict[str, Any]) -> dict[str, Any]:
        prd, record = _execute_node(
            node_name="product_manager",
            agent_name=ProductManagerAgent.prompt_name,
            input_payload={
                "requirement": state["requirement"],
                "retrieved_sources": state.get("retrieved_sources", []),
            },
            run=lambda: ProductManagerAgent(model_client).run(
                state["requirement"],
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=callbacks,
        )
        return {"prd": prd, "records": [*state["records"], record]}

    def backend_engineer_node(state: dict[str, Any]) -> dict[str, Any]:
        backend_design, record = _execute_node(
            node_name="backend_engineer",
            agent_name=BackendEngineerAgent.prompt_name,
            input_payload={
                "requirement": state["requirement"],
                "prd": state["prd"].model_dump(),
                "retrieved_sources": state.get("retrieved_sources", []),
            },
            run=lambda: BackendEngineerAgent(model_client).run(
                state["requirement"],
                state["prd"],
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=callbacks,
        )
        return {"backend_design": backend_design, "records": [*state["records"], record]}

    def reviewer_node(state: dict[str, Any]) -> dict[str, Any]:
        review_report, record = _execute_node(
            node_name="reviewer",
            agent_name=ReviewerAgent.prompt_name,
            input_payload={
                "prd": state["prd"].model_dump(),
                "backend_design": state["backend_design"].model_dump(),
                "retrieved_sources": state.get("retrieved_sources", []),
            },
            run=lambda: ReviewerAgent(model_client).run(
                state["prd"],
                state["backend_design"],
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=callbacks,
        )
        return {"review_report": review_report, "records": [*state["records"], record]}

    graph.add_node("product_manager", product_manager_node)
    graph.add_node("backend_engineer", backend_engineer_node)
    graph.add_node("reviewer", reviewer_node)
    graph.set_entry_point("product_manager")
    graph.add_edge("product_manager", "backend_engineer")
    graph.add_edge("backend_engineer", "reviewer")
    graph.add_edge("reviewer", END)
    return graph.compile()


class SequentialV1Workflow:
    def __init__(self, model_client: ModelClient | None, callbacks: list[Callback]):
        self.model_client = model_client
        self.callbacks = callbacks

    def invoke(self, state: dict[str, Any]) -> dict[str, Any]:
        requirement = state["requirement"]
        records: list[AgentExecutionRecord] = []

        prd, record = _execute_node(
            node_name="product_manager",
            agent_name=ProductManagerAgent.prompt_name,
            input_payload={"requirement": requirement, "retrieved_sources": state.get("retrieved_sources", [])},
            run=lambda: ProductManagerAgent(self.model_client).run(
                requirement,
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=self.callbacks,
        )
        records.append(record)

        backend_design, record = _execute_node(
            node_name="backend_engineer",
            agent_name=BackendEngineerAgent.prompt_name,
            input_payload={"requirement": requirement, "prd": prd.model_dump(), "retrieved_sources": state.get("retrieved_sources", [])},
            run=lambda: BackendEngineerAgent(self.model_client).run(
                requirement,
                prd,
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=self.callbacks,
        )
        records.append(record)

        review_report, record = _execute_node(
            node_name="reviewer",
            agent_name=ReviewerAgent.prompt_name,
            input_payload={
                "prd": prd.model_dump(),
                "backend_design": backend_design.model_dump(),
                "retrieved_sources": state.get("retrieved_sources", []),
            },
            run=lambda: ReviewerAgent(self.model_client).run(
                prd,
                backend_design,
                retrieved_sources=state.get("retrieved_sources", []),
            ),
            callbacks=self.callbacks,
        )
        records.append(record)

        return {
            "requirement": requirement,
            "prd": prd,
            "backend_design": backend_design,
            "review_report": review_report,
            "records": records,
        }


def _agent_name_for_node(node_name: str) -> str:
    agent_names = {
        "product_manager": ProductManagerAgent.prompt_name,
        "architect": ArchitectAgent.prompt_name,
        "backend_engineer": BackendEngineerAgent.prompt_name,
        "frontend_engineer": FrontendEngineerAgent.prompt_name,
        "reviewer": ReviewerAgent.prompt_name,
    }
    return agent_names[node_name]


def _run_v2_node_output(
    node_name: str,
    payload: dict[str, Any],
    model_client: ModelClient | None,
) -> Any:
    requirement = _require_str(payload, "requirement") if node_name != "reviewer" else payload.get("requirement", "")
    retrieved_sources = payload.get("retrieved_sources", [])

    if node_name == "product_manager":
        return ProductManagerAgent(model_client).run(requirement, retrieved_sources=retrieved_sources)

    prd = PrdArtifact.model_validate(payload["prd"])
    if node_name == "architect":
        return ArchitectAgent(model_client).run(requirement, prd, retrieved_sources=retrieved_sources)

    if node_name == "backend_engineer":
        return BackendEngineerAgent(model_client).run(requirement, prd, retrieved_sources=retrieved_sources)

    backend_design = BackendDesignArtifact.model_validate(payload["backend_design"])
    architecture_design = ArchitectureDesignArtifact.model_validate(payload["architecture_design"])
    if node_name == "frontend_engineer":
        return FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            backend_design,
            retrieved_sources=retrieved_sources,
        )

    frontend_skeleton = FrontendSkeletonArtifact.model_validate(payload["frontend_skeleton"])
    return ReviewerAgent(model_client).run(
        prd,
        backend_design,
        architecture_design,
        frontend_skeleton,
        retrieved_sources=retrieved_sources,
        generated_files=payload.get("generated_files", []),
        model_invocations=payload.get("model_invocations", []),
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
    callbacks: list[Callback],
) -> tuple[Any, AgentExecutionRecord]:
    start = perf_counter()
    try:
        output = run()
        output_payload = output.model_dump() if hasattr(output, "model_dump") else output
        record = AgentExecutionRecord(
            node_name=node_name,
            agent_name=agent_name,
            input_payload=input_payload,
            output_payload=output_payload,
            status="SUCCEEDED",
            duration_ms=int((perf_counter() - start) * 1000),
        )
    except Exception as exc:
        record = AgentExecutionRecord(
            node_name=node_name,
            agent_name=agent_name,
            input_payload=input_payload,
            output_payload=None,
            status="FAILED",
            duration_ms=int((perf_counter() - start) * 1000),
            error_message=str(exc),
        )
        _notify(callbacks, record)
        raise

    _notify(callbacks, record)
    return output, record


def _notify(callbacks: list[Callback], record: AgentExecutionRecord) -> None:
    for callback in callbacks:
        callback(record)


def record_response(record: AgentExecutionRecord) -> dict[str, Any]:
    return {
        "node_name": record.node_name,
        "agent_name": record.agent_name,
        "input_payload": record.input_payload,
        "output_payload": record.output_payload,
        "status": record.status,
        "duration_ms": record.duration_ms,
        "error_message": record.error_message,
        "provider_key": record.provider_key,
        "model_name": record.model_name,
    }
