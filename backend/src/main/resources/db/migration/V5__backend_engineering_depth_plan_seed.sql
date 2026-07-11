insert into project (user_id, name, original_requirement, status)
select 1,
       'AutoSpec Backend Engineering Depth Plan',
       'AutoSpec backend engineering depth roadmap',
       'PLANNED'
where not exists (
    select 1 from project where name = 'AutoSpec Backend Engineering Depth Plan'
);

insert into artifact (project_id, type, title, content, format, version, status, source_agent)
select p.id,
       'ROADMAP_PLAN',
       'AutoSpec Backend Engineering Depth Plan',
       '# AutoSpec Backend Engineering Depth Plan

Source doc: docs/autospec-backend-engineering-depth-plan.md

Objective: upgrade AutoSpec backend work from feature delivery into resume-grade backend engineering, with clear domain boundaries, transactional orchestration, reliable async execution, observability, permission-safe data access, repeatable database evolution, and integration tests.

Positioning:
AutoSpec backend is a production-oriented orchestration service for multi-agent software generation, with transactional state management, idempotent workflow execution, RBAC, audit trails, model invocation tracking, artifact versioning, and integration tests across persistence, external Agent Engine calls, and export workflows.

Scope:
- BE-01 Domain Boundary and Service Contracts
- BE-02 Transactional Orchestration and Idempotency
- BE-03 Async Job Reliability
- BE-04 Observability and Audit Trail
- BE-05 Security and Multi-tenant Authorization
- BE-06 API Contract and Error Model
- BE-07 Persistence Performance and Data Lifecycle
- BE-08 Integration Test Depth

Milestones:
- M1 Backend Contract Baseline: service boundaries and REST contracts are documented and tested.
- M2 Reliable Workflow Execution: duplicate requests, retries, timeouts, and partial Agent Engine failures are handled.
- M3 Production Diagnostics: every generation run can be traced by project, workflow, task, model invocation, latency, status, and error cause.
- M4 Secure Multi-tenant Access: unauthorized cross-project reads, writes, exports, and retries are rejected by tests.
- M5 Persistence and CI Readiness: migrations, query paths, and external-call integrations are verified by repeatable integration tests.

Acceptance criteria:
- Backend workflow operations have explicit transaction boundaries and idempotency behavior.
- Long-running Agent and export operations are represented by durable state, not only synchronous controller calls.
- Every external Agent Engine call records status, latency, request context, and failure cause.
- Project-scoped APIs consistently enforce owner/editor/viewer authorization.
- API errors use a stable response shape with machine-readable codes.
- Flyway migrations remain repeatable in H2/MySQL-compatible tests.
- Testcontainers or equivalent integration tests cover persistence plus external service boundaries.
- No secrets or local absolute paths are stored in generated artifacts.',
       'MARKDOWN',
       5,
       'APPROVED',
       'SYSTEM_PLANNER'
from project p
where p.name = 'AutoSpec Backend Engineering Depth Plan'
  and not exists (
      select 1
      from artifact a
      where a.project_id = p.id
        and a.type = 'ROADMAP_PLAN'
        and a.version = 5
  );
