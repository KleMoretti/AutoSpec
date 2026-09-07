from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from schemas.backend_design import BackendDesignArtifact
from schemas.prd import PrdArtifact


class BackendValidationIssue(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    code: str = Field(min_length=1)
    path: str = Field(min_length=1)
    message: str = Field(min_length=1)
    required_change: str = Field(min_length=1)


class BackendValidationResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    valid: bool
    candidate: BackendDesignArtifact | None = None
    issues: list[BackendValidationIssue] = Field(default_factory=list)


def validate_backend_candidate(
    candidate: Any,
    prd: PrdArtifact,
    *,
    profile: str = "backend-design-v1",
) -> BackendValidationResult:
    """Run deterministic checks needed before a BackendDesignArtifact can finish."""

    issues: list[BackendValidationIssue] = []
    if profile != "backend-design-v1":
        issues.append(
            _issue(
                "VALIDATOR_PROFILE_UNSUPPORTED",
                "$",
                f"Unsupported backend validator profile: {profile}.",
                "Use the frozen backend-design-v1 validator profile.",
            )
        )

    parsed: BackendDesignArtifact | None = None
    try:
        parsed = BackendDesignArtifact.model_validate(candidate)
    except ValidationError as error:
        message = _compact_validation_error(error)
        code = (
            "API_METHOD_PATH_DUPLICATE"
            if "method/path pairs must be unique" in str(error)
            else "SCHEMA_INVALID"
        )
        issues.append(
            _issue(
                code,
                "$",
                message,
                "Return a BackendDesignArtifact that satisfies its JSON Schema.",
            )
        )

    if parsed is not None:
        known_requirements = {
            feature.requirement_id
            for feature in prd.core_features
            if feature.requirement_id is not None
        }
        must_requirements = {
            feature.requirement_id
            for feature in prd.core_features
            if feature.priority == "MUST" and feature.requirement_id is not None
        }
        references = {
            reference
            for table in parsed.tables
            for reference in table.requirement_refs
        }
        references.update(
            reference
            for table in parsed.tables
            for field in table.fields
            for reference in field.requirement_refs
        )
        references.update(
            reference
            for api in parsed.apis
            for reference in api.requirement_refs
        )
        for reference in sorted(references - known_requirements):
            issues.append(
                _issue(
                    "REQUIREMENT_REF_INVALID",
                    "$.requirement_refs",
                    f"Backend design references unknown requirement {reference}.",
                    "Use only requirement ids declared by the PRD.",
                )
            )
        for requirement_id in sorted(must_requirements - references):
            issues.append(
                _issue(
                    "MUST_COVERAGE_MISSING",
                    "$.requirement_refs",
                    f"MUST requirement {requirement_id} has no backend evidence.",
                    "Add an API, table, or field with a requirement_refs entry.",
                )
            )
        for index, api in enumerate(parsed.apis):
            if not api.response_fields:
                issues.append(
                    _issue(
                        "API_RESPONSE_INCOMPLETE",
                        f"$.apis[{index}].response_fields",
                        f"API {api.method} {api.path} has no response fields.",
                        "Declare the structured response fields returned by the API.",
                    )
                )
            if api.auth_required and not api.required_roles:
                issues.append(
                    _issue(
                        "AUTH_ROLE_MISSING",
                        f"$.apis[{index}].required_roles",
                        f"Authenticated API {api.method} {api.path} has no required role.",
                        "Declare at least one role for an authenticated API.",
                    )
                )
            if not api.auth_required and api.required_roles:
                issues.append(
                    _issue(
                        "AUTH_FLAG_CONFLICT",
                        f"$.apis[{index}].auth_required",
                        f"API {api.method} {api.path} declares roles without authentication.",
                        "Set auth_required=true or remove the role requirement.",
                    )
                )

    return BackendValidationResult(
        valid=not issues,
        candidate=parsed if not issues else parsed,
        issues=issues,
    )


def _issue(code: str, path: str, message: str, required_change: str) -> BackendValidationIssue:
    return BackendValidationIssue(
        code=code,
        path=path,
        message=message,
        required_change=required_change,
    )


def _compact_validation_error(error: ValidationError) -> str:
    first = error.errors()[0] if error.errors() else {}
    location = ".".join(str(item) for item in first.get("loc", ()))
    message = str(first.get("msg", "candidate schema validation failed"))
    return f"{location}: {message}" if location else message
