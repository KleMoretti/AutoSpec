---
plan_id: ai-interview-agent-roadmap
version: 1.0
status: active
created_at: 2026-09-04
updated_at: 2026-09-04
source_conversation: 6a9a95a0-76d8-83ee-974c-23bcbea6ffbc
---

# AI 面试 Agent 项目优化计划

## 1. 项目定位

把现有的 **Java + LLM 面试应用** 从固定流水线升级为可解释、可恢复、可评测的 **AI Interview Agent 工程项目**。

目标形态不是堆叠 Agent 框架，而是：

> 确定性 Workflow + 受约束的 Agent 决策 + Tool Harness + Memory + RAG + Evaluation + Trace

已知基础（后续需结合代码核验）：状态机、Redis Stream 异步任务、结构化输出、重试与缓存。

## 2. 核心设计原则

1. 固定业务步骤交给 Workflow，只有开放式判断和动态路径选择交给 Agent。
2. 模型负责提出决策，确定性运行时负责校验、权限、超时、重试、幂等和终止条件。
3. 原始会话是事实源；摘要、画像和向量索引都必须可重建、可版本化。
4. 所有优化先建立 Baseline，再用离线评测与线上 Trace 证明效果。
5. 保留 Java 后端与 Redis 工程优势，不为了框架完整性重写成纯 Python Demo。
6. Multi-Agent 只按职责与上下文隔离需要拆分，不按“角色数量”堆叠。

## 3. 目标架构

```text
                    AI Interview Agent
                             │
                    Spring Boot API
                             │
                    Router / Planner
                             │
                    Interview Graph
             ┌───────────────┼───────────────┐
             ↓               ↓               ↓
        Interviewer      Evaluator        Reviewer
             └───────────────┼───────────────┘
                             ↓
                        Tool Harness
             ┌───────────────┼───────────────┐
             ↓               ↓               ↓
         Resume RAG      Question RAG     Rubric RAG
                             │
                         Agent State
                    ┌────────┴────────┐
                    ↓                 ↓
              Short Memory       Long Memory
                 Redis         PostgreSQL/Vector DB

-------------------------- Engineering --------------------------
 Redis Stream / Worker · Checkpoint / Retry · Context Compression
 Trace / Evaluation · Token / Cost · Backpressure / Dead Letter
```

核心边界：

- Workflow：简历接收、任务入队、数据持久化、报告生成、失败补偿。
- Agent：问题规划、动态追问、技能切换、是否重规划、是否结束。
- Tool Runtime：所有模型之外的安全与可靠性约束。

## 4. 路线图总览

| ID | 优先级 | 模块 | 状态 | 主要交付物 | 依赖 |
|---|---|---|---|---|---|
| AGT-P0-01 | P0 | Agent 编排 | done | 状态图、条件路由、终止策略、Checkpoint | 无 |
| AGT-P0-02 | P0 | Memory 与 Context | done | 短期记忆、长期画像、上下文预算与摘要版本 | AGT-P0-01 |
| AGT-P0-03 | P0 | Tool Harness | done | 注册、校验、权限、超时、重试、幂等、熔断 | AGT-P0-01 |
| AGT-P0-04 | P0 | Evaluation 与基础 Trace | done | 评测集、指标、Baseline、实验报告、可回放 Trace | AGT-P0-01 |
| AGT-P1-01 | P1 | RAG | done | Hybrid Search、RRF、Rerank、检索评测 | AGT-P0-04 |
| AGT-P1-02 | P1 | 可观测性深化 | done | 节点级耗时/Token/成本/失败归因与看板 | AGT-P0-04 |
| AGT-P1-03 | P1 | 并发与恢复 | done | 背压、限流、Retry/DLQ、断点恢复、容量报告模板 | AGT-P0-03 |
| AGT-P2-01 | P2 | MCP / Skills | planned | 候选工具标准化与按需暴露 | P0、P1 稳定后 |

状态约定：`planned` → `in_progress` → `blocked` / `done`。

P0 实施说明：当前仓库的产品事实仍是 AutoSpec V5，而不是独立的面试产品；P0 已映射到 V5 的冻结 DAG、Reviewer/Evaluator 路由、Artifact/NodeRun 检查点、项目知识上下文和 Redis Worker。面试专用的 RAG 索引与 MCP/Skills 仍按 P1/P2 规划，不通过新增假流程提前引入。

## 5. P0：必须先完成

### AGT-P0-01 Agent 编排

目标：让系统具备可解释的动态面试决策，同时保持关键流程确定性。

实施项：

- 定义统一 `InterviewState`，至少包含：
  - `resume_profile`
  - `target_position`
  - `skill_graph`
  - `interview_plan`
  - `current_stage`
  - `qa_history`
  - `weakness_profile`
  - `tool_results`
  - `score`
  - `next_action`
  - `step_count` / `token_budget` / `deadline`
- 实现 Router、Planner、Interviewer、Evaluator、Reviewer 节点。
- 用条件边覆盖四类核心决策：继续追问、切换技能、动态追加问题、结束面试。
- 把“结束条件”写入运行时：最大步数、问题数、时间、Token 预算、覆盖率。
- 每个节点输入输出使用版本化 Schema；非法输出进入修复或降级路径。
- 接入 Checkpoint，使节点失败后从最近成功步骤恢复。
- 记录每次条件路由的依据，保证可以解释和回放。

完成标准：

- 同一个输入可回放完整节点序列与路由理由。
- 达到任一预算上限时必定结束，不存在无界循环。
- 模型或工具在中间步骤失败时，不需要整场面试重跑。
- 固定流程与 Agent 决策边界可在架构图和代码中一一对应。

### AGT-P0-02 Memory 与 Context

目标：支持单场多轮状态、跨场次能力画像，并控制上下文膨胀与摘要污染。

实施项：

- Short-term Memory：当前岗位、技能、已问问题、最近回答、评分、未完成追问和 Tool Observation。
- Long-term Memory：按用户/岗位/技能记录历史能力证据、置信度、来源场次和更新时间。
- Context Builder 只装配：系统指令、结构化简历、面试计划、最近 3～5 轮、会话摘要、相关长期记忆、当前任务。
- 建立 Token Budget；达到阈值时触发压缩，不依赖固定轮数。
- 原始对话不可被摘要覆盖；摘要带版本、来源消息范围和生成模型版本。
- 重要事实单独结构化保存，并保留证据引用，避免只依赖自然语言摘要。
- 对长期记忆做相关性、时效性、冲突合并与用户隔离测试。

完成标准：

- 长对话中上下文大小受预算约束，关键事实仍可追溯到原始消息。
- 摘要可以删除并从原始会话重建。
- 跨 Session 召回只命中对应用户，且每条画像能展示证据来源。
- 对摘要错误、旧记忆冲突和无关记忆注入均有明确降级策略。

### AGT-P0-03 Tool Harness

目标：把“模型会调用工具”升级为受约束、可恢复的生产级工具运行时。

标准链路：

```text
LLM Tool Call
  → Tool Registry
  → Schema Validation
  → Permission / Whitelist
  → Idempotency Check
  → Timeout / Execute
  → Result Validation
  → Observation / Repair / Fallback
```

实施项：

- 建立 Tool Registry，记录名称、版本、描述、输入输出 Schema、权限、超时和重试策略。
- 在执行前做类型、枚举、必填项和业务约束校验。
- 参数错误时允许一次受控 Self-Repair；仍失败则走降级或人工可读错误。
- 实现超时、指数退避、熔断、限流、幂等键和结果大小限制。
- 区分可重试错误与不可重试错误，防止副作用工具重复执行。
- 设置单轮工具数、整场最大步骤数和允许工具白名单。
- 对 Prompt Injection、越权调用、伪造 Tool Result 建立测试用例。

完成标准：

- 非法参数不会进入真实工具执行。
- 超时、重复投递、半成功和连续失败均有自动化测试。
- 副作用工具在重试场景下不会重复生效。
- 每次调用都能关联 `trace_id`、工具版本、耗时、结果状态和重试次数。

### AGT-P0-04 Evaluation 与基础 Trace

目标：能够回答“优化是否有效、失败发生在哪一层”。

评测数据结构：

```text
Resume
Target Position
Expected Skill Coverage
Expected Question Type
Gold Rubric
Bad Question Cases
Expected Tool
Expected Route / Stop Condition
```

指标分层：

| 层级 | 指标 |
|---|---|
| Agent | Task Success Rate、Plan Completion Rate、Tool Selection Accuracy、Tool Argument Valid Rate、Loop/Replan Rate |
| 面试业务 | Question Relevance、Skill Coverage、Duplicate Question Rate、Difficulty Match、Evaluation Consistency |
| RAG | Recall@K、MRR、nDCG、Rerank Hit Rate |
| 工程 | P50/P95 Latency、Token/Interview、Cost/Interview、Tool Failure Rate、Retry Rate、Recovery Rate |

对照实验：

1. Baseline：Single LLM / 当前实现。
2. Agent + Planner。
3. Agent + Planner + Reviewer。
4. P1 完成后追加 Agent + Planner + RAG + Reviewer。

Trace 最小字段：

- `trace_id`、`session_id`、节点与步骤号。
- `model_version`、`prompt_version`、`tool_version`。
- 输入输出 Schema 版本、Token、耗时、成本。
- 检索文档、工具调用、重试次数、路由理由、失败原因。
- 对敏感简历与回答做脱敏，禁止将原始隐私数据直接写入普通日志。

完成标准：

- 建立版本化离线评测集，并可在一次命令/流水线中重复运行。
- 每次变更输出与 Baseline 的对比，不用“主观感觉更好”作为结论。
- 任一失败 Case 能通过 `trace_id` 定位到模型、检索、工具或编排层。
- 简历中的效果数字只使用可复现的真实实验结果。

## 6. P1：形成工程差异化

### AGT-P1-01 RAG

- 分别建设 Resume、Question、Rubric 三类索引，避免语义和权限混杂。
- 链路：Query Rewrite → BM25 + Embedding → RRF → Top N → Reranker → Top K。
- 记录 Chunk 策略、Embedding/Reranker 版本、过滤条件和命中文档。
- 用 Recall@K、MRR、nDCG 做检索评测；生成质量与检索质量分开诊断。
- 建立空召回、错误召回、过期文档和越权文档的降级策略。

当前 V5 实现：后端在 `knowledge_document` 上增加逻辑语料分类和过期时间，保留项目成员访问边界；检索使用 Query Rewrite、BM25/本地 Embedding、RRF 和确定性 Rerank，并将检索策略、版本、过滤条件和命中数写入节点输入的 `retrieval_trace`。Agent Engine 提供独立的 `/evaluation/retrieval` 评测入口，生成质量不与检索指标混算。

### AGT-P1-02 可观测性深化

- 展示各节点耗时、Token、成本、重试、失败率和路由分布。
- 支持按模型、Prompt、Tool、Retriever 版本切片比较。
- 建立 Trace Replay 和失败聚类，把真实线上失败沉淀为离线 Case。
- 设置隐私脱敏、日志保留期和访问权限。

当前 V5 实现：运行指标接口增加节点级 P50/P95、Token、成本、重试、失败和路由分布，以及 Model/Prompt/Tool/Retriever 版本切片；新增受项目角色保护的脱敏 Trace 接口，返回失败聚类和可导出的离线失败 Case，不返回节点原始输入、输出或错误消息全文。审计数据的保留期由部署级数据库生命周期策略控制，避免自动删除仍用于恢复/回放的事实数据。

### AGT-P1-03 并发与恢复

- 保留 Spring Boot API → Redis Stream → Agent Worker 链路。
- 完善 Consumer Group、Backpressure、Retry Queue、Dead Letter Queue。
- 增加 per-user 并发限制、全局 LLM Semaphore 和公平调度。
- 支持 Checkpoint/Resume 与重复消息幂等消费。
- 对积压、模型限流、工具超时、Worker 崩溃、Redis 短暂不可用进行故障演练。
- 输出容量边界、P95 延迟、恢复率与资源成本报告。

当前 V5 实现：启动入口增加按发起用户的运行中限额；Worker 支持批量并发、FIFO 公平 Semaphore、进程级 LLM 并发上限和用户级执行隔离。Redis Consumer Group、背压、重试、死信、XAUTOCLAIM、执行台账幂等、fencing token、心跳和断点恢复沿用现有正式链路；容量和故障演练结果不虚构，统一记录在 `docs/p1-capacity-and-recovery-report.md`。

## 7. P2：MCP / Skills

仅在 P0、P1 稳定后引入：

- 优先标准化跨应用、可复用、权限边界清晰的工具。
- 按当前任务动态暴露少量工具，避免工具过多导致 Prompt 膨胀和误选。
- 对 MCP 与普通 Function Calling 使用同一 Tool Harness、Trace 和权限模型。
- 不为了展示名词而迁移已有稳定内部工具。

## 8. 推荐实施顺序

```text
M0  代码与现状审计；冻结 Baseline 和关键 ADR
 ↓
M1  InterviewState + Agent Graph + 条件路由 + 终止策略
 ↓
M2  Memory/Context 与 Tool Harness（可并行开发，统一接入 Graph）
 ↓
M3  Evaluation + 基础 Trace；完成第一轮可复现实验
 ↓
M4  Hybrid RAG + 可观测性深化
 ↓
M5  并发、恢复与故障演练
 ↓
M6  按真实需要选择 MCP / Skills
```

## 9. 关键数据对象

### InterviewState

承载单场面试的可恢复状态。写入需要版本号与乐观锁，Checkpoint 只保存可序列化数据。

### MemoryRecord

建议字段：`user_id`、`skill`、`assessment`、`confidence`、`evidence_ref`、`source_session_id`、`created_at`、`expires_at`、`version`。

### AgentTrace

建议字段：`trace_id`、`span_id`、`parent_span_id`、`node`、`step`、`input_ref`、`output_ref`、`route_reason`、`latency_ms`、`token_usage`、`cost`、`status`、`error_type`、各组件版本。

### EvalCase / EvalRun

Case 保存输入、期望行为、Rubric 与标签；Run 保存代码/模型/Prompt/数据集版本和所有指标，保证结果可复现。

## 10. 风险与反模式

- 把固定流水线全部改成自主 Agent，导致稳定性和成本恶化。
- 先拆很多 Agent，再寻找职责边界。
- 用摘要替代原始对话，造成不可恢复的信息污染。
- 只记录最终回答，不记录节点、路由、工具和检索上下文。
- 只做向量 TopK，却无法说明 Chunk、召回、Rerank 和失败定位。
- 用未经评测的主观数字写简历。
- 为了展示 MCP 重写稳定工具，增加不必要的故障面。

## 11. 项目完成定义

项目达到“Agent 岗主项目”标准时，应同时满足：

- 有真实动态决策，而不是仅把固定 Pipeline 改名为 Agent。
- 决策受到 Schema、权限、预算、终止和失败恢复约束。
- 多轮与跨场次 Memory 可追溯、可隔离、可重建。
- 有版本化评测集、Baseline、对照实验和真实指标。
- 有节点级 Trace，可定位和回放失败。
- 在并发、积压、超时、重复投递和 Worker 崩溃下具备明确行为。
- 架构取舍能回答“为什么用 Agent、为什么拆这些节点、为什么不全用 Agent”。

## 12. 下一执行项

`AGT-P2-01 MCP / Skills`：仅在 P1 的检索、观测和恢复指标经过真实环境验证后，选择有明确复用收益的跨应用工具做标准化。
