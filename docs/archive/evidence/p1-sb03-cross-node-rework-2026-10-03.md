# SB-03 跨节点责任返工成功收敛

日期：2026-10-03（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`  
隔离 Compose project：`autospec-formal`

## 正式 run

- project `15` / run `13`，显式选择 WorkflowSpec version id `3`、`spec-sandbox-explicit-v2`。
- requirement 含 fixture-only 标记 `[[fixture-cross-node-rework]]`；不调用 DeepSeek。
- 最终状态：`COMPLETED`；六节点最终均 `SUCCEEDED`。

责任路由与 revision 变化：

| 节点 | revision 1 | revision 2 | 结论 |
| --- | --- | --- | --- |
| Reviewer v5 | `STALE`，首次输出固定 HIGH `FIXTURE_CROSS_NODE_REWORK` 并路由 `backend_engineer` | `SUCCEEDED` | Reviewer 返工后重新汇合 |
| Backend v7 | `STALE`，被 Reviewer route 标记失效 | `SUCCEEDED` | 新 revision 由合法 REWORK 边调度 |
| Evaluator v4 | — | `SUCCEEDED` | 消费返工后的最终 Artifact/事实 |

## 交付结果

- 编辑前 readiness：`BUILD_REQUIRED`。
- `POST /api/projects/15/code-skeleton`：成功，`autospec-project-15-skeleton.zip`。
- 编辑后 readiness：`READY`，`specReady=true`、`buildReady=true`。
- `POST /api/projects/15/export?format=MARKDOWN`：成功，`autospec-project-15.md`。

## 结论与限制

- Reviewer→Backend 的 owner 路由、下游失效、revision/fencing 和再次 Reviewer 汇合已通过真实 API/Redis Worker/控制面验证，不是单测 mock。
- 初次实现曾错误把 fixture marker 放入通用 single-shot Handler，project 14/run 12 以 `HANDLER_ERROR` 终止；`348230c` 修正为 Reviewer-only 分支后，run 13 成功。该失败样本保留为实现边界证据。
- 该样本是 fixture-only；live 模型自我修复、真实 `.env` 冷启动和 P2 live 对照仍受外部 Key/预算条件限制。
