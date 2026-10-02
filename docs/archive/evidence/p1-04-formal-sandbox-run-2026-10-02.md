# P1-04 正式 API/Worker Sandbox 运行证据

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
相关提交：`6732e26`、`66d410a`、`eeec0d59`、`403d42a`

## 隔离条件

- 使用独立 Compose project `autospec-formal`，配置来自 `.env.example`；独立 MySQL/Redis/verify-mysql 卷和验证网络，不读取或修改真实 `.env`、现有业务容器或业务数据卷。
- `AGENT_MODEL_MODE=fixture`，不调用 DeepSeek 或其他外部模型；Agent 节点、Redis Worker、后端、spec-verifier 和临时 MySQL 均使用当前源码构建。
- 宿主端口冲突第一次被 Docker 正确拒绝（已有容器占用 `18000/18080`），随后改用独立端口启动；这不是应用失败。

## 候选治理与审批

1. 正式治理 API 登录成功，原数据库仅有 `1:pm-schema-repair-v12:PUBLISHED`。
2. 通过 `POST /api/workflows` 创建 `spec-sandbox` 候选，候选 ID 为 2；`POST /validate` 返回 `valid=true`，随后显式发布为 `PUBLISHED`。它只在本隔离数据库可选择，未改 active 指针或 V1 种子。
3. `POST /api/workflow-runs` 显式传 `workflowVersionId=2` 创建 run 1。Product Manager 审批第一次缺少 `expectedLockVersion`，API 返回 400 `VALIDATION_FAILED`；补 `expectedLockVersion=0` 后审批记录为 `DECIDED`。这保留了审批并发保护证据。

## 第一次正式失败与修复

run 1 经过真实 API、Outbox/Redis Worker 和六节点执行后，在 Evaluator 阻断：

```text
QUALITY_GATE_BLOCKED
MUST_REQUIREMENT_TRACE_GAP: REQ-SEARCH is missing trace evidence for: acceptance criteria.
PERMISSION_COVERAGE: Project-scoped APIs are missing authentication or roles: /api/products.
```

根因是确定性的 fixture 数据和规则：校园 fixture 的 PRD 缺少 `REQ-SEARCH` 用户故事/验收条件；Evaluator 把 `approved products` 的词首误判为 `approve` 权限动作。提交 `6732e26` 增加搜索故事与两个验收条件，并把权限关键词改为词边界匹配，另加回归测试。

定向回归：

```text
D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q tests/test_evaluator.py tests/test_review_rules.py tests/test_software_domain_fixtures.py tests/test_candidate_verification_loop.py tests/test_production_handlers.py tests/test_spec_verifier.py
43 passed
```

## 第二次正式成功

新建项目 2 并显式选择候选 ID 2，run 2 经审批后最终状态为 `COMPLETED`；六个节点均为 `SUCCEEDED`：

| 节点 | 正式证据 |
| --- | --- |
| Backend | 真实 `spec.verify` TOOL 调用 `SUCCEEDED`；循环 Trace 写入 candidate hash `6e265332fc63f35b6f5f6409dde215e4a154b1e977354c66ca00bc3f2b8a9ef1` 与 verification fact ref `9b9762a9b7e15ee9a1c40110b938e1ebca5cce060d134543610ecb7d61f4efda` |
| Reviewer | 真实 `spec.verify` TOOL 调用 `SUCCEEDED`，随后 fixture Reviewer 成功；产物 `REVIEW_REPORT` 为 `PASS`，包含 `FULL/L2` fact |
| Evaluator | `EVALUATION_REPORT` 为 `overall_score=100`、`gate_status=PASSED`、`blocking_issue_count=0` |
| 运行 Trace | execution bundle hash `414117c26ac2851e35e43f35866bda46a059e3bdac5ad8bfbdb35ee94d05fc3a`；Backend 有 5 条步骤事实，候选/fact 引用已持久化 |

## 交付门禁与导出

- 首次读取 readiness 在生成包之前为 `BUILD_REQUIRED`，只阻断“Generate and verify a delivery bundle”，没有绕过规格门禁。
- 正式调用 code skeleton 后，作业 1 为 `SUCCEEDED`；再次读取 readiness 为 `READY`，`specReady=true`、`buildReady=true`、无 blockers。
- 通过正式导出 API 生成并持久化：`autospec-project-2-skeleton.zip`、`autospec-project-2.md`、`autospec-project-2.pdf`；Markdown 返回 4819 字符，PDF 返回 4552 字符的 base64 内容。

## 尚未关闭的边界

本记录证明了候选的正式注册/显式选择、审批、六节点 Worker、Backend/L2、Reviewer/FULL/L2、Evaluator 和 DeliveryGate 成功链路，并保留了真实门禁失败后的修复重跑。仍未把“故意规格缺陷→verifier FAILED→一次 bounded Replan→修复成功”通过正式 API 跑通，也未完成预算耗尽/震荡终止 Trace、缺失/过期证据的正式导出负例和 Sandbox 专项 CI。候选未切换为默认 active；本次 fixture 不产生外部模型费用。
