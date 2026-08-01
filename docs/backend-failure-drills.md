# 后端故障注入与恢复演练

本文档是 ROLE-BE-08 的可执行手册。演练覆盖控制面 Outbox、Redis Streams、Worker、MySQL 和模型调用链；所有状态恢复均通过应用重试、连接池重连或消息重放完成，不直接修改数据库。

## 快速运行

运行环境需要可用的 Docker daemon、Maven、Python，以及 `agent-engine/requirements.txt` 中的测试依赖。脚本不会启动长期运行的业务服务，Testcontainers 会按场景管理临时 MySQL、Redis 和 Toxiproxy 容器。

先查看场景清单：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-backend-failure-drills.ps1 -List
```

执行全部场景：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-backend-failure-drills.ps1 -Scenario all
```

只执行一个场景，例如 Redis 断连：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-backend-failure-drills.ps1 -Scenario redis-outage
```

默认从 `PATH` 查找 `mvn` 和 `python`。本机未配置 `PATH` 时，通过 `-MavenExecutable` 和 `-PythonExecutable` 传入本地可执行文件；不要把本机绝对路径提交到仓库。

为了减少重复启动 Spring 容器和测试进程，执行 `all` 时脚本会把所有 Java 场景合并为一次 Failsafe 调用，把所有 Python 场景合并为一次 pytest 调用。报告输出到已被 Git 忽略的 `build/failure-drills/<时间戳>/`：

- `summary.json`：运行起止时间、总耗时、场景结果、RTO、一致性目标、告警和人工修复次数；
- `backend-integration.log`：Java/Testcontainers 演练日志；
- `agent-pytest.log`：Worker 和模型执行器演练日志；
- 日志中的 `failureDrill=...` 行：测试记录的检测耗时、恢复耗时、重复次数和最终状态。

任何测试命令失败时脚本返回非零退出码，报告仍会落盘。

## 故障矩阵

| 场景 | 注入方式与自动化证据 | RTO | 数据一致性目标 | 主要告警 |
| --- | --- | --- | --- | --- |
| `outbox-restart` | 在事务提交、Outbox 发布前关闭第一个 Spring 上下文，再启动第二个上下文；`ControlPlaneOutboxRestartIT` | 检测和恢复各小于 15 秒 | Outbox 保留一条命令，Redis 最终只有一条命令，人工修复为 0 | `AutoSpecOutboxBacklog`、`AutoSpecOutboxOldestMessageTooOld` |
| `worker-exit` | 消息进入 Pending 后模拟 Worker 退出，由另一消费者执行 `XAUTOCLAIM`；`RedisWorkflowTransportIT#workerExitBeforeAckIsRecoveredByAnotherConsumerWithinRto` | 生产目标为 `WORKER_CLAIM_IDLE_MS` 加一个轮询周期；加速测试小于 5 秒 | 允许一次重复投递，最终 Pending 为 0 | `AutoSpecWorkerTargetDown`、`AutoSpecWorkerHeartbeatStale` |
| `ack-gap` | 终态事件发布后让 ACK 抛出连接错误，再由恢复 Worker 重放；后端并发消费相同事件；`test_worker_exit_after_terminal_publish_replays_same_event_before_ack`、`MySqlWorkflowEventConsumerConcurrencyIT` | `WORKER_CLAIM_IDLE_MS` 加一个轮询周期 | 重放使用确定性事件 ID，终态和 DAG 只推进一次 | `AutoSpecRedisPendingMessageTooOld`、`AutoSpecWorkerHeartbeatStale` |
| `redis-outage` | Toxiproxy 切断并恢复 Redis；`RedisOutboxRecoveryIT` | 检测和恢复各小于 10 秒 | 命令保持 `PENDING`；允许不确定提交窗口产生重复 Stream 记录，但所有重试保持同一 `event_id`，由下游幂等消费；人工修复为 0 | `AutoSpecWorkflowBacklogMetricsCollectionFailed`、`AutoSpecOutboxOldestMessageTooOld` |
| `mysql-outage` | Toxiproxy 切断并恢复 MySQL；`MySqlFailureRecoveryIT` | 检测小于 5 秒，恢复小于 10 秒 | 请求快速失败、半写入为 0、连接池自动恢复且后续可读写 | `AutoSpecWorkflowBacklogMetricsCollectionFailed`、`AutoSpecHttpFailureRateTooHigh` |
| `model-timeout` | Python 模型处理器超过命令超时，Java 控制面消费两次超时事件；`test_executor_times_out_slow_model_within_runtime_budget`、`ModelTimeoutRecoveryIT` | 超时检测、重试就绪、最终失败各小于 5 秒 | 只执行有界重试，重复终态不重复推进，最终为 `FAILED/MODEL_TIMEOUT` | `AutoSpecWorkerFailureRateTooHigh`，并观察 `autospec_model_invocations_total{status}` |
| `duplicate-terminal` | 两个真实 MySQL 事务并发消费相同终态事件；`MySqlWorkflowEventConsumerConcurrencyIT` | 在单次事件处理事务内完成 | 一个 `ACCEPTED`、一个 `DUPLICATE`，处理记录和 DAG 推进各一次 | `AutoSpecWorkflowEventHandlerFailed`（出现处理异常时） |
| `invalid-message` | Worker 批次首条为非法 JSON，随后放入一条合法消息；同时验证 DLQ 发布失败时不 ACK；`test_runner_quarantines_invalid_command_and_continues_batch` | 一个 Worker 批次内 | 毒消息进入 DLQ，后续消息继续；DLQ 写失败时保留原消息供重试 | `AutoSpecWorkerFailureRateTooHigh`，并观察 `autospec_worker_dead_lettered_total` |

## 结果判定与处置

演练通过必须同时满足：测试退出码为 0、`summary.json` 中所选场景均为 `passed`、最终状态符合矩阵中的一致性目标、`manualDatabaseRepairs` 为 0。Java 演练中记录的 `detectionMs`、`recoveryMs` 或 `finalizationMs` 还必须低于测试内声明的 RTO。

演练失败时只做以下处置，不直接修表：

1. 保留本次时间戳目录，先检查两个命令日志和 `failureDrill=...` 观测行。
2. 对照 Prometheus 告警确认故障是否被观测到，并检查 Outbox、Redis Pending、Worker 心跳和模型调用指标。
3. 基础设施恢复后重新运行失败的单个 `-Scenario`。Outbox Publisher、连接池、`XAUTOCLAIM` 和幂等消费者应自行完成恢复。
4. 只有消息达到既定最大重试次数后，才通过已有 DLQ 查询/重放接口处置；禁止绕过接口直接修改 Outbox 或事件去重表。

当前告警规则与指标契约见 `observability/prometheus/rules/autospec-alerts.yml` 和 `observability/README.md`。
