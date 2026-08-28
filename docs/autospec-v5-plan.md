# AutoSpec 当前产品与代码收口计划

> 当前版本：`autospec-v5:v5`
>
> 状态：已实现，旧固定流水线正在从主干代码中清除
>
> 架构设计：`docs/superpowers/specs/2026-07-11-autospec-v5-dynamic-workflow-design.md`

## 产品目标

用户输入一句软件需求后，系统通过可观察、可恢复的多 Agent 工作流生成并管理以下结构化产物：

1. Product Manager：PRD、用户故事与验收标准。
2. Architect：架构设计与跨模块约束。
3. Backend Engineer：数据库与 API 设计。
4. Frontend Engineer：路由、页面、组件与 API 绑定骨架。
5. Reviewer：确定性规则检查加模型语义审查，并给出定向返工路由。
6. Evaluator：需求到数据/API/UI 的追踪矩阵、评分与最终交付门禁。

系统还必须提供项目权限、Artifact 历史与审批、工作流版本、人工审批、定向返工、取消、恢复、回放、死信治理、模型路由与预算、项目级 RAG、诊断、审计、PDF/Markdown/ZIP 导出和代码骨架验证。

## 唯一运行链路

```text
React 产品工作台
  -> Spring Boot 控制面 + MySQL
       -> 冻结 autospec-v5 工作流快照
       -> DAG 调度 + 事务 Outbox
       -> Redis Streams 命令
  -> Python Worker 池
       -> 5 个角色 Handler + Evaluator
       -> Redis Streams 事件
  -> Spring Boot 幂等消费、Artifact 投影、返工/审批/恢复
```

正式生成从 `POST /api/workflow-runs` 开始。Agent Engine 的 FastAPI 进程只提供健康检查、评测用例目录和实验对比；节点执行由 Redis Worker 承担，不再提供同步 `/generate*` 固定流水线。

## 代码保留边界

保留：

- `agent-engine/agents` 中五个角色及全部当前 Schema、Prompt、规则 Reviewer、Evaluator 和模型网关。
- `agent-engine/runtime` 中节点注册、执行台账、上下文策略、Redis Worker、指标和追踪。
- `backend/workflow` 中动态 DAG、Outbox、事件消费、审批、返工、恢复、回放和死信。
- Artifact 版本、追踪图、RAG、模型调用治理、诊断、审计、交付门禁与三种导出。
- 前端 Intake -> Generate -> Review & fix -> Deliver 完整旅程。
- Prometheus、Grafana、Tempo、性能基线与故障演练。

删除：

- 固定 V1–V4 Agent 工作流及其 `/generate*`、继续生成、旧进度和任务重试入口。
- 旧同步 Agent HTTP Client、`agent_task`/`agent_event`/`external_call_log` 运行服务及项目级旧 Workflow Snapshot 服务。
- 前端旧版本 API 文件、SSE 时间线、固定流程图和无引用编辑组件。
- 只为旧 payload 存在的字符串 Schema 兼容、伪 Handler 版本和兜底追踪 ID。
- V2–V4 计划、样例和过期的实现路线文档。

已执行过的 Flyway 文件是数据库升级历史，必须保持不可变；当前 Java/Python/TypeScript 代码不再依赖其中的旧表。若未来要物理删除旧表，应新增经过备份与数据保留评审的迁移，不能改写历史文件。

## 当前外部契约

- 项目、Artifact、Review Issue、知识来源、模型调用和诊断 API。
- 工作流草稿、校验、发布、启动、节点、指标、取消、审批、回放和死信 API。
- 交付就绪检查、代码骨架、Markdown、PDF 和 ZIP 导出 API。
- 当前 OpenAPI：`backend/src/main/resources/contracts/autospec.openapi.yaml`。
- 当前 WorkflowSpec：`agent-engine/contracts/autospec-v5.workflow.json`。

## 验收标准

- 前端只暴露一条 V5 生成路径，且完整展示六个节点、审批、运行指标、问题处理和交付结果。
- 工作流顺序完全来自冻结的 WorkflowSpec；无依赖节点可并行执行。
- Redis 至少一次投递不会产生重复有效终态或重复 Artifact。
- Worker、Redis 或控制面中断后能从 MySQL 检查点恢复。
- Reviewer 的规则检查与语义检查都保留，返工只重跑目标及受影响下游。
- Evaluator 的阻断结论同时约束项目完成状态和导出/代码生成。
- 当前 OpenAPI 不包含已删除入口或旧 DTO。
- 仓库运行代码中不存在旧编排服务、旧 Agent HTTP Client 或旧前端 API 引用。

## 测试控制规则

- 日常修改只运行直接相关的单元测试或单模块测试，不在每次编辑后机械执行全量回归。
- 跨模块 API/Schema 变更、依赖或数据库变更、里程碑收尾和发布前才执行完整三端验证。
- 默认覆盖一个核心成功流程和确有风险的失败流程；不穷举低价值输入排列、框架行为、getter/setter 或难以发生的组合边界。
- 权限、幂等、数据损坏、消息重复、预算、交付门禁和外部契约属于高风险边界，保留针对性测试。

## 发布验证

```powershell
cd backend
mvn test

cd ../agent-engine
python -m pytest -q

cd ../frontend
npm test
npm run build

cd ..
python scripts/verify_workflow_contract.py
docker compose config --quiet
docker compose --profile monitoring config --quiet
```
