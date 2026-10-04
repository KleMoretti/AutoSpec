from __future__ import annotations

import os
import re
import secrets
import signal
import subprocess
import tempfile
import time
from urllib.parse import unquote, urlparse
from pathlib import Path

from schemas.verification import VerificationCheck, VerificationIssue, VerificationReport
from spec_verifier.compiler import COMPILER_VERSION_V2, CompiledSpec
from spec_verifier.validators import VERIFIER_VERSION, VERIFIER_VERSION_V2


_VERIFY_SCHEMA_PREFIX = "autospec_verify_"
_VERIFY_SCHEMA_PATTERN = re.compile(r"^autospec_verify_[0-9a-f]{24}$")
_SPEC_FAILURE_CODES = {
    "L2_COMPILER_FAILED",
    "L2_TYPESCRIPT_FAILED",
    "L2_DATABASE_SCHEMA_FAILED",
}
_ENVIRONMENT_FAILURE_CODES = {
    "L2_DATABASE_UNAVAILABLE",
    "L2_DATABASE_DRIVER_UNAVAILABLE",
    "L2_DATABASE_DSN_INVALID",
    "L2_DATABASE_SCHEMA_NAME_INVALID",
    "L2_DATABASE_CLEANUP_FAILED",
    "L2_DATABASE_ERROR",
    "L2_TYPESCRIPT_UNAVAILABLE",
    "L2_TYPESCRIPT_TIMEOUT",
    "L2_DEADLINE_EXCEEDED",
}
_DATABASE_ENVIRONMENT_ERRNOS = {
    1044, 1045, 1049, 1142, 1205, 1213, 2003, 2006, 2013, 2055,
}


class _VerifierDeadlineExceeded(RuntimeError):
    pass


def run_l2(
    compiled: CompiledSpec,
    *,
    execution_id: str,
    timeout_ms: int = 30_000,
    scope: str = "FULL",
    tsc_command: list[str] | None = None,
) -> VerificationReport:
    """Run the isolated compile checks used by the optional L2 sidecar.

    The verifier never invokes a shell and never interpolates contract values
    into a command.  Missing dependencies fail closed as an ERROR report.
    """

    issues: list[VerificationIssue] = []
    checks: list[VerificationCheck] = []
    deadline_monotonic = time.monotonic() + max(1.0, timeout_ms / 1000)
    with tempfile.TemporaryDirectory(prefix="autospec-verify-") as temp_dir:
        root = Path(temp_dir)
        for name, content in compiled.files.items():
            (root / name).write_text(content, encoding="utf-8", newline="\n")
        tsc = os.environ.get("AUTOSPEC_TSC_PATH", "tsc")
        command = list(tsc_command or [tsc])
        command.extend(["--noEmit", "--project", str(root / "tsconfig.json")])
        process = None
        try:
            remaining = _remaining_seconds(deadline_monotonic)
            if remaining <= 0:
                raise _VerifierDeadlineExceeded
            creation_flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            process = subprocess.Popen(
                command,
                cwd=root,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                shell=False,
                start_new_session=os.name != "nt",
                creationflags=creation_flags if os.name == "nt" else 0,
            )
            stdout, stderr = process.communicate(timeout=remaining)
        except FileNotFoundError:
            issues.append(VerificationIssue(code="L2_TYPESCRIPT_UNAVAILABLE", severity="HIGH", message="TypeScript compiler is not available in the verifier sandbox.", path="generated:bindings.ts"))
        except subprocess.TimeoutExpired:
            if process is not None:
                _terminate_process_group(process)
            issues.append(VerificationIssue(code="L2_TYPESCRIPT_TIMEOUT", severity="HIGH", message="TypeScript verification exceeded its deadline.", path="generated:bindings.ts"))
        except _VerifierDeadlineExceeded:
            issues.append(VerificationIssue(code="L2_DEADLINE_EXCEEDED", severity="HIGH", message="Verification deadline elapsed before TypeScript verification.", path="generated:bindings.ts"))
        else:
            if process is not None and process.returncode != 0:
                issues.append(VerificationIssue(code="L2_TYPESCRIPT_FAILED", severity="HIGH", message=(stderr or stdout or "tsc failed")[:1000], path="generated:bindings.ts"))
        typescript_issue_codes = [issue.code for issue in issues]
        typescript_status = (
            "PASSED"
            if not typescript_issue_codes
            else "FAILED"
            if "L2_TYPESCRIPT_FAILED" in typescript_issue_codes
            else "ERROR"
        )
        checks.append(VerificationCheck(
            category="typescript_compile",
            status=typescript_status,
            issue_codes=typescript_issue_codes,
        ))

        # Database execution is intentionally opt-in inside the sidecar.  A
        # configured MySQL verifier can replace this check; a missing one must
        # never be reported as a successful L2 run.
        dsn = os.environ.get("AUTOSPEC_VERIFY_MYSQL_DSN")
        if not dsn:
            issues.append(VerificationIssue(code="L2_DATABASE_UNAVAILABLE", severity="HIGH", message="No isolated verification MySQL DSN is configured."))
            checks.append(VerificationCheck(category="mysql_schema", status="ERROR", issue_codes=["L2_DATABASE_UNAVAILABLE"]))
        else:
            database_issues = _verify_mysql(
                compiled,
                dsn,
                max(1.0, timeout_ms / 1000),
                deadline_monotonic=deadline_monotonic,
            )
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
    *,
    deadline_monotonic: float | None = None,
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
        return [VerificationIssue(code="L2_DATABASE_DRIVER_UNAVAILABLE", severity="HIGH", message="PyMySQL is not installed in the verifier sidecar.", path="generated:schema.sql")]
    parsed = urlparse(dsn)
    bootstrap_database = unquote(parsed.path.strip("/"))
    if parsed.scheme != "mysql" or not parsed.hostname or not bootstrap_database:
        return [VerificationIssue(code="L2_DATABASE_DSN_INVALID", severity="CRITICAL", message="verification MySQL DSN is invalid.", path="generated:schema.sql")]

    schema_name = f"{_VERIFY_SCHEMA_PREFIX}{secrets.token_hex(12)}"
    if _VERIFY_SCHEMA_PATTERN.fullmatch(schema_name) is None:
        return [VerificationIssue(code="L2_DATABASE_SCHEMA_NAME_INVALID", severity="CRITICAL", message="generated verification schema name failed its safety check.", path="generated:schema.sql")]

    deadline = deadline_monotonic or time.monotonic() + max(1.0, timeout_seconds)
    initial_remaining = _remaining_seconds(deadline)
    if initial_remaining <= 0:
        return [VerificationIssue(code="L2_DEADLINE_EXCEEDED", severity="HIGH", message="Verification deadline elapsed before MySQL verification.", path="generated:schema.sql")]
    connection_kwargs = {
        "host": parsed.hostname,
        "port": parsed.port or 3306,
        "user": unquote(parsed.username or ""),
        "password": unquote(parsed.password or ""),
        "database": bootstrap_database,
        "connect_timeout": max(1, int(initial_remaining)),
        "read_timeout": max(1, int(initial_remaining)),
        "write_timeout": max(1, int(initial_remaining)),
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
            remaining = _remaining_seconds(deadline)
            if remaining <= 0:
                raise _VerifierDeadlineExceeded
            lock_wait_seconds = max(1, min(120, int(remaining)))
            cursor.execute(f"SET SESSION innodb_lock_wait_timeout = {lock_wait_seconds}")
            for statement in _sql_statements(compiled.files["schema.sql"]):
                statement = statement.strip()
                if statement and not statement.startswith("--") and statement != "SET NAMES utf8mb4":
                    remaining = _remaining_seconds(deadline)
                    if remaining <= 0:
                        raise _VerifierDeadlineExceeded
                    _set_socket_timeout(connection, remaining)
                    cursor.execute(statement)
    except _VerifierDeadlineExceeded:
        issues.append(VerificationIssue(code="L2_DEADLINE_EXCEEDED", severity="HIGH", message="Verification deadline elapsed during MySQL schema verification.", path="generated:schema.sql"))
    except Exception as exc:  # the sidecar converts all database failures to evidence
        issues.append(VerificationIssue(
            code=_database_issue_code(exc, connection),
            severity="HIGH",
            message=str(exc)[:1000],
            path="generated:schema.sql",
        ))
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
                    path="generated:schema.sql",
                ))
            finally:
                if cleanup_connection is not None:
                    cleanup_connection.close()
        elif connection is not None:
            connection.close()
    return issues


def _remaining_seconds(deadline_monotonic: float) -> float:
    return max(0.0, deadline_monotonic - time.monotonic())


def _set_socket_timeout(connection: object, remaining_seconds: float) -> None:
    socket = getattr(connection, "_sock", None)
    if socket is not None and hasattr(socket, "settimeout"):
        socket.settimeout(max(0.05, remaining_seconds))


def _terminate_process_group(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            process.kill()
        else:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (OSError, ProcessLookupError):
        process.kill()
    try:
        process.communicate(timeout=1.0)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()


def _database_issue_code(exc: Exception, connection: object | None) -> str:
    """Separate candidate DDL failures from verifier connectivity failures."""

    raw_errno = exc.args[0] if exc.args and isinstance(exc.args[0], int) else None
    if raw_errno in _DATABASE_ENVIRONMENT_ERRNOS or connection is None:
        return "L2_DATABASE_UNAVAILABLE"
    if type(exc).__name__ in {"ProgrammingError", "IntegrityError", "DataError"}:
        return "L2_DATABASE_SCHEMA_FAILED"
    if raw_errno in {1005, 1064, 1118, 1215, 1216, 1217}:
        return "L2_DATABASE_SCHEMA_FAILED"
    return "L2_DATABASE_ERROR"


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
    # Environment failures have priority over candidate/spec failures. A
    # broken verifier must never be presented as a repairable model defect.
    status = (
        "PASSED"
        if not issues
        else "ERROR"
        if any(issue.code in _ENVIRONMENT_FAILURE_CODES for issue in issues)
        else "FAILED"
        if any(issue.code in _SPEC_FAILURE_CODES for issue in issues)
        else "ERROR"
    )
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
        status=status,
        gate_status="BLOCKED" if blocked else "PASSED",
        source_digest=compiled.source_digest,
        checks=checks,
        issues=issues,
        generated_files=sorted(compiled.files),
    )
