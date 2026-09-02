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

## Harness 优化计划

> 状态：H0 已于 2026-08-30 完成，H1 已于 2026-09-02 完成；H2 待实施。以下工作只增强 `autospec-v5:v5` 的运行 Harness，不改变六节点产品能力，不新增同步 `/generate*` 流水线。正式节点仍由 Redis Worker 执行，MySQL 仍是事实源，Redis 缓存和执行台账不得成为业务事实源。

### H0：先修复正确性与隔离边界

#### H-01 返工指令工作记忆（已完成）

当前 Reviewer 的返工路由只选择目标节点，新 revision 仍复制旧输入，目标 Agent 没有收到 `issue_ids` 和 `required_changes`。应由控制面生成服务端可信的 `rework_directive` 并写入目标节点输入，至少包含：

- `review_round`、Reviewer node run ID 和 Reviewer Artifact/version。
- 稳定 `issue_ids`、目标节点对应的 `required_changes` 和证据路径。
- 上一版目标 Artifact ID/version/content hash。
- 必须保留的决策、允许修改的范围和已失效下游节点。

验收标准：

- 每个返工 revision 都能追溯到触发它的 Reviewer 输出和问题集合。
- 目标 Agent 的实际模型输入包含完整 `rework_directive`，Context Manifest 记录其是否被保留；返工指令不得被普通上下文裁剪丢弃。
- 相同旧输入加不同返工指令必须得到不同的规范化输入 hash；重复事件仍只产生一个有效 revision。
- 增加“Reviewer 定向返工 -> 目标节点消费修改要求 -> 下游重新生成 -> 再审查”的最小回归测试。

#### H-02 项目级 RAG 隔离（已完成）

所有正式检索必须同时绑定 `project_id` 和调用者身份。默认策略只能检索当前项目中调用者有权访问的知识，禁止把调用者其他项目的内容注入当前项目 Artifact。跨项目知识复用若未来开放，必须使用显式策略、独立权限和来源项目审计，不能复用默认路径。

验收标准：

- 工作流启动、返工和回放均冻结项目级 retrieval policy 与项目 ID。
- 非当前项目 chunk 的召回率为零；增加“同一用户拥有多个项目”和“项目含多个成员”的泄漏回归测试。
- 检索来源记录项目、Artifact ID/version、chunk ID、索引/检索策略版本和内容 hash，但对无权用户不暴露其他项目元数据。

#### H-03 知识索引生命周期（已完成）

Artifact 审批是知识索引的唯一业务触发语义，所有审批入口必须通过事务 Outbox 发布统一的 `ARTIFACT_APPROVED` 事件，由索引消费者幂等处理。不得只在某一个 Controller/Service 路径直接调用索引。

索引生命周期至少区分 `INDEXING`、`ACTIVE`、`SUPERSEDED` 和 `FAILED`：

- 新批准版本成功激活后，旧版本转为 `SUPERSEDED`。
- 默认检索只使用 `ACTIVE` 的最新批准版本；历史检索必须显式开启。
- 索引键包含 Artifact 内容 hash、chunker version 和 embedding model/version。
- embedding 损坏或版本过期时进入可恢复重建任务，不在每次查询中长期重复临时计算。
- 索引失败不得回滚 Artifact 审批事实，但必须进入可诊断、可重试状态。

验收标准：普通审批、工作流 `APPROVE`、`EDIT_AND_APPROVE`、恢复和重复事件均有针对性测试；同一 Artifact lineage 默认只能召回最新激活版本。

H0 实施记录：

- 控制面按 Reviewer route 生成不可裁剪的可信 `rework_directive`，冻结问题、修改范围、保留策略、失效下游及 Reviewer/目标 Artifact 引用；Architect、Backend、Frontend 的实际模型输入已接通。
- 正式检索入口只保留 `project_id + actor_user_id` 双重约束，启动冻结当前项目策略，返工沿用冻结输入，回放再次清除跨项目和旧式无项目来源。
- 所有 Artifact 审批入口统一写入 `ARTIFACT_APPROVED` 事务 Outbox；索引状态使用 `INDEXING/ACTIVE/SUPERSEDED/FAILED`，只检索最新 `ACTIVE` 版本，并由可配置恢复扫描重新排队损坏或过期索引。
- 已新增 V83 索引生命周期迁移与 V84 WorkflowSpec 契约迁移；后端 189 项、Agent 75 项、前端 24 项测试、前端构建、契约同步及 Compose 配置验证通过。

### H1：冻结记忆、上下文、预算与调用契约

#### H-04 结构化 Context/Memory Policy（已完成）

不建设无边界增长的自由文本“长期记忆”。记忆分为三层，并复用现有事实表：

1. 运行工作记忆：冻结需求、执行策略、上游 Artifact 引用和返工指令。
2. 决策/问题记忆：批准的 ADR、人工编辑、未解决 Review Issue 和前一 revision 摘要。
3. 项目知识记忆：仅由最新批准 Artifact 构成的项目级 RAG 索引。

每个节点在 WorkflowSpec 中声明并冻结 `context_policy`，至少包含 token budget、字段优先级、必保留字段、压缩策略、RAG 配额和最大单来源占比；该策略必须进入 executable contract hash。上下文编译应满足：

- 使用与目标模型匹配的 tokenizer 或保守 Token 估算，不再统一按字符数除以四。
- 按 Schema/语义字段裁剪，保留 `REQ-*`、`STORY-*`、`AC-*`、API/表/UI ID 及其引用闭包。
- 原始需求、执行策略和 `rework_directive` 为不可丢弃区；RAG 与长描述使用独立配额。
- 压缩后重新执行 Pydantic/JSON Schema 与跨 Artifact 引用校验，失败时在模型调用前终止。
- Context Manifest 记录策略版本、输入/输出 Token、各来源配额、裁剪路径、来源 hash 和压缩原因。

#### H-05 模型预算预占与硬输出上限（已完成）

WorkflowSpec 的 `model_policy` 增加模型上下文窗口、`max_output_tokens` 和必要的 Provider 能力约束。每次模型或工具调用执行“预占 -> 调用 -> 实际结算 -> 释放余额”：

- 调用前按规范化输入 Token、最大输出 Token、预计成本和调用次数原子预占。
- Provider 请求必须设置硬输出上限，并共享节点 deadline。
- primary/fallback、修复重试和工具调用都计入同一工作流预算。
- Provider 缓存 Token 可配置独立单价，成本台账区分 cached/uncached input。
- 预占失败时不得发起外部调用；结算失败进入可恢复状态，不能重复计费。

验收标准：并行节点、fallback、超时和重复事件下均不能突破已冻结的最大调用次数或最大可请求 Token；预算拒绝发生在外部调用之前。

#### H-06 逐次模型与工具调用台账（已完成）

节点终态事件继续提供汇总指标，但每次实际调用必须有独立记录，不能把 primary 失败和 fallback 成功折叠为一条 `provider=multiple` 记录。单次调用台账至少包含：

- call ID、parent node/execution ID、调用序号和 attempt。
- Provider/model、route reason、prompt/schema/contract version。
- 规范化参数 hash、脱敏结果 hash、状态、错误码、耗时和 deadline。
- input/output/cache Token、成本、预占与结算差额。
- 工具名称/version、权限策略、幂等键和引用来源；敏感参数只保存脱敏摘要。

Evaluator、诊断页和审计查询应能从节点下钻到每次调用，同时保持事件重复消费幂等。

H1 实施记录：

- WorkflowSpec 已升级为协议 v2，六节点分别冻结 `context_policy` 与 `model_policy` 并纳入 executable contract hash；Worker 使用保守多语言 Token 估算、结构化语义裁剪、独立 RAG/长文本配额和完整 Context Manifest，模型调用前重新校验 Pydantic 契约及跨 Artifact ID 引用。
- 控制面在发布 Redis 命令前，按节点最大输入、最大输出、调用次数和冻结单价原子预占 MySQL 工作流预算；Worker 对每个物理调用执行硬输出上限、共享 deadline、能力检查和调用次数门禁，primary/fallback 共用同一额度，并在成功、取消和超时路径精确结算或释放。预留节点拒绝协议降级终态，避免绕过 v2 结算。
- 节点终态事件新增逐次 `call_records`，模型与未来受控工具调用统一记录 call ID/序号、Provider/route、Prompt/Schema/contract hash、参数/结果 hash、错误、deadline、Token、缓存 Token、成本、预留与差额；Evaluator 输入和模型调用诊断 API 支持按 node run 下钻。当前 V5 节点未授权物理工具调用，工具 allowlist、调用上限和副作用策略仍由 H-08 冻结后启用。
- 已新增 V85 预算与调用台账迁移、V86 协议 v2 WorkflowSpec 契约迁移，并同步 Java/Python/TypeScript、OpenAPI、Compose 与环境示例；后端 194 项、Agent 82 项、前端 24 项测试、前端生产构建、OpenAPI 解析、契约同步及基础/监控 Compose 配置验证通过。

### H2：增强节点级 RAG、工具调用和缓存

#### H-07 节点级内置 RAG

工作流启动时冻结初始检索快照，但后续节点允许在冻结 policy 下执行节点级只读检索：

- Product Manager 使用原始需求和业务术语查询。
- Architect 使用需求、PRD、约束和决策问题查询。
- Backend/Frontend 按 requirement ID、领域实体、API 和 UI 契约查询。
- 返工节点按 Reviewer issue、证据路径和 required changes 查询。

检索实现逐步替换当前全量 JVM 扫描：先批量读取消除 N+1，再引入可替换的生产语义 embedding/vector index。fixture 继续使用确定性 hashing embedding。JSON Artifact 应按结构路径和语义单元分块，检索结果增加同一 Artifact 上限、去重和 MMR/diversity，避免 top-k 被相邻重叠 chunk 占满。

任何 Artifact 一旦携带 citation，Reviewer/Evaluator 都必须校验其 citation ID、Artifact/version、chunk hash 和 excerpt；不能依赖 requirement 是否包含 `history`、`rag` 等关键词，也不能只验证 PRD。

质量门禁至少维护 Recall@5、nDCG@5、引用精确率、旧版本误召回率、无依据引用率和检索延迟的离线基线。指标目标应在真实评测集建立基线后冻结，不能用主观文案评分代替。

#### H-08 受控 Tool Runtime

在不改变六节点 DAG 的前提下，为 Worker 节点增加受控工具注册表。首批只开放有明确 Schema 的只读或确定性工具：

- `knowledge.search`
- `artifact.get`
- `contract.lookup`
- `trace.query`
- `bundle.verify`

WorkflowSpec 的 `tool_policy` 必须冻结 allowlist、工具版本、最大调用数、单次/总超时、重试、结果大小、幂等和副作用等级，并进入 contract hash。工具输入输出必须经过 Schema 校验，返回内容按不可信数据处理。默认禁止任意 shell、任意 HTTP、数据库直连和未声明写操作；未来有副作用的工具需要单独权限、审计和人工审批策略。

模型工具循环必须有硬上限，共享节点 deadline/预算，并把每次 tool request/result 写入调用台账。工具失败应返回结构化错误，由冻结策略决定重试、fallback 或节点失败，不能无限自修复。

#### H-09 分层缓存

缓存只能建立在 H-01 至 H-06 的正确性和版本契约完成之后：

1. 保留现有按 `execution_id` 的 Redis 幂等终态缓存，用于至少一次投递去重。
2. 增加 RAG 查询缓存，key 至少包含 `project_id + corpus_epoch + query_hash + retrieval_strategy_version + k + access_policy_version`。
3. 增加可选的节点结果缓存，key 至少包含 `contract_hash + canonical_input_hash + provider/model + retrieval_snapshot_hash + tool_policy_hash`。
4. Prompt 内容和 tokenizer 可做进程内不可变缓存，以 checksum/version 失效。

只缓存通过输出 Schema、引用校验和确定性规则检查的成功结果。缓存命中必须记录 `cache_source_execution_id`、命中层级、节省 Token/成本和来源版本；审批、权限、语料 epoch、Prompt、Schema、模型或工具策略变化必须使相关缓存失效。禁止缓存人工审批决定、失败终态、包含未脱敏敏感数据的结果或有副作用工具的响应。

#### H-10 Prompt 与模型配置单一事实源

数据库中的 Prompt/Model Registry 与 Worker 文件/env 配置不能长期并存为两套看似有效的来源。发布 WorkflowSpec 时由控制面解析已批准的 Prompt 和模型配置，生成不可变执行 bundle，冻结内容、checksum、route 和 Provider 能力；Worker 只执行该 bundle 并校验 checksum，不在运行中追随“当前 active”配置。

模型密钥仍只通过安全运行环境提供，不写入 WorkflowSpec、数据库普通字段、事件或日志。`model_invocation.prompt_version_id` 等引用应与实际执行 bundle 对齐，避免管理页面展示未被 Worker 使用的配置。

### Harness 实施顺序与完成定义

1. **正确性里程碑**：完成 H-01、H-02、H-03；返工反馈可见、跨项目召回为零、审批索引和版本失效完整。
2. **可回放契约里程碑**：完成 H-04、H-05、H-06；Context、预算和每次调用都进入冻结契约与持久化台账。
3. **能力增强里程碑**：完成 H-07、H-08；节点级 RAG 和受控工具调用通过离线评测、权限与幂等测试。
4. **性能里程碑**：完成 H-09、H-10；缓存命中可解释、可失效，Prompt/Model 配置只有一个发布来源。

涉及 WorkflowSpec、事件协议、OpenAPI、数据库或跨语言 Schema 的里程碑，必须同步 Java/Python/TypeScript 消费者、不可变种子、迁移、测试和文档，并执行完整发布验证。每个阶段都必须保留 fixture 模式的确定性测试，并至少增加一个真实风险对应的失败用例。

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
