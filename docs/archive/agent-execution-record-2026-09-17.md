# 面经驱动优化 P0 执行记录

> 历史归档（2026-09-29）：保留当时的设计、状态和执行证据，不作为当前能力或待办清单。当前说明见 [文档索引](documentation-index.md)，后续工作见 [Spec Sandbox 计划](../autospec-v5-spec-sandbox-plan.md)。

更新日期：2026-09-17。对应计划：`interview-optimization-plan-2026-09-14.md`。
本记录描述工作区实现与可复现验证，不代表正式发布或 live 质量收益。

## 已实现的变化

### 有界 Backend Loop

新增 `BackendEngineerAgent:v2`、独立 `backend_engineer_loop:v1` Prompt 和四份实验 WorkflowSpec。
六角色 DAG 保留，正式 `autospec-v5:v5` 不变。V97 插入四个 v2 实验 DRAFT，V98 新增四个 v3 实验 DRAFT；V1–V97 未因后续修复而改写。
采集器默认 fixture 使用 v3，本轮 live 配置使用计价后的 v4。两者保留 v2 Backend Loop，并将 Evaluator 升级为 `EvaluatorAgent:v2` / `EvaluationRuntimeInput`，显式承接控制面的结构化检索策略、项目范围、缓存、trace 和 snapshot 元数据。旧 Evaluator 输入指纹不变，未知业务字段仍拒绝。

| 组 | 版本后缀 | Loop | Tools | Replan 上限 | Backend 模型调用/轮数预算 |
|---|---|---|---|---|---|
| A | v5-agent-execution-v3-a | 关 | 关 | 0 | 7 / 7 |
| B | v5-agent-execution-v3-b | 开 | 关 | 2 | 7 / 7 |
| C | v5-agent-execution-v3-c | 开 | 开 | 0 | 7 / 7 |
| D | v5-agent-execution-v3-d | 开 | 开 | 2 | 7 / 7 |

相同预算是控制变量，不代表 A 一定消耗七次调用。每个候选使用相同 Prompt 基础规则；只有启用 Loop 时返回 AgentTurn。
Java 发布校验、Python WorkflowSpec 和 Worker 入站共同检查：v2 最小调用/轮数预算为 `2 + tools_enabled + 2 * max_replans`，D 至少为 7。

模型输入现在包含完整 AgentTurn JSON Schema、候选 Artifact Schema、允许的动作类型、工具参数 Schema、累计工具观察、失败候选和确定性问题。
阶段不匹配和修改权威问题码会停止；无效工具参数可以在剩余预算内修正，权限拒绝仍停止。
Step 的 model_call_ref 指向台账中的真实调用 ID。Gateway 参数按工具建模，禁止未声明字段。

### 可信评测门禁

晋级需要完整的 A/D live 控制面证据、同一 holdout 数据/环境/模型/价格/预算版本、至少 8 个不同案例且每例至少 3 次。
逐例必须包含 Run、Trace、Bundle 和独立 rubric 证据；聚合指标从逐例事实重新计算并核对。
fixture、空案例、重复运行、缺失价格、非有限值、不足样本和 smoke/development 结果均不能晋级。

“零问题→零问题”不再被算作质量改进；零基线成本/Token/时延需要事先声明绝对限额。
候选不得降低门禁通过率或 MUST 覆盖率。被拒绝的越权请求与成功越权执行分别计数。
缺失成本不补零，失败模型调用的 Token 和有价格依据的成本继续计入。

### 正式 API 采集和外部评审

入口为 `python -m evaluation.run_control_plane`。只接受已 PUBLISHED、哈希正确且与本地实验契约完全相同的版本。
采集器不发布版本、不放开 DRAFT 运行、不代替人工审批。每组/案例/重复使用新项目，只发送用户需求，不发送 gold rubric。
调用正式 `POST /api/workflow-runs`，从 Run/Trace/Nodes/Artifact API 收集事实；仅取消自身超时或等待审批的实验运行。

同时限制实验总运行数、单次 Token/模型调用/费用/时长和实验总费用预留。每次启动先预留完整单次上限，不退款；预算不够的案例明确 NOT_EXECUTED。
live 配置还检查价格快照与 Token 上限的保守费用边界。配置中的价格应覆盖所有实际路由、fallback 模型；缺少实际调用模型的价格时成本仍不可用。
这依赖实际 provider 计费规则与价格录入正确，采集器不承诺第三方账单的绝对精度。

逐例 journal 在创建项目、创建 Run 和完成采集时记录状态。同一输出目录已有 journal 时拒绝重跑，避免中断后重复消耗；目前不支持自动续跑。
发生网络异常时按 journal 的 project/run ID 检查服务器状态，再决定取消或另建实验。不得删除 journal 后盲目重试。

采集结果中的 Evaluator 覆盖率只是自评诊断，默认没有 rubric_ref，不能单独触发晋级。
外部评审者逐一判定冻结案例的所有 MUST，可使用不同的 API 命名和实现，只要语义成立。
`evaluation.rubric_review` 验证案例/重复/Run/Bundle/数据集哈希、Artifact 内容哈希和 JSON pointer，保留原始采集结果并重算指标。
PASS 必须引用实际 Artifact，缺失要求可记 FAIL；人工评审不能把产品已阻断的结果改成通过。
哈希证明评审对象，不证明评审判断正确；仍需盲评和分歧复核。测试中的人工记录为合成测试数据。

## 数据集与执行方式

`evaluation/experiment_dataset.py` 固定 24 个案例：16 个 development、8 个 holdout，覆盖 CRUD、审批、权限、多实体、外部集成、歧义、冲突和纠错。
原有 8 例作为 smoke，并进入 development；holdout 不复用原案例。新增 rubric 是待人工复核的草案，不能称为已标注 gold。
REWORK 类检验输入中的纠错理解；真实运行中的人工返工/恢复另由故障演练验收，不据此宣称已覆盖。

从 `agent-engine` 目录使用仓库指定 Python 执行：

```powershell
python -m evaluation.run_control_plane --config ../docs/archive/examples/agent-eval-config.json --output target/evaluations
```

示例配置是 fixture smoke 模板：先将 version_ids 替换成隔离环境中按正式验证/发布流程准备的实验版本 ID，填写代码和环境版本、唯一实验 ID。
认证通过环境变量 `AUTOSPEC_EVAL_SESSION_TOKEN` 注入；后端地址为 `AUTOSPEC_EVAL_BASE_URL`，默认本机 18080 端口。凭据不写入配置和证据包。
32 次预算对应 8 例 × 4 组 × 1 次，只验证采集链路。holdout 的 8 × 4 × 3 至少需要 96 次；先按真实价格和可接受成本决定是否执行，不能只改标签。

live 价格快照结构如下，数值必须从执行时的真实价格填写，不能将占位值当测量结果：

```json
{
  "source": "provider pricing page URL",
  "observed_at": "execution-date",
  "currency": "provider billing currency",
  "version": "frozen-price-snapshot-id",
  "models": {
    "providerKey:modelName": {
      "input_per_million": "replace with numeric rate",
      "cached_input_per_million": "replace with numeric rate",
      "output_per_million": "replace with numeric rate"
    }
  }
}
```

独立评审文件是 `RubricReview` 对象数组（schema 见 `evaluation/rubric_review.py`），每个对象包含 dataset_hash、case_id、repetition、workflow_run_id、bundle_hash、reviewer、reviewed_at、blocking_issue_count 和 requirements。
每条 requirement 包含 requirement_id、PASS/FAIL verdict、rationale 与 evidence；evidence 为 artifact_id、content_hash 和 json_pointer。
内容哈希使用 `control_plane.digest(json.loads(artifact.content))`；不要对 JSON 字符串原始空白计算另一种哈希。

```powershell
python -m evaluation.rubric_review --matrix target/evaluations/EXPERIMENT/matrix.json --reviews REVIEW.json --journals target/evaluations/EXPERIMENT --output target/evaluations/REVIEWED
```

## 自动验证

| 验证 | 最近结果 |
|---|---|
| 后端 `mvn test` | 2026-09-17：203 tests，0 failures/errors；H2 成功迁移至 V101 |
| Agent `python -m pytest -q` | 2026-09-17：147 passed，包含冻结 PRD Prompt、嵌入 Schema、错误分类和失败调用计费回归 |
| 前端 `npm test` | 2026-09-17：9 文件、24 tests 通过 |
| 前端 `npm run build` | 2026-09-17：TypeScript 与 Vite 构建通过 |
| `scripts/verify_workflow_contract.py` | 2026-09-17：正式版、旧候选、v2/v3/v4 四组和 v5/v6 单组诊断共 16 份契约，与迁移、Handler、Prompt 同步通过 |
| 默认 / monitoring Compose config | 均通过 |

Python 3.13 在 Windows 沙箱创建的 0700 pytest 临时目录曾导致扫描拒绝访问；pytest 明确以 tests 为测试根目录，新测试输出使用已忽略的 target 目录。
前端 esbuild 子进程在沙箱内收到 EPERM，获准在沙箱外执行后测试和构建通过。

## 隔离 fixture 实验及失败证据

9 月 15 日在 `autospec-p0-eval-20260915-r2` 独立 Compose 项目中实际运行 MySQL、Redis、后端、Agent API 和两个 Worker。
端口为后端 18080、Agent API 18000、MySQL 13306、Redis 16379；正式开发数据卷没有作为实验数据源。
Docker Hub 下载停滞后，Agent 镜像使用本机已有依赖层，清空镜像内旧 `/app` 再复制当前源码。Python 3.12.14，requirements.txt SHA-256 为 `fc9fb2e06c4a765fbb6c40709541556924c350b79d14ea17ccca29fad77764b1`，与工作区一致。
后端容器构建遇到 Maven Central TLS 错误，改用本机 Maven 构建的当前 JAR 和已存在的 JDK 运行层；未宣称本轮完成干净网络环境下的可重复镜像构建。

v2 A/B/C/D 在该隔离数据库经正式 validate/publish 接口发布，未激活到正式环境。
产品经理审批由独立测试驱动通过正式 API 模拟所有者决定，限定本次创建的运行且要求已观测模型调用均为 local；它不属于人工质量盲评，采集器没有自动审批能力。

截至中断，收集到 9 条终态记录：7 条 Evaluator `VALIDATION_ERROR`、2 条 `RUN_CANCELLED`；另有 Run 10 的 journal 停在 RUNNING，当前无法确认其最终状态。未完成完整 A/B/C/D 矩阵。
脱敏摘要见 [中断证据](examples/agent-eval-fixture-interrupted-2026-09-16.json)，原始 journal 留在本机 `agent-engine/target/fixture-p0-20260915-r2/`。
组级 `SUCCEEDED` 只表示已收集该组所有案例，并不表示案例通过。所有结果为 fixture，发布决定仍为 NOT_EVALUATED。

| 失败案例 | 原因与修复 | 复测与代价 |
|---|---|---|
| 新 Prompt 发布返回 409 | Java 资源保留 CRLF，而 Python 读取归一为 LF；Seeder 统一换行后计算同一内容 | 新增跨资源校验和回归，隔离库后续发布成功；保留首次失败数据卷，没有改写既有 Prompt |
| Run 2 等七例 Evaluator 入站失败 | 前五节点成功，但旧 EvaluationInput 拒绝控制面检索对象和额外元数据 | 新增 Evaluator v2、v3 实验契约及 V98；本地六处理器回归证实已进入真正质量门禁，仍会因缺失验收/权限/运行证据返回 QUALITY_GATE_BLOCKED；新版本尚未完成 Compose 复测 |
| 多个 API 缺角色时 Loop 台账可能拒绝重复问题码 | 多个问题共用 AUTH_ROLE_MISSING，Step 要求代码唯一 | 仅在 Step 中去重代码，完整问题列表与失败候选继续传给返工；最小回归覆盖多个缺失角色后修复，属于合成测试而非 live 失败样本 |

9 月 16 日 Docker Desktop 因本机 `dockerInference` 残留 Unix 套接字无法访问而退出。精确路径的只读检查、备份移动和单文件删除均失败；没有递归清理、重置 Docker 或删除数据卷。
上述中断时，V98 仅通过 H2 迁移与契约验证，尚未在本机 MySQL/Redis 环境部署复测；当天 Docker 重置后的复测进展见下文。正式 v5 和历史 v2 的 Evaluator v1 契约保持不变；不要将 v3 的修复描述成它们已经自动升级。

## 本轮人民币 10 元预算（2026-09-16）

用户已批准本轮 live 总费用上限人民币 10 元。配置见 [live 预算配置](examples/agent-eval-live-cny10.json)。本轮授权 ID 为 `interview-live-20260916-cny10`，跨实验 ID、输出目录和采集器重启共用；没有新的用户授权不得换授权 ID 或删除预算数据库。

先选 `crud_inventory` 一个 smoke 案例，对 A/B/C/D 各采一次：单次上限 2 元、250,000 Token、32 次模型调用和 600 秒，共预留最多 8 元，剩余 2 元用于必要复测。此规模不能满足 holdout 重复实验和候选晋级条件，原晋级门禁保持不变。

新增 v4 A/B/C/D 和 V99 DRAFT 种子，完整保留六节点、审批、返工和 Loop 消融变量。付费节点统一固定 `deepseek:deepseek-v4-flash`，输入未缓存/缓存/输出每百万 Token 按人民币 2 / 0.04 / 8 元计入冻结 policy；价格依据 [DeepSeek 官方人民币价格页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) 2026-09-16 观测的高峰价格。不开启 fallback，节点自动尝试上限为 1，Loop 内的 7 次调用预算保留。这些变化只作用于新实验版本，历史迁移和正式 v5 保持不变。

价格预检同时核对固定模型、币种和冻结单价，拒绝零计价的旧实验契约用于 live；总 Token 的保守费用界限为 `250000 × 8 / 1000000 = 2` 元。后端按冻结 policy 预留节点费用，Backend 节点完整 7 次调用预留 0.476 元。

采集器在创建项目和运行之前，通过 SQLite 事务预留整个单次费用上限。数据库为本机忽略目录中的 `agent-engine/target/evaluation-budgets.sqlite3`；失败、断连和取消均不自动退还预留，结果未知时禁止复用同一运行键。并发事务确保多个采集器不能分别获得一份新额度，已有授权的币种和上限不允许原地更改。该账本记录预算预留，不代替供应商账单中的实际费用；进程外的独立调用不受此实验采集器控制。

本轮授权初次写入本机预算库时，预留 0 元、付费请求 0 次；后续实际用量见下方 live 记录。仓库内配置模板的 `version_ids` 保持留空，禁止跨环境猜测数据库 ID；本机具体 ID、源码指纹和镜像快照写入忽略目录内的运行配置。正式启动前重新核对价格，若上涨须新增契约版本，不能覆盖不可变历史。

## Docker 重置后的重试（2026-09-16）

用户重置 Docker 后，引擎恢复为 29.4.0，但容器、镜像及数据卷均已清空。新隔离项目为 `autospec-p0-retry-20260916`，沿用实验端口 18080/18000/13306/16379。原始工作区 journal 保留，旧数据库中未确认的 Run 10 已无法再核实；新环境的相同数字 ID 不代表同一次运行。

重新从官方基础镜像安装 Agent 依赖并复制当前源码；后端用本机 Maven 打包的当前 JAR 构建 JDK 运行镜像。本阶段 MySQL 完成至 V99 的迁移，六个服务（含两个 Worker）均健康。四组 fixture 最后验证镜像为 Agent `autospec-p0-agent:20260916-r2`、后端 `autospec-p0-backend:20260916-r2`；之后的诊断镜像另行记录。

重试发现并修复了两处之前单元路径没有覆盖的问题：

| 现场证据 | 修复 | 复测 |
|---|---|---|
| fixture r1 的 B/Run 2 在终态事件构造时报 `normalized_params_hash` 缺失 | fixture Loop 对实际输入和 AgentTurn 计算哈希，沿用冻结 Prompt key，并通过统一台账分配调用 ID | 补充冻结调用记录验证；先通过正式 API 取消 Run 2，再在新实验 ID 重跑 |
| fixture r2 的 C/Run 5、D/Run 6 返回 `POLICY_HASH_MISMATCH` | 网关原先从不含 `tool_policy` 的业务输入取策略；改从已校验哈希且与节点绑定的冻结执行包取策略，双方继续使用同一默认值规范化 | Java 回归证明业务输入不能覆盖策略、缺失/被改动的执行包会被拒绝；Python 固定规范化哈希交叉验证 |

最终 `fixture-retry-20260916-r3` 对 `crud_inventory` 一个案例完成 A/B/C/D 四组，Run 7–10 的前五节点全部成功，Evaluator 均返回 `QUALITY_GATE_BLOCKED`；没有 Schema 错误。C/D 均有一次成功的 `contract.lookup`，A/B 没有工具调用。质量拦截包括缺失 MUST 验收覆盖、权限覆盖和引用证据，没有放宽门禁。脱敏逐例证据见 [重试结果](examples/agent-eval-fixture-retry-2026-09-16.json)，原始 trace、产物和模拟 fixture 审批记录保存在本机 target 目录。

v4 A/B/C/D 经正式 validate/publish 接口在该隔离库发布，ID 分别为 11/12/13/14；没有激活正式默认工作流。先完成配置准备和采集器预检，本机配置为 `agent-engine/target/live-flash-smoke-20260916-r1/config.json`，四组每次预留 2 元，总预算共用此前 10 元授权。官方价格复核与人民币账户可用性只读检查完成；余额查询不属于模型生成调用。

live 切换曾被自动审批拒绝，要求明确确认将虚构仓库案例、Agent 角色提示词和该案例生成的 Artifact 发送到 `api.deepseek.com`。用户随后回复“继续”，授权问题已解决，切换和下述调用均已完成；不再重复询问同一发送授权或 10 元费用授权。

## Live smoke 与修复（2026-09-16～17）

实际经正式入口运行 5 次，均使用独立项目和合成的 `crud_inventory` 需求，未注入参考答案。脱敏证据见 [live 结果](examples/agent-eval-live-smoke-2026-09-17.json)，包括 Run/Trace/Bundle、源码和镜像指纹、Token、原始错误分类与诊断。

| 实验 | 工作流 Run | 结果 | Token | 按峰值价格估算的费用上界（元） |
|---|---|---|---|---|
| v4 A/B/C/D，各一例 | 11 / 12 / 13 / 14 | 全部在 Product Manager 返回空内容后失败；每次输出计数均达到 4,000 | 20,407 | 0.13530872 |
| v5 D，显式关闭思考模式 | 15 | 返回 JSON，但根部出现不被允许的 `acceptance_criteria`，PRD 校验失败 | 2,485 | 0.013526 |
| 合计 | 5 次，全部 FAILED | 没有持久化业务 Artifact，也没有进入 live Backend Loop | 22,892 | 0.14883472 |

价格快照为 DeepSeek Flash 输入 2、缓存输入 0.04、输出 8 元/百万 Token，9 月 17 日复核未变。这里按已冻结的峰值价格结合调用台账计算，**不是供应商实际扣费或余额差额**。[官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

同一授权账本目前有 5 笔 × 2 元预留，共 10 元。失败仍不释放预留，因此本轮不再创建付费运行；不删除账本、不换授权 ID、不把约 0.149 元估算成本误写成“已花 10 元”。当前未核验供应商账单。

### 失败原因与版本化修复

1. **输出上限与模型模式。** v4 的空响应、4,000 输出 Token 与默认启用思考模式时预算被耗尽的表现一致；这是结合官方行为的诊断，未存储或推测模型的隐藏思考内容。新增可选且须固定 DeepSeek provider 的 `thinking_mode`，写入请求和参数哈希；未指定的历史契约哈希保持原样。V100 / v5-D 显式关闭思考，且 `finish_reason=length` 产生 `MODEL_OUTPUT_LIMIT`，保留失败 Token 和成本。Run 15 已观察到非空 JSON，但仍未通过产物校验。[官方思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)
2. **PRD Prompt 与 Schema 层级不一致。** 旧 Prompt 的列表把验收标准写得像根字段；实际结构为 `user_stories[].acceptance_criteria[]`。新增 `ProductManagerAgent:v2` / `product_manager_schema:v1`，明确层级并嵌入完整 Pydantic JSON Schema。新 Prompt 估算 2,800 Token，v6-D 将 Prompt 预留增加至 3,072；总输入上限仍为 12,000。新增 V101 DRAFT，保留 V1～V100、旧 Prompt 与正式活动版本；不放宽 Schema、不偷偷移动错误字段。此修复尚未 live 复测。
3. **运行错误被吞成通用分类。** `run_agent_node` 原先将所有异常包装成普通 RuntimeError。现在保留 `VALIDATION_ERROR` 及 provider 的 `MODEL_OUTPUT_LIMIT` 等错误码，同时保留节点上下文。采集器补计 `OUTPUT_SCHEMA_ERROR`。Run 15 的历史 `schema_invalid_count=0` 原样保存，证据另列人工核验的 Schema 违规；不能将旧统计冒充已修正的观测。

新增回归使用与 v6-D 一致的冻结契约和 mock provider，验证合法嵌套输出成功、根字段错误被拒绝、输出截断分类正确，以及每种失败仍保留 Token 和成本。这些回归证明协议和记账路径，不证明真实模型的成功率。

### 修复后的部署验证

后端和 Agent 均已构建为 `20260917-r4` 镜像并部署到同一隔离环境，MySQL 实际完成 V101 迁移。中断后 Docker 引擎曾停止，重新启动后六个服务恢复健康；原数据卷和实验记录保留。隔离 Compose 覆盖文件将 Agent API、两个 Worker 明确设为 fixture，三个进程的实际模式已核验。

v6-D 经正式 validate/publish 接口发布为隔离库版本 16，未改动正式默认工作流。`fixture-prd-schema-20260917-r1` / Run 16 完整经过六节点：Product Manager v2、Architect、Backend v2、Frontend 和 Reviewer 成功，Evaluator 返回 `QUALITY_GATE_BLOCKED`。一次 `contract.lookup` 成功，7 条模型记录均为 local，PRD 验收标准位于故事内部，Schema 错误为 0。测试驱动模拟所有者审批，不属于独立人工 rubric。脱敏 [fixture 证据](examples/agent-eval-prd-schema-fixture-2026-09-17.json) 记录了镜像、Trace、Bundle 和各节点状态；免费验证后 live 预算仍为 5 笔、合计 10 元预留。

附 [Run 16 的脱敏 PRD Artifact](examples/agent-eval-prd-fixture-artifact-2026-09-17.json)。该文件明确标记为 fixture，保留其领域偏差和未完成的 MUST 覆盖，不能作为 live 产物质量示例。

发布决定继续为 `NOT_EVALUATED`。v5-D 同时改变模型策略与运行时代码，不能作为 v4-D 的同条件成对消融；单案例、无重复、无独立 rubric、无 holdout 也不足以证明质量收益。live 工具选择、返工收益和完整交付仍待证据。

## 仍待完成

- Docker 已恢复；四组单例 fixture 和 5 次 live 失败证据均已保存。8 类完整 smoke、修复后 live PRD 及 Backend Loop 仍待采集。
- 人工复核 24 例及 rubric；已采集实验的价格、源码、镜像和环境快照完整保留，不能将待复核标注当 gold。
- 本轮 10 元预留已满，实际费用上界估算约 0.149 元。后续付费复测必须先处理现有保守预留的结算或取得新增额度，不能更换授权 ID 绕过；当前实现不自动释放失败预留。
- 成对重复采集、独立盲评、置信区间和候选晋级决定仍未执行。若继续做四组对照，需为各组统一新的 PM Prompt 和 thinking 策略，不能拿只修改过的 D 组与历史 A 组宣称收益。
- P1 RAG、Project Memory、容量/恢复和真实导出构建证据，按主计划依赖后续实测推进；当前不宣称完成。

本次没有创建 commit、push 或修改正式环境的活动版本。
