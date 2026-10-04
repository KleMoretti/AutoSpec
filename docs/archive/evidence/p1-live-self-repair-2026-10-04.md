# P1 live / Sandbox / `.env` 验收证据

日期：2026-10-04（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`

## 已完成的外部边界

- 真实 `.env` 冷启动使用隔离 Compose project `autospec-realenv-20261003`：Backend readiness、Agent health、Frontend HTTP 和 verifier 依赖均通过；`AGENT_MODEL_MODE=live`、模型 Key、Agent token、verifier token 与 verifier MySQL DSN 均已注入。密钥值未回显，隔离卷未删除。
- 远端 Sandbox CI run `37175149711`（head `342b2296`）成功，五个 job 全绿：`compose-and-images`、`frontend-tests`、`verifier-sandbox-probes`、`backend-tests`、`agent-tests`。
- 跨节点责任返工 fixture 正式证据为 project 15/run 13：Reviewer revision 1 产生责任路由，Backend revision 1 失效，revision 2 成功，Reviewer revision 2 成功；最终交付门禁 READY，ZIP/Markdown 导出成功。

## DeepSeek Flash live 逐次证据

以下运行均使用真实 `.env`、`deepseek-flash`、隔离 live 栈和 D 组单用例；没有 fixture 回退。每次预算预注册上限为 5 CNY，实际成本合计 `1.275619 CNY`。

| run | 候选 | 结果 | 关键事实 |
| ---: | --- | --- | --- |
| 9 | v5 | `FAILED` | Frontend 成功；Backend 因旧 smoke `250000` token 上限不足以预占 `56000×5+8000×5`，`BUDGET_PREAUTH_FAILED` |
| 10 | v5b | `FAILED` | Backend Loop 完成；外层共享契约拒绝 `serialNumber` 与 `serial_number` 字段漂移 |
| 11 | v6 | `FAILED` | Backend Loop 完成；`archivedAt` 的 `DATETIME` kind 漂移 |
| 12 | v7 | `FAILED` | Backend `SCHEMA_INVALID → REPLAN → SPEC_VERIFY_PASSED` 成功；Reviewer FULL/L1 阻断 binding `details` 类型不一致 |
| 13 | v8 | `FAILED` | Backend/Reviewer 前置通过；Frontend 结构修复仍回退旧 page 字段 |
| 14 | v9 | `FAILED` | Backend/Frontend/Reviewer 成功；Evaluator 阻断 project-scoped archive/audit API 缺 auth/roles |
| 15 | v10 | `FAILED` | Architect 结构修复后仍使用旧 `description/rationale` 决策字段 |
| 16 | v11 | `FAILED` | Architect 结构修复后 `shared_contract` 仍产生 Schema 外字段，未进入下游 |

结论：Backend 的 live 有界自我修复（包括一次 Replan、工具事实、再次验证和 FINISH）已获得真实证据；完整六节点 live 自我修复/交付尚未通过，最后阻塞是 Architect 结构化输出。上述失败全部保留，不把部分成功升级为完整 live 通过。

## P2 live 对照

- explicit v2 A/B/C/D development/holdout manifest 的 validate-only 已通过（development 16 cases、holdout 8 cases）。
- 完整批次保守上限仍为 `384 + 192 = 576 CNY`；本轮只执行了单组单用例诊断，没有启动矩阵，也没有填写质量收益、置信区间或 PROMOTE 结论。
- P2-E 继续 `blocked/NOT_EVALUATED`；默认 active、历史 WorkflowSpec、数据库基线和已发布 v12 均未切换或改写。
