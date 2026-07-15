# AutoSpec V5 Dynamic Workflow — Sanitized Run

This example contains no user identifiers, credentials, provider payloads, or proprietary requirements. It records the deterministic integration scenario exercised by `DynamicWorkflowLifecycleTest` and links each failure behavior to its focused regression.

## Frozen run

```json
{
  "workflow_key": "autospec-v5",
  "workflow_version": "v5",
  "input": {
    "requirement": "Build a campus marketplace",
    "retrieved_sources": []
  },
  "max_parallel_nodes": 4,
  "max_review_rounds": 2
}
```

## Timeline

| Sequence | Node/control event | Result |
| --- | --- | --- |
| 1 | `product_manager` revision 1, attempt 1 | Candidate PRD persisted; run pauses at configured `AFTER_NODE` approval. |
| 2 | Human `APPROVE` | Candidate becomes approved and reconciliation resumes from the same checkpoint. |
| 3 | `architect` revision 1, attempt 1 | Input contains the approved PRD output. |
| 4 | `backend_engineer` and `frontend_engineer` | Both become `QUEUED` in the same reconciliation layer and can be claimed by different Workers. |
| 5 | `reviewer` | Joined input contains both engineering outputs; `PASS` continues to Evaluator. A `REWORK` response instead executes the persisted route plan described below. |
| 6 | `evaluator` | Evaluation artifact persists and the run reaches `COMPLETED` / 100%. |
| 7 | Duplicate Evaluator event | Returns `DUPLICATE`; no second transition or artifact is created; the run counter increments. |
| 8 | Original-snapshot replay | Creates a new run linked by `replay_of_run_id`; original attempts and artifacts remain immutable. |

The deterministic test envelope reports six node attempts, 72 ms of Worker-reported execution time, zero retries, zero recoveries, and one accepted duplicate delivery. Real queue time, Token usage, estimated cost, retries, and recoveries are available from `GET /api/workflow-runs/{runId}/metrics`.

## Targeted rework trace

Reviewer may return:

```json
{
  "decision": "REWORK",
  "routes": [{
    "target_node": "backend_engineer",
    "issue_ids": ["R-12"],
    "required_changes": ["Add the missing collection API and uniqueness constraint"],
    "invalidate_downstream": true
  }]
}
```

The control plane validates the route against frozen `REWORK` edges, increments the review round in one transaction, marks the completed target/downstream attempts `STALE`, creates the new Backend revision, preserves the unrelated Frontend branch, and reconciles a new Reviewer attempt only after affected nodes complete. A third rework request moves the run to `MANUAL_INTERVENTION`.

## Failure and restart evidence

| Scenario | Expected invariant | Regression evidence |
| --- | --- | --- |
| Duplicate Stream event | One valid terminal transition and one artifact per node attempt | `WorkflowEventConsumerTest`, `DynamicWorkflowLifecycleTest` |
| Worker disappears after claiming | Stale heartbeat becomes `ORPHANED`; a new attempt is created | `WorkflowRecoveryServiceTest` |
| Redis loses a queued command | Missing command is reconstructed from the MySQL checkpoint and Outbox | `WorkflowRecoveryServiceTest` |
| Control plane restarts during approval | Pending decision remains durable and resumes the original run once | `WorkflowApprovalServiceTest` |
| Retry/fallback due after restart | Frozen error policy promotes only eligible attempts | `RetryPolicyEvaluatorTest`, `WorkflowDueRetryServiceTest` |
| Reviewer requests rework | Only target and affected downstream revisions are replaced | `ReworkPlannerTest`, `ReworkPlanExecutionServiceTest` |
| Historical replay | New run retains provenance and never overwrites source records | `WorkflowReplayServiceTest` |
| Two Worker consumers | Redis consumer group distributes parallel commands; stale pending entries use `XAUTOCLAIM` | `RedisWorkflowStreamClient`/`WorkflowWorkerRunner` tests and Compose `agent-worker-1/2` services |

`docker-compose.yml` is the production-style smoke environment for MySQL 8.4 and Redis 7.4. `docker compose config` is part of final verification; a live smoke run additionally requires Docker Desktop/Engine to be running.
