import hmac
import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from evaluation.ablation import evaluate_release_gate
from evaluation.autospec_case_catalog import list_autospec_cases
from evaluation.retrieval import fixture_retrieval_evaluation
from schemas.evaluation import AutoSpecEvalRun


app = FastAPI(title="AutoSpec Agent Engine", version="5.0.0")
service_token = os.getenv("AGENT_ENGINE_SERVICE_TOKEN", "").strip()
if (
    os.getenv("AUTOSPEC_ENV", "development").strip().lower()
    in {"production", "prod"}
    and not service_token
):
    raise RuntimeError("AGENT_ENGINE_SERVICE_TOKEN is required in production")


@app.middleware("http")
async def authenticate_internal_requests(request: Request, call_next):
    if request.url.path == "/health" or not service_token:
        return await call_next(request)
    supplied = request.headers.get("X-AutoSpec-Service-Token", "")
    if not hmac.compare_digest(supplied, service_token):
        return JSONResponse(status_code=401, content={"detail": "Invalid service token"})
    return await call_next(request)


class ReleaseGateRequest(BaseModel):
    baseline: AutoSpecEvalRun
    candidate: AutoSpecEvalRun
    max_p95_ratio: float = Field(default=1.25, ge=1)
    max_token_ratio: float = Field(default=1.25, ge=1)
    max_cost_ratio: float = Field(default=1.25, ge=1)
    min_cases: int = Field(default=8, ge=1)
    min_repetitions: int = Field(default=3, ge=1)
    zero_baseline_limits: dict[str, float] = Field(default_factory=dict)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/evaluation/cases")
def evaluation_cases() -> list[dict]:
    return [case.model_dump() for case in list_autospec_cases()]


@app.get("/evaluation/retrieval")
def retrieval_evaluation() -> dict:
    """Return the independent deterministic hybrid-retrieval baseline."""
    return fixture_retrieval_evaluation().model_dump(mode="json")


@app.post("/evaluation/release-gate")
def release_gate(request: ReleaseGateRequest) -> dict:
    return evaluate_release_gate(
        request.baseline,
        request.candidate,
        max_p95_ratio=request.max_p95_ratio,
        max_token_ratio=request.max_token_ratio,
        max_cost_ratio=request.max_cost_ratio,
        min_cases=request.min_cases,
        min_repetitions=request.min_repetitions,
        zero_baseline_limits=request.zero_baseline_limits,
    ).model_dump(mode="json")
