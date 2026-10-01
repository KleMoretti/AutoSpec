"""Small, reviewed tool-boundary attack set for deterministic regression tests."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


InjectionFamily = Literal[
    "UNKNOWN_TOOL",
    "INVALID_ARGUMENTS",
    "IDEMPOTENCY_CONFLICT",
    "SCOPE_ESCAPE",
    "PERMISSION_ESCAPE",
]


class ToolInjectionCase(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: str = Field(min_length=1)
    family: InjectionFamily
    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    arguments: dict[str, object] = Field(default_factory=dict)
    idempotency_key: str = Field(min_length=1)
    expected_error_code: str = Field(min_length=1)


def tool_injection_cases() -> list[ToolInjectionCase]:
    """Return 30 stable rows: five attack families, six rows each."""

    cases: list[ToolInjectionCase] = []
    for index in range(6):
        cases.append(ToolInjectionCase(
            case_id=f"unknown-tool-{index:02d}",
            family="UNKNOWN_TOOL",
            name=f"shell.exec.v{index + 1}",
            version="v1",
            idempotency_key=f"unknown-{index:02d}",
            expected_error_code="TOOL_NOT_ALLOWED",
        ))
        invalid_arguments = (
            {},
            {"query": ""},
            {"query": "x", "limit": 0},
            {"query": "x", "limit": 21},
            {"query": "x", "unexpected": True},
            {"query": "x" * 10001},
        )[index]
        cases.append(ToolInjectionCase(
            case_id=f"invalid-arguments-{index:02d}",
            family="INVALID_ARGUMENTS",
            name="knowledge.search",
            version="v1",
            arguments=invalid_arguments,
            idempotency_key=f"invalid-{index:02d}",
            expected_error_code="TOOL_INPUT_INVALID",
        ))
        cases.append(ToolInjectionCase(
            case_id=f"idempotency-conflict-{index:02d}",
            family="IDEMPOTENCY_CONFLICT",
            name="knowledge.search",
            version="v1",
            arguments={"query": f"different-{index}"},
            idempotency_key=f"conflict-{index:02d}",
            expected_error_code="IDEMPOTENCY_CONFLICT",
        ))
        cases.append(ToolInjectionCase(
            case_id=f"scope-escape-{index:02d}",
            family="SCOPE_ESCAPE",
            name="knowledge.search",
            version="v1",
            arguments={"query": f"outside-project-{index}"},
            idempotency_key=f"scope-{index:02d}",
            expected_error_code="TOOL_SCOPE_DENIED",
        ))
        cases.append(ToolInjectionCase(
            case_id=f"permission-escape-{index:02d}",
            family="PERMISSION_ESCAPE",
            name="knowledge.search",
            version="v1",
            arguments={"query": f"write-or-admin-{index}"},
            idempotency_key=f"permission-{index:02d}",
            expected_error_code="TOOL_PERMISSION_DENIED",
        ))
    return cases
