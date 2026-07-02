# AutoSpec V4 Agent Orchestration and Evaluation Design

## Objective

AutoSpec V4 focuses on Agent orchestration and evaluation depth. The goal is to make AutoSpec defensible as an Agent engineering project rather than a simple document generator. V4 upgrades the existing fixed multi-agent pipeline into a configurable SOP workflow runtime and adds a reproducible evaluation layer for generated software artifacts.

The project remains aligned with the current AutoSpec scope: natural-language requirement input, structured PRD, architecture design, backend design, frontend skeleton, review report, export, traceability, and reviewer quality gates. V4 deepens the Agent layer without replacing the current Spring Boot, FastAPI, LangGraph, and React structure.

## Design Positioning

MetaGPT popularizes a software-company-style multi-agent process where roles collaborate through SOPs. AutoSpec should use that idea but target a narrower and more explainable product: enterprise software requirements analysis and prototype generation.

V4 will make this distinction explicit:

- MetaGPT-like inspiration: role-based collaboration, SOP-driven task flow, structured software artifacts.
- AutoSpec differentiation: Java/enterprise requirements engineering, strict artifact contracts, cross-artifact evaluator, traceable model/prompt/runtime records, and reproducible benchmark cases.

## Recommended Approach

The selected approach is **configurable orchestration plus evaluation loop**.

This is stronger than only adding more reviewer rules because it exposes the Agent system as a platform. It is also stronger than only improving observability because it evaluates output quality, not just runtime behavior. The work should be implemented as a V4 layer that preserves the existing V2/V3 flow while introducing workflow specs and evaluation reports.

## Architecture

V4 adds four conceptual units:

1. **Workflow Spec Registry**
   Stores versioned SOP definitions. A workflow spec declares node id, role name, input schema, output schema, prompt key/version, model policy, retry policy, human gate, artifact type, and edges.

2. **Agent Node Runtime**
   Executes nodes through a uniform contract. Runtime validates input before execution, validates output after execution, records prompt/model metadata, emits events, and writes artifact records.

3. **Evaluation Suite**
   Stores reusable evaluation cases. Each case includes a requirement, expected capability checklist, required artifact types, and scoring dimensions.

4. **Evaluator Agent**
   Produces a structured evaluation report after generation. It combines deterministic checks with optional LLM semantic judgment. The output is persisted as an `EVALUATION_REPORT` artifact.

## Workflow Spec Shape

The built-in V4 workflow should be equivalent to the current product flow but represented declaratively:

```text
product_manager
  -> human_prd_approval
  -> architect
  -> backend_engineer
  -> frontend_engineer
  -> reviewer
  -> evaluator
```

Parallel dependencies may remain explicit. For example, `backend_engineer` depends on PRD and architecture, while `frontend_engineer` depends on PRD, architecture, and backend API design.

Each node contract should include:

- `node_id`
- `agent_name`
- `input_schema`
- `output_schema`
- `artifact_type`
- `prompt_key`
- `prompt_version`
- `model_policy`
- `retry_policy`
- `timeout_ms`
- `requires_human_approval`
- `depends_on`

## Evaluation Dimensions

The evaluator should score the run across these dimensions:

| Dimension | Purpose |
| --- | --- |
| Schema validity | Checks whether every artifact conforms to the declared schema. |
| Requirement coverage | Checks whether core requirement features appear in PRD, API, database, and frontend skeleton. |
| Cross-artifact consistency | Checks whether PRD features map to APIs, APIs map to database entities, and frontend pages call declared APIs. |
| Permission coverage | Checks whether protected operations include authentication and role requirements. |
| RAG citation quality | Checks whether reused historical knowledge has source references. |
| Runtime reliability | Scores node failures, retries, duration, and validation errors. |
| Export readiness | Checks whether generated skeleton output avoids secrets and includes expected project files. |

The report should include numeric scores, issue severity, evidence references, and suggested fixes.

## Data Flow

1. User creates or selects a project.
2. Backend chooses a workflow spec version such as `autospec-v4`.
3. Backend creates a workflow run and delegates execution to Agent Engine.
4. Agent Engine resolves node dependencies and runs nodes through the node runtime contract.
5. Each node validates input, executes, validates output, emits events, and returns an artifact payload.
6. Backend persists agent tasks, artifacts, events, model invocations, and workflow snapshot data.
7. Evaluator consumes all generated artifacts and produces an `EVALUATION_REPORT`.
8. User views trace, review issues, evaluation score, and exported artifacts.

## Error Handling

- Schema validation failure should mark the node as failed with a structured validation error.
- Retry policy should be node-specific and recorded in `agent_task.retry_of_task_id`.
- Human approval gates should stop downstream execution until an approved artifact version exists.
- Evaluation should still run in partial mode when some artifacts are missing, but missing artifacts should lower the score and appear as issues.
- Model invocation failures should preserve provider, model, node, duration, and error message.

## Testing Strategy

V4 should prioritize deterministic tests:

- Workflow spec parsing and validation.
- Node contract input/output validation.
- Evaluator rule tests for missing API coverage, missing database mapping, missing permission roles, missing frontend route, and missing RAG source citation.
- Benchmark case tests using fixture artifacts instead of live LLM calls.
- Backend migration test to confirm the V4 roadmap artifact seed is created.

## Rollout Plan

1. Persist the V4 plan as a roadmap artifact.
2. Add workflow spec schema and a built-in `autospec-v4` workflow definition.
3. Add node runtime contract validation around existing Agent functions.
4. Add evaluation case schema and sample cases.
5. Add evaluator report schema and deterministic scoring rules.
6. Add experiment comparison metadata and reporting.
7. Package the repository with README, sample trace, and sample evaluation report.

## Acceptance Criteria

- V4 has a versioned workflow spec rather than only a hard-coded pipeline.
- Node contracts are validated before and after execution.
- At least three evaluation cases can run deterministically.
- Evaluator output is persisted as a structured artifact.
- Existing V2/V3 behavior remains available.
- The repository can explain the project as Agent orchestration and evaluation work suitable for a resume.
