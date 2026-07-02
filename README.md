# AutoSpec

AutoSpec is a multi-agent SOP orchestration and evaluation platform for software requirements engineering. It is inspired by MetaGPT's role-based software-company workflow, but focuses on a narrower Java/enterprise scenario: turning a natural-language requirement into structured PRD, architecture, backend design, frontend skeleton, review report, code export, and evaluator report artifacts.

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
    -> FastAPI Agent Engine
      -> Product Manager Agent
      -> Architect Agent
      -> Backend Engineer Agent
      -> Frontend Engineer Agent
      -> Reviewer Agent
      -> Evaluator Agent
```

The backend remains the system of record for projects, artifacts, review issues, execution events, prompt/model metadata, workflow snapshots, exports, and evaluation reports. The Agent Engine owns role execution, schemas, workflow contracts, and deterministic evaluation rules.

## Key Modules

- `agent-engine/schemas/workflow_spec.py`: V4 workflow contract models.
- `agent-engine/graph/workflow_specs.py`: built-in `autospec-v4` workflow registry.
- `agent-engine/schemas/evaluation.py`: evaluation case, score, issue, and report schemas.
- `agent-engine/review/evaluator.py`: deterministic evaluator rules.
- `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`: persists generated artifacts, including `EVALUATION_REPORT`.
- `docs/examples/v4-sample-artifacts.md`: sanitized V4 sample outputs.

## Verification

Run Agent Engine tests:

```powershell
python -m pytest agent-engine
```

Run backend tests:

```powershell
cd backend
mvn test
```

Run frontend build:

```powershell
cd frontend
npm run build
```

## Resume Positioning

AutoSpec can be presented as:

> Designed and implemented a configurable multi-agent SOP orchestration and evaluation platform inspired by MetaGPT, with typed Agent node contracts, prompt/model traceability, failure retry, human approval gates, benchmark-style deterministic evaluation, and rule-based cross-artifact consistency scoring for PRD, architecture, database, API, permissions, frontend skeleton, and export readiness.
