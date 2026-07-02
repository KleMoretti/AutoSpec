insert into project (user_id, name, original_requirement, status)
select 1,
       'AutoSpec V4 Plan',
       'AutoSpec V4 agent orchestration and evaluation roadmap',
       'PLANNED'
where not exists (
    select 1 from project where name = 'AutoSpec V4 Plan'
);

insert into artifact (project_id, type, title, content, format, version, status, source_agent)
select p.id,
       'ROADMAP_PLAN',
       'AutoSpec V4 Agent Orchestration and Evaluation Plan',
       '# AutoSpec V4 Plan

Canonical design spec: docs/superpowers/specs/2026-07-02-autospec-v4-agent-orchestration-evaluation-design.md

Objective: upgrade AutoSpec from a fixed multi-agent generation pipeline into a configurable Agent SOP orchestration and evaluation platform.

Scope:
- V4-01 Configurable SOP Workflow Spec
- V4-02 Agent Node Runtime Contract
- V4-03 Evaluation Case Suite
- V4-04 Evaluator Agent and Scoring Report
- V4-05 Experiment Comparison
- V4-06 Resume-grade Packaging

Acceptance criteria:
- Workflows are versioned structured specs.
- Every Agent node declares input schema, output schema, prompt version, model policy, retry policy, and artifact type.
- Evaluator output is a structured artifact with per-dimension scores, issues, evidence, and final grade.
- Evaluation cases are reproducible without depending on live LLM quality.
- Existing V1/V2/V3 generation paths remain usable.
- No secrets or local absolute paths are stored in generated artifacts.',
       'MARKDOWN',
       4,
       'APPROVED',
       'SYSTEM_PLANNER'
from project p
where p.name = 'AutoSpec V4 Plan'
  and not exists (
      select 1
      from artifact a
      where a.project_id = p.id
        and a.type = 'ROADMAP_PLAN'
        and a.version = 4
  );
