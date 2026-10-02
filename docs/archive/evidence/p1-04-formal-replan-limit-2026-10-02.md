# P1-04 正式 Replan 终止证据

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
相关提交：`462753b`、`47fa3f7`、`b88598b`

## 正式 API/Worker 运行

- 隔离 Compose project：`autospec-formal`；候选 WorkflowSpec ID 2；`AGENT_MODEL_MODE=fixture`，不产生外部模型费用。
- fixture-only 需求标记为 `[[fixture-verification-oscillation]]`。该标记让同一个 `TABLE_PRIMARY_KEY_INVALID` 缺陷在每次 verifier 调用中保持存在，用于验证有界终止；live 模式不会启用该分支。
- project 5 / run 5 通过正式 `POST /api/workflow-runs`、Product Manager 审批和 Redis Worker 执行，显式预算为 `600000 tokens / 10 cost / 40 calls`，避免预算边界遮蔽终止原因。

最终运行状态：`FAILED / FAILED`；Product Manager 审批为 `DECIDED / APPROVE`；Backend 节点错误码为 `REPLAN_LIMIT`。其余节点没有被错误地当成成功交付，受 DAG 失败传播影响的节点为 `CANCELLED` 或 `FAILED`。

Backend 持久化 Trace：

| step | phase | status / reason | 关键事实 |
| ---: | --- | --- | --- |
| 1–3 | PLAN / FINAL_CANDIDATE / VALIDATION | 全部 `SUCCEEDED` | 候选先通过结构校验 |
| 4 | OBSERVATION | `FAILED / SPEC_VERIFY_FAILED` | `TABLE_PRIMARY_KEY_INVALID`；fact ref `d2dfa025d0c5557ef8389f44eed24df2e6a1eb5986514c99d04a4cdbba0f1934` |
| 5 | REPLAN | `SUCCEEDED / REPLAN_ACCEPTED` | 反馈保留原 issue |
| 6–7 | FINAL_CANDIDATE / VALIDATION | 全部 `SUCCEEDED` | 第二轮候选仍通过结构校验 |
| 8 | OBSERVATION | `FAILED / SPEC_VERIFY_FAILED` | 相同 issue 和 fact ref；没有第三轮调用 |

第二次规格失败后，`max_replans=1` 立即产生 `REPLAN_LIMIT`，没有进入无限修复、吞掉错误或错误生成 DeliveryGate PASS。execution bundle hash 仍绑定候选冻结包 `414117c26ac2851e35e43f35866bda46a059e3bdac5ad8bfbdb35ee94d05fc3a`。

## 离线回归

```text
D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q tests/test_agent_loop.py tests/test_candidate_verification_loop.py tests/test_spec_verifier_service.py tests/test_spec_verifier.py tests/test_production_handlers.py tests/test_evaluator.py tests/test_review_rules.py tests/test_software_domain_fixtures.py
```

结果：`57 passed`。其中 fixture marker 的两条回归分别证明一次失败后修复成功，以及持续失败后 `REPLAN_LIMIT`。

## 限制

- 这是 fixture-only 终止证据，不代表 DeepSeek live 的自我修复能力或费用。
- 正式缺失/过期/错 scope/低层级 fact 的导出负例仍由后续 P1-G 边界补充；本记录只证明规格失败和震荡终止传播。
