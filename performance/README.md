# AutoSpec performance baseline

This directory contains the reproducible assets for ROLE-BE-02. It intentionally
contains no claimed QPS or latency result: every threshold in the k6 scripts is a
**target**, not an observation. Record observations only after running against a
named environment and completing the report template.

## Safety boundaries

- Run write and workflow scenarios only against an isolated performance
  environment. They create persistent records.
- `write-baseline.js` refuses to run unless `AUTOSPEC_ALLOW_WRITES=true`.
- `workflow-baseline.js` refuses to run unless
  `AUTOSPEC_ALLOW_WORKFLOW_LOAD=true`. A real published workflow may call model
  providers and incur cost.
- The workflow script does not auto-approve manual gates. Select a workflow
  without a manual gate, provide a separately documented approver, or expect
  the run to time out rather than treating that timeout as a capacity result.
- The history generator requires two explicit confirmations and refuses more
  than 1,000,000 rows per history table.
- Never point these tools at production or a shared developer database.
- Use a unique `AUTOSPEC_TEST_RUN_ID` for every run. If omitted, the client
  creates one. Workflow idempotency keys combine that run ID, scenario, VU,
  iteration, and a per-VU sequence, and remain within the API's 128-character
  limit.

## Prerequisites

- k6 installed and available on `PATH`
- the AutoSpec backend reachable from the load generator
- an AutoSpec account with access to the selected project
- synchronized clocks on the load generator and service hosts
- external collection for CPU, memory, GC, connection pool, MySQL, and Redis
  metrics; k6 alone cannot observe those server-side signals

The local demo username is `owner`. Set explicit credentials for every
environment. The password is read from an environment variable and is never
embedded in a script.

```powershell
$env:AUTOSPEC_BASE_URL = 'http://localhost:8080'
$env:AUTOSPEC_USERNAME = 'owner'
$env:AUTOSPEC_PASSWORD = '<local-demo-password>'
k6 version
k6 run performance/k6/smoke.js
```

`setup()` logs in once and passes the same session token to all VUs. An existing
token can be supplied through `AUTOSPEC_SESSION_TOKEN`, in which case the login
request is skipped.

## Scenario catalog

| Script | State change | Default load | Target thresholds |
| --- | --- | --- | --- |
| `smoke.js` | No | 1 iteration | health/list P95 < 500 ms, P99 < 1,000 ms, errors < 1% |
| `read-baseline.js` | No | ramp to 10 VUs, hold 2 min | each primary read P95 < 300 ms, P99 < 800 ms, errors < 1% |
| `write-baseline.js` | Creates projects | ramp to 5 VUs, hold 1 min | create P95 < 500 ms, P99 < 1,200 ms, errors < 1% |
| `workflow-baseline.js` | Starts real workflows | 1 concurrent run | admission P95 < 800 ms/P99 < 2,000 ms; status P95 < 300 ms/P99 < 800 ms; completion P95 < 5 min/P99 < 10 min; errors/timeouts < 1% |

These values are initial service objectives for the baseline exercise. A
threshold pass is not proof of capacity: the report must also contain achieved
request rate, resource saturation, dataset size, and test topology.

## Environment variables

Common variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTOSPEC_BASE_URL` | `http://localhost:8080` | Backend origin, without `/api` |
| `AUTOSPEC_USERNAME` | `owner` | Login username |
| `AUTOSPEC_PASSWORD` | required unless a token is supplied | Login password |
| `AUTOSPEC_SESSION_TOKEN` | unset | Reuse an already issued token instead of logging in |
| `AUTOSPEC_TEST_RUN_ID` | generated | Unique 1-32 character run label and idempotency-key component |
| `AUTOSPEC_REQUEST_TIMEOUT` | `30s` | Per-request timeout |
| `AUTOSPEC_PAGE_LIMIT` | `50` (`10` in smoke) | API page size, valid range 1-100 |

Read variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTOSPEC_PROJECT_ID` | required | Project containing the prepared history |
| `AUTOSPEC_WORKFLOW_RUN_ID` | unset | Also query one run and its node history |
| `AUTOSPEC_READ_VUS` | `10` | Peak read VUs |
| `AUTOSPEC_DURATION` | `2m` | Steady-state duration |
| `AUTOSPEC_RAMP_UP` / `AUTOSPEC_RAMP_DOWN` | `30s` | Read ramps |
| `AUTOSPEC_PROJECT_OFFSET` | `0` | Project-list offset |
| `AUTOSPEC_HISTORY_OFFSET` | `0` | History endpoint offset |
| `AUTOSPEC_READ_PAUSE_MS` | `250` | Think time after one read mix |

Write variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTOSPEC_ALLOW_WRITES` | `false` | Required safety opt-in |
| `AUTOSPEC_WRITE_VUS` | `5` | Peak write VUs |
| `AUTOSPEC_DURATION` | `1m` | Steady-state duration |
| `AUTOSPEC_WRITE_PAUSE_MS` | `250` | Think time between creates |

Workflow variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTOSPEC_ALLOW_WORKFLOW_LOAD` | `false` | Required safety/cost opt-in |
| `AUTOSPEC_PROJECT_ID` | required | Writable project |
| `AUTOSPEC_WORKFLOW_VERSION_ID` | required | Published workflow version |
| `AUTOSPEC_WORKFLOW_INPUT_JSON` | synthetic requirement | Input JSON object |
| `AUTOSPEC_WORKFLOW_CONCURRENCY` | `1` | Exact number of VUs and runs, maximum 1,000 |
| `AUTOSPEC_WORKFLOW_TIMEOUT_SECONDS` | `600` | Per-run terminal-state timeout |
| `AUTOSPEC_WORKFLOW_POLL_MS` | `2000` | Status polling interval |
| `AUTOSPEC_WORKFLOW_MAX_DURATION` | `12m` | k6 scenario upper bound |

## Recommended execution order

1. Deploy the exact commit to an isolated environment.
2. Prepare and count the dataset as described in
   [`data/README.md`](data/README.md).
3. Record machine and topology details in the report before load starts.
4. Run smoke. Stop if it fails.
5. Warm the system with a short read run; do not include warm-up in results.
6. Run read tiers independently, allowing the environment to return to idle
   between tiers.
7. Run the write tier only after confirming database reset/retention plans.
8. Run workflow concurrency tiers only after confirming worker and model
   capacity/cost.
9. Save k6 output plus server metrics and complete the report.

Example read baseline at the first page:

```powershell
$env:AUTOSPEC_PROJECT_ID = '123'
$env:AUTOSPEC_READ_VUS = '25'
$env:AUTOSPEC_DURATION = '5m'
$env:AUTOSPEC_HISTORY_OFFSET = '0'
k6 run --summary-export "$env:TEMP\autospec-read-first-page.json" performance/k6/read-baseline.js
```

Repeat against a deep page without mixing it into the first-page sample:

```powershell
$env:AUTOSPEC_HISTORY_OFFSET = '99900' # for a 100k-row table
k6 run --summary-export "$env:TEMP\autospec-read-deep-page.json" performance/k6/read-baseline.js
```

Example project-write baseline:

```powershell
$env:AUTOSPEC_ALLOW_WRITES = 'true'
$env:AUTOSPEC_WRITE_VUS = '10'
$env:AUTOSPEC_TEST_RUN_ID = 'write-20260716-a'
k6 run --summary-export "$env:TEMP\autospec-write.json" performance/k6/write-baseline.js
```

Example workflow run:

```powershell
$env:AUTOSPEC_ALLOW_WORKFLOW_LOAD = 'true'
$env:AUTOSPEC_PROJECT_ID = '123'
$env:AUTOSPEC_WORKFLOW_VERSION_ID = '7'
$env:AUTOSPEC_WORKFLOW_CONCURRENCY = '1'
$env:AUTOSPEC_TEST_RUN_ID = 'workflow-20260716-a'
k6 run --summary-export "$env:TEMP\autospec-workflow.json" performance/k6/workflow-baseline.js
```

For the planned queue-saturation exercise, use separate runs at 100, 500, and
1,000 concurrency. Do not jump directly to 1,000. At each tier, verify worker
capacity, Redis stream length/Pending Entry List, oldest pending age, outbox
backlog, MySQL connection-pool use, and model-provider limits before proceeding.
The workflow script starts exactly one run per VU and waits for a terminal state,
so `AUTOSPEC_WORKFLOW_CONCURRENCY` is also the intended concurrent-run count.

## Measurement rules

- Use a dedicated load-generator host; do not run k6 on the backend host for a
  capacity claim.
- Record k6 version, JVM flags, container limits, worker count, database/Redis
  versions, connection-pool size, and network placement.
- Keep warm-up, steady state, and cool-down distinguishable.
- Report QPS, P50, P95, P99, maximum, and error rate per tagged endpoint.
- Correlate the same time window with backend CPU/memory, JVM GC, thread count,
  connection-pool active/pending/max, MySQL slow queries, Redis stream pending,
  worker utilization, and external model latency.
- A `202`/`200`-style workflow admission is not an end-to-end success. Report
  admission latency and terminal completion latency separately.
- Do not average percentile values from multiple runs. Preserve each raw run and
  compare like-for-like steady-state windows.

Use [`reports/baseline-report-template.md`](reports/baseline-report-template.md)
for the evidence package and [`sql/explain-analyze.md`](sql/explain-analyze.md)
for query-plan capture.
