# Performance dataset preparation

The generator creates deterministic **synthetic history**, not benchmark
results. It is intended only for a disposable MySQL schema that has already
been migrated by the current backend.

## Generated shape

`-HistoryRows N` creates `N` rows in each of these tables for one anchor
project:

- `workflow_run`
- `workflow_node_run` (one node per generated run)
- `artifact`
- `agent_event`
- `model_invocation`

Therefore, `-HistoryRows 1000000` creates five million history rows in total,
not one million total. `-ProjectRows P` independently creates `P` visible
projects for the selected owner, including the anchor project. Only the anchor
has a `project_member` row because it is the project used by authenticated
detail/history requests; the other synthetic projects remain visible through
their `project.user_id`.

All generated names, idempotency keys, and execution IDs include `-RunId`.
Reusing a run ID is rejected rather than appending an ambiguous second dataset.
Commits happen in bounded batches. If generation fails partway through, treat
the schema as tainted and recreate the disposable database instead of claiming
the requested row count.

Fixture telemetry is deliberately non-observational: generated model duration,
token, and cost fields are zero (and score is null), workflow timestamps do not
claim a real execution duration, and records are labeled `PERF_FIXTURE`,
`PERF_SYNTHETIC`, or provider `fixture`. Exclude them from any measured model or
workflow latency claim.

## Preconditions

1. Create a dedicated schema such as `autospec_perf_100k`; never reuse
   production, staging, or a shared developer schema.
2. Run the backend once so Flyway applies every migration.
3. Log in once so the intended owner account exists.
4. Stop application writes or point the backend away while direct fixture
   insertion runs.
5. Take a disposable snapshot after generation if repeated comparable runs are
   needed.
6. Ensure free disk and transaction-log capacity. Storage depends on MySQL
   settings and text/index overhead, so measure it rather than relying on a
   guessed estimate.
7. Use a database user scoped to this performance schema. The wrapper requires
   `MYSQL_PWD` and never puts the password on the command line.

The wrapper is dry-run by default. Execution requires both `-Execute` and an
exact `-Confirmation` value naming the target database. Both the wrapper and
SQL routine also require the database name to contain `perf`.

## 100,000-row-per-history-table profile

This profile also prepares 10,000 projects for project-list pagination.

```powershell
$env:MYSQL_PWD = '<performance-schema-password>'
.\performance\data\generate-history-data.ps1 `
  -HostName 'localhost' `
  -Port 3306 `
  -Database 'autospec_perf_100k' `
  -UserName 'autospec_perf' `
  -OwnerUsername 'owner' `
  -RunId 'history-100k-20260716-a' `
  -HistoryRows 100000 `
  -ProjectRows 10000 `
  -BatchSize 5000
```

Review the dry-run output, then repeat with:

```powershell
  -Execute `
  -Confirmation 'AUTOSPEC_PERFORMANCE_ONLY:autospec_perf_100k'
```

Use offsets `0`, `50000`, and `99900` with page size 100 as separate read
samples.

## 1,000,000-row-per-history-table profile

Build this in a separate schema so the 100k and 1m profiles remain comparable.
The project-list population below is 100,000; the five history tables each
receive 1,000,000 rows.

```powershell
$env:MYSQL_PWD = '<performance-schema-password>'
.\performance\data\generate-history-data.ps1 `
  -HostName 'localhost' `
  -Port 3306 `
  -Database 'autospec_perf_1m' `
  -UserName 'autospec_perf' `
  -OwnerUsername 'owner' `
  -RunId 'history-1m-20260716-a' `
  -HistoryRows 1000000 `
  -ProjectRows 100000 `
  -BatchSize 10000
```

After checking the target and capacity, repeat with:

```powershell
  -Execute `
  -Confirmation 'AUTOSPEC_PERFORMANCE_ONLY:autospec_perf_1m'
```

Clear the password environment variable after generation:

```powershell
Remove-Item Env:MYSQL_PWD
```

Use offsets `0`, `500000`, and `999900` with page size 100 as separate read
samples.

## Verification

The SQL script prints the anchor `project_id`, min/max workflow run IDs, and
row counts. Save that output with the report. Verify independently before load:

```sql
select id, user_id, name
from project
where name = '__AUTOSPEC_PERF_history-100k-20260716-a__';

set @project_id = <printed-anchor-project-id>;

select 'workflow_run' as table_name, count(*) as row_count
from workflow_run where project_id = @project_id
union all
select 'workflow_node_run', count(*)
from workflow_node_run n
join workflow_run r on r.id = n.workflow_run_id
where r.project_id = @project_id
union all
select 'artifact', count(*) from artifact where project_id = @project_id
union all
select 'agent_event', count(*) from agent_event where project_id = @project_id
union all
select 'model_invocation', count(*) from model_invocation where project_id = @project_id;
```

Set the printed anchor ID as `AUTOSPEC_PROJECT_ID`. Set a printed workflow run
ID as `AUTOSPEC_WORKFLOW_RUN_ID` when exercising node-history reads.

## Reset policy

There is deliberately no broad delete script. Foreign keys and later schema
changes make piecemeal cleanup easy to get wrong, and a delete of millions of
rows can create more load than generation. Drop and recreate only the dedicated
performance schema using the environment's approved database procedure, then
let Flyway migrate it again.
