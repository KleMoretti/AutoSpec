---
plan_id: autospec-v5-next-optimization
version: 1.0
status: in_progress
created_at: 2026-09-04
updated_at: 2026-09-06
product_baseline: autospec-v5:v5
---

# AutoSpec V5 新一轮优化计划

## 1. 计划目标

本计划面向当前已经落地的 AutoSpec V5，目标是把现有“可运行、可恢复、可观测”的六节点工作流继续提升为：

> 契约真正不可变、配置只有一个发布来源、检索与工具可在生产链路受控执行、缓存可证明安全、质量和容量有真实证据的交付平台。

本轮不增加新的 Agent 角色，不重写 Java 控制面，不恢复同步 `/generate*` 接口，也不引入无边界自主循环。唯一产品工作流仍为 `autospec-v5`，完整保留以下六个节点：

1. Product Manager
2. Architect
3. Backend Engineer
4. Frontend Engineer
5. Reviewer
6. Evaluator

正式入口继续是 `POST /api/workflow-runs`；MySQL 继续作为事实源，Redis Streams Worker 继续作为正式节点执行链路。

## 2. 当前基线与真实缺口

本计划基于 2026-09-04 的代码审计，而不是把旧路线图重新命名。

| 领域 | 已有能力 | 当前缺口 | 代码证据 |
|---|---|---|---|
| Workflow 契约 | V5 DAG、协议 v2、Context/Model/Tool Policy、执行哈希 | V82/V84/V86 仍原地更新已发布 `v5`；校验脚本固定读取 V86，发布历史没有做到真正不可变 | `backend/.../V86__publish_autospec_v5_harness_h1_contract.sql`、`scripts/verify_workflow_contract.py` |
| Prompt/模型治理 | MySQL Prompt/Model Registry、Worker Prompt 文件、模型路由与调用台账 | 数据库、文件和 env 是多套可生效来源；发布 WorkflowSpec 时没有冻结实际 Prompt 内容与模型执行 bundle | `PromptRegistryService`、`DefaultPromptSeeder`、`runtime/production_handlers.py`、`model_gateway.py` |
| RAG | 项目级权限隔离、审批索引、Hybrid Search、启动时检索快照、基础评测接口 | 只按原始 requirement 做一次检索；后续节点没有按上游 Artifact/Review Issue 动态检索；Java 与 Python 检索实现和评测数据相互独立 | `WorkflowRuntimeController.trustedInput`、`KnowledgeIndexService`、`runtime/hybrid_rag.py` |
| Tool Runtime | Python 已有 ToolRegistry/ToolHarness、Schema、allowlist、超时、重试、幂等、熔断和台账接点 | 生产 Worker 使用空 ToolRegistry，当前 WorkflowSpec 未启用任何工具，也没有连接 MySQL 事实服务的受控工具通道 | `runtime/tool_harness.py`、`runtime/node_executor.py`、`autospec-v5.workflow.json` |
| 缓存 | `execution_id` 终态幂等缓存、Provider cache token 计量 | 没有租户安全的 RAG 查询缓存、验证后的节点结果缓存、语料 epoch 和完整失效协议 | `runtime/execution_ledger.py`、`runtime/worker.py` |
| 质量证据 | Fixture 测试、检索指标实现、性能脚本和容量报告模板 | 检索评测仍是小型内置 fixture；容量、P95、恢复率和真实成本报告尚未填写 | `evaluation/retrieval.py`、`docs/p1-capacity-and-recovery-report.md` |
| 治理权限 | 项目 OWNER/EDITOR/VIEWER 权限 | 任意已登录用户都可创建和发布全局 Workflow；Prompt active 列表没有显式认证，缺少平台级治理角色 | `WorkflowVersionController`、`PromptVersionController` |

## 3. 设计原则

1. 先修契约与权限，再开放检索、工具和缓存能力。
2. 已发布 WorkflowSpec、Prompt bundle、模型路由和 Schema 只能新增版本，不得原地改写。
3. Worker 不直连 MySQL，不获得任意 shell、任意 HTTP 或数据库查询能力。
4. 模型可以提出工具请求，但控制面负责权限、Schema、预算、超时、幂等、审计和终止。
5. 检索结果、工具结果和缓存命中都属于不可信输入，必须保留来源并经过结构化校验。
6. 优化结论必须来自可复现评测、Trace、容量或成本数据，不用主观文案替代。
7. 所有跨语言协议变更同步 Java、Python、TypeScript、OpenAPI、WorkflowSpec、迁移、测试和文档。

## 4. 路线图总览

状态流转统一使用：`planned` → `in_progress` → `blocked` / `done`。

| ID | 优先级 | 工作包 | 状态 | 核心交付物 | 依赖 |
|---|---|---|---|---|---|
| OPT-P0-01 | P0 | 不可变发布与治理权限 | done | 不可变 Workflow 版本、真实 SHA-256、平台级发布权限、契约校验器 | 无 |
| OPT-P0-02 | P0 | Prompt/Model 执行 bundle | done | 发布时冻结的执行 bundle、唯一配置来源、Worker checksum 校验 | OPT-P0-01 |
| OPT-P1-01 | P1 | 节点级 RAG 与统一评测 | done | 节点检索策略、统一检索契约、真实评测集、检索 Trace | OPT-P0-02 |
| OPT-P1-02 | P1 | 全链路引用完整性门禁 | done | 全 Artifact citation Schema、Reviewer/Evaluator 校验、交付阻断 | OPT-P1-01 |
| OPT-P1-03 | P1 | 生产级受控工具通道 | done | 控制面 Tool Gateway、首批只读工具、工具调用台账和失败语义 | OPT-P0-02、OPT-P1-01 |
| OPT-P2-01 | P2 | 分层缓存与失效协议 | done | corpus epoch、RAG 查询缓存、可选节点结果缓存、命中来源 | OPT-P1-02、OPT-P1-03 |
| OPT-P2-02 | P2 | 操作台与诊断闭环 | done | Bundle/RAG/Tool/Cache 可视化、治理入口、失败处理指引 | OPT-P1-03、OPT-P2-01 |
| OPT-P2-03 | P2 | 容量、安全与发布证据 | in_progress | 本地发布门禁与边界报告已完成；live/容量/故障演练需隔离环境 | 全部工作包 |

## 5. P0：先保证发布事实可信

### OPT-P0-01 不可变发布与治理权限

目标：确保任何一次历史运行都能定位并重放当时真正执行的不可变契约，且只有被授权的治理用户能够发布全局配置。

实施项：

- 明确 WorkflowVersion 状态机：`DRAFT` 可编辑，`PUBLISHED` 后数据库和服务层都禁止修改 `spec_json`、version、hash 和发布时间。
- 后续契约升级必须插入新版本；不得再用新迁移 `update` 已发布的 `autospec-v5:v5`。
- 对规范化 JSON 计算真实 SHA-256；拒绝 `builtin-*` 等说明性字符串冒充内容哈希。
- 为 `(definition_id, version)` 和已发布 hash 建立可验证的不变量；重复发布只返回同一结果，不产生不同内容。
- 重构 `scripts/verify_workflow_contract.py`：不再固定读取 V86，而是验证 canonical contract、最新不可变种子、Java 解析结果和 Python Handler 能力四者一致。
- 引入最小平台治理权限，例如 `PLATFORM_ADMIN` 或等价 capability；Workflow 创建/发布、Prompt 激活、模型配置变更只允许治理角色执行。
- `GET /api/prompts/active` 至少要求登录；若未来返回 Prompt 正文，必须另设更高权限并记录审计。
- 审计事件记录 actor、旧/新版本、内容 hash、发布时间和拒绝原因。

验收标准：

- 尝试修改已发布版本时返回稳定冲突错误，数据库层也不能静默覆盖。
- 同一个版本号不能对应两个 canonical hash。
- 普通项目用户无法创建或发布全局 Workflow、切换 Prompt 或模型配置。
- 历史运行在新版本发布后仍解析原始 snapshot 和 bundle，回放结果不追随当前 active 配置。
- 契约校验脚本不依赖某个固定 Flyway 文件名。

### OPT-P0-02 Prompt/Model 执行 bundle

目标：消除 MySQL Registry、Worker Prompt 文件和运行环境配置之间的多事实源问题。

实施项：

- 定义版本化 `ExecutionBundle` Schema，至少冻结：
  - Workflow/节点/Handler 版本及 Schema hash。
  - Prompt key、version、规范化内容和 checksum。
  - 模型 provider key、model name、route、能力、上下文窗口、输出上限和计价版本。
  - Context/Retry/Tool/Retrieval/Cache Policy 的版本与 hash。
- 发布 WorkflowVersion 时由 Spring Boot 解析已批准 Prompt 和模型配置，生成不可变 bundle；缺失、重复或 checksum 不一致时禁止发布。
- 统一 Prompt key 命名，消除 `ProductManagerAgent` 与 `product_manager` 等同义键。
- WorkflowRun 启动时冻结 bundle 引用和 bundle hash；Redis NodeCommand 只携带冻结值。
- Worker 使用 bundle 中的 Prompt 内容和路由，并校验 checksum；本地 Prompt 文件仅用于开发种子或构建校验，不能在运行时覆盖 bundle。
- API Key 继续只由安全环境注入。bundle、数据库、事件和日志不得包含密钥。
- Provider endpoint 如需环境覆盖，必须绑定 provider key 和部署策略版本，并在 Trace 中明确记录，不得无痕漂移。

验收标准：

- 数据库 active Prompt 在运行中改变，不影响已启动 WorkflowRun。
- Worker 本地 Prompt 文件被篡改时，契约校验在模型调用前失败。
- `model_invocation` 的 prompt/model/bundle 引用与实际调用完全一致。
- fixture 与 live 模式共用同一个 bundle Schema；差异只在 Provider 执行器和密钥来源。

## 6. P1：增强 Agent 能力但保持受控

### OPT-P1-01 节点级 RAG 与统一评测

目标：让每个节点按自己的任务和最新可信上下文检索，同时结束 Java 生产检索与 Python fixture 评测各自演进的问题。

实施项：

- 在 WorkflowSpec 节点中增加并冻结 `retrieval_policy`：语料范围、top-N/top-K、Token 配额、单 Artifact 上限、查询模板、检索/Embedding/Reranker 版本和超时。
- 控制面在节点进入可运行状态、组装输入时生成查询：
  - Product Manager：原始需求、业务术语和约束。
  - Architect：需求 ID、PRD、架构约束和待决策问题。
  - Backend/Frontend：需求 ID、实体、API、页面和权限契约。
  - Reviewer/返工节点：issue ID、证据路径和 required changes。
- 节点检索由拥有 MySQL 权限事实的 Spring Boot 服务执行；Python Worker 不直接访问数据库。
- 每次节点检索冻结 query hash、命中、排序分数、Artifact/version/chunk hash、策略版本和权限过滤摘要；回放默认复用原快照，显式“刷新检索”才产生新运行。
- 将 Java `KnowledgeIndexService` 定义为生产检索实现；Python `HybridRetriever` 只作为契约 fixture/eval adapter，使用同一组跨语言 golden cases，禁止两套算法悄然给出不同语义。
- 第一阶段先优化批量读取、候选过滤和结构化 JSON 分块；只有基线证明 JVM 全量评分成为瓶颈后，才接入可替换向量索引。
- 评测集加入脱敏的真实 AutoSpec Artifact，覆盖中文/英文、同义词、相邻重复 chunk、空召回、过期版本、无权项目和返工问题。

验收标准：

- 跨项目召回率、无权文档召回率和默认旧版本召回率均为零。
- 同一冻结输入和策略可重放相同检索快照；刷新模式明确产生新的 snapshot hash。
- 维护 Recall@5、MRR、nDCG@5、引用精确率、检索 P50/P95 和空召回率，并保存逐 Case 失败原因。
- Java 与 Python golden contract cases 对 hit identity、过滤原因和版本元数据给出一致结果。

### OPT-P1-02 全链路引用完整性门禁

目标：任何使用知识来源的 Artifact 都能证明引用存在、版本正确、内容未漂移，并由最终门禁统一约束。

实施项：

- 定义统一 Citation Schema：citation ID、project、Artifact ID/type/version、chunk ID/index、Artifact/chunk hash、excerpt 和 retrieval snapshot hash。
- PRD、架构、后端、前端、Reviewer、Evaluator 只要输出 citation，就必须通过同一校验器。
- 校验不再依赖 requirement 是否出现 `history`、`rag` 等关键词，也不只检查 PRD。
- Reviewer 先执行确定性 citation 校验，再做模型语义审查；模型不能降低确定性问题的严重级别。
- Evaluator 把无效引用、无依据关键结论和 MUST 追踪断裂写入追踪矩阵；HIGH/CRITICAL 问题继续阻断完成、代码生成和导出。
- 前端展示引用定位、版本、校验状态和失效原因，但不向无权用户泄漏来源项目元数据。

验收标准：

- 篡改 chunk 内容、hash、版本、project ID 或 excerpt 均被确定性拒绝。
- 未携带 citation 的普通 Artifact 不被误判；携带 citation 的所有 Artifact 都不能绕过校验。
- 返工生成的新版本能关联触发问题和被替换引用，旧引用不会继续显示为有效。

### OPT-P1-03 生产级受控工具通道

目标：复用现有 Python ToolHarness，把工具从“测试能力”接到正式 Redis Worker 链路，同时保持控制面权限和 MySQL 事实边界。

实施项：

- 不重写 `ToolRegistry`/`ToolHarness`；补齐生产注册、传输、权限上下文和持久化实现。
- 使用控制面托管的 Tool Gateway：Worker 发布带 execution ID、node run ID、actor、project、fencing token、deadline、policy hash 和幂等键的工具命令；Spring Boot 校验并执行后返回结构化结果。
- 工具请求/结果通过独立 Redis Streams 或等价的可恢复通道传输；MySQL 保存最终调用事实，Redis 不作为唯一台账。
- 首批仅开放：
  - `knowledge.search`
  - `artifact.get`
  - `contract.lookup`
  - `trace.query`
  - `bundle.verify`
- 每个工具固定名称/version、输入输出 Schema、权限 policy、side-effect 等级、最大结果、单次/总超时和重试规则。
- 默认只允许 `READ_ONLY`/`DETERMINISTIC`；禁止任意 shell、任意 HTTP、Worker 数据库直连和未声明写操作。
- 模型工具循环共享节点 deadline、模型/工具调用数和成本预算；只有离线实验表明有收益的节点才开启 allowlist。
- 工具返回按不可信数据处理，Prompt Injection 文本不得改变系统指令或调用权限。
- 每次请求、结果、失败、缓存命中和修复尝试进入逐次调用台账，并与 workflow/node/execution/trace 关联。

验收标准：

- 未声明工具、错误版本、越权 project、过期 fencing token和超预算请求均在执行前拒绝。
- 重复 Redis 消息只产生一个有效工具结果和一条可结算调用事实。
- Worker 或控制面中断后，工具调用进入可恢复、可重试或明确 DLQ 状态，不无限等待。
- 工具超时、非法参数、返回超限、Prompt Injection 和结果 Schema 损坏均有针对性测试。

## 7. P2：在正确性之上优化性能与体验

### OPT-P2-01 分层缓存与失效协议

目标：降低重复检索和模型成本，同时保证租户、权限、版本和追踪安全。

实施项：

- 保留现有 `execution_id` 终态幂等缓存，不改变其至少一次投递语义。
- 引入单调递增的 `corpus_epoch` 与访问策略版本；Artifact 批准、失效、权限变化和索引重建完成时更新对应 epoch。
- RAG 查询缓存 key 至少包含：`project_id + actor_scope_hash + corpus_epoch + query_hash + retrieval_policy_hash + k`。
- 可选节点结果缓存 key 至少包含：`bundle_hash + canonical_input_hash + retrieval_snapshot_hash + tool_policy_hash + provider/model`。
- 只缓存通过输出 Schema、引用校验和确定性 Reviewer 规则的成功结果。
- 不缓存人工审批决定、失败终态、含未脱敏敏感信息的结果或有副作用工具响应。
- 命中记录 source execution、层级、来源版本、节省 Token/成本和失效原因；回放页面能够解释命中。
- 先 shadow-read 比较结果，再逐节点开启；任何一致性差异立即关闭节点结果缓存而不影响正式执行。

验收标准：

- 权限、语料、Prompt、Schema、模型或策略变化后旧缓存不可命中。
- 不同项目或不同访问范围即使 query 相同也不能共享受保护结果。
- 缓存命中与未命中经过相同的 Schema、引用和交付门禁。
- 缓存收益用实际 Token、成本和时延对照数据证明。

### OPT-P2-02 操作台与诊断闭环

目标：让用户和运维人员能从一次运行下钻到真正执行的配置、检索、工具和缓存证据。

实施项：

- Workflow 运行页展示 bundle version/hash、节点 contract hash 和 replay 模式。
- 节点详情展示 Retrieval Trace、citation 状态、工具调用、调用次数/预算、缓存来源和失效原因。
- 治理页面按权限提供 Workflow/Prompt/Model 草稿、校验、发布和版本差异；普通项目用户只读可见与其运行相关的脱敏元数据。
- DLQ、预算拒绝、工具失败、引用阻断和索引失败提供明确的可操作提示，不只显示通用错误码。
- 关键列表使用现有游标分页约定；不在前端加载完整调用台账或大型 Artifact。

验收标准：

- 用户可以从阻断结论定位到 requirement、Artifact、citation、tool call 和执行 bundle。
- 未授权用户看不到 Prompt 正文、其他项目来源、内部工具参数或敏感错误内容。
- 页面在长历史、大 Artifact 和大量调用记录下仍使用分页/懒加载。

### OPT-P2-03 容量、安全与发布证据

目标：用真实环境证据决定是否发布，而不是把模板或 fixture 通过当成生产结论。

实施项：

- 在隔离环境完成 fixture 与 live 两套基线，填写 `docs/p1-capacity-and-recovery-report.md`，记录 commit、资源、并发、模型、数据集和采样窗口。
- 分别测量 API、队列、节点、检索、工具和端到端的 P50/P95/P99、错误率、恢复率、Token/run 和 cost/run。
- 执行 Outbox 积压、Redis 短断、Worker 崩溃、工具超时、Provider 限流、索引损坏和缓存失效故障演练。
- 增加治理接口越权、跨项目 RAG、工具参数注入、Prompt Injection、日志脱敏和重放攻击回归。
- 数字阈值只在首轮真实基线后冻结；后续发布使用相对回归门禁和明确的绝对安全不变量。
- 生成脱敏证据包：测试结果、契约 hash、样例 Trace、检索评测、容量报告、故障演练和已知限制。

验收标准：

- 安全不变量全部为零容忍：跨项目召回、未授权发布、未授权工具执行、重复有效副作用、无效引用交付。
- 容量报告不含“待填写”，所有数字可关联原始结果和部署版本。
- 发布前完整三端回归、契约同步、基础 Compose 和 monitoring Compose 校验通过。

## 8. 实施顺序与里程碑门禁

### M0：契约可信

完成 `OPT-P0-01`、`OPT-P0-02`。在 M0 完成前，不启用生产工具和节点结果缓存。

退出条件：已发布版本不可变；平台发布权限生效；运行能冻结并验证唯一 ExecutionBundle。

### M1：检索可信

完成 `OPT-P1-01`、`OPT-P1-02`。

退出条件：节点级检索可回放；跨语言评测一致；所有 citation 进入确定性门禁。

### M2：工具可信

完成 `OPT-P1-03`，先在一个高收益节点灰度，再按评测结果扩大。

退出条件：首批只读工具走正式权限、预算、台账和恢复链路；无任意外部执行能力。

### M3：性能与体验

完成 `OPT-P2-01`、`OPT-P2-02`。

退出条件：缓存可以解释和失效；操作台可定位 Bundle/RAG/Tool/Cache 的完整因果链。

### M4：发布证明

完成 `OPT-P2-03`。

退出条件：真实容量、安全和故障演练证据齐全，所有交付门禁通过。

## 9. 跨模块影响清单

| 模块 | 主要改动 |
|---|---|
| Backend | 不可变发布、平台治理权限、ExecutionBundle、节点检索编排、Tool Gateway、调用事实、cache epoch、诊断 API |
| Agent Engine | bundle 执行与 checksum、统一检索契约、生产工具适配、受限工具循环、cache provenance、评测集 |
| Frontend | 发布治理、版本差异、引用/RAG/Tool/Cache 诊断、阻断定位 |
| Contracts | WorkflowSpec、Redis tool command/result、OpenAPI、Pydantic/Java/TypeScript DTO、事件协议 |
| Database | 只新增 Flyway 迁移；可能新增 bundle、tool call、corpus epoch 和 cache provenance 事实，不改写 V1–V88 |
| Observability | bundle/retrieval/tool/cache 指标、Trace 关联、告警与保留策略 |
| Docs/Eval | golden cases、检索与工具实验、容量报告、故障演练、脱敏运行样例 |

## 10. 测试策略

日常开发只运行当前工作包直接相关的测试；以下情况执行完整回归：跨语言 Schema、OpenAPI、WorkflowSpec、Redis 事件、Flyway、依赖或里程碑收尾。

每个工作包至少包含：

- 一个核心成功流程。
- 一个真实高风险失败流程。
- 一个幂等/重复消息流程（涉及持久化或 Redis 时）。
- 一个权限隔离流程（涉及项目数据、治理或工具时）。
- 一个不可变历史/回放流程（涉及 bundle、检索或缓存时）。

完整发布验证：

```powershell
Set-Location 'backend'
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' test

Set-Location '..\agent-engine'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q

Set-Location '..\frontend'
npm test
npm run build

Set-Location '..'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' scripts/verify_workflow_contract.py
docker compose config --quiet
docker compose --profile monitoring config --quiet
```

## 11. 风险与回退

| 风险 | 控制措施 | 回退方式 |
|---|---|---|
| 新 bundle 与旧 v5 运行不兼容 | 新版本发布、双读校验、历史 snapshot 回放测试 | 新运行切回最后稳定的已发布版本，历史版本不修改 |
| 节点检索增加延迟或噪声 | per-node policy、Token 配额、离线 golden cases、灰度 | 关闭目标节点 retrieval policy，保留启动快照 |
| 工具循环放大成本或卡死 | 硬调用数、总 deadline、预算预占、只读 allowlist | 将节点 tool policy 置为 disabled，不回退 Worker 主链路 |
| 缓存返回错误或越权结果 | actor scope、epoch、shadow-read、命中后再校验 | 按层关闭缓存并提升 epoch，不删除事实数据 |
| 治理权限影响现有开发流程 | 开发环境种子管理员、明确审计和文档 | 保留只读查看，暂停发布写操作而不影响已有运行 |

## 12. 第一批可执行任务

实施从以下顺序开始，避免同时改动所有能力：

1. `OPT-P0-01-T01`：为已发布 WorkflowVersion 增加服务层不可变测试，禁止原地更新。
2. `OPT-P0-01-T02`：设计下一个新增 Workflow 版本的种子方式，并移除校验脚本对 V86 文件名的绑定。
3. `OPT-P0-01-T03`：定义平台治理 capability，补 Workflow/Prompt/Model 写接口的授权矩阵。
4. `OPT-P0-02-T01`：先提交 ExecutionBundle JSON Schema、canonical hash 规则和 Java/Python golden contract test，不立即切换生产执行。
5. M0 验收通过后再启动节点级 RAG，不与 Tool/Cache 并行上线。

本计划完成的最终标志不是“代码项全部打勾”，而是任意一次 AutoSpec V5 运行都能回答并证明：执行了哪个不可变契约、使用了哪些 Prompt/模型/检索/工具、为何命中缓存、花费多少预算、产生哪些引用，以及为什么允许或阻止交付。

## 13. 本次执行记录（2026-09-06）

本次在分支 `codex/autospec-v5-next-optimization` 上按工作包顺序完成了 P0–P2-02 的代码交付，并将 P2-03 的本地发布证据写入 `docs/p1-capacity-and-recovery-report.md`。GitHub PR 为 [#7](https://github.com/KleMoretti/AutoSpec/pull/7)。

### 分批提交

| 批次 | Commit | 内容 |
|---|---|---|
| P0/P0-02 | `3bf270e` | Workflow governance、execution bundle、真实校验和平台治理 |
| P1-01/P1-02 | `8d0db4c` | Citation metadata、retrieval integrity gate |
| P1-03 | `01b82b5` | Controlled Tool Gateway 与 Worker 注册 |
| P2-01 | `cd53255` | Corpus epoch、分层缓存 key、provenance 与失效协议 |
| Contract fix | `9f230cb` | Worker Schema hash、canonical contract、V94 不可变 seed 同步 |
| P2-02 | `14d9b21` | Bundle/Trace/Tool/Cache 运行证据 API 与回放诊断 UI |

### 本地门禁结果

- Backend：195 tests，0 failures，0 errors，0 skipped；29.34s wall time。
- Agent Engine：103 passed；fixture 回归通过。
- Frontend：9 个测试文件、24 tests passed；production build 转换 3097 modules 并通过。
- `scripts/verify_workflow_contract.py`、base Compose 和 monitoring Compose 配置校验全部通过。
- 详细命令、环境和边界见 `docs/p1-capacity-and-recovery-report.md`。

### P2-03 状态边界

P2-03 的代码级安全回归和本地发布门禁已完成；live provider、Docker 端到端容量、P50/P95/P99 采样及故障注入没有在本机执行，报告已逐项标注为“未执行”。因此整体计划暂保持 `in_progress`，生产签署需在隔离环境补齐这些外部证据。
