# AutoSpec performance report: `<run-id>`

> Status: DRAFT
> Measurements in this document are intentionally blank. Replace every `TBD`
> with observed evidence; do not infer or invent values. Script thresholds are
> targets, not results.

## 1. Test identity

| Field | Value |
| --- | --- |
| Test run ID | TBD |
| Date/time and timezone | TBD |
| Operator | TBD |
| Git commit / branch | TBD |
| Change under test | TBD |
| Baseline or optimized | TBD |
| k6 version | TBD |
| Raw k6 summary path / checksum | TBD |
| Dashboard or metric snapshot links | TBD |

## 2. Environment and topology

| Component | Version/image | CPU limit | Memory limit | Instances | Placement/notes |
| --- | --- | ---: | ---: | ---: | --- |
| Load generator | TBD | TBD | TBD | TBD | TBD |
| Spring Boot backend | TBD | TBD | TBD | TBD | TBD |
| Python worker | TBD | TBD | TBD | TBD | TBD |
| MySQL | TBD | TBD | TBD | TBD | TBD |
| Redis | TBD | TBD | TBD | TBD | TBD |
| Model service/fixture | TBD | TBD | TBD | TBD | TBD |

Record JVM version/flags, heap, GC algorithm, server thread settings, Hikari
pool size, worker concurrency, Redis persistence, MySQL buffer pool, and network
latency:

```text
TBD
```

## 3. Dataset

| Table/domain | Row count before | Row count after | Distribution/notes |
| --- | ---: | ---: | --- |
| project | TBD | TBD | TBD |
| workflow_run | TBD | TBD | TBD |
| workflow_node_run | TBD | TBD | TBD |
| artifact | TBD | TBD | TBD |
| agent_event | TBD | TBD | TBD |
| model_invocation | TBD | TBD | TBD |

Prepared project ID(s), workflow version, first/deep offsets, data-generation
command, and verification query output:

```text
TBD
```

## 4. Workload

| Field | Value |
| --- | --- |
| Script | TBD |
| Exact command (secrets removed) | TBD |
| Scenario/executor | TBD |
| VUs or arrival rate | TBD |
| Ramp / warm-up | TBD |
| Steady-state duration | TBD |
| Cool-down | TBD |
| Page limit / offset | TBD |
| Workflow concurrency / poll interval | TBD |
| Request mix | TBD |

## 5. Targets

Copy the applicable script targets here before running.

| Endpoint/metric | P95 target | P99 target | Error/timeout target |
| --- | ---: | ---: | ---: |
| Health/project smoke | < 500 ms | < 1,000 ms | < 1% |
| Primary read endpoints | < 300 ms | < 800 ms | < 1% |
| Project create | < 500 ms | < 1,200 ms | < 1% |
| Workflow admission | < 800 ms | < 2,000 ms | < 1% |
| Workflow status read | < 300 ms | < 800 ms | < 1% |
| Workflow terminal completion | < 300,000 ms | < 600,000 ms | failures/timeouts < 1% |

## 6. Observed client results

Use the steady-state window. Add one row per k6 endpoint tag.

| Endpoint/metric | Count | QPS | P50 | P95 | P99 | Max | Error rate | Target pass? |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Error status/body summary (sanitized):

| HTTP/error class | Count | Percentage | Representative correlation ID | Notes |
| --- | ---: | ---: | --- | --- |
| TBD | TBD | TBD | TBD | TBD |

## 7. Server-side observations

| Signal | Idle | Peak | Steady-state P95/max | Evidence |
| --- | ---: | ---: | ---: | --- |
| Backend CPU | TBD | TBD | TBD | TBD |
| Backend RSS / JVM heap | TBD | TBD | TBD | TBD |
| GC pause / allocation rate | TBD | TBD | TBD | TBD |
| Request threads active/queued | TBD | TBD | TBD | TBD |
| Hikari active/pending/max | TBD | TBD | TBD | TBD |
| MySQL CPU / connections | TBD | TBD | TBD | TBD |
| MySQL rows examined / slow queries | TBD | TBD | TBD | TBD |
| Redis CPU / memory | TBD | TBD | TBD | TBD |
| Stream length / pending / oldest pending age | TBD | TBD | TBD | TBD |
| Outbox pending / oldest age | TBD | TBD | TBD | TBD |
| Worker active/available | TBD | TBD | TBD | TBD |
| Model latency/error/rate-limit | TBD | TBD | TBD | TBD |

## 8. SQL evidence

| Query | Dataset/offset | Plan file | Actual time | Rows examined | Key/index | Notes |
| --- | --- | --- | ---: | ---: | --- | --- |
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Attach `EXPLAIN ANALYZE` before and after output. Note that `EXPLAIN ANALYZE`
executes the query.

## 9. Bottleneck and conclusion

First saturated resource and supporting evidence:

```text
TBD
```

Capacity boundary supported by this run:

```text
TBD
```

Do not generalize beyond the recorded topology, dataset, request mix, and
duration.

## 10. Optimization comparison

| Metric | Before | After | Delta | Same workload confirmed? |
| --- | ---: | ---: | ---: | --- |
| QPS | TBD | TBD | TBD | TBD |
| P95 | TBD | TBD | TBD | TBD |
| P99 | TBD | TBD | TBD | TBD |
| Error rate | TBD | TBD | TBD | TBD |
| Rows examined | TBD | TBD | TBD | TBD |
| CPU/connection/GC bottleneck | TBD | TBD | TBD | TBD |

Optimization hypothesis, implementation, and why the evidence supports or
rejects it:

```text
TBD
```

## 11. Reproduction and follow-up

1. TBD

Known limitations, invalidated samples, and next test:

```text
TBD
```
