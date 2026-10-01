from spec_verifier import compile_spec, validate_l1
from spec_verifier.fixtures import defect_spec_fixtures, normal_spec_fixtures


def test_all_documented_domain_fixtures_pass_l1_and_compile_deterministically() -> None:
    for key, contract in normal_spec_fixtures().items():
        report = validate_l1(contract, execution_id=f"fixture-{key}")
        compiled = compile_spec(contract)
        assert report.status == "PASSED"
        assert report.gate_status == "PASSED"
        assert report.source_digest == compiled.source_digest
        assert compile_spec(contract).manifest == compiled.manifest
        assert set(compiled.files) == {"openapi.json", "schema.sql", "client.ts", "bindings.ts", "tsconfig.json", "manifest.json"}


def test_l1_reports_real_cross_artifact_defects_with_stable_codes() -> None:
    expected = {
        "missing_primary_key": "TABLE_PRIMARY_KEY_INVALID",
        "missing_permission": "API_AUTH_ROLE_MISSING",
        "binding_drift": "BINDING_API_DRIFT",
        "unknown_requirement": "TRACE_UNKNOWN_REQUIREMENT",
        "unknown_foreign_key": "TABLE_FOREIGN_KEY_UNKNOWN",
        "foreign_key_type_mismatch": "TABLE_FOREIGN_KEY_TYPE_MISMATCH",
        "duplicate_api": "API_DUPLICATE_OPERATION",
        "binding_parameter_missing": "BINDING_PARAMETER_MISSING",
        "must_uncovered": "TRACE_MUST_UNCOVERED",
    }
    for key, code in expected.items():
        report = validate_l1(defect_spec_fixtures()[key])
        assert report.status == "FAILED"
        assert code in {issue.code for issue in report.issues}
