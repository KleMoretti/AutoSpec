# AutoSpec Backend Engineering Depth Plan

> Seed migration: `backend/src/main/resources/db/migration/V5__backend_engineering_depth_plan_seed.sql`

## Database Record

- Project: `AutoSpec Backend Engineering Depth Plan`
- Project ID: assigned by database migration/runtime
- Artifact type: `ROADMAP_PLAN`
- Artifact version: `5`
- Artifact title: `AutoSpec Backend Engineering Depth Plan`
- Created at: `2026-07-03`

## Objective

This plan upgrades AutoSpec backend work from feature delivery into resume-grade backend engineering. The goal is to make the Spring Boot backend defensible under interviews and code review: clear domain boundaries, transactional orchestration, reliable async execution, observable Agent runs, permission-safe data access, repeatable database evolution, and regression tests that prove integration behavior.

The target positioning is:

> AutoSpec backend is a production-oriented orchestration service for multi-agent software generation, with transactional state management, idempotent workflow execution, RBAC, audit trails, model invocation tracking, artifact versioning, and integration tests across persistence, external Agent Engine calls, and export workflows.

## Scope

| ID | Title | Priority | Status | Description | Deliverables |
| --- | --- | --- | --- | --- | --- |
| BE-01 | Domain Boundary and Service Contracts | P0 | IN_PROGRESS | Clarify bounded contexts for project, artifact, workflow, agent task, review, knowledge, export, and model invocation. | package boundary notes, service contract docs, DTO ownership rules |
| BE-02 | Transactional Orchestration and Idempotency | P0 | IN_PROGRESS | Make generation, continuation, retry, and export operations safe under duplicate requests and partial failures. | transactional orchestration model, workflow run key, idempotency key, transaction boundary tests, duplicate request tests |
| BE-03 | Async Job Reliability | P0 | IN_PROGRESS | Move long-running generation and code export toward durable job records with retry, timeout, cancellation, and failure state transitions. | job state machine, retry policy, timeout handling, cancellation API, recovery tests |
| BE-04 | Observability and Audit Trail | P0 | IN_PROGRESS | Expose backend-level observability for Agent tasks, model invocations, external calls, latency, failures, and user actions. | structured logs, metrics plan, trace correlation id, audit event table or event stream |
| BE-05 | Security and Multi-tenant Authorization | P0 | IN_PROGRESS | Harden project access with consistent owner/editor/viewer checks, row-scoped retrieval, and export permission rules. | authorization matrix, negative access tests, security regression suite |
| BE-06 | API Contract and Error Model | P1 | IN_PROGRESS | Standardize REST DTO validation, error codes, pagination, OpenAPI documentation, and backward-compatible API versioning. | error response schema, validation tests, OpenAPI generation, contract examples |
| BE-07 | Persistence Performance and Data Lifecycle | P1 | IN_PROGRESS | Improve query paths for project history, artifact versions, Agent traces, review issues, and large export files. | index review, pagination, artifact content lifecycle, query performance tests |
| BE-08 | Integration Test Depth | P1 | IN_PROGRESS | Add Testcontainers, WireMock or equivalent HTTP stubs, Flyway migration checks, and end-to-end backend use-case tests. | Testcontainers profile, Agent Engine stub tests, migration tests, CI-ready test commands |

## Execution Status

Updated on `2026-07-04`.

| ID | Status | Evidence |
| --- | --- | --- |
| BE-01 | PARTIAL | Backend bounded contexts, service contracts, layer ownership, transaction rules, and DTO ownership rules are documented in `docs/backend-service-contracts.md`. |
| BE-02 | PARTIAL | `workflow_run` table in `V6__workflow_run_idempotency.sql`, V4 generation `Idempotency-Key` handling, duplicate request and failed-run regressions in `ProjectControllerTest`. |
| BE-03 | PARTIAL | Code skeleton generation persists `code_generation_job.status = FAILED`, `error_message`, and `completed_at` when generation fails, and avoids writing an `export_file`; covered by `CodeSkeletonServiceTest`. |
| BE-04 | PARTIAL | Failed V4 Agent Engine calls persist `workflow_run.status = FAILED`, `error_message`, and `completed_at`; project status is moved to `FAILED`; project members can query run history through `GET /api/projects/{projectId}/workflow-runs`. |
| BE-05 | PARTIAL | Cross-project non-member access is denied for artifacts, Markdown export, workflow run history, model invocations, code skeleton generation, and failed task retry in `ProjectControllerTest`. |
| BE-06 | PARTIAL | `ApiErrorResponse`, `GlobalExceptionHandler`, validation and `ResponseStatusException` regression tests in `ApiErrorResponseTest`. |
| BE-07 | PARTIAL | Workflow run history is bounded with `limit` and `offset` query parameters, rejects invalid pagination input with the stable error model, and is covered by `ProjectControllerTest`. |
| BE-08 | PARTIAL | Flyway migration coverage for `workflow_run` in `SchemaInitSqlTest`; `HttpAgentEngineClientTest` uses a local HTTP server stub to verify V4 Agent Engine request/response mapping without external network access. |

## Milestones

| ID | Name | Items | Exit Criteria |
| --- | --- | --- | --- |
| M1 | Backend Contract Baseline | BE-01, BE-06 | Service boundaries and external REST contracts are documented and covered by validation tests. |
| M2 | Reliable Workflow Execution | BE-02, BE-03 | Generation and export operations tolerate duplicate requests, retries, timeouts, and partial Agent Engine failures. |
| M3 | Production Diagnostics | BE-04 | Every generation run can be traced by project, workflow, task, model invocation, latency, status, and error cause. |
| M4 | Secure Multi-tenant Access | BE-05 | Unauthorized cross-project reads, writes, exports, and retries are rejected by automated tests. |
| M5 | Persistence and CI Readiness | BE-07, BE-08 | Database migrations, query paths, and external-call integrations are verified by repeatable integration tests. |

## Acceptance Criteria

- Backend workflow operations have explicit transaction boundaries and idempotency behavior.
- Long-running Agent and export operations are represented by durable state, not only synchronous controller calls.
- Every external Agent Engine call records status, latency, request context, and failure cause.
- Project-scoped APIs consistently enforce owner/editor/viewer authorization.
- API errors use a stable response shape with machine-readable codes.
- Flyway migrations remain repeatable in H2/MySQL-compatible tests.
- Testcontainers or equivalent integration tests cover persistence plus external service boundaries.
- No API keys, model secrets, database passwords, or local absolute paths are stored in generated artifacts or docs.

## Resume Emphasis

This direction supports a stronger backend resume statement:

> Designed and evolved a Spring Boot backend for a multi-agent orchestration platform with transactional workflow state management, idempotent generation APIs, durable async job tracking, RBAC authorization, audit and observability records, model invocation telemetry, Flyway-based schema evolution, and integration tests using Testcontainers and HTTP service stubs.
