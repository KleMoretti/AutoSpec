# AutoSpec 产品概览与能力边界

> 归档资料（2026-09-29）：仅供背景、操作参考或历史证据使用，不作为当前任务。唯一当前任务见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

更新日期：2026-09-29。

AutoSpec 是基于多 Agent 协作的软件需求分析、原型生成与交付验证平台。正式生成入口为后端 `POST /api/workflow-runs`；MySQL 保存业务事实，Java 控制面按冻结的 WorkflowSpec 调度，Redis Streams 传递命令与事件，Python Worker 执行节点。

## 六节点工作流

```text
Product Manager → Architect → {Backend Engineer ∥ Frontend Engineer} → Reviewer → Evaluator
```

Product Manager 生成 PRD、故事与验收条件；Architect 生成架构和 Shared Contract；Backend 与 Frontend 分别生成数据/API 设计和页面/API 绑定骨架；Reviewer 检查一致性并给出定向返工；Evaluator 检查追踪覆盖与交付门禁。

系统还包含人工审批、Artifact 版本、取消、重试、恢复、回放、死信、项目级检索、预算、审计、诊断和 Markdown/PDF/ZIP 导出。Agent API 只承担健康检查与评测接口，业务执行不走同步 `/generate*` 流水线。

## 命名与兼容

产品、页面、图示和新增文档统一称为 **AutoSpec**。当前数据库中的工作流 ID 仍为 `autospec-v5`，默认发布标识为 `v5-parallel`；原 `v5` 用于历史运行与回放。这些是已有机器标识，不是后续产品名称。此次文档清理不迁移 ID，也不改变当前激活版本。

可执行契约见 [默认并行工作流](../../agent-engine/contracts/autospec-v5-parallel.workflow.json)，API 见 [OpenAPI](../../backend/src/main/resources/contracts/autospec.openapi.yaml)。已发布契约和 Flyway 历史保持不可变。

## 已知限制

- 默认工作流没有开启节点内 Agent Loop 或工具策略；候选 Backend 循环已有实现，但没有成功的 live 循环收益证据。
- 仓库保存的 live smoke 只有一个业务用例的 5 次尝试，全部在 Product Manager 失败；不能据此声称 live 已跑通，也不能推断所有历史运行均失败。
- 默认审查规则含领域关键词及 AutoSpec 自身接口约束，可能对其他业务误报。
- 当前交付校验包含静态检查、两份 Java 模板编译及 mock 响应检查；没有证明数据库可落库、完整前端可构建或业务行为正确。
- 本地默认使用 hashing embedding；真实 embedding 适配器已存在。三个离线检索用例不能证明语义检索收益。
- 项目记忆已有版本、来源和替代机制；批准状态约束、相关性召回及效果证据仍需完善。
- 控制面租约超时自动回收存在缺口，不能把全部故障恢复场景都视为已验证。

后续工作见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。代码中已实现、默认已启用、fixture 已通过及 live 已验证应分别说明。
