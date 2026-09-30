# AutoSpec：AI-Agent P0 实施基线

> 历史归档（2026-09-29）：保留当时的设计、状态和执行证据，不作为当前能力或待办清单。当前说明见 [文档索引](documentation-index.md)，后续工作见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

本文记录 `interview-agent-roadmap.md` 在当前 AutoSpec V5 产品中的落地方式。

## 边界

当前产品仍是唯一的 `autospec-v5:v5` 工作流，固定职责节点保持为 Product Manager、Architect、Backend Engineer、Frontend Engineer、Reviewer 和 Evaluator。动态决策只负责条件路由、定向返工、审批等待和结束/失败判断；DAG 顺序、权限、预算和持久化继续由 Spring Boot 控制面负责。

## P0 交付

- `agent-engine/schemas/agent_state.py`：版本化 `AgentState`、`RouteDecision`、节点步骤和终止策略；`runtime/agent_state.py` 提供预算/覆盖率/截止时间判断。
- `runtime/agent_router.py`：把已通过 Schema 校验的 Reviewer/Evaluator 结果转换为带理由和证据的路由决定。
- MySQL `workflow_node_run` 的成功输出继续作为事实检查点；`WorkflowReconciler` 从最近成功 revision 继续，Reviewer 返工决策写入 `workflow_transition` 并保留 issue、修改范围和理由。
- `runtime/memory.py` 与 `runtime/context_builder.py`：短期原始消息、project-scoped 的 Requirement/Decision/Constraint/Entity/API/Artifact facts、结构化增量摘要，以及复用 V5 Context Policy 的上下文预算。正式 facts 由 Spring Boot 控制面版本化持久化，进程内实现只用于 fixture。
- `schemas/workflow_spec.py`、Java WorkflowSpec 解析/校验和 Redis 命令：增加可选的 `tool_policy`。未声明时工具关闭；声明后由 Worker 的 `ToolRegistry`/`ToolHarness` 执行 Schema、白名单、权限、幂等、超时、重试、熔断、限流和结果大小约束。
- `schemas/evaluation.py` 与 `evaluation/control_plane.py`：提供版本化 `AutoSpecEvalCase`/`AutoSpecEvalRun`、MUST 追踪覆盖、Tool 违规、延迟、Token、成本与确定性发布门禁。正式评测只经 `POST /api/workflow-runs` 收集事实：

  ```powershell
  Set-Location 'agent-engine'
  python -m evaluation.run_control_plane --config .\evaluation-config.json --output ..\artifacts
  ```

评测输出只保存引用、版本、耗时、调用台账摘要和路由理由，不保存原始简历、需求或模型回答。RAG 指标在节点级检索能力完成后再填充，不以空数据冒充结果。

## 结果解释

P0 增强的是现有 V5 的可解释性、边界约束、记忆组织和可复现评测，不把六节点产品重写成无界自主 Agent。工具注册表暂不预置任意 Shell、HTTP 或数据库直连；具体工具只有在 WorkflowSpec 明确声明并由 Worker 注入注册实现后才可调用。
