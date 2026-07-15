# AutoSpec V5 Dynamic Workflow Plan

> Canonical design: `docs/superpowers/specs/2026-07-11-autospec-v5-dynamic-workflow-design.md`
>
> Canonical implementation plan: `docs/superpowers/plans/2026-07-11-autospec-v5-dynamic-workflow-implementation-plan.md`

## Database Record

- Project: `AutoSpec V5 Dynamic Workflow Plan`
- Artifact type: `ROADMAP_PLAN`
- Artifact version: `5`
- Artifact status: `APPROVED`
- Seed migration: `backend/src/main/resources/db/migration/V67__autospec_v5_dynamic_workflow_plan_seed.sql`

## Objective

Replace hard-coded Agent sequencing with a versioned production DAG runtime. Spring Boot owns orchestration and durable state, Redis Streams carries commands and events, and horizontally scalable Python workers execute individual Agent nodes.

## Milestones

| Milestone | Scope | Exit criterion |
| --- | --- | --- |
| M1 | WorkflowSpec V5, immutable versions, DAG compilation, dynamic scheduling | Changing node dependencies requires no orchestration code change |
| M2 | Redis Streams, Outbox, Consumer Groups, parallel Python workers | Independent nodes run concurrently on separate workers |
| M3 | Timeout, retry, fallback, leases, checkpoint recovery | Worker and service restarts resume without duplicate valid output |
| M4 | Conditions and Reviewer targeted rework | Only the target and affected downstream nodes rerun |
| M5 | Configurable before/after approval | Approval survives restart and resumes the original run |
| M6 | Immutable replay, comparison, failure tests, documentation | A historical snapshot creates an independent traceable replay |

## Acceptance Criteria

- Runtime order is derived from the frozen WorkflowSpec.
- Independent nodes execute concurrently within configured limits.
- Redis delivers at least once; Outbox and idempotent transitions prevent duplicate valid state.
- MySQL checkpoints support recovery after Worker, Redis, or control-plane interruption.
- Reviewer targeted rework is bounded and preserves unaffected branches.
- Any configured node can pause before or after execution for human approval.
- Workflow replay never overwrites the original run or artifacts.
- Existing V1-V4 endpoints and regression tests remain compatible.

## Completion Evidence

V5 implementation is complete on branch `codex/autospec-v5-dynamic-workflow`. The control-plane lifecycle regression, focused failure tests, all Agent tests, frontend tests/build, and Compose validation form the release gate. A sanitized trace and metric interpretation are available in `docs/examples/v5-dynamic-workflow-run.md`.
