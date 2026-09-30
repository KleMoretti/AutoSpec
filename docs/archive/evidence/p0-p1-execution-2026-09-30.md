# AutoSpec P0/P1 execution evidence

日期：2026-09-30（Asia/Shanghai）

## 状态

- 交付分支：`codex/p0-p1-complete-20260930`；本轮按任务分段提交，最新代码提交为 `5e377ca`。
- 本轮未 push、未发布、未切换 active WorkflowSpec、未删除数据卷；DeepSeek live 仅按用户授权执行两次有界 smoke。
- 交付状态：P0/P1 离线实现、P0-F、P1-E 真实 L2、正式 fixture API/导出已完成；P0-G/P1-H 仍因 live 没有成功用例和远端 CI 未重跑而保持阻断。

## 已落地范围

- 三个确定性业务 fixture：校园交易、库存管理、员工请假审批；共享稳定需求/API/数据/UI 引用。
- 新候选 `agent-engine/contracts/autospec-spec-repair.workflow.json`，工作流 key 仍为 `autospec-v5`、版本为 `spec-repair`，六节点拓扑保留；数据库只新增 V104 DRAFT 种子。
- 通用 `spec-full-v1` Reviewer 规则与 legacy marketplace 规则隔离；`approval`、业务 `event`/`retry` 不再触发 AutoSpec 控制面接口误报。
- 结构化输出最多一次修复，修复受 `max_calls`、deadline 和稳定错误码约束。
- `spec-contract-v1`、确定性编译器、L1 校验器、`spec.verify:v1` 受控 Gateway、可信 `VerificationFact`，以及候选 Reviewer→Evaluator→Delivery Gate 传播。
- 候选 Backend Loop 使用 `backend-design-v2` + `BACKEND` verifier policy；每个结构化候选强制通过 `spec.verify`，失败 issue code/source path/candidate hash/fact ref 进入 trace 并触发 bounded Replan，工具错误按 deadline/budget/verification failure fail-closed。
- verification Compose profile：独立 verifier、验证 MySQL、内部网络和资源上限；本轮已真实运行 verifier、MySQL 与 TypeScript L2，未删除验证数据卷。

## 实际验证

| 范围 | 命令/结果 |
| --- | --- |
| Agent Engine | `D:\miniconda3\envs\CrewAI_Study\python.exe -X utf8 -m pytest -q`：`165 passed`。 |
| Backend Loop verifier feedback | `tests/test_agent_loop.py::test_candidate_backend_loop_replans_after_verifier_feedback`：1 passed；两次候选验证经受控 Gateway 执行，失败 fact/issue 进入 trace，第二候选完成。 |
| 候选 verifier 闭环 | `tests/test_candidate_verification_loop.py`：1 passed；Reviewer 输出包含 `PASSED/L1` trusted fact。 |
| Backend | `D:\apache-maven-3.8.9\bin\mvn.cmd -q test`：exit 0；H2 Flyway 测试应用到 V104。最终 Gateway 副作用修正后 `-Dtest=ToolGatewayServiceTest,WorkflowExecutableContractTest,DeliveryGateServiceTest test` 亦 exit 0。 |
| Frontend | `npm test -- --run`：9 files / 25 tests passed；`npm run build`：exit 0。Windows 首次 `spawn EPERM` 后按正常权限机制重试通过。 |
| Workflow contract | `scripts/verify_workflow_contract.py`：`autospec-v5 workflow contracts are synchronized`。 |
| Compose 静态配置 | `docker compose config --quiet`、`--profile monitoring config --quiet`、`--profile verification config --quiet` 均通过。 |
| 历史兼容 | active `autospec-v5`、`autospec-v5-parallel`、诊断 contract 的工作树 blob hash 与 HEAD 一致。 |
| MySQL 故障恢复 | `D:\apache-maven-3.8.9\bin\mvn.cmd -Pintegration-test -Dit.test=MySqlFailureRecoveryIT verify`：exit 0；`detectionMs=4203`、`recoveryMs=31`、`partialWrites=0`、`manualRepairs=0`、`finalReadable=true`。 |
| 真实 verifier L2 | `metagpt-spec-verifier-1` 内三个 fixture 均 `L2 PASSED`；TypeScript 与 MySQL schema 均 `PASSED`，issues 均为空。 |
| 正式 fixture API | project 4 / run 7（workflow version 17，fixture mode）经人工审批后 `COMPLETED`；Product Manager、Architect、Backend、Frontend、Reviewer、Evaluator 六节点全部 `SUCCEEDED`。 |
| 正式导出 | run 7 的 Markdown、PDF 成功；ZIP 重试后 `READY`，7365 bytes / 15 entries，`backend/pom.xml`、`frontend/package.json`、`AUTOSPEC_MANIFEST.json`、`ACCEPTANCE_TESTS.json` 均存在。fat-jar 类路径修复已单独提交。 |

候选关键标识：Reviewer `ReviewReportV2` schema hash 为 `620549ea2a9afda11a3b07ddc10a8158770e65619690cc1193f2cb4241c90824`；verification policy hash 为 `cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794`。这些不是 secret。

## Live 结果与阻断

- run 8 使用 DeepSeek live、库存需求、显式上限 `maxModelCalls=15`；Product Manager 1 次调用后因 `MODEL_OUTPUT_LIMIT` 失败，消耗输出 3992 tokens，其余节点取消。
- run 9 使用更短的库存需求；Product Manager 1 次调用返回 3992 tokens 但 JSON 未闭合，trace 记录 `VALIDATION_ERROR`，其余节点取消。
- 两次尝试合计 2 次模型调用；没有继续扩大 live 尝试，也没有把 fixture 的 `consumedCost=0` 当作真实免费成本结论。模型价格/费用仍以运行时台账为准。
- live 的阻断是结构化输出预算/Prompt 适配问题，不是 Docker 或正式 API 未启动；需要新的、经批准的输出预算或验证过的更短结构化 Prompt/候选版本后再重试。
- 远端 CI 未重跑；本地通过不代表远端默认分支已绿。

## 后续最小顺序

1. 为 Product Manager 选择并冻结一个新的输出预算或更短结构化 Prompt/未激活候选，先做离线契约与回归验证。
2. 取得明确 live 预算后，仅用一个 fixture 通过正式 `POST /api/workflow-runs` 重试，记录 run/trace/fact、策略 hash 与真实台账。
3. 按仓库远端策略 push 后重跑 CI；在此之前不宣称远端默认分支已绿。
4. 若 live 运行发现运行时差异，新增修复迁移/候选版本，不改写 active 或历史 SQL。
