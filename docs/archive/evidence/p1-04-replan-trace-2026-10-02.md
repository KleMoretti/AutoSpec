# P1-04 Replan Trace 与验证错误分流证据

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
相关提交：`1b5f2ff`、`eeec0d59`

## 本次完成

- `FAILED + gate_status=BLOCKED` 仍进入已有的一次有界 Replan。
- `ERROR`、`BLOCKED`、`NOT_RUN` 报告，以及 sidecar 不可用、权限、协议、事实完整性、数据库清理和 TypeScript 环境错误，统一记录 `SPEC_VERIFY_ERROR` 或稳定错误码并以 `VERIFICATION_ERROR` 终止，不交给模型修复。
- Python AgentStepRecord 已有 `candidate_hash` / `verification_fact_ref` 的步骤事实；本次将两字段贯通到 Java 事件 DTO、MyBatis 持久化、Trace API/OpenAPI、Flyway V5 和前端回放面板。
- V1 基线未改写；新字段由 `V5__agent_step_evidence_refs.sql` 增量加入。既有 Java V3/V4 迁移保持不变。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| `D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q tests/test_agent_loop.py tests/test_candidate_verification_loop.py tests/test_spec_verifier.py` | 21 passed |
| `D:\apache-maven-3.8.9\bin\mvn.cmd -q clean -Dtest=SchemaInitSqlTest test` | 通过；Flyway 应用 V1、V2、既有 Java V3/V4 与本次 V5，共 5 个迁移 |
| `D:\apache-maven-3.8.9\bin\mvn.cmd -q -Dtest=SchemaInitSqlTest,BackendApiContractEndpointTest,WorkflowHandlerCatalogTest test` | 通过 |
| `npm run build` | 通过 |
| `npm test -- --run` | 10 个文件、29 tests 通过；首次受 Windows `spawn EPERM` 阻断，升权重试通过 |
| `git diff --check` | 通过；仅有 Windows 行尾提示 |

## 边界与限制

`test_backend_loop_stops_on_verifier_error_without_replan` 验证 sidecar 不可用时没有 `REPLAN`，且步骤保留候选哈希。已有 verifier 失败→Replan→修复用例继续通过，并保留 verification fact 引用。

本记录证明离线循环、事件接收、持久化和前端展示路径已贯通；尚未把一个故意失败的候选通过正式 `POST /api/workflow-runs`、Redis Worker、真实数据库台账和交付导出完整跑通。因此 P1-04/H 的“正式 API Replan Trace”仍不能标记完成。
