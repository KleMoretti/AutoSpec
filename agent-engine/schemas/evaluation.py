from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from schemas.traceability import RequirementId


EvaluationDimension = Literal[
    "SCHEMA_VALIDITY",
    "REQUIREMENT_COVERAGE",
    "CROSS_ARTIFACT_CONSISTENCY",
    "PERMISSION_COVERAGE",
    "RAG_CITATION_QUALITY",
    "RUNTIME_RELIABILITY",
    "EXPORT_READINESS",
]


class EvalCase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    requirement: str = Field(min_length=1)
    dataset_version: str = Field(default="autospec-v5-eval-v1", min_length=1)
    resume_profile: dict[str, Any] = Field(default_factory=dict)
    target_position: str | None = Field(default=None, min_length=1)
    expected_capabilities: list[str] = Field(default_factory=list)
    expected_question_type: list[str] = Field(default_factory=list)
    gold_rubric: dict[str, float] = Field(default_factory=dict)
    bad_question_cases: list[str] = Field(default_factory=list)
    expected_tools: list[str] = Field(default_factory=list)
    expected_routes: list[str] = Field(default_factory=list)
    expected_stop_condition: str | None = Field(default=None, min_length=1)
    required_artifact_types: list[str] = Field(default_factory=list)
    scoring_dimensions: list[EvaluationDimension] = Field(default_factory=list)


# Keep the existing public name used by the V5 evaluator and catalog.
EvaluationCase = EvalCase


class EvaluationIssue(BaseModel):
    model_config = ConfigDict(extra="forbid")

    severity: str = Field(min_length=1)
    issue_type: str = Field(min_length=1)
    description: str = Field(min_length=1)
    suggestion: str = Field(min_length=1)
    evidence: list[str] = Field(default_factory=list)
    requirement_id: str | None = None
    artifact_path: str | None = None
    blocking: bool = False


class RequirementTrace(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requirement_id: RequirementId
    requirement: str = Field(min_length=1)
    priority: Literal["MUST", "SHOULD", "COULD"]
    prd_evidence: list[str] = Field(default_factory=list)
    architecture_evidence: list[str] = Field(default_factory=list)
    api_evidence: list[str] = Field(default_factory=list)
    data_evidence: list[str] = Field(default_factory=list)
    ui_evidence: list[str] = Field(default_factory=list)
    acceptance_evidence: list[str] = Field(default_factory=list)
    covered: bool


class EvaluationDimensionScore(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dimension: EvaluationDimension
    score: int = Field(ge=0, le=100)
    rationale: str = Field(min_length=1)


class EvaluationReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    overall_score: int = Field(ge=0, le=100)
    final_grade: Literal["A", "B", "C", "D", "F"]
    dimension_scores: list[EvaluationDimensionScore] = Field(min_length=1)
    issues: list[EvaluationIssue] = Field(default_factory=list)
    gate_status: Literal["PASSED", "BLOCKED"] = "PASSED"
    blocking_issue_count: int = Field(default=0, ge=0)
    requirement_traceability: list[RequirementTrace] = Field(default_factory=list)

    def dimension(self, dimension: EvaluationDimension) -> EvaluationDimensionScore:
        for score in self.dimension_scores:
            if score.dimension == dimension:
                return score
        raise KeyError(f"unknown evaluation dimension: {dimension}")


class EvaluationInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requirement: str = Field(min_length=1)
    prd: dict
    architecture_design: dict
    backend_design: dict
    frontend_skeleton: dict
    review_report: dict
    records: list[dict] = Field(default_factory=list)
    model_invocations: list[dict] = Field(default_factory=list)
    retrieved_sources: list[dict] = Field(default_factory=list)
    generated_files: list[dict | str] = Field(default_factory=list)
    retrieval_policy: str | None = None
    execution_policy: dict = Field(default_factory=dict)


class ExperimentRun(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    run_id: str = Field(min_length=1)
    workflow_key: str = Field(min_length=1)
    workflow_version: str = Field(min_length=1)
    prompt_versions: dict[str, str] = Field(default_factory=dict)
    model_configurations: dict[str, str] = Field(default_factory=dict, alias="model_config")
    overall_score: int = Field(ge=0, le=100)
    duration_ms: int = Field(ge=0)
    status: str = Field(min_length=1)
    estimated_cost: float = Field(default=0.0, ge=0.0)
    failure_count: int = Field(default=0, ge=0)
    human_quality_score: float | None = Field(default=None, ge=0, le=100)
    human_reviewer_count: int = Field(default=0, ge=0)


class ExperimentComparison(BaseModel):
    model_config = ConfigDict(extra="forbid")

    baseline_run_id: str = Field(min_length=1)
    candidate_run_id: str = Field(min_length=1)
    score_delta: int
    duration_delta_ms: int
    cost_delta: float
    failure_delta: int
    human_quality_score_delta: float | None = None
    changed_prompt_keys: list[str] = Field(default_factory=list)
    changed_model_keys: list[str] = Field(default_factory=list)


class ExperimentComparisonReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    baseline_run_id: str = Field(min_length=1)
    best_run_id: str = Field(min_length=1)
    rankings: list[ExperimentRun] = Field(min_length=1)
    comparisons: list[ExperimentComparison] = Field(default_factory=list)
    issues: list[EvaluationIssue] = Field(default_factory=list)


class MetricSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agent: dict[str, float | None] = Field(default_factory=dict)
    business: dict[str, float | None] = Field(default_factory=dict)
    rag: dict[str, float | None] = Field(default_factory=dict)
    engineering: dict[str, float | None] = Field(default_factory=dict)


class EvalCaseResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    status: Literal["SUCCEEDED", "PARTIAL", "FAILED"]
    overall_score: float = Field(ge=0, le=100)
    completed_nodes: int = Field(default=0, ge=0)
    failed_nodes: int = Field(default=0, ge=0)
    skill_coverage: float = Field(default=0.0, ge=0, le=1)
    trace_id: str = Field(min_length=1)
    trace: list[dict[str, Any]] = Field(default_factory=list)


class EvalRun(BaseModel):
    model_config = ConfigDict(extra="forbid")

    run_id: str = Field(min_length=1)
    dataset_version: str = Field(min_length=1)
    workflow_key: str = Field(min_length=1)
    workflow_version: str = Field(min_length=1)
    baseline_run_id: str | None = Field(default=None, min_length=1)
    code_version: str = Field(min_length=1)
    model_versions: dict[str, str] = Field(default_factory=dict)
    prompt_versions: dict[str, str] = Field(default_factory=dict)
    started_at_epoch_ms: int = Field(ge=0)
    duration_ms: int = Field(ge=0)
    status: Literal["SUCCEEDED", "PARTIAL", "FAILED"]
    metrics: MetricSnapshot
    case_results: list[EvalCaseResult] = Field(default_factory=list)
