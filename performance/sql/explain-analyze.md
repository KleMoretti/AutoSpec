# MySQL `EXPLAIN ANALYZE` capture guide

Use this guide with the 100k and 1m synthetic profiles. It records query-plan
evidence; it does not claim that an index or pagination change is faster until
the same workload has been measured before and after.

`EXPLAIN ANALYZE` executes the query. Run only `SELECT` statements on the
isolated performance schema and never use it for a mutating statement.

## Capture protocol

1. Record MySQL version and relevant configuration.
2. Set real IDs printed by the dataset generator.
3. Confirm table row counts and index definitions.
4. Run the plain query once as warm-up.
5. Capture `EXPLAIN ANALYZE` at first, middle, and deep offsets.
6. Repeat at least five times under an otherwise idle database. Preserve every
   plan; report the median execution time rather than selecting the best run.
7. Capture the same plans during the load test if the goal is contention
   analysis, but label idle and loaded evidence separately.
8. After an optimization, restore the same dataset snapshot and repeat with the
   same IDs, offsets, buffer-pool state policy, and MySQL configuration.

Initial context:

```sql
select version() as mysql_version, database() as schema_name, now() as captured_at;
show variables where Variable_name in (
    'innodb_buffer_pool_size',
    'max_connections',
    'optimizer_switch',
    'performance_schema',
    'slow_query_log',
    'long_query_time'
);

set @user_id = <owner-user-id>;
set @project_id = <anchor-project-id>;
set @workflow_run_id = <generated-workflow-run-id>;
set @page_size = 100;
set @first_offset = 0;
set @middle_offset = 50000;  -- 500000 for the 1m profile
set @deep_offset = 99900;    -- 999900 for the 1m profile
```

Save index evidence:

```sql
show index from project;
show index from project_member;
show index from workflow_run;
show index from workflow_node_run;
show index from artifact;
show index from agent_event;
show index from model_invocation;
```

## Project visibility list

This mirrors `ProjectMapper.selectVisibleProjects`.

```sql
explain analyze
select distinct p.*
from project p
left join project_member pm on pm.project_id = p.id
where p.user_id = @user_id
   or pm.user_id = @user_id
order by p.id desc
limit @page_size offset @deep_offset;
```

Capture the first and middle offsets by changing only the offset value. Pay
attention to actual rows flowing into the sort/deduplication and whether the
`OR` prevents efficient use of the owner/member indexes.

## Workflow run history

This mirrors `GET /api/projects/{projectId}/workflow-runs`.

```sql
explain analyze
select *
from workflow_run
where project_id = @project_id
order by id asc
limit @page_size offset @deep_offset;
```

Also capture a keyset-shaped comparison without changing production code:

```sql
set @last_seen_id = (
    select id
    from workflow_run
    where project_id = @project_id
    order by id asc
    limit 1 offset @deep_offset
);

explain analyze
select *
from workflow_run
where project_id = @project_id
  and id >= @last_seen_id
order by id asc
limit @page_size;
```

The lookup used to obtain `@last_seen_id` is not free and must not be omitted
from an end-to-end API comparison. It is included only to expose the plan shape
expected when a cursor comes from the preceding page.

## Artifact, event, and model histories

```sql
explain analyze
select *
from artifact
where project_id = @project_id
order by id asc
limit @page_size offset @deep_offset;

explain analyze
select *
from agent_event
where project_id = @project_id
order by id asc
limit @page_size offset @deep_offset;

explain analyze
select *
from model_invocation
where project_id = @project_id
order by id asc
limit @page_size offset @deep_offset;
```

For artifact version history, keep the secondary ordering:

```sql
explain analyze
select *
from artifact
where project_id = @project_id
  and type = 'PERF_SYNTHETIC'
order by version asc, id asc
limit @page_size offset @deep_offset;
```

## Node history and diagnostics

```sql
explain analyze
select *
from workflow_node_run
where workflow_run_id = @workflow_run_id
order by node_id asc, revision asc, attempt asc;

explain analyze
select count(*)
from workflow_run
where project_id = @project_id;

explain analyze
select count(*)
from model_invocation
where project_id = @project_id
  and status = 'FAILED';

explain analyze
select *
from workflow_run
where project_id = @project_id
  and status = 'FAILED'
order by id desc
limit 1;
```

The generated dataset has successful workflow/model rows by default, so the
`FAILED` queries should be reported as an empty-result plan, not presented as a
representative failed-row lookup. Use a separately documented distribution if
failed-history behavior is the subject of the test.

## Slow-query evidence

Do not enable global slow logging from an ad hoc load session. Have the
environment owner configure a bounded log destination, retention, redaction,
and `long_query_time` before the run. Record the configuration and export only
the matching test window. At minimum, correlate:

- normalized query/digest
- count
- total and P95/P99 latency where available
- rows examined versus rows sent
- temporary table/filesort indicators
- connection and lock wait time

## Before/after acceptance

An optimization claim needs all of the following:

- identical commit-independent workload inputs and dataset snapshot
- raw plans showing actual time, loops, and rows
- k6 QPS/P95/P99/error results for both versions
- server resource metrics over the same steady-state duration
- no regression at first-page and moderate offsets
- an explanation of changed semantics, especially if offset pagination becomes
  cursor pagination

Copy the evidence paths and numeric observations into the performance report.
Leave fields as `TBD` until a real run supplies them.
