---
plan_id: autospec-v5-agent-execution
version: 1.0
status: archived
created_at: 2026-09-07
updated_at: 2026-09-07
product_baseline: autospec-v5:v5
predecessor: docs/archive/runtime-optimization-plan.md
---

# AutoSpec Agent Execution 落地计划

> 历史归档（2026-09-29）：保留当时的设计、状态和执行证据，不作为当前能力或待办清单。当前说明见 [文档索引](documentation-index.md)，后续工作见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

## 1. 结论与目标

AutoSpec V5 当前已经具备较完整的 Runtime / Control Plane：六节点动态 DAG、Redis Streams Worker、Execution Bundle、Tool Gateway、节点检索协议、引用门禁、缓存来源、预算、审计、恢复和交付门禁均已存在。下一阶段不再扩建同类治理能力，目标是把这些能力接入真实 Agent 决策路径，形成一条可复现、可评测、可回退的受限执行循环：

```text
Backend Goal
  -> Plan
  -> Select Action
  -> Tool Gateway（按需）
  -> Observation
  -> Generate Candidate
  -> Deterministic Validation
  -> Replan / Finish
```

本计划完成后，至少能够用一条脱敏的正式运行 Trace 证明 Backend Engineer 节点发生过真实的 Plan、Tool、Observation、Validation 和 Replan，而不是一次 `generate_json` 调用；同时能够用固定 Eval Set 比较 single-shot 与 Agent Loop 的质量、时延、Token 和成本。

## 2. 已核验基线

| 领域 | 当前事实 | 本轮判断 |
|---|---|---|
| 正式 Agent Handler | `runtime/production_handlers.py` 编译上下文后只调用一次 `run_agent_node` | 最大能力缺口 |
| Tool Runtime | Worker 已注册 `knowledge.search`、`artifact.get`、`contract.lookup`、`trace.query`、`bundle.verify` | 基础设施已完成 |
| Tool 使用 | `execute_current_tool` 仅有定义，生产 Agent 没有调用点 | 尚未产品化 |
| canonical ToolPolicy | `autospec-v5.workflow.json` 的六节点没有 `tool_policy` | 默认禁用，正式请求会被拒绝 |
| 节点检索 | 已实现 Retrieval Policy、Snapshot、Cache Provenance 和节点查询构造 | 协议与实现已具备 |
| canonical RetrievalPolicy | 六节点没有 `retrieval_policy`；运行创建阶段仍以原始 requirement 做一次公共检索 | 未形成基于最新上游 Artifact 的节点级检索闭环 |
| Trace | 已记录节点、模型调用和工具调用事实 | 缺少 Plan/Action/Observation/Validation/Replan 的步骤语义 |
| Eval | `evaluation/runner.py` 仍运行 deterministic fixture，关键 Tool/RAG/成本指标为空 | 可做回归，不能证明 Agent 质量收益 |
| Memory | `MemoryRecord`、`AgentState` 和 EvalCase 仍保留 skill/resume/question/interview 语义，且未进入正式生产链路 | 需要改成 Project Memory |
| 检索语料 | Java/Python 仍使用 RESUME、QUESTION、RUBRIC | 与 AutoSpec 产品语义不一致 |

上一轮计划继续作为 Runtime / Control Plane 的实施与证据记录；本计划接管后续 Agent execution 优先级，不重复 Citation、Cache、DLQ、权限或角色建设。

## 3. 范围与硬约束

### 3.1 本轮范围

1. 只在 `BackendEngineerAgent` 建立第一个 bounded agent loop。
2. 让该节点通过现有 Tool Gateway 真正调用受控工具。
3. 让节点级 Retrieval Policy 在最新上游 Artifact 已就绪时实际执行并冻结快照。
4. 建立 single-shot、loop、loop + tool、loop + tool + replan 四组可复现实验。
5. 质量门禁通过后，再重构 Project Memory、清理 Interview 语义并评估真实 Embedding/Reranker。

### 3.2 非目标

- 不增加第七个 Agent，不删除或合并现有六节点。
- 不把 Agent Loop 写进 Java 固定节点顺序；DAG 顺序仍只来自冻结的 WorkflowSpec。
- 不恢复 `/generate*`、LangGraph 固定编排或同步 Agent API 业务入口。
- 不重新实现 Tool Gateway、Citation Gate、Cache、DLQ、Trace 基础设施。
- 不开放任意 shell、任意 HTTP、Worker 数据库直连或写工具。
- 不一次性给 Product Manager、Architect、Frontend、Reviewer、Evaluator 全部启用循环。
- 不修改 V1–V94 历史 Flyway；任何数据库或种子变化只增加新迁移。
- 不用 fixture 结果冒充 live provider、Docker E2E 或生产容量数据。

### 3.3 设计原则

1. LLM 只能提出结构化 Action，不能决定权限、预算、重试或终止条件。
2. Loop、模型、工具和节点共同使用冻结的 deadline 与运行预算；每次调用前校验剩余额度。
3. 工具结果与检索内容一律视为不可信数据，必须保留来源并通过 Schema/引用校验。
4. 每个 Plan/Action/Observation/Validation/Replan 都必须可关联到 workflow run、node run、execution、bundle、模型调用和工具调用。
5. 已发布的 `autospec-v5:v5` 保持不变。新的 policy 先形成未激活候选；只有 Eval Gate 通过后，才按不可变发布流程确定并发布下一版本。
6. 任何质量收益都必须来自固定数据集上的对照实验，不能只展示一条挑选过的成功 Demo。

## 4. 路线图

状态统一使用 `planned` → `in_progress` → `blocked` / `done`。

| ID | 优先级 | 工作包 | 状态 | 核心交付物 | 依赖 |
|---|---|---|---|---|---|
| AEX-P0-01 | P0 | Loop 契约与逐步 Trace | planned | 冻结的 LoopPolicy、AgentTurn/Step Schema、停止原因、跨语言契约 | 无 |
| AEX-P0-02 | P0 | Backend Engineer bounded loop + 真 Tool Use | planned | 单节点 Plan/Action/Observation/Validation/Replan、现有 Gateway 调用 | AEX-P0-01 |
| AEX-P0-03 | P0 | 节点级 RAG 与候选 WorkflowSpec 激活 | planned | 最新上下文检索、Retrieval Snapshot、Tool/Retrieval Policy 候选 | AEX-P0-02 |
| AEX-P0-04 | P0 | Live Eval 与发布门禁 | planned | 四组消融实验、逐 Case 结果、质量/成本/时延结论 | AEX-P0-03 |
| AEX-P1-01 | P1 | Project Memory 与 Interview 语义清理 | planned | 项目记忆事实、受控召回、通用 Agent 状态与 Eval Schema | AEX-P0-04 |
| AEX-P1-02 | P1 | 真实 Embedding / Reranker 实验 | planned | 可替换 Provider、检索对照数据、启用或拒绝结论 | AEX-P0-04 |
| AEX-P1-03 | P1 | 容量、恢复与面试证据包 | planned | 10/20/50 并发、P50/P95、成本、恢复率、脱敏 Trace | AEX-P0-04 |

主路径为：

```text
AEX-P0-01 -> AEX-P0-02 -> AEX-P0-03 -> AEX-P0-04
                                              |
                         +--------------------+--------------------+
                         v                    v                    v
                    AEX-P1-01            AEX-P1-02            AEX-P1-03
```

## 5. P0 实施设计

### AEX-P0-01：Loop 契约与逐步 Trace

目标：在写循环前先冻结可执行边界，使历史运行能够解释当时允许多少步、多少次返工、使用何种策略以及为何停止。

实施项：

- 在 WorkflowSpec、ExecutionBundle、NodeCommand 和 contract hash 中增加可选 `agent_loop_policy`，默认 `enabled=false`，至少包含：
  - `version`
  - `enabled`
  - `strategy`，首版固定为 `plan-act-observe-validate-v1`
  - `max_steps=4`
  - `max_replans=2`
  - `no_progress_limit=1`
  - `validator_profile=backend-design-v1`
- 模型调用上限继续由 `model_policy.max_calls` 管理，工具调用上限继续由 `tool_policy.max_calls` 管理，节点总时间继续由 `timeout_ms` / `deadline_epoch_ms` 管理；不得在 LoopPolicy 中建立第二套预算事实源。
- 定义 provider-neutral 的结构化 `AgentTurn` 联合类型：
  - `PLAN`：目标、步骤和完成条件。
  - `TOOL_CALL`：工具名/version、参数、理由、预期证据。
  - `FINAL_CANDIDATE`：待验证的 BackendDesignArtifact。
  - `REPLAN`：针对确定性 issue code 的修订计划。
- 定义 `AgentStepRecord`：step、phase、status、reason_code、plan hash、observation hash、validation issue codes、model call ref、tool call ref、started/finished/duration。
- 扩展 Redis terminal event 和 Java 消费契约，使步骤记录进入 MySQL 事实源；优先复用现有模型/工具调用事实，只为步骤关系增加下一条 Flyway，不复制原始 Prompt 或完整工具结果。
- Workflow Trace API 返回按顺序排列的步骤摘要；原始敏感输入仍遵守现有授权和脱敏规则。
- 增加稳定停止原因：`COMPLETED`、`STEP_LIMIT`、`REPLAN_LIMIT`、`MODEL_BUDGET_EXHAUSTED`、`TOOL_BUDGET_EXHAUSTED`、`DEADLINE_EXCEEDED`、`PATH_OSCILLATION`、`VALIDATION_FAILED`。

验收标准：

- Java/Python 对同一 LoopPolicy、AgentTurn、AgentStepRecord golden case 解析一致。
- 未配置 LoopPolicy 的现有节点保持 single-shot 行为，历史 `autospec-v5:v5` 可继续执行与回放。
- `max_steps`、`max_replans`、模型调用数、工具调用数和 deadline 任一耗尽都会确定性停止。
- 重复 terminal event 不会生成重复 step fact；step sequence 与关联 call ref 唯一。
- contract hash 或 bundle 中的 LoopPolicy 被篡改时，模型调用前失败。

主要落点：

- `agent-engine/schemas/workflow_spec.py`
- `agent-engine/schemas/execution_bundle.py`
- `agent-engine/schemas/agent_loop.py`（新增；不让生产循环依赖旧 interview AgentState）
- `agent-engine/runtime/node_executor.py`
- `backend/.../workflow/spec`、`backend/.../workflow/runtime`
- `backend/src/main/resources/contracts/autospec.openapi.yaml`
- 下一条 Flyway、Workflow Trace DTO/Service、对应 Java/Python 测试

### AEX-P0-02：Backend Engineer bounded loop + 真 Tool Use

目标：仅将 Backend Engineer 从 single-shot 升级为有硬边界的 Agent execution，并复用现有 ToolHarness 与 Gateway。

建议候选配置：

```text
max_steps       = 4
max_replans     = 2
model max_calls = 4
tool max_calls  = 5
node timeout    = 120000 ms
tool timeout    = 3000 ms/call, 15000 ms total
side effects    = READ_ONLY, DETERMINISTIC
```

第一批 allowlist 只包含：

- `knowledge.search:v1`：查找项目内已授权知识与 Artifact 片段。
- `artifact.get:v1`：读取计划明确引用的 Artifact/version。
- `contract.lookup:v1`：核对正式 API/Schema/Workflow 契约。

`trace.query` 和 `bundle.verify` 暂不暴露给 Backend Engineer；它们继续供诊断或后续有数据证明的场景使用。

实施项：

- 将 Backend Engineer Handler 升级为 async loop handler；其他五个生产 Handler 不变。
- 初次模型调用输出结构化 Plan 或 ToolCall，不直接接受自由文本工具指令。
- 每个 ToolCall 只通过 `execute_current_tool` 执行；保留 Gateway 的项目权限、fencing token、bundle/policy hash、幂等、超时、重试和结果大小校验。
- Tool Observation 只把必要的结构化结果、citation/ref 和 hash 回填给下一步，不把任意 Tool 文本提升为系统指令。
- Candidate 进入确定性验证器；复用 Reviewer/Evaluator 已有规则，抽出 Backend 专用 profile，至少校验：
  - Pydantic/JSON Schema。
  - MUST requirement 到 API/数据的覆盖。
  - API method/path 唯一性与 request/response 完整性。
  - auth/required roles 与权限需求一致性。
  - table/API 的 requirement refs 有效。
- 验证失败只把稳定 issue code、证据 path 和 required change 送入 Replan；模型不能降低确定性问题严重级别。
- 同一 Plan hash + Action + 参数 hash + Observation hash 连续重复时判定 `PATH_OSCILLATION`，不继续消耗预算。
- 达到边界时，只有 Candidate 已通过 Schema 和硬规则才能成功结束；否则返回明确失败，不静默回退成一次生成。

验收标准：

- 正式测试 Trace 至少出现一次 `TOOL_CALL -> OBSERVATION -> VALIDATION`。
- 构造缺少 MUST API 覆盖的 Candidate 时，验证器触发 Replan，修订后通过或在预算内明确失败。
- 未声明工具、错误 version、非法参数、跨项目 Artifact、过期 fencing token、重复请求、超时和超大结果均有针对性测试。
- 重复 ToolCall 只能命中同一幂等事实，不产生第二次有效执行。
- fixture 模式可确定性覆盖成功、Replan、预算耗尽、工具失败和路径震荡。

### AEX-P0-03：节点级 RAG 与候选 WorkflowSpec 激活

目标：不再让所有节点共享“运行创建时仅按原始 requirement 检索”的结果；节点进入 READY/dispatch 时，使用已经冻结的上游 Artifact 与 rework directive 构造查询。

实施项：

- 将节点检索组装移动或扩展到控制面节点调度边界，在拥有项目权限事实的 Spring Boot 服务执行。
- 每个节点基于当时可见的可信输入生成查询：
  - Product Manager：requirement 与业务约束。
  - Architect：requirement + PRD。
  - Backend Engineer：requirement + PRD + architecture + rework directive。
  - Frontend Engineer：requirement + PRD + architecture + backend design + rework directive。
  - Reviewer/Evaluator：全部待审 Artifact、issue/trace 摘要和交付约束。
- 冻结 query hash、policy hash、corpus epoch、候选/命中、Artifact/version/chunk hash、访问过滤摘要和 snapshot hash；普通回放复用原快照，显式 refresh 才产生新运行。
- 在候选 WorkflowSpec 中给六节点声明 `retrieval_policy`；先使用现有 deterministic embedding/reranker，避免在同一实验中同时改变循环与检索模型。
- 首轮节点策略只允许 `PROJECT_ARTIFACT`，不继续扩散 RESUME/QUESTION/RUBRIC 等遗留 corpus。
- 仅给 Backend Engineer 声明 AEX-P0-02 的 `agent_loop_policy` 和 `tool_policy`，其他节点的 ToolPolicy 保持 disabled。
- 不修改已经发布的 `autospec-v5:v5`、V94 或更早迁移。生成一个未激活的下一版本候选、同步 canonical contract、Execution Bundle 和跨语言校验；正式版本名与激活由 AEX-P0-04 发布门禁决定。
- 候选失败可直接切回当前 active `autospec-v5:v5`；历史 run、bundle 和 snapshot 不变。

验收标准：

- Backend 节点查询实际包含 PRD、architecture 和 rework directive 的 hash/证据，而非只包含原始 requirement。
- 跨项目召回、无权 Artifact 召回和默认过期版本召回均为 0。
- 同一冻结输入可重放相同 Retrieval Snapshot；refresh 明确产生新 snapshot/hash。
- canonical contract、不可变候选 seed、Java parser 和 Python Handler 能力通过 `scripts/verify_workflow_contract.py`。
- 候选未通过 Eval Gate 前不会替换 active workflow。

### AEX-P0-04：Live Eval 与发布门禁

目标：用对照实验回答 Agent Loop 与真 Tool Use 是否提高 AutoSpec 交付质量，以及收益是否值得新增的时延与成本。

实验矩阵：

| 组别 | 执行方式 | 用途 |
|---|---|---|
| A | 当前 single-shot | 固定 Baseline |
| B | Agent Loop，Tool disabled | 分离循环/验证收益 |
| C | Agent Loop + Tool | 衡量工具选择与证据收益 |
| D | Agent Loop + Tool + Replan | 完整候选 |

实施项：

- 保留 `run_fixture_baseline` 作为快速回归，新增明确命名的 live/ablation runner，不用 fixture Provider 填充 live 指标。
- 新增独立的 `AutoSpecEvalCase` 契约，不复用或继续扩展带 resume/question/rubric 字段的旧 EvalCase；旧类型在所有消费者迁移前只做兼容保留。
- 建立版本化 AutoSpec Eval Set，首版至少覆盖：CRUD、审批工作流、权限、多实体关联、外部集成、模糊需求、冲突约束、返工和跨 Artifact 一致性；每个 Case 带 MUST requirements、期望 API/数据/UI 证据、允许/禁止工具和可判定失败条件。
- 快速节点实验直接运行 NodeExecutor；完整链路实验只通过正式 `POST /api/workflow-runs` 创建并从 Trace/Artifact API 采集结果，不增加同步 `/generate*`。
- 每次 EvalRun 固定 dataset、code commit、workflow/bundle、prompt/schema、provider/model、retriever、tool policy、随机参数和预算版本。
- 输出逐 Case 失败原因，至少统计：
  - gate pass rate、MUST trace coverage、blocking issue count、rework rate。
  - tool selection precision/recall、argument valid rate、tool success rate、unauthorized request count。
  - plan completion、replan rate、path oscillation、steps/run。
  - retrieval Recall@K、MRR、nDCG、citation validity。
  - node/end-to-end P50/P95、tokens/run、cost/run、failure/recovery rate。
- 对模型判分先做人工双评校准；发布门禁以确定性 Gate、追踪矩阵和预先声明的指标为主，不以单个 LLM-as-judge 分数决定。
- 原始 live 输出和可能包含用户内容的日志不提交；仓库只保存脱敏 Case、汇总、配置 hash 和可复现命令。

候选发布门禁：

- Schema validity、非法工具执行、跨项目召回、无效 citation 交付、重复有效副作用均为零容忍。
- D 组不能降低 MUST trace coverage；在固定 Eval Set 上，gate pass rate 相对 A 组提升至少 8 个百分点，或每 Case blocking issue 中位数下降至少 20%。
- Tool argument valid rate 不低于 95%，所有预算/终止边界测试通过。
- D 组 P95、Token 和 cost 相对 A 组的增幅必须落在实验前声明的预算内；若质量达标但成本超限，保留候选不激活并缩减步骤/工具范围。
- 任何门禁失败都保持 `autospec-v5:v5` 为 active，不用主观 Demo 覆盖失败数据。

验收标准：

- 四组使用同一批 Case 和同一模型配置，可按 run ID 重现与比较。
- `tool_selection_accuracy`、`tool_argument_valid_rate`、RAG、Token、cost、recovery 不再由占位 `None/0` 冒充实测值；未执行项明确标注。
- 至少保存一条成功 Replan Trace 和一条预算/终止失败 Trace 的脱敏样例。
- 发布决策记录为 `PROMOTE`、`REVISE` 或 `REJECT`，并引用完整 EvalRun 与 bundle hash。

## 6. P1 实施设计

### AEX-P1-01：Project Memory 与 Interview 语义清理

目标：把未进入正式生产路径的面试画像 Memory 改成 AutoSpec 项目长期事实，并通过受控检索按需使用。

建议 Schema：

```text
ProjectMemoryRecord
  project_id
  memory_type          DECISION | CONSTRAINT | CONVENTION |
                       REJECTED_ALTERNATIVE | REWORK_LESSON
  subject
  content
  evidence_refs
  origin_run_id
  origin_artifact_id / origin_artifact_version
  supersedes_id
  confidence
  status               ACTIVE | SUPERSEDED | EXPIRED
  created_by
  created_at / expires_at
  version
```

实施项：

- MySQL 作为 Project Memory 事实源；模型不能直接写入长期记忆。
- 只允许从人工批准 Artifact、确定性 Reviewer/Evaluator 结果或显式用户决策提议 Memory；写入前校验证据、权限、版本和幂等。
- 召回时过滤 project、actor scope、status、expiry 和 supersession，并冻结 memory snapshot/hash。
- 优先通过现有 `knowledge.search` 的明确 memory corpus/filter 暴露；只有对照实验证明独立工具能改善选择准确率时，才增加新的 Gateway 工具。
- 在 AEX-P0-01/P0-04 的通用 `AgentLoopState`、`AutoSpecEvalCase` 已替代全部生产消费者后，弃用并删除旧 `AgentState`/EvalCase 的 `resume_profile`、`target_position`、`skill_graph`、`interview_plan`、`qa_history`、`weakness_profile`、question/rubric 字段；不原地改变仍被兼容路径读取的数据含义。
- 将语料类型收敛为 `PROJECT_ARTIFACT`、`REQUIREMENT`、`DOMAIN_KNOWLEDGE`、`DESIGN_REFERENCE`、`API_SPEC`、`CODE_REFERENCE`；保留历史数据，新增迁移与兼容读取，不改写 V87。

验收标准：

- 跨项目记忆召回率为 0；过期或 superseded 记录默认不进入上下文。
- 每条使用中的 Memory 都能定位到原始证据和版本；删除/变更来源后可确定性失效。
- 冲突记忆不覆盖历史事实，而是通过 supersedes 形成可审计链。
- 正式生产代码、公开 OpenAPI 和 Eval Set 不再出现 interview/resume/question bank 语义。

### AEX-P1-02：真实 Embedding / Reranker 实验

目标：在 Loop/RAG 基线稳定后，判断真实向量模型与 reranker 是否带来可量化收益。

实施项：

- 保持 RetrievalPolicy 的 provider/version 可冻结；密钥只从安全环境注入。
- 在同一 golden retrieval set 上比较当前 `hashing-ngram-v1 + deterministic-rerank-v1` 与候选实现。
- 分别报告召回、排序、空召回、跨语言、重复 chunk、时延与单位查询成本。
- 只有 Recall@5/MRR/nDCG 或下游 Gate 有显著提升且成本、时延可接受时才启用；否则保留 deterministic 基线。

验收标准：

- 检索模型变化不会改变权限、版本过滤和 Citation Gate。
- 候选可按 policy 关闭并回退；历史 snapshot 继续引用原 provider/version。
- 报告包含逐 Case 变化，不只给平均分。

### AEX-P1-03：容量、恢复与面试证据包

目标：补齐上一轮容量报告中明确未执行的外部证据，并形成能解释架构取舍的脱敏材料。

实施项：

- 在隔离环境运行 10/20/50 并发 workflow，记录 queue/node/tool/end-to-end P50/P95/P99、成功率、Token/run、cost/run。
- 注入 Worker 崩溃、Redis 短断、Tool timeout、Provider 限流和重复 terminal event，统计 XAUTOCLAIM recovery、重复有效事实和恢复时间。
- 保存一条完整成功 Trace：Plan → knowledge.search/contract.lookup → Observation → Validation fail → Replan → artifact.get → Validation pass → Finish。
- 保存一条失败 Trace：预算耗尽或路径震荡后确定性停止。
- 更新 `docs/archive/p1-capacity-and-recovery-report.md`，未实际执行的维度继续明确写“未执行”。

验收标准：

- 所有数字可关联 commit、bundle hash、环境、数据集和原始结果位置。
- duplicate terminal event、跨项目读取、未授权工具执行均为 0。
- 面试证据能够分别解释 Harness vs Orchestrator、ReAct/Plan、Tool Selection、Path Oscillation、Memory/Context、RAG 与 Eval。

## 7. 测试与验证策略

日常只运行当前工作包直接相关的测试。以下变更必须执行跨模块验证：WorkflowSpec、ExecutionBundle、Redis command/event、OpenAPI、Flyway、AgentStep/Memory Schema 或 active workflow 发布。

建议按里程碑执行：

```powershell
Set-Location 'D:\@Java\MetaGPT\agent-engine'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q `
  tests/test_node_executor.py `
  tests/test_tool_gateway.py `
  tests/test_production_handlers.py `
  tests/test_retrieval_policy.py `
  tests/test_p0_runtime.py

Set-Location 'D:\@Java\MetaGPT\backend'
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' -Dtest='*Workflow*Test,*ToolGateway*Test,*Knowledge*Test' test

Set-Location 'D:\@Java\MetaGPT'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' scripts/verify_workflow_contract.py
```

发布候选前执行 AGENTS.md 规定的完整三端回归、base/monitoring Compose 校验和 live eval；没有 live Key 时不得切换 `AGENT_MODEL_MODE=live`，只完成 fixture 与契约门禁并明确记录边界。

## 8. 发布、灰度与回退

1. AEX-P0-01/P0-02 先在 fixture 和显式 NodeCommand policy 下验证，不改变 active workflow。
2. AEX-P0-03 生成未激活的不可变 WorkflowSpec 候选；只在 Backend Engineer 开 Loop/Tool。
3. AEX-P0-04 通过后才发布并灰度到内部测试项目；六节点结构和正式入口不变。
4. 灰度期重点观察 gate pass、rework、tool error、path oscillation、P95、Token 和 cost。
5. 异常时把新 run 切回 `autospec-v5:v5`；不修改或删除候选版本、历史 bundle、Artifact 和调用事实。
6. Project Memory 与真实检索模型分别灰度，禁止与 Loop 首次上线捆绑，确保实验可归因。

## 9. 第一批可执行任务

1. `AEX-P0-01-T01`：提交 `agent_loop_policy`、`AgentTurn`、`AgentStepRecord` JSON/Pydantic/Java 契约及 golden cases，默认关闭。
2. `AEX-P0-01-T02`：扩展 terminal event、调用消费和 Workflow Trace，使步骤关系可持久化、幂等和查询。
3. `AEX-P0-02-T01`：抽取 Backend deterministic validator profile，先用错误 Candidate 验证 issue code 稳定性。
4. `AEX-P0-02-T02`：实现 Backend async loop，先跑 loop + validator，不启用工具。
5. `AEX-P0-02-T03`：接入 `execute_current_tool`，只允许 knowledge/artifact/contract 三个工具并覆盖失败边界。
6. `AEX-P0-03-T01`：把检索执行移到节点 ready/dispatch 上下文并冻结 snapshot。
7. `AEX-P0-03-T02`：生成未激活候选 WorkflowSpec 与新 Flyway seed，跑契约同步。
8. `AEX-P0-04-T01`：先冻结 Eval Set 与 A 组结果，再运行 B/C/D，防止按候选输出反向修改评测集。

## 10. Definition of Done

本计划的 P0 不是以“代码合并”结束，而是必须同时满足：

- 一条正式 Backend Engineer Trace 可证明 Plan、真实 Tool、Observation、确定性 Validation、Replan 和 Finish。
- 所有步骤、模型、工具、检索与 Artifact 都能通过 execution/bundle/hash 串联。
- Agent 在步数、返工、模型、工具、deadline 或路径震荡边界上必定停止。
- 四组 Eval 可复现，指标没有伪造或用 fixture 填充 live 空缺。
- 新候选满足质量、权限、幂等和成本门禁后才允许成为 active；失败时现有 `autospec-v5:v5` 保持可用。
- 没有引入新 Agent、旧 `/generate*`、固定 Java/Python DAG、任意执行工具或新的多事实源。
