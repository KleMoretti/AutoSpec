from __future__ import annotations

import re
from typing import Any

from fixtures.software_domains import SUPPORTED_DOMAIN_KEYS, get_fixture
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.spec_contract import (
    SpecApi,
    SpecContract,
    SpecField,
    SpecFieldType,
    SpecFrontendBinding,
    SpecBindingParameter,
    SpecParameter,
    SpecRequirement,
    SpecResponseField,
    SpecTable,
    SpecForeignKey,
)


def spec_fixture(key: str) -> SpecContract:
    fixture = get_fixture(key)
    return spec_contract_from_artifacts(
        fixture.prd,
        fixture.backend,
        fixture.frontend,
        contract_id=_contract_id(key),
    )


def spec_contract_from_artifacts(
    prd: PrdArtifact,
    backend: BackendDesignArtifact,
    frontend: FrontendSkeletonArtifact | None = None,
    *,
    contract_id: str = "GeneratedSpec",
) -> SpecContract:
    requirements = [
        SpecRequirement(
            requirement_id=feature.requirement_id,
            name=feature.name,
            priority=feature.priority,
            acceptance_criteria=[
                criterion.criterion
                for story in prd.user_stories
                for criterion in story.acceptance_criteria
                if feature.requirement_id in criterion.requirement_refs
            ],
        )
        for feature in prd.core_features
    ]
    table_names = {table.name for table in backend.tables}
    tables = []
    for table in backend.tables:
        fields = []
        for old_field in table.fields:
            field_type = _field_type(old_field.type)
            foreign_key = _infer_foreign_key(old_field.name, table.name, table_names)
            fields.append(
                SpecField(
                    field_id=old_field.field_id,
                    name=old_field.name,
                    type=field_type,
                    nullable=old_field.nullable,
                    primary_key=old_field.name == "id",
                    foreign_key=foreign_key,
                    requirement_refs=list(old_field.requirement_refs),
                )
            )
        tables.append(SpecTable(
            table_id=table.table_id,
            name=table.name,
            fields=fields,
            requirement_refs=list(table.requirement_refs),
        ))

    apis = []
    for old_api in backend.apis:
        path_names = set(re.findall(r"\{([A-Za-z][A-Za-z0-9_]*)\}", old_api.path))
        params = []
        for old_param in old_api.request_params:
            location = "path" if old_param.name in path_names else "query" if old_api.method == "GET" else "body"
            params.append(SpecParameter(
                name=old_param.name,
                location=location,
                type=_field_type(old_param.type),
                required=old_param.required,
            ))
        apis.append(SpecApi(
            api_id=old_api.api_id,
            method=old_api.method,
            path=old_api.path,
            parameters=params,
            responses=[SpecResponseField(name=field.name, type=_field_type(field.type)) for field in old_api.response_fields],
            auth_required=old_api.auth_required,
            roles=list(old_api.required_roles),
            requirement_refs=list(old_api.requirement_refs),
        ))

    bindings = []
    if frontend is not None:
        bindings = [
            SpecFrontendBinding(
                binding_id=binding.binding_id,
                component=binding.consumer,
                api_id=binding.backend_api_id,
                method=binding.method,
                path=binding.path,
                requirement_refs=list(binding.requirement_refs),
            )
            for binding in frontend.api_bindings
        ]
    return SpecContract(
        contract_id=contract_id,
        requirements=requirements,
        tables=tables,
        apis=apis,
        frontend_bindings=bindings,
    )


def normal_spec_fixtures() -> dict[str, SpecContract]:
    return {key: spec_fixture(key) for key in SUPPORTED_DOMAIN_KEYS}


def defect_spec_fixtures() -> dict[str, SpecContract]:
    normal = spec_fixture("campus_marketplace")
    defects: dict[str, SpecContract] = {}

    missing_pk = normal.model_copy(deep=True)
    missing_pk.tables[0].fields[0].primary_key = False
    defects["missing_primary_key"] = missing_pk

    missing_role = normal.model_copy(deep=True)
    missing_role.apis[0].roles = []
    defects["missing_permission"] = missing_role

    binding_drift = normal.model_copy(deep=True)
    binding_drift.frontend_bindings[0].path = "/api/not-the-api"
    defects["binding_drift"] = binding_drift

    unknown_ref = normal.model_copy(deep=True)
    unknown_ref.apis[0].requirement_refs.append("REQ-UNKNOWN")
    defects["unknown_requirement"] = unknown_ref

    unknown_fk = normal.model_copy(deep=True)
    source_field = next(
        field
        for table in unknown_fk.tables
        for field in table.fields
        if not field.primary_key
    )
    source_field.foreign_key = SpecForeignKey(table="missing_table", field="id")
    defects["unknown_foreign_key"] = unknown_fk

    mismatched_fk = normal.model_copy(deep=True)
    source_table = next(
        table
        for table in mismatched_fk.tables
        if any(field.type.kind == "string" for field in table.fields)
    )
    source_field = next(field for field in source_table.fields if field.type.kind == "string")
    source_field.foreign_key = SpecForeignKey(table=source_table.name, field="id")
    defects["foreign_key_type_mismatch"] = mismatched_fk

    duplicate_api = normal.model_copy(deep=True)
    duplicate_api.apis.append(
        duplicate_api.apis[0].model_copy(update={"api_id": "API-DUPLICATE"})
    )
    defects["duplicate_api"] = duplicate_api

    binding_parameter = normal.model_copy(deep=True)
    binding_parameter.frontend_bindings[0].parameters.append(
        SpecBindingParameter(name="undeclared_param", source="form.undeclared")
    )
    defects["binding_parameter_missing"] = binding_parameter

    must_uncovered = normal.model_copy(deep=True)
    uncovered_id = must_uncovered.requirements[0].requirement_id
    for table in must_uncovered.tables:
        table.requirement_refs = [ref for ref in table.requirement_refs if ref != uncovered_id]
        for field in table.fields:
            field.requirement_refs = [ref for ref in field.requirement_refs if ref != uncovered_id]
    for api in must_uncovered.apis:
        api.requirement_refs = [ref for ref in api.requirement_refs if ref != uncovered_id]
        for parameter in api.parameters:
            parameter.requirement_refs = [ref for ref in parameter.requirement_refs if ref != uncovered_id]
        for response in api.responses:
            response.requirement_refs = [ref for ref in response.requirement_refs if ref != uncovered_id]
    for binding in must_uncovered.frontend_bindings:
        binding.requirement_refs = [ref for ref in binding.requirement_refs if ref != uncovered_id]
    defects["must_uncovered"] = must_uncovered
    return defects


def _field_type(value: str) -> SpecFieldType:
    upper = value.upper()
    if upper.startswith("VARCHAR"):
        match = re.search(r"\((\d+)\)", upper)
        return SpecFieldType(kind="string", length=int(match.group(1)) if match else 255)
    if upper.startswith("DECIMAL"):
        match = re.search(r"\((\d+)\s*,\s*(\d+)\)", upper)
        return SpecFieldType(kind="decimal", precision=int(match.group(1)) if match else 18, scale=int(match.group(2)) if match else 2)
    if upper in {"BIGINT", "LONG"}:
        return SpecFieldType(kind="bigint")
    if upper in {"INT", "INTEGER"}:
        return SpecFieldType(kind="integer")
    if upper in {"BOOLEAN", "BOOL"}:
        return SpecFieldType(kind="boolean")
    if upper in {"DATE"}:
        return SpecFieldType(kind="date")
    if upper in {"DATETIME", "TIMESTAMP"}:
        return SpecFieldType(kind="datetime")
    if upper == "JSON":
        return SpecFieldType(kind="json")
    if upper.endswith("[]"):
        return SpecFieldType(kind="json")
    return SpecFieldType(kind="string", length=255)


def _infer_foreign_key(name: str, table_name: str, table_names: set[str]) -> SpecForeignKey | None:
    if not name.endswith("_id") or name == "id":
        return None
    stem = name[:-3]
    candidates = [stem, f"{stem}_account", f"{stem}_user", f"{stem}_item"]
    for candidate in candidates:
        if candidate in table_names and candidate != table_name:
            return SpecForeignKey(table=candidate, field="id")
    return None


def _contract_id(key: str) -> str:
    return {
        "campus_marketplace": "CampusMarketplace",
        "inventory_management": "InventoryManagement",
        "employee_leave_approval": "EmployeeLeaveApproval",
    }[key]
