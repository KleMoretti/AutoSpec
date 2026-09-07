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


class AutoSpecRequirementExpectation(BaseModel):
    """A deterministic MUST/SHOULD trace target for the AutoSpec eval set."""

    model_config = ConfigDict(extra="forbid")

    requirement_id: str = Field(min_length=1)
    statement: str = Field(min_length=1)
    priority: Literal["MUST", "SHOULD", "COULD"] = "MUST"
    api_evidence: list[str] = Field(default_factory=list)
    data_evidence: list[str] = Field(default_factory=list)
    ui_evidence: list[str] = Field(default_factory=list)
    acceptance_evidence: list[str] = Field(default_factory=list)


class AutoSpecEvalCase(BaseModel):
    """Provider-neutral evaluation case for the six-node AutoSpec product."""

    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    category: Literal[
        "CRUD",
        "APPROVAL",
        "PERMISSION",
        "MULTI_ENTITY",
        "EXTERNAL_INTEGRATION",
        "AMBIGUOUS_REQUIREMENT",
        "CONFLICTING_CONSTRAINTS",
        "REWORK",
    ]
    requirement: str = Field(min_length=1)
    dataset_version: str = Field(default="autospec-v5-agent-execution-eval-v1", min_length=1)
    must_requirements: list[AutoSpecRequirementExpectation] = Field(min_length=1)
    expected_artifact_types: list[str] = Field(min_length=1)
    allowed_tools: list[str] = Field(default_factory=list)
    prohibited_tools: list[str] = Field(default_factory=list)
    failure_conditions: list[str] = Field(min_length=1)


class AutoSpecCaseResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    status: Literal["SUCCEEDED", "PARTIAL", "FAILED", "NOT_EXECUTED"]
    gate_pass: bool | None = None
    must_trace_coverage: float | None = Field(default=None, ge=0.0, le=1.0)
    blocking_issue_count: int | None = Field(default=None, ge=0)
    unauthorized_tool_requests: int | None = Field(default=None, ge=0)
    invalid_tool_arguments: int | None = Field(default=None, ge=0)
    steps: int | None = Field(default=None, ge=0)
    replans: int | None = Field(default=None, ge=0)
    path_oscillations: int | None = Field(default=None, ge=0)
    p95_latency_ms: float | None = Field(default=None, ge=0.0)
    tokens: int | None = Field(default=None, ge=0)
    cost: float | None = Field(default=None, ge=0.0)
    failure_codes: list[str] = Field(default_factory=list)


class AutoSpecMetric(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    status: Literal["MEASURED", "NOT_EXECUTED", "UNAVAILABLE"]
    value: float | None = None
    unit: str = Field(min_length=1)
    source: str = Field(min_length=1)
    note: str | None = None


class AutoSpecEvalRun(BaseModel):
    model_config = ConfigDict(extra="forbid")

    run_id: str = Field(min_length=1)
    dataset_version: str = Field(min_length=1)
    group: Literal["A", "B", "C", "D"]
    group_name: Literal[
        "single-shot",
        "loop-no-tools",
        "loop-with-tools",
        "loop-tools-replan",
    ]
    execution_mode: Literal["LIVE_CONTROL_PLANE", "FIXTURE_BASELINE"]
    workflow_key: str = Field(default="autospec-v5", min_length=1)
    workflow_version: str = Field(min_length=1)
    code_version: str = Field(min_length=1)
    bundle_hash: str | None = None
    prompt_schema_versions: dict[str, str] = Field(default_factory=dict)
    model_version: str | None = None
    retriever_version: str | None = None
    tool_policy_version: str | None = None
    budget_version: str = Field(min_length=1)
    random_seed: int | None = None
    status: Literal["SUCCEEDED", "PARTIAL", "FAILED", "NOT_EXECUTED"]
    gate_status: Literal["PASSED", "BLOCKED", "NOT_EVALUATED"]
    decision: Literal["PROMOTE", "REVISE", "REJECT", "NOT_EVALUATED"]
    not_executed_reason: str | None = None
    case_results: list[AutoSpecCaseResult] = Field(default_factory=list)
    metrics: list[AutoSpecMetric] = Field(default_factory=list)


class AutoSpecAblationMatrix(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matrix_id: str = Field(min_length=1)
    dataset_version: str = Field(min_length=1)
    generated_at_epoch_ms: int = Field(ge=0)
    runs: list[AutoSpecEvalRun] = Field(min_length=4)


class AutoSpecGateDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: Literal["PROMOTE", "REVISE", "REJECT", "NOT_EVALUATED"]
    gate_status: Literal["PASSED", "BLOCKED", "NOT_EVALUATED"]
    reasons: list[str] = Field(min_length=1)
    baseline_run_id: str = Field(min_length=1)
    candidate_run_id: str = Field(min_length=1)


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
