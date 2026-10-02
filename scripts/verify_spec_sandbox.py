"""Probe the running verifier's security, limits, and cleanup contract.

The probe is intentionally Docker-aware and read-only from the host: it
inspects the verifier container and executes short HTTP/cleanup checks inside
that container. It never prints service tokens or database credentials.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from typing import Any


EXPECTED_MEMORY_BYTES = 768 * 1024 * 1024
EXPECTED_NANO_CPUS = 1_000_000_000
EXPECTED_PIDS_LIMIT = 128


def _docker(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["docker", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()[:500]
        raise RuntimeError(f"docker {' '.join(args[:3])} failed: {detail}")
    return result


def _inspect(container: str) -> dict[str, Any]:
    result = _docker("inspect", container)
    payload = json.loads(result.stdout)
    if not payload:
        raise RuntimeError(f"container not found: {container}")
    return payload[0]


def _check_container_limits(container: str) -> dict[str, Any]:
    inspected = _inspect(container)
    host = inspected.get("HostConfig", {})
    networks = list((inspected.get("NetworkSettings", {}).get("Networks") or {}).keys())
    tmpfs = host.get("Tmpfs") or {}

    checks = {
        "readonly_rootfs": host.get("ReadonlyRootfs") is True,
        "drop_all_capabilities": "ALL" in {str(value).upper() for value in host.get("CapDrop", [])},
        "no_new_privileges": any(
            str(value).lower() == "no-new-privileges:true"
            for value in host.get("SecurityOpt", [])
        ),
        "memory_limit": host.get("Memory") == EXPECTED_MEMORY_BYTES,
        "cpu_limit": host.get("NanoCpus") == EXPECTED_NANO_CPUS,
        "pid_limit": host.get("PidsLimit") == EXPECTED_PIDS_LIMIT,
        "tmpfs_limit": tmpfs.get("/tmp") == "size=64m,mode=1777",
        "internal_network_only": len(networks) == 1 and networks[0].endswith("_verification_internal"),
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise RuntimeError(f"verifier container limit checks failed: {', '.join(failed)}")
    return {
        "container": container,
        "checks": checks,
        "memory_bytes": host.get("Memory"),
        "nano_cpus": host.get("NanoCpus"),
        "pids_limit": host.get("PidsLimit"),
        "tmpfs": tmpfs,
        "networks": networks,
    }


_IN_CONTAINER_PROBE = r'''
import glob
import json
import os
import urllib.error
import urllib.request
from urllib.parse import unquote, urlparse

BASE = "http://127.0.0.1:8010"
TOKEN = os.environ.get("SPEC_VERIFIER_SERVICE_TOKEN", "")


def request(path, payload=None, token=None):
    body = None if payload is None else payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token is not None:
        headers["X-AutoSpec-Service-Token"] = token
    request_value = urllib.request.Request(
        BASE + path,
        data=body,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request_value, timeout=15) as response:
            return response.status, response.read(4096)
    except urllib.error.HTTPError as error:
        return error.code, error.read(4096)


def require(label, condition, detail):
    if not condition:
        raise RuntimeError(f"{label}: {detail}")


health_status, health_body = request("/health")
require("health", health_status == 200, str(health_status))

unauthorized_status, _ = request("/verify", b"{}")
require("token", unauthorized_status == 401, str(unauthorized_status))

oversized_body = b"{" + (b"x" * (512 * 1024)) + b"}"
oversized_status, _ = request("/verify", oversized_body, TOKEN)
require("request_size", oversized_status == 413, str(oversized_status))

timeout_status, _ = request(
    "/verify",
    {
        "execution_id": "sandbox-probe-timeout-bound",
        "contract": {},
        "scope": "BACKEND",
        "required_level": "L1",
        "timeout_ms": 900,
    },
    TOKEN,
)
require("timeout_bound", timeout_status == 422, str(timeout_status))

from spec_verifier.fixtures import spec_fixture

valid_status, valid_body = request(
    "/verify",
    {
        "execution_id": "sandbox-probe-l2",
        "contract": spec_fixture("campus_marketplace").model_dump(mode="json"),
        "scope": "FULL",
        "required_level": "L2",
        "timeout_ms": 30_000,
    },
    TOKEN,
)
require("l2", valid_status == 200, str(valid_status))
report = json.loads(valid_body)
require("l2_report", report.get("level") == "L2" and report.get("status") == "PASSED", str(report)[:500])

leftover_tmp = sorted(glob.glob("/tmp/autospec-verify-*"))
require("temporary_directory_cleanup", not leftover_tmp, str(leftover_tmp))

dsn = urlparse(os.environ["AUTOSPEC_VERIFY_MYSQL_DSN"])
import pymysql

connection = pymysql.connect(
    host=dsn.hostname,
    port=dsn.port or 3306,
    user=unquote(dsn.username or ""),
    password=unquote(dsn.password or ""),
    database=unquote(dsn.path.strip("/")),
    connect_timeout=5,
    read_timeout=5,
    write_timeout=5,
    autocommit=True,
)
try:
    with connection.cursor() as cursor:
        cursor.execute("SHOW DATABASES LIKE 'autospec_verify_%'")
        leftovers = [row[0] for row in cursor.fetchall()]
finally:
    connection.close()
require("mysql_schema_cleanup", not leftovers, str(leftovers))

try:
    urllib.request.urlopen("http://example.com", timeout=2)
except Exception as error:
    external_network = {"blocked": True, "error_type": type(error).__name__}
else:
    raise RuntimeError("external network unexpectedly reachable from verifier")

print(json.dumps({
    "health": "ok",
    "unauthorized_status": unauthorized_status,
    "oversized_request_status": oversized_status,
    "timeout_bound_status": timeout_status,
    "l2_status": report["status"],
    "l2_level": report["level"],
    "temporary_directory_cleanup": True,
    "mysql_schema_cleanup": True,
    "external_network": external_network,
}, sort_keys=True))
'''


def _run_in_container(container: str) -> dict[str, Any]:
    result = _docker(
        "exec",
        container,
        "python3",
        "-c",
        _IN_CONTAINER_PROBE,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()[:1000]
        raise RuntimeError(f"verifier runtime probes failed: {detail}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("verifier runtime probe returned invalid JSON") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="autospec", help="Compose project name used to derive container names")
    parser.add_argument("--verifier-container", help="Explicit verifier container name")
    args = parser.parse_args()
    container = args.verifier_container or f"{args.project}-spec-verifier-1"

    try:
        limits = _check_container_limits(container)
        probes = _run_in_container(container)
    except (OSError, RuntimeError, json.JSONDecodeError) as error:
        print(f"Spec sandbox probe failed: {error}", file=sys.stderr)
        return 1

    print(json.dumps({"limits": limits, "probes": probes}, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
