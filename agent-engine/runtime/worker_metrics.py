from __future__ import annotations

import time
from typing import Callable, Protocol

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, REGISTRY


class WorkerMetricsRecorder(Protocol):
    def worker_started(self) -> None: ...

    def worker_stopped(self) -> None: ...

    def pulse(self) -> None: ...

    def record_reclaimed(self, count: int) -> None: ...

    def command_started(self) -> float: ...

    def command_finished(self, started_at: float, outcome: str) -> None: ...

    def record_dead_letter(self) -> None: ...


class WorkerMetrics:
    OUTCOMES = ("processed", "dead_lettered", "failed")

    def __init__(
        self,
        registry: CollectorRegistry = REGISTRY,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self.registry = registry
        self._monotonic = monotonic
        self._last_heartbeat = monotonic()
        self._active = Gauge(
            "autospec_worker_active",
            "Whether this AutoSpec worker process is running its consume loop",
            registry=registry,
        )
        self._inflight = Gauge(
            "autospec_worker_inflight",
            "Workflow commands currently being handled by this worker",
            registry=registry,
        )
        self._heartbeat_delay = Gauge(
            "autospec_worker_heartbeat_delay_seconds",
            "Seconds since this worker last made progress or published a heartbeat",
            registry=registry,
        )
        self._heartbeat_delay.set_function(self._seconds_since_heartbeat)
        self._commands = Counter(
            "autospec_worker_commands_total",
            "Workflow commands handled by outcome",
            labelnames=("outcome",),
            registry=registry,
        )
        for outcome in self.OUTCOMES:
            self._commands.labels(outcome=outcome)
        self._duration = Histogram(
            "autospec_worker_command_duration_seconds",
            "Time spent handling a workflow command",
            registry=registry,
        )
        self._reclaimed = Counter(
            "autospec_worker_reclaimed_total",
            "Stale workflow commands reclaimed by this worker",
            registry=registry,
        )
        self._dead_letters = Counter(
            "autospec_worker_dead_lettered_total",
            "Invalid workflow commands quarantined by this worker",
            registry=registry,
        )

    def worker_started(self) -> None:
        self._active.set(1)
        self.pulse()

    def worker_stopped(self) -> None:
        self._active.set(0)

    def pulse(self) -> None:
        self._last_heartbeat = self._monotonic()

    def record_reclaimed(self, count: int) -> None:
        if count > 0:
            self._reclaimed.inc(count)

    def command_started(self) -> float:
        self._inflight.inc()
        self.pulse()
        return self._monotonic()

    def command_finished(self, started_at: float, outcome: str) -> None:
        if outcome not in self.OUTCOMES:
            raise ValueError(f"unsupported worker outcome: {outcome}")
        self._inflight.dec()
        self._commands.labels(outcome=outcome).inc()
        self._duration.observe(max(0, self._monotonic() - started_at))
        self.pulse()

    def record_dead_letter(self) -> None:
        self._dead_letters.inc()

    def _seconds_since_heartbeat(self) -> float:
        return max(0, self._monotonic() - self._last_heartbeat)


class NoOpWorkerMetrics:
    def worker_started(self) -> None:
        pass

    def worker_stopped(self) -> None:
        pass

    def pulse(self) -> None:
        pass

    def record_reclaimed(self, count: int) -> None:
        pass

    def command_started(self) -> float:
        return 0

    def command_finished(self, started_at: float, outcome: str) -> None:
        pass

    def record_dead_letter(self) -> None:
        pass


NO_OP_WORKER_METRICS = NoOpWorkerMetrics()
