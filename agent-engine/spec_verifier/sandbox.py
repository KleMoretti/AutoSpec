from __future__ import annotations

import os
import subprocess
import tempfile
from urllib.parse import unquote, urlparse
from pathlib import Path

from schemas.verification import VerificationCheck, VerificationIssue, VerificationReport
from spec_verifier.compiler import CompiledSpec


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
            database_issue = _verify_mysql(compiled, dsn, timeout_seconds)
            if database_issue is not None:
                issues.append(database_issue)
            checks.append(VerificationCheck(category="mysql_schema", status="PASSED" if database_issue is None else "ERROR", issue_codes=[] if database_issue is None else [database_issue.code]))

    return compiled_report(compiled, execution_id, issues, checks, scope=scope)


def _verify_mysql(compiled: CompiledSpec, dsn: str, timeout_seconds: float) -> VerificationIssue | None:
    try:
        import pymysql  # type: ignore[import-not-found]
    except ImportError:
        return VerificationIssue(code="L2_DATABASE_DRIVER_UNAVAILABLE", severity="HIGH", message="PyMySQL is not installed in the verifier sidecar.")
    parsed = urlparse(dsn)
    if parsed.scheme != "mysql" or not parsed.hostname or not parsed.path.strip("/"):
        return VerificationIssue(code="L2_DATABASE_DSN_INVALID", severity="CRITICAL", message="verification MySQL DSN is invalid.")
    connection = None
    table_names = [line.split("`")[1] for line in compiled.files["schema.sql"].splitlines() if line.startswith("CREATE TABLE `")]
    try:
        connection = pymysql.connect(
            host=parsed.hostname,
            port=parsed.port or 3306,
            user=unquote(parsed.username or ""),
            password=unquote(parsed.password or ""),
            database=parsed.path.strip("/"),
            connect_timeout=max(1, int(timeout_seconds)),
            read_timeout=max(1, int(timeout_seconds)),
            write_timeout=max(1, int(timeout_seconds)),
            autocommit=True,
        )
        with connection.cursor() as cursor:
            for table in reversed(table_names):
                cursor.execute(f"DROP TABLE IF EXISTS `{table}`")
            for statement in _sql_statements(compiled.files["schema.sql"]):
                statement = statement.strip()
                if statement and not statement.startswith("--") and statement != "SET NAMES utf8mb4":
                    cursor.execute(statement)
    except Exception as exc:  # the sidecar converts all database failures to evidence
        return VerificationIssue(code="L2_DATABASE_SCHEMA_FAILED", severity="HIGH", message=str(exc)[:1000])
    finally:
        if connection is not None:
            try:
                with connection.cursor() as cursor:
                    for table in reversed(table_names):
                        cursor.execute(f"DROP TABLE IF EXISTS `{table}`")
            except Exception:
                pass
            connection.close()
    return None


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
        verifier_version="spec-verifier-v1",
        compiler_version=compiled.manifest["compiler_version"],
        level="L2",
        status="ERROR" if blocked else "PASSED",
        gate_status="BLOCKED" if blocked else "PASSED",
        source_digest=compiled.source_digest,
        checks=checks,
        issues=issues,
        generated_files=sorted(compiled.files),
    )
