---
plan_id: autospec-v5-interview-driven-optimization
version: 1.2
status: archived
created_at: 2026-09-14
product_baseline: autospec-v5:v5
reviewed_commit: 4487cc1d
scope: Agent应用研发与Java后端实习面试导向的项目优化
---

# AutoSpec 面经驱动优化计划

> 历史归档（2026-09-29）：保留当时的设计、状态和执行证据，不作为当前能力或待办清单。当前说明见 [文档索引](documentation-index.md)，后续工作见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

## 1. 结论

优先把已有 Agent 能力跑通、测准并形成可复现证据。当前项目已经有六节点 DAG、Redis Worker、Outbox、审批返工、版本化 Artifact、Tool Gateway、引用门禁和预算台账，继续增加框架名称的收益有限。

本轮最值得投入的方向依次是：

1. 修正候选 Agent Loop 的模型调用预算、Prompt 和结构化动作协议。
2. 补强实验晋级判定，接通正式控制面的 live 评测适配器。
3. 证明 RAG 在未见项目需求上的收益，再引入真实语义向量、结构化分块与重排。
4. 把现有记忆原型转为有来源、版本和项目权限的 Project Memory。
5. 补齐故障恢复、模型路由成本与交付可用性的实测证据。

按 AI 应用研发 / Agent 工程与 Java 后端实习方向制定；未针对模型训练、算法研究岗位安排 LoRA、RL 或分布式训练。以下优先级是结合公开样本与代码的判断，不是平台统计出的考题频率。

## 2. 调研来源与证据边界

检索日期：2026-09-14。检索范围包含牛客、实习僧，以及知乎、掘金和个人博客的相关结果。采用公开可读的原帖、作者自述和招聘方岗位页；付费合集、培训推广和无法核实正文的结果不作为核心依据。

面经是作者回忆，不能确认面试官原话或代表整家公司。实习僧本次主要检索到岗位 JD，下面明确与面经区分。部分岗位已下线，仅用于能力要求分析；动态页面与搜索索引时间可能不一致。

| ID | 来源、时间与类型 | 本次采用的信息 | 对应项目方向 |
|---|---|---|---|
| S1 | [牛客：安软 AI 应用开发实习面经](https://api-cdn.nowcoder.com/feed/main/detail/40920533fad14136bff01c3928c7e953?sourceSSR=post)；正文注明面试 2026-07-25 | 追问模型接入、工具注册权限、上下文保留规则，以及小数据集高命中率是否可信 | 受控工具、评测可信度、上下文与模型适配 |
| S2 | [牛客：AI Agent 二面—字节实习面经](https://api-cdn.nowcoder.com/feed/main/detail/d31969d954a94f1cb1bb06abc3196fe9?sourceSSR=users)；页面显示 03-22，未明确年份 | 涉及记忆、动态知识更新、复杂任务评估、MCP 与 Function Calling | 项目记忆、RAG 更新与能力边界 |
| S3 | [牛客：9.8 小厂 Agent 开发实习面经](https://www.nowcoder.com/feed/main/detail/2f4e4cde4e524a16aae5f55a89c49273?sourceSSR=dynamic)；标题标注 9.8，正文来自搜索索引，直开页面未返回完整正文 | 项目具体失败与迭代、Harness、上下文压缩，以及没有标准答案的规划类输出如何评测 | AutoSpec PRD/架构质量、失败案例复盘 |
| S4 | [作者博客：字节 Agent 开发实习一面面经](https://www.chaojixin.ren/posts/字节agent开发实习一面面经/)；2026-07-31 | 追问评测数据来源、业务对应关系、Harness 与沙箱隔离 | 评测集、交付验证、系统设计取舍 |
| S5 | [实习僧：平安科技大模型应用算法实习生](https://www.shixiseng.com/intern/inn_wmcezk0oinhe?pcm=pc_SearchList)；页面刷新 2026-09-04；招聘 JD | 要求完整 Agent Loop、异常反馈、调用上限、超时取消、检索重排、批量评测与链路追踪 | Loop 实际落地及质量、成本、时延联合评测 |
| S6 | [实习僧：蔚来 Agent 系统开发工程师](https://www.shixiseng.com/intern/inn_22y8tyrxejtr)；招聘 JD，页面显示下线 | Spring Boot/FastAPI、异步任务、MySQL/Redis、模型效果评估与容器化工程交付 | 保留 Java 控制面优势，补容量和恢复实证 |
| S7 | [实习僧：晶远芯 AI Agent 工程师](https://www.shixiseng.com/intern/inn_lkad3qwwhc42)；招聘 JD，直开页面显示下线 | Prompt、Tool Calling、Memory、RAG 与多智能体应用开发 | 以闭环能力和业务效果组织项目展示 |

这些来源共同提示：项目要能解释业务问题、设计取舍、失败原因和改进效果。技术方案以下面的仓库实现与验证为依据，不把面经中的回答当成正确性标准。

## 3. 当前代码基线：哪些已经有，哪些还缺证据

基线为 `4487cc1d` 加当前工作区；已有未提交文件保留。未连接运行中数据库核实激活状态，因此“草稿 / 正式版本”判断来自仓库契约与迁移。

| 能力 | 核验位置 | 当前事实 | 本计划处理 |
|---|---|---|---|
| 六节点与持久化执行 | `README.md`、`backend/src/main/java/com/autospec/workflow/`、`agent-engine/runtime/worker.py` | V5 控制面与 Worker 链路已存在 | 复用，补真实故障实验 |
| Backend Agent Loop | `agent-engine/runtime/production_handlers.py`、`runtime/backend_agent_loop.py` | Handler 已按冻结 policy 调用循环，并经 `execute_current_tool` 执行工具 | 不再列为从零开发；核验 live 可执行性 |
| 候选 WorkflowSpec | `agent-engine/contracts/autospec-v5-agent-execution.workflow.json`、Flyway `V96__seed_autospec_v5_agent_execution_candidate.sql` | 候选含 Loop/Tool/Retrieval Policy；种子状态为 DRAFT | 先修正、评测，再按不可变流程发布 |
| 模型交互协议 | `agent-engine/prompts/backend_engineer_v1.md`、`runtime/backend_agent_loop.py::_next_turn` | Prompt 要求 BackendDesignArtifact；循环载荷仅以名称引用 AgentTurn Schema，未在该载荷提供完整工具参数契约 | P0：解决提示目标与循环动作协议不一致 |
| 循环预算 | 候选 Backend 节点 `model_policy.max_calls=2`、`agent_loop_policy.max_steps=4` | Plan、ToolCall、FinalCandidate 通常需至少 3 次模型调用；若验证失败后显式 Replan 再生成，需要至少 5 次 | P0：预算与路径可达性校验 |
| 评测与晋级 | `agent-engine/evaluation/ablation.py`、`evaluation/autospec_case_catalog.py` | 8 类 AutoSpec case 和 A/B/C/D 收集框架已存在；无 live_runner 默认 NOT_EXECUTED | P0：实现正式运行适配器及可信晋级门禁 |
| RAG | `backend/src/main/java/com/autospec/service/KnowledgeEmbeddingService.java`、`KnowledgeIndexService.java` | 当前向量为 192 维 hashing n-gram；存在词法/向量融合与确定性重排；Artifact 分块调用参数为 900/120 字符 | P1：量化语义检索和分块改进，不称已有神经 Embedding |
| 长期记忆 | `agent-engine/runtime/memory.py`、`runtime/context_builder.py`、`schemas/memory.py` | 内存实现仍按 user/skill 组织，生产 Handler 使用另一条 context policy 链路；未找到正式 Project Memory 接入 | P1：项目事实记忆持久化与受控召回 |
| 审查与交付门禁 | `agent-engine/agents/reviewer.py`、`review/evaluator.py` | Reviewer 已合并规则与模型语义审查，Evaluator 已有追踪门禁 | 增加与人工判断、交付构建结果的对照 |
| 运行证据 | `docs/archive/p1-capacity-and-recovery-report.md` | 旧报告明确未执行 live、Docker E2E、容量和故障注入 | P1：补实测；旧报告不代表本次测试结果 |

补充发现：README 声称架构后前后端并行，但候选 Spec 中 Frontend 还依赖 Backend；说明与实际依赖要逐项对齐。不能直接为追求并发删除依赖，需验证 Frontend 输入是否需要 Backend 契约，并用冻结 Spec 表达最终决定。

## 4. 优先级与依赖

所有工作包初始状态为 `planned`。本轮只完成调研与计划创建，不代表下面功能已实施。

| ID | 优先级 | 工作包 | 预估净工作日 | 依赖 | 可交付结果 |
|---|---|---|---|---|---|
| INT-P0-01 | P0 | Loop 协议、Prompt、预算和状态约束 | 2–3 | 无 | 可到达工具调用与返工完成路径的候选版本 |
| INT-P0-02 | P0 | 可信实验晋级门禁 | 1–2 | 无 | 边界回归与拒绝无效证据的判定 |
| INT-P0-03 | P0 | 正式 API 评测适配器与盲测集 | 3–5 | P0-01、P0-02 | 逐 Case live 结果、Trace 和消融结论 |
| INT-P1-01 | P1 | 结构化 RAG、语义检索与评估 | 3–5 | P0-03 的评测基础 | 标注检索集、召回/重排对比与启用决定 |
| INT-P1-02 | P1 | Project Memory 与上下文保真 | 3–4 | P0-03 的评测基础 | 项目事实版本、冲突处理、压缩保真报告 |
| INT-P1-03 | P1 | 安全失败、容量恢复与成本路由 | 3–4 | P0-01、P0-03 | 故障恢复矩阵、并发曲线、模型路由账单 |
| INT-P1-04 | P1 | 交付验证与面试证据包 | 2–3 | P0-03；随 P1 逐步补齐 | 可重放 Demo、构建证据、设计取舍与失败复盘 |
| INT-P2-01 | P2 | 有实际接入目标时验证 MCP 适配 | 1–2 | P1-03，且目标岗位/工具确实需要 | 单个只读适配器与故障边界，或暂缓结论 |

单人完成 P0 约 6–10 个净工作日；全部核心 P0/P1 约 17–26 个净工作日，不含外部模型/环境准备时间。若面试临近，先完成 P0、一个 RAG 对照实验和 P1-04 的最小证据包，不要求面试前完成所有扩展。

## 5. P0 实施与验收

### INT-P0-01：让候选循环真正可执行

关联来源：S1、S3、S5。重点回答“模型怎么选择动作，工具为什么这样调用，循环为什么会停止”。

实施内容：

- 新增版本化 Loop Prompt；保留 single-shot Prompt 及历史校验和。把允许的 AgentTurn 联合 Schema、工具描述、参数 Schema、版本和可见范围编入受预算约束的输入，内容来自冻结契约。
- 明确允许的阶段转换。当前兼容解析会把直接返回的 BackendDesignArtifact 转为 FinalCandidate；需要区分“合法无需工具直接完成”和“未遵守实验要求跳过 Plan/Tool”，评测报告不得把两者混为工具能力证明。
- 候选配置按真实调用路径计算预算。例如显式 Plan→ToolCall→错误 Candidate→Replan→修订 Candidate 至少需要 5 次模型调用，配置调整需同时检查 max_steps、max_calls、deadline 与全运行预算。不能仅把一个上限调大。
- 以实际 token 估算预算，纳入每轮 Observation、修复反馈和完整 Schema；保留 MUST、权限、稳定 ID 与上游版本。
- 工具失败保持稳定分类：超时、配额、参数错误、权限错误分别记录。允许受限修复的错误回灌模型；鉴权、fencing 和预算拒绝不可让模型绕过。
- 复用现有 Tool Gateway、幂等、引用校验和步骤 Trace；通过新版本及必要的新迁移更新候选，不修改已发布历史 SQL。

验收：

1. 使用与候选相同的冻结预算，覆盖工具成功和至少一次验证失败后修复成功的路径，不能仅在宽松测试配置下通过。
2. 两条正式运行 Trace 分别展示工具调用闭环、返工闭环；每步关联模型/工具台账及 Artifact。关键步骤缺失时明确判不满足该场景目标。
3. 超预算、无进展、非法工具、非法阶段和取消后的迟到结果均确定性停止，保留现有门禁。
4. 记录失败的 provider 响应类型和停止原因，不能用 fixture 指定动作冒充 live 模型自主选择。

### INT-P0-02：先保证评测结论可信

关联来源：S1、S4。`evaluate_release_gate` 的局部构造验证已暴露三类边界，不等同于已证明线上发布服务被绕过：

- A/D 的 blocking_issue_median 同为 0 时，`0 <= 0 * 0.8` 成立，可能把无改善认作质量改善。
- 函数只检查汇总指标是否 MEASURED，没有在该函数中拒绝 fixture、空 case_results 或验证对应数据集/运行证据。
- 基线 cost 为 0 时跳过比例检查，候选正成本可能不受该项约束；候选种子价格也为 0，需要区分免费与未配置。

实施与验收：

1. 先新增最小门禁回归：无收益不得按“减少 20% 问题”晋级；空 case_results、失败/未执行 run、fixture 结果不得作为 live 晋级证据。
2. A/D 必须属于同一冻结评测集、相同测试划分和可比较环境，逐例 Run/Trace/Bundle/模型版本可追溯；校验缺失值、重复指标、非有限数和汇总值与逐例值一致性。
3. baseline=0 的指标显式采用预声明绝对预算或 NOT_EVALUATED；未配置价格记 UNAVAILABLE，不能输出“零成本”。价格快照标明来源、日期、币种和缓存计费口径。
4. 安全指标区分“越权尝试被拒绝”和“越权执行成功”：对抗集前者可能是预期现象，后者必须为 0，不能让安全拒绝被错误算为产品故障。
5. 现有提升 8 个百分点 / 问题中位数降低 20%、成本及 P95 比例 1.25 是代码中的候选门槛，先修正定义与零基线问题。小样本不足时输出证据不足，不为了晋级事后放宽门槛。

### INT-P0-03：正式入口评测与消融

关联来源：S1、S3、S4、S5。复用现有 AutoSpecCase、AutoSpecEvalRun 与 ablation runner，新增部署适配器，经 `POST /api/workflow-runs` 创建运行并从正式 Trace/Artifact 接口收集事实。

实验设计：

- 先用现有 8 类场景打通 smoke，再扩充到 24 个独立项目需求：16 个开发集、8 个保留测试集。按项目模板/领域分组隔离，防止同一模板改名后进入另一集合。
- 标注 MUST 与不可接受失败、API/数据/UI/验收证据；补充缺信息、冲突约束、近义词、旧版本引用及应拒绝工具的场景。
- 独立保留人工参考标注，不把答案或期望 API 表作为普通 RAG 知识注入；记录样本由谁生成、谁复核，无法双人复核时如实注明。
- 每组每例先重复 3 次：24×4×3=288 次工作流，是建议规模，执行前按 smoke 实测估算费用并配置硬预算。预算不足时缩小为探索性实验，报告明确样本量。

| 组别 | Loop | 工具 | Replan | 解释 |
|---|---|---|---|---|
| A | 关 | 关 | 关 | 原有 single-shot 基线 |
| B | 开 | 关 | 开 | 当前代码中的无工具 Loop |
| C | 开 | 开 | 关 | 带工具但不返工 |
| D | 开 | 开 | 开 | 完整循环 |

现有 B 同时含循环和返工，不能用 A→B 宣称“纯规划收益”。B↔D 用于观察工具影响，C↔D 用于观察返工影响；需要单独归因规划时再增加 B0（Loop 开、工具关、Replan 关）。四组实验不能改变六节点产品能力。

固定数据集、输入 Artifact、模型、Prompt 基础规则、检索语料和缓存策略；候选组的区别写入可追溯实验配置，不向正式请求开放任意 policy 覆盖。保留现有正式 v5；在隔离测试环境按受控发布流程准备实验版本，不能为跑评测开放 DRAFT 执行后门。

指标与完成标准：

- 硬质量：Schema 通过率、逐例 MUST 覆盖率、跨 Artifact 冲突、HIGH/CRITICAL 问题、引用正确性、交付门禁通过率。
- 软质量：PRD 清晰度、架构可实施性、验收可测试性；用统一 rubric 做盲评，模型裁判只提供辅助分，记录与人工意见的分歧。
- 执行质量：工具参数有效率、必要工具使用率、无效重复率、返工修复率、各停止原因占比。
- 成本和时延：逐次 token、实际价格快照估算成本、每成功交付成本、P50/P95，失败和重试成本计入总成本。单次请求只报告 duration，不给单例伪造 P95。
- 输出全量逐例结果、成对差值、重复运行波动与置信区间；小样本尾延迟标注不稳定。至少保留 3 个失败案例及根因，不只展示最好结果。
- 只有 P0-02 门禁通过且证据完整才考虑发布；无收益可保留 single-shot，不把扩大自治视为必然成功。

## 6. P1 实施与验收

### INT-P1-01：RAG 从能检索到能证明收益

关联来源：S1、S2、S5。复用项目隔离、混合检索、RRF、引用与 corpus epoch。

实施内容：以 PRD feature、API、table、architecture section 为边界分块，保留 JSON path、稳定 ID、父段落和 Artifact/version。将现有哈希向量保留为 baseline，添加可替换语义 Embedding Provider，索引记录 provider/model/dimension/version；不混用新旧向量空间。候选重排只处理已通过 ACL 的 Top-N。

先构建 60 个人工复核检索问题，按项目分成 40 个开发、20 个保留测试问题，标注相关 chunk；覆盖近义表达、跨 Artifact 联合证据、版本更新和无答案。测试集规模是本计划建议值，不是现有事实。

验收：对比当前检索、结构化分块、语义向量、增加重排四个配置，报告 Recall@5、MRR@10、nDCG@5、无答案误引用率、检索 P95 与成本；同时观察最终 Artifact 质量。删改/撤权后的新运行不得命中过期证据；历史回放按当时快照及当前访问权限展示。先测 1k/10k chunk 检索曲线，再决定是否引入向量数据库，避免把新数据库当成果。

### INT-P1-02：Project Memory 与上下文保真

关联来源：S1、S2、S3。记忆只覆盖 AutoSpec 的项目决策、约束、术语、人工纠错和未解决问题，不把旧 skill/resume/question 语义直接接进生产。

实施内容：MySQL 保存 project_id、事实键值、来源 Artifact/审批记录、版本、有效状态、替代关系与撤销信息；提取结果先为候选，用户批准和冻结约束优先级明确。检索视图可重建，不能成为第二份事实源。原始证据不因摘要被覆盖，支持受限按需取回。

验收：覆盖至少 12 条项目场景，包括需求变更、互相冲突的偏好、人工纠错、重复提取、撤销、跨项目同名实体、重启恢复和长上下文压缩。权限、MUST、稳定 ID、来源和未解决问题在测试中全部保留；同时记录压缩前后 token、关键事实丢失率与任务完成率。缓存随事实版本/权限变化失效。

### INT-P1-03：可靠性、边界与成本路由证据

关联来源：S1、S5、S6。在隔离 Compose 项目和独立数据卷执行，禁止把当前开发数据当故障靶场。

实施与验收：

- 故障注入覆盖 Outbox 发送后确认丢失、重复 terminal event、Worker 执行中退出、Redis 短暂断连、provider 超时/限流、用户取消、工具超时、索引更新。记录故障时刻、检测时刻、恢复时刻及 Run/Trace ID。
- 验证未授权写入、重复 Artifact 投影、过期 fencing 写入均为 0；分别报告系统自动恢复与需人工恢复，不把人工修复计为自动恢复。
- 使用 fixture 测基础设施并发 1/5/10/20，预算允许时对 live 做 1/3/5 的有限采样；瓶颈未定位前不机械冲到 50。报告队列等待、节点时延、端到端 P50/P95、吞吐、CPU/内存、SQL/Redis 负载和样本数。
- 抽测文档与工具结果中的提示注入、跨项目引用、超大响应及缓存权限变化；复用 Gateway 拒绝能力与引用门禁，记录正常样本误拒率。
- 对 Fast/Balanced/Deep 做同集对照；从台账核对输入、输出、缓存 token 与重试/fallback，展示质量—成本—时延取舍，不预先承诺节省比例。

### INT-P1-04：把工程结果变成可检查的面试证据

关联来源：S3、S4、S6。交付物由证据驱动，不提前编写虚构指标。

- 建立 5 分钟 Demo：输入带权限与审批约束的需求→六节点运行→受控查契约→发现缺失→定向返工→Evaluator→导出；准备固定案例回放和明确标记的失败回放。
- 对至少 3 个不同领域的实际导出 ZIP，在隔离容器中执行 Maven/Vite 构建和最小路由/权限 smoke；记录 artifact hash、构建镜像、依赖版本及结果。构建成功与完整业务实现分开报告。
- 若需自动执行生成代码，使用资源与时间上限、受限网络和独立临时目录，不挂载主机敏感目录或 Docker socket；作为已有交付验证扩展，不新增无限制 shell 工具。
- 建立“问题→原方案→失败证据→修复→复测→代价”的 3 张案例卡，并附源代码入口、实验结果与 Trace。
- 修正文档与代码漂移：Loop 已实现但旧计划仍写 single-shot、候选状态、DAG 前后端依赖和不同评测集数量分别说明。保留历史验收记录，不把旧记录重写为本次结果。

证据包应能回答以下问题：

| 面试追问 | 应提供的项目证据 |
|---|---|
| 为什么采用六角色流程？哪里需要模型决策？ | 冻结 DAG、Backend Loop Trace、single-shot 对照及复杂度代价 |
| 为什么自研控制面，没直接改成现成框架？ | MySQL 事实源、Outbox/幂等、审批与版本不可变的具体需求，以及维护成本 |
| 工具调用是模型决定还是程序写死？ | live 模型动作、参数校验、允许列表与真实 Gateway 台账 |
| 规划文档没有标准答案，怎么评估？ | 确定性覆盖门禁、人工 rubric、未见测试集与失败样本 |
| RAG 做了哪些改进？ | chunk 标注、召回与重排消融、最终产物质量和成本 |
| 长上下文压缩后怎么找回约束？ | 原始证据引用、事实版本、压缩 manifest 与召回演示 |
| Worker 重复执行或取消后返回怎么办？ | 故障时间线、fencing/幂等记录与 Artifact 数量校验 |
| 用更贵模型是否值得？ | 同集路由实验、每成功交付成本、质量及 P95 |
| AI 辅助写了多少，自己负责什么？ | 可解释的设计决策、边界回归和个人修复案例；不编造手写比例 |

## 7. 暂缓事项与产品约束

MCP 仅在出现真实外部工具接入需求时作为 P2；优先给现有 Gateway 增加薄适配层，沿用权限、版本、超时、预算与台账。能解释 MCP 与模型工具选择的关系，比只有一个空 Server 更有价值。

本轮不安排新增 Agent 角色、重写控制面、切换框架、引入 GraphRAG/多模态/LoRA/RL、无限制代码执行或自动生产发布。保留六节点、`POST /api/workflow-runs`、Redis Worker、Reviewer 双层审查及 Evaluator 硬门禁。不得恢复旧 `/generate*`，不修改历史 Flyway；新发布版本仍须经过不可变发布与验证流程。

## 8. 与已有计划的衔接

- `docs/archive/runtime-optimization-plan.md`：继续保留其 Runtime/Control Plane 建设历史；本计划 P1-03 接续其中尚缺的实测证据。
- `docs/archive/agent-execution-plan.md`：AEX-P0-01/02/03 已有部分实现，后续执行先按当前代码核验状态；INT-P0-01 负责收口，不重新实现。INT-P0-03 接续 Live Eval，P1 的 Memory/RAG/证据工作沿用同一目标。
- 新计划增加的重点是 Prompt/预算可达性、晋级门禁误判、评测数据隔离、消融归因及面试证据。
- 本次不覆盖以上已有文件。后续开始实施时统一任务状态，避免两份计划分别重复开发同一能力。

## 9. 制定计划时的核验与第一批待办（2026-09-14）

本次实际执行：

| 核验 | 结果 | 能证明什么 |
|---|---|---|
| Agent：`python -m pytest -q tests/test_agent_loop.py tests/test_autospec_evaluation.py`，使用仓库指定 Python | 5 passed，0.45s | 现有两文件定向回归通过，不代表 live 循环可用 |
| `python scripts/verify_workflow_contract.py`，使用仓库指定 Python | `autospec-v5 workflow contracts are synchronized` | 当前契约同步通过，不代表预算可达性已校验 |
| 内存构造 A/D：相同质量、问题中位数 0、FIXTURE_BASELINE、空 case_results，汇总指标均 MEASURED | `evaluate_release_gate` 返回 PROMOTE | 局部晋级函数存在证据检查与零问题边界缺口 |
| 上一构造再令 A cost=0、D cost=100 | 仍返回 PROMOTE | 零基线绕过成本比例检查；100 为合成测试数值，不是真实成本 |

本次未读取模型密钥、未发起付费模型评测、未启动/停止服务、未修改业务代码、未做全量三端回归。计划中的样本规模、工作日和验收目标都是拟议值。

第一批执行顺序：

- [x] 为 INT-P0-02 的三类门禁边界加最小回归并修复判定。
- [x] 为候选 Loop 建立与真实 policy 一致的预算/阶段回归，确认 Prompt 和工具参数 Schema 输入。
- [x] 新增版本化 Prompt 与候选契约；新增必要迁移，同步消费者、文档及契约测试。
- [ ] 接通正式 API 适配器，用 8 类现有案例先形成逐例证据，再扩大为冻结测试集。
- [ ] 依据结果决定是否发布候选，以及首先投入 RAG、Memory 或可靠性优化。

## 10. 执行状态（2026-09-17）

详细变更、验证命令、采集器与独立 rubric 使用方法见 [P0 执行记录](agent-execution-record-2026-09-17.md)。

| 项目 | 当前状态 | 证据与剩余工作 |
|---|---|---|
| INT-P0-01 | 实现与自动回归完成，四组单例 fixture 已复测 | v2 Prompt、7 次预算、阶段协议、参数修复、失败候选/观察保留、真实调用引用；补齐 fixture 台账与工具网关冻结策略解析。V100 固定模型思考模式，V101 新增 PM Schema Prompt 诊断契约；历史版本及正式六节点不变，live Loop 尚未到达 |
| INT-P0-02 | 门禁实现与回归完成 | 逐例重算、live/holdout/独立 rubric、重复样本、价格、零基线边界；不宣称候选已满足晋级要求 |
| INT-P0-03 | 部分完成，已取得 live 失败证据 | 正式 API 采集器、24 例 16/8 切分和外部评审导入已实现；完成四组单例 fixture、四组单例 live 及一次 D 诊断。5 次 live 均止于 Product Manager；费用上界估算 0.14883472 元、10 元保守预留已满。发送授权已解决；新 PM Prompt 尚未 live 复测，人工 rubric、重复实验和置信区间未完成 |
| INT-P1-01～04 | 待推进 | 仍需检索标注、Project Memory 实现与场景验证、故障/容量实测和导出证据；不计入本次已完成项 |
| P2 MCP | 继续暂缓 | 尚无需要新增适配层的真实外部接入需求 |

历史核验数字仅表示制定计划时的基线；本次新验证结果独立保留，避免将 fixture、自评或未执行指标写成 live 收益。

最新状态：隔离 MySQL 已迁移至 V101，六个服务健康，Agent API 与两个 Worker 已恢复 fixture。四组旧 fixture 经过六节点且 C/D 工具成功；新 v6-D / Run 16 的前五节点和工具调用也成功，Evaluator 继续按质量规则拦截，Schema 错误为 0。live 的空响应/输出预算和 PRD 验收标准层级问题已形成失败复盘，并新增模式配置、版本化 Schema Prompt 和错误分类修复。9 月 17 日回归为后端 203 项、Agent 147 项、前端 24 项及构建通过。脱敏 [live 证据](examples/agent-eval-live-smoke-2026-09-17.json) 与 [P0 执行记录](agent-execution-record-2026-09-17.md) 保留费用和限制；未获得 live Loop 收益或候选晋级结论，也未宣称完整计划完成。
