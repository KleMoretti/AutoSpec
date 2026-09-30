# AutoSpec Agent Execution Eval

> 归档资料（2026-09-29）：仅供背景、操作参考或历史证据使用，不作为当前任务。唯一当前任务见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

This repository now contains a versioned AutoSpec-specific evaluation set and an explicit A/B/C/D ablation matrix for `autospec-v5-agent-execution`.

## Case set

`agent-engine/evaluation/autospec_case_catalog.py` contains eight deterministic cases covering CRUD, approval, permission, multi-entity relationships, external integrations, ambiguous requirements, conflicting constraints, and reviewer-directed rework. Each case declares MUST evidence across API, data, UI, and acceptance criteria, plus allowed/prohibited tools and failure conditions.

The case contract is `AutoSpecEvalCase`; the historical interview-oriented `EvalCase`, fixture runner, case catalog, and generic experiment comparison compatibility layer have been removed. The FastAPI surface now exposes this catalog and the same deterministic `AutoSpecEvalRun` release gate used by the control-plane collector.

## Existing ablation matrix

This is the implemented experiment matrix. The verifier-feedback experiment proposed in [Spec Sandbox](../autospec-v5-spec-sandbox-plan.md) is a separate future design; do not reuse historical group labels without recording the new configuration.

| Group | Loop | Tools | Replan |
| --- | --- | --- | --- |
| A | disabled | disabled | disabled |
| B | enabled | disabled | enabled |
| C | enabled | enabled | disabled |
| D | enabled | enabled | enabled |

Run the planner/collector from `agent-engine`:

```powershell
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -c "import asyncio, json; from evaluation.ablation import run_ablation_matrix; print(json.dumps(asyncio.run(run_ablation_matrix()).model_dump(mode='json'), ensure_ascii=False, indent=2))"
```

Without a live control-plane adapter, every group is returned as `NOT_EXECUTED` and its metrics remain explicitly unmeasured. Fixture output is never relabeled as live tool, cost, retrieval, or recovery evidence. A deployment runner must execute the same case list through `POST /api/workflow-runs` and collect Trace/Artifact results before returning measured `AutoSpecEvalRun` records.

The candidate must pass deterministic schema, authorization, citation, budget, and traceability gates before it can replace the active `autospec-v5:v5-parallel` workflow.

The Agent API exposes `POST /evaluation/release-gate` for a reviewed A/D pair. It never promotes fixture or incomplete evidence.
