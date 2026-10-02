from __future__ import annotations

import os
import re
import secrets
import subprocess
import tempfile
from urllib.parse import unquote, urlparse
from pathlib import Path

from schemas.verification import VerificationCheck, VerificationIssue, VerificationReport
from spec_verifier.compiler import COMPILER_VERSION_V2, CompiledSpec
from spec_verifier.validators import VERIFIER_VERSION, VERIFIER_VERSION_V2


_VERIFY_SCHEMA_PREFIX = "autospec_verify_"
_VERIFY_SCHEMA_PATTERN = re.compile(r"^autospec_verify_[0-9a-f]{24}$")


def run_l2(
    compiled: CompiledSpec,
    *,
    execution_id: str,
    timeout_ms: int = 30_000,
    scope: str = "FULL",
) -> VerificationReport:
    """Run the isolated compile checks used by the optional L2 sidecar.

    The verifier never invokes a shell and never interpolates contract values
    into a command.  Missing dependencies fail closed as an ERROR report.
    """

    issues: list[VerificationIssue] = []
    checks: list[VerificationCheck] = []
    timeout_seconds = max(1.0, timeout_ms / 1000)
    with tempfile.TemporaryDirectory(prefix="autospec-verify-") as temp_dir:
        root = Path(temp_dir)
        for name, content in compiled.files.items():
            (root / name).write_text(content, encoding="utf-8", newline="\n")
        tsc = os.environ.get("AUTOSPEC_TSC_PATH", "tsc")
        try:
            result = subprocess.run(
                [tsc, "--noEmit", "--project", str(root / "tsconfig.json")],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
                shell=False,
            )
        except FileNotFoundError:
            issues.append(VerificationIssue(code="L2_TYPESCRIPT_UNAVAILABLE", severity="HIGH", message="TypeScript compiler is not available in the verifier sandbox."))
        except subprocess.TimeoutExpired:
            issues.append(VerificationIssue(code="L2_TYPESCRIPT_TIMEOUT", severity="HIGH", message="TypeScript verification exceeded its deadline."))
        else:
            if result.returncode != 0:
                issues.append(VerificationIssue(code="L2_TYPESCRIPT_FAILED", severity="HIGH", message=(result.stderr or result.stdout or "tsc failed")[:1000]))
        checks.append(VerificationCheck(category="typescript_compile", status="PASSED" if not issues else "ERROR", issue_codes=[issue.code for issue in issues]))

        # Database execution is intentionally opt-in inside the sidecar.  A
        # configured MySQL verifier can replace this check; a missing one must
        # never be reported as a successful L2 run.
        dsn = os.environ.get("AUTOSPEC_VERIFY_MYSQL_DSN")
        if not dsn:
            issues.append(VerificationIssue(code="L2_DATABASE_UNAVAILABLE", severity="HIGH", message="No isolated verification MySQL DSN is configured."))
            checks.append(VerificationCheck(category="mysql_schema", status="ERROR", issue_codes=["L2_DATABASE_UNAVAILABLE"]))
        else:
            database_issues = _verify_mysql(compiled, dsn, timeout_seconds)
            issues.extend(database_issues)
            checks.append(VerificationCheck(
                category="mysql_schema",
                status="PASSED" if not database_issues else "ERROR",
                issue_codes=[issue.code for issue in database_issues],
            ))

    return compiled_report(compiled, execution_id, issues, checks, scope=scope)


def _verify_mysql(
    compiled: CompiledSpec,
    dsn: str,
    timeout_seconds: float,
) -> list[VerificationIssue]:
    """Apply the generated DDL in a per-execution database namespace.

    The DSN database is a bootstrap database only.  The verifier account must
    be able to create and drop databases matching ``autospec_verify_*``.  A
    random namespace prevents two executions from sharing tables, and dropping
    the whole namespace makes foreign-key cleanup independent of table order.
    Cleanup is part of the result: a failed DROP is never silently converted
    into a successful verification.
    """
    try:
        import pymysql  # type: ignore[import-not-found]
    except ImportError:
        return [VerificationIssue(code="L2_DATABASE_DRIVER_UNAVAILABLE", severity="HIGH", message="PyMySQL is not installed in the verifier sidecar.")]
    parsed = urlparse(dsn)
    bootstrap_database = unquote(parsed.path.strip("/"))
    if parsed.scheme != "mysql" or not parsed.hostname or not bootstrap_database:
        return [VerificationIssue(code="L2_DATABASE_DSN_INVALID", severity="CRITICAL", message="verification MySQL DSN is invalid.")]

    schema_name = f"{_VERIFY_SCHEMA_PREFIX}{secrets.token_hex(12)}"
    if _VERIFY_SCHEMA_PATTERN.fullmatch(schema_name) is None:
        return [VerificationIssue(code="L2_DATABASE_SCHEMA_NAME_INVALID", severity="CRITICAL", message="generated verification schema name failed its safety check.")]

    connection_kwargs = {
        "host": parsed.hostname,
        "port": parsed.port or 3306,
        "user": unquote(parsed.username or ""),
        "password": unquote(parsed.password or ""),
        "database": bootstrap_database,
        "connect_timeout": max(1, int(timeout_seconds)),
        "read_timeout": max(1, int(timeout_seconds)),
        "write_timeout": max(1, int(timeout_seconds)),
        "autocommit": True,
    }
    connection = None
    schema_created = False
    issues: list[VerificationIssue] = []
    try:
        connection = pymysql.connect(**connection_kwargs)
        with connection.cursor() as cursor:
            cursor.execute(
                f"CREATE DATABASE `{schema_name}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        schema_created = True
        connection.select_db(schema_name)
        with connection.cursor() as cursor:
            for statement in _sql_statements(compiled.files["schema.sql"]):
                statement = statement.strip()
                if statement and not statement.startswith("--") and statement != "SET NAMES utf8mb4":
                    cursor.execute(statement)
    except Exception as exc:  # the sidecar converts all database failures to evidence
        issues.append(VerificationIssue(code="L2_DATABASE_SCHEMA_FAILED", severity="HIGH", message=str(exc)[:1000]))
    finally:
        if schema_created:
            cleanup_connection = connection
            try:
                if cleanup_connection is None:
                    cleanup_connection = pymysql.connect(**connection_kwargs)
                else:
                    try:
                        cleanup_connection.ping(reconnect=True)
                    except Exception:
                        cleanup_connection.close()
                        cleanup_connection = pymysql.connect(**connection_kwargs)
                cleanup_connection.select_db(bootstrap_database)
                with cleanup_connection.cursor() as cursor:
                    cursor.execute(f"DROP DATABASE IF EXISTS `{schema_name}`")
            except Exception as exc:
                issues.append(VerificationIssue(
                    code="L2_DATABASE_CLEANUP_FAILED",
                    severity="CRITICAL",
                    message=f"verification schema cleanup failed for {schema_name}: {str(exc)[:900]}",
                ))
            finally:
                if cleanup_connection is not None:
                    cleanup_connection.close()
        elif connection is not None:
            connection.close()
    return issues


def _sql_statements(sql: str) -> list[str]:
    statements: list[str] = []
    current: list[str] = []
    quoted = False
    index = 0
    while index < len(sql):
        char = sql[index]
        current.append(char)
        if char == "'":
            if quoted and index + 1 < len(sql) and sql[index + 1] == "'":
                current.append(sql[index + 1])
                index += 1
            else:
                quoted = not quoted
        elif char == ";" and not quoted:
            statements.append("".join(current[:-1]))
            current = []
        index += 1
    if "".join(current).strip():
        statements.append("".join(current))
    return statements


def compiled_report(
    compiled: CompiledSpec,
    execution_id: str,
    issues: list[VerificationIssue],
    checks: list[VerificationCheck],
    *,
    scope: str = "FULL",
) -> VerificationReport:
    blocked = bool(issues)
    return VerificationReport(
        execution_id=execution_id,
        contract_id=compiled.manifest["contract_id"],
        scope=scope,
        verifier_version=(
            VERIFIER_VERSION_V2
            if compiled.manifest["compiler_version"] == COMPILER_VERSION_V2
            else VERIFIER_VERSION
        ),
        compiler_version=compiled.manifest["compiler_version"],
        level="L2",
        status="ERROR" if blocked else "PASSED",
        gate_status="BLOCKED" if blocked else "PASSED",
        source_digest=compiled.source_digest,
        checks=checks,
        issues=issues,
        generated_files=sorted(compiled.files),
    )
