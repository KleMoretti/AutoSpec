# AutoSpec P0/P1 execution evidence

日期：2026-09-30（Asia/Shanghai）

## 状态

- 基线：`0768d4e25fe6467bbfc6b8a733d145bc3342523b`，分支 `master`。
- 当前工作树原有未提交修改已保留；本轮没有 commit、push、发布、active 切换、live 付费调用或数据卷删除。
- 交付状态：P0/P1 离线实现完成，P1-L1 完成；P1-F 的 Backend/Reviewer 离线闭环已完成；P0-F/P0-G/P1-E/P1-H 的真实 Docker/MySQL/L2/正式 API/live 验证未完成。

## 已落地范围

- 三个确定性业务 fixture：校园交易、库存管理、员工请假审批；共享稳定需求/API/数据/UI 引用。
- 新候选 `agent-engine/contracts/autospec-spec-repair.workflow.json`，工作流 key 仍为 `autospec-v5`、版本为 `spec-repair`，六节点拓扑保留；数据库只新增 V104 DRAFT 种子。
- 通用 `spec-full-v1` Reviewer 规则与 legacy marketplace 规则隔离；`approval`、业务 `event`/`retry` 不再触发 AutoSpec 控制面接口误报。
- 结构化输出最多一次修复，修复受 `max_calls`、deadline 和稳定错误码约束。
- `spec-contract-v1`、确定性编译器、L1 校验器、`spec.verify:v1` 受控 Gateway、可信 `VerificationFact`，以及候选 Reviewer→Evaluator→Delivery Gate 传播。
- 候选 Backend Loop 使用 `backend-design-v2` + `BACKEND` verifier policy；每个结构化候选强制通过 `spec.verify`，失败 issue code/source path/candidate hash/fact ref 进入 trace 并触发 bounded Replan，工具错误按 deadline/budget/verification failure fail-closed。
- verification Compose profile：独立 verifier、验证 MySQL、内部网络和资源上限；只完成配置校验，未宣称容器已运行。

## 实际验证

| 范围 | 命令/结果 |
| --- | --- |
| Agent Engine | `D:\miniconda3\envs\CrewAI_Study\python.exe -X utf8 -m pytest -q`：`164 passed`。 |
| Backend Loop verifier feedback | `tests/test_agent_loop.py::test_candidate_backend_loop_replans_after_verifier_feedback`：1 passed；两次候选验证经受控 Gateway 执行，失败 fact/issue 进入 trace，第二候选完成。 |
| 候选 verifier 闭环 | `tests/test_candidate_verification_loop.py`：1 passed；Reviewer 输出包含 `PASSED/L1` trusted fact。 |
| Backend | `D:\apache-maven-3.8.9\bin\mvn.cmd -q test`：exit 0；H2 Flyway 测试应用到 V104。最终 Gateway 副作用修正后 `-Dtest=ToolGatewayServiceTest,WorkflowExecutableContractTest,DeliveryGateServiceTest test` 亦 exit 0。 |
| Frontend | `npm test -- --run`：9 files / 25 tests passed；`npm run build`：exit 0。Windows 首次 `spawn EPERM` 后按正常权限机制重试通过。 |
| Workflow contract | `scripts/verify_workflow_contract.py`：`autospec-v5 workflow contracts are synchronized`。 |
| Compose 静态配置 | `docker compose config --quiet`、`--profile monitoring config --quiet`、`--profile verification config --quiet` 均通过。 |
| 历史兼容 | active `autospec-v5`、`autospec-v5-parallel`、诊断 contract 的工作树 blob hash 与 HEAD 一致。 |

候选关键标识：Reviewer `ReviewReportV2` schema hash 为 `620549ea2a9afda11a3b07ddc10a8158770e65619690cc1193f2cb4241c90824`；verification policy hash 为 `cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794`。这些不是 secret。

## 未执行与阻断

- `docker info` 失败：Docker API `npipe:////./pipe/dockerDesktopLinuxEngine` 不存在，因此未执行 Testcontainers `MySqlFailureRecoveryIT`，也未执行 verifier sidecar、验证 MySQL、真实 DDL 和 `tsc` L2。
- 未通过正式 `POST /api/workflow-runs` 跑六节点、审批/返工/ZIP 交付；离线 fixture 与受控 Gateway 测试不能替代正式 API E2E。
- 未进行 live 模型 smoke；本轮没有新的预算/授权，不把 `.env` 中 Key 的存在当作调用授权。
- 未执行真实 L2；Backend Loop 目前只在离线受控 Gateway 上验证 L1 feedback/replan，Reviewer 的 FULL verifier 也只有离线证据。
- 未重跑远端 CI；本地通过不代表远端 `master` 已绿。

## 后续最小顺序

1. 启动 Docker Engine，执行 `backend` 的 `-Pintegration-test -Dit.test=MySqlFailureRecoveryIT verify`，记录 detection/recovery/partial-write 三项结果。
2. 启动 verification profile，执行真实 verifier MySQL 与 TypeScript L2；确认 schema、临时目录和容器资源回收。
3. 在明确授权的单组 fixture 上通过 `POST /api/workflow-runs` 跑候选，记录 run/trace/fact、候选和策略 hash；成功后才更新 P0-G/P1-H。
4. 对已完成的 P1-F 增补真实 sidecar/L2 运行证据；若发现运行时差异，新增修复迁移/候选版本，不改写 active 或历史 SQL。
