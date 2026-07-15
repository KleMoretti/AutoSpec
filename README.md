# AutoSpec

AutoSpec is a multi-agent SOP orchestration and evaluation platform for software requirements engineering. It is inspired by MetaGPT's role-based software-company workflow, but focuses on a narrower Java/enterprise scenario: turning a natural-language requirement into structured PRD, architecture, backend design, frontend skeleton, review report, code export, and evaluator report artifacts.

## V5 Dynamic Workflow

V5 replaces the fixed cross-Agent call chain with a durable, versioned DAG runtime:

- Spring Boot owns immutable workflow versions, frozen run snapshots, DAG reconciliation, transactional Outbox, idempotent event consumption, approval, targeted rework, recovery, replay, and runtime metrics.
- Redis Streams transports at-least-once node commands and terminal/heartbeat events.
- Two or more Python Workers consume commands through a shared consumer group and execute versioned Pydantic-validated handlers.
- MySQL remains the source of truth for runs, attempts, artifacts, transitions, approvals, and recovery checkpoints.
- The React workspace can start a published V5 version, approve candidate artifacts, inspect attempts and metrics, and create immutable replays.

The built-in `autospec-v5:v5` graph runs Backend Engineer and Frontend Engineer in parallel after Architect, joins them at Reviewer, supports bounded structured rework, and finishes with Evaluator.

## V4 Focus

V4 shifts the project from a fixed document-generation pipeline toward Agent engineering depth:

- **Configurable SOP workflow spec**: `autospec-v4` declares Agent nodes, edges, input/output schemas, prompt versions, model policy, retry policy, human approval gates, and artifact mapping.
- **Typed node contracts**: Agent outputs are validated as structured Pydantic artifacts instead of opaque text.
- **Evaluation loop**: `EvaluatorAgent_v1` scores schema validity, requirement coverage, cross-artifact consistency, permission coverage, RAG citation quality, runtime reliability, and export readiness.
- **Traceability**: backend persistence records Agent tasks, artifacts, events, model invocations, workflow snapshots, and V4 evaluation reports.

## Architecture

```text
React frontend
  -> Spring Boot backend
    -> MySQL checkpoints + transactional Outbox
    -> Redis command/event Streams
      -> Python Worker consumer group
        -> versioned Product Manager / Architect / Engineer / Reviewer / Evaluator handlers
    -> DAG reconciliation / approval / rework / recovery / replay
```

The backend remains the system of record for projects, artifacts, review issues, execution events, prompt/model metadata, workflow snapshots, exports, and evaluation reports. The Agent Engine owns role execution, schemas, workflow contracts, and deterministic evaluation rules.

## Key Modules

- `backend/src/main/java/com/autospec/workflow`: V5 DAG compiler, runtime state machine, reconciliation, rework, recovery, and Redis transport.
- `agent-engine/runtime`: versioned handler registry, node executor, Redis Stream Worker, pending-message claim, and production entry point.
- `frontend/src/components/WorkflowReplayPanel.tsx`: V5 start, run history, metrics, attempt timeline, and immutable replay workspace.
- `docs/examples/v5-dynamic-workflow-run.md`: sanitized lifecycle and failure-evidence trace.

- `agent-engine/schemas/workflow_spec.py`: V4 workflow contract models.
- `agent-engine/graph/workflow_specs.py`: built-in `autospec-v4` workflow registry.
- `agent-engine/schemas/evaluation.py`: evaluation case, score, issue, and report schemas.
- `agent-engine/evaluation/case_catalog.py`: reproducible V4 evaluation cases.
- `agent-engine/review/experiments.py`: experiment comparison by workflow version, prompt version, model config, score, cost, duration, and failure count.
- `agent-engine/review/evaluator.py`: deterministic evaluator rules.
- `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`: persists generated artifacts, including `EVALUATION_REPORT`.
- `docs/examples/v4-sample-artifacts.md`: sanitized V4 sample outputs.

## Verification

### Run the complete V5 stack

Copy `.env.example` to a local untracked `.env`, replace the placeholder MySQL values, then run:

```powershell
docker compose up --build
```

The stack exposes the frontend on `http://localhost:5173`, backend on `http://localhost:8080`, and Agent API on `http://localhost:8000`. Compose starts MySQL, persistent Redis, the control plane, two Worker consumers, and nginx.

### V5 control-plane APIs

- `POST /api/workflows`, `POST /api/workflows/{versionId}/validate`, `POST /api/workflows/{versionId}/publish`
- `POST /api/workflow-runs`, `GET /api/workflow-runs/{runId}/nodes`, `GET /api/workflow-runs/{runId}/metrics`
- `POST /api/workflow-approvals/{approvalId}/decide`
- `POST /api/workflow-runs/{runId}/replay`

Useful Agent Engine endpoints:

- `POST /generate/v4`: run the V4 workflow and return `evaluation_report`.
- `GET /evaluation/cases`: list built-in reproducible benchmark cases.
- `POST /experiments/compare`: compare multiple Agent runs by score, runtime, cost, model/prompt config, and failure count.

Useful backend/frontend path:

- `POST /api/projects/{projectId}/generate-v4`: run the V4 Agent workflow through the backend and persist `EVALUATION_REPORT`.
- `generateProjectV4(projectId)`: frontend API client helper for V4 generation.

Run Agent Engine tests:

```powershell
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest agent-engine -q
```

Run backend tests:

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -f backend\pom.xml test
```

Run frontend build:

```powershell
npm --prefix frontend test
npm --prefix frontend run build
docker compose config
```

## Resume Positioning

AutoSpec can be presented as:

> Designed and implemented a configurable multi-agent SOP orchestration and evaluation platform inspired by MetaGPT, with typed Agent node contracts, prompt/model traceability, failure retry, human approval gates, benchmark-style deterministic evaluation, and rule-based cross-artifact consistency scoring for PRD, architecture, database, API, permissions, frontend skeleton, and export readiness.
