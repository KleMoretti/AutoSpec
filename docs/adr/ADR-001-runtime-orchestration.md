# ADR-001: Durable workflow orchestration without LangGraph runtime

Status: Accepted · 2026-09-21

## Context

AutoSpec's formal generation entry is Spring Boot `POST /api/workflow-runs`. A run can wait for human PRD approval, outlive Worker processes, retry a node, target a rework branch, resume after a crash, replay an immutable snapshot, and gate delivery. At-least-once Redis delivery means commands and events can be duplicated or stale.

## Decision

Keep the durable Java Control Plane + Redis Streams + Python Worker architecture. Do not introduce a second LangGraph scheduler for the production workflow.

| Responsibility | Current owner |
| --- | --- |
| DAG topology, dependencies, rework edges, handler/prompt/schema/budget policy | Published, versioned WorkflowSpec and frozen execution bundle |
| Durable run/node/approval/artifact state and checkpoint | MySQL via Java Control Plane |
| Dispatch and at-least-once transport | Transactional Outbox and Redis Streams |
| Node execution, typed outputs, tool/model calls | Python Worker, Pydantic handlers and ToolHarness |
| Duplicate/stale-event safety | Event idempotency, execution id, fencing token, revision and attempt checks |
| Timeout, heartbeat, retry, dead-letter, resume and replay | Control Plane reconciliation and Worker transport |

The `v5-parallel` graph adds a frozen Architect Shared Contract before independently scheduling Backend and Frontend. Reviewer is the join and checks both outputs against that contract; Evaluator remains the final gate.

## Why not LangGraph for formal orchestration?

LangGraph can be excellent for a single-process prototype: expressing stateful branches, tool turns and local checkpoints is compact, and it helps explore Agent behavior. AutoSpec already has cross-service admission, project/user authorization, approval, MySQL artifact/version history, outbox, Redis consumer recovery, fencing, and immutable replay. Adding LangGraph as a second production state machine would create competing sources of truth for scheduling and recovery without removing those obligations. Python may use bounded Agent loops inside a node; it does not own the product DAG.

## Consequences and limits

The distributed design is more operationally complex than a single-process graph. WorkflowSpec, DB seed, Java handler catalog, Python registry, prompt checksums and tests must evolve together. A published version is never rewritten: `v5` remains available, while `v5-parallel` is a new version. Real parallelism depends on available Worker capacity and the frozen `max_parallel_nodes` policy; the graph permits concurrency but does not guarantee simultaneous CPU execution. Rework invalidates downstream versions and rejoins only after both branches finish.
