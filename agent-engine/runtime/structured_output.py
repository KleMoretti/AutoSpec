"""Bounded structured-output validation and repair for model-backed nodes."""

from __future__ import annotations

import time
from typing import Any, Mapping

from pydantic import BaseModel, ValidationError

from runtime.execution_context import current_model_execution_contract


class StructuredOutputError(RuntimeError):
    error_code = "STRUCTURED_OUTPUT_INVALID"

    def __init__(self, message: str, *, error_code: str | None = None) -> None:
        super().__init__(message)
        if error_code is not None:
            self.error_code = error_code


def generate_structured_output(
    model_client: Any,
    prompt_name: str,
    input_payload: Mapping[str, Any],
    output_model: type[BaseModel],
) -> BaseModel:
    """Call a model and allow at most the frozen repair quota.

    A repair is another invocation of the same frozen prompt/schema contract.
    Provider, deadline, budget, authentication, and transport errors are
    propagated unchanged; only malformed structured output is repairable.
    """

    contract = current_model_execution_contract()
    repair_policy = (
        contract.model_policy.get("structured_output_repair", {})
        if contract is not None
        else {}
    )
    enabled = bool(repair_policy.get("enabled", False))
    max_repairs = min(1, max(0, int(repair_policy.get("max_repairs", 0)))) if enabled else 0

    payload: Mapping[str, Any] = input_payload
    last_issues: list[dict[str, str]] = []
    last_candidate: Any = None
    last_error_code = "STRUCTURED_OUTPUT_INVALID"
    for attempt in range(max_repairs + 1):
        if contract is not None and time.time() * 1000 >= contract.deadline_epoch_ms:
            raise TimeoutError("structured output repair deadline elapsed")
        try:
            candidate = model_client.generate_json(prompt_name, payload)
            last_candidate = candidate
            return output_model.model_validate(candidate)
        except ValidationError as exc:
            # Preserve the legacy node failure category for schema validation;
            # malformed provider JSON remains STRUCTURED_OUTPUT_INVALID.
            last_error_code = "VALIDATION_ERROR"
            last_issues = _validation_issues(exc)
        except Exception as exc:
            if getattr(exc, "error_code", None) != "STRUCTURED_OUTPUT_INVALID":
                raise
            last_error_code = "STRUCTURED_OUTPUT_INVALID"
            last_issues = [{"path": "$", "code": "STRUCTURED_OUTPUT_INVALID", "message": str(exc)[:300]}]

        max_model_calls = int(contract.model_policy.get("max_calls", 1)) if contract is not None else 1
        if attempt >= max_repairs or attempt + 1 >= max_model_calls:
            break
        payload = {
            **dict(input_payload),
            "_structured_output_repair": {
                "attempt": attempt + 1,
                "candidate": _bounded_candidate(last_candidate),
                "issues": last_issues[:8],
                "instruction": "Return only a JSON object that satisfies the frozen output schema.",
            },
        }

    detail = "; ".join(
        f"{issue['path']}: {issue['message']}" for issue in last_issues[:8]
    )
    raise StructuredOutputError(
        "Model output did not satisfy the frozen structured schema after the allowed repair attempt."
        + (f" {detail}" if detail else ""),
        error_code=last_error_code,
    )


def _validation_issues(exception: ValidationError) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    for item in exception.errors()[:8]:
        location = ".".join(str(part) for part in item.get("loc", ())) or "$"
        issues.append({
            "path": location,
            "code": str(item.get("type", "validation_error")),
            "message": str(item.get("msg", "invalid value"))[:300],
        })
    return issues or [{"path": "$", "code": "validation_error", "message": "invalid output"}]


def _bounded_candidate(candidate: Any) -> Any:
    if isinstance(candidate, Mapping):
        result: dict[str, Any] = {}
        for key, value in list(candidate.items())[:64]:
            text = str(value)
            result[str(key)] = text[:1000] if len(text) > 1000 else value
        return result
    if candidate is None:
        return None
    return str(candidate)[:4000]
