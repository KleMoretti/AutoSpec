from pydantic import BaseModel, Field
from fastapi import FastAPI, HTTPException

from graph.workflow import (
    AgentExecutionRecord,
    WorkflowResult,
    WorkflowV2Result,
    run_prd_workflow,
    run_v1_workflow,
    run_v2_continue_workflow,
    run_v2_node,
    run_v2_workflow,
)
from schemas.prd import PrdArtifact


app = FastAPI(title="AutoSpec Agent Engine", version="0.1.0")


class GenerateRequest(BaseModel):
    requirement: str = Field(min_length=1)
    retrieved_sources: list[dict] = Field(default_factory=list)


class ContinueV2Request(BaseModel):
    requirement: str = Field(min_length=1)
    prd: dict
    retrieved_sources: list[dict] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/generate")
def generate(request: GenerateRequest) -> dict:
    result = run_v1_workflow(request.requirement, retrieved_sources=request.retrieved_sources)
    return v1_response(result)


@app.post("/generate/prd")
def generate_prd(request: GenerateRequest) -> dict:
    prd, records = run_prd_workflow(request.requirement, retrieved_sources=request.retrieved_sources)
    return {
        "prd": prd.model_dump(),
        "records": [record_response(record) for record in records],
    }


@app.post("/generate/v2")
def generate_v2(request: GenerateRequest) -> dict:
    return v2_response(run_v2_workflow(request.requirement, retrieved_sources=request.retrieved_sources))


@app.post("/generate/v2/continue")
def generate_v2_continue(request: ContinueV2Request) -> dict:
    prd = PrdArtifact.model_validate(request.prd)
    return v2_response(run_v2_continue_workflow(request.requirement, prd, retrieved_sources=request.retrieved_sources))


@app.post("/nodes/{node_name}/run")
def run_node(node_name: str, payload: dict) -> dict:
    try:
        return record_response(run_v2_node(node_name, payload))
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


def v1_response(result: WorkflowResult) -> dict:
    return {
        "prd": result.prd.model_dump(),
        "backend_design": result.backend_design.model_dump(),
        "review_report": result.review_report.model_dump(),
        "records": [record_response(record) for record in result.records],
    }


def v2_response(result: WorkflowV2Result) -> dict:
    return {
        "prd": result.prd.model_dump(),
        "architecture_design": result.architecture_design.model_dump(),
        "backend_design": result.backend_design.model_dump(),
        "frontend_skeleton": result.frontend_skeleton.model_dump(),
        "review_report": result.review_report.model_dump(),
        "records": [record_response(record) for record in result.records],
    }


def record_response(record: AgentExecutionRecord) -> dict:
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
