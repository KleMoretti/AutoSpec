from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from pydantic import ValidationError

from schemas.spec_contract import SpecContract
from schemas.verification import VerificationCheck, VerificationIssue, VerificationReport
from spec_verifier.compiler import COMPILER_VERSION, compile_spec


VERIFIER_VERSION = "spec-verifier-v1"


def validate_l1(
    spec: SpecContract | dict[str, Any],
    *,
    execution_id: str = "l1-local",
    scope: str = "FULL",
) -> VerificationReport:
    raw = spec.model_dump(mode="json") if isinstance(spec, SpecContract) else spec
    source_digest = hashlib.sha256(_canonical(raw).encode("utf-8")).hexdigest()
    try:
        contract = spec if isinstance(spec, SpecContract) else SpecContract.model_validate(spec)
    except ValidationError as exc:
        issue = VerificationIssue(
            code="CONTRACT_SCHEMA_INVALID",
            severity="CRITICAL",
            message=str(exc)[:1000],
        )
        return _report(execution_id, "invalid", source_digest, [issue], [], scope=scope)

    issues: list[VerificationIssue] = []
    checks: list[VerificationCheck] = []
    _check_traceability(contract, issues)
    checks.append(_check("traceability", issues))
    _check_tables(contract, issues)
    checks.append(_check("data_model", issues))
    _check_apis(contract, issues)
    checks.append(_check("api_contract", issues))
    _check_bindings(contract, issues)
    checks.append(_check("frontend_bindings", issues))
    _check_security(contract, issues)
    checks.append(_check("security", issues))

    try:
        compiled = compile_spec(contract)
        source_digest = compiled.source_digest
        if _contains_unsafe_generated_content(compiled.files):
            issues.append(VerificationIssue(
                code="COMPILER_UNSAFE_OUTPUT",
                severity="CRITICAL",
                message="generated output contains an unsafe executable construct",
            ))
        if compile_spec(contract).manifest != compiled.manifest:
            issues.append(VerificationIssue(
                code="NON_DETERMINISTIC_COMPILATION",
                severity="CRITICAL",
                message="compiling the same contract produced different file hashes",
            ))
        checks.append(_check("compiler_safety", issues))
        checks.append(_check("generated_artifacts", issues))
        files = sorted(compiled.files)
    except (ValueError, KeyError, TypeError) as exc:
        issues.append(VerificationIssue(
            code="COMPILER_REJECTED_CONTRACT",
            severity="CRITICAL",
            message=str(exc)[:1000],
        ))
        checks.extend([_check("compiler_safety", issues), _check("generated_artifacts", issues)])
        files = []

    return _report(
        execution_id,
        contract.contract_id,
        source_digest,
        issues,
        checks,
        files,
        scope=scope,
    )


def _check_traceability(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    known = {item.requirement_id for item in contract.requirements}
    covered: set[str] = set()
    for table in contract.tables:
        covered.update(table.requirement_refs)
        covered.update(ref for field in table.fields for ref in field.requirement_refs)
    for api in contract.apis:
        covered.update(api.requirement_refs)
        covered.update(ref for parameter in api.parameters for ref in parameter.requirement_refs)
        covered.update(ref for response in api.responses for ref in response.requirement_refs)
    for binding in contract.frontend_bindings:
        covered.update(binding.requirement_refs)
    for ref in sorted(covered - known):
        issues.append(VerificationIssue(code="TRACE_UNKNOWN_REQUIREMENT", severity="HIGH", message=f"unknown requirement reference: {ref}"))
    for requirement in contract.requirements:
        if requirement.priority == "MUST" and requirement.requirement_id not in covered:
            issues.append(VerificationIssue(code="TRACE_MUST_UNCOVERED", severity="HIGH", message=f"MUST requirement is not covered: {requirement.requirement_id}"))


def _check_tables(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    tables = {table.name: table for table in contract.tables}
    for table in contract.tables:
        primary_keys = [field for field in table.fields if field.primary_key]
        if len(primary_keys) != 1:
            issues.append(VerificationIssue(code="TABLE_PRIMARY_KEY_INVALID", severity="HIGH", message=f"table {table.name} must have exactly one primary key"))
        fields = {field.name: field for field in table.fields}
        for field in table.fields:
            ref = field.foreign_key
            if ref is None:
                continue
            target = tables.get(ref.table)
            if target is None or not any(item.name == ref.field for item in target.fields):
                issues.append(VerificationIssue(code="TABLE_FOREIGN_KEY_UNKNOWN", severity="HIGH", message=f"foreign key {table.name}.{field.name} references missing {ref.table}.{ref.field}"))
                continue
            target_field = next(item for item in target.fields if item.name == ref.field)
            if target_field.type.kind != field.type.kind:
                issues.append(VerificationIssue(code="TABLE_FOREIGN_KEY_TYPE_MISMATCH", severity="HIGH", message=f"foreign key {table.name}.{field.name} has an incompatible type"))


def _check_apis(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    operations: set[tuple[str, str]] = set()
    for api in contract.apis:
        if (api.method, api.path) in operations:
            issues.append(VerificationIssue(code="API_DUPLICATE_OPERATION", severity="HIGH", message=f"duplicate API operation: {api.method} {api.path}"))
        operations.add((api.method, api.path))
        if api.auth_required and not api.roles:
            issues.append(VerificationIssue(code="API_AUTH_ROLE_MISSING", severity="HIGH", message=f"authenticated API has no role: {api.api_id}"))
        path_names = set(re.findall(r"\{([A-Za-z][A-Za-z0-9_]*)\}", api.path))
        declared = {item.name for item in api.parameters if item.location == "path"}
        if path_names != declared:
            issues.append(VerificationIssue(code="API_PATH_PARAM_MISMATCH", severity="HIGH", message=f"path parameters do not match: {api.api_id}"))


def _check_bindings(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    apis = {api.api_id: api for api in contract.apis}
    for binding in contract.frontend_bindings:
        api = apis.get(binding.api_id)
        if api is None:
            issues.append(VerificationIssue(code="BINDING_API_MISSING", severity="HIGH", message=f"binding references missing API: {binding.api_id}"))
            continue
        if (binding.method, binding.path) != (api.method, api.path):
            issues.append(VerificationIssue(code="BINDING_API_DRIFT", severity="HIGH", message=f"binding does not match API: {binding.binding_id}"))
        api_params = {item.name for item in api.parameters}
        for parameter in binding.parameters:
            if parameter.name not in api_params:
                issues.append(VerificationIssue(code="BINDING_PARAMETER_MISSING", severity="HIGH", message=f"binding parameter is not declared by API: {parameter.name}"))


def _check_security(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    for api in contract.apis:
        if any(token in api.path.lower() for token in ("..", ";", "\\", "//")):
            issues.append(VerificationIssue(code="SECURITY_UNSAFE_PATH", severity="CRITICAL", message=f"unsafe API path: {api.path}"))
    for table in contract.tables:
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", table.name):
            issues.append(VerificationIssue(code="SECURITY_UNSAFE_IDENTIFIER", severity="CRITICAL", message=f"unsafe table identifier: {table.name}"))


def _contains_unsafe_generated_content(files: dict[str, str]) -> bool:
    forbidden = ("eval(", "new Function", "child_process", "os.system", "shell=True", "--no-check")
    return any(token in content for content in files.values() for token in forbidden)


def _check(category: str, issues: list[VerificationIssue]) -> VerificationCheck:
    category_codes = {
        "traceability": {"TRACE_UNKNOWN_REQUIREMENT", "TRACE_MUST_UNCOVERED"},
        "data_model": {"TABLE_PRIMARY_KEY_INVALID", "TABLE_FOREIGN_KEY_UNKNOWN", "TABLE_FOREIGN_KEY_TYPE_MISMATCH"},
        "api_contract": {"API_DUPLICATE_OPERATION", "API_AUTH_ROLE_MISSING", "API_PATH_PARAM_MISMATCH"},
        "frontend_bindings": {"BINDING_API_MISSING", "BINDING_API_DRIFT", "BINDING_PARAMETER_MISSING"},
        "security": {"SECURITY_UNSAFE_PATH", "SECURITY_UNSAFE_IDENTIFIER"},
        "compiler_safety": {"COMPILER_UNSAFE_OUTPUT", "COMPILER_REJECTED_CONTRACT", "NON_DETERMINISTIC_COMPILATION"},
        "generated_artifacts": {"COMPILER_REJECTED_CONTRACT"},
    }
    codes = [issue.code for issue in issues if issue.code in category_codes.get(category, set())]
    return VerificationCheck(category=category, status="FAILED" if codes else "PASSED", issue_codes=sorted(set(codes)))


def _report(
    execution_id: str,
    contract_id: str,
    source_digest: str,
    issues: list[VerificationIssue],
    checks: list[VerificationCheck],
    files: list[str] | None = None,
    *,
    scope: str = "FULL",
) -> VerificationReport:
    blocked = any(issue.severity in {"HIGH", "CRITICAL"} for issue in issues)
    return VerificationReport(
        execution_id=execution_id,
        contract_id=contract_id,
        scope=scope,
        verifier_version=VERIFIER_VERSION,
        compiler_version=COMPILER_VERSION,
        level="L1",
        status="FAILED" if blocked else "PASSED",
        gate_status="BLOCKED" if blocked else "PASSED",
        source_digest=source_digest,
        checks=checks,
        issues=issues,
        generated_files=files or [],
    )


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
