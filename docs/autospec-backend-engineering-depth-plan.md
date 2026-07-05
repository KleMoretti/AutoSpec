# AutoSpec Backend Engineering Depth Plan

> Seed migration: `backend/src/main/resources/db/migration/V5__backend_engineering_depth_plan_seed.sql`
> Progress update migrations: `backend/src/main/resources/db/migration/V23__backend_engineering_depth_plan_progress_update.sql`, `backend/src/main/resources/db/migration/V24__backend_engineering_depth_plan_pagination_contract.sql`, `backend/src/main/resources/db/migration/V25__backend_engineering_depth_plan_openapi_contract.sql`, `backend/src/main/resources/db/migration/V26__backend_engineering_depth_plan_openapi_endpoint.sql`, `backend/src/main/resources/db/migration/V27__backend_engineering_depth_plan_diagnostics_endpoint.sql`, `backend/src/main/resources/db/migration/V28__backend_engineering_depth_plan_agent_task_diagnostics.sql`, `backend/src/main/resources/db/migration/V29__backend_engineering_depth_plan_failure_diagnostics.sql`, `backend/src/main/resources/db/migration/V30__backend_engineering_depth_plan_model_invocation_diagnostics.sql`, `backend/src/main/resources/db/migration/V31__backend_engineering_depth_plan_code_generation_job_diagnostics.sql`

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

Updated on `2026-07-05`.

| ID | Status | Evidence |
| --- | --- | --- |
| BE-01 | PARTIAL | Backend bounded contexts, service contracts, layer ownership, transaction rules, and DTO ownership rules are documented in `docs/backend-service-contracts.md`. |
| BE-02 | PARTIAL | `workflow_run` table in `V6__workflow_run_idempotency.sql`, V4 generation `Idempotency-Key` handling, duplicate request and failed-run regressions in `ProjectControllerTest`; Markdown/PDF export persistence and `PROJECT_EXPORTED` audit writes share one transaction, with rollback coverage in `ExportTransactionTest` when audit recording fails. |
| BE-03 | PARTIAL | Code skeleton generation persists `code_generation_job.status = FAILED`, `error_message`, and `completed_at` when generation fails, and avoids writing an `export_file`; `GET /api/projects/{projectId}/code-generation-jobs` exposes durable job state; `POST /api/projects/{projectId}/code-generation-jobs/{jobId}/cancel` moves RUNNING jobs to `CANCELLED` with `cancelled_at` and `completed_at`; `POST /api/projects/{projectId}/code-generation-jobs/{jobId}/retry` creates a new generation job for FAILED jobs and records `retry_of_job_id` lineage; stale RUNNING code generation jobs can be marked `FAILED` through `CodeGenerationJobService.timeoutRunningJobsBefore`; project diagnostics now summarizes code generation job total/RUNNING/FAILED/CANCELLED counts and latest failed job error; Markdown/PDF export responses are persisted to `export_file`, `GET /api/projects/{projectId}/exports` exposes durable export history metadata, and `GET /api/projects/{projectId}/exports/{exportFileId}` retrieves stored export content for authorized project members; covered by `CodeSkeletonServiceTest`, `ProjectControllerTest`, and `BackendDiagnosticsControllerTest`. |
| BE-04 | PARTIAL | Failed V4 Agent Engine calls persist `workflow_run.status = FAILED`, `error_message`, and `completed_at`; project status is moved to `FAILED`; project members can query run history through `GET /api/projects/{projectId}/workflow-runs`; V4 workflow runs assign a `correlationId` that is persisted and exposed across workflow run, audit event, external call, and model invocation history; `audit_event` records V4 workflow run started, completed, and failed transitions, plus `PROJECT_EXPORTED` user actions linked to the generated `EXPORT_FILE`, and is exposed through bounded `GET /api/projects/{projectId}/audit-events`; export file persistence is rolled back when the linked audit event cannot be recorded; Agent node events are exposed through bounded `GET /api/projects/{projectId}/events/history`; model invocation telemetry is exposed through bounded `GET /api/projects/{projectId}/model-invocations` and includes `workflowRunId` for idempotent V4 runs; `external_call_log` records V4 Agent Engine success/failure, latency, request context, and error cause, exposed through bounded `GET /api/projects/{projectId}/external-calls`; `GET /api/projects/{projectId}/diagnostics` summarizes workflow, `agent_task`, audit, external call, model telemetry, and code generation job counts plus latest workflow/correlation identifiers, latest failed Agent task node/error, latest failed external call error/duration, latest failed model invocation node/model/duration/error, and latest failed code generation job error, covered by `BackendDiagnosticsControllerTest`. |
| BE-05 | PARTIAL | Cross-project non-member access is denied for artifacts, artifact version history, Markdown export, export file content, workflow run history, audit events, review issues, model invocations, external call logs, code skeleton generation, code generation cancellation, code generation retry, and failed task retry in `ProjectControllerTest`; export file detail lookup is scoped by `(project_id, id)` and returns `NOT_FOUND` when a member requests another project's export file ID through their own project; role matrix regression proves `VIEWER` members can read project artifacts and stored export content but cannot export, edit artifacts, or trigger V4 generation, while `EDITOR` members can export. |
| BE-06 | PARTIAL | `ApiErrorResponse`, `GlobalExceptionHandler`, validation and `ResponseStatusException` regression tests in `ApiErrorResponseTest`; `PaginationRequest` centralizes REST list parameter defaults and validation while preserving stable BAD_REQUEST messages, covered by `PaginationRequestTest`; `contracts/autospec-backend-v1.openapi.yaml` packages a machine-readable OpenAPI 3.0.3 contract for core project, V4 generation, diagnostics, artifact list, export, and error/pagination schemas, including `ProjectDiagnosticsResponse.agentTaskCount`, `ProjectDiagnosticsResponse.failedAgentTaskCount`, latest failure fields, latest external-call latency, latest model invocation failure diagnostics, and code generation job diagnostics, covered by `BackendApiContractTest`; `GET /api/contracts/openapi` serves that contract as `application/yaml` without a session for tooling, CI, and review, covered by `BackendApiContractEndpointTest`. |
| BE-07 | PARTIAL | Artifact history and artifact version history, workflow run history, audit event history, Agent event history, review issue history, model invocation history, code generation job history, export file history, and external call history are bounded with `limit` and `offset` query parameters, reject invalid pagination input with the stable error model, keep large export content out of history lists while exposing it through a project-scoped detail endpoint, and now share the same `PaginationRequest` query-boundary contract; covered by `ProjectControllerTest` and `PaginationRequestTest`. |
| BE-08 | PARTIAL | Flyway migration coverage for `workflow_run`, `audit_event`, `external_call_log`, `code_generation_job.cancelled_at`, `code_generation_job.retry_of_job_id`, `model_invocation.workflow_run_id`, workflow/audit/external/model `correlation_id` columns, and the `artifact(project_id, id)` / `artifact(project_id, type, version)` / `audit_event(project_id, id)` / `audit_event(project_id, correlation_id)` / `review_issue(project_id, id)` / `agent_event(project_id, id)` / `model_invocation(project_id, id)` / `model_invocation(workflow_run_id)` / `model_invocation(project_id, correlation_id)` / `code_generation_job(project_id, id)` / `code_generation_job(status, created_at)` / `export_file(project_id, id)` / `external_call_log(project_id, id)` / `external_call_log(project_id, correlation_id)` / `workflow_run(correlation_id)` history, recovery, and trace indexes in `SchemaInitSqlTest`; `HttpAgentEngineClientTest` uses a local HTTP server stub to verify V4 Agent Engine request/response mapping without external network access. |

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
