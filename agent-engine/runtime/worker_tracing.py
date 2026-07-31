from __future__ import annotations

import os

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Tracer


INSTRUMENTATION_NAME = "autospec.agent-worker"


def configure_worker_tracer() -> Tracer:
    if not _enabled(os.getenv("WORKER_TRACING_ENABLED", "false")):
        return trace.get_tracer(INSTRUMENTATION_NAME)

    provider = TracerProvider(
        resource=Resource.create(
            {
                SERVICE_NAME: os.getenv(
                    "OTEL_SERVICE_NAME",
                    "autospec-agent-worker",
                )
            }
        )
    )
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(
                endpoint=os.getenv(
                    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                    "http://localhost:4318/v1/traces",
                ),
                timeout=float(os.getenv("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "5")),
            )
        )
    )
    trace.set_tracer_provider(provider)
    return provider.get_tracer(INSTRUMENTATION_NAME)


def _enabled(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}
