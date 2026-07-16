# AutoSpec observability

The monitoring stack is optional and does not run with the default Compose
profile. It adds:

- Prometheus scraping the backend at `/actuator/prometheus`;
- provisioned Grafana with the `AutoSpec Operations` dashboard;
- Prometheus alerts for Outbox and Redis Pending backlogs, HTTP P99 latency,
  and HTTP 5xx failure rate.

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
docker compose --profile monitoring up -d
```

Do not use the angle-bracket placeholders as passwords and do not commit the
local values.

The UIs bind to loopback by default:

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`

Use `GRAFANA_BIND_ADDRESS` or `PROMETHEUS_BIND_ADDRESS` only when remote access
is intentional and protected by an authenticated reverse proxy.

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
instrumentation work. Panels marked **reserved metric** show `No data` until
the corresponding Java or Python instrumentation is enabled:

| Area | Metric | Status |
| --- | --- | --- |
| Outbox | `autospec_workflow_outbox_pending` | Reserved |
| Outbox | `autospec_workflow_outbox_oldest_age_seconds` | Reserved |
| Outbox | `autospec_workflow_outbox_publish_failures_total` | Implemented |
| Redis Streams | `autospec_redis_stream_pending` | Reserved |
| Redis Streams | `autospec_redis_stream_oldest_idle_seconds` | Reserved |
| Redis Streams | `autospec_redis_stream_reclaimed_total` | Implemented |
| Worker | `autospec_worker_active` | Reserved |
| Worker | `autospec_worker_inflight` | Reserved |
| Worker | `autospec_worker_heartbeat_delay_seconds` | Reserved |
| Model | `autospec_model_invocations_total{status}` | Reserved |
| Model | `autospec_model_invocation_duration_seconds_bucket` | Reserved |
| Model | `autospec_model_tokens_total` | Reserved |
| Model | `autospec_model_cost_total` | Reserved |

If Worker metrics are later exposed by a separate HTTP server, add its internal
Compose target to `observability/prometheus/prometheus.yml`. Dashboard queries
do not need to change.

## Alert thresholds

Default thresholds are intentionally conservative starting points:

- Outbox pending messages: more than 100 for 5 minutes;
- Redis Pending messages: more than 100 for 5 minutes;
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
    docker compose --profile monitoring config --quiet
}

Get-Content -Raw observability/grafana/dashboards/autospec-operations.json |
    ConvertFrom-Json | Out-Null
```

These commands validate syntax only and do not start the stack.
