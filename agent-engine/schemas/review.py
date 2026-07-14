from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ReviewIssue(BaseModel):
    model_config = ConfigDict(extra="forbid")

    severity: str = Field(min_length=1)
    issue_type: str = Field(min_length=1)
    description: str = Field(min_length=1)
    suggestion: str = Field(min_length=1)


class ReviewDecision(StrEnum):
    PASS = "PASS"
    REWORK = "REWORK"


class ReworkRoute(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target_node: str = Field(
        pattern=r"^(architect|backend_engineer|frontend_engineer)$"
    )
    issue_ids: list[str] = Field(min_length=1)
    required_changes: list[str] = Field(min_length=1)
    invalidate_downstream: bool = True


class ReviewReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    score: int = Field(ge=0, le=100)
    issues: list[ReviewIssue] = Field(default_factory=list)
    decision: ReviewDecision = ReviewDecision.PASS
    routes: list[ReworkRoute] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_routes_match_decision(self) -> "ReviewReport":
        if self.decision == ReviewDecision.REWORK and not self.routes:
            raise ValueError("REWORK decision requires at least one route")
        if self.decision == ReviewDecision.PASS and self.routes:
            raise ValueError("PASS decision must not contain rework routes")
        return self
