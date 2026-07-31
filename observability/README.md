# AutoSpec observability

The monitoring stack is optional and does not run with the default Compose
profile. It adds:

- Prometheus scraping the backend and both Python Worker metric endpoints;
- provisioned Grafana with the `AutoSpec Operations` dashboard;
- Tempo receiving OTLP/HTTP traces from the Java backend and Python Workers;
- Prometheus alerts for Outbox and Redis Pending backlogs, dead letters,
  Worker health and failure rate, HTTP P99 latency, and HTTP 5xx failure rate.

## Start the monitoring profile

Set the existing database variables and a strong Grafana password in the
current shell or in a local, untracked `.env` file. `GRAFANA_ADMIN_PASSWORD` is
required when the monitoring profile starts; the Compose file intentionally
contains no password default. The Grafana container exits before startup when
the value is missing.

```powershell
$env:MYSQL_PASSWORD = "<local database password>"
$env:MYSQL_ROOT_PASSWORD = "<local root password>"
$env:GRAFANA_ADMIN_PASSWORD = "<strong local Grafana password>"
$env:BACKEND_TRACING_ENABLED = "true"
$env:WORKER_TRACING_ENABLED = "true"
docker compose --profile monitoring up -d
```

Do not use the angle-bracket placeholders as passwords and do not commit the
local values.

The UIs bind to loopback by default:

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- Tempo API: `http://127.0.0.1:3200`
- OTLP/HTTP ingest: `http://127.0.0.1:4318/v1/traces`

Use `GRAFANA_BIND_ADDRESS` or `PROMETHEUS_BIND_ADDRESS` only when remote access
is intentional and protected by an authenticated reverse proxy. Tempo and its
unauthenticated OTLP receivers use `TEMPO_BIND_ADDRESS` and are also restricted
to loopback by default.

Grafana provisions `AutoSpec Tempo`; open **Explore**, choose that data source,
and search by `service.name`, workflow attributes, or a trace ID copied from a
correlated log line. Tracing remains disabled in the default Compose run, so the
backend and Workers do not retry an unavailable collector when the monitoring
profile is not active.

To stop and remove the monitoring containers without deleting metric or
dashboard state:

```powershell
docker compose --profile monitoring down
```

## Dashboard and metric contract

The dashboard uses standard Spring Boot Micrometer metrics immediately:

- `http_server_requests_seconds_*`
- `jvm_memory_*` and `jvm_gc_pause_seconds_*`
- `hikaricp_connections_*`

The following AutoSpec metric names are the contract for the application
instrumentation:

| Area | Metric | Status |
| --- | --- | --- |
| Outbox | `autospec_workflow_outbox_pending` | Implemented |
| Outbox | `autospec_workflow_outbox_oldest_age_seconds` | Implemented |
| Outbox | `autospec_workflow_outbox_publish_failures_total` | Implemented |
| Outbox | `autospec_workflow_outbox_dead_letters_total` | Implemented |
| Redis Streams | `autospec_redis_stream_pending` | Implemented |
| Redis Streams | `autospec_redis_stream_oldest_idle_seconds` | Implemented |
| Redis Streams | `autospec_redis_stream_reclaimed_total` | Implemented |
| Worker | `autospec_worker_active` | Implemented |
| Worker | `autospec_worker_inflight` | Implemented |
| Worker | `autospec_worker_heartbeat_delay_seconds` | Implemented |
| Worker | `autospec_worker_commands_total{outcome}` | Implemented |
| Model | `autospec_model_invocations_total{provider,model,status}` | Implemented |
| Model | `autospec_model_invocation_duration_seconds_bucket` | Implemented |
| Model | `autospec_model_tokens_total{provider,model,token_type}` | Implemented |
| Model | `autospec_model_cost_total{provider,model}` | Implemented |

Worker metrics listen on container port `9100` and are scraped only over the
internal Compose network; no host port is published.

## Alert thresholds

Default thresholds are intentionally conservative starting points:

- Outbox pending messages: more than 100 for 5 minutes;
- oldest Outbox message: more than 5 minutes old for 5 minutes;
- any new Outbox dead letter in 5 minutes;
- Redis Pending messages: more than 100 for 5 minutes;
- oldest Redis Pending message: more than 2 minutes idle for 5 minutes;
- Worker scrape target down or active Worker heartbeat stale for 2 minutes;
- Worker failure/dead-letter rate: more than 5 percent for 5 minutes, with at
  least 20 commands in the measurement window;
- any backlog collection or workflow event-handler failure in 5 minutes;
- backend HTTP P99: more than 2 seconds for 10 minutes;
- backend HTTP 5xx rate: more than 5 percent for 5 minutes.

Tune them only after capturing a representative k6 baseline. Prometheus loads
the rules, but this increment does not configure an Alertmanager receiver, so
notification routing remains an environment-specific deployment concern.

## Static validation

Compose interpolation requires validation-only values for all required
password variables:

```powershell
& {
    $env:MYSQL_PASSWORD = "compose-validation-only"
    $env:MYSQL_ROOT_PASSWORD = "compose-validation-only"
    $env:GRAFANA_ADMIN_PASSWORD = "compose-validation-only"
    $env:BACKEND_TRACING_ENABLED = "true"
    $env:WORKER_TRACING_ENABLED = "true"
    docker compose --profile monitoring config --quiet
}

Get-Content -Raw observability/grafana/dashboards/autospec-operations.json |
    ConvertFrom-Json | Out-Null
```

These commands validate syntax only and do not start the stack.
