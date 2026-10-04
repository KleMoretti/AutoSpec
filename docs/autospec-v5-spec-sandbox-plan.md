---
plan_id: autospec-spec-sandbox
version: 1.3
status: in_progress
created_at: 2026-09-29
updated_at: 2026-10-04
product_baseline: autospec-v5:pm-schema-repair-v12
database_baseline: V1
predecessor: docs/archive/agent-execution-plan.md
current_focus: implementation-1-2-3
testing_priority: deferred-by-user
---

# AutoSpec 当前实现总计划：可修复、会取证、能澄清的 Agent 工作流

> 2026-10-02 用户调整优先级：当前只推进三项实现——① Spec Sandbox 自我修复闭环；② 按需工具、知识与项目记忆；③ 需求澄清、用户确认与需求基线。测试建设、大规模评测、消融和指标报告暂不安排；不以补齐这些工作作为当前实现的前置条件。保留原有运行时校验、权限、预算和交付门禁，它们属于产品实现。
>
> 本文定义范围与顺序。[P0/P1 手册](p0-p1-execution-plan.md)负责 SB-01～SB-04；[P2–P4 手册](p2-p4-execution-plan.md)负责 KB-01～KB-04、CL-01～CL-04。文件名沿用用户指定路径，当前任务编号优先于旧阶段编号。文末折叠区原文为历史记录，不再作为当前待办。

<!-- CURRENT_IMPLEMENTATION_PLAN_BEGIN -->

## 2026-10-04 复核结论与边界

本次依据用户提供的只读静态复核更新计划；复核没有运行测试、容器或模型，
其代码缺口不能写成已复现的运行结果。对照 SB/KB/CL 共 12 项任务，当前完成了
Sandbox 的前半：SB-01 完成，SB-02 基本实现但仍有偏差，SB-03 仅本节点修复可用，
SB-04 已有校验零件但尚未绑定当前产物。KB 四项已有公共模块而未接入显式候选，
CL 四项尚未开始。完整六节点 live 仍未通过。

`spec-sandbox-explicit-v2-live-loop-v12` 的 Architect v6 仍漏接
`shared_contract_required`，修复及必要行为回归完成前不启动该候选的付费 live。
它与产品默认 `pm-schema-repair-v12` 是两个不同版本标识。

## 当前目标

做成一个能澄清用户意图、按需查阅项目知识、通过可执行规格检查发现问题并受控修复的软件需求平台。六个 Agent 的职责、控制面/Worker 分工和正式入口保持一致；主要工作是把现有能力接成一条完整产品流程。

产品主流程：

```text
POST /api/workflow-runs
  → Product Manager：发现歧义 → 请求用户回答/确认假设 → 生成 PRD
  → 用户批准 PRD，同时冻结需求基线
  → Architect：共享契约
  → Backend ∥ Frontend：使用可信项目上下文生成显式规格
       Backend 按需查知识/读 Artifact/查契约
       Backend 调用 Sandbox → 本节点错误定向修复 → 再验证
  → Reviewer：汇合检查 FULL/L2，跨节点错误交回责任节点
  → Evaluator：需求覆盖与可信验证事实
  → 版本化交付
```

图示是产品行为说明，实际节点顺序、依赖和合法返工目标仍必须来自冻结 WorkflowSpec。需求澄清是 Product Manager 内部阶段，不新增第七个 Agent；用户等待不占住 Worker、模型调用或数据库事务。

## 当前代码基线与复用边界

以用户提供的 10-04 静态复核为当前增量状态；[bc00b0d6 复核](archive/evidence/spec-sandbox-reassessment-bc00b0d6-2026-10-02.md)仅保留起点背景：

- 六节点编排、Redis Worker、Outbox、审批、返工、预算、可靠性与可观测已有实现，继续复用。
- SB-01 的显式 v2 Schema、适配、候选与 Java/Python 注册已接通；后续 live-loop v12 的 Architect v6 注册存在，但执行分支仍按写死版本集合选择旧输出模型。按输出模型判定共享契约，并补行为回归后才复验。
- Sandbox 已有先鉴权、流式限长、30s 上限、共享 deadline、进程组回收、随机 schema 清理及 Java 剩余超时联动。SB-02 仍需修正混合错误的 ERROR 优先、未知数据库异常归 ERROR，以及 DDL/TS 诊断到源 Artifact 的映射。
- Backend 本节点修复与停止策略已有；Reviewer FULL 验证失败仍直接抛异常，fixture 注入 routes 只证明协调器可执行。真实 issue 的责任/来源、RepairDirective、Reviewer FAILED→routes 和 Backend→Architect 返工事件/合法边尚未接通。
- 验证事实已绑定 execution、fencing、policy、digest、scope、版本和有效期；SB-04 尚未绑定当前 Artifact id/version/hash。DeliveryGate 从冻结 policy 读取 source_digest 的条件不足以证明当前产物一致，必须按实际来源集合重算并失效旧报告。
- native tool-call 适配、`knowledge.search`、`artifact.get`、`contract.lookup`、上传语料与批准记忆已有实现。当前候选主要只开放 `spec.verify`，尚未把按需取证、节点查询和相关记忆连成主路径。
- 项目级初始检索已经存在；缺节点 `retrieval_policy` 不能简单等同于“没有 RAG”。当前目标是节点根据问题和最新上游输入取证，而非再建一套检索系统。
- PR #13 合并后的 master CI 已成功，包含 verifier probe。它是既有事实，本轮不重复把“取一个绿色 CI”列为功能任务。
- 当前新库种子仍是 `pm-schema-repair-v12`；现有迁移为 V1、V2、V5，下一新增迁移从 V6 起，实施前再核对实际最大版本。不恢复旧 V104～V117，不改已应用迁移。

## 范围与不做项

| 当前实现 | 暂不推进 |
| --- | --- |
| 显式规格、L1/L2 错误分流、局部/跨节点修复、可信交付 | 新测试集、全量回归专项、故障注入专项、消融、质量/性能数字与报告 |
| 受控只读工具、节点知识查询、批准记忆、冲突提示、native 调用接入 | MCP/A2A、新向量数据库、新 Agent 框架、让六个节点全部启用循环 |
| PM 澄清、持久化等待、用户回答/接受假设、需求基线冻结 | 需求变更影响分析与组件级增量重生成（此前建议第 4 项） |
| 为三项功能所必需的表单、状态和已有 Trace 展示 | 独立动态 DAG 项目、评测看板扩建、截图/GIF、面经卡片重写（此前建议第 5 项） |

暂缓测试不等于删除已有测试，也不等于绕过编译、Schema、授权、幂等、预算或运行时检查。新计划只用代码接线、状态/数据流和产品行为定义完成条件，不提前声称可靠性或质量收益已经实测。

## 三项能力及边界

### ① Spec Sandbox 自我修复

最终结果：实际所选候选产出显式规格；验证问题带来源和责任节点；可修复问题得到受限重试，环境错误终止；交付只接受当前产物的可信 FULL/L2 事实。

- 显式 Schema、Handler、Prompt、context policy、compiler/verifier 与候选一起冻结。不能只把 `verifier_version` 改成 v2 而让上游继续输出旧结构。
- Backend 只校验自身和上游契约，不等待并行 Frontend；Reviewer 汇合后检查前后端消费一致性。
- `FAILED` 表示模型可以修复的规格缺陷；`ERROR` 表示连接/鉴权/超时/清理等运行问题。未知失败默认 ERROR，禁止瞎重试。
- 修复指令携带 issue code、Artifact 路径、稳定 ID、责任节点、允许修改范围、必须保留的契约和候选 hash。
- 若根因属于 Architect，Backend 不擅自改 Shared Contract。通过冻结规格允许的返工控制边交给原有 rework coordinator；没有合法路由就明确阻断。
- 控制面校验版本、来源、scope、要求层级、有效期及可信台账。模型不得声明自己已经通过验证。

### ② 工具、知识与项目记忆

最终结果：Agent 可以说明当前缺少什么信息，选择被允许的工具取得证据，再据此生成或修复；检索和记忆有来源、版本、权限与预算。

- Backend 先开放 `knowledge.search`、`artifact.get`、`contract.lookup`，保留强制执行的 `spec.verify`。不为凑工具数量增加新目录。
- native 与 JSON 协议通过冻结策略选择，复用已有适配器；不得静默回退，不能因 provider 不支持就放开白名单。
- 节点初始检索围绕当前任务与上游；修复时围绕 issue 构造查询。初始检索和工具搜索使用同一服务、ACL、索引版本与引用格式。
- 记忆先按批准状态/替代链/有效期筛选，再做任务相关性排序和 token 截断；排序首版复用检索或确定性评分，不引入新模型服务。
- 用户本次明确确认的需求与历史记忆有冲突时生成 `ContextConflict`，记录来源和冲突原因。已批准旧记忆不是永远优先，检索文档也不能自动覆盖本次需求。

### ③ 需求澄清、确认和冻结

最终结果：模糊输入不再直接变成一套默认业务假设；用户能回答关键问题或明确接受假设，后续节点统一使用冻结需求基线。

- PM 输出结构化缺失信息、冲突、假设和问题；只询问影响角色权限、主业务流程、数据边界或交付范围的问题。信息充分时直接进入 PRD。
- 一轮最多 5 个问题、默认最多 2 轮自动澄清，具体数值写入新候选策略。达到轮次上限仍有必答问题就停在待用户处理，不捏造回答。
- 复用审批基础设施，以明确的澄清类型区分“提交回答后重跑 PM”和“批准 PRD 后放行 Architect”。两种操作不得混用。
- `WAITING_APPROVAL` 承载等待，记录 `CLARIFICATION` 或 PRD 审批类型；实现版本化的输入请求事件、状态分支和恢复输入，不把问题列表当成功 PRD。
- 最终用户批准 PRD 时，原输入、有效问答、接受的假设、已解决冲突、约束与范围形成不可变 RequirementBaseline。后续每个 Artifact 和报告关联 baseline ID/version/hash。
- 基线冻结后要改需求，首版创建新的运行/基线；不实现组件级增量生成，不篡改已在运行的基线。

## 当前执行顺序与状态

以下状态只描述本轮增量。`planned` 不否定已存在的公共模块；必须完成本轮接线后才更新。

| 顺序 | 任务 | 主交付物 | 依赖 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | SB-01 | 新显式规格候选与真实 Handler/Prompt/Schema 接线 | 当前基线 | done（当前显式候选，旧版本兼容保留） |
| 2 | SB-02 | L2 失败分类、限流读取、共享 deadline/取消清理 | SB-01 | in_progress（基本实现；错误优先级、未知异常和源映射待补） |
| 3 | SB-03 | 有界局部修复与跨节点责任返工 | SB-01、SB-02 | in_progress（本节点修复已有；真实责任路由未接通） |
| 4 | SB-04 | 可信验证事实、版本化交付和候选收口 | SB-03 | in_progress（已有事实/门禁零件；当前 Artifact 绑定与基线预留未接通） |
| 5 | KB-01 | 现有只读工具进入候选，native 决策受预算治理 | SB-04 | done（未激活 `spec-sandbox-kb-v1` 候选；工具白名单、强制验证预算和可选 ACTION 已接通，未执行 live） |
| 6 | KB-02 | 当前任务/问题驱动的节点 RAG 与统一引用 | KB-01 | done（候选策略、节点/issue query 和统一 retrieval snapshot 已接通；未宣称质量收益） |
| 7 | KB-03 | 相关批准记忆、冲突对象和消解规则 | KB-02 | done（批准事实保护、相关性 bounded recall 和 conflict context 已接通） |
| 8 | KB-04 | 工具观察进入后续决策和现有 Trace | KB-03 | done（ToolObservation、source refs/result hash 和 ToolCall reason 已进入 loop/Trace，保持旧字段兼容） |
| 9 | CL-01 | PM 澄清协议、字段和策略 | KB-04 | done（`clarification-v1`、互斥 PM 结果封套、PM v3 Handler/Prompt 已接通；定向 15 passed） |
| 10 | CL-02 | 控制面持久化等待、回答和恢复 PM | CL-01 | done（V6 澄清表、`NODE_INPUT_REQUIRED`、WAITING_APPROVAL、锁版本/幂等恢复新 PM revision；Java 定向回归通过） |
| 11 | CL-03 | 用户澄清表单、假设确认与 PRD 审批 | CL-02 | done（工作台澄清面板、选项/自由文本、假设/冲突展示、只读态和取消操作已接通；Vitest 29 passed，构建通过） |
| 12 | CL-04 | 冻结需求基线、下游统一消费与最终候选 | CL-03 | done（V7 append-only 基线、PRD 审批冻结、下游输入/provenance/Delivery Gate绑定和未激活 `spec-sandbox-kb-cl-v1` 已接通；定向 Java/合同校验通过） |

当前实现目标已收口：**SB/KB/CL 当前代码链路完成，候选仍未激活**。修复只补必要行为与高风险边界回归，
不新增测试集/消融专项。完整 live、576 CNY 批量评测均不作为本地实现的前置。
任务细节按两份手册的“当前实现”章节执行，三份当前状态表同步更新。

## 版本、发布与资料治理

- 创建新的未激活候选，不改写 v12、已发布 spec-sandbox 或旧消融快照。既有候选状态/hash先查代码和数据库事实；发布版本与 Flyway 版本分开。
- 执行三项功能需要的新增字段、事件、审批模式和 Schema 必须同步 Java/Python/TypeScript/OpenAPI 与新迁移。旧运行仍按旧协议解析。
- 本计划是代码实现授权范围说明，不包含付费批量调用、默认切换、commit 或 push 授权。
- 真实 Key 只保存在本地秘密配置；provider 不可用时明确报告配置问题，不假称 live 完成。
- 前端新增澄清卡片和已有 Trace 字段展示属于本轮交付；新 DAG 看板和独立评测产品不属于本轮。
- JSON 已收敛：当前契约保留唯一副本，历史契约/实验位于各自 `archive/`；见 [收敛说明](archive/json-consolidation.md)。旧全量副本问题已做目录治理，但不表示业务缺口已修复。共享 `structured_output.py` 的 explicit-v2 专用修复措辞仍需按协议/Schema 版本分派，避免改变历史行为。
- 用户复核中“3 个提交未推送”和根 `package*.json` 用途属于当时仓库状态，执行前重新核对；本文更新不授权 push、删除文件或外部付费调用。

## 当前完成定义与面试主题

| 能力 | 实现完成条件 | 可解释的面试主题 |
| --- | --- | --- |
| Sandbox 修复 | 候选显式输入→L1/L2→责任路由/有限修复→可信交付的数据与控制链路接通 | ReAct、环境反馈、自我修正、幻觉控制、多 Agent 协作、停止策略 |
| 工具与知识 | 模型按需选择允许工具；节点检索/批准记忆影响生成；引用和冲突可追溯 | Function Calling、RAG、上下文工程、记忆、工具治理与注入隔离 |
| 需求澄清 | 信息不足→持久化等待→用户输入→恢复 PM→批准冻结→下游同一基线 | Human-in-the-loop、不确定性处理、工作流恢复、需求分析 |

不以“所有面经关键词都有模块”定义完整，也不从代码接通直接推出质量提升数字。本轮结束可以描述实际架构与产品行为；质量收益、规模和可靠性数字继续以单独证据为准。

## 实施交接规则

每完成一个编号，更新此表和对应手册，写清：实际修改入口、调用链变化、状态/数据变化、未完成内容、下一编号。只记录实际完成内容。测试/评测暂缓状态保留，不擅自将旧 P2 付费计划恢复为当前任务。

## 2026-10-04 外部验收状态

- 真实 `.env` 冷启动、远端五项 Sandbox CI 和 fixture 注入的 Reviewer→Backend 返工样本已记录；证据见 [`p1-live-self-repair-2026-10-04.md`](archive/evidence/p1-live-self-repair-2026-10-04.md)。fixture 样本证明协调器执行，不能关闭真实 verifier issue 的 SB-03 责任路由缺口。
- DeepSeek Flash live 已完成真实小额诊断，Backend 有界自我修复路径已在真实运行中完成 Replan/验证，但完整六节点 live 仍被结构化输出或 Evaluator 质量门禁阻断，不能标记为完整 live 通过。
- P2 完整矩阵未启动、结果仍为 `NOT_EVALUATED`；按 10-02 调整，576 CNY 批量评测、消融和指标报告是当前范围外的暂缓项，不能列为 SB/KB/CL 完成的阻塞。默认版本、历史运行和数据库基线保持不变。

<!-- CURRENT_IMPLEMENTATION_PLAN_END -->

## 历史计划与执行记录（保留原文，不作为当前任务）

以下内容记录本次调整前的计划和执行状态，包括已有未提交修改。其测试、评测、预算和旧优先级只作历史参考；有冲突时以文件上方 CURRENT_IMPLEMENTATION_PLAN 段为准。

<details>
<summary>展开原阶段计划与执行记录</summary>

# AutoSpec 面试就绪与特色功能计划（Spec Sandbox）

> 2026-10-02 审查更新：PR #12 已合并，master 普通 CI 已全绿；生产显式契约、独立前端消费检查、L2 重复执行和正式 Replan 证据已分批补齐，Sandbox 的正式负例、终止 Trace、真实 `.env` 冷启动和完整扩展 CI 仍未验收。本文保留分阶段状态与完成边界。审查方法、Git/CI 更正及覆盖边界见 [10-02 复核记录](archive/evidence/spec-sandbox-review-2026-10-02.md)。

> 本文件是当前任务总计划，具体入口和历史执行过程见 [P0/P1 执行手册](p0-p1-execution-plan.md)、[P2–P4 执行手册](p2-p4-execution-plan.md)。两份手册属于同一任务；与本次审查冲突的旧 `done` 或“未 push / CI 待验证”判断，以本次状态修订为准。其他文档、样例与证据放在 [archive/](archive/README.md)，只作参考，不生成另一条实施路线。文件名按用户指定保留，产品名称统一为 AutoSpec。

> 2026-10-02 P2/P3 续作已完成新候选冻结、完整 fixture development/holdout、Docker Sandbox probe、native tool-call 协议对照和本地 embedding 对照；P2-E 完整 live 批次因安全执行层未接受 `576 CNY` 批量外部调用而保持 `blocked/NOT_EVALUATED`。详见 [P2/P3 续作证据](archive/evidence/p2-p3-2026-10-02.md)；P4 不在本轮实施范围。

> 数据库已于 2026-09-30 经授权收敛为 V1，后续迁移只增不改，从实际最大版本 + 1 分配；当前源码已有 V2。保留运行的 WorkflowSpec / Prompt / Schema / Handler 冻结版本不变，不能恢复 V104–V117 或重写 V1。详见 [基线收敛记录](archive/evidence/baseline-consolidation-2026-09-30.md)。新数据库的产品基线为 `pm-schema-repair-v12`；旧 `v5-parallel` 仅为历史兼容输入。

## 1. 结论

AutoSpec 的六节点编排、可靠性、审批、成本与可观测底座已经落地，Spec Sandbox 也有真实的编译器、L1 和隔离执行骨架；**特色功能整体仍为 `in_progress`，不能宣称已完成可执行验证与自我修复闭环。**

- v12 的 Backend 已开启有界循环（`max_steps=7`、`max_replans=1`、`backend-design-v2`），Backend 和 Reviewer 已强制调用 verifier；新 `spec-sandbox` 候选将其冻结为 Backend `BACKEND/L2`、Reviewer `FULL/L2`。旧 v12 的 L1 运行与候选 L2 证据仍按版本区分。
- 主要剩余缺口是正式预算/震荡终止 Trace、正式证据负例、真实 `.env` 冷启动和远端 Sandbox CI；正式 fixture 的失败反馈→Replan→修复 Trace及隔离 hard-limit 本地探针已经补齐。没有完整 D 对 A live 对照。
- [PR #12](https://github.com/KleMoretti/AutoSpec/pull/12) 已于北京时间 2026-10-01 22:01 合并；master `944f1bde` 的 [CI run 36873079480](https://github.com/KleMoretti/AutoSpec/actions/runs/36873079480) 四项全绿。该 CI 没有构建 verifier 镜像、执行 L2 或运行整栈工作流，不能关闭 Sandbox 缺口。
- 当前优先级是**修复验证器和输入事实→建立正式 fixture 闭环与 L2 增量证据→重新冻结 live 实验**。MCP、变更影响分析等扩展继续按范围取舍，不抢占这条主线。

目标仍为：每个高频面经主题有可核验的“代码 + 证据 + 讲法”，并交付一个可演示、可度量、能明确说明局限的 Spec Sandbox。

## 2. 当前差距与证据边界（2026-10-02）

图例：**源码**＝本次读代码/配置确认；**推演**＝根据源码推断，尚未真实执行；**记录**＝引用既有运行证据，本次未复跑；**远端**＝本次 GitHub API 确认。测试套件数量不是本次执行结果。

### 2.1 Sandbox 必修项

| # | 依据 | 当前事实与缺口 | 修复归属 |
| --- | --- | --- | --- |
| S1 | 源码 / 记录 | 新增 [spec-sandbox](../agent-engine/contracts/autospec-spec-sandbox.workflow.json) 候选，Backend `BACKEND/L2`、Reviewer `FULL/L2`；独立 project 已完成三领域重复/并发 L2，正式 run 2 通过六节点/L2/导出，run 4 又完成故意缺陷→Replan→修复 Trace | SIG-P1-04 |
| S2 | 记录 / 部分完成 | `a1b2187` 改为每次随机 `autospec_verify_<hex>` schema 并在 `finally` 执行 `DROP DATABASE`；`66d410a` 独立 project 三领域各两次、三次并发均通过且无残留，`9b3d282` 增加运行期清理/网络/请求上限探针。真实 `.env` 冷启动和主动资源故障注入仍待验收 | SIG-P1-03 |
| S3 | 记录 / 部分完成 | `tests/test_spec_verifier.py` 和 service 回归覆盖随机 schema、部分失败清理、清理异常、请求/响应/超时边界；真实 6 次重复、3 条并发及残留查询、隔离探针均有记录。远端 Sandbox CI 尚待实际运行 | SIG-P1-03 |
| S4 | 记录 / 正式验收 | `d1e98b2` 新增未激活 `spec-sandbox-explicit-v2`；run 9/run 10 真实选择 Backend v7、Frontend v4、Reviewer v5、Evaluator v4，显式 PK/FK/类型/参数位置/独立消费映射保真；旧推断适配仅保留兼容版本 | SIG-P1-00 |
| S5 | 记录 / 正式验收 | `1023024` 的 v2 client/消费桩经真实 v2 FULL/L2 `tsc --noEmit` 通过；run 6 暴露并由 `d79f65b` 修复 `Json` 导入缺陷，跨 Artifact explicit context 经 `34295ec` 修复 | SIG-P1-01 |
| S6 | 源码 / 记录 | `1023024` 已补 OpenAPI/DDL 结构解析、参数/响应绑定语义和稳定 issue code；保留 9 个最小缺陷并继续补分层变异、误报与耗时证据 | SIG-P1-02 |
| S7 | 源码 / 记录 | `1efbfc8` 已将 verifier/verify-mysql 纳入默认 Compose 依赖并补专用 Token 与 `VERIFY_MYSQL_*` 配置；真实 `.env` 缺 3 个新变量，使用 `.env.example` 的独立 project 已可启动并通过认证 L2，真实 `.env` 冷启动到 Backend 仍待复现 | SIG-P0-03 / P1-03 |
| S8 | 记录 / 部分完成 | `1efbfc8` 已落地 internal network、non-root、read-only、cap drop、no-new-privileges、pids/tmpfs、CPU/内存限制，`a1b2187` 提供随机 schema；`9b3d282` 探针、`4ed8585`/`c47716d`/`8dbd878` deadline、`d9801a9` 主动 timeout/PID、临时 memory cgroup 和 `c6667f4` lock wait 均有证据；最新 head `8003ba4` 的远端 Sandbox CI run `37130433346` 全部 success，真实 `.env` 冷启动仍待覆盖 | SIG-P1-03 |
| S9 | 源码 / 记录 | `1b5f2ff` 已将规格 FAILED 与环境 ERROR/BLOCKED/NOT_RUN 分流；旧容器无密码 DSN 的失败保留为环境错误，不进入 Replan | SIG-P1-03 / 04 |
| S10 | 记录 / 正式验收 | `eeec0d59` 已将候选/fact 引用贯通持久化 Trace；run 9/run 10 证明显式 v2 FULL/L2、Evaluator/DeliveryGate/导出，run 10 Backend trace 含 `SPEC_VERIFY_PASSED` fact ref；`b60401a` 至 `d03541b` 补正式 Artifact/fact 导出拒绝，`c6144ee` 补三端回归。完整 live A/B/C/D、远端 Sandbox job、真实 `.env` 冷启动和主动资源故障仍待验收 | SIG-P1-04 / P2-02 / 03 |

### 2.2 基础 Agent 与展示

| 项目 | 当前状态 | 仍需的证据或动作 |
| --- | --- | --- |
| Live / 通用门禁 | 已有 v12 live run 38 六节点及导出成功；一次有界结构修复、三领域 fixture 与通用规则已实现 | 不再把 09-17 的 PM 失败或旧领域规则当成当前全貌；单次成功不证明总体收益 |
| CI / MySQL 故障恢复 | 远端 master 与 PR head 四项检查通过，09-22 IT 失败项已解决 | 不再安排 push 决定；未分析中间失败原因或测稳定性。为 Sandbox 增补 CI 覆盖 |
| 可靠性 | watchdog、poison 上限、outbox claim、cancel 已有代码及阶段故障证据 | 保留容量/故障条件，不把本地 fixture 数据扩展为生产 SLA |
| Function calling | 原生调用协议适配已实现，治理与台账存在 | 缺与 JSON-in-prompt 的工具选择/参数有效率对照；v12 工具白名单只有 `spec.verify` |
| MCP / A2A / Skills | 产品协议实现仍为空，MCP 明确延后 | 先准备取舍说明；仅有明确外部集成需求时选最小 governed MCP。`spec.verify` 有沙箱副作用，不能包装成纯只读工具 |
| RAG / 记忆 | 100 条、5 领域本地 semantic 与 hashing 对照已记录，Recall@5 都为 1.0；批准状态召回与 30 条注入回归已实现 | 基准饱和，无 semantic 收益证据；核对 v12 默认提示词是否使用项目检索，补记忆效果评测，不能由策略字段缺失直接推断没有 RAG |
| 展示 | Trace steps、追踪矩阵、验证面板、评测看板及 fixture Demo 已实现 | 动态 DAG 图未验收；看板有数据通路但完整 live 矩阵缺失，继续 `NOT_EVALUATED`；截图归档沿用用户豁免 |
| 仓库卫生 | 总计划/手册状态存在漂移，根目录两个 package 文件仍未跟踪 | 同步当前状态；核实 package 用途后再处置，不为清爽直接删除；变更影响分析继续 deferred |

## 3. 特色功能：Spec Sandbox

### 3.1 定位与完成边界

在规则检查和模型语义审查之外，将 API / 数据 / 前端消费契约确定性编译成可执行检查，用环境反馈支持 Backend 的有界修复，并给 Reviewer、Evaluator 与交付入口提供可信证据。L1 是结构校验，L2 是真实数据库/编译器执行；两者都不证明完整业务应用正确或可运行。

当前已经实现的是编译器、部分 L1、工具接线和独立 L2 骨架。下述架构与安全项是**修复后的目标**，未验收项不能写成现状。验证器不调用 LLM，fixture 不调用外部模型；两者仍有本地计算开销，live 生成/修复另计费用。

### 3.2 目标架构

```text
Worker（Backend BACKEND 验证 / Reviewer FULL 验证）
   │  spec.verify：冻结策略、来源版本与 scope
   ▼
Java Tool Gateway（权限 / fencing / 幂等 / deadline / 预算 / 台账）
   │  内网 HTTP + 专用 verifier Token
   ▼
spec-verifier sidecar（结构化输入，确定性编译，不执行模型代码）
   ├─ L1：OpenAPI 校验、DDL 解析、引用/类型/绑定语义检查
   └─ L2：随机 schema 的隔离 MySQL + 导入 client 的独立消费桩 tsc
   ▼
可信 VerificationReport / Fact（执行层级、issue、来源与策略 hash）
   → Backend Replan / Reviewer / Evaluator / Delivery Gate
```

- 验证范围来自冻结 WorkflowSpec。Backend 没有下游 Frontend 时，只验证后端可验部分；Reviewer 在汇合后执行 FULL。保留历史并行输入兼容，不在代码里硬编码六节点次序。
- `SANDBOXED` 已接入治理；节点仍须显式 allowlist，模型不能降低策略或把工具调用成功当成验证 PASS。
- 新修复候选目标为 **Backend `BACKEND/L2`、Reviewer `FULL/L2`**：前者提供可修复的环境反馈，后者提供交付所需的汇合证据。只要求 L1 的开发线须单独冻结并标注，不能声称 Sandbox 已验收。
- 保留 v12 和历史执行包。新契约/编译器/验证器/Prompt/Handler 版本按兼容影响分配，修复与实测通过后再考虑默认晋级；本次文档更新不切换工作流或启动实验。

### 3.3 显式输入与独立消费契约

SIG-P1-00 必须沿真实 `Agent→Artifact→adapter→SpecContract` 链路实现：

1. 后端产物显式提供 PK、FK 目标列、受限类型与参数位置，缺字段明确拒绝；允许显式 `foreign_key=null`，禁止用名字或 HTTP method 补事实。旧推断适配仅服务明确标记的历史兼容，不能进入新候选的可信验证。
2. 前端独立声明 `backend_api_id`、每个请求参数的名称/位置/来源类型/必填性，以及消费的响应字段路径、预期类型和可空性。空集合必须能与“字段未提供”区分；检查器输入不能由 API 契约反向补全。
3. 类型化客户端处理 path / query / body；消费桩按前端自己的映射 import 并调用客户端、读取声明响应字段。限制为受控的类型/路径表达，不执行模型写的 TS。
4. 仅改前端参数/响应消费或仅改后端响应时，检查结果必须产生可解释差异；相同正常契约输出逐字节一致。同源生成的客户端与字符串表通过 tsc 不能作为跨 Artifact 一致性证明。

### 3.4 检查目录与错误语义

| 层级 | 修复后要求 | 当前覆盖与待补 |
| --- | --- | --- |
| L1 | 实际 OpenAPI 3.1 校验、操作唯一、路径参数声明 | 已有操作/路径检查；补文档校验器，并将其错误映射为稳定 code/path |
| L1 | DDL 解析、PK/FK 目标/索引和 MySQL 兼容性、标识符安全 | 已有 PK/FK `kind` 检查与编译白名单；补解析及精度/长度规则，只按 MySQL 的实际兼容规则判断，不要求所有字符串长度一律相等 |
| L1 | 必填参数完整、位置/类型匹配、响应字段与可空性消费一致 | 目前主要检查操作漂移和参数名称；补 `BINDING_PARAMETER_MISMATCH`、`BINDING_RESPONSE_FIELD_MISSING` 等版本化 code |
| L1 | 引用与 MUST 追踪适用性 | 保留现有引用检查；明确 API / 数据 / UI 对每项需求是否适用，不强迫每个 MUST 都生成新表 |
| L2 | 独立 MySQL 8.4 中真实应用确定性 DDL | 已有两规格连续/并发及部分失败清理证据（`a1b2187`）；继续补第三 fixture、真实错误分类、资源/网络探针和专项 CI |
| L2 | `tsc --noEmit` 检查客户端与独立页面消费桩 | 当前字符串表没有消费关系；先补契约/生成器，再测增量检出 |
| 延后 | 完整前端构建、业务验收、离线 Java 骨架编译扩展 | 不纳入本轮 Sandbox 首版完成声明 |

已有 issue code 保持兼容；新增规则随版本冻结。规格导致的 DDL / TS / 契约错误返回 `FAILED / BLOCKED`，环境不可用、权限/认证、超时、资源限制或清理失败返回 `ERROR / BLOCKED`，缺检查为 `NOT_RUN`。成功判定要求声明检查实际执行完毕、没有阻断问题、来源/策略/scope/层级匹配，且清理成功。不能用捕获所有异常或吞掉清理异常制造 PASS。

能在 L1 可靠表达的错误应在 L1 检出。**保留字可能已被正确转义，响应缺字段应由补齐的 L1 检出，不能预先把它们指定为“只有 L2 才能发现”的缺陷。** L2 增量样本须以真实执行结果确定。

### 3.5 安全与资源边界（待验收）

- 只接受已通过 Schema 校验的结构化字段；SQL / OpenAPI / TS 均由白名单编译器生成。绝不执行 LLM 撰写的 SQL、代码或 shell，不挂载 docker socket、业务数据卷或业务密钥。
- sidecar 使用 non-root、只读根文件系统、`cap_drop: ALL`、`no-new-privileges`、PID/CPU/内存硬限和有容量限制的 tmpfs 工作目录；专用服务 Token 与通用 Agent Token 分离。
- verifier / verify-mysql 保持独立 internal network；用运行期探针证明不能访问业务 MySQL、Redis和公网，不能仅由配置字段推定隔离成功。
- verify-mysql 不保留数据卷；每次**实际执行**创建由验证器生成的随机 `autospec_verify_%` schema，最小权限用户仅能管理该前缀，最终 DROP DATABASE。请求幂等不要求重复执行共享 schema；并发执行不能互删资源。
- 全链路采用剩余 deadline，不能分别给 tsc、数据库完整超时而累计超限；流式读取并在上限前拒绝超大请求，限制并发、子进程输出和结果大小。进程强制结束后有可核验的残留回收机制。
- 清理失败记录执行/schema/错误并阻断；基础设施修复由平台处理，不送模型 Replan。不得通过删除业务卷、关闭 FK 检查或临时放宽门禁完成验收。

### 3.6 延伸特色（deferred）

需求变更影响分析与增量重生成仍复用 trace graph / `ReworkPlanner`，对应 SIG-P3-06。主线完成并明确选择后，再验证影响子图与未受影响组件 hash，测量相对全量重跑的 token 变化。MCP / A2A / Skills 不作为修复 Sandbox 的前置。

### 3.7 当前可使用的事实与表述

| 已有记录 | 可以说明 | 不支持的结论 |
| --- | --- | --- |
| 3 个正常规格、9/9 手写变异检出 | 既有最小回归覆盖的规则均检出该样本 | 独立基准检出率、泛化准确率或完整缺陷覆盖 |
| v12 live run 38：54,619 tokens、估算 0.130100 CNY、Evaluator `100/A/PASSED`，Markdown/PDF/ZIP 成功 | 一条指定配置的六节点 live 与导出通过样本 | 平均成本、总体质量、L2 门禁通过或真实 Replan 成功 |
| fixture 10/20 并发，30/30 完成 | 冻结 inventory 输入、本地隔离环境的容量观测 | 生产 SLA、live 吞吐或跨配置质量收益 |
| 普通 CI 四项全绿 | 合并提交在声明的质量门禁范围通过 | verifier 镜像、L2、整栈运行及隔离验收 |

以上数字来自 [P0/P1 证据](archive/evidence/p0-p1-execution-2026-09-30.md)、[P2–P4 证据](archive/evidence/p2-p4-2026-10-01.md) 及 [10-02 复核](archive/evidence/spec-sandbox-review-2026-10-02.md)。当前表述可写“实现确定性规格编译、L1 校验及受控验证工具接入，已有单次隔离 L2 和六节点 live 通过记录，正在补齐重复执行与修复证据”。门禁通过率 `X%→Y%`、Sandbox 自我修复成功率和 D 优于 A 暂无可填数字。

## 4. 路线图与当前状态

状态使用 `planned / in_progress / blocked / done / deferred`。`done` 只覆盖明确的验收范围；发现现实缺陷则重开，不删除历史通过记录。相对规模 S/M/L 不承诺工期。后续执行顺序以第 9 节为准，不重做已通过的无关工作。

### P0：可用性与可信度

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P0-01 | done（指定 smoke） | 一次有界修复与 run 38 正式 live/导出证据已有；不扩展为总体质量收益 |
| SIG-P0-02 | done（现有三领域回归） | 通用规则与三领域 fixture 已有；保留缺陷回归，不因 Sandbox 变更重写领域规则 |
| SIG-P0-03 | in_progress | MySQL IT 修复及普通远端 CI 已满足；修正默认启动依赖/文档，核实根 package 文件用途后处置。CI 通过不令整个卫生工作包自动完成 |

### P1：重开 Sandbox 验收

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P1-00 | done（正式 v2 smoke 范围） | `d1e98b2` 新候选经治理 API 发布；run 9/run 10 真实 Handler/Prompt/Schema/explicit adapter 全链路通过，默认 active/v12 未改写。增量变异和 live 仍不属于本范围 |
| SIG-P1-01 | in_progress | v2 path/query/body client、独立消费桩和 `Json` 导入已由 `d79f65b` 修复并在正式 run 9/run 10 的 L2 tsc 中通过；分层变异检出/误报/耗时报告仍待补 |
| SIG-P1-02 | in_progress | `1023024` 已补 OpenAPI/DDL 结构解析和绑定参数/响应语义；独立 L2 与正式 FULL/L2 已实际应用 MySQL DDL，分层变异检出/误报/耗时报告仍待 P1-E/CI |
| SIG-P1-03 | in_progress | 随机 schema / 可靠清理、隔离硬限、真实 MySQL 三领域重复并发、残留查询、运行期探针、共享 deadline 和主动 timeout/PID 已记录；主动内存/锁等待故障、真实 `.env` 冷启动和远端 L2 CI 仍待覆盖 |
| SIG-P1-04 | in_progress | `d1e98b2` 候选已正式发布；run 9/run 10 完成 explicit FULL/L2、Evaluator/DeliveryGate/导出，run 10 Backend v7 loop trace 已保存；R3 deadline/资源、R5 增量样本、live 自我修复和远端 Sandbox 仍待验收 |

### P2：证据与对照

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P2-01 | done（explicit v2 冻结） | `46810a13` 新冻 explicit v2 A/B/C/D：治理版本 4–7、contract hash、Backend/Frontend/Reviewer/Evaluator prompt/schema、L2 compiler/verifier、数据/价格/预算 manifest 均 validate-only 通过；旧版本未改写 |
| SIG-P2-02 | blocked（仅 live） | explicit v2 development/holdout manifest 和 fixture config 已 validate-only；真实 key/冷启动及最小 live smoke 已执行并 fail-closed，完整批次保守上限 `576 CNY` 未取得本批次预算/启动授权，未创建 P2 live 矩阵；fixture 不替代 live 结论 |
| SIG-P2-03 | done（限定环境） | 既有正式 Replan/预算/`REPLAN_LIMIT` Trace 加本轮冻结候选的 192/96 完整 fixture 矩阵已有来源关联；短样本与 fixture 不泛化为生产 SLA 或 live 收益 |

### P3：基础 Agent 补证据与可选扩展

| ID | 状态 | 当前范围与剩余验收 |
| --- | --- | --- |
| SIG-P3-01 | deferred | 有明确外部集成需求再选最小 governed MCP；读取 Trace 可只读，`spec.verify` 仍保留 SANDBOXED 权限。MCP 实现不等于 A2A / Skills 实现 |
| SIG-P3-02 | done（既有回归） | 30 条注入回归与不可信内容治理已有；新工具/契约改变时重跑相关边界，不宣传泛化攻击免疫 |
| SIG-P3-03 | done（协议层） | `06798eda`/`c5f4528` 接入显式 native tool_calls、allowlist、provider call ID、错误/并行边界和 no-tool turn；5 条同集 JSON/native fixture 对照通过，未测 live provider 质量 |
| SIG-P3-04 | done（本地对照） | 批准状态召回与 ACL/过期过滤已有；本轮复跑 100 条、5 领域、top_k=5 的 hashing/local semantic 同集对照，结果饱和且无收益提升，不切换生产外部 provider |
| SIG-P3-05 | done（既有故障范围） | watchdog / poison / outbox / cancel 已有实现和限定演练；不重复建设，也不泛化为全故障可靠性 |
| SIG-P3-06 | deferred | 变更影响分析只在主线完成且明确选择后实施 |

### P4：展示与讲法

| ID | 状态 | 当前范围与剩余验收 |
| --- | --- | --- |
| SIG-P4-01 | in_progress | Trace、矩阵、验证与看板实现已有；动态 DAG 图未验收，L2 与真实修复证据需可下钻；完整评测证据仍依赖 P2 |
| SIG-P4-02 | in_progress | 五分钟 fixture Demo 已有，截图归档按用户豁免；修正 verifier 启动依赖、环境说明与完成状态后冷启动复验 |
| SIG-P4-03 | in_progress | 15 张问答卡片已有；刷新 Sandbox/CI 断言与引用，所有数字保留限定条件 |

### 依赖与截断线

```text
P1-03 清理/重复执行 ─┐
P1-00 显式生产契约 ─┼─▶ P1-01/02 独立消费与检查 ─▶ P1-03 部署/隔离/CI
                    └──────────────────────────────▶ P1-04 FULL/L2 + 正式修复 Trace
                                                      └─▶ P2-01 重冻 ─▶ P2-02 live 对照
```

L1 开发线可以独立演示“规格静态检查”，不能以此关闭 Sandbox 的 P1-03/04 或替代 L2。基础 Agent 的已验收能力继续保留；可选扩展不成为必选项的借口，也不以缺预算阻塞本地修复与 fixture 证据。

## 5. 面经覆盖矩阵

| 面经主题 | 当前可讲 | 待补证据 |
| --- | --- | --- |
| 架构 / 多 Agent / HITL | 冻结 Spec、六节点 DAG、Outbox/Worker、审批与返工 | 使用当前默认与历史兼容的正确版本说明 |
| ReAct / 有界循环 | v12 Backend 的步数、Replan、预算/震荡停止机制 | 正式失败反馈→修复和终止 Trace；首轮 PASS 不等于自我修正 |
| Function Calling / 工具治理 | allowlist、fencing、幂等、台账及原生协议适配 | 同集 native/JSON 对照；工具薄的取舍 |
| MCP / A2A / Skills | 目前未实现，按外部需求延后 | 如选 MCP，给实际调用/权限台账；否则如实说明边界 |
| RAG / 记忆 / 上下文 | 项目级链路、预算、批准召回，本地 100 例对照 | 默认使用证明、困难样本、记忆召回效果；Recall 饱和不证明收益 |
| 幻觉 / 护栏 / 结构输出 | Schema、有界修复、引用门禁、30 条注入回归 | 完整显式事实与跨 Artifact 检查，不把推断结果当真值 |
| 评估 / 成本 / 时延 | 采集器、统计/门禁、账本与看板，负面 smoke 记录 | 完整 live 四组、置信区间与重复数据；预占和实际成本分列 |
| 可靠性 / 可观测 | watchdog、DLQ、claim、取消、Trace steps 与容量故障证据 | 不将 fixture 排队/执行值泛化为 live SLA |
| Sandbox / 安全 / 交付 | 确定性编译、L1、受控接线、单次独立 L2 | 隔离探针、L2 增量、重复执行、FULL/L2 可信门禁 |

## 6. 评测与预算

### 6.1 先评验证器，再评 Agent

验证器实验不调用外部模型：保留 3 个正常规格与 9 个历史反例，围绕新增现实风险建立规则变异集，**目标至少 30 个有效样本**，覆盖参数、响应、PK/FK、追踪、DDL 与隔离/执行错误；不为凑数复制同一缺陷。按基础规格/缺陷族聚类，报告手写或生成方式、训练/校准/保留集划分及依赖关系，不能将同源变异当独立业务样本。

用同一输入对照 L1 与 L1+L2，列出逐样本检出层级、误报、漏报、稳定 code 与耗时。至少取得 **3–5 个经真实 MySQL/tsc 确认的 L2 增量反例**；若补齐 L1 后没有增量，应如实报告并调整 L2 价值判断，不故意削弱 L1。用获准使用的既有真实产物补充复扫，先独立标注真值，区分旧字段缺失、规格缺陷和验证器误报；脱敏保留来源版本/hash。

### 6.2 A/B/C/D 定义与有效样本

- A：single-shot 实验基线；**不是当前默认路径的描述**。
- B：Backend 循环 + 确定性结构校验。
- C：与 B 同一循环/预算策略 + 只读工具。
- D：与 C 相同的循环/只读工具，再向生成侧提供 `spec.verify` 反馈。
- 四组最终判分器相同，包括修复后的 L2，A/B/C 的最终 verifier 结果不能提前回灌生成侧；C/D 不得同时改变 Replan 开关。冻结原生/JSON 调用模式、上游输入、上下文/检索、Prompt/Schema、工具和预算，才能归因反馈收益。
- 分节点级固定上游对照与经 `POST /api/workflow-runs` 的六节点对照；保留 8 smoke / 16 development / 8 holdout、至少 5 领域，每格至少 3 次重复。最终阈值、成本与时延上限在新 manifest 中预注册。
- Wilson 95% 区间与按 case 聚类的配对 bootstrap；holdout 只作一次正式决策，不能看结果后改规则/数据。结论可 `REVISE` 或 `REJECT`，缺组/重复/有效样本则 `NOT_EVALUATED`。

历史结果保留：r11 fixture development 为 **12/192** 通过、holdout **0/96**，失败均为 `QUALITY_GATE_BLOCKED`；它证明采集完整并暴露该配置失败，不能归因 Sandbox 的质量收益。live smoke 为 1 case × 1 repetition × 4 groups，无可用交付通过的四组对照；D-only 重试跑完六节点但被 Evaluator 阻断，也不能单独与 A 比较。

### 6.3 预占、费用与启动条件

[10-01 记录](archive/evidence/p2-p4-2026-10-01.md) 中 r12 已冻结并通过 `--validate-only`，完整 development 192 + holdout 96 = **288 个 run**，每 run 上限 2 CNY，需 **576 CNY 保守预占**；已有授权标识，但安全执行层未接受该批量启动，因此 r12 没有新增 live run/费用预占。不能把阻塞一概写成“尚未申请预算”。

576 CNY 是预占上限，不是预计账单。按已有约 0.13–0.19 CNY/run 外推约 40–55 CNY；按原计划 0.2–0.3 CNY/run 外推约 60–90 CNY。这些只基于历史配置/价格/用量，不含重试，修复后的 L2/Replan 配置尚无成本分布，不是现价或费用承诺。

修复完成后再核对：保留完整规模与原预占、依据实测包络调整预占，或缩小规模并下调结论范围。任何输入、版本、预占或规模变动必须重新冻结并核对仍在用户授权内；超出则取得新的明确授权，执行层须接受实际动作。旧 manifest/预算台账不可改写，也不能以切小批或换执行入口绕过批量启动限制。当前文档更新只推进计划，不执行付费实验。

## 7. 约束、风险与回退

沿用 AGENTS.md：保留六节点与正式 Worker 链路，不新增 `/generate*`、固定 DAG 或任意执行工具；Worker 不直连业务 MySQL。WorkflowSpec、已发布 Prompt/Schema/Handler 和历史运行冻结不动；Flyway 从实际最大版本 + 1 新增，V1 不改写。真实 Key 不入库、不回显 `.env`。日常仅跑相关测试，跨模块契约/数据库变更或里程碑才做三端回归。

| 风险 | 控制与回退 |
| --- | --- |
| L2 清理、共享 schema 或并发污染 | 每次执行随机 schema、清理可观察；异常阻断并回收。不关 FK 检查或清业务卷掩盖失败 |
| 验证器与生成器同源自证 | 独立前端消费事实、单边变异、外部解析器和真实执行；判分器对四组相同 |
| sidecar 不可用 / 超时 / 输出超限 | readiness/预检、全局 deadline、流式大小限制；环境 ERROR 终止，冻结要求 L2 的运行绝不降级 |
| 验证器误报或新增规则不准 | 对照独立真值与现实兼容规则；阻止该候选晋级，修复后重冻。不能默认把已有 HIGH 降为 MEDIUM |
| 正式运行错误被模型反复“修环境” | FAILED 与 ERROR 分流，Replan 共享调用预算；停止原因入 Trace |
| 对照结果不佳或不完整 | 保留失败、缺样本与负面结论；不改旧结果，不凭单次 PASS 激活新默认 |
| 旧文档或 CI 状态漂移 | 以带日期/commit/run 的新记录更新当前手册；历史证据原样保留，声明 CI 实际覆盖范围 |
| 范围膨胀 | Sandbox 主线先验收；MCP、A2A、Skills、影响分析及完整应用生成不自动加入 |

新候选失败时停止其后续运行，保留 run/fact/issue；当前产品基线仍按已有部署选择运行。L1 开发演示使用独立冻结策略并明确层级，不修改已要求 L2 的运行或覆盖 v12。

## 8. 实施工具与验证范围

使用 [AGENTS.md](../AGENTS.md) 的固定本机工具，不安装插件或改环境来完成文档整理。修复实现时复用现有定向 pytest/JUnit；只新增核心成功与必要高风险反例，真实 L2 不能由 mock 或普通 CI 代替。

本次仅做源码/配置复核、GitHub CI 查询及文档验证；没有运行应用测试、Docker/MySQL 或 live 调用。Agent 226 / Backend 185 / Frontend 29 是 10-01 证据文件的历史数字，不标为本次通过。

## 9. 下一批可执行任务（按顺序）

| 顺序 / 原计划映射 | 动作与入口 | 最小必要验收 / 停止条件 |
| --- | --- | --- |
| 1 / SIG-P1-03 | **已完成本步**（`a1b2187`）：`spec_verifier/sandbox.py`、Compose 使用随机 schema、整库 finally 清理和显式清理失败；详见 [P1-03 证据](archive/evidence/p1-03-l2-schema-cleanup-2026-10-02.md) | 校园交易/请假各连续两次真实 MySQL 通过；两条并发验证通过；部分 DDL 失败清理且无残留；清理异常有明确 issue。SIG-P1-03 的隔离硬限、第三 fixture、专项 CI 仍由第 4/7 项继续 |
| 2 / SIG-P1-00 | **已完成 L1/生产接线**（`cb4355b`）：`schemas/`、Backend/Frontend Prompt、`artifact_adapter.py`、两个 v2 生产调用点和独立映射已接入 | 新候选缺字段拒绝；非命名惯例 PK/FK/位置保留；旧回放兼容；两条生产路径显式事实通过。候选冻结与正式 FULL/L2 仍由 SIG-P1-04 验收 |
| 3 / SIG-P1-01 / 02 | **已完成 L1 部分**（`1023024`）：`compiler.py`、`validators.py` 补 OpenAPI/DDL 校验、完整客户端、import client 的消费桩和稳定 issue | 正常规格、参数/响应单边变异、同输入逐字节一致已通过；真实 tsc/MySQL L2 增量和分层报告待 P1-E/CI |
| 4 / SIG-P0-03 / P1-03 / P4-02 | **已完成代码与本地隔离探针**（`9b3d282`）：默认 verifier 依赖、专用 Token、请求/响应/超时边界、容器 hard-limit 和 `scripts/verify_spec_sandbox.py` 已落地，`quality.yml` 新增构建/探针 job | `.env.example` 隔离 project 的 401/413/422、真实 FULL/L2、PID/CPU/内存/tmpfs、临时目录/schema 清理和外网阻断均通过；真实 `.env` 冷启动、主动资源故障注入和远端 Sandbox job 实际运行仍是停止条件 |
| 5 / SIG-P1-03 / 04 | **已完成代码与正式 fixture Trace**（`1b5f2ff`、`47fa3f7`、`b88598b`）：FAILED/ERROR 分流、候选/fact 引用和正式 run 4 的 bounded Replan 已记录；默认预算不足的 run 3 也保留为负例 | 规格失败只能进入一次有界 Replan；环境/权限/清理/超时故障终止；两者均阻断交付，不能靠调高预算或吞错通过 |
| 6 / SIG-P1-04 / P2-03 | **已完成正式 Replan/终止 Trace 与主要交付负例**：run 4 经 API + Worker 保存 candidate/fact hash、issue、Replan 和最终六节点状态，run 3/5 分别记录预算拒绝与 `REPLAN_LIMIT`；run 2 已有 Evaluator/DeliveryGate/导出成功；另有 Artifact 变更、缺失/过期/低层级/错 scope/伪造 fact 的正式导出拒绝，且 `5771150` 修复人工编辑评估报告复用旧 fact 的缺口 | 来源/policy digest 单独变异尚未在修复后的正式 API 上执行；不能只调单测伪模型，live Replan 仍未关闭 |
| 7 / SIG-P1-02 / 03 | **已补本地探针与 CI 定义**（`9b3d282`）：扩展 `quality.yml` 的 verification config、verifier 镜像构建和 sandbox probe job | 仍需远端 Sandbox job 实际运行，并补分层变异集/真实产物复扫的检出、误报、耗时逐样本证据；全程 fixture 无外部模型费用 |
| 8 / SIG-P2-01 / 02 | **已完成离线/fixture 部分**：新冻 A/B/C/D manifest、数据与预算通过 validate-only；development/holdout fixture 完整采集并保留 `NOT_EVALUATED` | 新配置未借旧 r12 hash；完整 live 批次需要额外有效付费授权，安全执行层拒绝 `576 CNY` 批量启动，P2-02 继续 blocked，不填收益数字、不晋级 |

每步完成只更新对应验收事实。详细执行步骤可补入两份手册，脱敏报告归档；根 package 文件处置与可选 MCP 只在用途/范围确认后继续，不阻塞上述本地修复。

## 10. Definition of Done

| 验收项 | 当前状态 / 关闭条件 |
| --- | --- |
| 正式六节点 live + 门禁 + 导出 | 已有 run 38 的指定配置样本；保留为 smoke，不代表 L2 或自我修复 |
| 真实生产显式事实 + 独立前端消费 | 部分完成：`d1e98b2`/`34295ec`/`948162f` 与 run 9/run 10 已证明显式 Handler、消费、FULL/L2 和 trace；增量负例、远端 CI 与 live 仍待补 |
| L2 可重复、并发隔离与安全硬限 | 部分完成：真实重复/并发/失败清理、网络/资源探针和共享 deadline 均有记录；主动资源/锁等待故障注入、真实 `.env` 冷启动和远端 L2 CI 仍待验收 |
| 正式 FULL/L2 门禁 | 部分完成：显式 v2 run 9/run 10 已绑定 v2 fact 并通过 Reviewer/Evaluator/DeliveryGate，ZIP/Markdown 成功；Artifact 变更、缺失/过期/低层级/错 scope/伪造 fact 负例保留，来源/policy 单变体和 live 仍待补 |
| 正式 verifier 失败→Replan→修复，以及终止 Trace | 部分完成：run 6 记录真实 L2 TS 失败、run 10 记录显式 Backend L2 PASS loop；既有 fixture run 4/5 保留 Replan/终止证据，真实 L2 FAILED→Replan→修复专门样本和 live 自我修复仍未关闭 |
| 验证器增量、检出/误报/耗时 | 未完成：分层样本、独立真值、历史产物复扫；明确样本量/依赖/局限 |
| 完整 live A/B/C/D 与置信区间 | blocked：先修复、重冻并满足启动条件；负面结论可接受，缺样本不能关闭 |
| CI | 普通 master CI 已满足；Sandbox 扩展 CI 已加入 verifier 构建/探针 job，但远端实际运行结果尚未取得 |
| 展示与问答 | 已有 fixture Demo/面板/15 卡片；启动说明、真实 L2/修复下钻与 DAG 图待补；截图归档豁免继续有效 |
| 范围与历史保护 | 不新增 Agent、旧同步入口、固定 DAG 或任意执行；不改冻结版本/历史证据，不删业务卷，数字可追溯 |

只有必选修复和证据均满足，总计划才能改为 `done`。MCP / 变更影响等明确 deferred 项不强制实施；它们的缺失必须在介绍中如实说明。

## 资料来源

面经主题清单来自公开整理，非原帖逐字内容：

- [牛客：近期开发 AI 面经](https://www.nowcoder.com/feed/main/detail/e3425d60dec24ee1bcf1dc44420b0101)
- [卡码笔记：Agent / 大模型大厂面试题汇总](https://notes.kamacoder.com/interview/llm/agent_interview.html)
- [知乎：小红书二面——Agent 框架选型与评价指标](https://zhuanlan.zhihu.com/p/2017373001881002562)
- [阿里云开发者社区：65 题 AI Agent 面试宝典](https://developer.aliyun.com/article/1739618)
- [GitHub：ai-agent-interview-guide](https://github.com/bcefghj/ai-agent-interview-guide)


</details>
