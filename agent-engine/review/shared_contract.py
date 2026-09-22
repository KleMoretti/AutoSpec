from schemas.architecture_design import ArchitectureDesignArtifactV2
from schemas.backend_design import BackendDesignArtifact
from schemas.frontend_skeleton import FrontendSkeletonArtifact


def validate_backend_contract(architecture: ArchitectureDesignArtifactV2, backend: BackendDesignArtifact) -> None:
    expected = {api.api_id: api for api in architecture.shared_contract.api_signatures}
    actual = {api.api_id: api for api in backend.apis}
    if set(actual) != set(expected):
        raise ValueError("Backend API ids diverge from the frozen Shared Contract")
    for api_id, spec in expected.items():
        implementation = actual[api_id]
        if implementation.model_dump(mode="json") != spec.model_dump(mode="json"):
            raise ValueError(f"Backend API {api_id} diverges from the frozen Shared Contract")
    tables = {table.name: table for table in backend.tables}
    for model in architecture.shared_contract.domain_models:
        table = tables.get(model.name)
        if table is None:
            raise ValueError(f"Backend domain model {model.name} diverges from the frozen Shared Contract")
        fields = {field.name: field for field in table.fields}
        for field in model.fields:
            actual_field = fields.get(field.name)
            if actual_field is None or (actual_field.type, actual_field.nullable) != (field.type, field.nullable):
                raise ValueError(f"Backend domain field {model.name}.{field.name} diverges from the frozen Shared Contract")


def validate_frontend_contract(architecture: ArchitectureDesignArtifactV2, frontend: FrontendSkeletonArtifact) -> None:
    expected = {api.api_id: api for api in architecture.shared_contract.api_signatures}
    for binding in frontend.api_bindings:
        spec = expected.get(binding.backend_api_id)
        if spec is None or (binding.method, binding.path) != (spec.method, spec.path):
            raise ValueError(f"Frontend binding {binding.binding_id} diverges from the frozen Shared Contract")
