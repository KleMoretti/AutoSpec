# AutoSpec Backend Service Contracts

## Ownership

Spring Boot is the control plane and MySQL is the source of truth. Python workers execute registered Agent handlers; Redis Streams only transport commands and events.

| Context | Current records | Owner |
| --- | --- | --- |
| Identity and access | `user_account`, `user_session`, `project_member` | `AuthService`, `ProjectAccessService` |
| Project and artifacts | `project`, `artifact`, `artifact_component`, `artifact_trace_edge` | Project, artifact version, trace graph and knowledge services |
| Workflow control | `workflow_definition`, `workflow_version`, `workflow_run`, `workflow_node_run`, `workflow_transition`, `workflow_approval` | Workflow version, run, reconciliation, approval, recovery and replay services |
| Reliable transport | `workflow_outbox`, `processed_workflow_event`, `workflow_event_dead_letter` | Outbox publisher, Redis transport and idempotent event consumer |
| Model and knowledge | `model_provider`, `model_config`, `model_invocation`, `prompt_version`, `knowledge_document`, `knowledge_chunk` | Model governance, prompt registry and project-scoped retrieval services |
| Quality and delivery | `review_issue`, `code_generation_job`, `export_file` | Reviewer projection, delivery gate, code generation and export services |
| Audit and diagnostics | `audit_event` plus current workflow/model/job records | Audit, metrics and project diagnostics services |

## Boundary rules

- Controllers validate HTTP input, resolve the session and enforce project roles before returning project data.
- Services own transactions and state transitions; controllers do not compose multi-table workflow updates.
- Published WorkflowSpecs and run snapshots are immutable. Replay creates a new run and never rewrites the original.
- Node commands are written through the transactional Outbox. Event handling is idempotent and fenced by execution identity.
- Workers validate handler input and output with Pydantic and publish terminal events; they do not schedule downstream nodes.
- Artifact approval, restoration and workflow approval use optimistic locking.
- Retrieval is project-scoped and every cited chunk remains traceable to its source version.
- Reviewer issues are queryable records, while the original Review/Evaluation report remains an immutable Artifact.
- Delivery services consult the server-side quality gate before producing Markdown, PDF or code ZIP output.
- Public request/response shapes live in DTOs and `contracts/autospec.openapi.yaml`; entities are not returned directly.

## Failure rules

- Timeouts, provider failures and invalid Schema output produce explicit node failures; retry policy is read from the frozen workflow contract.
- Duplicate Redis delivery must not create a second accepted terminal transition or Artifact.
- Cancellation closes pending work and prevents late events from projecting output.
- Exhausted Outbox publication becomes a user-visible dead letter that can be listed, replayed or closed through authorized APIs.
- Model calls record provider, route decision, prompt checksum, tokens, cost, duration, status and error.
- No API key, model secret, database password or machine-specific path may enter persisted artifacts or repository docs.

## Verification policy

- Use focused tests during implementation.
- Keep targeted coverage for authorization, idempotency, optimistic locking, recovery, delivery gates and public contracts.
- Run full Java/Python/frontend validation once at a cross-module milestone or before release.
- Preserve published Flyway migrations; remove obsolete runtime dependencies without rewriting migration history.
