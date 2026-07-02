# AutoSpec V3 Plan

> Canonical superpowers plan: `docs/superpowers/plans/2026-06-28-autospec-v3-implementation-plan.md`
>
> The detailed V3 implementation plan has been created with `superpowers:writing-plans`.
> Use the canonical plan above for execution; this roadmap remains the summary and database index.

## Database Record

- Project: `AutoSpec V3 Plan`
- Project ID: `3`
- Artifact type: `ROADMAP_PLAN`
- Artifact ID: `5`
- Artifact version: `3`
- Source: `docs/superpowers/plans/2026-06-28-autospec-v3-implementation-plan.md`
- Created at: `2026-06-28`

## Objective

V3 在 V2 的多 Agent 产物、人工确认、事件观测、重试和导出能力之上，补齐可进入多人协作和工程交付演示的关键能力：登录与权限、历史项目知识复用、多模型路由与评分、代码骨架 ZIP 生成，以及 LangGraph 工作流可视化。

## Implementation Status

Implemented in the workspace on `2026-06-29`.

| ID | Status | Implemented Evidence |
| --- | --- | --- |
| V3-01 | DONE | `user_account` / `project_member` schema, demo login API, project membership guard, frontend login/session flow, permission tests |
| V3-02 | DONE | approved artifact knowledge indexing, source retrieval endpoint, retrieved source injection into Agent task inputs |
| V3-03 | DONE | model provider/config/invocation schema, invocation logging, governance endpoint, reviewer score linkage |
| V3-04 | DONE | Spring Boot + React skeleton ZIP generation endpoint, manifest persistence, frontend export panel |
| V3-05 | DONE | persisted `autospec-v3` workflow snapshot, workflow graph endpoint, frontend DAG visualization |

Sample V3 artifacts: `docs/examples/v3-sample-artifacts.md`

## Plan Items

The table below preserves the original planning snapshot; current completion status is recorded in `Implementation Status`.

| ID | Title | Priority | Status | Description | Deliverables |
| --- | --- | --- | --- | --- | --- |
| V3-01 | Permission Model and User Login | P0 | TODO | 建立用户登录、项目归属、成员角色和接口访问控制，避免所有项目默认全局可见。 | `user_account` / `project_member` schema; auth API; backend guard; frontend login/session UI; permission regression tests |
| V3-02 | Historical Project RAG | P0 | TODO | 将已确认 Artifact 和审查结论沉淀为可检索知识，在新项目生成 PRD、架构和 API 时引用相似历史项目。 | knowledge document/chunk model; embedding job; retrieval API; Agent context injection; source citation in task payload |
| V3-03 | Multi-model Routing and Result Scoring | P1 | TODO | 为不同 Agent 节点配置候选模型，记录调用成本、耗时、结构化校验结果和 Reviewer 评分，并支持失败降级。 | model provider/config tables; model invocation log; routing policy; scoring report; fallback tests |
| V3-04 | Spring Boot / React Code Skeleton ZIP Generation | P1 | TODO | 基于已批准 PRD、架构、数据库设计和 API 文档生成可下载的后端与前端代码骨架。 | code generation job; template set; ZIP export endpoint; manifest; sanitized sample ZIP |
| V3-05 | LangGraph Workflow Visualization | P2 | TODO | 展示 Agent 工作流拓扑、节点输入输出摘要、执行状态和事件回放，便于解释系统如何协作。 | workflow graph API; node event projection; frontend DAG view; execution replay panel |

## Milestones

| ID | Name | Items | Exit Criteria |
| --- | --- | --- | --- |
| M1 | Trust boundary | V3-01 | 用户可以登录；项目只能被 owner 或成员访问；核心 API 覆盖权限测试。 |
| M2 | Knowledge and model governance | V3-02, V3-03 | Agent 可引用历史项目片段；每次模型调用可追踪模型、成本、耗时、评分和失败降级路径。 |
| M3 | Engineering deliverable export | V3-04 | 完成项目可导出包含 Spring Boot 与 React 骨架的 ZIP，并附生成清单。 |
| M4 | Workflow explainability | V3-05 | 前端可查看工作流 DAG、节点状态、历史事件和关键输入输出摘要。 |

## Data Model Direction

- `user_account`：保存登录主体、昵称、密码摘要或外部身份标识、启用状态和创建时间。
- `project_member`：保存项目成员、角色、加入时间，用于项目级访问控制。
- `knowledge_document` / `knowledge_chunk`：保存可检索 Artifact、分块内容、来源 Artifact、版本和向量引用。
- `model_provider` / `model_config` / `model_invocation`：保存模型配置、节点路由策略、调用耗时、成本、状态和错误。
- `code_generation_job` / `export_file`：保存代码骨架生成任务、输出文件名、清单、状态和失败原因。
- `workflow_snapshot`：保存工作流节点、边、版本和展示用元数据，执行事件继续使用 V2 的 `agent_event`。

## Implementation Order

1. 先实现 V3-01，确保后续历史知识、模型配置和导出文件都有明确的项目与用户边界。
2. 再实现 V3-02，把已确认 Artifact 作为知识来源，并要求 Agent 输出引用来源，避免不可追溯的自由文本拼接。
3. 接着实现 V3-03，在已有 `prompt_version`、`agent_task` 和 `agent_event` 基础上增加模型调用治理。
4. 然后实现 V3-04，把 V2/V3 结构化 Artifact 转为可下载代码骨架。
5. 最后实现 V3-05，用已持久化的任务与事件数据做解释性可视化，不阻塞核心生成链路。

## Acceptance Criteria

- 登录、项目归属和成员角色会在后端 API 层强制校验，前端隐藏不可用操作但不依赖前端做安全边界。
- 历史项目 RAG 只能使用已确认或已完成的 Artifact，Agent 任务输入中必须记录检索到的来源。
- 每次模型调用都记录 provider、model、Agent 节点、prompt version、耗时、状态、错误和可选成本。
- 代码 ZIP 生成必须基于结构化 Artifact 和模板，不直接保存或输出 API Key、数据库密码、本机绝对路径。
- 工作流可视化必须来自后端持久化的 workflow snapshot、agent task 和 agent event，而不是前端硬编码流程。
- Reviewer Agent 继续执行“规则检查 + LLM 语义审查”两层校验，并新增权限、RAG 来源和代码导出一致性检查。
- 新增功能必须包含最小回归测试或可复现样例。
