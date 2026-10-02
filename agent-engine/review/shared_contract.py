import re

from schemas.architecture_design import ArchitectureDesignArtifactV2
from schemas.backend_design import BackendDesignArtifact, ExplicitBackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact


def validate_backend_contract(architecture: ArchitectureDesignArtifactV2, backend: BackendDesignArtifact) -> None:
    expected = {api.api_id: api for api in architecture.shared_contract.api_signatures}
    actual = {api.api_id: api for api in backend.apis}
    if set(actual) != set(expected):
        raise ValueError("Backend API ids diverge from the frozen Shared Contract")
    for api_id, spec in expected.items():
        implementation = actual[api_id]
        if _api_contract_shape(implementation) != _api_contract_shape(spec):
            raise ValueError(f"Backend API {api_id} diverges from the frozen Shared Contract")
    tables = {table.name: table for table in backend.tables}
    for model in architecture.shared_contract.domain_models:
        table = _find_table(tables, model.name)
        if table is None:
            raise ValueError(f"Backend domain model {model.name} diverges from the frozen Shared Contract")
        fields = {field.name: field for field in table.fields}
        for field in model.fields:
            actual_field = fields.get(field.name)
            if actual_field is None or (actual_field.type, actual_field.nullable) != (field.type, field.nullable):
                 raise ValueError(f"Backend domain field {model.name}.{field.name} diverges from the frozen Shared Contract")


def validate_explicit_backend_contract(
    architecture: ArchitectureDesignArtifactV2,
    backend: ExplicitBackendDesignArtifact,
) -> None:
    """Validate explicit backend facts against the legacy shared-contract wire shape."""

    expected = {api.api_id: api for api in architecture.shared_contract.api_signatures}
    actual = {api.api_id: api for api in backend.apis}
    if set(actual) != set(expected):
        raise ValueError("Backend API ids diverge from the frozen Shared Contract")
    for api_id, spec in expected.items():
        implementation = actual[api_id]
        if (implementation.method, implementation.path) != (spec.method, spec.path):
            raise ValueError(f"Backend API {api_id} diverges from the frozen Shared Contract")
        if implementation.auth_required != spec.auth_required:
            raise ValueError(f"Backend API {api_id} authentication diverges from the frozen Shared Contract")
        if set(implementation.required_roles) != set(spec.required_roles):
            raise ValueError(f"Backend API {api_id} roles diverge from the frozen Shared Contract")
        if [
            (parameter.name, parameter.required)
            for parameter in implementation.request_params
        ] != [
            (parameter.name, parameter.required)
            for parameter in spec.request_params
        ]:
            raise ValueError(f"Backend API {api_id} parameters diverge from the frozen Shared Contract")
        if [field.name for field in implementation.response_fields] != [
            field.name for field in spec.response_fields
        ]:
            raise ValueError(f"Backend API {api_id} responses diverge from the frozen Shared Contract")

    tables = {table.name: table for table in backend.tables}
    for model in architecture.shared_contract.domain_models:
        table = _find_table(tables, model.name)
        if table is None:
            raise ValueError(f"Backend domain model {model.name} diverges from the frozen Shared Contract")
        fields = {field.name: field for field in table.fields}
        for field in model.fields:
            actual_field = fields.get(field.name)
            if actual_field is None or actual_field.nullable != field.nullable:
                raise ValueError(f"Backend domain field {model.name}.{field.name} diverges from the frozen Shared Contract")
            if not _explicit_type_matches(actual_field.type, field.type):
                raise ValueError(f"Backend domain field {model.name}.{field.name} type diverges from the frozen Shared Contract")


def _api_contract_shape(api: object) -> tuple[object, ...]:
    return (
        api.api_id,
        api.method,
        api.path,
        tuple((param.name, param.type, param.required) for param in api.request_params),
        tuple((field.name, field.type) for field in api.response_fields),
        api.auth_required,
        tuple(sorted(api.required_roles)),
        tuple(sorted(api.requirement_refs)),
    )


def _explicit_type_matches(actual: object, expected: str) -> bool:
    kind = getattr(actual, "kind", None)
    if kind is None:
        return actual == expected
    normalized = expected.upper()
    if normalized.startswith("VARCHAR") or normalized == "STRING":
        return kind == "string"
    if normalized in {"BIGINT", "LONG"}:
        return kind == "bigint"
    if normalized in {"INT", "INTEGER"}:
        return kind == "integer"
    if normalized in {"NUMBER", "DECIMAL"}:
        return kind in {"integer", "bigint", "decimal"}
    if normalized in {"BOOLEAN", "BOOL"}:
        return kind == "boolean"
    if normalized == "DATE":
        return kind == "date"
    if normalized in {"DATETIME", "TIMESTAMP"}:
        return kind == "datetime"
    if normalized == "JSON" or normalized.endswith("[]"):
        return kind == "json"
    return False


def _find_table(tables: dict[str, object], model_name: str) -> object | None:
    normalized_tables = {
        _normalize_identifier(name): table for name, table in tables.items()
    }
    for candidate in _identifier_variants(model_name):
        table = normalized_tables.get(candidate)
        if table is not None:
            return table
    return None


def _identifier_variants(value: str) -> list[str]:
    normalized = _normalize_identifier(value)
    variants = [normalized]
    if normalized.endswith("ies"):
        variants.append(normalized[:-3] + "y")
    if normalized.endswith("s") and not normalized.endswith("ss"):
        variants.append(normalized[:-1])
    else:
        variants.append(normalized + "s")
    return list(dict.fromkeys(variants))


def _normalize_identifier(value: str) -> str:
    snake_case = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", value)
    return re.sub(r"[^a-zA-Z0-9]+", "_", snake_case).strip("_").casefold()


def validate_frontend_contract(architecture: ArchitectureDesignArtifactV2, frontend: FrontendSkeletonArtifact) -> None:
    expected = {api.api_id: api for api in architecture.shared_contract.api_signatures}
    for binding in frontend.api_bindings:
        spec = expected.get(binding.backend_api_id)
        if spec is None or (binding.method, binding.path) != (spec.method, spec.path):
            raise ValueError(f"Frontend binding {binding.binding_id} diverges from the frozen Shared Contract")
