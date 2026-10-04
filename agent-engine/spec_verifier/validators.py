from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from pydantic import ValidationError

from schemas.spec_contract import SpecContract, SpecContractV2, SpecFieldType, normalise_spec_contract
from schemas.verification import VerificationCheck, VerificationIssue, VerificationReport
from spec_verifier.compiler import (
    COMPILER_VERSION,
    COMPILER_VERSION_V2,
    _openapi_type,
    _sql_type,
    compile_spec,
)


VERIFIER_VERSION = "spec-verifier-v1"
VERIFIER_VERSION_V2 = "spec-verifier-v2"


def validate_l1(
    spec: SpecContract | dict[str, Any],
    *,
    execution_id: str = "l1-local",
    scope: str = "FULL",
) -> VerificationReport:
    raw = spec.model_dump(mode="json") if isinstance(spec, SpecContract) else spec
    source_digest = hashlib.sha256(_canonical(raw).encode("utf-8")).hexdigest()
    try:
        contract = normalise_spec_contract(spec)
    except ValidationError as exc:
        issue = VerificationIssue(
            code="CONTRACT_SCHEMA_INVALID",
            severity="CRITICAL",
            message=str(exc)[:1000],
        )
        return _report(execution_id, "invalid", source_digest, [issue], [], scope=scope)

    explicit = isinstance(contract, SpecContractV2)
    verifier_version = VERIFIER_VERSION_V2 if explicit else VERIFIER_VERSION
    compiler_version = COMPILER_VERSION_V2 if explicit else COMPILER_VERSION

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
        _check_generated_documents(contract, compiled.files, issues)
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
        verifier_version=verifier_version,
        compiler_version=compiler_version,
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
            issues.append(VerificationIssue(code="TABLE_PRIMARY_KEY_INVALID", severity="HIGH", message=f"table {table.name} must have exactly one primary key", path=f"artifact.tables.{table.table_id}"))
        fields = {field.name: field for field in table.fields}
        for field in table.fields:
            ref = field.foreign_key
            if ref is None:
                continue
            target = tables.get(ref.table)
            if target is None or not any(item.name == ref.field for item in target.fields):
                issues.append(VerificationIssue(code="TABLE_FOREIGN_KEY_UNKNOWN", severity="HIGH", message=f"foreign key {table.name}.{field.name} references missing {ref.table}.{ref.field}", path=f"artifact.tables.{table.table_id}.fields.{field.field_id}"))
                continue
            target_field = next(item for item in target.fields if item.name == ref.field)
            if target_field.type.kind != field.type.kind:
                issues.append(VerificationIssue(code="TABLE_FOREIGN_KEY_TYPE_MISMATCH", severity="HIGH", message=f"foreign key {table.name}.{field.name} has an incompatible type", path=f"artifact.tables.{table.table_id}.fields.{field.field_id}"))
            elif target_field.type.model_dump(mode="json") != field.type.model_dump(mode="json"):
                issues.append(VerificationIssue(code="TABLE_FOREIGN_KEY_SHAPE_MISMATCH", severity="HIGH", message=f"foreign key {table.name}.{field.name} has incompatible MySQL type parameters", path=f"artifact.tables.{table.table_id}.fields.{field.field_id}"))


def _check_apis(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    operations: set[tuple[str, str]] = set()
    for api in contract.apis:
        if (api.method, api.path) in operations:
            issues.append(VerificationIssue(code="API_DUPLICATE_OPERATION", severity="HIGH", message=f"duplicate API operation: {api.method} {api.path}", path=f"artifact.apis.{api.api_id}"))
        operations.add((api.method, api.path))
        if api.auth_required and not api.roles:
            issues.append(VerificationIssue(code="API_AUTH_ROLE_MISSING", severity="HIGH", message=f"authenticated API has no role: {api.api_id}", path=f"artifact.apis.{api.api_id}"))
        path_names = set(re.findall(r"\{([A-Za-z][A-Za-z0-9_]*)\}", api.path))
        declared = {item.name for item in api.parameters if item.location == "path"}
        if path_names != declared:
            issues.append(VerificationIssue(code="API_PATH_PARAM_MISMATCH", severity="HIGH", message=f"path parameters do not match: {api.api_id}", path=f"artifact.apis.{api.api_id}"))
        if isinstance(contract, SpecContractV2):
            for parameter in api.parameters:
                if parameter.location == "path" and not parameter.required:
                    issues.append(VerificationIssue(code="API_PATH_PARAM_REQUIRED", severity="HIGH", message=f"path parameter must be required: {api.api_id}.{parameter.name}", path=f"apis.{api.api_id}.parameters.{parameter.name}"))
            response_names = [field.name for field in api.responses]
            if len(response_names) != len(set(response_names)):
                issues.append(VerificationIssue(code="API_RESPONSE_DUPLICATE_FIELD", severity="HIGH", message=f"duplicate response field: {api.api_id}", path=f"apis.{api.api_id}.responses"))


def _check_bindings(contract: SpecContract, issues: list[VerificationIssue]) -> None:
    apis = {api.api_id: api for api in contract.apis}
    for binding in contract.frontend_bindings:
        api = apis.get(binding.api_id)
        if api is None:
            issues.append(VerificationIssue(code="BINDING_API_MISSING", severity="HIGH", message=f"binding references missing API: {binding.api_id}"))
            continue
        if (binding.method, binding.path) != (api.method, api.path):
            issues.append(VerificationIssue(code="BINDING_API_DRIFT", severity="HIGH", message=f"binding does not match API: {binding.binding_id}"))
        if isinstance(contract, SpecContractV2):
            _check_explicit_binding(binding, api, issues)
            continue
        api_params = {item.name for item in api.parameters}
        for parameter in binding.parameters:
            if parameter.name not in api_params:
                issues.append(VerificationIssue(code="BINDING_PARAMETER_MISSING", severity="HIGH", message=f"binding parameter is not declared by API: {parameter.name}"))


def _check_explicit_binding(binding: Any, api: Any, issues: list[VerificationIssue]) -> None:
    api_parameters = {(item.location, item.name): item for item in api.parameters}
    api_parameters_by_name = {item.name: item for item in api.parameters}
    seen_sources: dict[str, tuple[SpecFieldType, bool]] = {}
    binding_parameters = {(item.location, item.name): item for item in binding.parameters}
    for parameter in binding.parameters:
        target = api_parameters.get((parameter.location, parameter.name))
        if target is None:
            if parameter.name in api_parameters_by_name:
                code = "BINDING_PARAMETER_LOCATION_MISMATCH"
                message = f"binding parameter location differs from API: {parameter.name}"
            else:
                code = "BINDING_PARAMETER_MISSING"
                message = f"binding parameter is not declared by API: {parameter.name}"
            issues.append(VerificationIssue(code=code, severity="HIGH", message=message, path=f"bindings.{binding.binding_id}.parameters.{parameter.name}"))
            continue
        if parameter.type.model_dump(mode="json") != target.type.model_dump(mode="json"):
            issues.append(VerificationIssue(code="BINDING_PARAMETER_TYPE_MISMATCH", severity="HIGH", message=f"binding parameter type differs from API: {parameter.name}", path=f"bindings.{binding.binding_id}.parameters.{parameter.name}"))
        if parameter.required != target.required:
            issues.append(VerificationIssue(code="BINDING_PARAMETER_REQUIRED_MISMATCH", severity="HIGH", message=f"binding parameter requiredness differs from API: {parameter.name}", path=f"bindings.{binding.binding_id}.parameters.{parameter.name}"))
        previous = seen_sources.get(parameter.source)
        current = (parameter.type, parameter.required)
        if previous is not None and (
            previous[0].model_dump(mode="json") != current[0].model_dump(mode="json")
            or previous[1] != current[1]
        ):
            issues.append(VerificationIssue(code="BINDING_SOURCE_CONFLICT", severity="HIGH", message=f"frontend source maps to incompatible parameter facts: {parameter.source}", path=f"bindings.{binding.binding_id}.parameters.{parameter.name}"))
        seen_sources[parameter.source] = current
    sources = sorted(seen_sources)
    for index, source in enumerate(sources):
        for other in sources[index + 1:]:
            if other.startswith(f"{source}.") or source.startswith(f"{other}."):
                issues.append(VerificationIssue(code="BINDING_SOURCE_SHAPE_CONFLICT", severity="HIGH", message=f"frontend sources overlap as both a value and an object: {source}, {other}", path=f"bindings.{binding.binding_id}.parameters"))
    for parameter in api.parameters:
        if parameter.required and (parameter.location, parameter.name) not in binding_parameters:
            issues.append(VerificationIssue(code="BINDING_PARAMETER_UNCONSUMED", severity="HIGH", message=f"required API parameter is not consumed by binding: {parameter.name}", path=f"bindings.{binding.binding_id}.parameters"))

    api_responses = {field.name: field for field in api.responses}
    for response in binding.response_fields:
        if "." in response.path:
            issues.append(VerificationIssue(code="BINDING_RESPONSE_PATH_UNSUPPORTED", severity="HIGH", message=f"nested response paths are not supported by the flat response contract: {response.path}", path=f"bindings.{binding.binding_id}.response_fields.{response.path}"))
            continue
        target = api_responses.get(response.path)
        if target is None:
            issues.append(VerificationIssue(code="BINDING_RESPONSE_FIELD_MISSING", severity="HIGH", message=f"binding consumes an undeclared response field: {response.path}", path=f"bindings.{binding.binding_id}.response_fields.{response.path}"))
            continue
        if response.type.model_dump(mode="json") != target.type.model_dump(mode="json"):
            issues.append(VerificationIssue(code="BINDING_RESPONSE_TYPE_MISMATCH", severity="HIGH", message=f"binding response type differs from API: {response.path}", path=f"bindings.{binding.binding_id}.response_fields.{response.path}"))
        if response.nullable != target.nullable:
            issues.append(VerificationIssue(code="BINDING_RESPONSE_NULLABILITY_MISMATCH", severity="HIGH", message=f"binding response nullability differs from API: {response.path}", path=f"bindings.{binding.binding_id}.response_fields.{response.path}"))


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


def _check_generated_documents(contract: SpecContract, files: dict[str, str], issues: list[VerificationIssue]) -> None:
    _check_openapi_document(contract, files.get("openapi.json", ""), issues)
    _check_sql_document(contract, files.get("schema.sql", ""), issues)


def _check_openapi_document(contract: SpecContract, content: str, issues: list[VerificationIssue]) -> None:
    try:
        document = json.loads(content)
    except json.JSONDecodeError as exc:
        issues.append(VerificationIssue(code="OPENAPI_PARSE_FAILED", severity="CRITICAL", message=f"generated OpenAPI is not valid JSON: {exc.msg}", path="openapi.json"))
        return
    if document.get("openapi") != "3.1.0":
        issues.append(VerificationIssue(code="OPENAPI_VERSION_INVALID", severity="HIGH", message="generated OpenAPI must declare version 3.1.0", path="openapi.json.openapi"))
    paths = document.get("paths")
    if not isinstance(paths, dict):
        issues.append(VerificationIssue(code="OPENAPI_PATHS_INVALID", severity="CRITICAL", message="generated OpenAPI paths must be an object", path="openapi.json.paths"))
        return
    for api in contract.apis:
        operation = paths.get(api.path, {}).get(api.method.lower()) if isinstance(paths.get(api.path), dict) else None
        if not isinstance(operation, dict):
            issues.append(VerificationIssue(code="OPENAPI_OPERATION_MISSING", severity="CRITICAL", message=f"generated OpenAPI operation is missing: {api.method} {api.path}", path=f"openapi.json.paths.{api.path}"))
            continue
        if operation.get("operationId") != api.api_id:
            issues.append(VerificationIssue(code="OPENAPI_OPERATION_ID_MISMATCH", severity="HIGH", message=f"generated OpenAPI operationId differs: {api.api_id}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.operationId"))
        parameters = {
            (item.get("in"), item.get("name")): item
            for item in operation.get("parameters", [])
            if isinstance(item, dict)
        }
        for parameter in api.parameters:
            if parameter.location == "body":
                continue
            generated = parameters.get((parameter.location, parameter.name))
            if not isinstance(generated, dict):
                issues.append(VerificationIssue(code="OPENAPI_PARAMETER_MISSING", severity="HIGH", message=f"generated OpenAPI parameter is missing: {parameter.name}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.parameters"))
                continue
            expected_required = parameter.required or parameter.location == "path"
            if generated.get("required") != expected_required:
                issues.append(VerificationIssue(code="OPENAPI_PARAMETER_REQUIRED_MISMATCH", severity="HIGH", message=f"generated OpenAPI parameter requiredness differs: {parameter.name}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.parameters.{parameter.name}.required"))
            if generated.get("schema") != _openapi_type(parameter.type):
                issues.append(VerificationIssue(code="OPENAPI_PARAMETER_SCHEMA_MISMATCH", severity="HIGH", message=f"generated OpenAPI parameter type differs: {parameter.name}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.parameters.{parameter.name}.schema"))
        body_parameters = sorted(
            (item for item in api.parameters if item.location == "body"),
            key=lambda item: (item.location, item.name),
        )
        if body_parameters:
            body = operation.get("requestBody")
            if not isinstance(body, dict):
                issues.append(VerificationIssue(code="OPENAPI_REQUEST_BODY_MISSING", severity="HIGH", message=f"generated OpenAPI request body is missing: {api.api_id}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.requestBody"))
            else:
                schema = body.get("content", {}).get("application/json", {}).get("schema", {})
                expected_schema = {
                    "type": "object",
                    "properties": {item.name: _openapi_type(item.type) for item in body_parameters},
                    "required": [item.name for item in body_parameters if item.required],
                }
                if schema != expected_schema:
                    issues.append(VerificationIssue(code="OPENAPI_REQUEST_BODY_SCHEMA_MISMATCH", severity="HIGH", message=f"generated OpenAPI request body differs: {api.api_id}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.requestBody"))
        status = str(getattr(api, "success_status", 200))
        response = operation.get("responses", {}).get(status)
        if not isinstance(response, dict):
            issues.append(VerificationIssue(code="OPENAPI_RESPONSE_STATUS_MISSING", severity="HIGH", message=f"generated OpenAPI success response is missing: {status}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.responses"))
            continue
        schema = response.get("content", {}).get("application/json", {}).get("schema", {})
        expected_properties = {field.name: _openapi_type(field.type) for field in api.responses}
        if schema.get("properties") != expected_properties:
            issues.append(VerificationIssue(code="OPENAPI_RESPONSE_SCHEMA_MISMATCH", severity="HIGH", message=f"generated OpenAPI response fields differ: {api.api_id}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.responses.{status}"))
        if schema.get("required", []) != [field.name for field in api.responses if not field.nullable]:
            issues.append(VerificationIssue(code="OPENAPI_RESPONSE_NULLABILITY_MISMATCH", severity="HIGH", message=f"generated OpenAPI response required fields differ: {api.api_id}", path=f"openapi.json.paths.{api.path}.{api.method.lower()}.responses.{status}"))


def _check_sql_document(contract: SpecContract, content: str, issues: list[VerificationIssue]) -> None:
    create_pattern = re.compile(r"CREATE TABLE `(?P<name>[A-Za-z][A-Za-z0-9_]*)` \(\n(?P<body>.*?)\n\) ENGINE=InnoDB;", re.S)
    create_matches = list(create_pattern.finditer(content))
    expected_tables = {table.name: table for table in contract.tables}
    generated_tables = {match.group("name"): match.group("body") for match in create_matches}
    if set(generated_tables) != set(expected_tables):
        issues.append(VerificationIssue(code="DDL_TABLE_SET_MISMATCH", severity="CRITICAL", message="generated DDL table set differs from the contract", path="schema.sql"))
    for table_name, table in expected_tables.items():
        body = generated_tables.get(table_name)
        if body is None:
            continue
        columns: dict[str, tuple[str, str]] = {}
        primary_key: list[str] = []
        for raw_line in body.splitlines():
            line = raw_line.strip().rstrip(",")
            if not line:
                continue
            primary_match = re.fullmatch(r"PRIMARY KEY \((?P<fields>[^)]*)\)", line)
            if primary_match:
                primary_key = re.findall(r"`([^`]+)`", primary_match.group("fields"))
                continue
            column_match = re.fullmatch(r"`(?P<name>[A-Za-z][A-Za-z0-9_]*)` (?P<type>[A-Z]+(?:\(\d+(?:,\d+)?\))?)(?P<constraints>.*)", line)
            if column_match is None:
                issues.append(VerificationIssue(code="DDL_PARSE_FAILED", severity="CRITICAL", message=f"unrecognised generated DDL statement in table {table_name}", path=f"schema.sql.tables.{table_name}"))
                continue
            columns[column_match.group("name")] = (column_match.group("type"), column_match.group("constraints"))
        expected_primary = [field.name for field in table.fields if field.primary_key]
        if primary_key != expected_primary:
            issues.append(VerificationIssue(code="DDL_PRIMARY_KEY_MISMATCH", severity="HIGH", message=f"generated primary key differs for table {table_name}", path=f"schema.sql.tables.{table_name}.primary_key"))
        expected_columns = {field.name for field in table.fields}
        if set(columns) != expected_columns:
            issues.append(VerificationIssue(code="DDL_COLUMN_SET_MISMATCH", severity="HIGH", message=f"generated columns differ for table {table_name}", path=f"schema.sql.tables.{table_name}.fields"))
        for field in table.fields:
            generated = columns.get(field.name)
            if generated is None:
                issues.append(VerificationIssue(code="DDL_COLUMN_MISSING", severity="HIGH", message=f"generated column is missing: {table_name}.{field.name}", path=f"schema.sql.tables.{table_name}.fields.{field.name}"))
                continue
            if generated[0] != _sql_type(field.type):
                issues.append(VerificationIssue(code="DDL_COLUMN_TYPE_MISMATCH", severity="HIGH", message=f"generated column type differs: {table_name}.{field.name}", path=f"schema.sql.tables.{table_name}.fields.{field.name}.type"))
            if ("NOT NULL" in generated[1]) != (not field.nullable):
                issues.append(VerificationIssue(code="DDL_COLUMN_NULLABILITY_MISMATCH", severity="HIGH", message=f"generated column nullability differs: {table_name}.{field.name}", path=f"schema.sql.tables.{table_name}.fields.{field.name}.nullable"))
            if (" UNIQUE" in generated[1]) != field.unique:
                issues.append(VerificationIssue(code="DDL_COLUMN_UNIQUE_MISMATCH", severity="HIGH", message=f"generated column uniqueness differs: {table_name}.{field.name}", path=f"schema.sql.tables.{table_name}.fields.{field.name}.unique"))
    fk_pattern = re.compile(r"ALTER TABLE `(?P<table>[A-Za-z][A-Za-z0-9_]*)` ADD CONSTRAINT `fk_[^`]+` FOREIGN KEY \(`(?P<field>[A-Za-z][A-Za-z0-9_]*)`\) REFERENCES `(?P<target_table>[A-Za-z][A-Za-z0-9_]*)` \(`(?P<target_field>[A-Za-z][A-Za-z0-9_]*)`\);")
    generated_foreign_keys = {
        (match.group("table"), match.group("field"), match.group("target_table"), match.group("target_field"))
        for match in fk_pattern.finditer(content)
    }
    expected_foreign_keys = {
        (table.name, field.name, field.foreign_key.table, field.foreign_key.field)
        for table in contract.tables
        for field in table.fields
        if field.foreign_key is not None
    }
    if generated_foreign_keys != expected_foreign_keys:
        issues.append(VerificationIssue(code="DDL_FOREIGN_KEY_MISMATCH", severity="HIGH", message="generated foreign keys differ from the contract", path="schema.sql.foreign_keys"))


def _check(category: str, issues: list[VerificationIssue]) -> VerificationCheck:
    category_codes = {
        "traceability": {"TRACE_UNKNOWN_REQUIREMENT", "TRACE_MUST_UNCOVERED"},
        "data_model": {"TABLE_PRIMARY_KEY_INVALID", "TABLE_FOREIGN_KEY_UNKNOWN", "TABLE_FOREIGN_KEY_TYPE_MISMATCH", "TABLE_FOREIGN_KEY_SHAPE_MISMATCH"},
        "api_contract": {"API_DUPLICATE_OPERATION", "API_AUTH_ROLE_MISSING", "API_PATH_PARAM_MISMATCH", "API_PATH_PARAM_REQUIRED", "API_RESPONSE_DUPLICATE_FIELD"},
        "frontend_bindings": {"BINDING_API_MISSING", "BINDING_API_DRIFT", "BINDING_PARAMETER_MISSING", "BINDING_PARAMETER_LOCATION_MISMATCH", "BINDING_PARAMETER_TYPE_MISMATCH", "BINDING_PARAMETER_REQUIRED_MISMATCH", "BINDING_PARAMETER_UNCONSUMED", "BINDING_SOURCE_CONFLICT", "BINDING_SOURCE_SHAPE_CONFLICT", "BINDING_RESPONSE_PATH_UNSUPPORTED", "BINDING_RESPONSE_FIELD_MISSING", "BINDING_RESPONSE_TYPE_MISMATCH", "BINDING_RESPONSE_NULLABILITY_MISMATCH"},
        "security": {"SECURITY_UNSAFE_PATH", "SECURITY_UNSAFE_IDENTIFIER"},
        "compiler_safety": {"COMPILER_UNSAFE_OUTPUT", "COMPILER_REJECTED_CONTRACT", "NON_DETERMINISTIC_COMPILATION"},
        "generated_artifacts": {"COMPILER_REJECTED_CONTRACT", "OPENAPI_PARSE_FAILED", "OPENAPI_VERSION_INVALID", "OPENAPI_PATHS_INVALID", "OPENAPI_OPERATION_MISSING", "OPENAPI_OPERATION_ID_MISMATCH", "OPENAPI_PARAMETER_MISSING", "OPENAPI_PARAMETER_REQUIRED_MISMATCH", "OPENAPI_PARAMETER_SCHEMA_MISMATCH", "OPENAPI_REQUEST_BODY_MISSING", "OPENAPI_REQUEST_BODY_SCHEMA_MISMATCH", "OPENAPI_RESPONSE_STATUS_MISSING", "OPENAPI_RESPONSE_SCHEMA_MISMATCH", "OPENAPI_RESPONSE_NULLABILITY_MISMATCH", "DDL_TABLE_SET_MISMATCH", "DDL_PARSE_FAILED", "DDL_PRIMARY_KEY_MISMATCH", "DDL_COLUMN_SET_MISMATCH", "DDL_COLUMN_MISSING", "DDL_COLUMN_TYPE_MISMATCH", "DDL_COLUMN_NULLABILITY_MISMATCH", "DDL_COLUMN_UNIQUE_MISMATCH", "DDL_FOREIGN_KEY_MISMATCH"},
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
    verifier_version: str = VERIFIER_VERSION,
    compiler_version: str = COMPILER_VERSION,
) -> VerificationReport:
    blocked = any(issue.severity in {"HIGH", "CRITICAL"} for issue in issues)
    return VerificationReport(
        execution_id=execution_id,
        contract_id=contract_id,
        scope=scope,
        verifier_version=verifier_version,
        compiler_version=compiler_version,
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
