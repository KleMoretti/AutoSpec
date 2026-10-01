"""Explicit adapter from generated artifacts to the executable spec contract.

Legacy artifacts predate primary-key, foreign-key, parameter-location and
response-nullability fields. They continue through the compatibility adapter;
new verification candidates must use this module so no field-name inference is
silently treated as a contract fact.
"""
from __future__ import annotations

from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact
from schemas.prd import PrdArtifact
from schemas.spec_contract import (
    SpecApi, SpecContract, SpecField, SpecFieldType, SpecFrontendBinding,
    SpecParameter, SpecRequirement, SpecResponseField, SpecTable,
)
from spec_verifier.fixtures import _contract_id, _field_type


def explicit_spec_contract_from_artifacts(
    prd: PrdArtifact,
    backend: BackendDesignArtifact,
    frontend: FrontendSkeletonArtifact | None = None,
    *,
    contract_id: str = "GeneratedSpec",
) -> SpecContract:
    """Build a contract only from fields explicitly present in the artifacts."""

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
            _require(old_field, "primary_key", f"field {old_field.name} must declare primary_key")
            _require(old_field, "foreign_key", f"field {old_field.name} must declare foreign_key (null is explicit)")
            foreign_key = None
            if old_field.foreign_key is not None:
                if old_field.foreign_key.table not in table_names:
                    raise ValueError(f"field {old_field.name} references an undeclared table")
                foreign_key = old_field.foreign_key.model_dump(mode="json")
            fields.append(SpecField(
                field_id=old_field.field_id,
                name=old_field.name,
                type=_field_type(old_field.type),
                nullable=old_field.nullable,
                primary_key=bool(old_field.primary_key),
                unique=bool(old_field.unique) if "unique" in old_field.model_fields_set else False,
                default=old_field.default,
                foreign_key=foreign_key,
                requirement_refs=list(old_field.requirement_refs),
            ))
        tables.append(SpecTable(
            table_id=table.table_id, name=table.name, fields=fields,
            requirement_refs=list(table.requirement_refs),
        ))

    api_ids = {api.api_id for api in backend.apis}
    apis = []
    for old_api in backend.apis:
        params = []
        for old_param in old_api.request_params:
            _require(old_param, "location", f"parameter {old_param.name} must declare location")
            params.append(SpecParameter(
                name=old_param.name, location=old_param.location, type=_field_type(old_param.type),
                required=old_param.required, requirement_refs=list(old_param.requirement_refs),
            ))
        responses = []
        for old_response in old_api.response_fields:
            _require(old_response, "nullable", f"response {old_response.name} must declare nullable")
            responses.append(SpecResponseField(
                name=old_response.name, type=_field_type(old_response.type),
                nullable=bool(old_response.nullable), requirement_refs=list(old_response.requirement_refs),
            ))
        apis.append(SpecApi(
            api_id=old_api.api_id, method=old_api.method, path=old_api.path,
            parameters=params, responses=responses, auth_required=old_api.auth_required,
            roles=list(old_api.required_roles), requirement_refs=list(old_api.requirement_refs),
        ))

    bindings = []
    if frontend is not None:
        for binding in frontend.api_bindings:
            if binding.backend_api_id is None:
                raise ValueError(f"binding {binding.binding_id} must declare backend_api_id")
            if binding.backend_api_id not in api_ids:
                raise ValueError(f"binding {binding.binding_id} references an undeclared API")
            bindings.append(SpecFrontendBinding(
                binding_id=binding.binding_id, component=binding.consumer,
                api_id=binding.backend_api_id, method=binding.method, path=binding.path,
                requirement_refs=list(binding.requirement_refs),
            ))
    return SpecContract(
        contract_id=contract_id, requirements=requirements, tables=tables,
        apis=apis, frontend_bindings=bindings,
    )


def _require(value: object, field: str, message: str) -> None:
    if field not in getattr(value, "model_fields_set", set()):
        raise ValueError(message)
