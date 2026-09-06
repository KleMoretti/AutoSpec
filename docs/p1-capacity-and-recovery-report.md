# AutoSpec V5 P2-03 本地发布证据

状态：local-fixture-verified-with-production-boundaries

本记录是 2026-09-06 在 Windows 本地工作区完成的发布门禁证据。它只记录实际执行过的命令和结果；没有把 fixture 测试结果当作 live 模型、Docker 运行时或容量结论。

## 测量环境

| 项目 | 实际值 |
|---|---|
| 代码基线 | `14d9b219` — P2-02 runtime execution evidence |
| 测量窗口 | 2026-09-06 22:14–22:20，Asia/Shanghai |
| 操作系统 | Windows |
| Java | 21.0.4 LTS |
| Maven | 3.8.9，使用仓库规定的 `D:\apache-maven-3.8.9\bin\mvn.cmd` |
| Agent Python | `D:\miniconda3\envs\CrewAI_Study\python.exe` |
| Node / npm | v22.20.0 / 10.9.3 |
| Docker / Compose | 29.4.0 / v5.1.2 |
| 模型模式 | Agent Engine 回归使用 fixture；没有读取或使用 `MODEL_API_KEY` |
| 数据集 | 仓库内脱敏 fixture、Schema/契约样例和 H2 测试数据库 |

## 发布门禁结果

| 层级 | 命令或门禁 | 实际结果 | 采样说明 |
|---|---|---|---|
| Backend | `mvn -q test` | 195 tests，0 failures，0 errors，0 skipped；29.34s wall time | 59 个 Surefire XML 报告；Flyway 94 migrations validated/applied |
| Agent Engine | `python -m pytest -q` | 103 passed；pytest 输出 1.18s，命令 wall time 2.44s | fixture、Schema、检索、引用、Tool Gateway、缓存和 Worker 回归 |
| Frontend | `npm test` | 9 files，24 passed；Vitest duration 3.14s | 包含 WorkflowReplayPanel Bundle/Trace 证据回归 |
| Frontend build | `npm run build` | 3097 modules transformed，build 8.30s | TypeScript 与 Vite production build 均通过 |
| WorkflowSpec | `scripts/verify_workflow_contract.py` | `autospec-v5 workflow contract is synchronized` | canonical JSON、最新 V94 seed、Java 解析和 Python Worker 能力一致 |
| Compose | `docker compose config --quiet` | 通过 | 仅校验配置，不启动容器 |
| Monitoring Compose | `docker compose --profile monitoring config --quiet` | 通过 | 仅校验配置，不启动容器 |

## 已验证的安全与正确性边界

- V5 仍是 Product Manager、Architect、Backend Engineer、Frontend Engineer、Reviewer、Evaluator 六节点；正式入口和 Redis Worker 主链路未被改成同步生成。
- Worker 输出 Schema hash 已与 canonical WorkflowSpec、V94 数据库 seed 同步；历史 Flyway 迁移未修改。
- Prompt 别名归一化测试使用事务回滚，避免测试数据覆盖内置 V5 prompt；执行 bundle 会拒绝活动 Prompt checksum 漂移。
- 本地回归覆盖项目/访问范围检索隔离、引用完整性、受控 Tool Gateway allowlist、fencing/idempotency、corpus epoch 和缓存 provenance 相关失败路径。
- 前端回放页可以从运行定位 Bundle ID/hash、节点 contract hash、fencing token、Trace 中的模型/工具调用和检索缓存状态。

## 容量与故障演练边界

以下项目在本次本地发布门禁中明确没有执行，因此不生成伪造的 P50/P95/P99、吞吐、成本或恢复率：

| 项目 | 状态 | 原因与后续需要 |
|---|---|---|
| Live provider 基线 | 未执行 | 本机未启用 live 模型；需要安全注入 Key、固定 provider/model 和脱敏采样窗口 |
| Docker 端到端运行 | 未执行 | 本次只做 base/monitoring Compose 配置校验；需要隔离 Docker 环境、数据库和 Redis 数据卷 |
| API/队列/节点/检索/工具容量采样 | 未执行 | 单元和集成测试不能替代多实例、受控并发和资源采样 |
| Outbox/Redis/Worker/Provider/Tool/索引/缓存故障注入 | 未执行 | 故障演练会改变本地服务状态；需在隔离环境按 `docs/backend-failure-drills.md` 执行并保留 Run/Trace ID |
| 生产成本与恢复率 | 未执行 | fixture 成本为零，不代表 live 成本；需要从 model/tool invocation 台账计算 |

## 结论

本次变更达到“代码、契约、迁移、三端回归和 Compose 配置”的本地发布门禁：195 个后端测试、103 个 Agent Engine 测试、24 个前端测试全部通过，WorkflowSpec 同步校验通过。

本报告不授予生产容量或 live provider 发布结论。生产签署前仍需在隔离环境补做 live 基线、受控并发、故障演练和成本采样；这些是环境证据边界，不是本次代码回归失败。

## 可复现命令

以下命令从仓库根目录执行，均使用本仓库规定的工具路径：

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
