# AutoSpec 后端岗位优化计划

> 创建日期：2026-07-16
> 适用方向：Java 后端开发、分布式系统、平台工程、AI 工程化后端
> 目标：把 AutoSpec 从“功能完整的 Spring Boot 编排后端”升级为“经过真实中间件验证、压测、故障演练和生产观测的可靠异步系统”。

## 1. 岗位定位

AutoSpec 已经具备比普通 CRUD 项目更好的后端基础：

- Spring Boot 负责项目、Artifact、Workflow、审批、模型治理、审计和导出。
- V5 使用不可变 Workflow Version、运行快照和 DAG 状态机。
- MySQL 是运行、节点、尝试、Artifact、审批和恢复检查点的事实来源。
- 事务 Outbox 保证数据库状态与异步命令发布之间的可靠衔接。
- Redis Streams 使用消费组传输节点命令和执行事件。
- 消费端具备幂等处理和 XAUTOCLAIM 待处理消息接管基础。
- 系统支持失败重试、超时、取消、恢复、回放和 Reviewer 定向返工。
- 已有项目级 Owner/Editor/Viewer 权限、审计、Flyway 和 OpenAPI 契约。

面向后端岗位时，项目的核心叙事应调整为：

> 设计并实现一个面向长耗时 AI 任务的可靠异步编排平台，使用 MySQL 状态机、事务 Outbox、Redis Streams 至少一次投递和幂等消费者保证任务一致性，并通过真实集成测试、压测、链路追踪和故障注入验证恢复能力。

## 2. 面经信号

近期公开后端面经和技术专题集中考察：

- Java 集合、JVM、线程池、锁、并发安全和网络基础。
- MySQL 索引、事务隔离、锁、慢 SQL、深分页和分库分表后的查询。
- Redis 数据结构、Lua 原子操作、分布式锁、缓存一致性和热点问题。
- 消息队列选型、重复消费、幂等、重试、死信队列和最终一致性。
- 系统设计中的限流、熔断、降级、异步削峰和故障恢复。
- 项目的真实 QPS、P95/P99、瓶颈、压测方法和优化前后数据。
- AI 工程化后端中的模型调用稳定性、成本、Prompt 版本和数据安全。

参考：

- [2026 Java 后端开发面试真题汇总](https://www.nowcoder.com/discuss/864594486704291840)
- [字节跳动 Java 后端二面面经](https://www.nowcoder.com/discuss/860815475284955136)
- [腾讯、百度 Agent 与后端交叉面经](https://www.nowcoder.com/discuss/878600528970735616)
- [力扣后端高性能专题](https://leetcode.cn/discuss/post/zSXn1g/)
- [力扣消息队列设计专题](https://leetcode.cn/discuss/post/62179/ru-he-cong-ling-dao-yi-she-ji-yi-ge-mqxiao-xi-dui-)

## 3. 当前差距

| 维度 | 当前实现 | 主要差距 |
| --- | --- | --- |
| 数据库测试 | H2、Flyway Schema 检查和较多服务测试 | 缺少 Testcontainers MySQL，无法充分验证真实隔离级别、索引、锁和 SQL 方言 |
| Redis 测试 | Redis 客户端封装、Mock 测试和 Worker 单测 | 缺少真实消费组、Pending、XAUTOCLAIM、重复投递和断连恢复集成测试 |
| 性能 | 已建立大量历史查询索引和分页接口 | 缺少压测脚本、基准数据、P95/P99、连接池、慢 SQL 和优化前后对比 |
| 可观测性 | 数据库中记录诊断、审计、外部调用和模型调用 | 缺少 Actuator、Micrometer、Prometheus、Grafana 和跨 Java/Python Trace |
| 消息可靠性 | Outbox、至少一次投递、幂等消费、恢复和 XAUTOCLAIM | 缺少明确 DLQ、毒消息隔离、退避策略、Stream 裁剪和 Pending 告警 |
| 认证 | BCrypt 密码与随机 Session Token | Session 存在单机内存，重启丢失且无法支持多实例 |
| 大数据分页 | 多数历史接口使用 limit/offset | 深分页性能会随 offset 增大，缺少游标分页和百万级数据验证 |
| 并发控制 | 依赖事务、状态条件和唯一约束 | Artifact 编辑、审批和状态更新缺少统一乐观锁版本与并发冲突协议 |
| 流量治理 | Worker 可并行消费，接口有基本状态保护 | 缺少用户配额、生成接口限流、队列背压和系统过载降级 |
| 故障验证 | 有恢复、超时和失败单元测试 | 缺少 Redis/MySQL/Worker/网络真实故障注入和恢复时间证据 |

关键现状证据：

- `backend/pom.xml` 尚未包含 Actuator、Micrometer Registry、Testcontainers 或故障注入依赖。
- `backend/src/main/java/com/autospec/service/AuthService.java` 使用进程内 `ConcurrentHashMap` 保存 Session。
- `agent-engine/runtime/redis_stream_client.py` 已实现消费组读取和 XAUTOCLAIM，可以继续深化，不需要更换消息中间件。
- `docs/autospec-backend-engineering-depth-plan.md` 中多个工程深化工作项仍为 PARTIAL。

## 4. 优化工作项

### ROLE-BE-01 真实中间件集成测试

**优先级：P0**

目标是用真实 MySQL 和 Redis 验证核心一致性，不再主要依赖 H2 和 Mock。

交付内容：

- 引入 Testcontainers MySQL 和 Redis。
- 建立独立 integration-test Profile。
- 使用真实 Flyway Migration 初始化数据库。
- 验证事务 Outbox 在提交和回滚后的记录状态。
- 验证命令发布、消费组、ACK、Pending 和 XAUTOCLAIM。
- 验证重复命令、重复事件和多 Worker 竞争。
- 验证唯一约束冲突能够映射为稳定的业务响应。
- 在 CI 中单独运行单元测试和容器集成测试。

核心场景：

1. 数据库提交成功、发布前服务退出，Outbox Job 恢复发布。
2. Worker 执行成功、ACK 前退出，消息被重新投递但不产生重复 Artifact。
3. 事件被重复投递，Processed Event 去重生效。
4. 两个 Worker 同时竞争同一消费组，节点只产生一个有效终态。
5. MySQL 事务回滚后，不存在孤立的 Outbox 命令。
6. Redis 重启后，未完成任务可通过数据库状态和 Pending 恢复。

验收标准：

- 核心一致性场景全部使用真实 MySQL 和 Redis。
- 测试可在本地与 CI 重复运行。
- 每个失败场景都断言数据库最终状态，而不只断言 Mock 调用次数。

### ROLE-BE-02 性能基线与压测

**优先级：P0**

目标是能够回答 AutoSpec 的 QPS、延迟、瓶颈和容量边界。

交付内容：

- 使用 k6 或 Gatling 建立可复现压测脚本。
- 准备不同规模的项目、Workflow Run、Node Run、Artifact、事件和模型调用数据。
- 分别测试读接口、写接口和异步工作流。
- 记录 QPS、P50、P95、P99、错误率、CPU、内存、GC、连接池和 Redis Pending。
- 开启 MySQL 慢查询并保存关键 SQL 的 `EXPLAIN ANALYZE`。
- 对比索引、分页、批量查询和缓存优化前后数据。

建议场景：

- 项目列表和项目详情。
- Workflow Run 创建。
- Node Run/Artifact 历史查询。
- 项目 Diagnostics 聚合查询。
- SSE 事件订阅。
- 100、500、1000 个并发 Workflow Run 的队列堆积与恢复。
- 10 万、100 万历史记录下的分页性能。

验收标准：

- 仓库内保存压测脚本、数据准备说明和报告模板。
- 报告明确测试机器、数据规模、并发数和持续时间。
- 至少完成一项有数据支撑的性能优化。
- 为关键接口定义可接受的 P95/P99 和错误率目标。

### ROLE-BE-03 指标、日志与分布式追踪

**优先级：P0**

目标是让数据库诊断记录、运行指标和链路追踪形成统一观测面。

交付内容：

- Spring Boot 接入 Actuator 和 Micrometer。
- 暴露 Prometheus 指标并提供 Grafana Dashboard。
- Python Worker 暴露兼容指标。
- 使用 OpenTelemetry 或等价方案传播 Trace Context。
- Trace 覆盖 Spring Controller、事务、Outbox、Redis Stream、Python Worker、模型调用和结果事件。
- 使用结构化日志记录 traceId、correlationId、workflowRunId、nodeRunId 和 executionId。

核心指标：

- Workflow 创建、成功、失败、取消和恢复数量。
- Node 执行耗时和失败率。
- Outbox 待发布数量、最老消息年龄和发布失败次数。
- Redis Pending 数量、消息空闲时间、重新认领次数和消费延迟。
- Worker 活跃数、并发数和心跳延迟。
- 模型调用成功率、P95、Token 和费用。
- MySQL 连接池使用率、慢查询和事务耗时。

验收标准：

- 可以从一个 Workflow Run 跳转查看完整 Trace。
- Dashboard 可以发现队列堆积、Worker 宕机和模型服务异常。
- 为 Outbox 堆积、Pending 堆积、失败率和 P99 设置告警阈值。

### ROLE-BE-04 Redis Streams 可靠性深化

**优先级：P0**

保留当前 Redis Streams 设计，补齐失败终点和运维策略。

交付内容：

- 定义按错误类型区分的重试策略。
- 使用指数退避和随机抖动，避免集中重试。
- 达到最大尝试次数后写入 DLQ。
- 保存原消息、失败节点、错误类型、尝试次数和最后错误。
- 提供 DLQ 查询、重新投递和人工关闭接口。
- 配置 Stream 最大长度或时间保留策略。
- 监控 Pending Entry List 和 Consumer 空闲状态。
- 对无法解析的毒消息进行隔离，避免 Worker 循环崩溃。

验收标准：

- 业务失败、临时基础设施失败和非法消息有不同处理路径。
- 毒消息不会阻塞正常消息。
- DLQ 消息可以经过审批重新投递且保持幂等。
- Stream 不会无限增长。

### ROLE-BE-05 生产级认证与会话

**优先级：P1**

目标是消除进程内 Session 对重启和多实例的限制。

可选方案：

- 使用 Spring Security + Spring Session Redis。
- 或使用短期 Access Token、可撤销 Refresh Token 和服务端 Token Version。

交付内容：

- 统一认证 Filter 和 Security Context。
- 支持登录、注销、过期、禁用用户和 Token 撤销。
- Session/Token 不再保存在进程内 Map。
- Owner/Editor/Viewer 权限通过统一授权组件执行。
- 增加登录限流、密码错误次数限制和审计。

验收标准：

- 双实例部署时任一实例都能验证会话。
- 服务重启不会导致全部合法会话无条件失效，或失效行为符合明确策略。
- 用户禁用、注销和角色变更可以及时生效。
- 横向越权和垂直越权进入自动化回归。

### ROLE-BE-06 游标分页与并发控制

**优先级：P1**

交付内容：

- Workflow Run、Node Run、Artifact、Agent Event 和 Model Invocation 支持游标分页。
- 游标包含稳定排序字段，例如 `(created_at, id)` 或单调递增 `id`。
- API 明确 `nextCursor`、排序方向和一致性语义。
- Artifact、Workflow Approval 和关键状态实体增加乐观锁版本。
- 并发冲突返回稳定错误码和最新版本信息。

验收标准：

- 百万级数据下首屏和深页延迟保持稳定。
- 新数据写入不会造成明显重复页或漏页。
- 两个用户并发编辑同一 Artifact 时，不会静默覆盖。
- 两次并发审批只产生一个有效决定。

### ROLE-BE-07 限流、配额与背压

**优先级：P1**

目标是防止模型慢调用和突发生成请求拖垮数据库及 Worker。

交付内容：

- 使用 Redis Lua 实现原子滑动窗口或令牌桶。
- 按用户、项目和模型配置并发配额。
- 根据 Stream 长度、Pending 数量和最老消息年龄触发背压。
- 当系统过载时拒绝或延迟非关键请求。
- 为查询、生成、重试、回放和导出设置不同策略。
- 记录限流原因和 Retry-After。

验收标准：

- 并发压测中系统不会因无限入队导致资源耗尽。
- 限流操作在多实例之间保持原子一致。
- 核心查询在生成队列拥堵时仍能维持目标延迟。

### ROLE-BE-08 故障注入与恢复演练

**优先级：P1**

目标是用真实故障证明系统的恢复能力。

交付内容：

- 使用 Toxiproxy 或容器控制模拟网络故障。
- 模拟 MySQL 延迟/断连、Redis 断连、Worker 强制退出和模型服务超时。
- 记录恢复时间、重复消息数、最终状态和人工介入步骤。
- 为每种故障明确 RTO、数据一致性目标和告警。

故障矩阵：

| 故障 | 预期行为 |
| --- | --- |
| Outbox 发布前服务退出 | 重启后继续发布，不丢命令 |
| Worker 执行中退出 | 心跳超时后重新调度或 XAUTOCLAIM |
| Worker 完成后 ACK 前退出 | 允许重复投递，但业务结果幂等 |
| Redis 暂时不可用 | 命令保留在 Outbox，恢复后继续发布 |
| MySQL 暂时不可用 | 请求快速失败或退避，不产生半完成状态 |
| 模型服务超时 | 按策略重试/降级，超过上限进入失败或 DLQ |
| 重复终态事件 | 去重，不重复推进 DAG |
| 非法消息 | 隔离到 DLQ，不阻塞消费组 |

验收标准：

- 每类核心故障至少有一条自动化或半自动演练脚本。
- 演练报告包含故障时间、检测时间、恢复时间和最终一致性结果。
- 故障恢复不依赖直接手工修改数据库。

## 5. 实施顺序

| 里程碑 | 工作项 | 退出条件 |
| --- | --- | --- |
| M1 真实一致性验证 | ROLE-BE-01 | MySQL、Redis、Outbox、消费组和幂等均有容器集成测试 |
| M2 性能与观测基线 | ROLE-BE-02、ROLE-BE-03 | 有压测数据、Dashboard、告警和跨服务 Trace |
| M3 消息可靠性闭环 | ROLE-BE-04、ROLE-BE-08 | 重试、DLQ、毒消息和主要故障恢复经过演练 |
| M4 多实例与高并发治理 | ROLE-BE-05、ROLE-BE-06、ROLE-BE-07 | 认证可横向扩展，分页、并发和过载保护有测试与指标 |

推荐实际开发顺序：

1. 先补 Testcontainers，不先增加新的业务功能。
2. 建立监控和压测基线，确认真正瓶颈。
3. 基于压测结果优化 SQL、分页和连接池。
4. 完成 DLQ、退避、告警和故障演练。
5. 最后升级认证、限流和并发控制。

## 6. 面试与简历证据

完成 P0 后，项目必须能提供：

- 一张 MySQL、Outbox、Redis Streams、Worker 和模型调用的完整时序图。
- 一条端到端 Trace。
- 一份包含 QPS、P95/P99、错误率和资源占用的压测报告。
- 一份索引或分页优化前后的 `EXPLAIN ANALYZE` 对比。
- 一份 Worker 宕机、重复投递、XAUTOCLAIM 和幂等消费的故障演练。
- 一份 Outbox、Pending、Node 失败率和模型延迟 Dashboard。

建议简历表述：

> 设计并实现 AutoSpec 可靠异步编排后端，使用 MySQL 状态机、事务 Outbox、Redis Streams 消费组和幂等事件处理保证长耗时 Agent 任务的一致性；通过 Testcontainers、压测、分布式追踪和故障注入验证重复投递、Worker 宕机、Redis 断连及恢复场景，并建立 P95/P99、队列积压和模型调用稳定性监控。

## 7. 非目标

- 不为堆技术栈强行把 Redis Streams 更换为 Kafka/RocketMQ。
- 不把系统拆成更多微服务；先证明现有边界、可靠性和容量。
- 不在没有压测数据前盲目增加缓存或分库分表。
- 不宣称 Exactly Once；应准确描述为至少一次投递加业务幂等。
- 不使用本机内存保存生产 Session、分布式锁或关键任务状态。
- 不在日志、测试数据和演练报告中保存 API Key、密码或敏感模型输入。

## 8. Definition of Done

后端方向优化完成至少需要满足：

- 核心一致性路径通过真实 MySQL 和 Redis 集成测试。
- 事务 Outbox、重复投递、幂等消费、XAUTOCLAIM 和恢复均有可复现证据。
- 关键接口有确定的数据规模、QPS、P95/P99 和错误率。
- Java 控制面、Redis Streams、Python Worker 和模型调用拥有统一 Trace。
- 消息达到最大重试次数后进入可管理的 DLQ。
- 认证支持多实例，不依赖进程内 Session Map。
- 深分页、并发编辑、重复审批和突发流量有明确处理策略及测试。
- 至少完成一次包含 Worker、Redis、MySQL 或模型服务故障的恢复演练。
