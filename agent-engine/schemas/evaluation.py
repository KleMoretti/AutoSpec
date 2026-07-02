from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


EvaluationDimension = Literal[
    "SCHEMA_VALIDITY",
    "REQUIREMENT_COVERAGE",
    "CROSS_ARTIFACT_CONSISTENCY",
    "PERMISSION_COVERAGE",
    "RAG_CITATION_QUALITY",
    "RUNTIME_RELIABILITY",
    "EXPORT_READINESS",
]


class EvaluationCase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    requirement: str = Field(min_length=1)
    expected_capabilities: list[str] = Field(default_factory=list)
    required_artifact_types: list[str] = Field(default_factory=list)


class EvaluationIssue(BaseModel):
    model_config = ConfigDict(extra="forbid")

    severity: str = Field(min_length=1)
    issue_type: str = Field(min_length=1)
    description: str = Field(min_length=1)
    suggestion: str = Field(min_length=1)
    evidence: list[str] = Field(default_factory=list)


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
    retrieved_sources: list[dict] = Field(default_factory=list)
    generated_files: list[dict | str] = Field(default_factory=list)
