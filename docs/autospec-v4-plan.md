# AutoSpec V4 Plan

> Canonical design spec: `docs/superpowers/specs/2026-07-02-autospec-v4-agent-orchestration-evaluation-design.md`
>
> Seed migration: `backend/src/main/resources/db/migration/V4__autospec_v4_plan_seed.sql`

## Database Record

- Project: `AutoSpec V4 Plan`
- Project ID: assigned by database migration/runtime
- Artifact type: `ROADMAP_PLAN`
- Artifact version: `4`
- Artifact title: `AutoSpec V4 Agent Orchestration and Evaluation Plan`
- Source: `docs/superpowers/specs/2026-07-02-autospec-v4-agent-orchestration-evaluation-design.md`
- Created at: `2026-07-02`

## Objective

V4 upgrades AutoSpec from a fixed multi-agent generation pipeline into a resume-grade Agent orchestration and evaluation platform. The focus is not adding another UI feature, but making the Agent system itself defensible: configurable SOP workflows, typed node contracts, prompt/model traceability, reproducible evaluation cases, and cross-artifact quality scoring.

The target positioning is:

> AutoSpec is a multi-agent SOP orchestration and evaluation platform for software requirements engineering. It turns natural-language requirements into structured software artifacts, then evaluates completeness, consistency, permissions, API/database alignment, and frontend coverage through rule-based and LLM-assisted reviewers.

## Scope

| ID | Title | Priority | Status | Description | Deliverables |
| --- | --- | --- | --- | --- | --- |
| V4-01 | Configurable SOP Workflow Spec | P0 | PLANNED | Replace hard-coded pipeline assumptions with a versioned workflow spec that declares nodes, edges, input contracts, output contracts, human gates, retry policy, and artifact mapping. | workflow spec schema, built-in `autospec-v4` spec, compatibility adapter for current V2/V3 flow |
| V4-02 | Agent Node Runtime Contract | P0 | PLANNED | Standardize node execution around typed input/output, prompt version, model policy, timeout, retry, and error envelope. | node runtime schema, execution record mapping, contract validation tests |
| V4-03 | Evaluation Case Suite | P0 | PLANNED | Add deterministic benchmark cases for common software requirements so different workflow/prompt/model versions can be compared. | evaluation case schema, sample cases, expected capability checklist |
| V4-04 | Evaluator Agent and Scoring Report | P0 | PLANNED | Add a dedicated evaluator stage that scores generated artifacts across schema validity, feature coverage, API/DB consistency, permissions, frontend coverage, and RAG citation quality. | evaluator schema, scoring rules, report artifact, regression tests |
| V4-05 | Experiment Comparison | P1 | PLANNED | Compare multiple runs by workflow version, prompt version, model config, duration, failure rate, cost, and evaluation score. | experiment run model, comparison API, summary view or export |
| V4-06 | Resume-grade Packaging | P1 | PLANNED | Package the project as a MetaGPT-inspired but enterprise Java-oriented Agent engineering platform. | README update, architecture diagram, sample trace, sample evaluation report |

## Milestones

| ID | Name | Items | Exit Criteria |
| --- | --- | --- | --- |
| M1 | Orchestration Contract | V4-01, V4-02 | A workflow can be loaded from a versioned spec and each node is validated against declared input/output contracts. |
| M2 | Evaluation Baseline | V4-03, V4-04 | At least three benchmark cases can produce scoring reports with deterministic rule scores and optional LLM judge fields. |
| M3 | Experiment Traceability | V4-05 | Runs can be compared by workflow version, prompt version, model config, score, duration, and failure status. |
| M4 | Portfolio Readiness | V4-06 | The repository explains the Agent architecture, evaluation design, and a reproducible demo path suitable for resume review. |

## Acceptance Criteria

- Workflows are represented as versioned structured specs, not only hard-coded Python functions or frontend diagrams.
- Every Agent node declares input schema, output schema, prompt version, model policy, retry policy, and artifact type.
- Evaluator output is a structured artifact with per-dimension scores, issue list, evidence references, and final grade.
- Evaluation cases are reproducible and can run without relying on live LLM output quality.
- Existing V1/V2/V3 generation paths remain usable while V4 adds a more explicit orchestration layer.
- No API keys, model secrets, database passwords, or local absolute paths are stored in generated artifacts or docs.

## Resume Emphasis

This V4 direction supports a stronger resume statement:

> Designed a configurable multi-agent SOP orchestration and evaluation platform inspired by MetaGPT, with typed Agent node contracts, prompt/model traceability, failure retry, human approval gates, benchmark evaluation cases, and rule-based plus LLM-assisted cross-artifact consistency scoring for PRD, architecture, database, API, permissions, and frontend skeleton artifacts.
