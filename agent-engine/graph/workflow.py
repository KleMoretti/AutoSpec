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
from schemas.review import ReviewReport

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
) -> WorkflowResult:
    workflow = build_v1_workflow(model_client=model_client, callbacks=callbacks or [])
    state = workflow.invoke({"requirement": requirement, "records": []})
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
) -> tuple[PrdArtifact, list[AgentExecutionRecord]]:
    prd, record = _execute_node(
        node_name="product_manager",
        agent_name=ProductManagerAgent.prompt_name,
        input_payload={"requirement": requirement},
        run=lambda: ProductManagerAgent(model_client).run(requirement),
        callbacks=callbacks or [],
    )
    return prd, [record]


def run_v2_workflow(
    requirement: str,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
) -> WorkflowV2Result:
    callback_list = callbacks or []
    prd, records = run_prd_workflow(requirement, model_client=model_client, callbacks=callback_list)
    downstream = run_v2_continue_workflow(
        requirement,
        prd,
        model_client=model_client,
        callbacks=callback_list,
    )
    return WorkflowV2Result(
        prd=prd,
        architecture_design=downstream.architecture_design,
        backend_design=downstream.backend_design,
        frontend_skeleton=downstream.frontend_skeleton,
        review_report=downstream.review_report,
        records=[*records, *downstream.records],
    )


def run_v2_continue_workflow(
    requirement: str,
    prd: PrdArtifact,
    model_client: ModelClient | None = None,
    callbacks: list[Callback] | None = None,
) -> WorkflowV2Result:
    callback_list = callbacks or []
    records: list[AgentExecutionRecord] = []

    architecture_design, record = _execute_node(
        node_name="architect",
        agent_name=ArchitectAgent.prompt_name,
        input_payload={"requirement": requirement, "prd": prd.model_dump()},
        run=lambda: ArchitectAgent(model_client).run(requirement, prd),
        callbacks=callback_list,
    )
    records.append(record)

    backend_design, record = _execute_node(
        node_name="backend_engineer",
        agent_name=BackendEngineerAgent.prompt_name,
        input_payload={"requirement": requirement, "prd": prd.model_dump()},
        run=lambda: BackendEngineerAgent(model_client).run(requirement, prd),
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
        },
        run=lambda: FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            backend_design,
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
        },
        run=lambda: ReviewerAgent(model_client).run(
            prd,
            backend_design,
            architecture_design,
            frontend_skeleton,
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
            input_payload={"requirement": state["requirement"]},
            run=lambda: ProductManagerAgent(model_client).run(state["requirement"]),
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
            },
            run=lambda: BackendEngineerAgent(model_client).run(state["requirement"], state["prd"]),
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
            },
            run=lambda: ReviewerAgent(model_client).run(state["prd"], state["backend_design"]),
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
            input_payload={"requirement": requirement},
            run=lambda: ProductManagerAgent(self.model_client).run(requirement),
            callbacks=self.callbacks,
        )
        records.append(record)

        backend_design, record = _execute_node(
            node_name="backend_engineer",
            agent_name=BackendEngineerAgent.prompt_name,
            input_payload={"requirement": requirement, "prd": prd.model_dump()},
            run=lambda: BackendEngineerAgent(self.model_client).run(requirement, prd),
            callbacks=self.callbacks,
        )
        records.append(record)

        review_report, record = _execute_node(
            node_name="reviewer",
            agent_name=ReviewerAgent.prompt_name,
            input_payload={
                "prd": prd.model_dump(),
                "backend_design": backend_design.model_dump(),
            },
            run=lambda: ReviewerAgent(self.model_client).run(prd, backend_design),
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

    if node_name == "product_manager":
        return ProductManagerAgent(model_client).run(requirement)

    prd = PrdArtifact.model_validate(payload["prd"])
    if node_name == "architect":
        return ArchitectAgent(model_client).run(requirement, prd)

    if node_name == "backend_engineer":
        return BackendEngineerAgent(model_client).run(requirement, prd)

    backend_design = BackendDesignArtifact.model_validate(payload["backend_design"])
    architecture_design = ArchitectureDesignArtifact.model_validate(payload["architecture_design"])
    if node_name == "frontend_engineer":
        return FrontendEngineerAgent(model_client).run(
            requirement,
            prd,
            architecture_design,
            backend_design,
        )

    frontend_skeleton = FrontendSkeletonArtifact.model_validate(payload["frontend_skeleton"])
    return ReviewerAgent(model_client).run(
        prd,
        backend_design,
        architecture_design,
        frontend_skeleton,
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
