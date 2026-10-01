# AutoSpec 可核验问答卡片

这 15 张卡片只引用当前工作区源码和本轮脱敏证据；“通过”仅表示相应本地测试或静态检查通过，不代表 live、生产 SLA 或质量晋级。

## 1. 控制面与执行面如何分工？

- 一句话答案：Spring Boot 控制面持久化事实并调度，Redis Streams 与 Python Worker 执行冻结节点。
- 调用链：`POST /api/workflow-runs` → DAG/Reconciler → Outbox/Streams → Worker → 事件投影与 Artifact。
- 代码：[WorkflowReconciler](../../backend/src/main/java/com/autospec/workflow/runtime/WorkflowReconciler.java#L18)、[worker](../../agent-engine/runtime/worker.py#L1)。证据：本轮合同校验和 Compose 配置检查通过。
- 失败场景：控制面事件消费失败时不 ACK，保留 pending 事件；取舍是至少一次投递而非假设恰好一次。

## 2. 动态 DAG 如何支持并行和返工？

- 一句话答案：拓扑来自冻结 WorkflowSpec，编译后的 edges 决定 Backend/Frontend 并行与 Reviewer 汇合，返工只重排受影响节点。
- 调用链：WorkflowSpec → [DagCompiler](../../backend/src/main/java/com/autospec/workflow/runtime/DagCompiler.java#L19) → [WorkflowReconciler](../../backend/src/main/java/com/autospec/workflow/runtime/WorkflowReconciler.java#L18)。
- 证据：六节点契约由 `scripts/verify_workflow_contract.py` 校验；不把前端连线硬编码进页面。
- 失败场景：条件边缺输入或返工目标不合法时拒绝调度；取舍是宁可阻断也不猜测路径。

## 3. Backend 循环怎样终止？

- 一句话答案：Backend 才允许有界 plan/act/observe/validate/replan 循环，并受最大步数、重规划次数、无进展与物理调用预算共同限制。
- 调用链：[run_backend_agent_loop](../../agent-engine/runtime/backend_agent_loop.py#L76) → structured turn → tool/verifier → validation/replan。
- 证据：`tests/test_agent_loop.py` 与候选验证循环测试通过。
- 失败场景：震荡或达到预算会生成终止事实；取舍是保留失败证据，不无限重试。

## 4. 结构化输出失败如何处理？

- 一句话答案：模型输出先做 JSON/Pydantic 校验，允许的修复至多一次并计入同一预算，截断和空响应不会伪装成成功。
- 调用链：[OpenAICompatibleModelClient](../../agent-engine/model_gateway.py#L90) → structured-output parser → handler schema。
- 证据：`tests/test_model_gateway.py`、Agent Engine 全量 pytest 通过。
- 失败场景：`MODEL_OUTPUT_LIMIT` 或 schema invalid 进入结构化失败路径；取舍是不拼接残缺 JSON。

## 5. 函数调用协议如何避免静默降级？

- 一句话答案：模型协议显式标为 `JSON_OBJECT` 或 `NATIVE_TOOL_CALL`，供应商不声明 native 能力或没有 adapter 时直接失败。
- 调用链：[model_protocol](../../agent-engine/runtime/model_protocol.py#L10) → model policy → model gateway。
- 证据：`test_native_tool_protocol_does_not_fallback_to_json` 通过。
- 失败场景：native 响应被当作普通 JSON 时拒绝；取舍是少一次“看似成功”的兼容调用。

## 6. 工具治理与 MCP 取舍是什么？

- 一句话答案：当前只提供固定、版本化、可审计的受控工具目录；没有具体外部 MCP 需求，因此未引入 MCP adapter。
- 调用链：[ToolHarness](../../agent-engine/runtime/tool_harness.py#L232) → [register_controlled_gateway_tools](../../agent-engine/runtime/tool_gateway.py#L232) → Java Tool Gateway。
- 证据：`test_tool_injection_dataset.py` 覆盖 30 行攻击数据；MCP 状态仍为 deferred。
- 失败场景：未知工具、越权参数、范围越界或权限不符返回结构化错误；取舍是目录小而边界清晰。

## 7. RAG 与 embedding 如何保持项目边界？

- 一句话答案：检索按项目、用户权限、语料、epoch 和固定策略过滤，引用随节点输入冻结；真实外部 embedding 对照本轮未执行。
- 调用链：[KnowledgeIndexService](../../backend/src/main/java/com/autospec/service/KnowledgeIndexService.java#L33) → project retrieval → node input snapshot。
- 证据：本地 hash 检索 gold 数据集与检索策略测试通过；semantic provider 标为未评估。
- 失败场景：过期、无权限或跨项目文档被过滤；取舍是宁可少召回也不跨租户泄露。

## 8. 记忆如何批准、版本化和召回？

- 一句话答案：Project memory 事实带版本、来源和 trust status，Worker 节点只召回 APPROVED、有效且未冲突事实。
- 调用链：[ProjectMemoryService](../../backend/src/main/java/com/autospec/service/ProjectMemoryService.java#L28) → [WorkflowNodeInputAssembler](../../backend/src/main/java/com/autospec/workflow/runtime/WorkflowNodeInputAssembler.java#L1)。
- 证据：V2 trust/index migration、ProjectMemoryService 定向测试和后端编译通过。
- 失败场景：UNTRUSTED 或过期事实不进入生成输入；取舍是保留旧兼容读取 API，但正式节点走 trusted API。

## 9. 如何测试注入和权限边界？

- 一句话答案：用 30 个稳定 case 覆盖未知工具、无效参数、幂等冲突、范围逃逸和权限逃逸，并在受控网关处断言错误码。
- 调用链：[tool_injection_cases](../../agent-engine/evaluation/tool_injection_dataset.py#L30) → ToolGatewayRequest/ToolHarness → structured error。
- 证据：30 行数据和代表性异步边界测试通过。
- 失败场景：请求不能因“看起来像工具调用”而获得执行权；取舍是不把 prompt 文本当权限。

## 10. MySQL/Redis 如何处理幂等？

- 一句话答案：Outbox/event projection 以 event/request identity 和持久记录去重，Redis Streams 只承担至少一次传输。
- 调用链：[WorkflowEventPoller](../../backend/src/main/java/com/autospec/workflow/transport/WorkflowEventPoller.java#L7) → event consumer；[WorkflowOutboxPublisher](../../backend/src/main/java/com/autospec/workflow/transport/WorkflowOutboxPublisher.java#L18) → command stream。
- 证据：V1→V2 H2 Flyway 启动、Outbox/Poller 定向测试通过。
- 失败场景：重复消息被 ACK 为 duplicate 或 poison；取舍是接受重复投递，不重复业务副作用。

## 11. 租约、fencing 与取消如何互相约束？

- 一句话答案：心跳过期由 CAS 抢占为 ORPHANED，再创建新 attempt；fencing token 和终态检查拒绝旧 Worker 迟到结果，取消与审批互斥。
- 调用链：[WorkflowRecoveryService](../../backend/src/main/java/com/autospec/workflow/runtime/WorkflowRecoveryService.java#L23) → node CAS/replacement；[WorkflowRunServiceImpl](../../backend/src/main/java/com/autospec/service/impl/WorkflowRunServiceImpl.java#L22)。
- 证据：Recovery、Run、Approval 定向测试通过；真实 Redis 中断演练未执行。
- 失败场景：旧 attempt 在取消后回报成功不得创建 Artifact；取舍是允许孤儿记录留存以便审计。

## 12. 人工审批和定向返工怎样保持历史？

- 一句话答案：审批决策写入持久状态并带乐观锁，Reviewer route 只使受影响下游失效，旧 Artifact/Workflow snapshot 不覆盖。
- 调用链：[WorkflowApprovalServiceImpl](../../backend/src/main/java/com/autospec/service/impl/WorkflowApprovalServiceImpl.java#L44) → Reviewer rework coordinator → reconciler。
- 证据：Approval service 与 workflow lifecycle 测试通过。
- 失败场景：过期 approval 或已取消 run 被拒绝；取舍是冲突返回而不是自动替用户选择。

## 13. Spec Sandbox 和交付门禁分别保证什么？

- 一句话答案：verifier 检查显式契约与编译/规则事实，DeliveryGate 再检查 trusted verification、MUST trace 和阻断问题后才允许交付。
- 调用链：[verify_payload](../../agent-engine/spec_verifier/service.py#L29) → Evaluation report → [DeliveryGateService](../../backend/src/main/java/com/autospec/service/DeliveryGateService.java#L30)。
- 证据：显式 artifact adapter、TTL 字段和合同哈希已同步；没有把 fixture L2 当正式 live L2。
- 失败场景：fact 过期、scope 不匹配或 HIGH/CRITICAL 问题阻断；取舍是交付安全优先于输出完整。

## 14. 消融评测如何统计？

- 一句话答案：A/B/C/D 只改变预声明能力，完整 holdout 才能做一次晋级判断，Wilson 区间和按 Case ID 的 cluster bootstrap 保留重复相关性。
- 调用链：[evaluate_release_gate](../../agent-engine/evaluation/ablation.py#L142) → [wilson_interval](../../agent-engine/evaluation/statistics.py#L16) / paired bootstrap → gate decision。
- 证据：8/16/8 split freeze、统计边界和 gate 测试通过；live/paid P2-E 因未授权未执行。
- 失败场景：缺组、缺 rubric、重复或未知费用为 NOT_EVALUATED；越权执行为 REJECT。

## 15. 成本、可观测和失败复盘如何落账？

- 一句话答案：模型调用、Token、成本、Prompt/Schema/Route、Trace 和预算预占分别进入结构化台账，未知费用保持未知。
- 调用链：[BudgetLedger](../../agent-engine/evaluation/budget_ledger.py#L20) + [record_model_invocation](../../agent-engine/runtime/model_telemetry.py#L156) + [WorkflowRuntimeMetricsService](../../backend/src/main/java/com/autospec/service/WorkflowRuntimeMetricsService.java#L35)。
- 证据：本轮全量 Agent/前端测试、后端定向测试和 Compose 静态配置检查通过。
- 失败场景：预算耗尽停止新 case，已提交 run 保留并采集；取舍是报告阶段交接而不是伪造完整实验。
