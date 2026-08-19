from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Protocol

from runtime.node_executor import NodeExecutionEvent


class ExecutionClaimStatus(StrEnum):
    ACQUIRED = "ACQUIRED"
    CACHED = "CACHED"
    BUSY = "BUSY"


@dataclass(frozen=True)
class ExecutionClaim:
    status: ExecutionClaimStatus
    fencing_token: int
    cached_event: NodeExecutionEvent | None = None


class ExecutionLedger(Protocol):
    async def claim(
        self,
        execution_id: str,
        owner_id: str,
        lease_ms: int,
    ) -> ExecutionClaim: ...

    async def renew(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        lease_ms: int,
    ) -> bool: ...

    async def complete(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        event: NodeExecutionEvent,
    ) -> bool: ...


@dataclass
class _Entry:
    state: str = "NEW"
    fencing_token: int = 0
    owner_id: str | None = None
    lease_until_ms: int = 0
    event: NodeExecutionEvent | None = None


class InMemoryExecutionLedger:
    """Deterministic ledger for tests and non-distributed fixture mode."""

    def __init__(self) -> None:
        self._entries: dict[str, _Entry] = {}
        self._lock = asyncio.Lock()

    async def claim(
        self,
        execution_id: str,
        owner_id: str,
        lease_ms: int,
    ) -> ExecutionClaim:
        async with self._lock:
            now = _now_ms()
            entry = self._entries.setdefault(execution_id, _Entry())
            if entry.state == "COMPLETED" and entry.event is not None:
                return ExecutionClaim(
                    ExecutionClaimStatus.CACHED,
                    entry.fencing_token,
                    entry.event,
                )
            if entry.state == "RUNNING" and entry.lease_until_ms > now:
                return ExecutionClaim(ExecutionClaimStatus.BUSY, entry.fencing_token)
            entry.state = "RUNNING"
            entry.fencing_token += 1
            entry.owner_id = owner_id
            entry.lease_until_ms = now + lease_ms
            entry.event = None
            return ExecutionClaim(ExecutionClaimStatus.ACQUIRED, entry.fencing_token)

    async def renew(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        lease_ms: int,
    ) -> bool:
        async with self._lock:
            entry = self._entries.get(execution_id)
            if (
                entry is None
                or entry.state != "RUNNING"
                or entry.owner_id != owner_id
                or entry.fencing_token != fencing_token
            ):
                return False
            entry.lease_until_ms = _now_ms() + lease_ms
            return True

    async def complete(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        event: NodeExecutionEvent,
    ) -> bool:
        async with self._lock:
            entry = self._entries.get(execution_id)
            if (
                entry is None
                or entry.state != "RUNNING"
                or entry.owner_id != owner_id
                or entry.fencing_token != fencing_token
            ):
                return False
            entry.state = "COMPLETED"
            entry.lease_until_ms = 0
            entry.event = event.model_copy(deep=True)
            return True


class RedisExecutionLedger:
    _CLAIM_SCRIPT = """
local state = redis.call('HGET', KEYS[1], 'state')
local fence = tonumber(redis.call('HGET', KEYS[1], 'fence') or '0')
if state == 'COMPLETED' then
  return {'CACHED', tostring(fence), redis.call('HGET', KEYS[1], 'event') or ''}
end
local lease_until = tonumber(redis.call('HGET', KEYS[1], 'lease_until_ms') or '0')
if state == 'RUNNING' and lease_until > tonumber(ARGV[1]) then
  return {'BUSY', tostring(fence), ''}
end
fence = fence + 1
redis.call('HSET', KEYS[1],
  'state', 'RUNNING',
  'fence', tostring(fence),
  'owner', ARGV[2],
  'lease_until_ms', tostring(tonumber(ARGV[1]) + tonumber(ARGV[3])))
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 4)
return {'ACQUIRED', tostring(fence), ''}
"""

    _RENEW_SCRIPT = """
if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING'
  or redis.call('HGET', KEYS[1], 'owner') ~= ARGV[2]
  or tonumber(redis.call('HGET', KEYS[1], 'fence') or '-1') ~= tonumber(ARGV[3]) then
  return 0
end
redis.call('HSET', KEYS[1], 'lease_until_ms', tostring(tonumber(ARGV[1]) + tonumber(ARGV[4])))
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]) * 4)
return 1
"""

    _COMPLETE_SCRIPT = """
if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING'
  or redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1]
  or tonumber(redis.call('HGET', KEYS[1], 'fence') or '-1') ~= tonumber(ARGV[2]) then
  return 0
end
redis.call('HSET', KEYS[1],
  'state', 'COMPLETED',
  'event', ARGV[3],
  'lease_until_ms', '0')
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4]))
return 1
"""

    def __init__(
        self,
        redis_client: Any,
        *,
        key_prefix: str = "autospec:execution-ledger:",
        completed_retention_ms: int = 7 * 24 * 60 * 60 * 1000,
    ) -> None:
        self._redis = redis_client
        self._key_prefix = key_prefix
        self._completed_retention_ms = completed_retention_ms

    async def claim(
        self,
        execution_id: str,
        owner_id: str,
        lease_ms: int,
    ) -> ExecutionClaim:
        response = await self._redis.eval(
            self._CLAIM_SCRIPT,
            1,
            self._key(execution_id),
            _now_ms(),
            owner_id,
            lease_ms,
        )
        status = ExecutionClaimStatus(_decode(response[0]))
        fence = int(_decode(response[1]))
        cached = _decode(response[2]) if len(response) > 2 else ""
        return ExecutionClaim(
            status,
            fence,
            NodeExecutionEvent.model_validate_json(cached) if cached else None,
        )

    async def renew(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        lease_ms: int,
    ) -> bool:
        result = await self._redis.eval(
            self._RENEW_SCRIPT,
            1,
            self._key(execution_id),
            _now_ms(),
            owner_id,
            fencing_token,
            lease_ms,
        )
        return int(result) == 1

    async def complete(
        self,
        execution_id: str,
        owner_id: str,
        fencing_token: int,
        event: NodeExecutionEvent,
    ) -> bool:
        result = await self._redis.eval(
            self._COMPLETE_SCRIPT,
            1,
            self._key(execution_id),
            owner_id,
            fencing_token,
            event.model_dump_json(),
            self._completed_retention_ms,
        )
        return int(result) == 1

    def _key(self, execution_id: str) -> str:
        digest = hashlib.sha256(execution_id.encode("utf-8")).hexdigest()
        return self._key_prefix + digest


def _decode(value: Any) -> str:
    return value.decode("utf-8") if isinstance(value, bytes) else str(value)


def _now_ms() -> int:
    return round(time.time() * 1000)
