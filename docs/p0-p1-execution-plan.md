---
plan_id: autospec-p0-p1-execution
status: p1-l2-fixture-complete-live-blocked
created_at: 2026-09-29
parent: docs/autospec-v5-spec-sandbox-plan.md
---

# AutoSpec P0 / P1 修复执行手册

本文件是 [Spec Sandbox 总计划](autospec-v5-spec-sandbox-plan.md)的执行分解，不是另一条产品路线。按用户本次要求保留在 `docs/` 根目录，供能力较弱、上下文较短的模型逐任务实施。本文只制定步骤；所有任务初始状态均为 `planned`，不能把写完计划记为实现完成。

截至 2026-09-30，本轮已完成 P0/P1 的离线实现、P0-F 的真实 MySQL 故障演练、P1-E 的真实隔离 L2、以及正式 API 的 fixture 六节点与 Markdown/PDF/ZIP 交付验收。DeepSeek live 已按授权做两次有界 smoke，但 Product Manager 均在冻结输出上限内未闭合 JSON；远端 CI 也未重跑，因此 P0-G/P1-H 仍按下表保留为阻断状态。

执行顺序、默认方案、修改入口、最小测试、验收证据和停止条件均在下文。按编号实施，一次完成一个任务；不要自行加入 MCP、向量数据库、完整业务代码生成或新前端看板。

## 1. 本轮目标与完成边界

### P0 的结果

1. 默认领域规则不再因为 `approval`、`event`、`retry` 就强制用户业务实现 AutoSpec 自身接口。
2. 新候选工作流支持一次有界结构化输出修复，修复调用受已有模型预算和 deadline 约束，失败原因能够保留。
3. 三个业务领域的确定性 fixture 可以完成六节点；已知严重缺陷仍被门禁拦截。
4. 定位并修复 MySQL 故障检测测试超时；清理确实不再被引用的内容，保留历史证据。
5. 获得新 live 预算授权后，用正式 API 跑通至少一个完整用例并验证交付。没有授权时，状态写为“代码完成，live 验证待授权”，不能写 P0 全部完成。

### P1 的结果

1. 新契约能明确表达主外键、字段类型、API 参数位置和前端字段绑定。
2. 确定性编译 OpenAPI、DDL、类型化客户端与绑定桩；L1 能定位结构错误，L2 能真实执行 DDL 与 TypeScript 类型检查。
3. 校验结果通过现有受控工具通道进入 Backend 修复循环、Reviewer 和 Evaluator，并绑定来源、策略与验证器版本。
4. 缺失、过期、伪造或未达到要求层级的验证证据不能被当成 PASS，也不能通过直接调用导出接口绕过门禁。
5. 旧工作流、旧 Schema、历史运行和回放继续可用；新候选保持未激活。正式晋级与大规模消融属于 P2。

“P1-L1 完成”和“P1 全部完成”是两个状态。本轮已完成三个 fixture 的真实隔离 L2，但 P1-H 仍缺 live 成功证据与远端 CI 结果，不能写 P1 全部完成。

## 2. 固定约束：执行时不要重新发明方案

| 事项 | 本手册采用的方案 |
| --- | --- |
| 工作流 | 继续六节点，Backend 与 Frontend 并行，Reviewer 汇合；顺序只来自冻结 WorkflowSpec |
| 历史兼容 | 不修改已发布契约、Prompt 文件、Schema 的原始语义或历史 SQL；新能力走新版本和新迁移 |
| 产品命名 | 产品称 AutoSpec；新的文档和候选文件名不带产品代际后缀，内部发布版本仍必须可追踪 |
| P0 候选 | 建议文件 `agent-engine/contracts/autospec-spec-repair.workflow.json`，工作流 key 仍为已有 `autospec-v5`，发布标识 `spec-repair` |
| P1 候选 | 建议文件 `agent-engine/contracts/autospec-spec-sandbox.workflow.json`，发布标识 `spec-sandbox`；不得修改当前 active 指针 |
| 版本冲突 | 开始前查是否已存在并发布；已发布就新增标识，不覆盖。迁移编号取实施时最大编号加一，不盲写 V104 |
| 输出修复 | P0 先保留 `json_object`，加一次错误回填修复；原生 function calling / provider 能力探测不作为前置任务 |
| 规则 | 新候选使用通用结构规则；旧规则如需历史重现，留在 legacy rule pack，仅旧 Handler 使用 |
| 编译器 | 单一 Python 实现，放在 `agent-engine/spec_verifier/`；L1 本地调用和 sidecar 复用同一实现 |
| 报告落点 | 新 ReviewReport / EvaluationReport 内嵌精简摘要；可信详细结果和请求 hash 存现有工具事实台账。仅确有字段容量/唯一约束缺口时新增迁移 |
| 工具 | 新增 `spec.verify:v1`，显式副作用 `SANDBOXED`，默认关闭；不开放任意 SQL、shell、URL 或文件路径参数 |
| 运行时 | 独立 verifier 容器与独立验证 MySQL；Worker 不直连任意 MySQL |
| 门禁 | 要求 L2 时，L2 超时/不可用即未验证并阻断；禁止自动退成 L1 PASS |
| 不做 | MCP/A2A、真实 embedding 效果实验、watchdog、大规模消融、Trace 新界面、业务 CRUD 实现、`mvn` 生成项目编译，均不属于本轮 |

技术依赖版本必须在实施时核对现有 lock/requirements 和官方文档。不要凭记忆填最新版本，不自动升级整个项目依赖。

## 3. 每个任务的固定执行流程

1. 读本节、当前任务和它的前置任务完成记录；只打开任务列出的入口及必要调用方。
2. 运行 `git status --short` 与相关路径的 `git diff`，识别已有修改。不要 `reset`、`checkout --`、批量格式化或覆盖其他任务代码。
3. 用 `rg` 找实际调用点、Schema 注册和已有测试。Windows 下使用 `rg pattern directory -g '*.py'`，不要把未展开的 `directory/*.py` 当路径。
4. 在第 13 节把当前任务标为 `in_progress`，记录本次允许修改的文件范围。
5. 只为当前真实风险复用或补一个最小失败样例。已有等价测试就复用，不重复写。
6. 实现最小闭环；纯函数、运行时调用、Java 校验和前端展示各有职责，不复制同一规则到多个地方。
7. 运行任务指定的定向测试；通过后停止扩展测试。出现错误只修当前任务造成的问题。
8. 检查 diff、文档引用和证据。通过条件全部满足才标 `done`；环境/预算缺失写 `blocked_validation`，代码未完成写 `in_progress`。
9. 输出交接记录，再进入下一任务。单次会话做不完时，记录精确断点和下一条命令，不写泛泛的“继续优化”。

连续两次修复同一失败仍未解决时：缩小复现、记录实际错误与已尝试方法；不要以放宽 Schema、关闭门禁、增大无限重试或跳过测试作为修复。能独立进行的后续纯离线任务可以继续，依赖失败项的集成必须等待。

文档、证据、小改动不需要重新请求实施许可。需要新外部模型预算、删除来源不明的用户文件、提交/push/激活发布等独立授权时，先完成可独立的准备工作；明确提出具体动作及原因，不反复问已获授权的事项。

## 4. 任务顺序与最短路径

| 顺序 | 任务 | 交付物 | 前置 |
| --- | --- | --- | --- |
| 01 | P0-A 基线与台账 | 文件/版本/已有测试清单 | 无 |
| 02 | P0-B 三领域 fixture | 一套可复用的三个领域数据 | P0-A |
| 03 | P0-C 通用规则 | 无平台接口误杀，严重缺陷仍阻断 | P0-B |
| 04 | P0-D 结构化修复 | 一次修复、预算计量、稳定错误码 | P0-A |
| 05 | P0-E 修复候选 | 新 Handler/Prompt/契约/种子同步 | P0-C、D |
| 06 | P0-F CI 与清理 | MySQL 超时原因和最小修复、依赖清理记录 | P0-A |
| 07 | P0-G 正式链路验收 | fixture E2E；获授权后的 live smoke | P0-E、F |
| 08 | P1-A 新契约 | 完整字段、版本兼容与固定样例 | P0-E；live 等待期间可做 |
| 09 | P1-B 编译器 | OpenAPI / DDL / TS / 绑定清单 | P1-A |
| 10 | P1-C L1 校验 | 报告与最小缺陷集 | P1-B |
| 11 | P1-D 受控工具 | SANDBOXED 策略、可信台账、幂等 | P1-C |
| 12 | P1-E 隔离执行 | verifier + 验证 MySQL + tsc | P1-D |
| 13 | P1-F 循环与汇合 | Backend 局部验证、Reviewer 全量验证 | P1-C、D；L1 可先接 |
| 14 | P1-G 交付门禁 | Evaluator 与直接交付共同检查可信证据 | P1-E、F |
| 15 | P1-H 候选与收尾 | 兼容验证、fixture 整链路、证据包 | P1-G |

为便于低性能模型执行，默认串行推进。P0-F 可以独立处理；P0 live 被预算阻挡时可以继续 P1 的离线部分，不得用 P1 离线通过替代 P0 live 验收。

与总计划编号的对应关系：

| 总计划 | 本手册任务 | 状态更新原则 |
| --- | --- | --- |
| SIG-P0-01 | P0-D、E、G | helper 与候选完成但 live 未验证，整体仍未完成 |
| SIG-P0-02 | P0-B、C | 三领域正常例与严重缺陷反例都通过才完成 |
| SIG-P0-03 | P0-F | 文档归档已做；CI、依赖清理按实际结果记录，不整体勾选 |
| SIG-P1-00 | P1-A | 新契约与旧快照兼容都验证后完成 |
| SIG-P1-01 | P1-B | 编译确定性与安全输入边界通过后完成 |
| SIG-P1-02 | P1-C | 最小缺陷集和正常例通过后完成 |
| SIG-P1-03 | P1-D、E | 工具 mock 成功不能代替真实隔离验证 |
| SIG-P1-04 | P1-F、G、H | 反馈循环、可信门禁和整链路均通过后完成 |

最短可交付线：P0 离线完成 → P1-A/B/C → P1-D/F 的 L1 集成 → 明确标记 `P1-L1`。完整 P1 仍需 E/G/H，不把容器部署写成可有可无的已完成项。

## 5. P0 详细步骤

### P0-A：记录基线，不先重构

**读取入口**

- `AGENTS.md`、本手册和总计划。
- `agent-engine/contracts/autospec-v5-parallel.workflow.json`、`scripts/verify_workflow_contract.py`。
- `docs/archive/examples/agent-eval-live-smoke-2026-09-17.json`。
- `.github/workflows/quality.yml`、`backend/pom.xml`。

**操作**

1. 记录 HEAD、分支、脏文件；本项目曾出现仅换行造成的 status 噪声，必要时用 `git hash-object <file>` 与 `git rev-parse HEAD:<file>` 对比，不据 status 直接判断他人修改。
2. 运行一次契约同步脚本。列出当前 Handler/Prompt/Schema 注册及最新迁移编号，后续新版本以这份清单为基准。
3. 仅报告 `.env` 中所需配置是缺失、占位符还是已配置；不输出值，不复制 `.env` 到证据。
4. 记录 Docker 是否可用、现有 Compose 项目是否在用。测试优先用 test profile / 独立测试项目；不重启用户正在使用的服务。
5. 本手册末尾记录已知限制：没有 live 成功证据；当前 active 不变；本轮没有自动获得新付费额度。

**验收**：基线可追踪，契约校验结果明确。此步不写新测试，不跑三端全量。

### P0-B：建立三领域数据，停止“所有需求都返回二手交易”

**修改入口**：`agent-engine/agents/product_manager.py`、`architect.py`、`backend_engineer.py`、`frontend_engineer.py`；`runtime/agent_node_runner.py` 与现有 fixture 分支；新增 `agent-engine/fixtures/software_domains.py`。

**固定领域**：校园交易、库存管理、员工请假审批。数据必须包含 PRD、故事/验收、Shared Contract、后端、前端及互相一致的稳定 ID；审批域包含 `approval`，库存域包含业务 `event` / `retry` 描述，但不包含 AutoSpec 管理接口。

**操作**

1. 先抽出既有校园交易 fixture，保留原引用 ID 与旧测试行为；不要修改 live 分支去返回 fixture。
2. 用小型手写数据或确定性 builder 增加库存、请假两个用例，禁止调用 LLM 生成测试真值。
3. fixture 选择逻辑放到一个模块，不在四个 Agent 各自写一套关键词猜测。测试可显式选择 fixture key；正式 fixture 演示只做有文档说明的简单领域路由。
4. 对未支持领域不得声称生成了真实业务规格；返回明确限制或标注演示数据。生产 live 不得回落到 fixture。
5. 共享同一用例数据给后续规则、循环和沙箱测试，不重复维护三套大 JSON。

**最小测试**：一个参数化测试覆盖三个正常领域的 Schema、稳定引用及 Shared Contract 一致性。此时旧规则误报可作为 P0-C 的失败复现；不要为了暂时让测试绿而删掉业务关键词。

**验收**：fixture 领域确实不同；各层字段一致；live 与 fixture 分支隔离。

### P0-C：把领域词规则移出新默认路径

**读取/修改入口**：`agent-engine/review/rules.py`、`review/backend_validator.py`、`review/shared_contract.py`、`review/evaluator.py`、`agents/reviewer.py`、`tests/test_review_rules.py`、`tests/test_evaluator.py`。

**操作**

1. 列出所有依赖文本词的规则：`FEATURE_API_RULES`、`API_DATA_RULES`、图片、管理员关键词，以及 `_run_cross_artifact_checks` 中的平台固定路径。不要只删三段 API 路径便宣称完成。
2. 把业务专用规则放进显式 legacy/marketplace rule pack。旧 Handler 继续可用；新候选 Reviewer/Backend validator/Evaluator 使用相同的通用规则版本。
3. 通用规则只根据已有明确事实判断：引用是否存在、需求/验收是否覆盖、API method/path 是否一致、前端是否引用合法 API、Shared Contract 权限是否一致、引用来源是否有效。
4. 复用已有追踪和 Shared Contract 校验；没有明确数据关系时，不凭 `orderId`、`image` 等词推断必须存在某张表。
5. 不增加“所有 MUST 都必有一张新表”的通用规则；读接口、外部集成等需求可以没有新表。P0 保留现有追踪要求，若缺口涉及适用性建模，记入 P1-A，不用降低门禁掩盖。
6. Reviewer 仍执行确定性检查和模型语义审查；模型不得删除或降级确定性 HIGH/CRITICAL。未知结构缺陷必须保留可追踪 issue code、路径与建议。
7. 将规则配置冻结到新候选的 Handler/validator 版本中，不增加可由请求正文任意关闭门禁的开关。

**最小测试**

- 三领域正常数据复用 P0-B，规则无错误 HIGH。
- 一个参数化测试输入 `approval/event/retry`，断言不会新增要求 AutoSpec 自身接口的问题。
- 两个严重缺陷：删除一个必需 API 绑定；让受保护 API 的权限与 Shared Contract 冲突。断言仍阻断并路由到正确节点。
- 已有 citation/秘密扫描测试继续保留，只运行直接受修改影响的文件，不重写等价测试。

**验收**：误报消失且真实缺陷仍被拦截。若删除旧关键词测试，必须在记录中指出替代的结构缺陷测试。

### P0-D：结构化输出只修复一次，保留调用事实

**读取/修改入口**

- `agent-engine/model_gateway.py`、`agents/base.py`、各 Agent 中 `generate_json` 后的 `model_validate` 调用。
- `runtime/model_telemetry.py`、`runtime/execution_context.py`、`runtime/agent_node_runner.py`、`runtime/production_handlers.py`。
- `schemas/workflow_spec.py`、Java `WorkflowExecutableContractValidator.java`。
- 新增 `agent-engine/runtime/structured_output.py`；测试优先扩展 `tests/test_model_gateway.py`、`tests/test_production_handlers.py`。

**实现约定**

- 通用网关仍负责一次物理请求、JSON 解码、Token/成本与错误记录，不在 SDK 内暗中重试。
- 新 helper 负责目标 Pydantic Schema 校验和最多一次反馈修复。让 Agent 明确传入目标模型，不能在网关按 prompt 名猜 Schema。
- 新候选冻结 `model_policy.structured_output_repair = {"enabled": true, "max_repairs": 1}`；Java/Python 同步白名单与校验。缺字段表示旧行为、零修复，不能隐式改变旧 bundle hash。
- `max_calls` 是当前 execution/attempt 的物理请求上限，修复与 fallback 共用该计数。控制面派发新的节点 attempt 时沿用现有预占/结算语义，之前费用仍累计到运行总预算；不能把新 attempt 当成免费额度。helper 不新建模型客户端，不绕过 `begin_model_invocation`。
- 已启用 Backend Loop 的候选沿用现有 Replan，不再在每个 loop turn 外套同一修复循环；两个修复机制不得相乘。

**操作**

1. 先复用假 provider，分别制造合法 JSON 但 Schema 错误、无效 JSON、`finish_reason=length` 和空响应，确认现有行为。
2. 网关为解析错误/空响应提供明确类型或稳定错误码；不要捕获所有 `RuntimeError` 后一概认定可修复。
3. helper 的首次请求传入目标 Schema；校验失败后提取最多 8 个 `path/code/message`，不把完整输入值或可能的凭据串进错误消息。
4. 一次修复输入包含原任务、目标 Schema、截断后的候选和校验问题；候选内容标明为不可信数据。调用前重新计算上下文预算，保留已有冻结 deadline。
5. 第二次仍失败即终止。Schema 错误用 `STRUCTURED_OUTPUT_INVALID`；截断保留 `MODEL_OUTPUT_LIMIT`；权限、余额、deadline、认证与网络异常不进入内容修复。
6. 输出 token 达上限时不要在运行中偷偷加大冻结上限；该运行结束并记录，调整后需要新候选配置。空响应/坏 JSON 可在预算内修复一次，但不拼接残缺 JSON 假装有效。
7. 校验失败不伪造已成功 Artifact。模型请求成功返回 JSON 与 Artifact 校验成功是不同事实：前者可为成功调用，后者必须产生失败/修复记录；最终节点状态准确。
8. 检查 `run_agent_node` 与 `production_handlers` 的异常包装，确保稳定错误码能到达 Worker 事件；不要退回笼统 `HANDLER_ERROR` 丢掉根因。
9. 新单发 Handler 接入 helper，Reviewer 只修复模型语义报告的结构，不能覆盖确定性规则结果。Evaluator 继续确定性执行，不增加模型调用。

**最小测试**

- 成功首发仅调用一次；Schema 首发失败、修复成功调用两次，并包含错误路径。
- 连续两次失败停止；坏 JSON 与空响应可共用一个参数化错误分类测试。
- 输出截断保留 usage 和 `MODEL_OUTPUT_LIMIT`，不自动扩容重试。
- `max_calls=1` 或 deadline 耗尽不发第二个请求，失败调用的费用仍进入现有台账。

不再单独测试每个 getter、Pydantic 自身类型检查、所有错误排列或每个 Agent 的相同 helper 分支。一个公共 helper 测试加一个真实 Handler 接入测试足够。

**验收**：最多一次修复；物理请求数与台账一致；坏输出不会越过预算或被标成功。

### P0-E：形成可运行的新候选，不改历史

**修改入口**：`runtime/production_handlers.py`、`runtime/handler_registry.py`、`runtime/agent_node_runner.py`、Java `WorkflowHandlerCatalog.java`、两端 `prompts/`、`contracts/`、Flyway、`scripts/verify_workflow_contract.py`。

**操作**

1. 从当前并行拓扑复制新候选，保留六节点、审批、返工、预算和 Worker 协议；不能从旧串行实验图丢掉 Shared Contract。
2. 为变化的单发 Agent 新增 Handler 注册和 Prompt 版本；原 Prompt 文件和旧 Handler 注册保持原内容。PRD Schema 提示词已有候选实现，先复用其结构，不从空白发明。
3. 新模型策略显式冻结 provider、model、thinking、每次输出上限和物理调用上限。建议初始 PRD/Reviewer 4096、架构/后端/前端 8192 输出 token，单发最多两次物理调用；这些是待验证起始值，不是效果保证。
4. DeepSeek 的 thinking 显式关闭或按已验证策略设置；不能在只有 `route_key` 时绕过现有 provider 校验。上下文与价格参数必须来自确认的配置，真实成本不得填零冒充免费。
5. 新迁移只插入新候选，不更新旧 published spec、不修改 active。Prompt 两端资源逐字一致，checksum 和 input/output schema hash 由现有注册机制生成，不手填假 hash。
6. 扩展契约校验脚本，让新文件参与检查。现脚本只枚举固定旧文件和旧命名 glob，新文件不在其范围时，即使脚本绿也未验证新候选。
7. 运行 Python/Java 注册契约测试，再运行脚本。抽查旧 `v5` 与 `v5-parallel` 的 canonical 内容与原 hash 未改变。

**最小测试**：复用 `test_production_handlers.py`、`test_execution_bundle.py`、`WorkflowExecutableContractTest`；只新增一个新候选注册一致性样例和一个旧快照兼容样例，优先参数化现有测试。

**验收**：候选能按显式版本启动；旧版本仍可解析；active 不变。候选跑通不能写成“当前默认配置已验证”。

### P0-F：修 CI 和真正的残留，不扩大清理范围

**入口**：`backend/src/test/java/com/autospec/integration/MySqlFailureRecoveryIT.java`、`backend/src/test/resources/`、`.github/workflows/quality.yml`、根 `package.json` / `package-lock.json`。

**操作**

1. 先独立复现 `MySqlFailureRecoveryIT`。记录故障检测和恢复两个时间，分清数据库 socket/connect 超时、连接池等待和业务重试，不把 5257 ms 的检测失败写成恢复失败。
2. 检查 JDBC 参数、Hikari 超时、连接校验及代理切断方式；优先修导致总等待超出约定的配置或测量边界。
3. 不直接把 5 秒改成 30 秒、不加 sleep、不移除断言、不自动 retry 测试至绿。确需改变 RTO，先写清实际行为与产品目标，再单独作决策。
4. 修后同一 IT 连续通过 3 次即可停止本任务重复测试；保留“无部分写入、可读、恢复后可写”的原有断言。
5. 根 package 文件目前只见 pnpm 依赖，但先检查脚本、CI、锁文件与用户用途。来源不明的未跟踪文件先标记，不能为清爽而删除。
6. 对旧 memory/agent_state/interview 模块逐项查 Python import、反射字符串、测试、数据集和迁移引用；只有已证实无消费者且有替代路径的才删除。历史 SQL 及证据保留原命名。
7. 文档已经完成归档，不重做目录整理。只更新本手册状态和当前计划中被本任务实际修复的项。

**最小测试**：原有 MySQL IT；被删除模块的真实调用方测试。不要新增“文件不存在”或遍历所有目录的单元测试。

**验收**：本地指定 IT 通过并有原因记录。未 push 时远端 CI 仍是历史状态，不宣称 master 已绿；提交和远端运行等待用户授权。

### P0-G：正式六节点验收与 live 最小证据

**入口**：`agent-engine/evaluation/control_plane.py`、`evaluation/run_control_plane.py`、`evaluation/budget_ledger.py`、归档评测配置、后端 OpenAPI。

**操作**

1. 先用 fixture 通过 `POST /api/workflow-runs` 启动新候选。复用现有采集器，指定候选版本 ID，不以直接调用六个 Python 函数代替整链路。
2. 现有采集器按 A/B/C/D 收集：P0 不需为此付费跑四组。先确认是否已有单组选择；没有则增加最小显式 groups 过滤，默认保留旧行为。单组 smoke 只报告 smoke，不输出消融晋级结论。
3. 使用 P0-B 三个用例。遇到人工审批按正式审批入口处理，并记录审批事实；不要在数据库中直接改成 APPROVED。
4. 确认六节点全部终态成功、Evaluator 通过、交付就绪接口通过，并生成一个真实 ZIP。下载 ZIP 成功不等于完整业务运行成功，只能说明当时骨架门禁通过。
5. live 前核验授权 ID、金额上限、剩余预占、模型 Key 配置状态和隔离环境。旧 10 元授权不得默认为可再次使用。
6. 新授权到位后只跑一个 smoke case。首次失败即读取原记录定位，不自动扩大为四组或三领域 live；再次付费尝试仍受同一授权剩余额度和明确尝试上限约束。
7. 每次保存 run/trace ID、commit 与 dirty 状态、候选/Prompt/Schema hash、节点终态、Token、估算成本、门禁结果和失败码。原始 Key、业务私密文本和完整模型原文不进入证据。
8. 更新 `docs/archive/evidence/` 中的脱敏记录；总计划写“候选版本通过某个用例”，不能据一个成功例声称总体质量提升。

**最小测试**：三个 fixture 正常 E2E；授权后一个 live smoke。P0 不做消融、统计显著性、并发容量或全部故障注入。

## 6. P1-A：先完成可执行契约

**已有入口**：`schemas/backend_design.py`、`frontend_skeleton.py`、`architecture_design.py`、`review.py`、`evaluation.py`；`review/shared_contract.py`；`runtime/production_handlers.py` 中输入/输出与上下文校验；Java Handler catalog。

**新增建议**：`agent-engine/schemas/spec_contract.py` 和 `schemas/verification.py`。以下结构是待实现的约定，不是声称已有字段。可以把紧密相关模型放同一文件，不为每个数据类建文件。

### 6.1 首版字段范围

| 对象 | 必须明确表达 | 首版限制 |
| --- | --- | --- |
| 字段类型 | `kind` 与适用的 `length/precision/scale`，nullable | 仅 string、integer、bigint、decimal、boolean、date、datetime；不接受 SQL 类型片段 |
| 表 | table_id、name、fields、primary_key 字段列表、foreign_keys、requirement_refs | FK 指定本地字段、目标表 ID、目标字段；首版仅引用目标主键；支持组合键但必须同长度同类型 |
| API 参数 | name、location(path/query/body)、type、required | flat JSON 对象 body；不支持任意嵌套、文件上传、XML 或用户 JSON Schema 注入 |
| API 响应 | success_status、具名字段、type、nullable | 首版一个成功响应结构；不把自由文本当类型或表达式 |
| 前端绑定 | binding_id、backend_api_id、consumer、参数映射、被消费的响应字段 | 参数写明目标 name/location 与源 prop/state 名；响应写明字段名和预期类型；不接受 JS 表达式 |
| Shared Contract | 同一类型系统下的 API 签名、DTO、权限、稳定 ID | Architect 制定；Backend/Frontend 只能消费，不能各自修订 Architect 的接口事实 |
| 验证要求 | required_level、rule_profile、verifier_version、适用检查 | 来源为冻结执行策略，不从模型输出的“我不需要验证”读取 |

为降低首版歧义，字符串长度、decimal 精度都设明确上限；identifier 仅 ASCII，长度 1–64。未支持类型返回稳定 `CONTRACT_UNSUPPORTED_TYPE`，不能自动降成 `string` 或 `TEXT` 后报告通过。

### 6.2 操作

1. 冻结三个小型手写规格：一个普通 CRUD、一个含外键、一个有 path/query/body 与页面字段绑定，尽量复用 P0-B。
2. 新模型与旧模型分开命名/注册。不要在被旧 SharedContract 导入的 `FieldDesign` 上直接新增必填字段；即便新增可选字段，也会改变生成 Schema hash。
3. 新 Architect、Backend、Frontend 模型共享一套新类型定义；保留旧 Pydantic 模型和其 hash。新输入不能经过旧模型解析后丢失字段。
4. 新旧解析按冻结 Handler/Schema 标识明确分派；不能用“新版解析失败就试旧版”的宽松回退。
5. 旧 Artifact 展示和回放照常；不能自动猜主外键为旧 Artifact 补出“已验证”结论。旧数据请求强验证时返回缺少可执行契约字段。
6. 把新模型接到上下文校验、Shared Contract 校验、注册表、Java catalog 和消费 Artifact 的代码骨架导出；先确保这些消费者能保留新字段，再写编译器。
7. 适用性由策略和明确引用决定：无本地表的需求不必伪造表；可标 `NOT_APPLICABLE` 但必须有结构依据和适用规则，不能由 LLM 任意豁免 MUST。

**最小测试**：一个新规格通过注册/序列化往返；一个旧快照 hash/解析不变；一个关键新字段缺失或未知类型被拒绝。无需为每个字段重复测试 Pydantic。

**停止条件**：如果仍需要根据描述猜测参数位置、字段类型或 FK，契约未完成，禁止进入编译阶段。

### 6.3 冻结验证策略，避免实施时临时决定

新增节点级 `verification_policy`，只在新候选显式声明。首版形状如下，具体值在候选注册时冻结：

```json
{
  "enabled": true,
  "scope": "FULL",
  "required_level": "L2",
  "rule_profile": "spec-full-v1",
  "verifier_version": "spec-verifier-v1",
  "compiler_version": "spec-compiler-v1",
  "timeout_ms": 30000,
  "max_input_bytes": 262144
}
```

- Backend 使用 `scope=BACKEND` 和对应 `spec-backend-v1`；Reviewer 使用 FULL；Evaluator 使用 FULL 的同一要求核验可信结果，不重复发起验证。实际运行是否启用 L2 必须在启动前冻结。
- 具体必需检查由版本化 profile 的固定目录决定，不能从模型提交的 checks 列表决定。`enabled=false` 只允许未要求验证的旧路径/显式开发候选，不能覆盖同一已冻结运行的要求。
- Java 的 WorkflowSpec/parser/validator、执行 bundle、节点输入装配，与 Python 的 WorkflowSpec、NodeCommand、ModelExecutionContract 同步支持该字段；枚举、默认缺省语义和 hash 规则一致。
- 字段缺失时不向历史 canonical JSON 注入新字段，不改变其 hash。新候选要求的字段缺失则拒绝启动；不能自动用当前全局设置补齐。
- Gateway 从冻结策略决定 sidecar 参数。`tool_policy.per_call_timeout_ms`、工具总时间、节点 timeout 与剩余运行 deadline 必须容纳同一次验证；不得保持旧 5 秒工具超时却让 sidecar 跑 30 秒。总 deadline 优先，剩余不足时拒绝开始。
- 新报告的 `achieved_level` 只允许 `NONE/L1/L2`，未执行为 NONE；达成层级与 PASS 分开，执行过 L2 但验证失败仍不能交付。

## 7. P1-B：确定性编译器

**新增建议目录**

```text
agent-engine/spec_verifier/
  __init__.py
  compiler.py       # 纯函数：规格 → 文件映射
  validators.py     # L1；P1-C 实现
  report.py         # 排序、hash、issue 汇总
  service.py        # sidecar 入口；P1-E 实现
  sandbox.py        # MySQL / tsc；P1-E 实现
```

**接口约定**：`compile_spec(spec) -> CompiledSpec`，返回按相对路径索引的 UTF-8 文本、源输入 digest、编译器版本和文件 digest。不接收输出目录、命令或外部 URL。落盘由受限 sandbox 管理。

**操作**

1. 写统一 canonical JSON/hash 工具；使用稳定字段顺序和明确数组顺序，保留有语义的列顺序。耗时、时间戳、随机工作目录不能进入编译产物 hash。
2. 标识符先白名单和长度校验，再按目标语言转义；SQL 标识符统一反引号。类型只从枚举映射，不允许直接拼接模型返回的 `VARCHAR(...)`、默认表达式或 SQL。
3. OpenAPI 使用内部 `$ref`；按 path/method 排序，声明 path 参数且 required=true，operationId 来自稳定 API ID。禁用远程 `$ref`，校验器不能访问网络补引用。
4. DDL 只生成建表、主键和受限外键。先创建所有表，再添加 FK；暂不生成 trigger、procedure、INSERT、任意 ALTER、用户权限语句或注释中的可执行内容。
5. 用结构化 API 定义生成客户端类型；用前端自己的参数/响应映射独立生成消费桩。不得用 API 反向生成“肯定匹配”的前端映射，否则 tsc 只是自证。
6. TS 标识符使用生成的安全符号映射；文本只作 JSON 编码后的字面量。检查代码不得使用 `any`、`@ts-ignore`、双重类型断言或 `skipLibCheck` 掩盖消费错误。
7. 生成只含已允许文件名的 `openapi.json`、`schema.sql`、`client.ts`、`bindings.ts`、固定 `tsconfig.json` 与 manifest。生成目录不能放模型给定的 package scripts。

**最小测试**：相同输入编译两次逐字节一致；一个正常规格检查关键路径/PK/FK；一个恶意 identifier/type/路径拒绝的参数化测试。不用对所有输出行作脆弱的大快照断言。

**验收**：没有执行模型文本的入口，产物可重复，frontend 桩与 API 来自不同来源。

## 8. P1-C：L1 验证与报告

### 8.1 报告约定

`VerificationReport` 至少包含下列内容；请求身份由 Gateway 注入，不由 LLM 自述：

- schema_version、verifier_version、compiler_version、rule_profile、policy_hash。
- execution_id、scope(`BACKEND`/`FULL`)、required_level、achieved_level、status(`PASSED`/`FAILED`/`ERROR`/`NOT_RUN`)。
- source_bindings：上游 Artifact 的 type/id/version/hash，待验证候选的 schema 标识与 canonical content hash。Backend 候选尚未落 Artifact 时不能编造 artifact_id。
- checks：check_id、level、status(`PASSED`/`FAILED`/`NOT_APPLICABLE`/`NOT_RUN`)、耗时、关联组件和需求 ID。
- issues：稳定 code、severity、source_path、component_id、requirement_refs、简短 message、修复建议。
- evidence_digest、工具事实引用；运行时间单独保存，不参加语义内容 digest。

`PASSED` 必须同时满足：要求的检查已执行、没有阻断问题、来源/策略匹配。`ERROR` 代表执行环境/超时等错误，不得伪装为模型规格错误后无限 Replan；`NOT_RUN` 绝不等于通过。

### 8.2 检查与最小缺陷集

首轮用 8 类必要风险，每类一个最小反例；FK 的“目标缺失”和“类型不符”各一个，因为是两个独立路径。共 9 个缺陷样本，另复用 3 个正常规格。替代总计划原来的“8 类 × 3”，不为了凑数量复制样例。

| 类别 | 检查 | 示例 issue code | 阻断 |
| --- | --- | --- | --- |
| OpenAPI | path 中的参数未声明/不必填、操作定义非法 | `OAS_PATH_PARAMETER_INVALID` | 是 |
| 主键 | 持久化表缺主键或引用不存在列 | `DDL_PRIMARY_KEY_INVALID` | 是 |
| 外键 | 目标不存在；本地与目标列类型/长度/精度不匹配 | `DDL_FOREIGN_KEY_TARGET_MISSING` / `DDL_FOREIGN_KEY_TYPE_MISMATCH` | 是 |
| 绑定目标 | 前端引用的 API ID 不存在或签名不匹配 | `BINDING_API_NOT_FOUND` | 是 |
| 请求绑定 | 必需参数未提供或位置/类型错误 | `BINDING_PARAMETER_MISMATCH` | 是 |
| 响应绑定 | 页面消费了 API 未定义的字段/类型 | `BINDING_RESPONSE_FIELD_MISSING` | 是 |
| 追踪 | 悬空引用或 MUST 缺适用的已验证覆盖 | `TRACE_REQUIRED_COVERAGE_MISSING` | 是 |
| 输入安全 | identifier/type 中带 SQL/代码片段或远程引用 | `CONTRACT_UNSAFE_INPUT` | 是 |

**操作**

1. OpenAPI 使用明确支持目标规范的 validator；MySQL DDL 使用 parser 的 MySQL 方言。锁定依赖版本并限制引用解析，不依赖字符串 `contains` 判断语法合法。
2. PK/FK、绑定和追踪根据规格对象与 ID 图检查，不能只检查生成产物，因为编译器可能漏掉错误字段。
3. 同一问题按 `(code, source_path, component_id)` 去重并稳定排序。记录路径采用 JSON Pointer 风格，能回到原 Artifact 字段。
4. 检查 profile 区分 BACKEND 与 FULL。Backend 不等待尚未完成的 Frontend，也不把没有前端数据误判成完整验证通过。
5. 工具传输成功与规格验证通过分开：返回报告可以是一次 `SUCCEEDED` 的工具调用，但报告 `status=FAILED`；调用不可达是传输失败。
6. 缺陷集使用人写的预期 code/路径；不能从本次验证器输出自动生成“预期”。所有已声明阻断样本必须检出；不以总体 ≥90% 掩盖关键漏检。
7. 用固定小规格做一次预热及有限次计时，记录环境/输入规模和 P95 是否低于 2 秒。不把开发机器抖动的 wall-clock 断言塞进每次单元测试。

**验收**：正常样例无误报，9 个已声明缺陷均被拦截，code 稳定；报告只能证明该范围的契约性质，不能宣称业务正确率。

## 9. P1-D：接入受控工具与可信事实

**入口**

- Python：`schemas/tool_gateway.py`、`schemas/workflow_spec.py`、`runtime/tool_gateway.py`、`runtime/tool_harness.py`、`contracts/tool-gateway.schema.json`。
- Java：`service/ToolGatewayService.java`、`dto/ToolGatewayRequest.java`、`dto/ToolGatewayResponse.java`、`workflow/runtime/WorkflowExecutableContractValidator.java`。
- 新增 Java `SpecVerificationClient` 与薄服务，复用现有 HTTP/配置习惯；无需 Worker 直接调用 sidecar。

**操作**

1. 给新工具建立独立参数 Schema：候选结构、scope、上游引用、候选 digest；不得复用过宽的任意参数模型。实际 verification policy 从冻结 bundle 读取，忽略或拒绝调用者擅自降低层级。
2. 把 `spec.verify` 加入 Java/Python 固定目录，`SANDBOXED` 加入工具注册与双方 allowed_side_effects 校验。旧工具名单与默认策略不变。
3. Gateway 对 run/project/actor/node/execution/fencing/policy hash/deadline 做已有校验，再验证上游 Artifact 真属当前运行可用输入；不相信 caller 提供的 artifact hash。
4. 候选内容与上传 digest 由 Gateway/sidecar重新计算一致性。同一个 execution 可验证多个候选，不用 execution_id 单独做幂等键。
5. 幂等身份包括 execution、scope、候选内容、上游来源、策略与验证器版本。相同 key + 不同内容为冲突；相同请求的重投返回已记录结果。
6. 先核对工具事实唯一索引及并发插入时机。当前“先查询已有结果、执行、最后存结果”不足以保证两个同时请求不重复执行。新增原子占用/唯一键领取，IN_PROGRESS 不被当作 PASS；必要时新增迁移，不重写既有表历史。
7. 外部校验不能占着业务事务连接等待数十秒：短事务领取 → 事务外调用 → 短事务完成。不要依靠同类方法自调用让 Spring `@Transactional` 生效；复用事务模板或独立服务。
8. 调用前后都校验有效执行与 fencing；期间取消或被替代的结果可留审计，但不可成为当前交付证据。Gateway 成功落账后才向 Worker返回可信 fact_id/digest。
9. sidecar 网络超时、响应过大、非法报告映射为明确错误；不把异常吞掉返回空 issues。

**最小测试**：一次正常调用留完整事实；同 key 并发两次只执行一次、改内容冲突；一个参数化授权/fencing/层级降低拒绝测试。复用原 ToolGateway 测试的其他权限与预算场景，不重新测试每种排列。

**验收**：Worker 只经 Gateway；授权失败无外部执行；模型自己写的 PASS 不能产生可信事实。

## 10. P1-E：隔离 sidecar、真实 MySQL 与 tsc

### 10.1 固定部署方案

新增 `spec-verifier` 与 `verify-mysql`，放入 Compose 的 `verification` profile。默认普通启动不启用；新候选若要求 L2 而 profile 未开，应明确失败，不能静默降级。

| 网络 | 成员 | 用途 |
| --- | --- | --- |
| 现有业务网络 | 保持原成员 | 业务 MySQL/Redis 与 Worker |
| verifier-control（internal） | backend、spec-verifier | 内部校验 HTTP；sidecar 不加入业务网络 |
| verifier-data（internal） | spec-verifier、verify-mysql | 临时 DDL；backend/Worker 不加入 |

不向宿主发布 verifier/MySQL 端口；容器没有 Docker socket 或主机目录挂载。网络名不同不是验证证据，仍要从 verifier 实际尝试业务 MySQL/Redis 的 DNS 与已知私网 IP，并检查外部出口不可达。backend 多网络连接不能被当作允许数据库代理的理由。

### 10.2 安全与资源的具体起始值

- verifier：非 root、只读根目录、`cap_drop: ALL`、`no-new-privileges`；工作目录 tmpfs，单并发；起始 1 CPU、512 MiB、pids_limit 128、tmpfs 64 MiB。
- 请求体上限 256 KiB，完整响应不超过冻结工具大小上限；外层 deadline 起始 30 秒，子进程与 SQL 超时不超过其剩余时间。这些是待验收参数，超过限制返回稳定错误。
- 不静默截断问题列表后返回 PASS；超限要返回 `ERROR` 和明确 result-limit code，保留可用摘要。
- verify-mysql：独立 MySQL 8.4 实例；数据、socket 和临时目录使用 tmpfs，不复用业务卷；起始内存 1 GiB。镜像启动权限与 tmpfs 路径需实际验证，不盲目给数据库套用 verifier 的只读根设置。
- verifier 只得到验证数据库专用账号和独立 `SPEC_VERIFIER_SERVICE_TOKEN`，没有业务 MySQL、Redis、模型 Key 或后端通用 service token。新秘密仅存本地未跟踪 `.env`，`.env.example` 写无值说明。
- 依赖在镜像构建阶段固定安装；请求执行期间不得 `npm install`、下载代码或访问远端 `$ref`。

### 10.3 操作

1. sidecar 暴露私有 `/health` 与 `/verify`；服务 Token 校验、body 上限和 deadline 校验先于编译和落盘。健康接口只报状态，不报环境变量。
2. sidecar 重新解析结构化输入并调用同一编译器；拒绝 caller 直接提交待执行 SQL、TS 文件或 shell。
3. 为每次执行创建服务端随机目录与 schema 名，仅允许固定 prefix + 随机 hex；客户不能指定数据库名。不要仅按 run ID 共享目录。
4. MySQL 专用用户只允许验证命名空间所需的 CREATE/DROP/ALTER/REFERENCES 等操作；启动管理凭据不交给 verifier。MySQL grant 中 `_`/`%` 的通配行为要按实际目标校验，不能因写了前缀就假定隔离正确。
5. 通过数据库驱动逐条执行编译器生成的 DDL，关闭 multi-statements；server 端设置合适的会话/锁等待上限。客户端 timeout 不代表服务端语句已停止，要关闭/终止会话并确认清理。
6. `finally` 删除该次 schema 和临时文件；进程崩溃遗留由启动时仅针对自身安全 prefix 的清理恢复。禁止将清理连接指向业务数据库，禁止对用户数据卷执行 `down -v`。
7. tsc 使用镜像内固定可执行文件，参数固定为 `--noEmit --strict` 与服务端生成的 tsconfig。`shell=False`，timeout 后终止整个进程组并回收资源；不执行任何生成 JS 或 npm script。
8. 编译失败映射 `DDL_APPLY_*` / `TS_*`，证据只含来源路径与受限诊断，不返回数据库连接串、完整环境或无限 stderr。
9. 同一验证请求内容可重用完成结果；随机 schema/目录、时间戳与耗时不参与语义 digest。verifier/rule/compiler 版本改变后不能使用旧缓存。
10. 增加固定场景的验证脚本，例如 `scripts/verify_spec_sandbox.py`。只操作新 profile 的资源，不提供任意命令执行参数；输出脱敏结果 JSON，临时故障配置结束后清理。

**最小边界验证**

- 一个正常 FULL 规格完成真实 DDL 落库与 tsc，结束后 schema/临时目录已清理。
- 从容器验证业务数据库、Redis 和外部出口不可达；验证自己的临时 MySQL可达，防止把“所有网络都坏了”当隔离通过。
- 一个故意超时场景确认子进程/SQL 不继续运行、证据不是 PASS、资源回收。
- 一个超大输入/输出拒绝场景；容器配置与一次受控资源上限探针确认限制实际生效。

这些是沙箱隔离的必要边界，不做任意 SQL 漏洞大字典或大量 shell 注入排列。L1 已拒绝的恶意文本不再逐个跑容器。

**验收**：有真实容器和 MySQL/tsc 证据。只有 mock HTTP 成功不能标此任务完成。

## 11. P1-F / G：接入循环、汇合与交付门禁

### P1-F：Backend 局部反馈，Reviewer 完整验证

**入口**：`runtime/backend_agent_loop.py`、`review/backend_validator.py`、`runtime/production_handlers.py`、`agents/reviewer.py`、新 Schema、两端新 Prompt。

1. 为新候选注册 `backend-design-v2` validator profile，旧 `backend-design-v1` 保持原行为。新 profile 每次 Candidate 后执行 L1 结构验证；需要 L2 时经工具调用。
2. Backend 的输入只能依赖 PRD、Architect 和自己的候选，scope=BACKEND；不得等待 Frontend 或硬编码改变 DAG。
3. 验证结果必须进入原有 validation_issues/observation 和 step trace，包含 code、源路径、候选 hash 与 fact 引用；复用已有 Replan 和停止逻辑，不另建第二个 agent loop。
4. 对已定位的本节点规格问题允许 Replan；对环境不可用、超时、无权限、预算耗尽立即结束为相应错误，不让模型“修复基础设施”。Architect Shared Contract 本身冲突则交回正确节点，Backend 不擅自改上游。
5. 无变化候选依旧受到 no-progress/oscillation 限制；每次 verifier 调用计入 tool calls 和 deadline。模型可选择工具辅助，但必须执行的验证不能由它选择跳过。
6. Reviewer 等两个分支都完成，scope=FULL，使用本次实际落库的 Artifact 版本作来源。只有这里才做跨前后端的参数/响应绑定回放。
7. Reviewer 将确定性失败映射稳定 ReviewIssue 和返工目标；高风险级别不能被语义模型降级。先读取可信校验报告，再合并语义审查，不让 LLM生成 fact_id/已验证状态。
8. 新 ReviewReport 内嵌报告摘要，完整工具结果仍从可信台账读取。不要向新旧通用模型直接塞字段导致旧 Schema hash 变化。

**最小测试**：一个有明确缺陷的假模型候选经 verifier 反馈后第二次修正成功；一个不改候选达到既有停止条件；一个并行场景证明 Backend 不等 Frontend、Reviewer 才验证 FULL。已有循环预算/震荡用例复用，不再穷举步数。

### P1-G：Evaluator 与直接交付接口都检查证据

**入口**：`review/evaluator.py`、`schemas/evaluation.py`、Java `WorkflowNodeInputAssembler.java`、`MybatisWorkflowArtifactProjector.java`、`DeliveryGateService.java`、`GeneratedBundleVerificationService.java`、`CodeSkeletonService.java`；必要时 OpenAPI/前端类型。

1. 控制面为 Evaluator 装配可信 verifier 摘要与引用；不能直接把模型输出里的 `verification: PASSED` 当事实。
2. 校验 fact 所属项目、run、execution、scope、source hashes、policy_hash、verifier/rule/compiler 版本和要求层级；任何不匹配都不能通过。
3. 追踪矩阵的“已验证”应逐需求由关联组件的适用 checks 汇总，而不是把整份 report 的总 PASS 复制给所有 MUST。缺覆盖或仅 NOT_RUN 都阻断。
4. 旧运行是否要求新验证由它自己的冻结策略决定；不追溯要求历史运行具备不存在的报告。新候选明确要求验证，缺字段就阻断，不能回退旧宽松分支。
5. Artifact 人工编辑、返工或新版本生成后，之前的 report hash 不再对应当前交付集合；要求重新验证。不能只检查同一个 run 曾经有过一次 PASS。
6. Java 交付门禁再次核对可信事实与最终 Artifact 集合，覆盖代码生成、Markdown/PDF/ZIP 等正式交付入口；避免绕过 UI 直接请求导出时被放行。
7. 生成包 manifest 绑定验证 fact/digest 与源 Artifact hash；原有 required files、secret scan、骨架编译等校验保留，但名称和证据不能暗示已经执行完整业务验收。
8. 源 hash 与 report digest 保持单向依赖：digest 绑定规格 Artifact，不包含正在生成的报告自身或 manifest 的自引用 hash，避免循环计算。

**最小测试**：一个可信 FULL PASS 可交付；一个参数化测试覆盖报告缺失/来源过期/层级不足/假 fact；一个“Artifact 改动后直接导出”被拒绝用例。复用已有 DeliveryGate/CodeSkeleton 测试正常路径，不为每个导出格式复制整套案例。

**验收**：让模型把报告改成 PASS 不能放行；改动 Artifact 后旧证据失效；超时和 L1 不冒充 L2。

## 12. P1-H：候选封装、必要回归与交付记录

1. 为新契约、验证策略、新 Handler/Prompt、工具 allowlist 和预算生成新候选，保持原并行拓扑和六节点数量。
2. 新策略放在 WorkflowSpec 的明确受支持字段，Java/Python 严格解析一致；不要把任意扩展塞进未经校验的 input payload。新增字段要同步解析、bundle hash、测试和 OpenAPI 中实际受影响的 DTO。
3. 新迁移只插入候选与确需的事实字段/唯一索引；数据库迁移编号实时分配。旧注册/旧 canonical/hash 必须仍通过脚本。
4. fixture 模式通过正式 API 跑一条包含真实 verifier/L2 的六节点链路，验证审批、返工和交付证据。fixture 指的是不调用 LLM，verifier 的数据库和 tsc 仍真实执行。
5. 用缺陷候选证明一次可解释的 Replan，再证明缺失/过期证据不能交付。复用 P1-F/G 样例，不重新造大测试集。
6. 在 P0 和 P1 各自准备交付的里程碑，按仓库要求运行一次三端全量、构建、契约校验与 Compose 配置检查。日常每个任务只做对应定向测试；未变化且仍适用的检查不机械重复。
7. 新 profile 增加 `docker compose --profile verification config --quiet`；不要输出完整解析配置，避免泄露环境值。
8. 记录候选未激活、live 是否执行、真实 L2 是否执行、远端 CI 是否重跑。P2 消融、候选晋级、commit/push 均不自动开始。

### 失败后的最小回退步骤

1. 停止该候选的新运行，记录最后一次 run/trace 与失败码；不修改已失败历史为成功。
2. active 始终未切换时，正常用户仍使用原已发布版本。若只是本地显式选择候选，下一次选择原版本即可；不重写数据库中的 published spec。
3. verifier 不可用时，保持要求 L2 的候选阻断。如需验证 L1 开发线，创建明确要求 L1 的独立未激活候选，不在同一冻结运行里改层级。
4. 新迁移不执行反向删除、不恢复旧 SQL 文件。可通过后续修复迁移纠正可恢复数据问题，必须保留证据与历史版本。
5. 仅停止验证 profile 的服务时，先确认其他任务没有使用它们，再使用服务级 stop；不执行全项目 down，更不删除业务数据卷。
6. 代码尚未提交时保留本轮 diff 与用户原有修改。回退具体错误编辑应逐文件/逐块处理，不能用 `git reset --hard` 清场。

## 13. 执行状态与交接模板

任务执行者只更新实际状态，禁止因文档已存在或测试用 mock 通过就标 `done`。

| 任务 | 状态 | 证据/阻塞 |
| --- | --- | --- |
| P0-A | done | 契约脚本通过；旧 active v5/v5-parallel/diagnostic 内容哈希未变；环境限制见证据。 |
| P0-B | done | `fixtures/software_domains.py` 与参数化三领域测试；3 个 fixture 均通过。 |
| P0-C | done | `spec-full-v1` 通用规则、legacy 规则隔离；approval/event/retry 不再误报，真实引用/权限缺陷仍阻断。 |
| P0-D | done | 一次有界结构化修复、预算/deadline/错误码与调用台账；相关回归通过。 |
| P0-E | done | `autospec-spec-repair` 候选、V104 DRAFT 种子、Handler/Schema hash 同步；active 未改变。 |
| P0-F | done | `MySqlFailureRecoveryIT` 在 Testcontainers MySQL 8.4 + Toxiproxy 2.5.0 通过；检测 4203 ms、恢复 31 ms、partial writes 0、manual repairs 0。 |
| P0-G | blocked_validation | project 4 / run 7 通过正式 `POST /api/workflow-runs`、人工审批、六节点、Markdown/PDF/ZIP；run 8/9 的 DeepSeek live 均在 Product Manager 输出边界失败，故 live 成功门槛仍未满足。 |
| P1-A | done | `spec-contract-v1` 明确字段、PK/FK、参数位置、响应、绑定与验证策略；旧 Schema 兼容测试通过。 |
| P1-B | done | 确定性生成 OpenAPI/DDL/TypeScript/绑定/tsconfig/manifest；确定性与安全边界通过。 |
| P1-C | done | 3 个正常规格与 9 个最小缺陷稳定 code/path/digest 报告通过。 |
| P1-D | done | `spec.verify:v1`、SANDBOXED allowlist、deadline、幂等并发与 scope/fencing 校验已接入；离线 Gateway 回归通过。 |
| P1-E | done | verification profile 中真实 verifier、MySQL 与 TypeScript L2 对校园交易、库存管理、员工请假审批三个 fixture 均 `PASSED`，issues 为空。 |
| P1-F | done | 候选 Backend Loop 已按 BACKEND scope 强制调用 `spec.verify`，把 issue/source/candidate/fact ref 写入 step trace，并复用 bounded Replan；Reviewer FULL 验证已接入。真实 verifier L2 与正式 fixture API 证据已补齐，live/远端 CI 仍属于 P1-H 收尾边界。 |
| P1-G | done | Evaluator 与 Java Delivery Gate 均 fail-closed；缺失/过期/伪造/低层级 fact 回归通过，候选控制面装配已补齐。 |
| P1-H | blocked_validation | 候选、V104、全量回归、真实 L2、正式 fixture API/ZIP 和证据已整理；live run 8/9 失败于结构化输出边界，远端 CI 未重跑。 |

每个任务结束，在本表后追加简短记录；详细报告放 `docs/archive/evidence/`，生成临时结果放已有忽略的 target 目录，确认后再保存脱敏摘要。

```text
任务：P0-X / P1-X
状态：done / in_progress / blocked_validation
基线：commit、分支、相关 dirty 文件
本次变更：文件 + 作用
实际执行的测试：完整命令、通过/失败数量
未执行：项目 + 原因
证据：run/trace/fact ID、候选/策略/源内容 hash、脱敏报告路径
已知限制：例如只测 fixture、未跑 Docker、未获 live 预算
下一步：任务编号 + 第一个动作
```

### 本次执行交接记录（2026-09-30）

- 基线与交付分支：`codex/p0-p1-complete-20260930`；按任务分段提交，当前已提交至 `5e377ca`，未 push、未激活候选、未删除数据卷。
- 主要变更：三领域 fixture 与通用 Reviewer 规则；结构化输出单次修复；候选 `spec-repair`；`spec-contract-v1` 编译器/L1；`spec.verify:v1` 与可信事实；候选 Reviewer→Evaluator→Delivery Gate 传播；verification Compose profile。
- 实际验证：Agent Engine 全量 `165 passed`；Backend `mvn -q test` 通过并 Flyway 应用 V104；前端 `9 files / 25 tests` 与 `npm run build` 通过；契约脚本和三种 Compose config 校验通过。
- 真实验证：`MySqlFailureRecoveryIT` 通过；三个 fixture 的 verifier L2（MySQL + TypeScript）通过；project 4 / run 7 正式 API 完成六节点、审批、Markdown/PDF/ZIP，ZIP 包含 4 个必需文件并生成 READY 门禁状态。
- live 结果：run 8、run 9 各执行 1 次 DeepSeek Product Manager 调用，均因输出 JSON 未在冻结上限内闭合而失败；没有继续扩大付费尝试。远端 CI 未重跑。
- 下一步：若要完成 P0-G/P1-H，先为 Product Manager 提供经批准的新输出预算或已验证的更短结构化 Prompt/候选版本，再用同一正式 API 做单次 smoke；随后按仓库远端策略重跑 CI。不得修改 active/historical WorkflowSpec 或历史 SQL 代替验证。

## 14. 最小测试清单与命令

### 14.1 测试取舍

**只新增**：新行为的一条核心成功路径；权限、预算、deadline、幂等并发、历史兼容、数据损坏、隔离和交付门禁的必要反例。正常规格、伪模型、Gateway fixture 全阶段复用。

**不新增**：每个函数都有一个测试、getter/setter、框架类型校验排列、巨量随机输入、全部错误组合、只验证实现内部调用次数但无外部意义的 mock 测试、与本任务无关的覆盖率目标。

**不删除**：现有有效的高风险边界回归。最小测试不是降低门禁，也不是把未执行改成跳过即通过。

总计划“8 类 × 3”缺陷集在本轮改为 P1-C 的 8 类 9 个最小反例，后续发现真实漏检再加样本。全量回归是复用既有套件的里程碑检查，不要求给每个新函数补单测。

### 14.2 路径与命令约定

所有块从仓库根目录开始。固定可执行文件路径直接从 AGENTS.md 读取，不重复询问用户，也不把新的本机绝对路径写入源码。每次新终端先运行下面的初始化块，后续命令复用变量。

```powershell
$repo = (Get-Location).Path
$guidelines = Get-Content -LiteralPath (Join-Path $repo 'AGENTS.md') -Raw -Encoding utf8
$py = [regex]::Match($guidelines, '(?m)^- Agent Python：`([^`]+)`').Groups[1].Value
$mvn = [regex]::Match($guidelines, '(?m)^- Maven：`([^`]+)`').Groups[1].Value
if (!$py -or !(Test-Path -LiteralPath $py)) { throw 'Agent Python path in AGENTS.md is unavailable' }
if (!$mvn -or !(Test-Path -LiteralPath $mvn)) { throw 'Maven path in AGENTS.md is unavailable' }
git status --short
& $py scripts/verify_workflow_contract.py
if ($LASTEXITCODE -ne 0) { throw 'Workflow contract verification failed' }
```

Python 定向回归（按任务选文件，不每次全选）：

```powershell
Push-Location (Join-Path $repo 'agent-engine')
try {
    & $py -m pytest -q tests/test_model_gateway.py tests/test_production_handlers.py
    if ($LASTEXITCODE -ne 0) { throw 'Model/handler tests failed' }
} finally { Pop-Location }
```

规则任务将文件替换为 `tests/test_review_rules.py tests/test_evaluator.py tests/test_p1_parallel_and_retrieval.py`；工具任务用 `tests/test_tool_gateway.py` 及实际已有 harness 测试；循环任务用 `tests/test_agent_loop.py`。P1 新测试文件在任务实现后再运行，不以尚不存在的文件作为已完成验证。

Java 定向回归（按任务调整类名）：

```powershell
Push-Location (Join-Path $repo 'backend')
try {
    & $mvn '-Dtest=WorkflowExecutableContractTest,ToolGatewayServiceTest,DeliveryGateServiceTest,CodeSkeletonServiceTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Targeted backend tests failed' }
} finally { Pop-Location }
```

MySQL 故障检测单项，使用已经配置的 Failsafe profile：

```powershell
Push-Location (Join-Path $repo 'backend')
try {
    & $mvn '-Pintegration-test' '-Dit.test=MySqlFailureRecoveryIT' verify
    if ($LASTEXITCODE -ne 0) { throw 'MySQL failure recovery verification failed' }
} finally { Pop-Location }
```

里程碑全量回归使用 AGENTS.md 中的三端命令、契约和 Compose 检查；涉及新迁移时再执行已有 integration-test profile。不要在 Docker 不可用时声称集成通过。若 Windows 出现 `spawn EPERM`，记录为工具执行限制并通过正常权限机制重试，不改业务代码绕过。

live 采集命令在 P0-G 确认单组过滤与配置后使用现有 `python -m evaluation.run_control_plane --config ... --output ...`。配置中的授权、候选 version ID 和用例必须实际解析；不得复制旧 live 配置直接付费重跑。

## 15. 最终核对表

- [x] 旧 published WorkflowSpec、旧 Prompt 和历史 SQL 内容未被改写。
- [x] 三领域 fixture 通过；平台关键词不误杀，真实权限/引用缺陷仍阻断。
- [x] 单发修复最多一次，调用事实与预算一致；Backend Loop 未新增嵌套修复循环。
- [x] 新候选注册/两端 Prompt/Schema hash/种子同步，active 未改变。
- [x] MySQL 故障检测问题有原因及定向验证；`MySqlFailureRecoveryIT` 通过并记录 detection/recovery/partial-write/manual-repair 结果。
- [ ] P0 live 结果明确；run 8/9 已明确失败原因，但没有成功用例，保持 P0 未全部完成。
- [x] 正式 fixture API 六节点与交付导出完成；run 7 通过审批，Markdown/PDF/ZIP 均成功，ZIP 生成后状态为 `READY`。
- [x] 新契约可表达验证所需事实，没有按名称猜主外键和参数位置。
- [x] L1 正常样例与最小缺陷集通过，报告 code/路径/digest 可追踪。
- [x] Gateway 对新工具的授权、并发幂等、deadline 和可信事实校验有效（离线受控回归）。
- [x] 真实 L2 完成，隔离与资源限制有证据；三个 fixture 均通过 verifier MySQL 与 TypeScript 检查。
- [x] Backend 验证局部、Reviewer 验证汇合产物及错误反馈完整闭环；Backend 使用 BACKEND scope，Reviewer 使用 FULL scope，错误反馈进入 bounded Replan；真实 L2 证据另见 P1-E。
- [x] Evaluator 和交付入口对缺失、过期、伪造或低层级证据 fail-closed（离线回归）。
- [x] 最终报告写清实际命令、结果、未执行项、预算与候选未激活事实。
- [x] 没有未经授权的 commit、push、发布、删除数据卷或额外付费实验。
- [ ] 远端 CI 已重跑并通过；本轮未 push，因此不宣称远端状态。

## 16. 给执行模型的首条指令

可以把下面这段直接交给下一轮执行模型。任务编号按第 13 节状态表推进，不需要把全计划重复粘贴到每轮对话。

```text
阅读 AGENTS.md 和 docs/p0-p1-execution-plan.md。
先执行第 3 节固定流程，再处理第 13 节中第一个前置条件已满足的未完成任务。
本轮仅完成该任务，不提前修改后续任务文件，不新增本文之外的功能。
先核对本地已有修改，复用已有实现和测试。
只补该任务列出的必要边界测试，不逐函数增加测试，不机械跑三端全量。
按实际命令结果验收；mock、fixture、真实沙箱、live 和远端 CI 分开记录。
完成后更新第 13 节，附修改文件、测试结果、未执行项和下一步。
没有额外授权时不付费调用模型、不 commit/push、不改 active、不删除用户数据。
缺少环境或预算时明确记录受阻项，并完成其他不依赖该条件的离线准备工作。
```
