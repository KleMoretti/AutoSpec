# P1-04 正式 verifier 失败到 Replan 修复证据

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
相关提交：`47fa3f7`、`6732e26`、`66d410a`

## 隔离条件

- 使用独立 Compose project `autospec-formal` 和独立 MySQL/Redis/verify-mysql 卷；配置来自 `.env.example`，未读取或修改真实 `.env`、已有业务容器或业务卷。
- `AGENT_MODEL_MODE=fixture`，不调用 DeepSeek 或其他外部模型。Agent API、两个 Redis Worker、后端和 verifier 使用当前源码镜像。
- 候选 `spec-sandbox` 的数据库版本 ID 为 2，只在该隔离数据库显式选择，默认 active 仍为原版本。
- 需求包含 fixture-only 标记 `[[fixture-verification-failure]]`。该标记只在 `model_client is None` 且首次验证时注入缺陷，live 路径不会启用；缺陷为把一个主键标记为非主键，预期错误码为 `TABLE_PRIMARY_KEY_INVALID`。

## 预算边界 run 3

首次重跑未传执行策略，使用默认 `BALANCED`（150000 token）。Backend 首轮失败后进入 Replan 并完成第二轮 `SPEC_VERIFY_PASSED`，但其他并行节点的最坏情况预占超过剩余 run 预算，最终：

- project 3 / run 3：`FAILED`，`responseStatus=BUDGET_EXCEEDED`。
- Evaluator：`CANCELLED / BUDGET_EXCEEDED`；Frontend：`FAILED / BUDGET_PREAUTH_FAILED`；Reviewer：`CANCELLED / BUDGET_EXCEEDED`。
- 该结果证明预算不足会阻断运行，而不会被吞掉或伪装成成功；它不作为 Replan 成功链路的验收样本。

## 正式成功 run 4

run 4 显式使用一次性 fixture 验收策略：`qualityProfile=DEEP`、`maxTokens=600000`、`maxCost=10`、`maxModelCalls=40`、`maxWallTimeMs=2700000`。这是为覆盖 Backend 二次验证预占而设置的测试边界，不代表 live 费用或默认策略。

- project 4 / run 4：`COMPLETED`，`responseStatus=COMPLETED`；Product Manager 审批 ID 4 为 `DECIDED / APPROVE`，lock version 为 1。
- 六个节点均为 `SUCCEEDED`，两个 Worker 均实际执行节点；fixture 模式模型调用计数为 8，外部模型费用不适用。
- execution bundle hash：`414117c26ac2851e35e43f35866bda46a059e3bdac5ad8bfbdb35ee94d05fc3a`。

Backend node 的持久化 Trace 顺序如下：

| step | phase | status / reason | 关键事实 |
| ---: | --- | --- | --- |
| 1 | PLAN | `SUCCEEDED / PLAN_ACCEPTED` | — |
| 2–3 | FINAL_CANDIDATE / VALIDATION | `SUCCEEDED / CANDIDATE_READY`、`VALIDATION_PASSED` | candidate hash `6e265332fc63f35b6f5f6409dde215e4a154b1e977354c66ca00bc3f2b8a9ef1` |
| 4 | OBSERVATION | `FAILED / SPEC_VERIFY_FAILED` | issue `TABLE_PRIMARY_KEY_INVALID`；failed fact ref `f870ac2c7cdb47ab3224e30d6b823cf822f43830c34f571c0fa029deeeedce2e` |
| 5 | REPLAN | `SUCCEEDED / REPLAN_ACCEPTED` | 只允许一次 bounded Replan；反馈保留同一 issue code |
| 6–7 | FINAL_CANDIDATE / VALIDATION | `SUCCEEDED / CANDIDATE_READY`、`VALIDATION_PASSED` | 候选结构再次通过 |
| 8 | OBSERVATION | `SUCCEEDED / SPEC_VERIFY_PASSED` | passed fact ref `863c469668abaef0541c591acc53dac45117cf0d1e8e14e8f40f7a985a27df45` |
| 9 | FINISH | `SUCCEEDED / COMPLETED` | 绑定 passed fact ref |

Reviewer 的 `FULL/L2` verifier、Evaluator 和六节点最终状态也均为成功；本次目标是验证正式 Replan Trace，先前 run 2 已记录 DeliveryGate `READY` 及 ZIP/Markdown/PDF 导出。

## 仍未关闭的边界

- 预算耗尽已经有正式负例，但预算/震荡终止的专门 Trace、缺失/过期/错 scope/低层级 fact 的正式导出负例仍待执行。
- verifier 的运行期网络、PID/内存/输出限制、只读文件系统和清理回收探针，以及 Sandbox 专项 CI 仍未完成。
- 本证据是 fixture-only；不能推导 DeepSeek live 的自我修复质量或费用结果。
