import re

import pytest

from fixtures.software_domains import (
    explicit_backend_for_fixture,
    explicit_frontend_for_fixture,
    get_fixture,
)
from runtime.handler_registry import schema_fingerprint
from runtime.production_handlers import _validate_artifact_context
from review.rules import run_current_rule_checks
from schemas.backend_design import (
    ExplicitBackendDesignArtifact,
    ExplicitForeignKey,
)
from schemas.frontend_skeleton import ExplicitFrontendSkeletonArtifact
from schemas.spec_contract import ExplicitType, SpecContractV2, normalise_spec_contract
from spec_verifier.artifact_adapter import explicit_spec_contract_from_artifacts
from spec_verifier.compiler import compile_spec
from spec_verifier.sandbox import compiled_report
from spec_verifier.validators import validate_l1


def _explicit_type(value: str) -> dict[str, object]:
    upper = value.upper()
    if upper.startswith("VARCHAR"):
        return {"kind": "string", "length": int(re.search(r"\((\d+)\)", upper).group(1))}
    if upper in {"BIGINT", "LONG", "NUMBER"}:
        return {"kind": "bigint"}
    if upper in {"INT", "INTEGER"}:
        return {"kind": "integer"}
    if upper in {"BOOLEAN", "BOOL"}:
        return {"kind": "boolean"}
    if upper == "DATE":
        return {"kind": "date"}
    if upper.endswith("[]"):
        return {"kind": "json"}
    return {"kind": "string", "length": 255}


def _explicit_fixture() -> tuple[object, ExplicitBackendDesignArtifact, ExplicitFrontendSkeletonArtifact]:
    fixture = get_fixture("campus_marketplace")
    backend_payload = fixture.backend.model_dump(mode="json")
    foreign_keys = {
        ("product", "seller_id"): {"table": "user_account", "field": "id"},
        ("favorite", "product_id"): {"table": "product", "field": "id"},
    }
    for table in backend_payload["tables"]:
        for field in table["fields"]:
            field["type"] = _explicit_type(field["type"])
            field["primary_key"] = field["name"] == "id"
            field["unique"] = False
            field["foreign_key"] = foreign_keys.get((table["name"], field["name"]))
    for api in backend_payload["apis"]:
        api["success_status"] = 200
        path_params = set(re.findall(r"\{([A-Za-z][A-Za-z0-9_]*)\}", api["path"]))
        for parameter in api["request_params"]:
            parameter["type"] = _explicit_type(parameter["type"])
            parameter["location"] = (
                "path"
                if parameter["name"] in path_params
                else "query"
                if api["method"] == "GET"
                else "body"
            )
        for response in api["response_fields"]:
            response["type"] = _explicit_type(response["type"])
            response["nullable"] = False
    backend = ExplicitBackendDesignArtifact.model_validate(backend_payload)

    frontend_payload = fixture.frontend.model_dump(mode="json")
    api_by_id = {api["api_id"]: api for api in backend_payload["apis"]}
    for binding in frontend_payload["api_bindings"]:
        api = api_by_id[binding["backend_api_id"]]
        binding["parameters"] = [
            {
                "name": parameter["name"],
                "location": parameter["location"],
                "source": (
                    f"route.{parameter['name']}"
                    if parameter["location"] == "path"
                    else f"state.{parameter['name']}"
                ),
                "type": parameter["type"],
                "required": parameter["required"],
            }
            for parameter in api["request_params"]
        ]
        binding["response_fields"] = [
            {
                "path": response["name"],
                "type": response["type"],
                "nullable": response["nullable"],
            }
            for response in api["response_fields"]
        ]
    frontend = ExplicitFrontendSkeletonArtifact.model_validate(frontend_payload)
    return fixture.prd, backend, frontend


def test_explicit_adapter_preserves_nonconventional_pk_fk_and_mappings() -> None:
    prd, backend, frontend = _explicit_fixture()
    backend.tables[1].fields[1].name = "owner_ref"
    backend.tables[1].fields[1].foreign_key = ExplicitForeignKey(
        table="user_account", field="id"
    )

    contract = explicit_spec_contract_from_artifacts(
        prd, backend, frontend, contract_id="ExplicitCampus"
    )

    assert isinstance(contract, SpecContractV2)
    product = next(table for table in contract.tables if table.name == "product")
    assert [field.name for field in product.fields if field.primary_key] == ["id"]
    owner_ref = next(field for field in product.fields if field.name == "owner_ref")
    assert owner_ref.foreign_key is not None
    assert owner_ref.foreign_key.table == "user_account"
    assert contract.apis[0].success_status == 200
    assert contract.frontend_bindings[0].parameters
    assert contract.frontend_bindings[0].response_fields

    round_tripped = normalise_spec_contract(contract.model_dump(mode="json"))
    assert round_tripped.model_dump(mode="json") == contract.model_dump(mode="json")
    report = validate_l1(contract)
    assert report.status == "PASSED"
    assert compile_spec(contract).files["openapi.json"]


def test_explicit_artifacts_use_explicit_shared_contract_validation() -> None:
    fixture = get_fixture("campus_marketplace")
    backend = explicit_backend_for_fixture(fixture)
    frontend = explicit_frontend_for_fixture(fixture, backend)
    issues = run_current_rule_checks(
        fixture.prd,
        fixture.shared_architecture(),
        backend,
        frontend,
        rule_profile="spec-full-v1",
    )

    assert "SHARED_CONTRACT_BACKEND_DRIFT" not in {issue.issue_type for issue in issues}

    _validate_artifact_context({
        "prd": fixture.prd.model_dump(mode="json"),
        "architecture_design": fixture.shared_architecture().model_dump(mode="json"),
        "backend_design": backend.model_dump(mode="json"),
        "frontend_skeleton": frontend.model_dump(mode="json"),
        "verification_policy": {
            "verifier_version": "spec-verifier-v2",
            "compiler_version": "spec-compiler-v2",
        },
    })


def test_explicit_adapter_rejects_legacy_artifacts_instead_of_inferring_facts() -> None:
    fixture = get_fixture("campus_marketplace")

    with pytest.raises(ValueError, match="primary_key"):
        explicit_spec_contract_from_artifacts(
            fixture.prd, fixture.backend, fixture.frontend
        )


def test_explicit_models_do_not_change_legacy_schema_fingerprints() -> None:
    from schemas.backend_design import BackendDesignArtifact
    from schemas.frontend_skeleton import FrontendSkeletonArtifact

    assert (
        schema_fingerprint(BackendDesignArtifact)
        == "2678cf7396c34995cf38731ca5da9626bcde8d438378efabdf1c21a736e290b0"
    )
    assert (
        schema_fingerprint(FrontendSkeletonArtifact)
        == "2490b7c0f0e5f8dfaaf62c4a2a8807f2f05d85ae75fb670fa0cbfbe9789e5dbd"
    )


@pytest.mark.parametrize(
    "fixture_key",
    ["campus_marketplace", "inventory_management", "employee_leave_approval"],
)
def test_explicit_fixture_contracts_pass_l1_without_inference(fixture_key: str) -> None:
    fixture = get_fixture(fixture_key)
    backend = explicit_backend_for_fixture(fixture)
    frontend = explicit_frontend_for_fixture(fixture, backend)
    contract = explicit_spec_contract_from_artifacts(fixture.prd, backend, frontend)

    report = validate_l1(contract)
    assert report.status == "PASSED"
    assert report.verifier_version == "spec-verifier-v2"
    assert report.compiler_version == "spec-compiler-v2"


def test_explicit_compiler_generates_typed_request_and_independent_consumer() -> None:
    prd, backend, frontend = _explicit_fixture()
    contract = explicit_spec_contract_from_artifacts(prd, backend, frontend)

    first = compile_spec(contract)
    second = compile_spec(contract)

    assert first.files == second.files
    assert first.manifest["compiler_version"] == "spec-compiler-v2"
    l2_report = compiled_report(first, "explicit-l2", [], [])
    assert l2_report.verifier_version == "spec-verifier-v2"
    assert l2_report.compiler_version == "spec-compiler-v2"
    client = first.files["client.ts"]
    bindings = first.files["bindings.ts"]
    assert "encodeURIComponent(String(args.productId))" in client
    assert "URLSearchParams" in client
    assert "JSON.stringify(body)" in client
    assert "from './client'" in bindings
    assert "type Json" in bindings
    assert "consumeBIND_AUDIT" in bindings
    assert "source.route.productId" in bindings
    assert "response.auditStatus" in bindings


@pytest.mark.parametrize(
    ("mutation", "expected_code"),
    [
        ("parameter_location", "BINDING_PARAMETER_LOCATION_MISMATCH"),
        ("parameter_type", "BINDING_PARAMETER_TYPE_MISMATCH"),
        ("parameter_required", "BINDING_PARAMETER_REQUIRED_MISMATCH"),
        ("response_type", "BINDING_RESPONSE_TYPE_MISMATCH"),
        ("response_nullable", "BINDING_RESPONSE_NULLABILITY_MISMATCH"),
        ("response_path", "BINDING_RESPONSE_FIELD_MISSING"),
    ],
)
def test_explicit_binding_single_edge_mutations_have_stable_failures(
    mutation: str,
    expected_code: str,
) -> None:
    prd, backend, frontend = _explicit_fixture()
    contract = explicit_spec_contract_from_artifacts(prd, backend, frontend)
    binding = contract.frontend_bindings[0]
    if mutation == "parameter_location":
        binding.parameters[0].location = "query"
    elif mutation == "parameter_type":
        binding.parameters[0].type = ExplicitType.model_validate(_explicit_type("VARCHAR(64)"))
    elif mutation == "parameter_required":
        binding.parameters[0].required = not binding.parameters[0].required
    elif mutation == "response_type":
        binding.response_fields[0].type = ExplicitType.model_validate(_explicit_type("VARCHAR(64)"))
    elif mutation == "response_nullable":
        binding.response_fields[0].nullable = not binding.response_fields[0].nullable
    else:
        binding.response_fields[0].path = "missingField"

    report = validate_l1(contract)
    assert report.status == "FAILED"
    assert expected_code in {issue.code for issue in report.issues}
