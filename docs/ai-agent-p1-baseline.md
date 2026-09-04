# AutoSpec V5 P1 实施基线

更新时间：2026-09-04

本基线对应 `docs/AI-Agent项目优化计划.md` 中的 AGT-P1-01、AGT-P1-02 和 AGT-P1-03。实现继续使用 AutoSpec V5 的六节点冻结 WorkflowSpec；没有新增独立的面试流水线，也没有改变正式入口 `POST /api/workflow-runs`。

## P1-01 Hybrid RAG

- `knowledge_document` 增加 `corpus_type` 和 `expires_at`，Resume、Question、Rubric 与普通项目 Artifact 通过逻辑语料边界分开；旧 Artifact 默认迁移为 `PROJECT_ARTIFACT`。
- 后端检索按项目成员授权、`ACTIVE` 状态和未过期条件过滤；不授权时返回空结果，不把跨项目或已过期文档送入排序。
- 检索链路为 Query Rewrite → BM25 风格词法召回 + 本地 Embedding 召回 → RRF → Top N → 确定性 Rerank → Top K，并保留 Chunker、Embedding、Retriever、Query Rewrite、Reranker 和访问策略版本。
- 生成输入中的 `retrieval_trace` 记录过滤口径、命中数和空召回标记；`retrieved_sources` 记录语料、Chunk、Artifact/Chunk 内容哈希和相关性分数。
- Agent Engine 的 `/evaluation/retrieval` 独立计算 Recall@K、MRR、nDCG 和 Rerank Hit Rate，并覆盖空召回、错误召回、过期和越权降级。它不把生成文本质量混入检索结果。

## P1-02 可观测性深化

- `GET /api/workflow-runs/{runId}/metrics` 增加节点级尝试次数、成功/失败/重试、排队和执行 P50/P95、Token、成本、模型/工具调用及路由分布。
- 同一接口增加 Model、Prompt、Tool、Retriever 维度切片，便于比较版本和定位成本/时延变化。
- `GET /api/workflow-runs/{runId}/trace` 返回节点、调用元数据、失败聚类和离线失败 Case；接口沿用项目 OWNER/EDITOR/VIEWER 访问控制，并且不返回节点原始输入、输出或错误消息全文。
- 离线失败 Case 使用稳定的 Workflow/Node 标识、处理器、模型/Prompt/路由和错误码，便于复制到评测集；`WORKFLOW_TRACE_RETENTION_DAYS` 提供部署级保留期口径，事实数据不由 Trace 接口自动删除，避免影响恢复/回放。

## P1-03 并发与恢复

- Spring Boot 入口在创建运行前检查 Outbox、Worker backlog 与按发起用户的运行中上限，超限返回 Retry-After 语义并记录拒绝指标。
- Worker Runner 支持 Redis Stream 批量拉取后的受控并发；NodeExecutor 使用 FIFO 全局 LLM Semaphore，并按内部发起用户标识隔离同一用户的节点执行。
- 现有 Consumer Group、XAUTOCLAIM、心跳、Retry/DLQ、执行台账、fencing token、重复消息幂等和 Checkpoint/Resume 继续作为正式恢复链路。
- 真实容量、P95、恢复率和成本不在代码或文档中预填；按 `docs/p1-capacity-and-recovery-report.md` 的口径在目标部署环境采集。

## 边界说明

P1 使用确定性本地 Embedding 和 Rerank 作为当前仓库的可复现实现，后续可以替换向量提供方而不改变检索结果/Trace 协议。没有为了“看起来完整”引入新的向量数据库、分布式限流服务或自动清理任务。
