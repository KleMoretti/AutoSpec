# AutoSpec Backend Service Contracts

Updated on `2026-07-03`.

## Purpose

This document defines backend ownership boundaries for AutoSpec. The backend is the system of record for projects, membership, artifacts, workflow state, Agent execution traces, model invocation telemetry, exports, and API contracts. The Python Agent Engine remains an external execution boundary; it returns typed generation results, while the Spring Boot backend owns persistence, authorization, idempotency, state transitions, and user-facing REST contracts.

## Layer Ownership

| Layer | Owns | Must Not Own |
| --- | --- | --- |
| `controller` | HTTP routing, request validation, session token extraction, role checks, DTO mapping. | Database query composition for complex workflows, cross-aggregate state changes, Agent Engine calls. |
| `service` | Domain workflows, transaction boundaries, state transitions, idempotency, external client calls, persistence orchestration. | HTTP response formatting or controller-specific JSON shapes. |
| `entity` | Table-aligned persistence model and MyBatis-Plus mapping. | Public API compatibility or frontend field naming. |
| `dto` | REST request/response contracts and stable API field names. | Persistence-only fields or database implementation details. |
| `mapper` | MyBatis-Plus persistence access. | Business rules, authorization decisions, or transaction policy. |

## Bounded Contexts

| Context | Primary Tables | Primary Services | Boundary Rule |
| --- | --- | --- | --- |
| Identity and membership | `user_account`, `project_member` | `AuthService`, `ProjectAccessService`, `ProjectMemberService` | Every project-scoped controller resolves a session user and checks owner/editor/viewer roles before reading or mutating project data. |
| Project lifecycle | `project` | `ProjectService`, `AgentOrchestrationService` | Project status is changed only through workflow services that know the related Agent or artifact state. |
| Artifact lifecycle | `artifact`, `knowledge_document`, `knowledge_chunk` | `ArtifactService`, `ArtifactVersionService`, `KnowledgeIndexService` | Artifact approval, versioning, and knowledge indexing are coupled through service methods, not controller-side updates. |
| Workflow orchestration | `workflow_run`, `workflow_snapshot` | `AgentOrchestrationService`, `WorkflowRunService`, `WorkflowSnapshotService` | Generation APIs must create durable workflow state for idempotency, failure inspection, and run history. |
| Agent execution trace | `agent_task`, `agent_event` | `AgentTaskService`, `AgentEventService`, `AgentEventStreamService` | Node status, retry lineage, event history, and SSE output all come from persisted task/event records. |
| Model governance | `model_provider`, `model_config`, `model_invocation`, `prompt_version` | `ModelInvocationService`, `PromptRegistryService`, `PromptVersionService` | Prompt/model metadata is recorded per Agent task so runs are explainable after generation. |
| Review and evaluation | `review_issue`, `artifact` | `ReviewIssueService`, `AgentOrchestrationService` | Reviewer and evaluator outputs are stored as structured artifacts plus queryable issue records. |
| Export and code generation | `code_generation_job`, `export_file` | `MarkdownExportService`, `PdfExportService`, `CodeSkeletonService` | Export endpoints return DTOs, while generated files and job metadata are persisted for audit and repeatability. |

## Service Contracts

| Service | Contract | Transaction and Failure Notes |
| --- | --- | --- |
| `ProjectAccessService` | Resolve session users, add owner membership, and enforce role checks for project-scoped operations. | Throws `401` for missing/invalid sessions and `403` for non-member access. Must be called before project data leaves the controller. |
| `AgentOrchestrationService` | Run generation, PRD-only generation, continuation, V4 generation, progress lookup, review score lookup, and failed task retry. | Owns project status changes, artifact writes, Agent task/event writes, model invocation records, and workflow run failure state. |
| `WorkflowRunService` | Persist and list workflow runs scoped by project. | Query methods must stay project-scoped and ordered deterministically for audit views. |
| `ArtifactVersionService` | Update draft artifacts, approve artifacts, and resolve latest approved artifacts. | Approval triggers knowledge indexing through service coordination. |
| `KnowledgeIndexService` | Index approved artifacts and retrieve project-owner-visible context for Agent Engine calls. | Retrieval must be scoped to the project owner or project membership model; cross-tenant context leakage is forbidden. |
| `ModelInvocationService` | Store model invocation records linked to project/task/prompt metadata. | Read APIs must be project-role guarded before returning invocation telemetry. |
| `MarkdownExportService` and `PdfExportService` | Render persisted artifacts into exportable user-facing documents. | Export controllers must authorize project access before invoking render services. |
| `CodeSkeletonService` | Persist a code generation job, create a skeleton ZIP, and persist the generated export file. | Job state starts as `RUNNING` and must end in a terminal state when async reliability work is completed. |
| `HttpAgentEngineClient` | Translate backend workflow requests into Agent Engine HTTP calls. | The backend treats this as an unreliable external boundary; failures must be captured in workflow or task state. |

## DTO Ownership Rules

- Controllers return DTOs from `com.autospec.dto`; entities are not returned directly from REST endpoints.
- Request DTOs own validation annotations and client-facing required fields.
- Response DTOs expose stable API names even if entity/table fields change.
- DTO factories such as `from(entity)` may map simple read models, but business decisions stay in services.
- Error responses use `ApiErrorResponse` through `GlobalExceptionHandler`; controllers should throw typed exceptions instead of shaping error JSON manually.
- New API versions should add DTOs or endpoint variants rather than changing existing response semantics silently.

## Transaction and Observability Rules

- Multi-table workflow operations belong in services with explicit transaction boundaries.
- Idempotent operations must persist a durable key before invoking external work.
- External Agent Engine calls must record status, latency or duration, prompt/model metadata where available, and failure reason.
- Retry operations must preserve lineage through `retry_of_task_id`.
- Long-running or generated-file operations should persist job records before creating or returning binary payloads.

## Verification Rules

- Project-scoped APIs need negative tests for missing sessions and cross-project non-member access.
- Workflow operations need duplicate request, failed external call, and state transition tests.
- Migration tests must verify new tables and important query columns in an H2 MySQL-compatible profile.
- Generated artifacts and docs must not contain API keys, model secrets, database passwords, or local absolute paths.
