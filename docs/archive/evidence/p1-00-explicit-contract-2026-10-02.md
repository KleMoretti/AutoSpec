# P1-00 显式生产契约证据（2026-10-02）

## 范围

本记录只覆盖 SIG-P1-00 的显式输入与生产消费接线，不代表 P1-E、正式 FULL/L2 或 Replan Trace 已完成。旧 v1 Artifact、WorkflowSpec、数据库历史和 active 版本未改写。

## 实现

- 提交 `cb4355b`：新增显式 Backend/Frontend Artifact 子模型、`spec-contract-v2`、严格 Artifact adapter、Backend v7 / Frontend v4 Handler、Reviewer/Backend verifier 的 v2 生产分派、双端 Prompt/catalog 注册及 fixture-only 显式事实构造。
- 后端显式声明 primary key、foreign key（含显式 `null`）、受限结构化类型、参数位置、成功状态和响应可空性；旧 Artifact 仍走兼容适配器，不能被新 adapter 自动推断成可信事实。
- 前端显式声明 `backend_api_id`、请求参数名称/位置/来源类型/必填性和响应字段路径/类型/可空性；参数与响应映射不由 API 契约反向补全。
- 旧 `BackendDesignArtifact` 与 `FrontendSkeletonArtifact` Schema 指纹保持不变：
  - `2678cf7396c34995cf38731ca5da9626bcde8d438378efabdf1c21a736e290b0`
  - `2490b7c0f0e5f8dfaaf62c4a2a8807f2f05d85ae75fb670fa0cbfbe9789e5dbd`

## 验证

以下命令在该提交前执行并通过：

```text
D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q agent-engine/tests/test_agent_loop.py agent-engine/tests/test_production_handlers.py agent-engine/tests/test_candidate_verification_loop.py agent-engine/tests/test_explicit_contract_adapter.py agent-engine/tests/test_spec_verifier.py agent-engine/tests/test_context_policy.py
37 passed

D:\miniconda3\envs\CrewAI_Study\python.exe scripts/verify_workflow_contract.py
AutoSpec workflow contracts are synchronized

D:\apache-maven-3.8.9\bin\mvn.cmd -q -Dtest=WorkflowHandlerCatalogTest test
passed

D:\miniconda3\envs\CrewAI_Study\python.exe -m compileall -q agent-engine
passed（仅输出 pytest 临时目录不可列出的提示）
```

`test_production_handlers.py` 覆盖显式 Handler 输出，`test_candidate_verification_loop.py` 覆盖显式 Reviewer 与 Backend verifier 两条冻结调用路径，`test_explicit_contract_adapter.py` 覆盖非命名惯例 FK、缺字段拒绝、旧指纹和三领域显式 fixture L1 通过。

## 限制与下一步

- 本记录没有调用 live DeepSeek，没有发布或激活新 WorkflowSpec，也没有宣称默认 v12 已切换到 v2。
- 完整客户端/消费桩、OpenAPI/DDL 结构校验和前端单边变异检查由后续 P1-01/02 提交覆盖；真实 `tsc`/MySQL L2 仍由隔离执行任务验收。
