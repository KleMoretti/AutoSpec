# AutoSpec

AutoSpec is an auditable requirements-to-contract platform. A user submits a software requirement and a durable multi-Agent workflow produces structured PRD, architecture, backend, frontend, review, evaluation, and code-scaffold artifacts with approval, replay, and recovery support.

## Canonical V5 workflow

The product UI now exposes one generation path: the published `autospec-v5:v5` workflow.

- Spring Boot owns immutable workflow versions, frozen run snapshots, DAG reconciliation, transactional Outbox, approval, targeted rework, cancellation, recovery, replay, and artifact history.
- Redis Streams transports at-least-once node commands and terminal or heartbeat events to Python Workers.
- Python handlers load versioned prompts, validate every role output with Pydantic, and can use an OpenAI-compatible live model gateway.
- Backend and frontend engineering run in parallel after architecture; Reviewer joins both branches and Evaluator applies the final delivery gate.
- The canonical graph lives in `agent-engine/contracts/autospec-v5.workflow.json`; CI verifies that it is identical to the immutable database seed.

The local `fixture` model mode is deterministic and intended only for development and tests. Production rejects fixture mode and requires explicit live-model configuration.

## Trust and delivery boundaries

- Demo login is opt-in; the backend no longer creates `owner / owner-pass` during login.
- Browser sessions use an `HttpOnly`, `SameSite` cookie; reusable session credentials are never placed in URLs.
- Agent API diagnostics require a service token, and Worker traffic uses authenticated Redis connections plus bounded node deadlines.
- Compose binds published ports to `127.0.0.1` by default and protects Redis with a password.
- Production components fail closed when demo login is enabled, cookies are not Secure/Strict, the database user is root, required secrets are blank, fixture model mode is selected, or database/Redis TLS is not explicitly enabled.
- Retrieval is restricted to the current project, so an editor cannot pull knowledge from the owner's other projects.
- Reruns preserve artifact history. Cancelling a run closes pending commands and prevents late Worker events from projecting artifacts.
- Evaluator builds a `REQ-* -> story/acceptance -> API -> data -> UI` trace matrix. Missing MUST coverage or any HIGH/CRITICAL issue blocks completion and delivery.
- Markdown/PDF/ZIP export and code generation are permitted only after the latest V5 run and its own evaluation report pass the gate.
- Generated code ZIPs derive routes, tables, pages, and project metadata from the latest artifacts and contain Maven/Vite buildable scaffolds.

## P1 product capabilities

- Artifact history now distinguishes latest, approved, and candidate versions. Every artifact records its parent/upstream versions, content hash, schema and prompt version, selected model route, context policy, and exact knowledge citations; users can inspect field-level diffs and restore an older version as a new candidate without rewriting history.
- Review findings are actionable records with a stable issue key, artifact path, requirement evidence, owner, resolution, and resolved artifact version. High-severity findings must be resolved or explicitly ignored with a reason before approval.
- Every V5 run freezes a Fast, Balanced, or Deep execution policy. Provider/model decisions, fallback reason, input/output/cache tokens, model calls, estimated cost, and compacted-context manifest are persisted, while atomic token/cost/call/time budgets stop over-budget runs.
- Project knowledge retrieval combines lexical and stored-vector ranking with reciprocal-rank fusion, remains project-scoped, and returns exact chunk identifiers and excerpts. Evaluation rejects unknown or unfaithful citations.
- The frontend now provides a searchable project dashboard and a staged Intake -> Generate -> Review & fix -> Deliver workspace, with specialized PRD, architecture, API/data, frontend, evaluation, provenance, version-diff, and runtime-usage views.
- The evaluation catalog contains 20 cross-domain cases and experiment comparison supports automatic metrics, human scores, and prompt/model A/B deltas.

## Architecture

```text
React frontend
  -> Spring Boot control plane + MySQL source of truth
    -> Redis command/event Streams
      -> Python Product Manager / Architect / Backend / Frontend / Reviewer / Evaluator Workers
    -> approval / rework / recovery / replay / delivery gate
```

Key locations:

- `backend/src/main/java/com/autospec/workflow`: V5 state machine, reconciliation, transport, recovery, and replay.
- `agent-engine/model_gateway.py`: live OpenAI-compatible JSON model client and production configuration checks.
- `agent-engine/review/evaluator.py`: deterministic traceability and hard quality-gate rules.
- `frontend/src/components/WorkflowReplayPanel.tsx`: generation, approval, attempts, metrics, and replay workspace.
- `.github/workflows/quality.yml`: full-stack release gate.

## Local startup

Copy `.env.example` to an untracked `.env` and replace at least these local secrets:

```dotenv
MYSQL_PASSWORD=...
MYSQL_ROOT_PASSWORD=...
REDIS_PASSWORD=...
AGENT_ENGINE_SERVICE_TOKEN=...
AUTH_DEMO_USER_PASSWORD=...
```

Then start the local development stack:

```powershell
docker compose up --build
```

The frontend is available at `http://localhost:5173`. Backend and Agent API ports are also published on localhost for diagnostics. The supplied Compose file is a local-development topology, not a production deployment manifest.

To exercise real model output, set:

```dotenv
AGENT_MODEL_MODE=live
MODEL_API_KEY=...
MODEL_BASE_URL=https://your-openai-compatible-endpoint/v1
MODEL_NAME=...
```

For production, also use `AUTOSPEC_ENV=production`, disable the demo user, enable Secure/Strict cookies, use a non-root database user, supply a TLS-enabled `DB_URL` (for example with `sslMode=VERIFY_IDENTITY`), enable Redis TLS, and deploy data/internal services on private networks.

## Verification

Run the same core checks as the repository quality workflow:

```powershell
cd backend
mvn test

cd ../agent-engine
python -m pytest -q

cd ../frontend
npm test
npm run build

cd ..
python scripts/verify_workflow_contract.py
docker compose config --quiet
docker compose --profile monitoring config --quiet
```

CI additionally runs Testcontainers integration tests and builds the backend, Agent, and frontend images. Fixed legacy generation APIs are removed; the published V5 workflow is the only product generation path.
