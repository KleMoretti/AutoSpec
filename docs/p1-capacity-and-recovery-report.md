# AutoSpec V5 P1 容量与恢复报告

这是一份面向真实部署的测量模板。当前仓库不填充未经执行的容量数字。

## 测量环境

| 项目 | 值 |
|---|---|
| Git revision | 待填写 |
| 部署方式 | Docker Compose / 其他：待填写 |
| Backend 实例数 | 待填写 |
| Worker 实例数与 `WORKER_MAX_CONCURRENCY` | 待填写 |
| `WORKER_GLOBAL_LLM_CONCURRENCY` / `WORKER_PER_USER_CONCURRENCY` | 待填写 |
| `WORKFLOW_ADMISSION_MAX_ACTIVE_RUNS_PER_USER` | 待填写 |
| 模型模式与模型版本 | 待填写 |
| 数据集/需求规模 | 待填写 |
| 测量时间 | 待填写 |

## 容量与时延

每个场景至少完成一轮预热和一轮稳定采样；报告只记录真实观测值。

| 场景 | 并发运行数 | 吞吐（runs/min） | 排队 P50/P95 | 运行 P50/P95 | Token/run | 成本/run | 备注 |
|---|---:|---:|---:|---:|---:|---:|---|
| Fixture 核心成功流程 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| Live 模型核心成功流程 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 单用户并发上限 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 拒绝数/Retry-After |
| 全局 LLM 并发上限 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 模型调用峰值 |

## 恢复演练

使用现有 `docs/backend-failure-drills.md` 的步骤，记录故障注入、可观察信号和最终状态。每个场景只需要覆盖一次成功恢复和一次明确不可恢复/进入 DLQ 的结果。

| 场景 | 注入点 | 预期行为 | 实际结果 | 恢复耗时 | 恢复率 | DLQ/重复消息数 | Trace/Run ID |
|---|---|---|---|---:|---:|---:|---|
| Outbox 积压 | 待填写 | 新运行按 Retry-After 背压 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 模型限流 | 待填写 | 受限重试，预算不重复结算 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 工具超时 | 待填写 | 节点失败/重试或降级 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| Worker 崩溃 | 待填写 | XAUTOCLAIM 后由新 Worker 接管 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| Redis 短暂不可用 | 待填写 | 控制面重试，不制造重复终态 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |

## 结论

- 稳定容量边界：待填写（以 P95 排队/运行时延和失败率共同确定）。
- 单用户公平性：待填写（说明是否观察到单用户占满全局槽位）。
- 恢复结论：待填写（说明哪些故障自动恢复、哪些进入人工处理）。
- 成本结论：待填写（使用运行指标接口的 Token/成本数据）。

建议使用的收尾验证：

以下命令从仓库根目录执行：

```powershell
Set-Location 'backend'
mvn test

Set-Location '..\agent-engine'
python -m pytest -q

Set-Location '..'
python scripts/verify_workflow_contract.py
docker compose config --quiet
docker compose --profile monitoring config --quiet
```
