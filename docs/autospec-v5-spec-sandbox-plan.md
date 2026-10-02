---
plan_id: autospec-spec-sandbox
version: 1.1
status: in_progress
created_at: 2026-09-29
updated_at: 2026-10-02
product_baseline: autospec-v5:pm-schema-repair-v12
database_baseline: V1
predecessor: docs/archive/agent-execution-plan.md
---

# AutoSpec 面试就绪与特色功能计划（Spec Sandbox）

> 2026-10-02 审查更新：PR #12 已合并，master 普通 CI 已全绿；生产显式契约、独立前端消费检查、L2 重复执行和正式 Replan 证据已分批补齐，Sandbox 的正式负例、终止 Trace、真实 `.env` 冷启动和完整扩展 CI 仍未验收。本文保留分阶段状态与完成边界。审查方法、Git/CI 更正及覆盖边界见 [10-02 复核记录](archive/evidence/spec-sandbox-review-2026-10-02.md)。

> 本文件是当前任务总计划，具体入口和历史执行过程见 [P0/P1 执行手册](p0-p1-execution-plan.md)、[P2–P4 执行手册](p2-p4-execution-plan.md)。两份手册属于同一任务；与本次审查冲突的旧 `done` 或“未 push / CI 待验证”判断，以本次状态修订为准。其他文档、样例与证据放在 [archive/](archive/README.md)，只作参考，不生成另一条实施路线。文件名按用户指定保留，产品名称统一为 AutoSpec。

> 数据库已于 2026-09-30 经授权收敛为 V1，后续迁移只增不改，从实际最大版本 + 1 分配；当前源码已有 V2。保留运行的 WorkflowSpec / Prompt / Schema / Handler 冻结版本不变，不能恢复 V104–V117 或重写 V1。详见 [基线收敛记录](archive/evidence/baseline-consolidation-2026-09-30.md)。新数据库的产品基线为 `pm-schema-repair-v12`；旧 `v5-parallel` 仅为历史兼容输入。

## 1. 结论

AutoSpec 的六节点编排、可靠性、审批、成本与可观测底座已经落地，Spec Sandbox 也有真实的编译器、L1 和隔离执行骨架；**特色功能整体仍为 `in_progress`，不能宣称已完成可执行验证与自我修复闭环。**

- v12 的 Backend 已开启有界循环（`max_steps=7`、`max_replans=1`、`backend-design-v2`），Backend 和 Reviewer 已强制调用 verifier；新 `spec-sandbox` 候选将其冻结为 Backend `BACKEND/L2`、Reviewer `FULL/L2`。旧 v12 的 L1 运行与候选 L2 证据仍按版本区分。
- 主要剩余缺口是正式预算/震荡终止 Trace、正式证据负例、真实 `.env` 冷启动和远端 Sandbox CI；正式 fixture 的失败反馈→Replan→修复 Trace及隔离 hard-limit 本地探针已经补齐。没有完整 D 对 A live 对照。
- [PR #12](https://github.com/KleMoretti/AutoSpec/pull/12) 已于北京时间 2026-10-01 22:01 合并；master `944f1bde` 的 [CI run 36873079480](https://github.com/KleMoretti/AutoSpec/actions/runs/36873079480) 四项全绿。该 CI 没有构建 verifier 镜像、执行 L2 或运行整栈工作流，不能关闭 Sandbox 缺口。
- 当前优先级是**修复验证器和输入事实→建立正式 fixture 闭环与 L2 增量证据→重新冻结 live 实验**。MCP、变更影响分析等扩展继续按范围取舍，不抢占这条主线。

目标仍为：每个高频面经主题有可核验的“代码 + 证据 + 讲法”，并交付一个可演示、可度量、能明确说明局限的 Spec Sandbox。

## 2. 当前差距与证据边界（2026-10-02）

图例：**源码**＝本次读代码/配置确认；**推演**＝根据源码推断，尚未真实执行；**记录**＝引用既有运行证据，本次未复跑；**远端**＝本次 GitHub API 确认。测试套件数量不是本次执行结果。

### 2.1 Sandbox 必修项

| # | 依据 | 当前事实与缺口 | 修复归属 |
| --- | --- | --- | --- |
| S1 | 源码 / 记录 | 新增 [spec-sandbox](../agent-engine/contracts/autospec-spec-sandbox.workflow.json) 候选，Backend `BACKEND/L2`、Reviewer `FULL/L2`；独立 project 已完成三领域重复/并发 L2，正式 run 2 通过六节点/L2/导出，run 4 又完成故意缺陷→Replan→修复 Trace | SIG-P1-04 |
| S2 | 记录 / 部分完成 | `a1b2187` 改为每次随机 `autospec_verify_<hex>` schema 并在 `finally` 执行 `DROP DATABASE`；`66d410a` 独立 project 三领域各两次、三次并发均通过且无残留，`9b3d282` 增加运行期清理/网络/请求上限探针。真实 `.env` 冷启动和主动资源故障注入仍待验收 | SIG-P1-03 |
| S3 | 记录 / 部分完成 | `tests/test_spec_verifier.py` 和 service 回归覆盖随机 schema、部分失败清理、清理异常、请求/响应/超时边界；真实 6 次重复、3 条并发及残留查询、隔离探针均有记录。远端 Sandbox CI 尚待实际运行 | SIG-P1-03 |
| S4 | 源码 / 记录 | `cb4355b` 已将显式适配器接入 Backend/Reviewer 生产路径，候选输入声明 PK、FK、受限类型、参数位置和来源；旧推断适配仅保留给兼容版本。`1023024` 与候选测试覆盖缺字段拒绝和非命名事实不推断 | SIG-P1-00 |
| S5 | 源码 / 记录 | `1023024` 已补独立前端请求/响应映射、path/query/body client 与消费桩，绑定参数/响应语义进入 L1；真实 verifier 中的 tsc 增量检出和跨 Artifact 样本仍待 P1-E/CI | SIG-P1-01 |
| S6 | 源码 / 记录 | `1023024` 已补 OpenAPI/DDL 结构解析、参数/响应绑定语义和稳定 issue code；保留 9 个最小缺陷并继续补分层变异、误报与耗时证据 | SIG-P1-02 |
| S7 | 源码 / 记录 | `1efbfc8` 已将 verifier/verify-mysql 纳入默认 Compose 依赖并补专用 Token 与 `VERIFY_MYSQL_*` 配置；真实 `.env` 缺 3 个新变量，使用 `.env.example` 的独立 project 已可启动并通过认证 L2，真实 `.env` 冷启动到 Backend 仍待复现 | SIG-P0-03 / P1-03 |
| S8 | 源码 / 记录 | `1efbfc8` 已落地 internal network、non-root、read-only、cap drop、no-new-privileges、pids/tmpfs、CPU/内存限制，`a1b2187` 提供随机 schema；`9b3d282` 的实际容器探针证明资源/网络/清理/请求边界，真实 `.env` 冷启动和主动资源故障注入仍待覆盖 | SIG-P1-03 |
| S9 | 源码 / 记录 | `1b5f2ff` 已将规格 FAILED 与环境 ERROR/BLOCKED/NOT_RUN 分流；旧容器无密码 DSN 的失败保留为环境错误，不进入 Replan | SIG-P1-03 / 04 |
| S10 | 记录 | `eeec0d59` 已将候选/fact 引用贯通持久化 Trace；run 2 证明成功 L2/门禁/导出，run 4 证明正式规格失败→Replan→修复，run 3/5 分别保留预算不足与 `REPLAN_LIMIT` 终止负例；`b60401a` 至 `d03541b` 补正式 Artifact/fact 导出拒绝，`c6144ee` 补三端全量回归。完整 live A/B/C/D、远端 Sandbox job 和真实 `.env` 冷启动仍待验收 | SIG-P1-04 / P2-02 / 03 |

### 2.2 基础 Agent 与展示

| 项目 | 当前状态 | 仍需的证据或动作 |
| --- | --- | --- |
| Live / 通用门禁 | 已有 v12 live run 38 六节点及导出成功；一次有界结构修复、三领域 fixture 与通用规则已实现 | 不再把 09-17 的 PM 失败或旧领域规则当成当前全貌；单次成功不证明总体收益 |
| CI / MySQL 故障恢复 | 远端 master 与 PR head 四项检查通过，09-22 IT 失败项已解决 | 不再安排 push 决定；未分析中间失败原因或测稳定性。为 Sandbox 增补 CI 覆盖 |
| 可靠性 | watchdog、poison 上限、outbox claim、cancel 已有代码及阶段故障证据 | 保留容量/故障条件，不把本地 fixture 数据扩展为生产 SLA |
| Function calling | 原生调用协议适配已实现，治理与台账存在 | 缺与 JSON-in-prompt 的工具选择/参数有效率对照；v12 工具白名单只有 `spec.verify` |
| MCP / A2A / Skills | 产品协议实现仍为空，MCP 明确延后 | 先准备取舍说明；仅有明确外部集成需求时选最小 governed MCP。`spec.verify` 有沙箱副作用，不能包装成纯只读工具 |
| RAG / 记忆 | 100 条、5 领域本地 semantic 与 hashing 对照已记录，Recall@5 都为 1.0；批准状态召回与 30 条注入回归已实现 | 基准饱和，无 semantic 收益证据；核对 v12 默认提示词是否使用项目检索，补记忆效果评测，不能由策略字段缺失直接推断没有 RAG |
| 展示 | Trace steps、追踪矩阵、验证面板、评测看板及 fixture Demo 已实现 | 动态 DAG 图未验收；看板有数据通路但完整 live 矩阵缺失，继续 `NOT_EVALUATED`；截图归档沿用用户豁免 |
| 仓库卫生 | 总计划/手册状态存在漂移，根目录两个 package 文件仍未跟踪 | 同步当前状态；核实 package 用途后再处置，不为清爽直接删除；变更影响分析继续 deferred |

## 3. 特色功能：Spec Sandbox

### 3.1 定位与完成边界

在规则检查和模型语义审查之外，将 API / 数据 / 前端消费契约确定性编译成可执行检查，用环境反馈支持 Backend 的有界修复，并给 Reviewer、Evaluator 与交付入口提供可信证据。L1 是结构校验，L2 是真实数据库/编译器执行；两者都不证明完整业务应用正确或可运行。

当前已经实现的是编译器、部分 L1、工具接线和独立 L2 骨架。下述架构与安全项是**修复后的目标**，未验收项不能写成现状。验证器不调用 LLM，fixture 不调用外部模型；两者仍有本地计算开销，live 生成/修复另计费用。

### 3.2 目标架构

```text
Worker（Backend BACKEND 验证 / Reviewer FULL 验证）
   │  spec.verify：冻结策略、来源版本与 scope
   ▼
Java Tool Gateway（权限 / fencing / 幂等 / deadline / 预算 / 台账）
   │  内网 HTTP + 专用 verifier Token
   ▼
spec-verifier sidecar（结构化输入，确定性编译，不执行模型代码）
   ├─ L1：OpenAPI 校验、DDL 解析、引用/类型/绑定语义检查
   └─ L2：随机 schema 的隔离 MySQL + 导入 client 的独立消费桩 tsc
   ▼
可信 VerificationReport / Fact（执行层级、issue、来源与策略 hash）
   → Backend Replan / Reviewer / Evaluator / Delivery Gate
```

- 验证范围来自冻结 WorkflowSpec。Backend 没有下游 Frontend 时，只验证后端可验部分；Reviewer 在汇合后执行 FULL。保留历史并行输入兼容，不在代码里硬编码六节点次序。
- `SANDBOXED` 已接入治理；节点仍须显式 allowlist，模型不能降低策略或把工具调用成功当成验证 PASS。
- 新修复候选目标为 **Backend `BACKEND/L2`、Reviewer `FULL/L2`**：前者提供可修复的环境反馈，后者提供交付所需的汇合证据。只要求 L1 的开发线须单独冻结并标注，不能声称 Sandbox 已验收。
- 保留 v12 和历史执行包。新契约/编译器/验证器/Prompt/Handler 版本按兼容影响分配，修复与实测通过后再考虑默认晋级；本次文档更新不切换工作流或启动实验。

### 3.3 显式输入与独立消费契约

SIG-P1-00 必须沿真实 `Agent→Artifact→adapter→SpecContract` 链路实现：

1. 后端产物显式提供 PK、FK 目标列、受限类型与参数位置，缺字段明确拒绝；允许显式 `foreign_key=null`，禁止用名字或 HTTP method 补事实。旧推断适配仅服务明确标记的历史兼容，不能进入新候选的可信验证。
2. 前端独立声明 `backend_api_id`、每个请求参数的名称/位置/来源类型/必填性，以及消费的响应字段路径、预期类型和可空性。空集合必须能与“字段未提供”区分；检查器输入不能由 API 契约反向补全。
3. 类型化客户端处理 path / query / body；消费桩按前端自己的映射 import 并调用客户端、读取声明响应字段。限制为受控的类型/路径表达，不执行模型写的 TS。
4. 仅改前端参数/响应消费或仅改后端响应时，检查结果必须产生可解释差异；相同正常契约输出逐字节一致。同源生成的客户端与字符串表通过 tsc 不能作为跨 Artifact 一致性证明。

### 3.4 检查目录与错误语义

| 层级 | 修复后要求 | 当前覆盖与待补 |
| --- | --- | --- |
| L1 | 实际 OpenAPI 3.1 校验、操作唯一、路径参数声明 | 已有操作/路径检查；补文档校验器，并将其错误映射为稳定 code/path |
| L1 | DDL 解析、PK/FK 目标/索引和 MySQL 兼容性、标识符安全 | 已有 PK/FK `kind` 检查与编译白名单；补解析及精度/长度规则，只按 MySQL 的实际兼容规则判断，不要求所有字符串长度一律相等 |
| L1 | 必填参数完整、位置/类型匹配、响应字段与可空性消费一致 | 目前主要检查操作漂移和参数名称；补 `BINDING_PARAMETER_MISMATCH`、`BINDING_RESPONSE_FIELD_MISSING` 等版本化 code |
| L1 | 引用与 MUST 追踪适用性 | 保留现有引用检查；明确 API / 数据 / UI 对每项需求是否适用，不强迫每个 MUST 都生成新表 |
| L2 | 独立 MySQL 8.4 中真实应用确定性 DDL | 已有两规格连续/并发及部分失败清理证据（`a1b2187`）；继续补第三 fixture、真实错误分类、资源/网络探针和专项 CI |
| L2 | `tsc --noEmit` 检查客户端与独立页面消费桩 | 当前字符串表没有消费关系；先补契约/生成器，再测增量检出 |
| 延后 | 完整前端构建、业务验收、离线 Java 骨架编译扩展 | 不纳入本轮 Sandbox 首版完成声明 |

已有 issue code 保持兼容；新增规则随版本冻结。规格导致的 DDL / TS / 契约错误返回 `FAILED / BLOCKED`，环境不可用、权限/认证、超时、资源限制或清理失败返回 `ERROR / BLOCKED`，缺检查为 `NOT_RUN`。成功判定要求声明检查实际执行完毕、没有阻断问题、来源/策略/scope/层级匹配，且清理成功。不能用捕获所有异常或吞掉清理异常制造 PASS。

能在 L1 可靠表达的错误应在 L1 检出。**保留字可能已被正确转义，响应缺字段应由补齐的 L1 检出，不能预先把它们指定为“只有 L2 才能发现”的缺陷。** L2 增量样本须以真实执行结果确定。

### 3.5 安全与资源边界（待验收）

- 只接受已通过 Schema 校验的结构化字段；SQL / OpenAPI / TS 均由白名单编译器生成。绝不执行 LLM 撰写的 SQL、代码或 shell，不挂载 docker socket、业务数据卷或业务密钥。
- sidecar 使用 non-root、只读根文件系统、`cap_drop: ALL`、`no-new-privileges`、PID/CPU/内存硬限和有容量限制的 tmpfs 工作目录；专用服务 Token 与通用 Agent Token 分离。
- verifier / verify-mysql 保持独立 internal network；用运行期探针证明不能访问业务 MySQL、Redis和公网，不能仅由配置字段推定隔离成功。
- verify-mysql 不保留数据卷；每次**实际执行**创建由验证器生成的随机 `autospec_verify_%` schema，最小权限用户仅能管理该前缀，最终 DROP DATABASE。请求幂等不要求重复执行共享 schema；并发执行不能互删资源。
- 全链路采用剩余 deadline，不能分别给 tsc、数据库完整超时而累计超限；流式读取并在上限前拒绝超大请求，限制并发、子进程输出和结果大小。进程强制结束后有可核验的残留回收机制。
- 清理失败记录执行/schema/错误并阻断；基础设施修复由平台处理，不送模型 Replan。不得通过删除业务卷、关闭 FK 检查或临时放宽门禁完成验收。

### 3.6 延伸特色（deferred）

需求变更影响分析与增量重生成仍复用 trace graph / `ReworkPlanner`，对应 SIG-P3-06。主线完成并明确选择后，再验证影响子图与未受影响组件 hash，测量相对全量重跑的 token 变化。MCP / A2A / Skills 不作为修复 Sandbox 的前置。

### 3.7 当前可使用的事实与表述

| 已有记录 | 可以说明 | 不支持的结论 |
| --- | --- | --- |
| 3 个正常规格、9/9 手写变异检出 | 既有最小回归覆盖的规则均检出该样本 | 独立基准检出率、泛化准确率或完整缺陷覆盖 |
| v12 live run 38：54,619 tokens、估算 0.130100 CNY、Evaluator `100/A/PASSED`，Markdown/PDF/ZIP 成功 | 一条指定配置的六节点 live 与导出通过样本 | 平均成本、总体质量、L2 门禁通过或真实 Replan 成功 |
| fixture 10/20 并发，30/30 完成 | 冻结 inventory 输入、本地隔离环境的容量观测 | 生产 SLA、live 吞吐或跨配置质量收益 |
| 普通 CI 四项全绿 | 合并提交在声明的质量门禁范围通过 | verifier 镜像、L2、整栈运行及隔离验收 |

以上数字来自 [P0/P1 证据](archive/evidence/p0-p1-execution-2026-09-30.md)、[P2–P4 证据](archive/evidence/p2-p4-2026-10-01.md) 及 [10-02 复核](archive/evidence/spec-sandbox-review-2026-10-02.md)。当前表述可写“实现确定性规格编译、L1 校验及受控验证工具接入，已有单次隔离 L2 和六节点 live 通过记录，正在补齐重复执行与修复证据”。门禁通过率 `X%→Y%`、Sandbox 自我修复成功率和 D 优于 A 暂无可填数字。

## 4. 路线图与当前状态

状态使用 `planned / in_progress / blocked / done / deferred`。`done` 只覆盖明确的验收范围；发现现实缺陷则重开，不删除历史通过记录。相对规模 S/M/L 不承诺工期。后续执行顺序以第 9 节为准，不重做已通过的无关工作。

### P0：可用性与可信度

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P0-01 | done（指定 smoke） | 一次有界修复与 run 38 正式 live/导出证据已有；不扩展为总体质量收益 |
| SIG-P0-02 | done（现有三领域回归） | 通用规则与三领域 fixture 已有；保留缺陷回归，不因 Sandbox 变更重写领域规则 |
| SIG-P0-03 | in_progress | MySQL IT 修复及普通远端 CI 已满足；修正默认启动依赖/文档，核实根 package 文件用途后处置。CI 通过不令整个卫生工作包自动完成 |

### P1：重开 Sandbox 验收

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P1-00 | done（L1/生产接线） | `cb4355b` 已接入显式适配器、Prompt/Schema、Backend v7 / Frontend v4 及 Reviewer/Backend 两条 v2 路径；缺字段拒绝，真实显式 PK/FK/位置不推断。候选冻结与正式 FULL/L2 仍属 SIG-P1-04 |
| SIG-P1-01 | in_progress | `1023024` 已补 path/query/body 客户端、独立消费桩和逐字节确定性；run 2/run 4 与 sandbox probe 已实际执行 `tsc --noEmit`，增量变异检出/误报/耗时报告仍待补 |
| SIG-P1-02 | in_progress | `1023024` 已补 OpenAPI/DDL 结构解析和绑定参数/响应语义；独立 L2 与正式 FULL/L2 已实际应用 MySQL DDL，分层变异检出/误报/耗时报告仍待 P1-E/CI |
| SIG-P1-03 | in_progress | 随机 schema / 可靠清理、隔离硬限、真实 MySQL 三领域重复并发和残留查询、运行期资源/网络探针已记录；真实 `.env` 冷启动、主动资源故障注入和远端 L2 CI 仍待覆盖 |
| SIG-P1-04 | in_progress | `66d410a` 已冻结候选并通过独立 L2；run 2 通过 fixture API/Worker、FULL/L2、可信来源、Evaluator/DeliveryGate 和导出，run 4 补齐失败→Replan→修复，run 3/5 补齐预算与 `REPLAN_LIMIT` 终止 Trace；正式 fact 导出负例仍待验收 |

### P2：证据与对照

| ID | 状态 | 剩余工作与验收 |
| --- | --- | --- |
| SIG-P2-01 | done（限定候选） | `p2-p3-20261002-r1` 已绑定 spec-sandbox A/B/C/D、全部输入/工具反馈策略、L2 verifier/编译器 hash、数据/价格/预算；版本 2–5 显式发布，旧版本未改写 |
| SIG-P2-02 | blocked（仅 live） | 新采集器已完成 development `192/192` 与 holdout `96/96` fixture；四组完整 fixture 均 `SUCCEEDED` 但 `NOT_EVALUATED`。完整 live 未启动，保守上限 `576 CNY` 被安全执行层拒绝，缺样本不能晋级 |
| SIG-P2-03 | done（限定环境） | 既有正式 Replan/预算/`REPLAN_LIMIT` Trace 加本轮冻结候选的 192/96 完整 fixture 矩阵已有来源关联；短样本与 fixture 不泛化为生产 SLA 或 live 收益 |

### P3：基础 Agent 补证据与可选扩展

| ID | 状态 | 当前范围与剩余验收 |
| --- | --- | --- |
| SIG-P3-01 | deferred | 有明确外部集成需求再选最小 governed MCP；读取 Trace 可只读，`spec.verify` 仍保留 SANDBOXED 权限。MCP 实现不等于 A2A / Skills 实现 |
| SIG-P3-02 | done（既有回归） | 30 条注入回归与不可信内容治理已有；新工具/契约改变时重跑相关边界，不宣传泛化攻击免疫 |
| SIG-P3-03 | done（协议层） | `06798eda`/`c5f4528` 接入显式 native tool_calls、allowlist、provider call ID、错误/并行边界和 no-tool turn；5 条同集 JSON/native fixture 对照通过，未测 live provider 质量 |
| SIG-P3-04 | done（本地对照） | 批准状态召回与 ACL/过期过滤已有；本轮复跑 100 条、5 领域、top_k=5 的 hashing/local semantic 同集对照，结果饱和且无收益提升，不切换生产外部 provider |
| SIG-P3-05 | done（既有故障范围） | watchdog / poison / outbox / cancel 已有实现和限定演练；不重复建设，也不泛化为全故障可靠性 |
| SIG-P3-06 | deferred | 变更影响分析只在主线完成且明确选择后实施 |

### P4：展示与讲法

| ID | 状态 | 当前范围与剩余验收 |
| --- | --- | --- |
| SIG-P4-01 | in_progress | Trace、矩阵、验证与看板实现已有；动态 DAG 图未验收，L2 与真实修复证据需可下钻；完整评测证据仍依赖 P2 |
| SIG-P4-02 | in_progress | 五分钟 fixture Demo 已有，截图归档按用户豁免；修正 verifier 启动依赖、环境说明与完成状态后冷启动复验 |
| SIG-P4-03 | in_progress | 15 张问答卡片已有；刷新 Sandbox/CI 断言与引用，所有数字保留限定条件 |

### 依赖与截断线

```text
P1-03 清理/重复执行 ─┐
P1-00 显式生产契约 ─┼─▶ P1-01/02 独立消费与检查 ─▶ P1-03 部署/隔离/CI
                    └──────────────────────────────▶ P1-04 FULL/L2 + 正式修复 Trace
                                                      └─▶ P2-01 重冻 ─▶ P2-02 live 对照
```

L1 开发线可以独立演示“规格静态检查”，不能以此关闭 Sandbox 的 P1-03/04 或替代 L2。基础 Agent 的已验收能力继续保留；可选扩展不成为必选项的借口，也不以缺预算阻塞本地修复与 fixture 证据。

## 5. 面经覆盖矩阵

| 面经主题 | 当前可讲 | 待补证据 |
| --- | --- | --- |
| 架构 / 多 Agent / HITL | 冻结 Spec、六节点 DAG、Outbox/Worker、审批与返工 | 使用当前默认与历史兼容的正确版本说明 |
| ReAct / 有界循环 | v12 Backend 的步数、Replan、预算/震荡停止机制 | 正式失败反馈→修复和终止 Trace；首轮 PASS 不等于自我修正 |
| Function Calling / 工具治理 | allowlist、fencing、幂等、台账及原生协议适配 | 同集 native/JSON 对照；工具薄的取舍 |
| MCP / A2A / Skills | 目前未实现，按外部需求延后 | 如选 MCP，给实际调用/权限台账；否则如实说明边界 |
| RAG / 记忆 / 上下文 | 项目级链路、预算、批准召回，本地 100 例对照 | 默认使用证明、困难样本、记忆召回效果；Recall 饱和不证明收益 |
| 幻觉 / 护栏 / 结构输出 | Schema、有界修复、引用门禁、30 条注入回归 | 完整显式事实与跨 Artifact 检查，不把推断结果当真值 |
| 评估 / 成本 / 时延 | 采集器、统计/门禁、账本与看板，负面 smoke 记录 | 完整 live 四组、置信区间与重复数据；预占和实际成本分列 |
| 可靠性 / 可观测 | watchdog、DLQ、claim、取消、Trace steps 与容量故障证据 | 不将 fixture 排队/执行值泛化为 live SLA |
| Sandbox / 安全 / 交付 | 确定性编译、L1、受控接线、单次独立 L2 | 隔离探针、L2 增量、重复执行、FULL/L2 可信门禁 |

## 6. 评测与预算

### 6.1 先评验证器，再评 Agent

验证器实验不调用外部模型：保留 3 个正常规格与 9 个历史反例，围绕新增现实风险建立规则变异集，**目标至少 30 个有效样本**，覆盖参数、响应、PK/FK、追踪、DDL 与隔离/执行错误；不为凑数复制同一缺陷。按基础规格/缺陷族聚类，报告手写或生成方式、训练/校准/保留集划分及依赖关系，不能将同源变异当独立业务样本。

用同一输入对照 L1 与 L1+L2，列出逐样本检出层级、误报、漏报、稳定 code 与耗时。至少取得 **3–5 个经真实 MySQL/tsc 确认的 L2 增量反例**；若补齐 L1 后没有增量，应如实报告并调整 L2 价值判断，不故意削弱 L1。用获准使用的既有真实产物补充复扫，先独立标注真值，区分旧字段缺失、规格缺陷和验证器误报；脱敏保留来源版本/hash。

### 6.2 A/B/C/D 定义与有效样本

- A：single-shot 实验基线；**不是当前默认路径的描述**。
- B：Backend 循环 + 确定性结构校验。
- C：与 B 同一循环/预算策略 + 只读工具。
- D：与 C 相同的循环/只读工具，再向生成侧提供 `spec.verify` 反馈。
- 四组最终判分器相同，包括修复后的 L2，A/B/C 的最终 verifier 结果不能提前回灌生成侧；C/D 不得同时改变 Replan 开关。冻结原生/JSON 调用模式、上游输入、上下文/检索、Prompt/Schema、工具和预算，才能归因反馈收益。
- 分节点级固定上游对照与经 `POST /api/workflow-runs` 的六节点对照；保留 8 smoke / 16 development / 8 holdout、至少 5 领域，每格至少 3 次重复。最终阈值、成本与时延上限在新 manifest 中预注册。
- Wilson 95% 区间与按 case 聚类的配对 bootstrap；holdout 只作一次正式决策，不能看结果后改规则/数据。结论可 `REVISE` 或 `REJECT`，缺组/重复/有效样本则 `NOT_EVALUATED`。

历史结果保留：r11 fixture development 为 **12/192** 通过、holdout **0/96**，失败均为 `QUALITY_GATE_BLOCKED`；它证明采集完整并暴露该配置失败，不能归因 Sandbox 的质量收益。live smoke 为 1 case × 1 repetition × 4 groups，无可用交付通过的四组对照；D-only 重试跑完六节点但被 Evaluator 阻断，也不能单独与 A 比较。

### 6.3 预占、费用与启动条件

[10-01 记录](archive/evidence/p2-p4-2026-10-01.md) 中 r12 已冻结并通过 `--validate-only`，完整 development 192 + holdout 96 = **288 个 run**，每 run 上限 2 CNY，需 **576 CNY 保守预占**；已有授权标识，但安全执行层未接受该批量启动，因此 r12 没有新增 live run/费用预占。不能把阻塞一概写成“尚未申请预算”。

576 CNY 是预占上限，不是预计账单。按已有约 0.13–0.19 CNY/run 外推约 40–55 CNY；按原计划 0.2–0.3 CNY/run 外推约 60–90 CNY。这些只基于历史配置/价格/用量，不含重试，修复后的 L2/Replan 配置尚无成本分布，不是现价或费用承诺。

修复完成后再核对：保留完整规模与原预占、依据实测包络调整预占，或缩小规模并下调结论范围。任何输入、版本、预占或规模变动必须重新冻结并核对仍在用户授权内；超出则取得新的明确授权，执行层须接受实际动作。旧 manifest/预算台账不可改写，也不能以切小批或换执行入口绕过批量启动限制。当前文档更新只推进计划，不执行付费实验。

## 7. 约束、风险与回退

沿用 AGENTS.md：保留六节点与正式 Worker 链路，不新增 `/generate*`、固定 DAG 或任意执行工具；Worker 不直连业务 MySQL。WorkflowSpec、已发布 Prompt/Schema/Handler 和历史运行冻结不动；Flyway 从实际最大版本 + 1 新增，V1 不改写。真实 Key 不入库、不回显 `.env`。日常仅跑相关测试，跨模块契约/数据库变更或里程碑才做三端回归。

| 风险 | 控制与回退 |
| --- | --- |
| L2 清理、共享 schema 或并发污染 | 每次执行随机 schema、清理可观察；异常阻断并回收。不关 FK 检查或清业务卷掩盖失败 |
| 验证器与生成器同源自证 | 独立前端消费事实、单边变异、外部解析器和真实执行；判分器对四组相同 |
| sidecar 不可用 / 超时 / 输出超限 | readiness/预检、全局 deadline、流式大小限制；环境 ERROR 终止，冻结要求 L2 的运行绝不降级 |
| 验证器误报或新增规则不准 | 对照独立真值与现实兼容规则；阻止该候选晋级，修复后重冻。不能默认把已有 HIGH 降为 MEDIUM |
| 正式运行错误被模型反复“修环境” | FAILED 与 ERROR 分流，Replan 共享调用预算；停止原因入 Trace |
| 对照结果不佳或不完整 | 保留失败、缺样本与负面结论；不改旧结果，不凭单次 PASS 激活新默认 |
| 旧文档或 CI 状态漂移 | 以带日期/commit/run 的新记录更新当前手册；历史证据原样保留，声明 CI 实际覆盖范围 |
| 范围膨胀 | Sandbox 主线先验收；MCP、A2A、Skills、影响分析及完整应用生成不自动加入 |

新候选失败时停止其后续运行，保留 run/fact/issue；当前产品基线仍按已有部署选择运行。L1 开发演示使用独立冻结策略并明确层级，不修改已要求 L2 的运行或覆盖 v12。

## 8. 实施工具与验证范围

使用 [AGENTS.md](../AGENTS.md) 的固定本机工具，不安装插件或改环境来完成文档整理。修复实现时复用现有定向 pytest/JUnit；只新增核心成功与必要高风险反例，真实 L2 不能由 mock 或普通 CI 代替。

本次仅做源码/配置复核、GitHub CI 查询及文档验证；没有运行应用测试、Docker/MySQL 或 live 调用。Agent 226 / Backend 185 / Frontend 29 是 10-01 证据文件的历史数字，不标为本次通过。

## 9. 下一批可执行任务（按顺序）

| 顺序 / 原计划映射 | 动作与入口 | 最小必要验收 / 停止条件 |
| --- | --- | --- |
| 1 / SIG-P1-03 | **已完成本步**（`a1b2187`）：`spec_verifier/sandbox.py`、Compose 使用随机 schema、整库 finally 清理和显式清理失败；详见 [P1-03 证据](archive/evidence/p1-03-l2-schema-cleanup-2026-10-02.md) | 校园交易/请假各连续两次真实 MySQL 通过；两条并发验证通过；部分 DDL 失败清理且无残留；清理异常有明确 issue。SIG-P1-03 的隔离硬限、第三 fixture、专项 CI 仍由第 4/7 项继续 |
| 2 / SIG-P1-00 | **已完成 L1/生产接线**（`cb4355b`）：`schemas/`、Backend/Frontend Prompt、`artifact_adapter.py`、两个 v2 生产调用点和独立映射已接入 | 新候选缺字段拒绝；非命名惯例 PK/FK/位置保留；旧回放兼容；两条生产路径显式事实通过。候选冻结与正式 FULL/L2 仍由 SIG-P1-04 验收 |
| 3 / SIG-P1-01 / 02 | **已完成 L1 部分**（`1023024`）：`compiler.py`、`validators.py` 补 OpenAPI/DDL 校验、完整客户端、import client 的消费桩和稳定 issue | 正常规格、参数/响应单边变异、同输入逐字节一致已通过；真实 tsc/MySQL L2 增量和分层报告待 P1-E/CI |
| 4 / SIG-P0-03 / P1-03 / P4-02 | **已完成代码与本地隔离探针**（`9b3d282`）：默认 verifier 依赖、专用 Token、请求/响应/超时边界、容器 hard-limit 和 `scripts/verify_spec_sandbox.py` 已落地，`quality.yml` 新增构建/探针 job | `.env.example` 隔离 project 的 401/413/422、真实 FULL/L2、PID/CPU/内存/tmpfs、临时目录/schema 清理和外网阻断均通过；真实 `.env` 冷启动、主动资源故障注入和远端 Sandbox job 实际运行仍是停止条件 |
| 5 / SIG-P1-03 / 04 | **已完成代码与正式 fixture Trace**（`1b5f2ff`、`47fa3f7`、`b88598b`）：FAILED/ERROR 分流、候选/fact 引用和正式 run 4 的 bounded Replan 已记录；默认预算不足的 run 3 也保留为负例 | 规格失败只能进入一次有界 Replan；环境/权限/清理/超时故障终止；两者均阻断交付，不能靠调高预算或吞错通过 |
| 6 / SIG-P1-04 / P2-03 | **已完成正式 Replan/终止 Trace 与主要交付负例**：run 4 经 API + Worker 保存 candidate/fact hash、issue、Replan 和最终六节点状态，run 3/5 分别记录预算拒绝与 `REPLAN_LIMIT`；run 2 已有 Evaluator/DeliveryGate/导出成功；另有 Artifact 变更、缺失/过期/低层级/错 scope/伪造 fact 的正式导出拒绝，且 `5771150` 修复人工编辑评估报告复用旧 fact 的缺口 | 来源/policy digest 单独变异尚未在修复后的正式 API 上执行；不能只调单测伪模型，live Replan 仍未关闭 |
| 7 / SIG-P1-02 / 03 | **已补本地探针与 CI 定义**（`9b3d282`）：扩展 `quality.yml` 的 verification config、verifier 镜像构建和 sandbox probe job | 仍需远端 Sandbox job 实际运行，并补分层变异集/真实产物复扫的检出、误报、耗时逐样本证据；全程 fixture 无外部模型费用 |
| 8 / SIG-P2-01 / 02 | **已完成离线/fixture 部分**：新冻 A/B/C/D manifest、数据与预算通过 validate-only；development/holdout fixture 完整采集并保留 `NOT_EVALUATED` | 新配置未借旧 r12 hash；完整 live 批次需要额外有效付费授权，安全执行层拒绝 `576 CNY` 批量启动，P2-02 继续 blocked，不填收益数字、不晋级 |

每步完成只更新对应验收事实。详细执行步骤可补入两份手册，脱敏报告归档；根 package 文件处置与可选 MCP 只在用途/范围确认后继续，不阻塞上述本地修复。

## 10. Definition of Done

| 验收项 | 当前状态 / 关闭条件 |
| --- | --- |
| 正式六节点 live + 门禁 + 导出 | 已有 run 38 的指定配置样本；保留为 smoke，不代表 L2 或自我修复 |
| 真实生产显式事实 + 独立前端消费 | 部分完成：`cb4355b` / `1023024` 覆盖两个生产调用点、显式事实和独立消费；真实 tsc/L2 与正式 FULL/L2 已有 run 2/run 4 记录，增量负例和远端 CI 仍待补 |
| L2 可重复、并发隔离与安全硬限 | 部分完成：真实重复/并发/失败清理、网络与资源探针均有记录；主动资源故障注入、真实 `.env` 冷启动和远端 L2 CI 仍待验收 |
| 正式 FULL/L2 门禁 | 部分完成：新候选正式 run 2/run 4 已绑定最终来源并通过 Reviewer/Evaluator/DeliveryGate；Artifact 变更、缺失/过期/低层级/错 scope/伪造 fact 的正式导出负例已补，来源/policy digest 单变体和 live 仍待补 |
| 正式 verifier 失败→Replan→修复，以及终止 Trace | 部分完成：fixture run 4 已满足失败→一次 Replan→修复，run 3/5 已记录预算不足与 `REPLAN_LIMIT`；live 自我修复仍未关闭 |
| 验证器增量、检出/误报/耗时 | 未完成：分层样本、独立真值、历史产物复扫；明确样本量/依赖/局限 |
| 完整 live A/B/C/D 与置信区间 | blocked：先修复、重冻并满足启动条件；负面结论可接受，缺样本不能关闭 |
| CI | 普通 master CI 已满足；Sandbox 扩展 CI 已加入 verifier 构建/探针 job，但远端实际运行结果尚未取得 |
| 展示与问答 | 已有 fixture Demo/面板/15 卡片；启动说明、真实 L2/修复下钻与 DAG 图待补；截图归档豁免继续有效 |
| 范围与历史保护 | 不新增 Agent、旧同步入口、固定 DAG 或任意执行；不改冻结版本/历史证据，不删业务卷，数字可追溯 |

只有必选修复和证据均满足，总计划才能改为 `done`。MCP / 变更影响等明确 deferred 项不强制实施；它们的缺失必须在介绍中如实说明。

## 资料来源

面经主题清单来自公开整理，非原帖逐字内容：

- [牛客：近期开发 AI 面经](https://www.nowcoder.com/feed/main/detail/e3425d60dec24ee1bcf1dc44420b0101)
- [卡码笔记：Agent / 大模型大厂面试题汇总](https://notes.kamacoder.com/interview/llm/agent_interview.html)
- [知乎：小红书二面——Agent 框架选型与评价指标](https://zhuanlan.zhihu.com/p/2017373001881002562)
- [阿里云开发者社区：65 题 AI Agent 面试宝典](https://developer.aliyun.com/article/1739618)
- [GitHub：ai-agent-interview-guide](https://github.com/bcefghj/ai-agent-interview-guide)
