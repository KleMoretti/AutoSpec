# AutoSpec V4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build AutoSpec V4 as a configurable multi-agent SOP orchestration and evaluation layer with typed node contracts, reproducible evaluation cases, evaluator reports, and resume-grade project packaging.

**Architecture:** Add V4 first inside `agent-engine` because workflow specs, node contracts, evaluation cases, and evaluator scoring are Agent-domain concerns. Keep Spring Boot persistence compatible with the existing `artifact`, `workflow_snapshot`, `agent_task`, and `model_invocation` tables; add backend endpoints only after Agent Engine can produce V4 outputs deterministically.

**Tech Stack:** Python FastAPI, Pydantic, pytest, existing LangGraph-compatible workflow functions, Spring Boot, Flyway, React/TypeScript.

---

## File Structure

- Create `agent-engine/schemas/workflow_spec.py`: Pydantic models for workflow specs, node contracts, model policies, retry policies, and validation helpers.
- Create `agent-engine/graph/workflow_specs.py`: built-in `autospec-v4` workflow spec and registry lookup.
- Create `agent-engine/tests/test_workflow_spec.py`: tests for V4 workflow spec shape and validation failures.
- Create `agent-engine/schemas/evaluation.py`: Pydantic models for evaluation cases, score dimensions, issues, and reports.
- Create `agent-engine/review/evaluator.py`: deterministic evaluator scoring generated artifacts and runtime records.
- Create `agent-engine/tests/test_evaluator.py`: tests for schema validity, missing coverage, permission gaps, RAG citation gaps, runtime reliability, and export readiness.
- Modify `agent-engine/graph/workflow.py`: add `WorkflowV4Result` and `run_v4_workflow()` that extends V2 with evaluator output.
- Modify `agent-engine/main.py`: add `/generate/v4` and response serialization for evaluation reports.
- Modify `backend` only after Agent Engine V4 outputs are stable: persist `EVALUATION_REPORT`, expose model/run comparison if needed.
- Create `agent-engine/evaluation/case_catalog.py`: built-in reproducible V4 benchmark case catalog.
- Create `agent-engine/review/experiments.py`: compare run score, prompt versions, model config, duration, cost, status, and failure count.
- Modify docs: add sample V4 evaluation report and README packaging after core behavior is verified.

## Task 1: Workflow Spec Contract

**Files:**
- Create: `agent-engine/schemas/workflow_spec.py`
- Create: `agent-engine/graph/workflow_specs.py`
- Create: `agent-engine/tests/test_workflow_spec.py`

- [x] **Step 1: Write failing workflow spec tests**

Add tests that assert:

```python
from graph.workflow_specs import get_workflow_spec
from schemas.workflow_spec import WorkflowSpec


def test_autospec_v4_workflow_spec_declares_evaluator_contract():
    spec = get_workflow_spec("autospec-v4")

    assert isinstance(spec, WorkflowSpec)
    assert spec.workflow_key == "autospec-v4"
    assert spec.version == "v4"
    assert [node.node_id for node in spec.nodes] == [
        "product_manager",
        "human_prd_approval",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    ]
    evaluator = spec.node("evaluator")
    assert evaluator.artifact_type == "EVALUATION_REPORT"
    assert evaluator.input_schema == "EvaluationInput"
    assert evaluator.output_schema == "EvaluationReport"
    assert evaluator.depends_on == ["reviewer"]


def test_workflow_spec_rejects_edges_to_unknown_nodes():
    payload = {
        "workflow_key": "broken",
        "version": "v1",
        "nodes": [
            {
                "node_id": "product_manager",
                "agent_name": "ProductManagerAgent_v1",
                "input_schema": "GenerateRequest",
                "output_schema": "PrdArtifact",
                "artifact_type": "PRD",
                "prompt_key": "product_manager",
                "prompt_version": "v1",
                "model_policy": {"provider_key": "local", "model_name": "deterministic-fixture"},
                "retry_policy": {"max_attempts": 1},
                "timeout_ms": 30000,
                "requires_human_approval": False,
                "depends_on": [],
            }
        ],
        "edges": [{"from_node": "product_manager", "to_node": "missing"}],
    }

    with pytest.raises(ValueError, match="unknown node"):
        WorkflowSpec.model_validate(payload)
```

- [x] **Step 2: Run tests and verify RED**

Run:

```powershell
python -m pytest agent-engine/tests/test_workflow_spec.py
```

Expected: fail because `schemas.workflow_spec` and `graph.workflow_specs` do not exist.

- [x] **Step 3: Implement workflow spec models and registry**

Implement Pydantic models with `model_validator` checking duplicate node ids, unknown edge endpoints, and node dependency references. Add `get_workflow_spec("autospec-v4")`.

- [x] **Step 4: Run tests and verify GREEN**

Run the same pytest command and expect all workflow spec tests to pass.

## Task 2: Evaluation Schema and Deterministic Evaluator

**Files:**
- Create: `agent-engine/schemas/evaluation.py`
- Create: `agent-engine/review/evaluator.py`
- Create: `agent-engine/tests/test_evaluator.py`

- [x] **Step 1: Write failing evaluator tests**

Add tests that create fixture PRD, architecture, backend design, frontend skeleton, review report, records, and generated files. Assert that:

- a complete run returns `overall_score >= 90`
- missing frontend API binding creates a `FRONTEND_COVERAGE` issue
- missing RAG sources creates a `RAG_CITATION` issue when historical reuse is requested
- failed runtime records lower the runtime reliability score
- generated files with concrete secrets create an `EXPORT_READINESS` issue

- [x] **Step 2: Run tests and verify RED**

Run:

```powershell
python -m pytest agent-engine/tests/test_evaluator.py
```

Expected: fail because evaluator modules do not exist.

- [x] **Step 3: Implement evaluation models**

Define `EvaluationCase`, `EvaluationIssue`, `EvaluationDimensionScore`, `EvaluationReport`, and `EvaluationInput` with strict but practical fields.

- [x] **Step 4: Implement deterministic evaluator**

Implement `evaluate_artifacts()` using existing artifact objects and `AgentExecutionRecord` data. Use score dimensions from the V4 design: schema validity, requirement coverage, cross-artifact consistency, permission coverage, RAG citation quality, runtime reliability, and export readiness.

- [x] **Step 5: Run tests and verify GREEN**

Run evaluator tests and then the full Agent Engine pytest suite.

## Task 3: V4 Workflow Integration

**Files:**
- Modify: `agent-engine/graph/workflow.py`
- Modify: `agent-engine/main.py`
- Modify: `agent-engine/tests/test_workflow.py`

- [x] **Step 1: Write failing V4 workflow test**

Add a test that calls `run_v4_workflow()` with `V2FakeModelClient` and asserts node execution order includes `evaluator`, result has `evaluation_report`, and evaluator report is serialized by the API helper.

- [x] **Step 2: Run test and verify RED**

Run:

```powershell
python -m pytest agent-engine/tests/test_workflow.py -k v4
```

Expected: fail because `run_v4_workflow` does not exist.

- [x] **Step 3: Implement `WorkflowV4Result` and `run_v4_workflow()`**

Run the existing V2 workflow, then evaluate all generated artifacts and append an evaluator execution record. Keep V2 behavior unchanged.

- [x] **Step 4: Add `/generate/v4`**

Expose V4 generation from FastAPI and include `evaluation_report` in the response body.

- [x] **Step 5: Run tests and verify GREEN**

Run Agent Engine pytest suite.

## Task 4: Backend Persistence and Retrieval

**Files:**
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/main/java/com/autospec/service/HttpAgentEngineClient.java`
- Modify: DTOs as needed under `backend/src/main/java/com/autospec/dto`
- Modify tests under `backend/src/test/java/com/autospec`

- [x] **Step 1: Add failing backend test**

Extend controller/service tests so a V4-style Agent Engine response containing `evaluation_report` persists an `EVALUATION_REPORT` artifact.

- [x] **Step 2: Run backend test and verify RED**

Run:

```powershell
cd backend
mvn -Dtest=ProjectControllerTest test
```

- [x] **Step 3: Persist evaluation report artifact**

Map `evaluation_report` from the Agent Engine response into `artifact.type = EVALUATION_REPORT`, `format = JSON`, `source_agent = EvaluatorAgent_v1`.

- [x] **Step 4: Run backend tests and verify GREEN**

Run backend targeted tests, then full `mvn test`.

## Task 5: V4 Samples and Packaging

**Files:**
- Create: `docs/examples/v4-sample-artifacts.md`
- Modify: `docs/autospec-v4-plan.md`
- Modify: `README.md` if present, otherwise create it.

- [x] **Step 1: Add sample V4 evaluation report**

Document a sanitized `EVALUATION_REPORT` JSON example with dimension scores and issues.

- [x] **Step 2: Add resume-grade README section**

Explain the MetaGPT-inspired positioning, V4 architecture, how to run tests, and how to demo V4.

- [x] **Step 3: Verify docs contain no secrets**

Run:

```powershell
rg -n "api_key|apikey|secret|password|token|D:\\\\" README.md docs
```

Expected: only policy text or placeholder examples, no real credentials.

## Task 6: Evaluation Case Catalog and Experiment Comparison

**Files:**
- Create: `agent-engine/evaluation/case_catalog.py`
- Create: `agent-engine/review/experiments.py`
- Modify: `agent-engine/schemas/evaluation.py`
- Modify: `agent-engine/main.py`
- Test: `agent-engine/tests/test_evaluation_cases.py`
- Test: `agent-engine/tests/test_experiment_comparison.py`
- Test: `agent-engine/tests/test_main.py`

- [x] **Step 1: Add reproducible evaluation cases**

Implemented at least three deterministic cases: campus marketplace, dorm repair workflow, and club activity platform.

- [x] **Step 2: Add experiment comparison models**

Implemented `ExperimentRun`, `ExperimentComparison`, and `ExperimentComparisonReport`.

- [x] **Step 3: Add experiment comparison logic**

Implemented ranking by failure count/status, score, estimated cost, and duration; also emits failure issues for failed runs.

- [x] **Step 4: Expose Agent Engine APIs**

Implemented `GET /evaluation/cases` and `POST /experiments/compare`.

- [x] **Step 5: Verify**

Run: `python -m pytest agent-engine`
Expected: all Agent Engine tests pass.

## Task 7: Backend and Frontend V4 Invocation Path

**Files:**
- Modify: `backend/src/main/java/com/autospec/service/AgentEngineClient.java`
- Modify: `backend/src/main/java/com/autospec/service/HttpAgentEngineClient.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`
- Modify: `frontend/src/api/projects.ts`
- Modify: `frontend/src/api/projects.test.ts`

- [x] **Step 1: Add backend failing test**

Added a test requiring `POST /api/projects/{projectId}/generate-v4` to call `AgentEngineClient.generateV4()` and persist `EVALUATION_REPORT`.

- [x] **Step 2: Implement backend V4 generation path**

Added `generateV4()` to the Agent Engine client, HTTP client, orchestration service, and project controller.

- [x] **Step 3: Add frontend API client**

Added `generateProjectV4(projectId)` and Vitest coverage.

- [x] **Step 4: Verify**

Run backend and frontend targeted tests, then full verification.

## Final Verification

Run:

```powershell
python -m pytest agent-engine
cd backend
mvn test
cd ..\frontend
npm run build
```

Expected: all commands exit with code 0.
