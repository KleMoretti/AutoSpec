import sys

import pytest

from spec_verifier import compile_spec, validate_l1
from spec_verifier.fixtures import defect_spec_fixtures, normal_spec_fixtures
from spec_verifier.fixtures import spec_fixture
from schemas.verification import VerificationIssue
from spec_verifier.sandbox import _verify_mysql, compiled_report


class _FakeCursor:
    def __init__(self, connection: "_FakeConnection") -> None:
        self.connection = connection

    def __enter__(self) -> "_FakeCursor":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def execute(self, statement: str) -> None:
        self.connection.statements.append(statement)
        if self.connection.failure_marker and self.connection.failure_marker in statement:
            raise RuntimeError(f"forced database failure: {self.connection.failure_marker}")


class _FakeConnection:
    def __init__(self, statements: list[str], failure_marker: str | None) -> None:
        self.statements = statements
        self.failure_marker = failure_marker
        self.selected_databases: list[str] = []
        self.closed = False

    def cursor(self) -> _FakeCursor:
        return _FakeCursor(self)

    def select_db(self, database: str) -> None:
        self.selected_databases.append(database)

    def ping(self, reconnect: bool = False) -> None:
        return None

    def close(self) -> None:
        self.closed = True


class _FakePyMySql:
    def __init__(self, failure_marker: str | None = None) -> None:
        self.failure_marker = failure_marker
        self.statements: list[str] = []
        self.connections: list[_FakeConnection] = []

    def connect(self, **kwargs) -> _FakeConnection:
        connection = _FakeConnection(self.statements, self.failure_marker)
        self.connections.append(connection)
        return connection


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


@pytest.mark.parametrize("fixture_key", ["campus_marketplace", "employee_leave_approval"])
def test_mysql_verification_uses_a_fresh_schema_and_drops_it(monkeypatch, fixture_key: str) -> None:
    fake_mysql = _FakePyMySql()
    monkeypatch.setitem(sys.modules, "pymysql", fake_mysql)
    compiled = compile_spec(spec_fixture(fixture_key))

    assert _verify_mysql(compiled, "mysql://verify:secret@mysql:3306/autospec_verify", 5.0) == []
    assert _verify_mysql(compiled, "mysql://verify:secret@mysql:3306/autospec_verify", 5.0) == []

    create_statements = [statement for statement in fake_mysql.statements if statement.startswith("CREATE DATABASE")]
    drop_statements = [statement for statement in fake_mysql.statements if statement.startswith("DROP DATABASE")]
    assert len(create_statements) == len(drop_statements) == 2
    created_names = {statement.split("`")[1] for statement in create_statements}
    dropped_names = {statement.split("`")[1] for statement in drop_statements}
    assert created_names == dropped_names
    assert len(created_names) == 2
    assert all(name.startswith("autospec_verify_") for name in created_names)


def test_mysql_verification_reports_partial_schema_failure_and_still_cleans_up(monkeypatch) -> None:
    fake_mysql = _FakePyMySql("ALTER TABLE `favorite`")
    monkeypatch.setitem(sys.modules, "pymysql", fake_mysql)
    compiled = compile_spec(spec_fixture("campus_marketplace"))

    issues = _verify_mysql(compiled, "mysql://verify:secret@mysql:3306/autospec_verify", 5.0)

    assert "L2_DATABASE_SCHEMA_FAILED" in {issue.code for issue in issues}
    assert "L2_DATABASE_CLEANUP_FAILED" not in {issue.code for issue in issues}
    assert any(statement.startswith("DROP DATABASE") for statement in fake_mysql.statements)


def test_mysql_verification_does_not_swallow_cleanup_failure(monkeypatch) -> None:
    fake_mysql = _FakePyMySql("DROP DATABASE")
    monkeypatch.setitem(sys.modules, "pymysql", fake_mysql)
    compiled = compile_spec(spec_fixture("employee_leave_approval"))

    issues = _verify_mysql(compiled, "mysql://verify:secret@mysql:3306/autospec_verify", 5.0)

    assert "L2_DATABASE_CLEANUP_FAILED" in {issue.code for issue in issues}


def test_l2_spec_failures_are_failed_but_environment_failures_are_errors() -> None:
    compiled = compile_spec(spec_fixture("campus_marketplace"))
    spec_failure = VerificationIssue(
        code="L2_TYPESCRIPT_FAILED",
        severity="HIGH",
        message="generated consumer does not type-check",
    )
    environment_failure = VerificationIssue(
        code="L2_TYPESCRIPT_TIMEOUT",
        severity="HIGH",
        message="compiler deadline elapsed",
    )

    assert compiled_report(compiled, "spec-failure", [spec_failure], []).status == "FAILED"
    assert compiled_report(compiled, "environment-failure", [environment_failure], []).status == "ERROR"
