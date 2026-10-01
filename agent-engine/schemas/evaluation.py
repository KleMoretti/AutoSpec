from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.traceability import RequirementId
from schemas.workflow_spec import RetrievalPolicySpec


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
    business_domain: str = Field(default="general", min_length=1)
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
    repetition: int = Field(default=1, ge=1)
    workflow_run_id: str | None = None
    trace_id: str | None = None
    bundle_hash: str | None = None
    rubric_ref: str | None = None
    status: Literal["SUCCEEDED", "PARTIAL", "FAILED", "NOT_EXECUTED"]
    gate_pass: bool | None = None
    must_trace_coverage: float | None = Field(default=None, ge=0.0, le=1.0)
    blocking_issue_count: int | None = Field(default=None, ge=0)
    unauthorized_tool_requests: int | None = Field(default=None, ge=0)
    invalid_tool_arguments: int | None = Field(default=None, ge=0)
    tool_call_count: int | None = Field(default=None, ge=0)
    unauthorized_tool_executions: int | None = Field(default=None, ge=0)
    schema_invalid_count: int | None = Field(default=None, ge=0)
    duration_ms: float | None = Field(default=None, ge=0)
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
    interval_low: float | None = None
    interval_high: float | None = None
    sample_count: int | None = Field(default=None, ge=0)
    statistic_version: str | None = None


class AutoSpecEvalRun(BaseModel):
    model_config = ConfigDict(extra="forbid")

    run_id: str = Field(min_length=1)
    dataset_version: str = Field(min_length=1)
    dataset_hash: str | None = None
    dataset_split: str | None = None
    environment_hash: str | None = None
    pricing_snapshot: dict[str, Any] = Field(default_factory=dict)
    group: Literal["A", "B", "C", "D"]
    group_name: Literal[
        "single-shot",
        "loop-no-tools",
        "loop-with-tools",
        "loop-tools-verify",
        # Compatibility for historical result files. New manifests use
        # loop-tools-verify so the D/C distinction names spec.verify feedback.
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
    runs: list[AutoSpecEvalRun] = Field(min_length=1)


class AutoSpecGateDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: Literal["PROMOTE", "REVISE", "REJECT", "NOT_EVALUATED"]
    gate_status: Literal["PASSED", "BLOCKED", "NOT_EVALUATED"]
    reasons: list[str] = Field(min_length=1)
    baseline_run_id: str = Field(min_length=1)
    candidate_run_id: str = Field(min_length=1)
    statistics_version: str | None = None
    paired_statistics: dict[str, Any] = Field(default_factory=dict)


class AutoSpecEvaluationComparison(BaseModel):
    """Read-only comparison envelope for the evaluation dashboard.

    The envelope deliberately keeps an explicit NOT_EVALUATED state.  The
    dashboard may render missing live evidence, but it must not turn the
    absence of a collected matrix into zero-valued metrics or a promotion.
    """

    model_config = ConfigDict(extra="forbid")

    status: Literal["MEASURED", "NOT_EVALUATED"]
    source: Literal["RESULT_DIRECTORY", "NONE"]
    matrix: AutoSpecAblationMatrix
    decision: AutoSpecGateDecision
    not_evaluated_reason: str | None = None


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

    @model_validator(mode="before")
    @classmethod
    def accept_control_plane_retrieval_envelope(cls, value: Any) -> Any:
        """Keep the published v1 fingerprint compatible with trusted metadata.

        The backend adds retrieval provenance to the node input for audit and
        context routing.  The historical v1 evaluator does not consume those
        fields; the v2 runtime model below does.  Strip only for this exact
        compatibility model so the published v5-parallel contract remains
        immutable while newer contracts retain typed provenance.
        """
        if cls is not EvaluationInput or not isinstance(value, Mapping):
            return value
        normalized = dict(value)
        for field in (
            "retrieval_project_id",
            "retrieval_node_id",
            "corpus_epoch",
            "actor_scope_hash",
            "retrieval_cache_key",
            "retrieval_cache",
            "retrieval_trace",
            "retrieval_snapshot",
        ):
            normalized.pop(field, None)
        return normalized


class EvaluationReportV2(EvaluationReport):
    """Candidate-only report carrying a trusted verifier fact."""

    verification_fact: dict[str, Any] | None = None


class EvaluationInputV2(EvaluationInput):
    """Candidate-only evaluator input carrying frozen verification policy/evidence."""

    verification_policy: dict[str, Any] = Field(default_factory=dict)
    verification_fact: dict[str, Any] | None = None


class EvaluationInputV3(EvaluationInputV2):
    """Candidate evaluator input with trusted control-plane retrieval provenance."""

    retrieval_policy: RetrievalPolicySpec | str | None = None
    retrieval_project_id: int | None = Field(default=None, ge=1)
    retrieval_node_id: str | None = None
    corpus_epoch: int | None = Field(default=None, ge=0)
    actor_scope_hash: str | None = None
    retrieval_cache_key: str | None = None
    retrieval_cache: dict[str, Any] = Field(default_factory=dict)
    retrieval_trace: dict[str, Any] = Field(default_factory=dict)
    retrieval_snapshot: dict[str, Any] = Field(default_factory=dict)
    rework_directive: dict[str, Any] | None = None


class EvaluationRuntimeInput(EvaluationInput):
    """V2 worker input: preserve trusted control-plane retrieval provenance."""

    retrieval_policy: RetrievalPolicySpec | str | None = None
    retrieval_project_id: int | None = Field(default=None, ge=1)
    retrieval_node_id: str | None = None
    corpus_epoch: int | None = Field(default=None, ge=0)
    actor_scope_hash: str | None = None
    retrieval_cache_key: str | None = None
    retrieval_cache: dict[str, Any] = Field(default_factory=dict)
    retrieval_trace: dict[str, Any] = Field(default_factory=dict)
    retrieval_snapshot: dict[str, Any] = Field(default_factory=dict)
    rework_directive: dict[str, Any] | None = None
