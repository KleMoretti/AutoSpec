# AutoSpec P0/P1 execution evidence

日期：2026-09-30（Asia/Shanghai）

## 状态

- 交付分支：`codex/p0-p1-complete-20260930`；本轮按任务分段提交，代码修复提交为 `5c52a85`、`a67a7d6`。
- 本轮未 push、未发布、未切换 active WorkflowSpec、未删除数据卷；候选 `pm-schema-repair-v12` 仅通过显式版本 ID 运行。
- 交付状态：P0/P1 离线实现、P0-F、P1-E 真实 L2、正式 fixture API/导出和一个成功 DeepSeek live smoke 已完成；远端 CI 未重跑，仍是外部验证限制。

## 已落地范围

- 三个确定性业务 fixture：校园交易、库存管理、员工请假审批；共享稳定需求/API/数据/UI 引用。
- 新候选 `agent-engine/contracts/autospec-spec-repair.workflow.json`，工作流 key 仍为 `autospec-v5`、版本为 `spec-repair`，六节点拓扑保留；数据库只新增 V104 DRAFT 种子。
- 通用 `spec-full-v1` Reviewer 规则与 legacy marketplace 规则隔离；`approval`、业务 `event`/`retry` 不再触发 AutoSpec 控制面接口误报。
- 结构化输出最多一次修复，修复受 `max_calls`、deadline 和稳定错误码约束。
- `spec-contract-v1`、确定性编译器、L1 校验器、`spec.verify:v1` 受控 Gateway、可信 `VerificationFact`，以及候选 Reviewer→Evaluator→Delivery Gate 传播。
- 候选 Backend Loop 使用 `backend-design-v2` + `BACKEND` verifier policy；每个结构化候选强制通过 `spec.verify`，失败 issue code/source path/candidate hash/fact ref 进入 trace 并触发 bounded Replan，工具错误按 deadline/budget/verification failure fail-closed。
- verification Compose profile：独立 verifier、验证 MySQL、内部网络和资源上限；本轮已真实运行 verifier、MySQL 与 TypeScript L2，未删除验证数据卷。
- DeepSeek Flash 定价冻结：运行时使用峰时 CNY/1M tokens：cache hit `0.04`、cache miss `2`、output `8`；非峰时官方价格为 `0.02 / 1 / 4`。模型名为 `deepseek-flash`，价格来源为 [DeepSeek 官方定价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)，`.env` 未进入证据或提交。

## 实际验证

| 范围 | 命令/结果 |
| --- | --- |
| Agent Engine | `D:\miniconda3\envs\CrewAI_Study\python.exe -X utf8 -m pytest -q`：`188 passed`。 |
| Backend Loop verifier feedback | `tests/test_agent_loop.py::test_candidate_backend_loop_replans_after_verifier_feedback`：1 passed；两次候选验证经受控 Gateway 执行，失败 fact/issue 进入 trace，第二候选完成。 |
| 候选 verifier 闭环 | `tests/test_candidate_verification_loop.py`：1 passed；Reviewer 输出包含 `PASSED/L1` trusted fact。 |
| Backend | `D:\apache-maven-3.8.9\bin\mvn.cmd -q test`：`223 tests / 0 failures / 0 errors / 0 skipped`；H2 Flyway 测试应用到 V117；最终 `DeliveryGateServiceTest`、`MarkdownExportServiceTest` 定向回归均通过。 |
| Frontend | `npm test -- --run`：9 files / 25 tests passed；`npm run build`：exit 0。Windows 首次 `spawn EPERM` 后按正常权限机制重试通过。 |
| Workflow contract | `scripts/verify_workflow_contract.py`：`autospec-v5 workflow contracts are synchronized`。 |
| Compose 静态配置 | `docker compose config --quiet`、`--profile monitoring config --quiet`、`--profile verification config --quiet` 均通过。 |
| 历史兼容 | active `autospec-v5`、`autospec-v5-parallel`、诊断 contract 的工作树 blob hash 与 HEAD 一致。 |
| MySQL 故障恢复 | `D:\apache-maven-3.8.9\bin\mvn.cmd -Pintegration-test -Dit.test=MySqlFailureRecoveryIT verify`：exit 0；`detectionMs=4203`、`recoveryMs=31`、`partialWrites=0`、`manualRepairs=0`、`finalReadable=true`。 |
| 真实 verifier L2 | `metagpt-spec-verifier-1` 内三个 fixture 均 `L2 PASSED`；TypeScript 与 MySQL schema 均 `PASSED`，issues 均为空。 |
| 正式 fixture API | project 4 / run 7（workflow version 17，fixture mode）经人工审批后 `COMPLETED`；Product Manager、Architect、Backend、Frontend、Reviewer、Evaluator 六节点全部 `SUCCEEDED`。 |
| 正式 live API | project 35 / run 38（candidate workflow version 30=`pm-schema-repair-v12`）经 approval 35 后 `COMPLETED`；六节点全部 `SUCCEEDED`，Evaluator `100/A/PASSED`，blocking issues `0`。DeepSeek Flash 7 次调用、54,619 tokens、估算成本 `0.130100 CNY`。 |
| 正式导出 | run 38 的 Markdown、PDF、ZIP 均 HTTP 200 并持久化为 `autospec-project-35.md`、`autospec-project-35.pdf`、`autospec-project-35-skeleton.zip`；Markdown 已包含 `AC-ADD-ITEM-1` 与 `REQ-ADD-ITEM`。code-generation job 状态为 `SUCCEEDED/PASSED`。 |

候选关键标识：Reviewer `ReviewReportV2` schema hash 为 `620549ea2a9afda11a3b07ddc10a8158770e65619690cc1193f2cb4241c90824`；verification policy hash 为 `cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794`。这些不是 secret。

## Live 结果与边界

- run 8 使用 DeepSeek live、库存需求、显式上限 `maxModelCalls=15`；Product Manager 1 次调用后因 `MODEL_OUTPUT_LIMIT` 失败，消耗输出 3992 tokens，其余节点取消。
- run 9 使用更短的库存需求；Product Manager 1 次调用返回 3992 tokens 但 JSON 未闭合，trace 记录 `VALIDATION_ERROR`，其余节点取消。
- 两次旧尝试合计 2 次模型调用；run 8 是 `MODEL_OUTPUT_LIMIT`/`finish_reason=length`，run 9 是 provider 调用成功后的 `VALIDATION_ERROR`，实际字段路径包括 `target_users` 类型错误、`core_features[].priority` 枚举错误和顶层 `acceptance_criteria` 额外字段，不能把两次都归因于 JSON 截断。
- 候选 v1–v12 均保持未激活；V117 种子注册 v12，run 37/38 显式选择 DB workflow version 30，默认 published v5-parallel 未改变。
- run 37：`COMPLETED`，六节点成功，`49,664` tokens、估算成本 `0.125145 CNY`，Evaluator `100/A/PASSED`、2 个非阻断 review issue；用于确认 scope 修复后的交付门禁。
- run 38：`COMPLETED`，六节点成功，`54,619` tokens、估算成本 `0.130100 CNY`，Evaluator `100/A/PASSED`、issues `0`；Markdown/PDF/ZIP 和 code skeleton 全部成功。
- 证据仍 fail-closed：运行 36 在延迟导出时因 FULL fact 过期被拒绝；`5c52a85` 修复 Backend/FULL policy scope 误比对，run 38 在新鲜证据窗口内完成导出。验收 fact 的有效期仍按冻结 verifier policy 约 30 秒，后续若要支持延迟交付应单独设计可审计的 evidence TTL 字段，不在本轮绕过过期校验。
- `a67a7d6` 修复结构化验收条件导出，保留 acceptance ID 与 requirement refs；该问题原先只影响 Markdown/PDF 展示，不影响 Artifact Schema 校验。
- 远端 CI 未重跑；本地通过不代表远端默认分支已绿。

## 后续最小顺序

1. 本地 P0/P1 实施和必要 live smoke 已完成；不再为“成功一次”扩展到消融、统计显著性或更多付费用例。
2. 如需远端 CI，先由用户明确 push 目标和权限，再运行 CI；在此之前不宣称远端默认分支已绿。
3. 如需延长可信 evidence 有效期，新增显式、可审计的策略字段和迁移/候选版本，不改写 active 或历史 SQL。
