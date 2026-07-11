# AutoSpec V5 配置驱动的生产级 Agent 工作流设计

## 1. 背景与目标

AutoSpec V4 已具备 `WorkflowSpec`、节点契约、评测、任务记录和工作流快照，但 V4 运行时仍复用硬编码的顺序调用。V5 将工作流配置升级为真实运行契约：修改节点、依赖、条件、审批或重试策略时，不再修改编排代码。

V5 的目标包括：

- 根据版本化 DAG 配置执行拓扑排序、依赖解析和并行调度。
- 支持节点级超时、指数退避、错误分类和降级 Handler。
- 支持条件分支及 Reviewer 按问题归属定向打回。
- 通过 MySQL Checkpoint 和 reconciliation 实现服务重启后的断点恢复。
- 为任意节点配置前置或后置人工审批，并在审批后继续原运行。
- 使用冻结的工作流、Prompt、模型和输入快照回放历史版本。

V5 不建设通用 BPMN 引擎，不允许运行用户脚本，不支持无界循环。系统仅执行平台注册并经过版本校验的 Handler、Schema、Prompt 和条件操作符。

## 2. 架构与职责

V5 采用 Spring Boot 控制面、Redis Streams 消息总线、Python Worker 执行面和 MySQL 状态存储。

```text
React 控制台
  -> Spring Boot 控制面
       -> MySQL：工作流定义、版本、运行、节点、审批、Checkpoint、Outbox
       -> Redis Stream：autospec.workflow.commands
  -> Python Agent Worker Consumer Group
       -> Redis Stream：autospec.workflow.events
  -> Spring Boot Event Consumer
       -> MySQL 状态转换
       -> DAG Reconciler
       -> SSE 运行事件
```

Spring Boot 是 DAG 调度、状态机、审批、恢复、回放和审计的所有者。Python Worker 注册并执行单个 Agent Handler，负责输入输出校验、单次超时检测和执行事件上报，但不决定后继节点、重试或工作流最终状态。MySQL 是唯一事实来源；Redis Streams 仅提供至少一次消息传输。

LangGraph 可以继续用于单个复杂 Agent 内部的推理流程，但不再拥有跨节点工作流的持久化生命周期。

## 3. WorkflowSpec V5

工作流版本包含以下配置：

- `workflow_key`、语义版本、描述和内容哈希。
- 工作流最大并行度、全局超时和最大 Reviewer 循环次数。
- 节点 Handler、输入输出 Schema、Prompt、模型策略及其版本。
- 节点依赖、输入映射、超时、并发限制和产物类型。
- 可重试错误、最大尝试次数、指数退避和抖动参数。
- 降级 Handler、降级模型及失败后的终止或跳过策略。
- `BEFORE_NODE`、`AFTER_NODE` 或 `NONE` 审批模式。
- 普通执行边、条件边和受控 `REWORK` 边。

发布前执行静态校验：

- 节点 ID 唯一，所有引用存在。
- 普通执行边无环，至少存在一个入口和一个终点。
- 不存在不可达节点。
- Rework 边只能指向白名单节点，且工作流必须配置最大循环次数。
- Handler、Schema、Prompt 和模型策略版本均已注册。
- 输入映射只能读取全局输入或声明的上游输出。
- 条件表达式只能使用受限 JSON 路径和平台注册操作符。
- 超时、重试、并行度和审批配置位于允许范围内。

已发布版本不可修改或删除。创建运行时，将完整 Spec 及其依赖版本冻结为 Workflow Snapshot。

## 4. DAG 编译与动态调度

`DagCompiler` 将 WorkflowSpec 编译成不可变运行图，生成邻接表、反向依赖表、拓扑层级和输入映射计划。普通边必须构成 DAG；Rework 边不参与普通拓扑排序。

`WorkflowReconciler` 在运行创建、节点完成、审批决定、重试到期和定时恢复时执行：

1. 读取 Workflow Snapshot 和当前节点运行状态。
2. 找出依赖均成功且条件成立的 `PENDING` 节点。
3. 不满足条件且已不可能被激活的节点标记为 `SKIPPED`。
4. 按工作流及节点并行度选出可运行节点。
5. 在同一数据库事务内将节点改为 `QUEUED` 并写入 Outbox。
6. Outbox Publisher 将 `EXECUTE_NODE` 命令投递到 Redis Stream。
7. 所有有效终止节点成功后，将运行标记为 `SUCCEEDED`。

无依赖节点或同一拓扑层中依赖已经满足的节点可以并行投递。每次 reconciliation 必须可重复执行，并通过条件更新和唯一键避免重复调度。

## 5. Redis Streams 与 Worker 协议

使用以下 Streams：

- `autospec.workflow.commands`：`EXECUTE_NODE`、`CANCEL_NODE`、`HEALTH_CHECK`。
- `autospec.workflow.events`：`NODE_STARTED`、`NODE_HEARTBEAT`、`NODE_SUCCEEDED`、`NODE_FAILED`、`NODE_CANCELLED`。
- `autospec.workflow.dlq`：超过传输重试上限的畸形或不可处理消息。

Python Worker 使用 Consumer Group 横向扩展。节点命令包含 `event_id`、`workflow_run_id`、`node_id`、`revision`、`attempt`、`execution_id`、冻结的节点配置和输入快照。

Worker 完成处理并成功发布结果事件后才确认命令。失联消息通过 Pending Entries List 和 `XAUTOCLAIM` 重新认领。系统提供至少一次传输语义，通过幂等状态机实现单个 `execution_id` 只接受一个有效终态。

## 6. 状态机与并发一致性

工作流状态包括：

```text
CREATED -> RUNNING -> WAITING_APPROVAL -> RUNNING -> SUCCEEDED
                   -> FAILED | CANCELLED | MANUAL_INTERVENTION
```

节点状态包括：

```text
PENDING -> READY -> QUEUED -> RUNNING -> SUCCEEDED
                              -> RETRY_WAIT -> READY
                              -> FALLBACK_READY -> QUEUED
                              -> WAITING_APPROVAL
                              -> FAILED
```

辅助状态包括 `STALE`、`SKIPPED`、`CANCELLED` 和 `ORPHANED`。

状态转换必须同时校验旧状态和 `lock_version`。节点逻辑执行标识采用：

```text
workflowRunId + nodeId + revision + attempt
```

Reviewer 重做会增加 revision；普通重试只增加 attempt。旧 revision 或旧 attempt 的迟到事件只记录为审计事件，不能覆盖当前节点结果。

## 7. 超时、重试与降级

错误分类包括：

- `VALIDATION_ERROR`
- `MODEL_TIMEOUT`
- `RATE_LIMITED`
- `PROVIDER_UNAVAILABLE`
- `AUTHENTICATION_ERROR`
- `HANDLER_ERROR`
- `OUTPUT_SCHEMA_ERROR`
- `WORKER_LOST`
- `CANCELLED`

Worker 检测单次调用超时并上报错误；Spring Boot 根据冻结策略决定重试。重试采用带随机抖动的指数退避，且只允许白名单错误自动重试。主 Handler 达到最大次数后可以进入 `FALLBACK_READY`，使用冻结的降级 Handler 或模型策略再次执行。关键节点最终失败会使工作流失败或转人工介入；非关键节点仅在配置明确允许时可以跳过。

## 8. Reviewer 定向打回

Reviewer 输出结构化路由：

```json
{
  "decision": "REWORK",
  "routes": [
    {
      "target_node": "backend_engineer",
      "issue_ids": ["R-12"],
      "required_changes": ["补充收藏接口及唯一索引"],
      "invalidate_downstream": true
    }
  ]
}
```

控制面验证目标节点属于 WorkflowSpec 声明的 Rework 白名单，然后：

1. 增加工作流审查轮次。
2. 为目标节点创建新 revision 并置为 `PENDING`。
3. 将依赖其输出的已完成下游节点标记为 `STALE`。
4. 保留不受影响的并行分支及其产物。
5. 重新执行目标节点、受影响下游节点和 Reviewer。

同一运行最多执行两轮 Reviewer 重做。超过上限转为 `MANUAL_INTERVENTION`，避免无限循环。Reviewer 不能打回自身，也不能路由到未声明的节点。

## 9. Checkpoint 与断点恢复

Checkpoint 包括：

- Workflow Checkpoint：快照、运行状态、审查轮次和最后 reconciliation 时间。
- Node Checkpoint：revision、attempt、输入、输出、错误、租约和 Worker 心跳。
- Artifact Checkpoint：产物版本、来源节点运行和输入依赖版本。

持久化发生在运行创建、节点入队、节点开始、节点终止、审批暂停、审批完成、Reviewer 重做和工作流结束时。

恢复任务定期扫描无心跳的 `RUNNING` 节点，将其标记为 `ORPHANED`，再根据错误策略创建新 attempt。对于 `QUEUED` 但缺少有效 Outbox 或 Redis 消息的节点，补偿任务重新写入 Outbox。Spring Boot 或 Redis 重启后，Reconciler 仅依赖 MySQL 状态即可恢复未完成工作流。

## 10. 配置化人工审批

任意节点可以配置：

- `BEFORE_NODE`：执行前暂停。
- `AFTER_NODE`：候选输出生成后暂停。
- `NONE`：无需审批。

允许操作为 `APPROVE`、`REJECT`、`EDIT_AND_APPROVE`、`ROLLBACK_TO_NODE` 和 `CANCEL_WORKFLOW`，具体可用操作由节点配置限制。

审批请求不可变地记录候选产物、操作人、决定、原因和时间。人工编辑不会覆盖 Agent 原产物，而是创建带来源关系的新 Artifact 版本。审批通过后，Reconciler 从原 Workflow Snapshot 和当前 Checkpoint 继续运行。审批接口必须支持幂等键，重复提交不能重复恢复工作流。

## 11. 工作流版本回放

原样回放复用原始 Workflow Snapshot、输入、Prompt 版本、模型策略和知识引用快照，创建新的 Workflow Run，并通过 `replay_of_run_id` 关联原运行。旧运行、节点和 Artifact 不被覆盖。

对比重跑允许选择新 Workflow Version、Prompt 或模型配置，并形成独立运行及 Experiment Comparison。若冻结的旧 Handler 或模型已不可用，原样回放必须返回 `RUNTIME_VERSION_UNAVAILABLE`，不能静默替换依赖。

## 12. 数据模型

新增或扩展：

- `workflow_definition`：工作流逻辑身份、名称和草稿状态。
- `workflow_version`：不可变 Spec JSON、语义版本、内容哈希、发布状态和发布时间。
- `workflow_run`：增加工作流版本、冻结快照、回放来源、审查轮次、心跳和乐观锁字段。
- `workflow_node_run`：节点、revision、attempt、执行状态、输入输出、错误、租约、执行标识和乐观锁。
- `workflow_approval`：审批模式、候选产物、状态、决定、人工修订和操作者。
- `workflow_transition`：追加式状态转换审计记录。
- `workflow_outbox`：待发布命令及其投递状态。
- `processed_workflow_event`：已处理的事件 ID，用于消费幂等。

`workflow_node_run` 对 `(workflow_run_id, node_id, revision, attempt)` 和 `execution_id` 建立唯一约束。现有 `agent_task`、`agent_event`、`workflow_snapshot`、`artifact` 和 `model_invocation` 保留，并增加 `workflow_node_run_id` 关联，保证 V1 至 V4 接口兼容。

## 13. API 与前端能力

新增 API：

```text
POST /api/workflows
POST /api/workflows/{id}/validate
POST /api/workflows/{id}/publish
GET  /api/workflows/{key}/versions
GET  /api/workflows/{key}/versions/{version}
POST /api/workflow-runs
GET  /api/workflow-runs/{runId}
POST /api/workflow-runs/{runId}/cancel
POST /api/workflow-runs/{runId}/resume
POST /api/workflow-runs/{runId}/replay
GET  /api/workflow-runs/{runId}/nodes
GET  /api/workflow-runs/{runId}/events
POST /api/workflow-approvals/{approvalId}/decide
```

前端增加工作流版本查看、DAG 运行状态、节点 attempt/revision 时间线、审批操作、定向重做展示和版本回放入口。SSE 推送排队、开始、心跳、重试、降级、审批、重做、恢复和结束事件。

## 14. 测试与故障验证

单元测试覆盖 DAG 编译、环路检测、节点可运行判断、并行度、条件分支、错误分类、退避计算、降级、失效传播、审批状态机和回放快照。

使用 Testcontainers 启动 MySQL 和 Redis，验证：

- 两个无依赖节点由不同 Worker 并行执行。
- 重复命令和事件不会生成重复有效结果。
- Worker 执行中退出后，Pending 消息可被重新认领。
- Spring Boot 或 Redis 重启后可以从 MySQL Checkpoint 恢复。
- Reviewer 仅重跑目标节点和受影响下游分支。
- 审批暂停期间重启服务，之后仍能继续原运行。
- 原版本回放产生独立运行，并保留完整来源链路。

## 15. 交付阶段

### M1：WorkflowSpec V5 与动态 DAG

完成版本管理、发布校验、DAG 编译、节点运行模型和串行动态调度。验收标准是修改节点顺序或依赖时不修改编排代码。

### M2：Redis Streams 与并行 Worker

完成 Command/Event Streams、Transactional Outbox、Python Worker、Consumer Group 和并行度控制。验收标准是两个无依赖节点可由不同 Worker 并行完成。

### M3：可靠性与恢复

完成超时、指数退避、fallback、心跳、租约、Pending 消息认领、补偿投递和断点恢复。验收标准是 Worker 与服务重启不造成重复有效产物，未完成工作流可继续。

### M4：条件分支与 Reviewer 重做

完成受限条件表达式、Rework Edge、下游失效传播和最大审查轮次。验收标准是后端问题只重跑后端节点及受影响下游节点。

### M5：配置化人工审批

完成前置/后置审批、人工 Artifact 修订、审批恢复、审计和前端审批操作。验收标准是服务重启不丢失审批状态，审批后可继续原运行。

### M6：回放与生产化验证

完成原版本回放、对比重跑、故障集成测试、指标、运行时间线、演示样例和文档。验收标准是历史运行可使用冻结版本创建独立回放，并展示节点级差异。

单人业余开发预计需要 26 至 37 个有效开发日。M1 至 M3 构成第一个生产化演示版本，M4 至 M6 补齐 Agent 特有能力。

## 16. 简历表述

> 设计并实现配置驱动的多 Agent DAG 调度平台，以 Spring Boot 作为控制面、Redis Streams 作为异步消息总线、Python Worker 作为执行面，实现拓扑调度、并行执行、节点级超时与指数退避、模型降级、人工审批、Reviewer 定向重做、Checkpoint 断点恢复及工作流版本回放；通过 Outbox、幂等状态机和 Consumer Group 故障认领保证至少一次投递场景下的执行一致性。
