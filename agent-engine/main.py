import hmac
import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from evaluation.case_catalog import list_evaluation_cases
from review.experiments import compare_experiment_runs
from schemas.evaluation import ExperimentRun


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


class ExperimentCompareRequest(BaseModel):
    runs: list[ExperimentRun] = Field(min_length=2)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/evaluation/cases")
def evaluation_cases() -> list[dict]:
    return [case.model_dump() for case in list_evaluation_cases()]


@app.post("/experiments/compare")
def compare_experiments(request: ExperimentCompareRequest) -> dict:
    return compare_experiment_runs(request.runs).model_dump(by_alias=True)
