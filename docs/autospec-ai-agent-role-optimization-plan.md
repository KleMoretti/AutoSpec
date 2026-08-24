# AutoSpec AI Agent 岗位优化计划

> 创建日期：2026-07-16
> 适用方向：AI Agent 开发、大模型应用开发、LLM Engineering、AI 工程化
> 目标：把 AutoSpec 从“具备多 Agent 编排能力的工作流平台”升级为“有真实模型、真实检索、真实工具调用和量化评测闭环的 Agent 系统”。

## 1. 岗位定位

AutoSpec 当前最有价值的基础不是 Agent 数量，而是已经具备可解释、可恢复的多 Agent 软件工程工作流：

- V5 使用版本化 DAG、运行快照、审批、定向返工、恢复和回放管理长任务。
- Agent 输入输出通过 Pydantic Schema 约束，而不是只保存自由文本。
- Spring Boot 记录 Workflow、Artifact、Agent Task、模型调用、审计事件和运行指标。
- Redis Streams 承载至少一次投递，Python Worker 使用消费组执行节点。
- Reviewer 和 Evaluator 已有规则检查及结构化报告基础。

面向 AI Agent 岗位时，项目的核心叙事应调整为：

> 设计并实现一个面向软件需求工程的生产型多 Agent 系统，通过受约束 DAG 管理规划、执行、审批、返工和恢复；支持真实模型路由、混合 RAG、类型化工具调用、全链路观测以及可复现的离线评测。

## 2. 面经信号

近期公开面经对 AI Agent / 大模型应用开发的考察集中在：

- RAG 全流程、文档分块、向量与关键词混合召回、Re-rank/RRF、知识时效性。
- Agent 的 Planning、Memory、Tool Use、ReAct/Plan-Execute、循环终止和失败恢复。
- Function Calling、MCP、Skill、工具参数校验和高风险操作审批。
- 模型选择、Prompt 版本、调用失败降级、Token 成本与延迟优化。
- Agent 评测体系、自动化回归、幻觉、引用忠实度和线上 Bad Case 闭环。
- Python、网络、数据库、队列、并发等工程基础，而不只是框架 API。

参考：

- [春招五周 Agent / AI 方向高频总结](https://www.nowcoder.com/discuss/869231276035760128)
- [腾讯、百度大模型与 Agent 面经总结](https://www.nowcoder.com/discuss/878600528970735616)
- [初创公司 Agent 面经](https://www.nowcoder.com/discuss/874350322167136256)
- [2026 Java 后端与 AI 工程化面经](https://www.nowcoder.com/discuss/864594486704291840)

## 3. 当前差距

| 维度 | 当前实现 | 主要差距 |
| --- | --- | --- |
| 模型调用 | 定义了 `ModelClient` 协议、模型配置和调用记录 | Production Handler 没有注入真实模型客户端，默认结果仍来自 deterministic fixture |
| RAG | 已有知识文档、知识分块、权限过滤和来源注入 | 固定字符切块加词项重合评分，不包含 Embedding、混合召回、重排和检索评测 |
| Agent 工具 | Agent 按角色生成结构化 Artifact | 没有 Tool Registry、Function Calling、工具循环、步骤预算和工具级权限 |
| 工作流 | 已有版本化动态 DAG、条件、审批、重试和返工 | 图结构主要由平台配置，尚无受约束的 Planner Agent 自动生成 WorkflowSpec |
| 评测 | 有规则型 Evaluator 和实验对比结构 | 规则较浅，缺少固定数据集、RAG 指标、LLM-as-Judge、人评校准和 CI 回归门禁 |
| 模型治理 | 已有 Provider、Config、Invocation、Prompt Version 数据模型 | 缺少真正执行的模型路由、降级、并发配额和成本预算策略 |
| 上下文 | 节点输入包含上游 Artifact 和检索来源 | 缺少 Token Budget、摘要、按需加载、上下文压缩和长期记忆策略 |
| 安全 | 有项目权限和人工审批 | 缺少 Prompt Injection、防越权工具调用、敏感信息检测和模型输出安全测试集 |

关键现状证据：

- `agent-engine/graph/workflow.py` 的默认执行记录使用 `local / deterministic-fixture`。
- `agent-engine/agents/product_manager.py` 在没有模型客户端时返回固定示例 Artifact。
- `backend/src/main/java/com/autospec/service/KnowledgeIndexService.java` 使用固定长度切块和词项重合评分。
- `agent-engine/review/evaluator.py` 的部分覆盖检查只提取少量固定关键词。

## 4. 优化工作项

### AI-01 真实模型网关

**优先级：P0**

目标是让所有生产节点实际调用模型，同时保持测试环境可使用 deterministic fixture。

交付内容：

- 实现统一 `ModelGateway`，兼容 OpenAI-compatible API。
- 支持 JSON Schema/Structured Output、调用超时、有限重试和错误分类。
- 将模型客户端注入 Product Manager、Architect、Backend Engineer、Frontend Engineer 和 Reviewer。
- 记录 provider、model、prompt version、输入/输出 Token、费用、耗时、状态和错误。
- 区分可重试错误、不可重试错误、Schema 错误和内容安全错误。
- 保留 Fake Model Client，确保单元测试不依赖外部网络。

验收标准：

- 同一组需求可以分别使用至少两个模型执行。
- 每次真实调用都有完整的 ModelInvocation 和 Trace 记录。
- Schema 错误可以自动修复一次，仍失败则进入明确失败状态。
- 模型超时或限流时可按策略降级，不产生重复 Artifact。
- 提供一份模型成功率、P95 延迟、平均 Token 和平均费用对比报告。

### AI-02 混合 RAG 与引用闭环

**优先级：P0**

目标是将当前关键词复用升级为可评测的历史项目知识检索。

交付内容：

- 按 Markdown 标题、段落和结构化 Artifact 字段进行语义切块，保留少量重叠。
- 为 KnowledgeChunk 生成 Embedding，并保存向量索引引用、模型版本和内容校验和。
- 实现向量召回与 BM25/关键词召回。
- 使用 RRF 融合结果，并增加可配置 Re-ranker。
- 过滤未批准 Artifact、无权限项目、过期版本和已删除知识。
- 支持索引更新、删除、重建和 Embedding 模型迁移。
- Agent 输出必须携带 source artifact、chunk、version 和引用位置。

验收标准：

- 建立不少于 50 条检索 Query 的标注集。
- 记录 Recall@5、MRR、nDCG@5、无结果率和检索延迟。
- 对比关键词检索、向量检索、混合检索和混合加重排四种方案。
- Reviewer 能发现无来源引用、错误引用和过期引用。
- 项目权限变更后，不可访问知识不会出现在召回结果中。

### AI-03 类型化工具调用与 Agent 循环

**优先级：P0**

目标是让 Agent 能通过工具获得外部能力，而不是只依赖一次模型生成。

第一批工具：

- `search_knowledge`：检索批准的历史 Artifact。
- `read_openapi_contract`：读取和查询 AutoSpec OpenAPI。
- `inspect_project_artifacts`：读取当前项目的结构化产物。
- `generate_code_skeleton`：触发受控代码骨架生成。
- `run_build_validation`：运行生成项目的离线构建或静态检查。
- `submit_review_issue`：将明确问题写入 Review Report。

运行要求：

- 工具参数使用 Pydantic Schema 校验。
- 每个工具声明权限、超时、幂等性、风险等级和返回 Schema。
- Agent 使用 Plan-Execute 或受限 ReAct 循环。
- 设置最大步骤数、Token 预算、工具调用预算和明确终止条件。
- 写操作和高风险操作经过 Human-in-the-loop 审批。
- 工具错误作为结构化 Observation 返回，允许有限自我修复。

验收标准：

- 每次工具调用记录参数摘要、结果摘要、耗时、状态和错误。
- 非法参数不会到达真实工具。
- 达到步骤或费用上限后任务可解释地终止。
- 重复执行幂等工具不会产生重复副作用。
- 建立工具选择正确率和工具调用成功率指标。

### AI-04 Agent 评测与回归门禁

**优先级：P0**

目标是回答“Agent 效果如何证明”和“改 Prompt 后是否退化”。

数据集：

- 建立 30～50 个固定需求案例。
- 覆盖常规需求、权限需求、跨 Artifact 一致性、RAG、歧义需求、超长需求和恶意输入。
- 为关键案例保存期望能力、必需 API、必需页面、权限约束和参考来源。

评测维度：

- Schema Validity。
- Requirement Coverage。
- Cross-artifact Consistency。
- Permission Coverage。
- RAG Recall 与 Citation Faithfulness。
- Tool Selection Accuracy。
- Task Completion Rate。
- Runtime Reliability。
- 平均成本、P95 延迟、平均重试次数。
- 人工可用性评分。

评测策略：

- 确定性规则负责协议、权限、引用、API 和状态检查。
- LLM-as-Judge 使用固定 Rubric，输出结构化理由和证据。
- 使用小规模人工双人评分校准 Judge。
- 对 Prompt、模型、检索策略和 Workflow Version 做成对比较。
- 在 CI 中设置关键指标退化阈值。

验收标准：

- 评测命令可在本地重复运行。
- 相同 fixture 的确定性指标结果稳定。
- 每次 Prompt/模型版本变更可生成差异报告。
- 至少维护 10 个真实 Bad Case 及修复前后证据。
- 关键指标超过允许退化阈值时 CI 失败。

### AI-05 模型路由、降级与成本治理

**优先级：P1**

交付内容：

- 根据节点类型、任务复杂度和预算选择模型。
- 为格式修复、摘要、评审和主生成配置不同模型等级。
- 实现限流、熔断、并发配额和 Provider 降级。
- 为 Workflow Run 设置最大 Token、最大费用和最大调用次数。
- 对比高质量模型、低成本模型和混合路由策略。

验收标准：

- 路由决策可解释并持久化。
- Provider 故障时不会无限重试。
- 超过运行预算后停止后续模型调用。
- 混合路由相对单模型方案有可量化的成本或质量收益。

### AI-06 上下文与记忆治理

**优先级：P1**

交付内容：

- 定义节点级 Context Policy。
- 上游 Artifact 按字段和任务按需装载，避免全量传递。
- 对历史事件和旧轮次做结构化摘要。
- 保留系统约束、用户目标、最近错误、来源和待办状态。
- 记录每段上下文的来源、Token 占用和裁剪原因。

验收标准：

- 超长项目不会因为上下文溢出直接失败。
- 上下文压缩前后质量差异可评测。
- 被裁剪的重要约束能够被 Reviewer 检出。

### AI-07 Agent 安全

**优先级：P1**

交付内容：

- 建立 Prompt Injection 和越权工具调用测试集。
- 系统指令、检索内容和用户输入采用明确的数据边界。
- 检索内容不得覆盖系统权限和工具策略。
- 工具执行前重新做后端权限检查。
- 对输入、模型输出、日志和导出内容做敏感信息检测。

验收标准：

- 恶意知识文档不能触发未授权工具。
- Viewer 无法通过 Agent 间接执行 Editor/Owner 操作。
- 安全测试进入自动化回归。

### AI-08 受约束的 Planner Agent

**优先级：P2**

目标不是让模型任意生成代码级工作流，而是在平台允许的节点、Schema 和策略范围内生成 WorkflowSpec 草稿。

交付内容：

- Planner 根据需求选择节点、依赖、审批点和重试策略。
- 生成结果必须通过 DAG 编译、Schema、循环、权限和预算校验。
- 发布前由用户确认 Workflow Version。
- 保存规划理由、验证错误和人工修改差异。

验收标准：

- 至少三类需求生成不同但合法的 DAG。
- 非法循环、未知 Handler、错误 Schema 和超预算配置会被拒绝。
- Planner 输出不能直接绕过审批发布。

## 5. 实施顺序

| 里程碑 | 工作项 | 退出条件 |
| --- | --- | --- |
| M1 真实智能基础 | AI-01 | 所有生产 Agent 节点可真实调用模型，调用信息完整可追踪 |
| M2 知识与行动 | AI-02、AI-03 | Agent 可以检索历史知识、引用来源并安全调用工具 |
| M3 效果证明 | AI-04、AI-05 | 有固定数据集、回归门禁和模型/Prompt/检索对比报告 |
| M4 长任务治理 | AI-06、AI-07 | 长上下文、安全攻击和工具越权都有自动化回归 |
| M5 动态规划 | AI-08 | Planner 能生成受约束且需审批的 WorkflowSpec |

推荐实际开发顺序：

1. 先实现真实模型网关，不继续扩展 Agent 数量。
2. 建立第一版评测集，作为后续改造基线。
3. 实现混合 RAG，并用标注集证明收益。
4. 增加少量但真实的工具及受限执行循环。
5. 再做模型路由、上下文、安全和动态规划。

## 6. 面试与简历证据

完成 P0 后，项目必须能提供：

- 一张真实模型调用的端到端 Trace。
- 一份关键词、向量、混合和重排检索对比报告。
- 一份 Prompt/模型/Workflow Version 的评测差异报告。
- 一个工具调用失败后参数修复或安全终止的案例。
- 一个 Worker 中断、任务恢复且不重复生成 Artifact 的案例。
- 一份脱敏的真实 Artifact、引用来源和 Review Report。

建议简历表述：

> 设计并实现 AutoSpec 多 Agent 需求工程平台，使用版本化 DAG 编排规划、生成、审批、返工与恢复；接入真实模型网关、混合 RAG 和类型化工具调用，建立覆盖需求一致性、引用忠实度、任务完成率、成本与延迟的离线评测集，并通过 Prompt/模型/工作流版本回归门禁控制质量退化。

## 7. 非目标

- 不为增加角色数量而继续堆 Agent。
- 不在缺少基准数据时宣称某模型或 Prompt 效果更好。
- 不把关键词重合检索描述成向量 RAG。
- 不把固定 DAG 描述成完全自主规划。
- 不优先投入模型微调；AutoSpec 当前更需要应用层检索、工具、评测和可靠性。
- 不保存真实 API Key、Token、数据库密码或未脱敏模型输入输出。

## 8. Definition of Done

AI Agent 方向优化完成至少需要满足：

- 生产模式默认使用真实模型，fixture 只用于测试或演示降级。
- RAG 具备向量与关键词混合召回、重排、权限过滤和引用。
- 至少四个真实工具通过统一协议被 Agent 调用。
- 每个 Agent 循环都有步骤、Token、费用和时间上限。
- 固定评测集可以重复运行，并阻止关键质量指标明显退化。
- 模型、Prompt、检索、工具和 Workflow Version 均可追踪。
- 对长上下文、模型超时、工具失败、Prompt Injection 和越权有回归测试。
