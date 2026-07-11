# AutoSpec V5 Dynamic Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard-coded V4 orchestration path with a versioned, Spring-controlled DAG runtime that dispatches Agent nodes through Redis Streams and supports recovery, targeted rework, configurable approval, and replay.

**Architecture:** Spring Boot owns workflow definitions, immutable versions, run/node state, DAG reconciliation, approvals, replay, and the transactional outbox. Python workers execute one registered node command at a time and publish idempotent events through Redis Streams. MySQL is the system of record; Redis provides at-least-once transport.

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis-Plus, Flyway, MySQL/H2, Spring Data Redis Streams, Python 3.11, FastAPI, Pydantic 2, redis-py, pytest, React/TypeScript.

---

## Delivery Strategy

The design is split into six independently testable milestones. M1-M3 form the first production-style demonstration; M4-M6 add Agent-specific control features. Every production behavior starts with a failing test, followed by the smallest implementation and a focused green run.

## Task 1: Persist the approved V5 roadmap

**Files:**
- Modify: `backend/src/test/java/com/autospec/SchemaInitSqlTest.java`
- Create: `backend/src/main/resources/db/migration/V67__autospec_v5_dynamic_workflow_plan_seed.sql`
- Create: `docs/autospec-v5-plan.md`

- [x] **Step 1: Write a failing Flyway seed test**

Add `flywayMigrationsSeedV5DynamicWorkflowPlan()` that migrates an H2 MySQL-mode database and asserts one `ROADMAP_PLAN` artifact exists for project `AutoSpec V5 Dynamic Workflow Plan`, version `5`, status `APPROVED`, and content containing `Redis Streams`, `Reviewer targeted rework`, `checkpoint recovery`, and `workflow replay`.

- [x] **Step 2: Verify the seed test is red**

Run:

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -f backend\pom.xml -Dtest=SchemaInitSqlTest#flywayMigrationsSeedV5DynamicWorkflowPlan test
```

Expected: failure because the V5 project and artifact do not exist.

- [x] **Step 3: Add the idempotent V67 seed migration**

Insert the project only when its name does not exist, then insert the approved version-5 roadmap artifact only when `(project_id, type, version)` does not exist. Store a concise Markdown summary of all six milestones and the canonical design/plan paths.

- [x] **Step 4: Add the human-readable roadmap index**

Document the objective, database record, milestones, acceptance criteria, canonical design path, and canonical implementation-plan path in `docs/autospec-v5-plan.md`.

- [x] **Step 5: Verify the focused migration test is green**

Run the command from Step 2. Expected: one test, zero failures.

## Task 2: Extend and validate WorkflowSpec V5

**Files:**
- Modify: `agent-engine/schemas/workflow_spec.py`
- Modify: `agent-engine/graph/workflow_specs.py`
- Modify: `agent-engine/tests/test_workflow_spec.py`

- [x] **Step 1: Write failing schema tests**

Add tests proving that V5 accepts runtime parallelism, retry backoff, fallback, approval modes, conditional edges, and bounded rework edges; rejects duplicate nodes, unknown references, ordinary cycles, unreachable nodes, and rework without `max_review_rounds`.

- [x] **Step 2: Run the tests and confirm red**

```powershell
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest agent-engine\tests\test_workflow_spec.py -q
```

Expected: new V5 fields or validation behavior missing.

- [x] **Step 3: Implement focused Pydantic contracts**

Add `WorkflowRuntimePolicy`, `RetryBackoffPolicy`, `FallbackPolicy`, `ApprovalPolicy`, `ApprovalMode`, `WorkflowEdgeType`, and a restricted condition model. Keep the V4 payload valid through defaults and compatibility aliases.

- [x] **Step 4: Implement graph validation**

Build ordinary-edge adjacency, run Kahn topological validation, check reachability from entry nodes, and validate rework bounds separately. Return actionable `ValueError` messages containing the offending node or edge.

- [x] **Step 5: Register `autospec-v5`**

Add a built-in spec in which `backend_engineer` and `frontend_engineer` can run in parallel after `architect`, followed by `reviewer` and `evaluator`; PRD uses an `AFTER_NODE` approval and Reviewer has bounded rework routes.

- [x] **Step 6: Run focused and full Agent tests**

```powershell
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest agent-engine\tests\test_workflow_spec.py -q
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest agent-engine -q
```

Expected: all tests pass.

## Task 3: Add immutable workflow definitions and runtime tables

**Files:**
- Modify: `backend/src/test/java/com/autospec/SchemaInitSqlTest.java`
- Create: `backend/src/main/resources/db/migration/V68__dynamic_workflow_runtime.sql`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowDefinition.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowVersion.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowNodeRun.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowApproval.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowTransition.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowOutbox.java`
- Create: `backend/src/main/java/com/autospec/entity/ProcessedWorkflowEvent.java`
- Modify: `backend/src/main/java/com/autospec/entity/WorkflowRun.java`

- [ ] **Step 1: Write a failing migration contract test**

Assert all seven new tables, V5 columns on `workflow_run`, unique `execution_id`, unique `(workflow_run_id,node_id,revision,attempt)`, and recovery indexes exist after Flyway migration.

- [ ] **Step 2: Verify the migration test is red**

Run the focused `SchemaInitSqlTest` method and confirm missing-table or missing-column failure.

- [ ] **Step 3: Add the V68 migration**

Create the tables and indexes described in the design. Use portable MySQL/H2-compatible types already established by V1-V66. Add nullable foreign-key reference columns to existing `agent_task`, `agent_event`, `artifact`, and `model_invocation` without breaking V1-V4 data.

- [ ] **Step 4: Map persistence entities**

Add MyBatis-Plus entities with Java time types, `@TableName`, identifier annotations, JSON stored as `String`, and `lockVersion` fields where conditional transitions require optimistic locking.

- [ ] **Step 5: Verify migration and backend tests**

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -f backend\pom.xml -Dtest=SchemaInitSqlTest test
```

Expected: all schema tests pass.

## Task 4: Compile and validate DAGs in the Spring control plane

**Files:**
- Create: `backend/src/main/java/com/autospec/workflow/spec/WorkflowSpecDocument.java`
- Create: `backend/src/main/java/com/autospec/workflow/spec/WorkflowNodeDocument.java`
- Create: `backend/src/main/java/com/autospec/workflow/spec/WorkflowEdgeDocument.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/CompiledWorkflow.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/DagCompiler.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/DagCompilerTest.java`

- [ ] **Step 1: Write failing compiler tests**

Cover deterministic topological layers, parallel siblings, unknown dependencies, ordinary cycles, unreachable nodes, and exclusion of `REWORK` edges from ordinary topological sorting.

- [ ] **Step 2: Verify tests fail because the compiler is absent**

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -f backend\pom.xml -Dtest=DagCompilerTest test
```

- [ ] **Step 3: Implement immutable spec records and compiler**

Represent nodes and edges as Java records. Compile ordered node maps, predecessor/successor maps, topological layers, entry nodes, and terminal nodes. Reject invalid graphs with stable domain error codes.

- [ ] **Step 4: Verify focused tests are green**

Run the command from Step 2. Expected: all compiler cases pass.

## Task 5: Implement idempotent node-state transitions and reconciliation

**Files:**
- Create: `backend/src/main/java/com/autospec/workflow/runtime/WorkflowNodeStatus.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/NodeReadinessEvaluator.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/WorkflowReconciler.java`
- Create: `backend/src/main/java/com/autospec/service/WorkflowNodeRunService.java`
- Create: `backend/src/main/java/com/autospec/service/impl/WorkflowNodeRunServiceImpl.java`
- Create: `backend/src/main/java/com/autospec/mapper/WorkflowNodeRunMapper.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/NodeReadinessEvaluatorTest.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/WorkflowReconcilerTest.java`

- [ ] **Step 1: Write failing readiness and reconciliation tests**

Verify roots become ready, dependent nodes wait, failed dependencies block, false conditions skip, independent nodes share a scheduling batch, and the configured parallel limit is honored.

- [ ] **Step 2: Run focused tests and confirm red**

Run Maven with `-Dtest=NodeReadinessEvaluatorTest,WorkflowReconcilerTest`.

- [ ] **Step 3: Implement legal state transitions**

Define allowed transitions and use conditional mapper updates containing expected status and `lock_version`. A zero-row update represents a concurrent or duplicate event and must not be treated as a second successful transition.

- [ ] **Step 4: Implement reconciliation**

Read the frozen graph and node runs, compute ready/skipped nodes, reserve capacity, transition selected nodes to `QUEUED`, and create one Outbox entry per execution ID in the same transaction.

- [ ] **Step 5: Verify focused and backend tests**

Run focused tests, then the full backend test suite.

## Task 6: Introduce the Redis Streams command/event transport

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/autospec/workflow/transport/WorkflowCommand.java`
- Create: `backend/src/main/java/com/autospec/workflow/transport/WorkflowExecutionEvent.java`
- Create: `backend/src/main/java/com/autospec/workflow/transport/WorkflowOutboxPublisher.java`
- Create: `backend/src/main/java/com/autospec/workflow/transport/WorkflowEventConsumer.java`
- Create: `backend/src/test/java/com/autospec/workflow/transport/WorkflowEventConsumerTest.java`
- Modify: `agent-engine/requirements.txt`
- Create: `agent-engine/runtime/handler_registry.py`
- Create: `agent-engine/runtime/node_executor.py`
- Create: `agent-engine/runtime/worker.py`
- Create: `agent-engine/tests/test_node_executor.py`
- Create: `agent-engine/tests/test_worker_protocol.py`

- [ ] **Step 1: Write failing Java and Python protocol tests**

Assert shared message fields, idempotent duplicate event handling, success-after-start ordering, unknown Handler rejection, input/output Schema validation, and timeout error classification.

- [ ] **Step 2: Confirm both test groups are red**

Run the focused Maven tests and the two focused pytest files.

- [ ] **Step 3: Implement Java transport**

Publish pending Outbox rows to `autospec.workflow.commands`, consume `autospec.workflow.events`, guard event IDs with `processed_workflow_event`, update node state conditionally, and invoke reconciliation after accepted terminal events.

- [ ] **Step 4: Implement Python Worker runtime**

Add `redis==5.2.1`, a versioned Handler Registry, Pydantic command/event envelopes, per-command timeout, heartbeat publication, event publication, and acknowledgement only after terminal event publication.

- [ ] **Step 5: Verify focused and full suites**

Run backend and Agent suites. Expected: all tests pass without a live Redis dependency in unit tests.

## Task 7: Add retry, fallback, leases, and checkpoint recovery

**Files:**
- Create: `backend/src/main/java/com/autospec/workflow/runtime/RetryPolicyEvaluator.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/WorkflowRecoveryService.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/RetryPolicyEvaluatorTest.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/WorkflowRecoveryServiceTest.java`
- Modify: `agent-engine/runtime/node_executor.py`
- Modify: `agent-engine/tests/test_node_executor.py`

- [ ] **Step 1: Write failing retry and recovery tests**

Cover exponential backoff caps, retryable error whitelist, fallback after primary exhaustion, stale heartbeat to `ORPHANED`, creation of a new attempt, and compensation for queued nodes with no pending Outbox message.

- [ ] **Step 2: Confirm focused tests are red**

Run the new Java tests and focused Python executor tests.

- [ ] **Step 3: Implement retry and fallback decisions**

Keep the Worker limited to classifying one attempt. Persist `next_retry_at`; let reconciliation create subsequent attempts only when due.

- [ ] **Step 4: Implement recovery reconciliation**

Use database timeouts and heartbeat leases to orphan lost attempts, ignore late events, and create replacement commands. Add a scheduled recovery invocation guarded against concurrent control-plane execution.

- [ ] **Step 5: Verify focused and full suites**

Run all backend and Agent tests.

## Task 8: Implement conditional routing and Reviewer targeted rework

**Files:**
- Create: `backend/src/main/java/com/autospec/workflow/runtime/RestrictedConditionEvaluator.java`
- Create: `backend/src/main/java/com/autospec/workflow/runtime/ReworkPlanner.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/RestrictedConditionEvaluatorTest.java`
- Create: `backend/src/test/java/com/autospec/workflow/runtime/ReworkPlannerTest.java`
- Modify: `agent-engine/schemas/review.py`
- Modify: `agent-engine/agents/reviewer.py`
- Modify: `agent-engine/tests/test_review_rules.py`

- [ ] **Step 1: Write failing routing tests**

Cover `EQ`, `NE`, `IN`, `EXISTS`, boolean `all`/`any`, invalid JSON paths, target allowlists, downstream invalidation, unaffected branch preservation, revision increments, and transition to `MANUAL_INTERVENTION` after two rounds.

- [ ] **Step 2: Confirm tests are red**

Run focused Java and Python review tests.

- [ ] **Step 3: Implement restricted conditions**

Evaluate only declared JSON paths and registered operators; reject arbitrary code and unsupported operators.

- [ ] **Step 4: Implement rework planning**

Validate Reviewer routes, traverse successor relationships, mark affected completed nodes `STALE`, create a new target revision, preserve unrelated branches, and enqueue a new Reviewer execution after affected nodes complete.

- [ ] **Step 5: Verify focused and full suites**

Run all backend and Agent tests.

## Task 9: Add configurable approval and continuation

**Files:**
- Create: `backend/src/main/java/com/autospec/service/WorkflowApprovalService.java`
- Create: `backend/src/main/java/com/autospec/service/impl/WorkflowApprovalServiceImpl.java`
- Create: `backend/src/main/java/com/autospec/controller/WorkflowApprovalController.java`
- Create: `backend/src/main/java/com/autospec/dto/ApprovalDecisionRequest.java`
- Create: `backend/src/test/java/com/autospec/WorkflowApprovalServiceTest.java`
- Modify: `frontend/src/api/v3.ts`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`
- Create: `frontend/src/components/WorkflowApprovalPanel.tsx`
- Create: `frontend/src/components/WorkflowApprovalPanel.test.tsx`

- [ ] **Step 1: Write failing backend approval tests**

Cover before-node pause, after-node candidate output, approval, rejection, edit-and-approve creating a child Artifact version, rollback to an allowed node, idempotent duplicate decision, and continuation after service reconstruction.

- [ ] **Step 2: Implement backend approval state machine and API**

Persist immutable decisions, transition workflow/node states conditionally, retain Agent and human Artifact versions, and invoke reconciliation only after an accepted decision.

- [ ] **Step 3: Write failing frontend interaction tests**

Assert allowed actions render from API data, edited JSON is submitted with an idempotency key, and completed decisions disable duplicate submission.

- [ ] **Step 4: Implement approval UI**

Add the panel to project detail without changing existing V1-V4 behavior.

- [ ] **Step 5: Verify backend, frontend, and Agent suites**

Run Maven tests, pytest, `npm test`, and `npm run build`.

## Task 10: Add immutable replay and experiment comparison

**Files:**
- Create: `backend/src/main/java/com/autospec/service/WorkflowReplayService.java`
- Create: `backend/src/main/java/com/autospec/service/impl/WorkflowReplayServiceImpl.java`
- Create: `backend/src/main/java/com/autospec/controller/WorkflowRuntimeController.java`
- Create: `backend/src/test/java/com/autospec/WorkflowReplayServiceTest.java`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`

- [ ] **Step 1: Write failing replay tests**

Verify original replay copies the frozen Workflow Snapshot and original input into a new run, links `replay_of_run_id`, does not overwrite old nodes or artifacts, and fails with `RUNTIME_VERSION_UNAVAILABLE` when a frozen Handler is missing.

- [ ] **Step 2: Implement replay service and API**

Support `ORIGINAL_SNAPSHOT` and `SELECTED_VERSION` replay modes. Preserve provenance and emit an audit transition for the new run.

- [ ] **Step 3: Add replay UI and test it**

Show source run, replay mode, selected version, and a link to the new run timeline.

- [ ] **Step 4: Verify all suites**

Run backend, Agent, and frontend verification commands.

## Task 11: Production integration and failure tests

**Files:**
- Modify: `docker-compose.yml`
- Create: `backend/src/test/java/com/autospec/workflow/DynamicWorkflowIntegrationTest.java`
- Create: `docs/examples/v5-dynamic-workflow-run.md`
- Modify: `README.md`

- [ ] **Step 1: Add failing Testcontainers integration scenarios**

Test parallel workers, duplicate delivery, Worker loss and claim, control-plane restart, Redis restart compensation, targeted rework, approval across restart, and immutable replay.

- [ ] **Step 2: Wire production containers and health checks**

Configure Redis persistence, backend stream consumer, at least two Python Worker replicas in the documented demo profile, and service health dependencies.

- [ ] **Step 3: Add metrics and a sanitized execution example**

Document node queue time, execution duration, retry count, recovery count, token cost, and accepted duplicate-event count. Include one sanitized V5 trace with parallel execution, Reviewer rework, approval, and replay.

- [ ] **Step 4: Run final verification**

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -f backend\pom.xml test
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest agent-engine -q
npm --prefix frontend test
npm --prefix frontend run build
docker compose config
```

Expected: every command exits zero, with zero test failures and a valid Compose configuration.

## Completion Checklist

- [ ] Runtime node order is derived exclusively from the frozen WorkflowSpec.
- [ ] Independent nodes execute concurrently within configured limits.
- [ ] Timeout, retry, fallback, cancellation, and error classification are observable.
- [ ] Redis duplicate delivery cannot create duplicate valid state transitions or artifacts.
- [ ] Reviewer rework invalidates only the target and affected downstream nodes.
- [ ] Checkpoint recovery survives Worker, Redis, and control-plane restart scenarios.
- [ ] Any configured node can pause before or after execution for approval.
- [ ] Historical workflow versions can be replayed without overwriting original records.
- [ ] V1-V4 endpoints and regression tests remain valid.
- [ ] The V5 roadmap, sanitized trace, architecture, verification commands, and measured results are documented.
