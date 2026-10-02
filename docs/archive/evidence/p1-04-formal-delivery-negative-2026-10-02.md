# P1-04 正式交付门禁负例证据

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
运行环境：隔离 Compose project `autospec-formal`，`AGENT_MODEL_MODE=fixture`

## 正式 API 负例

此前的真实质量门禁失败样本 project 1 / run 1 保留在隔离数据库中。该 run 经六节点 Worker 执行后因 Evaluator 的 `QUALITY_GATE_BLOCKED` 失败，包含：

- `REQ-SEARCH` 缺少验收条件追踪；
- `/api/products` 缺少认证或角色覆盖。

本次重新登录控制面，只读取 project 1 的 readiness，然后直接调用交付入口：

| API | 结果 |
| --- | --- |
| `GET /api/projects/1/delivery-readiness` | `SPEC_BLOCKED`，`specReady=false`，blocker 为 `The latest workflow run is not completed` |
| `POST /api/projects/1/code-skeleton` | HTTP `409 CONFLICT`，拒绝生成代码骨架 |
| `POST /api/projects/1/export?format=MARKDOWN` | HTTP `409 CONFLICT`，拒绝 Markdown 导出 |

这证明失败运行不能通过直接调用交付 API 绕过门禁。该负例没有修改 project 1、run 1 或任何已成功运行的产物。

## 离线 fact 负例覆盖

Backend 的 `DeliveryGateServiceTest` 已覆盖可信事实缺失、过期、层级不足和来源/版本不匹配等 fail-closed 分支；本轮定向 Maven 命令：

```text
D:\apache-maven-3.8.9\bin\mvn.cmd -q -Dtest=DeliveryGateServiceTest,CodeSkeletonServiceTest test
```

结果：退出码 0。测试使用 H2/Flyway 临时数据库，不修改隔离 Compose 数据。

## 限制

- project 1 的正式 API 负例首先被“最新 run 未完成”阻断，不能把它冒充成某一个伪造 fact 的 HTTP 负例；四类 fact 变体的精确路径仍由离线 DeliveryGate 测试覆盖。
- 缺失/过期/错 scope/低层级 fact 的独立正式导出负例，以及最终导出 manifest 的逐项审计，仍保留为后续 P1-G 边界；成功 run 2 的 ZIP/Markdown/PDF 证据未改写。
