from __future__ import annotations

import asyncio
from collections import deque
from contextlib import asynccontextmanager
from typing import AsyncIterator


class FairSemaphore:
    """FIFO semaphore for predictable worker scheduling under load."""

    def __init__(self, limit: int) -> None:
        if limit < 1:
            raise ValueError("semaphore limit must be positive")
        self._available = limit
        self._waiters: deque[asyncio.Future[None]] = deque()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            if self._available > 0 and not self._waiters:
                self._available -= 1
                return
            waiter = asyncio.get_running_loop().create_future()
            self._waiters.append(waiter)
        try:
            await waiter
        except asyncio.CancelledError:
            async with self._lock:
                if waiter in self._waiters:
                    self._waiters.remove(waiter)
                elif waiter.done():
                    self._release_locked()
            raise

    async def release(self) -> None:
        async with self._lock:
            self._release_locked()

    def _release_locked(self) -> None:
        while self._waiters:
            waiter = self._waiters.popleft()
            if not waiter.done():
                waiter.set_result(None)
                return
        self._available += 1

    @asynccontextmanager
    async def hold(self) -> AsyncIterator[None]:
        await self.acquire()
        try:
            yield
        finally:
            await self.release()


class ConcurrencyController:
    """Coordinates node-level work and the process-wide LLM concurrency budget."""

    def __init__(self, global_llm_limit: int = 2, per_user_limit: int = 1) -> None:
        if per_user_limit < 1:
            raise ValueError("per-user semaphore limit must be positive")
        self.global_llm = FairSemaphore(global_llm_limit)
        self.per_user_limit = per_user_limit
        self._user_semaphores: dict[str, FairSemaphore] = {}
        self._user_lock = asyncio.Lock()

    async def _user_semaphore(self, user_key: str) -> FairSemaphore:
        async with self._user_lock:
            return self._user_semaphores.setdefault(
                user_key,
                FairSemaphore(self.per_user_limit),
            )

    @asynccontextmanager
    async def hold(
        self,
        *,
        user_key: str | None = None,
        requires_model: bool = True,
    ) -> AsyncIterator[None]:
        user = await self._user_semaphore(user_key) if user_key else None
        if user is not None:
            await user.acquire()
        try:
            if requires_model:
                async with self.global_llm.hold():
                    yield
            else:
                yield
        finally:
            if user is not None:
                await user.release()
