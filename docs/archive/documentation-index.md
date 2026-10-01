# AutoSpec 归档文档索引

> 归档资料（2026-09-29）：仅供背景、操作参考或历史证据使用，不作为当前任务。唯一当前任务见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

产品名称统一使用 **AutoSpec**，当前文档文件名和标题不再附加产品代际版本号。下列文档仅作归档参考；唯一当前任务为根目录的 Spec Sandbox 计划。

## 参考说明与当前任务链接

| 文档 | 用途 |
| --- | --- |
| [产品概览与能力边界](product-overview.md) | 六节点产品、正式运行入口、当前限制和版本命名边界 |
| [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md) | 后续工作主线；计划中的功能不代表已经实现 |
| [运行时编排 ADR](adr/ADR-001-runtime-orchestration.md) | Java 控制面与 Python Worker 的职责及架构取舍 |
| [后端服务契约](backend-service-contracts.md) | 服务所有权、事务和失败处理边界 |
| [检索与并行工作流](embedding-and-parallelism.md) | Embedding 配置、Shared Contract 和并行分支 |
| [Agent 评测说明](agent-execution-eval.md) | 已实现的消融分组、采集入口与发布门禁 |
| [后端故障演练](backend-failure-drills.md) | 可复现命令及判定方法 |

## 运行与评测证据

- [脱敏运行示例](examples/dynamic-workflow-run.md)：示例中的工作流版本属于历史运行事实。
- [本地发布与容量证据边界](p1-capacity-and-recovery-report.md)：保留记录日期与未执行项，不代表当前环境已经复测。
- [Live smoke 记录](examples/agent-eval-live-smoke-2026-09-17.json)：5 次调用均在 Product Manager 失败，结论为 `NOT_EVALUATED`。
- [Fixture 重试记录](examples/agent-eval-fixture-retry-2026-09-16.json)及 [PRD Schema fixture 记录](examples/agent-eval-prd-schema-fixture-2026-09-17.json)：只支持相应离线验证结论。
- [历史文档目录](README.md)：过时方案、早期基线及阶段执行记录。

## 维护规则

`docs/` 根目录仅保留用户指定的当前任务文件，其他资料均在本归档目录中维护。历史契约、迁移和评测 JSON 中的标识保持不变。
