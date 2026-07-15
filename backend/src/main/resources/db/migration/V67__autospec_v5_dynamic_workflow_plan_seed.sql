insert into project (user_id, name, original_requirement, status)
select 1,
       'AutoSpec V5 Dynamic Workflow Plan',
       'AutoSpec V5 configuration-driven production workflow roadmap',
       'PLANNED'
where not exists (
    select 1 from project where name = 'AutoSpec V5 Dynamic Workflow Plan'
);

insert into artifact (project_id, type, title, content, format, version, status, source_agent)
select p.id,
       'ROADMAP_PLAN',
       'AutoSpec V5 Dynamic Workflow Plan',
       '# AutoSpec V5 Dynamic Workflow Plan

Canonical design: docs/superpowers/specs/2026-07-11-autospec-v5-dynamic-workflow-design.md

Canonical implementation plan: docs/superpowers/plans/2026-07-11-autospec-v5-dynamic-workflow-implementation-plan.md

Objective: replace hard-coded Agent sequencing with a versioned production DAG runtime controlled by Spring Boot, transported through Redis Streams, and executed by horizontally scalable Python workers.

Milestones:
- M1 WorkflowSpec V5, immutable versions, DAG compilation, and dynamic scheduling
- M2 Redis Streams commands and events with parallel Python workers
- M3 timeout, retry, fallback, heartbeat leases, and checkpoint recovery
- M4 conditional routing and Reviewer targeted rework
- M5 configurable before-node and after-node human approval
- M6 immutable workflow replay, comparison, failure tests, and resume-grade evidence

Acceptance criteria:
- Runtime order is derived from the frozen WorkflowSpec rather than hard-coded orchestration.
- Independent nodes execute concurrently within configured limits.
- At-least-once Redis delivery remains safe through Outbox and idempotent state transitions.
- Worker and control-plane restarts recover from durable MySQL checkpoints.
- Reviewer targeted rework invalidates only the selected node and affected downstream nodes.
- Approval can pause and resume any configured node without losing execution state.
- Historical workflow replay creates an independent run from immutable snapshots.
- Existing V1-V4 behavior remains compatible.',
       'MARKDOWN',
       5,
       'APPROVED',
       'SYSTEM_PLANNER'
from project p
where p.name = 'AutoSpec V5 Dynamic Workflow Plan'
  and not exists (
      select 1
      from artifact a
      where a.project_id = p.id
        and a.type = 'ROADMAP_PLAN'
        and a.version = 5
  );
