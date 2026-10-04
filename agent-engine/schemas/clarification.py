from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from schemas.prd import PrdArtifact


ClarificationKind = Literal["CLARIFICATION_REQUIRED", "PRD_READY"]
ClarificationAcceptanceStatus = Literal["PENDING", "ACCEPTED", "REJECTED"]
ClarificationResolution = Literal["KEEP_CURRENT", "USE_NEW", "CUSTOM"]


class ClarificationPolicy(BaseModel):
    """Frozen limits for the Product Manager's human-input phase."""

    model_config = ConfigDict(extra="forbid")

    enabled: bool = False
    max_rounds: int = Field(default=2, ge=1, le=10)
    max_questions_per_round: int = Field(default=5, ge=1, le=20)
    waiting_expiry_ms: int = Field(default=86_400_000, ge=1_000, le=30 * 86_400_000)
    allowed_actions: list[str] = Field(
        default_factory=lambda: [
            "ANSWER",
            "ACCEPT_ASSUMPTION",
            "RESOLVE_CONFLICT",
            "CANCEL",
        ]
    )

    @model_validator(mode="after")
    def validate_actions(self) -> "ClarificationPolicy":
        if len(self.allowed_actions) != len(set(self.allowed_actions)):
            raise ValueError("clarification allowed_actions must be unique")
        if not set(self.allowed_actions).issubset(
            {"ANSWER", "ACCEPT_ASSUMPTION", "RESOLVE_CONFLICT", "CANCEL"}
        ):
            raise ValueError("clarification allowed_actions contains an unsupported action")
        return self


class ClarificationQuestion(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question_id: str = Field(min_length=1, max_length=128)
    category: str = Field(min_length=1, max_length=64)
    question: str = Field(min_length=1, max_length=2_000)
    reason: str = Field(min_length=1, max_length=2_000)
    blocking: bool = False
    options: list[str] = Field(default_factory=list, max_length=12)
    related_requirement_refs: list[str] = Field(default_factory=list, max_length=32)

    @model_validator(mode="after")
    def validate_options_and_refs(self) -> "ClarificationQuestion":
        if len(self.options) != len(set(self.options)):
            raise ValueError("clarification question options must be unique")
        if any(not option.strip() for option in self.options):
            raise ValueError("clarification question options must not be blank")
        if len(self.related_requirement_refs) != len(
            set(self.related_requirement_refs)
        ):
            raise ValueError("clarification requirement refs must be unique")
        return self


class RequirementAssumption(BaseModel):
    model_config = ConfigDict(extra="forbid")

    assumption_id: str = Field(min_length=1, max_length=128)
    statement: str = Field(min_length=1, max_length=2_000)
    impact: str = Field(min_length=1, max_length=2_000)
    origin: str = Field(min_length=1, max_length=128)
    acceptance_status: ClarificationAcceptanceStatus = "PENDING"


class ClarificationConflict(BaseModel):
    model_config = ConfigDict(extra="forbid")

    conflict_id: str = Field(min_length=1, max_length=128)
    fact_type: str = Field(min_length=1, max_length=128)
    fact_key: str = Field(min_length=1, max_length=256)
    value: Any = None
    source_ref: str | None = Field(default=None, max_length=512)
    version: int | str | None = None
    blocking: bool = False
    reason: str = Field(min_length=1, max_length=2_000)
    status: str = Field(default="OPEN", min_length=1, max_length=64)


class ClarificationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: Literal["clarification-v1"] = "clarification-v1"
    request_id: str = Field(min_length=1, max_length=128)
    lock_version: int = Field(default=0, ge=0)
    round: int = Field(ge=1, le=10)
    original_requirement_ref: str = Field(min_length=1, max_length=2_000)
    questions: list[ClarificationQuestion] = Field(default_factory=list, max_length=5)
    assumptions: list[RequirementAssumption] = Field(default_factory=list, max_length=20)
    context_conflicts: list[ClarificationConflict] = Field(
        default_factory=list, max_length=20
    )
    summary: str = Field(min_length=1, max_length=4_000)

    @model_validator(mode="after")
    def validate_identity_and_need(self) -> "ClarificationRequest":
        question_ids = [question.question_id for question in self.questions]
        if len(question_ids) != len(set(question_ids)):
            raise ValueError("clarification question_id values must be unique")
        assumption_ids = [assumption.assumption_id for assumption in self.assumptions]
        if len(assumption_ids) != len(set(assumption_ids)):
            raise ValueError("clarification assumption_id values must be unique")
        conflict_ids = [conflict.conflict_id for conflict in self.context_conflicts]
        if len(conflict_ids) != len(set(conflict_ids)):
            raise ValueError("clarification conflict_id values must be unique")
        if not self.questions and not any(
            conflict.blocking for conflict in self.context_conflicts
        ):
            raise ValueError("clarification request must contain a question or blocking conflict")
        return self


class ClarificationAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question_id: str = Field(min_length=1, max_length=128)
    value: str | list[str] = Field(min_length=1)
    note: str | None = Field(default=None, max_length=2_000)


class ConflictResolution(BaseModel):
    model_config = ConfigDict(extra="forbid")

    conflict_id: str = Field(min_length=1, max_length=128)
    resolution: ClarificationResolution
    value: Any = None
    note: str | None = Field(default=None, max_length=2_000)


class ClarificationResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=128)
    expected_lock_version: int = Field(ge=0)
    idempotency_key: str = Field(min_length=1, max_length=256)
    answers: list[ClarificationAnswer] = Field(default_factory=list, max_length=5)
    accepted_assumption_ids: list[str] = Field(default_factory=list, max_length=20)
    conflict_resolutions: list[ConflictResolution] = Field(
        default_factory=list, max_length=20
    )
    # The API accepts no client-supplied actor. The control plane fills this
    # field after authentication before persisting the response.
    actor: str | None = Field(default=None, max_length=256)

    @model_validator(mode="after")
    def validate_answer_identity(self) -> "ClarificationResponse":
        question_ids = [answer.question_id for answer in self.answers]
        if len(question_ids) != len(set(question_ids)):
            raise ValueError("clarification answers must contain unique question_id values")
        if len(self.accepted_assumption_ids) != len(set(self.accepted_assumption_ids)):
            raise ValueError("accepted assumption ids must be unique")
        conflict_ids = [item.conflict_id for item in self.conflict_resolutions]
        if len(conflict_ids) != len(set(conflict_ids)):
            raise ValueError("conflict resolutions must be unique")
        return self


class ProductManagerResult(BaseModel):
    """Versioned PM envelope; clarification and PRD payloads are exclusive."""

    model_config = ConfigDict(extra="forbid")

    version: Literal["product-manager-result-v1"] = "product-manager-result-v1"
    kind: ClarificationKind
    clarification_request: ClarificationRequest | None = None
    prd: PrdArtifact | None = None

    @model_validator(mode="after")
    def validate_payload(self) -> "ProductManagerResult":
        has_request = self.clarification_request is not None
        has_prd = self.prd is not None
        if self.kind == "CLARIFICATION_REQUIRED" and (not has_request or has_prd):
            raise ValueError(
                "CLARIFICATION_REQUIRED requires clarification_request and forbids prd"
            )
        if self.kind == "PRD_READY" and (not has_prd or has_request):
            raise ValueError("PRD_READY requires prd and forbids clarification_request")
        return self
