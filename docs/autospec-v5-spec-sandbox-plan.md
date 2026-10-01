---
plan_id: autospec-spec-sandbox
version: 1.0
status: planned
created_at: 2026-09-29
updated_at: 2026-09-29
product_baseline: autospec-v5:pm-schema-repair-v12
predecessor: docs/archive/agent-execution-plan.md
---

# AutoSpec 面试就绪与特色功能计划（Spec Sandbox）

> 2026-09-30 维护更新：用户授权将数据库迁移收敛为新的 V1，保留最近五次运行；后续数据库变更从 V2 开始。当前基线与回滚边界见 [基线收敛记录](archive/evidence/baseline-consolidation-2026-09-30.md)。下文关于旧迁移、候选和旧默认版本的说明记录原计划背景，不构成恢复旧版本的指令。

> 本文件是当前任务总计划。按用户要求，具体实施顺序、文件入口、必要边界测试和验收分别见 [P0/P1 执行手册](p0-p1-execution-plan.md) 与 [P2–P4 执行手册](p2-p4-execution-plan.md)，两者保留在 `docs/` 根目录；后者包含当前基线复核、最小测试、命令、停止条件与断点交接，创建计划不代表已实施。其他文档和运行证据均在 [archive/](archive/README.md)，仅供参考；本文件名按用户指定保留，产品名称统一为 AutoSpec。

## 1. 结论

现有代码已具备较完整的工程底座，但默认 live 路径、通用门禁与可执行交付验证仍有缺口。本计划描述待实施工作，不把计划内容视为已有能力。

- **强项在工程底座**：冻结 WorkflowSpec 的 DAG、事务 Outbox、幂等消费、fencing、DLQ、人工审批与定向返工、预算预占、Artifact 版本、REQ→STORY→AC→API/TABLE→PAGE 追踪 ID。这些足以回答“可靠性 / 编排 / HITL / 可观测 / 成本”类问题。
- **弱项在默认 Agent 路径与证据**：默认发布工作流未配置节点内循环、工具或节点检索策略；已有 live smoke 的 5 次尝试均在 Product Manager 失败；部分门禁规则混入业务领域和平台自身接口；现有交付验证只能证明有限的骨架一致性，不能证明完整应用可运行。
- **差异化需要证据和展示**：可恢复编排、人工审批、定向返工和交付门禁已经具备产品价值；Spec Sandbox 是进一步增强可执行验证的候选方向，不是对现有能力的否定。

目标：

1. 每个高频面经主题都有“代码 + 证据 + 讲法”。
2. 增加一个可演示、可度量的特色功能——**Spec Sandbox（规格沙箱）**。

## 2. 核验后的差距

图例：✔ 本轮亲自读代码或运行命令核验；◐ 子代理静态审计结论（未运行测试，实施前需复核）。

| # | 状态 | 事实 | 证据 | 面试风险 |
|---|---|---|---|---|
| 1 | ✔ | 已发布 `v5` 与 `v5-parallel` 的六个节点均未配置 `agent_loop_policy` / `tool_policy` / `retrieval_policy`；只有未激活候选（如 `autospec-v5-agent-execution-v6-d`）在 Backend 节点启用循环与工具 | `agent-engine/contracts/*.workflow.json`（JSON 解析） | “循环在哪里跑？”——默认路径是单次 JSON 生成 |
| 2 | ✔ | 已保存的 live smoke：一个业务用例的 5 次尝试全部在 Product Manager 失败（4 次输出预算耗尽/空响应，1 次 PRD Schema 失败），结论 `NOT_EVALUATED`；不代表所有历史运行 | `docs/archive/examples/agent-eval-live-smoke-2026-09-17.json` | 默认 live 完整链路与循环质量收益尚缺成功证据 |
| 3 | ✔ | 通用模型网关使用 `json_object`；截断、空响应和无效 JSON 抛错，Product Manager 的 Schema 失败没有错误回填修复。候选 Backend 循环已有 `SCHEMA_INVALID` 捕获、错误回填和 Replan，不能说全仓库没有修复 | `agent-engine/model_gateway.py`、`agent-engine/agents/product_manager.py`、`agent-engine/review/backend_validator.py`、`agent-engine/runtime/backend_agent_loop.py` | 默认路径缺少统一的有界输出修复 |
| 4 | ✔ | 确定性规则按二手交易写死（收藏/发布/搜索/订单/审核）；PRD 只要出现 approval/event/retry，就以 HIGH 强制要求 AutoSpec **自身**接口（`/api/projects/{projectId}/artifacts/{artifactId}/approve` 等）；fixture Agent 对任何需求都返回二手交易模板 | `agent-engine/review/rules.py:12-38,114-146,358-389`、`agents/product_manager.py:29-90` | 换一个业务领域，门禁会误杀或漏检 |
| 5 | ✔ | 交付验证包含文件、验收映射、前端静态检查、秘密扫描，以及两个 Java 模板的编译和 READY mock 检查；不执行完整数据库、前端构建或业务验收 | `GeneratedBundleVerificationService.java:54`、`GeneratedBundleVerificationService.java:176`、`CodeSkeletonService.java:297` | 骨架校验不能作为业务可运行证明 |
| 6 | ✔ | MCP / A2A / Skills 代码为零；工具仅 5 个只读元工具，靠 prompt 内 JSON 选择，无原生 function calling | 全仓库检索、`agent-engine/runtime/tool_gateway.py` | 高频考点无对应实现 |
| 7 | ✔ | 截至 2026-09-29 查询，master 最新 CI（2026-09-22）失败；MySQL 故障检测 5257 ms 超过 5000 ms 断言，恢复为 28 ms。单次失败不足以定性为偶发 | [CI 运行](https://github.com/KleMoretti/AutoSpec/actions/runs/35705562787)、`MySqlFailureRecoveryIT.java:123` | 需要定位检测耗时并重新验证 |
| 8 | ✔ | 默认 hashing 嵌入；Java/Python 已有真实 embedding 适配。三个离线 gold 用例复测 Recall/MRR/nDCG 为 1.0，只能证明该小样本；无真实 embedding 收益证据 | `KnowledgeEmbeddingService`、`runtime/embedding_provider.py`、`evaluation/datasets/autospec_retrieval_gold_v2.json` | 区分适配器、配置和质量收益 |
| 9 | ✔ | 项目记忆是 Artifact 规则投影，已有来源、hash、版本、有效期和 SUPERSEDED 替代；包含未批准产物，缺少相关性排序召回。旧 memory/agent_state 模块是否可删仍需逐项依赖核验 | `ProjectMemoryService`、`runtime/context_policy.py` | 重点验证批准状态与召回效果，不重复建设版本机制 |
| 10 | ◐ | 控制面缺 watchdog：心跳只落库、ORPHANED 从不赋值；非格式错误的 poison 事件无投递上限；outbox 发布无行级占用（多实例可能重复）；cancel 不通知 worker | `WorkflowEventConsumer`、`WorkflowOutboxPublisher`、`WorkflowRecoveryService` | 项目主打“可靠”，这里最易被追问 |
| 11 | ◐ | 前端看不到 Agent 步骤（后端 `/trace` 已返回 `steps[]`，`api/workflow.ts` 未接入）；无 DAG 图、追踪矩阵、评测看板；README 无截图/GIF、无取舍与局限，未披露 live 失败 | `frontend/src`、`README.md` | Demo 与介绍缺可视化证据 |
| 12 | ◐ | 无需求澄清、变更影响分析、增量重生成；产物 diff 仅 ≤12 个 JSON 路径 | 后端审计 | 缺“产品智能” |
| 13 | ✔ | 2026-09-29 已将其他资料归档；本文件为当前总计划，按用户新要求增加 P0/P1 执行手册。两份文件属于同一任务，产品正文统一称 AutoSpec；代码遗留语义仍需单独核验 | [执行手册](p0-p1-execution-plan.md)、[归档资料](archive/README.md) | 文档计划不等于实现完成 |

## 3. 特色功能：Spec Sandbox

### 3.1 定位

> 在已有规则检查和模型语义审查基础上，为规格补充可执行的确定性验证证据。

把 API / 数据 / 前端契约确定性编译为 OpenAPI、MySQL DDL 和类型化客户端，在隔离环境里真的校验、落库、类型检查、回放；失败以稳定 issue code 回灌 Backend Agent 循环的 Replan，并成为交付门禁的一等证据。

### 3.2 为什么选它

- 直接补上差距 #5，兑现产品定位中的“交付验证”。
- 把 Plan-Act-Observe-Validate-Replan 从“模型自评”升级为“环境反馈”（grounded reflection），循环的价值因此可以被量化。
- 判分器确定性、验证器自身不调用 LLM；验证仍消耗计算资源，生成与修复调用另计费用。缺陷集用于复现和校准检测效果。
- 一次覆盖面经里的工具使用、自我修正、幻觉控制、沙箱安全、评估五类问题。
- 不违反 AGENTS.md：不新增 Agent，工作流顺序仍只来自 WorkflowSpec，Worker 不直连业务库。

### 3.3 架构

```text
Worker（Backend 循环 / Reviewer）
   │  tool call: spec.verify:v1
   ▼
Tool Gateway（Java：权限 / fencing / 预算 / 幂等 / 台账）
   │  内网 HTTP + 服务 Token
   ▼
spec-verifier sidecar（Python，无业务密钥）
   ├─ 编译（确定性）  ApiDesign→OpenAPI 3.1 │ TableDesign→MySQL DDL │ 前端绑定→调用清单
   ├─ L1 进程内       OpenAPI 校验 · 绑定回放 · sqlglot(mysql) + 语义检查 · ID/引用完整性
   └─ L2 隔离执行     verify-mysql（tmpfs、随机 schema、最小权限）· tsc --noEmit ·（延伸）mvn -o compile
   ▼
VerificationReport（issue code + 证据路径 + 源 Artifact 版本 hash）
   → Backend 循环 Validate / Reviewer ReviewIssue / Evaluator 追踪矩阵“已验证”维度
```

要点：

- **并行模式的顺序约束**：`v5-parallel` 中 Backend ∥ Frontend 同时运行，Backend 节点只能验证后端可验部分（OpenAPI、DDL、共享契约一致性）；跨 Artifact 的“前端绑定回放”在 Reviewer 汇合后执行。
- **新增 side-effect 等级 `SANDBOXED`**：不修改平台事实，默认禁用，节点必须在冻结的 `tool_policy` 里显式 allowlist；WRITE 类工具仍默认拒绝。
- **VerificationReport 落点**：新版本 ReviewReport / EvaluationReport 内嵌精简摘要，可信完整明细走工具调用台账；交付必须核对来源 hash、策略、要求层级和事实引用，不能相信模型自报 PASS。

### 3.4 首版检查目录

| 层级 | 检查 | issue code 前缀 | 初始严重级别 |
|---|---|---|---|
| L1 | OpenAPI 文档有效性、路径参数已声明、操作唯一 | `OAS_*` | HIGH |
| L1 | DDL 解析；PK 缺失、FK 目标缺失、FK 类型不匹配、保留字、标识符超长 | `DDL_*` | HIGH / MEDIUM |
| L1 | 前端绑定回放：操作缺失、参数不符、引用了响应中不存在的字段 | `BINDING_*` | HIGH |
| L1 | 追踪完整性：引用悬空；MUST 需求缺已验证的 API / 表 / 页面 | `TRACE_*` | HIGH |
| L2 | DDL 在一次性 MySQL 8.4 中真实落库 | `DDL_APPLY_*` | HIGH |
| L2 | 由 OpenAPI 生成的类型化客户端 + 页面桩 `tsc --noEmit` | `TS_*` | MEDIUM，校准后再定 |
| L2（延伸） | 骨架 `mvn -o compile` | `BUILD_*` | 待定 |

严重级别在缺陷注入集上校准后冻结；模型不能降低确定性问题的级别（沿用 Reviewer 既有原则）。

前置条件：现有 `TableDesign` 未显式声明主外键，API 参数缺少位置，前端 `ApiBinding` 缺少请求/响应字段映射。先完成 SIG-P1-00，禁止通过字段名猜测补齐这些事实。同一编译器生成的客户端与页面桩通过类型检查，只证明内部一致性；必须加入独立正反例、缺陷注入和跨 Artifact 检查，不能据此宣称业务需求正确。

### 3.5 安全边界

- 输入只有**结构化、已通过 Schema 校验**的字段；DDL / OpenAPI / TS 由确定性编译器生成；标识符白名单 `^[A-Za-z_][A-Za-z0-9_]{0,63}$` 并转义；**绝不执行 LLM 撰写的 SQL、代码或 shell**。
- sidecar：独立容器、只读根文件系统、无 docker socket、无法访问业务 MySQL / Redis（网络策略有测试）、CPU / 内存 / 时间 / 输出大小硬限、tmpfs 工作目录。
- `verify-mysql`：独立实例、无数据卷、每次随机 schema、专用用户仅拥有 `autospec_verify_%` 权限、结束即删除。
- 工具结果按不可信数据处理（沿用既有原则）；调用经 Tool Gateway 保留幂等、fencing、deadline、结果大小校验。

### 3.6 延伸特色（可选）：需求变更影响分析 + 增量重生成

复用 trace graph 与 `ReworkPlanner`：用户修改一条需求 → 沿 REQ→STORY→AC→API/TABLE→PAGE 计算影响子图 → 只重跑受影响节点，指令限定为受影响 ID → 确定性校验“未受影响组件 hash 不变” → 只重新验证受影响切片。对应 SIG-P3-06，只有主线完成后才做。

### 3.7 简历表述模板（数字只能填实测值）

> 设计并实现 Spec Sandbox：将 Agent 产出的 API / 数据契约确定性编译为 OpenAPI / DDL，在隔离沙箱中执行校验（DDL 落库、契约回放、类型检查），失败以稳定 issue code 回灌 Plan-Act-Observe-Validate-Replan 循环并作为交付门禁证据；在 `<N>` 个 holdout 用例上门禁通过率 `<X>%→<Y>%`（95% CI `<…>`），缺陷注入集检出率 `<Z>%`。

## 4. 路线图

状态统一使用 `planned` → `in_progress` → `blocked` / `done`。规模 S / M / L 为相对估计，不是工期承诺。

### P0：先让它真实可用

| ID | 规模 | 工作包 | 验收 |
|---|---|---|---|
| SIG-P0-01 | S–M | Live 路径端到端跑通：保留 `json_object`，先实现一次有界错误回填修复并计入预算；新候选显式冻结 thinking 与输出上限。原生结构化输出能力适配后置，详见执行手册 | 获新预算授权后，DeepSeek live 下 ≥1 个新候选 smoke 用例六节点 COMPLETED 且交付门禁 PASS；保存脱敏 Trace；不据此声称当前 active 已改变或总体质量提升 |
| SIG-P0-02 | M | 门禁去领域化：默认规则改为基于 `requirement_id` / `api_id` / `table_id` 的结构性检查；领域关键词与“强制 AutoSpec 自身接口”移出默认集，改为可选 rule pack；fixture 覆盖 ≥3 个领域 | 3 个领域的 fixture 通过默认规则；黄金缺陷样本仍 100% 被拦截；默认规则不再引用 AutoSpec 自身路径 |
| SIG-P0-03 | S | 可信度清理：定位并修复 `MySqlFailureRecoveryIT` 故障检测超时，重新验证 CI；文档归档、索引与产品命名已整理，后续核验变更后的链接与状态；确认后删除根目录误装的 `package.json` / `package-lock.json`；删除孤儿模块与 interview 遗留命名（数据库侧只新增迁移，不改历史） | CI 全绿；README 链接无 404；生产路径无 resume / question 语义 |

### P1：特色功能

| ID | 规模 | 工作包 | 验收 |
|---|---|---|---|
| SIG-P1-00 | M | 先定义版本化契约：结构化 PK/FK、受限字段类型、参数位置、响应结构、前端参数/响应字段绑定；同步消费者与新候选，保留旧契约解析 | 不靠命名猜测主外键或参数位置；每项检查有明确输入字段；历史回放仍通过 |
| SIG-P1-01 | M | 在 SIG-P1-00 完成后实现契约编译器（确定性）：`ApiDesign→OpenAPI 3.1`、`TableDesign→MySQL DDL`（仅由结构化字段生成）、前端绑定调用清单；输出绑定源 Artifact 版本 hash | 同输入两次输出逐字节一致；恶意 / 保留字标识符 100% 被拒绝或转义 |
| SIG-P1-02 | M | L1 进程内校验器与 `VerificationReport` Schema；按用户要求仅保留必要边界测试：8 类风险的 9 个最小反例，复用 3 个正常规格，详见执行手册 | 已声明阻断缺陷全部检出，正常样例无误报；issue code 稳定；记录限定输入规模下 P95 是否 < 2 s，不把结果泛化为整体质量 |
| SIG-P1-03 | L | L2 隔离执行：`spec-verifier` sidecar + `verify-mysql` + `tsc --noEmit`；Tool Gateway 新增 `spec.verify:v1`（`SANDBOXED`），调用事实入台账 | 网络策略测试证明 sidecar 不可达业务 MySQL / Redis；超时 / 内存 / 输出大小硬限生效；重复调用幂等 |
| SIG-P1-04 | M | 接入 Agent 与门禁：Backend 循环 Validate 使用 `backend-design-v2` profile；Reviewer 执行全套并把失败转成 HIGH `ReviewIssue`；Evaluator 追踪矩阵新增“已验证”维度，MUST 需求缺验证即阻断交付 | 构造的缺陷 Candidate 触发 Replan，并在预算内修复或明确失败；缺验证的 MUST 阻断交付 |

### P2：证据

| ID | 规模 | 工作包 | 验收 |
|---|---|---|---|
| SIG-P2-01 | S | 复用 P0/P1 为验证创建的未激活候选，冻结 P2 消融配置和版本；契约与新迁移同步，迁移编号实施时分配。仅 Backend 开循环，其余节点不增加 Agent 决策循环；Reviewer 可执行确定性工具验证 | 契约脚本通过；候选通过 Eval Gate 前不激活，active 仍为 `v5-parallel` |
| SIG-P2-02 | M | 冻结 Eval Set 与 A/B/C/D 消融（见第 6 节）；需新预算授权 | 逐 Case 结果、配置 hash、命令可复现；决策为 `PROMOTE` / `REVISE` / `REJECT`，负面结果如实记录 |
| SIG-P2-03 | M | 证据包：一条含 verifier 反馈的成功 Replan Trace、一条预算 / 震荡终止 Trace；10 / 20 并发容量与故障演练结果落盘（脱敏） | 所有数字关联 commit / bundle hash / 环境；未执行项明确写“未执行” |

### P3：补齐其余面经点（各自独立，可裁剪）

| ID | 规模 | 工作包 | 验收 |
|---|---|---|---|
| SIG-P3-01 | M | Governed MCP：Tool Gateway 只在明确存在外部工具集成需求时，将只读工具以 MCP server 暴露；client 侧让 Backend 循环经 MCP 使用外部工具，仍受 allowlist / 预算 / 幂等 / 审计约束 | MCP 调用与原生工具进入同一台账；未授权工具被拒 |
| SIG-P3-02 | S–M | Prompt Injection 红队集：≥30 条（检索片段 / 工具结果 / Artifact 文本）入回归；不可信内容隔离与标注 | 注入样本 0 次改变工具权限或系统指令 |
| SIG-P3-03 | S–M | 原生 function calling 适配，并与 JSON-in-prompt 对照工具选择准确率与参数有效率 | 报告含逐 Case 差异 |
| SIG-P3-04 | M | RAG：真实 embedding 与 hashing 在同一 golden set（≥100 例）上对照；接入用户上传的领域文档语料；为现有记忆版本/替代机制补充批准状态约束和相关性召回 | Recall@5 / MRR / nDCG 实测；跨项目召回 0 |
| SIG-P3-05 | M | 控制面 watchdog（租约超时→ORPHANED→重派）、poison 事件投递上限→DLQ、outbox 行级占用、cancel 通知 worker | 对应故障演练通过并入库 |
| SIG-P3-06 | M–L | 延伸特色：需求变更影响分析 + 增量重生成 | 只重跑受影响节点；未受影响组件 hash 不变（确定性校验）；相对全量重跑的 Token 下降有数据 |

### P4：展示

| ID | 规模 | 工作包 | 验收 |
|---|---|---|---|
| SIG-P4-01 | M | 前端：步骤级 Trace 时间线、DAG 实时图、追踪矩阵热力图（含“已验证”）、Verification 报告面板、评测消融看板（数据来自后端已有 `steps[]` 与 `requirement_traceability`） | 从一次运行可下钻到某 MUST 需求的 API / 表 / 页面及其验证证据 |
| SIG-P4-02 | S–M | README 重写：架构图、取舍与局限、证据表、GIF / 截图、5 分钟 Demo 脚本、演示账号说明 | 新人按 README 5 分钟内看到一次完整运行 |
| SIG-P4-03 | S | 面经 Q&A 卡片（15 张）：一句话答案 + 代码 `file:line` + 证据文件 + 被追问时的取舍与失败故事 | 每张卡片的引用均可点击、可复现 |

### 依赖与截断线

```text
P0-01 ─┐
P0-02 ─┼─▶ P1-00 ─▶ P1-01 ─▶ P1-02 ─▶ P1-04 ─▶ P2-01 ─▶ P2-02 ─▶ P2-03
P0-03 ─┘                 │     ▲
                         │     └─ P1-03（L2 沙箱，可晚于 L1）
                         └─▶ P3-* / P4-*（P1-02 之后可并行）
```

- **最小可讲版本**（不含沙箱容器）：P0-01/02/03 + P1-00/01/02/04（仅 L1）+ P2-02 的节点级小规模评测 + P4-01（Trace / 矩阵）+ P4-02/03。
- **完整版**：其余全部；P3-06 视时间取舍。

## 5. 面经覆盖矩阵

| 面经主题 | 现状 | 动作 | 证据产物 |
|---|---|---|---|
| 项目架构 / 执行流程 | ✅ 强（冻结 Spec、DAG、Outbox、Worker、ADR-001） | P4-02 | 架构图、Demo 脚本 |
| ReAct / 计划-行动-观察、防死循环 | ⚠️ 代码有（max_steps / replan / path oscillation），默认全关，未 live 跑过 | P0-01、P2-01/02 | 成功 Replan Trace + 震荡终止 Trace |
| Function Calling / 工具治理 | ⚠️ 治理强，工具薄，无原生 tools | P1-03、P3-03 | 工具选择准确率 / 参数有效率 |
| MCP / A2A / Skills | ❌ 零 | P3-01 | MCP 台账样例 |
| RAG 全流程与评估 | ⚠️ 链路齐全，默认非语义嵌入，评测集极小 | P3-04 | Recall / MRR / nDCG 实测 |
| 记忆 / 上下文工程 | ⚠️ 有投影式记忆与 token 预算，无摘要，含未批准产物 | P3-04、P0-03 | 记忆写入 / 取代链测试 |
| 幻觉 / 护栏 / 结构化输出 | ⚠️ Schema + 引用门禁 + 规则；默认路径缺统一修复，候选 Backend 已有错误回填；注入防御需验证 | P0-01、P1-*、P3-02 | 缺陷检出率、注入红队集 |
| 多 Agent 协作 | ✅ 共享契约 fan-out、REWORK 路由；无辩论 | 准备“何时不该用多 Agent”论证 | 并行 vs 串行时延实测 |
| 评估体系 | ⚠️ 消融与 holdout 门禁齐全，缺少 live 质量收益证据 | P2-02 | 带置信区间的四组结果 |
| 可靠性 / 幂等 / HITL | ✅ 强；控制面 watchdog 缺口 | P3-05 | 故障演练结果入库 |
| 成本 / 时延 / 路由 | ✅ 预算预占 / 结算 / 路由；fallback 默认关 | P2-03 | 成本与时延分布 |
| 可观测 | ✅ OTel / Prometheus / Grafana；前端不可见 | P4-01 | 步骤时间线截图 |
| 沙箱 / 安全 | ⚠️ 只读工具白名单；交付验证覆盖有限 | P1-03 | 威胁模型 + 网络隔离测试 |

## 6. 评测与预算

- **判分器固定**：A/B/C/D 四组使用同一份门禁（含可执行校验），只有“生成侧能看到的反馈”不同，避免自证。
  - A：single-shot（当前默认）
  - B：Agent 循环，仅确定性校验
  - C：循环 + 只读工具
  - D：循环 + 工具 + `spec.verify`
- **分层实验**：节点级（固定上游 Artifact，只评 Backend 节点；便宜、低方差）+ 整链路（少量用例经 `POST /api/workflow-runs`，不新增同步入口）。
- **数据集**：沿用现有 8 smoke / 16 dev / 8 holdout，扩到 ≥5 个业务领域；另建缺陷注入集用于验证器本身。
- **统计**：每格 ≥3 次重复；Wilson 95% 区间；配对差异用 bootstrap；仅在 holdout 上做一次晋级决策；阈值沿用 AEX-P0-04（gate pass 提升 ≥8 个百分点，或每 Case blocking issue 中位数下降 ≥20%），成本 / 时延上限在冻结数据集前声明。
- **预算（估算，非账单）**：按 2026-09-17 快照价（DeepSeek flash 输入 ¥2 / 缓存 ¥0.04 / 输出 ¥8 每百万 token）与已观测 token 量级，节点级约 ¥0.03–0.12 / 次，整链路约 ¥0.2–0.3 / 次；4 组 × 16 例 × 3 次节点级加少量整链路，合计约 ¥20–40。旧的 ¥10 授权已在台账中被整额预占，需要**新的显式授权**，建议上限 ¥50。
- 未获授权前只跑 fixture 与确定性验证器（零成本）。

## 7. 约束、风险与回退

约束（来自 AGENTS.md）：

- 不新增第 7 个 Agent；`spec.verify` 是工具，不是节点。
- 工作流顺序只来自冻结 WorkflowSpec；`tool_policy` 按节点声明。
- Worker 不直连业务 MySQL；验证用的 MySQL 是独立一次性实例，仅 sidecar 可达。
- 不恢复 `/generate*`；sidecar 只供 Tool Gateway 内部调用，不提供业务流水线入口。
- Flyway 只增不改（V104+）；OpenAPI / WorkflowSpec 变更同步消费者、测试并运行 `scripts/verify_workflow_contract.py`。
- 真实 Key 不入库、不回显 `.env`；live 仅在 `MODEL_API_KEY` 已配置且预算已授权时启用。
- 日常只跑直接相关的测试；跨模块变更与里程碑收尾才跑三端全量。

| 风险 | 控制 | 回退 |
|---|---|---|
| live 输出仍不稳定（thinking 耗尽输出预算、Schema 漂移） | P0-01 修复回路 + 逐节点显式策略 + 先 smoke 再放量 | 保持 fixture 可演示；评测标注 `NOT_EVALUATED` |
| 沙箱被恶意或畸形输入滥用 | 只执行确定性编译产物；白名单标识符；最小权限；资源硬限；网络隔离测试 | 把 `spec.verify` 置 disabled，退回 L1 |
| 验证器误报 | 缺陷注入集校准；新检查先 MEDIUM 观察再升 HIGH | 逐检查项开关 |
| sidecar 拖慢流水线 | 超时与结果上限；按源 Artifact 版本 hash 缓存结果 | 仅在冻结策略允许 L1 时降级并标注；要求 L2 的交付在超时时标记未验证并阻断 |
| 评测结果不佳 | 预先声明决策规则；负面结果同样入库 | 候选保持未激活，`v5-parallel` 继续 active |
| 范围膨胀 | 最小可讲版本先行；P3 逐项独立可裁 | — |

## 8. 实施工具

按仓库 `AGENTS.md` 使用已确认的本机工具与测试命令。插件安装状态不作为计划前提；进入具体任务时核验所需工具。文档整理不安装插件或改变运行环境。

## 9. 第一批可执行任务

1. `SIG-P0-01-T01`：先写失败测试（fixture provider 注入 `length` 与坏 JSON），再实现修复回路。
2. `SIG-P0-02-T01`：建立黄金缺陷集与 3 领域 fixture，先让现有规则在其上暴露误杀，再重构规则。
3. `SIG-P1-00-T01`：补齐版本化契约字段及兼容设计，再实现 `SIG-P1-01` 的编译器与标识符安全测试。
4. `SIG-P1-02-T01`：L1 校验器与缺陷注入集。
5. 申请 live 预算授权，再做 `SIG-P0-01` 的 live 验证。

## 10. Definition of Done

- 一条脱敏的 live 端到端 Trace：六节点完成，门禁通过。
- Spec Sandbox 缺陷注入集检出率、误报率与耗时的实测报告。
- A/B/C/D 消融含置信区间；结论可以是负面的，但必须真实。
- README 含取舍 / 局限 / 证据表 / 截图或 GIF；15 张面经卡片的引用全部可点击、可复现。
- CI 全绿；没有新增 Agent、`/generate*`、固定 DAG 或任意执行工具；Worker 未直连业务库。

## 资料来源

面经主题清单来自公开整理，非原帖逐字内容：

- [牛客：近期开发 AI 面经](https://www.nowcoder.com/feed/main/detail/e3425d60dec24ee1bcf1dc44420b0101)
- [卡码笔记：Agent / 大模型大厂面试题汇总](https://notes.kamacoder.com/interview/llm/agent_interview.html)
- [知乎：小红书二面——Agent 框架选型与评价指标](https://zhuanlan.zhihu.com/p/2017373001881002562)
- [阿里云开发者社区：65 题 AI Agent 面试宝典](https://developer.aliyun.com/article/1739618)
- [GitHub：ai-agent-interview-guide](https://github.com/bcefghj/ai-agent-interview-guide)
