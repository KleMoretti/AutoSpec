# AutoSpec 审查增量复核：bc00b0d6

日期：2026-10-02。对象：本地 HEAD `bc00b0d66467a6ed2604d847b9d803e77006720d`，已合并 PR #13。此记录更新用户粘贴审查及同日早先的 [复核快照](spec-sandbox-review-2026-10-02.md)，不改写旧时点记录，也不新增实施路线。

## 结论

旧审查需要更新。随机 schema 清理、自动化回归、默认部署依赖、隔离配置、正式 fixture 的 L2/Replan/终止/交付负例及远端 Sandbox CI 都有新进展，不能再称为“只有骨架、零测试、没有正式 Replan、远端 CI 未运行”。

但不能把整个 P1 标为完成：**显式契约路径虽已实现，当前候选和新消融配置仍选旧 verifier；真正的 L2 规格错误仍归为 ERROR，无法触发 Replan；输入和执行总 deadline 的硬限尚未闭环。** 完整 live 质量对照与 DAG 展示也未完成。

## 本轮核验范围

- 当前源码、canonical 候选、A/B/C/D 冻结配置、三份主计划及归档证据。
- GitHub API 确认当前 master [CI run 37000055989](https://github.com/KleMoretti/AutoSpec/actions/runs/37000055989) 为 `completed/success`，HEAD 与本地相同；`verifier-sandbox-probes` job 及其启动、运行 probe 步骤均为 success。
- 尝试读取该 job 的完整日志时遇到 GitHub API 403 rate limit；没有声称逐行复核了远端日志。CI 覆盖边界依据 job 元数据、workflow 和 probe 源码判断。
- 本地执行 6 个相关 pytest 文件，**40 passed in 1.96s**：`test_spec_verifier.py`、`test_spec_verifier_service.py`、`test_explicit_contract_adapter.py`、`test_candidate_verification_loop.py`、`test_spec_sandbox_candidate.py`、`test_agent_loop.py`。
- 另执行无网络的策略分支与报告分类检查，结果如下。没有重启服务、运行真实 MySQL/tsc、调用付费模型或重跑三端全量。
- 检查本地 `.env` 仅报告存在状态：`SPEC_VERIFIER_SERVICE_TOKEN`、`VERIFY_MYSQL_PASSWORD`、`VERIFY_MYSQL_ROOT_PASSWORD` 三项均缺失；未输出值或修改文件。
- 本轮开始已有未提交的 P0/P1 手册修改、同日复核草稿及根 package 文件，均未覆盖。

## 旧审查逐项更新

| 原判断 | 当前判断 |
| --- | --- |
| 默认门禁只要求 L1 | 仍成立：新库默认 v12 仍为 L1。但另有未默认激活的 `spec-sandbox` 候选要求 Backend/L2 与 Reviewer/FULL/L2，不能说仓库没有 L2 正式链路 |
| 逆字典序删表导致二次验证失败 | 已修：每次随机 schema，finally DROP DATABASE，清理失败显式报错。相关 fake-driver 回归本轮通过；真实重复/并发与无残留结果见既有证据 |
| L2 零自动化测试 | 已过时：有 schema/失败清理单测、service 边界回归、真实 L2 CI probe。仍未覆盖所有 runtime deadline/资源故障 |
| 显式适配器是死代码 | 不能再称无调用方；v2 分支及测试已存在。但当前候选实际不选该分支，接入问题尚未关闭，见 R1 |
| TS 只检查同源常量表 | v2 编译器已经使用独立前端参数/响应声明并 import client；v1 仍为旧路径。当前候选选 v1，不能用 v2 单测证明候选已完成独立消费检查 |
| L1 无必填/类型/响应/FK 精度检查 | v2 已增加这些检查。但实现仍是自定义 OpenAPI JSON 检查和 DDL 正则解析，不能宣称已经接入标准 OpenAPI validator 或 SQL parser |
| 普通启动漏 verifier，env.example 缺配置 | 代码和 README 已修：verifier/MySQL 纳入默认服务，env.example 含独立 Token 与 MySQL 配置；本地真实 `.env` 三项仍缺失，当前机器冷启动未验收 |
| 缺只读根、cap/PID/tmpfs、随机 schema、专用 token 和 probe | 这些实现与 probe/远端 job 已增加。body 先整体读取、900 秒服务上限、执行总 deadline 与主动故障探针仍需收尾 |
| L2 规格错误被标 ERROR | 仍成立；循环虽能区分 FAILED/ERROR，但 L2 生产报告没有正确分类，见 R2 |
| 没有正式 Replan/终止 Trace | 已过时：有正式 API/Redis Worker 的 fixture run 4 成功修复、run 5 REPLAN_LIMIT、run 3 预算拒绝。不是 live 模型自我修复证据 |
| 未 push、master CI 红、Sandbox job 未跑 | 已过时：PR #13 已合并，当前 master 整体 CI 和 Sandbox job 已绿 |
| fixture 批次 12/192、0/96 | 是旧批次历史。新 r1 记录 development 192/192、holdout 96/96 完成，仍为 fixture、NOT_EVALUATED，不能当 live 质量收益 |
| 总计划仍 planned | 已过时：总计划为 in_progress，但部分开头、状态表和同日记录仍互相滞后 |

## 必须优先处理的剩余项

### R1：候选没有启用显式契约分支（P1-A/B/C/H、BASE-02）

当前 `autospec-spec-sandbox.workflow.json` 的 Backend/Reviewer 都冻结：

```text
verifier_version = spec-verifier-v1
compiler_version = spec-compiler-v1
required_level = L2
```

[Backend 分支](../../../agent-engine/runtime/backend_agent_loop.py:688) 与 [Reviewer 分支判断](../../../agent-engine/runtime/production_handlers.py:474) 都要求 `verifier_version == spec-verifier-v2` 才用显式适配器。否则调用仍会猜 PK/FK/参数位置的 `spec_contract_from_artifacts`。

本轮使用实际策略和 registry 检查得到：

| 配置 | Backend/Reviewer explicit 分支 | Backend/Frontend 输出注册 |
| --- | --- | --- |
| pm-schema-repair-v12 | false / false | BackendDesignArtifact / FrontendSkeletonArtifact |
| spec-sandbox | false / false | BackendDesignArtifact / FrontendSkeletonArtifact |
| 新 r1 A/B/C/D 四组 | 全部 false / false | 同上 |

因此“显式 adapter 接线代码存在”成立，“正式 spec-sandbox 候选已经使用显式输入”没有被当前 canonical 配置支持。现有定向测试手工提供 v2 policy，所以 40 项全绿不会发现候选仍选 v1 的问题。

**下一步**：完整升级并新增未激活候选的 Handler/Prompt/输入输出 Schema/verification policy，不能只替换 verifier 字符串；同步种子/注册/上下文/前端消费者并验证兼容。冻结一项从实际候选 JSON 出发的接线回归，断言产生 `SpecContractV2` 且没有调用推断适配器。重新跑一条正式 FULL/L2，核对 report 的 compiler/verifier 版本，再重新冻结 A/B/C/D。已发布候选不原地改写。

### R2：真正 L2 规格失败不能触发修复（P1-E/F）

[sandbox.py:208](../../../agent-engine/spec_verifier/sandbox.py:208) 仍对所有非空 issues 返回 `ERROR`。本轮调用真实 `compiled_report` 的结果：

```text
L2_TYPESCRIPT_FAILED       -> ERROR
L2_DATABASE_SCHEMA_FAILED -> ERROR
L2_TYPESCRIPT_TIMEOUT      -> ERROR
```

[Backend loop](../../../agent-engine/runtime/backend_agent_loop.py:390) 仅在 `FAILED/BLOCKED` 时 Replan，遇到 ERROR 直接 `VERIFICATION_ERROR` 终止。新运行记录里注入的是 L1 的 `TABLE_PRIMARY_KEY_INVALID`，它证明 L1 反馈回路，不能证明真实 tsc/DDL 错误可修复。

**下一步**：根据失败原因区分可修复的规格不一致与基础设施错误；DDL 语法/约束、TS 类型错误返回 FAILED，连接/鉴权/超时/清理失败等返回 ERROR。不能把全部数据库异常一律改为 FAILED。复用一条真正 L2 失败样例与一条环境超时样例验证不同终止路径，不用扩展巨大测试矩阵。

### R3：硬限仍有代码和验收缺口（P1-E）

- [service.py:79](../../../agent-engine/spec_verifier/service.py:79) 在鉴权前 `await request.body()`，读取完整 body 后才做 512 KiB 判断；返回 413 不代表流式内存上限有效。
- [service.py:30](../../../agent-engine/spec_verifier/service.py:30) 仍允许 `timeout_ms=900000`，本轮模型解析检查确认接受。现有 probe 的 422 输入是 **900 ms 小于下限**，不是证明超时上限或运行中 timeout 回收。
- [run_l2](../../../agent-engine/spec_verifier/sandbox.py:35) 给 tsc 和 MySQL 分别传完整 timeout；MySQL 又把它用于各次 socket 等待。没有共享单调时钟的剩余总 deadline，调用方 35 秒 HTTP timeout 也不会自动取消服务器验证。
- probe 核对 Docker 资源配置、成功 L2/清理和外网不可达；没有主动触发内存/PID/进程超时，也没有分别验证业务 MySQL/Redis 的 DNS 和实际私网地址不可达。

**下一步**：先鉴权并限流读取 body；共享 deadline 并落实 SQL/子进程取消、清理；补一条运行中 timeout 回收，以及计划要求的隔离/资源边界验收。继续复用现有 probe，不把新增 probe 数量当完成目标。

### R4：本地部署与默认版本仍需收口（P0-F、P1-H）

默认 Compose 依赖关系和模板已经修复；真实 `.env` 缺少三项 verifier 配置，不能用 CI 的临时环境替代用户本地冷启动验收。通过适当的本地秘密配置后，以当前版本完成启动、审批、一次完整交付并记录。

默认 v12 仍为 L1 是版本选择事实，不要求为了“看起来完成”立即切 active。应在新显式 L2 候选验证及既定晋级条件满足后，再决定默认升级；不得改写 V1 基线。

### R5：标准校验器与 L2 增量价值仍需明确（P1-B/C）

v2 已有生成物结构检查，但 [OpenAPI 检查](../../../agent-engine/spec_verifier/validators.py:252) 是 `json.loads` 加手写字段检查，[DDL 检查](../../../agent-engine/spec_verifier/validators.py:319) 是模板正则。它们可以验证受限生成格式，覆盖面不等于完整规范 validator/parser。

按总计划实现标准校验器，或者明确收窄验收标准并说明支持子集；不要把手写正则称为完整 MySQL 语法验证。真正数据库落库由 L2 提供。

不必为了旧审查建议机械把 9 个变成 30 个。先补上述接线/分类/超时必要反例，再用同一小型样例集记录 L1 与 L1+L2 的实际差异、误报和耗时。FK 精度与响应字段目前已是 v2 L1 检查，不能为了让 L2 显得更强而从 L1 删除，也不能预设它们是“只有 L2 才检出”。

## 距离各阶段完成的距离

| 阶段 | 已有依据 | 剩余必选项 |
| --- | --- | --- |
| P0 | fixture 与单次 live 六节点/审批/导出记录，通用规则和有界输出修复，当前远端 CI 全绿 | 本地 verifier 配置与冷启动、根 package 文件归属/处理、文档状态对齐 |
| P1 | 编译/L1/L2、随机清理、显式分支、正式 fixture Replan/终止/交付负例、Sandbox CI | R1–R5；尤其候选显式接线与 L2 错误分类，不是只补文档即可关闭 |
| P2 | 新数据与配置冻结、采集器/统计、完整 fixture 矩阵和容量/恢复证据 | 修正候选后重新冻结并开展经授权的 live 对照；没有可宣称的 D>A 质量收益或 PROMOTE 结论 |
| P3 | 可靠性、注入回归、语料/记忆、native 协议适配、本地 embedding 对照已有实现和记录 | native 5 条 fixture 对照仅验证协议，不是模型选择质量；embedding 小基准饱和不证明收益。若要声称收益，需要另有对应评测 |
| P4 | Trace 时间线、矩阵、验证面板、评测看板、演示与 15 张卡片已有记录 | 动态 DAG 图未验收；最终介绍/看板数字要随真实 live 结果更新。截图归档按用户既定豁免 |
| 可选扩展 | MCP / 需求变更影响分析明确 deferred | 不作为强制补齐项，不为了面经关键词自动扩范围 |

P2 当前文件记录的 576 CNY 是 288 次运行按每次 2 CNY 计算的保守预占上限，不是实际账单。本轮不核定价格、不申请/执行付费批次。先完成 R1/R2，再在预算授权、样本规模和冻结协议确定后执行，避免花钱测仍走旧契约的 D 组。

## 建议执行顺序

1. R1：新增显式 v2 候选，核对真实运行所选版本、schema 与报告版本。
2. R2：修正 L2 FAILED/ERROR，证明一条真正 L2 缺陷反馈与一条环境失败终止。
3. R3/R4：硬限与取消清理、本地秘密配置及冷启动验收。
4. R5：规范校验范围与最小增量数据；更新 P1-A/BASE-02 等过早完成标记。
5. 重冻结 P2 live，按授权执行；补 P4 DAG 和最终数据展示。

本轮只做审查与文档记录，没有修改业务代码、候选、数据库、环境配置或任何计划已有未提交内容。
