# AutoSpec V5 Agent Execution Eval

This repository now contains a versioned AutoSpec-specific evaluation set and an explicit A/B/C/D ablation matrix for `autospec-v5-agent-execution`.

## Case set

`agent-engine/evaluation/autospec_case_catalog.py` contains eight deterministic cases covering CRUD, approval, permission, multi-entity relationships, external integrations, ambiguous requirements, conflicting constraints, and reviewer-directed rework. Each case declares MUST evidence across API, data, UI, and acceptance criteria, plus allowed/prohibited tools and failure conditions.

The case contract is independent from the historical interview-oriented `EvalCase`. Existing fixture regression remains available through `run_fixture_baseline`; the AutoSpec case set is used by the ablation runner.

## Ablation matrix

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

The candidate must pass deterministic schema, authorization, citation, budget, and traceability gates before it can replace the active `autospec-v5:v5` workflow.
