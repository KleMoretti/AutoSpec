from pydantic import BaseModel, ConfigDict, Field


class ReviewIssue(BaseModel):
    model_config = ConfigDict(extra="forbid")

    severity: str = Field(min_length=1)
    issue_type: str = Field(min_length=1)
    description: str = Field(min_length=1)
    suggestion: str = Field(min_length=1)


class ReviewReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    score: int = Field(ge=0, le=100)
    issues: list[ReviewIssue] = Field(default_factory=list)
