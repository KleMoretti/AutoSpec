# AutoSpec Spec Sandbox 审查复核（2026-10-02）

这是审查后的状态快照，不是新增实施路线；修复任务与验收以 [总计划](../../autospec-v5-spec-sandbox-plan.md) 为准。保留 09-30、10-01 的原始证据，不把当时的“未 push / 未执行 CI”改成事后事实。

## 1. Git 与远端 CI 更正

- 经 GitHub API 确认，[PR #12](https://github.com/KleMoretti/AutoSpec/pull/12)（`feat: complete AutoSpec P2-P4 implementation and evidence`）于北京时间 **2026-10-01 22:01:10** 合并。
- 合并提交：`944f1bde23e7dd1cc42d45cd09fe3c6d7ab56fa9`；本地 HEAD、`origin/master` 和 GitHub master 均为该提交。
- 审查对象 PR head：`bcb3e0a681673dec742ba4e64b147903b3028ba5`；`git diff --stat bcb3e0a6 944f1bde` 为空，合并没有改变被审查的代码树。
- 更新文档前，工作区只有未跟踪的根目录 `package.json`、`package-lock.json`。本次不删除这两个文件。
- 合并后的 [Full-stack quality gate run 36873079480](https://github.com/KleMoretti/AutoSpec/actions/runs/36873079480) 为 `completed / success`；`backend-tests`、`agent-tests`、`frontend-tests`、`compose-and-images` 四项全绿。PR head 的同名检查也已通过。
- 因此撤回“分支未 push”“远端没有新 CI”“master 最新 CI 失败”“需要决定是否 push”的当前判断。09-22 的 MySQL IT 失败已被后续通过结果取代；本次没有分析中间失败日志，也没有做独立稳定性实验。

## 2. CI 覆盖边界

依据合并树的 [quality.yml](../../../.github/workflows/quality.yml)：

| 已覆盖 | 未覆盖 |
| --- | --- |
| 后端 `mvn test` 与 `-Pintegration-test verify`（Testcontainers） | `verification` profile 的 Compose 配置检查 |
| Agent 全量 pytest、WorkflowSpec 契约脚本 | `spec_verifier/Dockerfile` 镜像构建 |
| 前端测试、生产构建 | 真实 MySQL + `tsc` 的 L2 执行及重复验证 |
| 默认 / monitoring Compose 校验；后端、Agent、前端三个镜像构建 | 整栈启动后经正式 API 完成一次工作流；隔离与资源限制探针 |

普通 CI 已满足“当前合并提交全绿”，不能作为 Spec Sandbox L2 验收。Agent 测试套件没有引用 `run_l2`、`_verify_mysql` 或 `spec_verifier.sandbox`；其全绿没有覆盖审查中的清理问题。

## 3. 仍成立的审查结论

- **代码事实**：v12 Backend 已开启有界循环（7 steps / 1 replan），Backend `BACKEND` 与 Reviewer `FULL` 均强制 verifier，但 `required_level` 都是 `L1`。不能再写“默认循环全关”，也不能写“正式流程已有 L2 门禁”。
- **代码事实**：生产 Backend / Reviewer 仍调用 `fixtures.py` 的推断适配器；显式适配器没有生产调用方。前端绑定不携带请求参数与响应消费字段；`bindings.ts` 不 import `client.ts`，现有 `tsc` 不证明跨 Artifact 消费一致性。
- **代码事实**：L1 未接入 OpenAPI 校验器或 SQL 解析器，绑定检查缺必填参数、位置/类型及响应字段，FK 只比较 `kind`。L2 把规格失败与环境错误统一报为 `ERROR`。
- **静态推演，待真实 MySQL 复现**：DDL 按表名字典序创建、逆序删表，不等于外键依赖逆序；校园交易 `favorite→product`、请假审批 `leave_approval→leave_request` 存在先删父表的路径。清理异常被吞掉，固定 schema / 持久卷可能令同一合法规格第二次验证误报 `L2_DATABASE_SCHEMA_FAILED`。
- **静态推演，待冷启动复现**：Java 的 L1 也请求 sidecar；README / AGENTS 的普通启动没有开启 `verification` profile，`.env.example` 缺 `VERIFY_MYSQL_*`。默认启动与 v12 必需服务存在依赖缺口。
- **代码事实**：已有 internal network、non-root、CPU / 内存限制；缺只读文件系统、capability / PID 加固、tmpfs、随机 schema、专用 verifier Token；请求体整体读入后才限大小，服务允许 900 秒超时。运行期隔离与硬限尚未验收。
- **证据缺口**：未找到正式运行中的“verifier 失败→Replan→修复”Trace，也没有 D 对 A 的完整 live 质量对照。三个 fixture 各一次 L2 PASS、9 个手写缺陷、单次 live 成功均不能替代这些证据。

以上结论经本次源码/配置阅读复核；本次没有运行应用测试、Docker、MySQL 或付费模型调用。既有测试数字和运行结果仍引用 [P0/P1 证据](p0-p1-execution-2026-09-30.md)、[P2–P4 证据](p2-p4-2026-10-01.md)，没有重跑或改写它们。

## 4. 状态处理

总计划与两份手册撤回相关 P1 / BASE-02 的整体验收完成判断，保留已实现代码和已取得证据；P2 live 仍缺完整矩阵，且须先修复并重新冻结验证器。远端普通 CI 已通过，不再列为阻塞。MCP / A2A / Skills、变更影响分析保留既定范围取舍，CI 更正不自动扩大实现范围。
