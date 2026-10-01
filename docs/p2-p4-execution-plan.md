---
plan_id: autospec-p2-p4-execution
status: blocked
created_at: 2026-09-30
parent: docs/autospec-v5-spec-sandbox-plan.md
baseline: autospec-v5:pm-schema-repair-v12
database_baseline: V1
---

# AutoSpec P2–P4 修复执行手册

本文件分解 [总计划](autospec-v5-spec-sandbox-plan.md) 的 P2（证据）、P3（可靠性及独立扩展）、P4（展示），与 [P0/P1 手册](p0-p1-execution-plan.md) 属于同一条路线。按用户要求保留在 `docs/` 根目录。适用对象是上下文较短、推理能力较弱的执行模型：一次只完成一个任务，按明确输入、步骤、测试和验收推进，不自行扩展目标。

**本文件已更新为实施交接记录。** 本轮完成了可在本地离线验证的 P2–P4 代码、契约、迁移、界面和测试，并完成带 `spec-verifier` 的隔离 fixture 六节点、10/20 并发、取消、worker 重启、Redis poison 和 Outbox 进程中断恢复运行；2026-10-01 又按 README 走通了 Docker fixture 浏览器演示和审批/Trace 门禁观察，补齐平台管理员只读 A/B/C/D 评测比较接口、结果目录加载和明确未执行看板，并使用本地真实模型完成 P3-K3 同集 embedding 对照。P2-F 已补齐冻结输入下的 10/20 成功容量批次；r10 身份一致的 fixture smoke 已通过，随后在用户授权的 13 元 DeepSeek 预算内完成了 A/B/C/D live smoke 及迁移后的 D-only 重试，并用 `--resume` 补齐了 r1 的结果矩阵与 `NOT_EVALUATED` gate。live smoke 的失败、质量门禁和预算保留原样，不能晋级为完整 dev/holdout 或发布结论；P4-E 截图归档按用户明确要求豁免。剩余缺项、验证命令和下一步见 [阶段交接证据](archive/evidence/p2-p4-2026-09-30.md) 与 [浏览器演示补充证据](archive/evidence/p2-p4-2026-10-01.md)。

## 1. 开工基线与必须纠正的旧假设

先读 [AGENTS.md](../AGENTS.md) 和 [基线收敛记录](archive/evidence/baseline-consolidation-2026-09-30.md)。以下是编写时的源码/记录快照，执行时按 BASE-01 重新核对。

| 项目 | 当前依据 | 对后续执行的要求 |
| --- | --- | --- |
| 数据库 | 迁移目录仅 `V1__autospec_baseline.sql` | 不恢复 V104–V117；下一条迁移从实际最大版本 + 1 分配，当前应为 V2 |
| 当前工作流 | `agent-engine/contracts/autospec-pm-schema-repair-v12.workflow.json` | 不能再写“保持 v5-parallel 为默认”；先读实际部署选择，再保护该版本 |
| 已保留数据 | 上轮记录为 run 34–38、36 个 Artifact，v10/v11/v12 冻结版本 | 不重复清库、取消或重编号；历史记录不充当新实验样本 |
| 本地回归 | 上轮记录为 Agent 176、Backend 179、Frontend 25 项通过 | 本轮必须重新执行直接相关测试和里程碑回归；结果写入交接证据 |
| verifier 等级 | 当前候选 Backend/Reviewer 的 `required_level` 均为 L1 | 独立 L2 fixture 通过不等于正式整链路 L2 通过 |
| 显式规格 | `schemas/spec_contract.py` 有显式字段；生产适配仍由 `spec_verifier/fixtures.py` 猜主外键、参数位置 | BASE-02 补齐实际 Agent→Artifact→verifier 链路，不能只检查中间 Schema 存在 |
| 可信证据 | `DeliveryGateService` 从 Evaluation JSON 读 fact 并按其 scope 匹配；Python fact 到期时间复用 `timeout_ms` | BASE-03 补查台账、最终来源集合、FULL scope 与有效期，不能直接据此做质量晋级 |
| 评测入口 | `evaluation/control_plane.py` 的 `contract_family` 只认 v3–v6，并拼接旧文件名 | 先改为冻结的显式 manifest；不恢复已删除的候选以迎合旧采集器 |
| A/B/C/D | 现代码 C 关闭 Replan、D 开启 Replan；总计划要求对照 verifier 反馈 | 先修正实验定义，否则 C/D 同时改变两个因素 |
| Trace | 后端字段是 `nodes[].steps[]`，前端 TraceNode 类型尚未接入 | P4 扩展现有接口与页面，不另造同步生成链路 |
| 可靠性 | Recovery 处理 RETRY_WAIT/FALLBACK_READY；事件轮询保留持久投递计数和 XAUTOCLAIM，轮询任务已隔离单次运行时异常 | 继续核查租约超时、poison 上限和发布占用的实际证据，不另建第二套恢复器 |

总计划中的旧故障记录、预算估算、迁移号和默认版本均为历史背景。以此次核对的源码、冻结配置和新证据为准；不是通过改写历史证据消除差异。

## 2. 固定实施规则

1. 业务入口保持 `POST /api/workflow-runs`、Redis Streams、Python Worker、六节点 DAG；不新增 `/generate*`、第七个 Agent 或另一套循环执行器。
2. 只在 Backend 开决策循环；Reviewer 可以调用确定性 verifier，但不增加自由 Agent 循环。结构修复与节点重试共享物理调用预算，禁止“循环里面再套无上限修复”。
3. 当前已发布 WorkflowSpec、Schema、Prompt、Handler 和执行包冻结不动。新行为用确有必要的新版本；普通调试不为每次参数调整生成一套 SQL、JSON 和测试副本。
4. A/B/C/D 从一份基础配置和四组差异生成；只有冻结评测批次才物化不可变快照。冻结后变化必须形成新批次，不能覆盖旧 hash。默认版本选择单独处理。
5. 只执行确定性编译的 SQL/TS；不执行模型提供的任意 shell、SQL 或代码。不向 Worker 开放业务数据库连接。
6. 保留现有未提交改动。起点不能只记录 HEAD：还要记录 diff 摘要与源码指纹。没有本任务的明确指令时，不 commit、push、合并或切默认；已有有效授权不重复询问。
7. 本轮请求已授权实施本手册；live、真实 embedding 等外部付费动作仍须有覆盖该批次的明确额度。核对有效授权与共享台账，不把账户余额当实验额度，也不沿用已耗尽的授权。
8. 含真实数据的备份和原始 Trace 放忽略目录，公共证据脱敏后放 `docs/archive/evidence/`；新代码/文档不写本机绝对路径，工具路径从 AGENTS.md 读取。
9. 没有新外部工具需求时 P3-MCP 标 `deferred`；变更影响分析默认 `deferred`，主线完成且用户选择后再做。其余必选任务未完成不得把完整 P2–P4 标完成。
10. P3 新增行为不得回填到已冻结的 P2 结果；受影响的质量/成本对照需要新批次，不需要重复无关实验。

## 3. 执行顺序、状态与断点

默认串行顺序：`BASE-01 → BASE-02 → BASE-03 → P2-A → P2-B → P2-C → P2-D → P2-E → P2-F → P3-R1 → P3-R2 → P3-R3 → P3-R4 → P3-S → P3-F → P3-K1 → P3-K2 → P3-K3 → P4-A → P4-B → P4-C → P4-D → P4-E → P4-F → END-01`。

P3-MCP 在 P3-S/P3-F 后、且外部集成需求明确时插入；P3-I 在 BASE-03/P3-R4/P4-C 后、主线完成且选择该扩展时插入。缺预算可继续 P3-R*、P3-S 的离线部分和 P4 数据适配，不伪造 P2-E 的结果。P2-F 暴露的可靠性缺陷由 P3-R* 修复后重跑对应故障场景；不形成“必须先全通过才允许修故障”的循环依赖。

状态使用 `planned / in_progress / blocked / done / deferred`。`blocked` 写具体缺口和可继续的独立任务；`deferred` 只用于条件性扩展，不掩盖必选任务未执行。

入口路径约定：Python 的 `runtime/`、`schemas/`、`evaluation/`、`spec_verifier/`、`review/`、`tests/` 均相对 `agent-engine/`；Java 包路径相对 `backend/src/main/java/com/autospec/`，测试相对 `backend/src/test/java/com/autospec/`。只写类名的入口先用 `rg --files backend/src | rg '类名'` 定位，不猜目录。前端以 `frontend/src/` 为根。“拟新增”才允许创建；现有入口找不到时先查重命名，不创建平行替代模块。

| 本文 ID | 总计划映射 | 前置 | 初始状态 | 输出证据键 |
| --- | --- | --- | --- | --- |
| BASE-01 | 全部任务前置核验 | 无 | done | baseline |
| BASE-02 | SIG-P1-00 / 01 补缺 | BASE-01 | done | explicit-contract |
| BASE-03 | SIG-P1-04 补缺 | BASE-02 | done | trusted-delivery |
| P2-A | SIG-P2-01 | BASE-03 | done | experiment-manifest |
| P2-B | SIG-P2-02 采集器 | P2-A | done | collector |
| P2-C | SIG-P2-02 数据集 | P2-A | done | dataset |
| P2-D | SIG-P2-02 判分统计 | P2-B、P2-C | done | metrics-gate |
| P2-E | SIG-P2-02 正式评测 | P2-D、有效预算 | blocked：A/B/C/D `#10/#11/#12/#13`、r10 fixture smoke 和用户授权的 live smoke 已完成；live 结果含取消、Handler 错误、超时和质量门禁阻断，完整 development `192` 节点样本与 holdout `96` 节点样本未执行，不能形成晋级结论 | ablation |
| P2-F | SIG-P2-03 | P2-B；质量结论另依赖 P2-E | done：隔离空项目冻结 inventory 输入后完成 10/20 并发，30/30 run 六节点成功、Evaluator `100/A/PASSED`、预算已结算且无重试/恢复/重复副作用；另保留动态知识索引导致的失败 smoke，未将 fixture 观测晋级为生产 SLA 或跨配置质量结论 | traces-capacity |
| P3-R1 | SIG-P3-05 watchdog | BASE-01 | done | watchdog |
| P3-R2 | SIG-P3-05 poison / DLQ | P3-R1 | done | poison |
| P3-R3 | SIG-P3-05 outbox 占用 | P3-R2 | done | outbox-claim |
| P3-R4 | SIG-P3-05 cancel | P3-R3 | done | cancellation |
| P3-S | SIG-P3-02 | BASE-03 | done | injection |
| P3-F | SIG-P3-03 | P3-S、P2-D | done | function-calling |
| P3-K1 | SIG-P3-04 文档语料 | BASE-01 | done | corpus-ingest |
| P3-K2 | SIG-P3-04 记忆批准与召回 | P3-K1 | done | memory |
| P3-K3 | SIG-P3-04 embedding 对照 | P3-K2、有效预算或本地模型 | done：本地 `sentence-transformers` 真实模型与 hashing 在 100 条、5 领域、`top_k=5` 同集对照；外部 provider 仍未配置 | retrieval-eval |
| P3-MCP | SIG-P3-01 | P3-S、P3-F、明确外部需求 | deferred | mcp |
| P3-I | SIG-P3-06 | 主线完成、明确选择 | deferred | impact |
| P4-A | SIG-P4-01 数据接口 | BASE-03、P2-D | done：已同步知识上传、Trace steps 和平台管理员评测比较契约 | ui-contract |
| P4-B | SIG-P4-01 Trace / DAG | P4-A | done：步骤时间线已接入；未宣称动态 DAG 图验收 | trace-dag |
| P4-C | SIG-P4-01 矩阵 / 验证 | P4-B | done | verification-ui |
| P4-D | SIG-P4-01 评测看板 | P4-A、P2-D | done（看板实现）：已实现平台管理员只读比较 API/DTO、结果目录加载、fixture/live 分离、split 展示和 live smoke 组计数；完整 dev/holdout 四组矩阵仍缺失，因此页面继续显示 `NOT_EVALUATED`，P2-E/P4-D 晋级证据保持 blocked | eval-ui |
| P4-E | SIG-P4-02 | P4-B/C/D | done（截图归档按用户明确要求豁免）：Docker + fixture 浏览器演示、审批/Trace/验证/交付链路已走通，截图仍只保留受控会话证据，不生成仓库图片文件 | demo-readme |
| P4-F | SIG-P4-03 | P4-E | done：15 张问答卡片已归档 | interview-cards |
| END-01 | 总计划 DoD | 必选任务完成或明确列缺项 | blocked：见交接证据中的外部条件 | final-handoff |

### 每个任务的固定算法

1. 读本节状态表、该任务全文和指定入口；只选第一个前置满足的任务。
2. 记录开始时 branch、HEAD、已有 diff、相关源码 fingerprint；运行依赖发现命令，确认入口存在。
3. 对照“当前行为→目标行为”列出不超过 5 个修改点。已有功能满足时保留，提供证据，不重新实现。
4. 修改单一任务的生产代码/契约；若任务过大，按步骤编号分两轮，状态继续 `in_progress`。
5. 复用或补该任务列出的必要测试，运行对应命令；失败先分类为代码、环境或外部依赖，不调低门禁。
6. 核对 API/Schema/Prompt/SQL/消费者是否成套，执行 `git diff --check`；涉及冻结契约再跑契约脚本。
7. 更新状态表并写第 12 节交接条目。只有验收产物存在、结果可复核，才标 `done`。
8. 有独立任务可继续时继续；否则报告确切阻塞。不得用重置库、重跑付费批次、删除测试绕过失败。

## 4. BASE：先使 P2 的测量对象可信

### BASE-01：复核工作区、当前版本和实施条件

**输入/入口**：AGENTS.md、总计划、两份执行手册、基线收敛记录；`git status`；迁移目录；当前 WorkflowSpec；`scripts/verify_workflow_contract.py`。

**步骤**：

1. 使用第 10 节初始化命令；记录迁移文件列表，确认 V1 新基线和历史运行编号没有被错误恢复。
2. 核对当前 Spec 的六节点、模型/价格/输出额度/修复次数、Backend loop、tool allowlist、verifier 等级。只报告 Key 已配置/缺失，不输出值。
3. 将代码已实现、离线测试通过、真实 L2、live、远端 CI 分开记录。以源码复核本手册第 1 节的差距；已修复项直接引用证据，跳过重复修改。
4. 核对服务地址和端口，使用实际 Compose 映射；不得直接套用曾经的 8080/18080、5173/25173。故障/容量实验必须使用隔离项目、数据库和 Redis 命名空间。
5. 建立本轮证据目录索引和任务状态，不把本地备份纳入公开证据。记录缺少的预算、外部服务、CI 权限，不因此阻塞离线实现。

**必要验证**：契约脚本、静态配置检查；不因写计划或检查状态跑全量测试。

**验收/停止**：形成可复现 baseline 记录。若新 V1 与当前代码不匹配，先定位工作区差异；不擅自回滚基线。当前缺口未确认前不跑付费评测。

### BASE-02：补齐真实输出链路的显式规格

**入口**：`agent-engine/schemas/backend_design.py`、`schemas/architecture_design.py`、`schemas/frontend_skeleton.py`、`schemas/spec_contract.py`、`spec_verifier/fixtures.py`、`spec_verifier/compiler.py`、`runtime/production_handlers.py`、`runtime/backend_agent_loop.py`；两端 prompts；Java `WorkflowHandlerCatalog.java` 与 `WorkflowNodeInputAssembler.java`。

**步骤**：

1. 列出 verifier 所需事实的来源表：主键、外键目标、受限类型、长度/精度、参数位置、响应字段、前端请求/响应字段绑定。逐项指出当前在哪个 Artifact 中缺失。
2. 为 Backend/Frontend 输出定义新版本，Architecture 共享契约同步引用必要字段。版本名先检索再分配；旧 Schema 和旧 Handler 保留用于历史解析。
3. 新版本必须明确表达上述字段。将新版本 Artifact→SpecContract 转换移到正常生产模块，例如拟新增 `spec_verifier/artifact_adapter.py`；`fixtures.py` 只保留样例构造。旧字段猜测只能留在有显式旧版本判别的适配器中，不能成为新版本失败后的自动降级。
4. 新 Prompt 直接展示准确层级和必填字段；注册新 Handler、同步 Java/Python Schema hash 与冻结候选。复用现有一次结构修复和 Backend loop，不再添加补字段的隐式兜底。
5. 更新确定性编译器和绑定校验，使至少一个字段缺陷来自独立测试输入，而非同一生成器同时生成“答案”和“校验标准”。
6. 改动暂只进入待冻结候选，不改当前 v12 或 V1 SQL。BASE-03 完成后由 P2-A 统一封装。

**最小测试**：一个完整显式规格成功；一个参数化用例覆盖“缺少 PK 声明、FK 目标不匹配、绑定不存在的响应字段”；一个旧 Artifact 仍能按旧 Schema 解析的兼容用例。复用 `test_schemas.py`、`test_spec_verifier.py`、`test_candidate_verification_loop.py`，不穷举字段类型排列。

**验收/回退**：新路径不调用 `_primary_key_name` / `_infer_foreign_key`，不按 HTTP 方法猜参数位置；错误有稳定 code/path。若必须改旧 hash 才能工作，停止并修复版本隔离；不要直接删除旧适配器。

### BASE-03：可信验证事实、交付来源与有效期

**入口**：`runtime/tool_gateway.py`、`schemas/verification.py`、`schemas/workflow_spec.py`；Java `ToolGatewayService.java`、`WorkflowToolCallFactMapper.java`、`WorkflowNodeInputAssembler.java`、`DeliveryGateService.java`、`GeneratedBundleVerificationService.java`；`review/evaluator.py`。

**步骤**：

1. 列出 fact 的权威存储字段和读取链路。Evaluation JSON 仅提供引用，不作为信任根；后端按引用查询已有工具调用事实，禁止新建第二套事实库。
2. 先选定此次实际交付的 Artifact 集合，再重算规范化来源 digest。核对项目、run、node/execution、fencing、source hashes、policy hash、编译器/验证器版本、report digest 与要求等级。
3. 从交付策略确定必须的 FULL 检查；BACKEND PASS 不能代替 FULL PASS，不能通过“让 fact.scope 决定选哪条策略”绕过汇合验证。历史运行按其冻结策略处理，不追溯要求旧运行具备不存在的新报告。
4. 在新验证策略中分离 `timeout_ms` 与拟新增 `evidence_ttl_ms`。首个候选固定 TTL 为 1 小时，作为待验证默认值，不在运行中临时加长；旧 fact 的时间戳不改写。
5. 过期时通过受控 verifier 对当前来源集合重新验证，写入新事实并保留旧引用；不得只刷新过期时间，也不得静默重跑上游模型。复用现有 Worker/工具通道；若缺入口，只添加权限受控的验证请求，不另造同步业务流水线。
6. Markdown/PDF/ZIP/代码包共用交付门禁。前端稍后展示“过期待复验”，不能把交付失败藏成下载错误。

**最小测试**：一个台账中存在的 FULL PASS 允许交付；一个参数化边界覆盖假 fact、其他项目/run 的 fact、仅 BACKEND、L1 冒充 L2、过期；一个 Artifact 修改后旧 fact 被拒绝的集成用例；用可控时钟验证过期复验，不真实 sleep 一小时。复用 DeliveryGate、ToolGateway 和 Evaluator 测试，勿为四种导出复制四套反例。

**验收/回退**：上述高风险边界必须通过；正式 fixture 经 Worker 完成一次含真实 L2 的六节点链路及受控复验。没有真实 L2 环境可完成离线部分，但任务保持 blocked；缺口未关闭不得晋级。回退为停止新候选使用，不放宽生产门禁。

## 5. P2：冻结实验、获得可复核证据

### P2-A：定义并冻结 A/B/C/D

**入口**：`evaluation/ablation.py`、`schemas/evaluation.py`、当前候选 JSON、`runtime/backend_agent_loop.py`、`runtime/production_handlers.py`、契约脚本；拟新增 `evaluation/configs/` 下的批次 manifest。

| 组 | Backend 决策循环 | 基础确定性字段/引用反馈 | 普通只读工具 | 循环中 spec.verify 反馈 | 最终独立判分 |
| --- | --- | --- | --- | --- | --- |
| A | 关闭，单次语义生成 | 不反馈后再语义重做 | 禁用 | 禁用 | 与 B/C/D 相同 |
| B | 开启、允许有界 Replan | 开启 | 禁用 | 禁用 | 同一判分器 |
| C | 与 B 相同 | 开启 | allowlist 启用 | 禁用 | 同一判分器 |
| D | 与 C 相同 | 开启 | 与 C 相同 | 启用 | 同一判分器 |

**步骤**：

1. 将 C/D 差异限定为 spec.verify 可见反馈；旧 `loop-tools-replan` 命名/配置仅在兼容读取处保留，不能沿用后称为新四组实验。
2. 四组固定相同模型、thinking、温度、输入、上游 Artifact、检索语料、Prompt 的共同部分、最大物理调用预算和最终判分版本；工具权限差异只能来自上表。语法/Schema 单次修复单独定义并对四组一致，A 不因此获得额外语义纠错循环。
3. 最终判分器总是执行统一的可执行检查。它看到的 verifier 结果不能提前泄露给 A/B/C；不能因 D 多跑一次 verifier 就给 D 换更宽松评分规则。
4. manifest 至少记录：批次 ID、代码指纹、base spec hash、四组明确文件路径与 hash、模型/provider/thinking、Prompt/Schema/Handler 版本、验证等级、数据集版本、价格快照、预算上限、判分阈值、执行顺序随机种子。
5. 用一份受控生成逻辑生成四份快照，比较差异是否只在允许字段。一次冻结一批，避免恢复“一次修错新增一整个实验版本”。新迁移编号实施时分配，V1 不改。
6. 如后端要求 PUBLISHED 才能显式运行，可发布为可选择的实验版本，但不得使 UI 或默认启动自动选它。当前前端按版本排序兜底选择，需同步处理显式默认标记/选择策略；“发布”和“设为默认”分开验收。

**最小测试**：一份参数化矩阵检查四组差异和相同判分；一个候选发布后默认选择仍为开工基线的边界。不为四个 JSON 复制四套测试。

**验收**：四组 freeze manifest 与契约检查通过；正式默认未变化。配置尚在变动时不进入 P2-E。

### P2-B：修复采集器、节点级模式和断点续做

**入口**：`evaluation/control_plane.py`、`evaluation/run_control_plane.py`、`evaluation/budget_ledger.py`、`evaluation/ablation.py`、`runtime/node_executor.py`、`schemas/evaluation.py`；`tests/test_control_plane_evaluation.py`。

**步骤**：

1. 去掉“contract_family→拼接旧文件名”的新实验依赖，按 manifest 中的显式路径/hash/version ID 校验。旧配置若保留读取则明确版本分支，不加多层猜文件名 fallback。
2. 修复选择组逻辑：只跑配置列出的组，不能只配 D 仍先运行 A；单组结果不得进入 A/D 晋级判断。缺组、缺重复或未完成均为 NOT_EVALUATED。
3. 分离节点级与整链路结果。节点级 runner 固定上游 Artifact，通过现有 NodeExecutor/Handler/模型与工具网关执行 Backend，不复制 Agent loop，不向业务 API 新增同步入口；正式整链路仍只走 `POST /api/workflow-runs`。两类结果不能混合计算时延/成本。
4. 定义 journal 状态：`RESERVED → PROJECT_CREATED → RUN_SUBMITTED → TERMINAL → COLLECTED`，保存稳定幂等键、run ID 与已预占额度；未知结果先查询原 run，不能换 experiment_id 绕过额度重建。
5. 对超时、网络失败、MANUAL_INTERVENTION、审批等待分别处理。采集器可以暂停等待用户，不能静默批准；fixture 专用审批若自动化，必须写入预先声明的测试规则与审计，不能声称真实人工批准。
6. 续做已完成项只读取事实；已提交未知项复查幂等键和 run；确认未提交才能继续原预占。保留 SQLite 授权台账，不能删 journal 或预算库来解除限制。涉及取消复用 P3-R4 目标语义，未支持的状态先安全停采集。
7. 支持实施时新增的 `--validate-only`（绝不建项目或调用模型）和 `--resume`（原批次续做），并在帮助中列出。只在实现且测试后使用这些参数。

**最小测试**：一个 D-only 不触发 A 的用例；一个参数化断点覆盖“已预占未提交、已提交未知、已采集”；一个额度耗尽后零新增调用的边界。复用已有 HTTP 假服务和 BudgetLedger 测试，不模拟每一种网络异常。

**验收**：fixture 通过公开 API 采集一条整链路；节点级结果有独立模式标记；重复启动不会重付费、重建 run 或重预占。live 暂不开启。

### P2-C：冻结数据、价格与预注册阈值

**入口**：`evaluation/experiment_dataset.py`、`evaluation/autospec_case_catalog.py`、`evaluation/rubric_review.py`、`evaluation/datasets/`、`schemas/evaluation.py`。

**步骤**：

1. 核对并冻结 8 smoke / 16 development / 8 holdout，覆盖至少 5 个业务领域；按源码实际统计领域，不能把 CRUD、APPROVAL 等题型直接当业务领域数量。
2. 每例写独立 MUST 事实、应有证据、允许/禁止工具、失败判定、上游输入 hash；只把需求和允许上下文送入生成侧，gold/判分答案不得进入 RAG 或 Prompt。
3. 人工复核 rubric；未审核样例明确标记且不用于 PROMOTE。holdout 不参与 Prompt 调参；同一题换措辞不能视为独立样本。实验项目隔离，防止前一组答案污染后一组语料。
4. 冻结三次重复和组顺序。正式 dev 为 `4×16×3=192` 个节点样本，holdout 为 `4×8×3=96`，完整 smoke 为 `4×8×3=96`；小批 smoke 可减少数量，但只作连通性检查。少量整链路另行列出 Case/次数/成本，不隐藏在节点预算里。
5. 按开始时官方费率核实模型与计价单位，记录来源、时间、币种、缓存价和适用时段；保守预占使用较高适用费率。旧文档 ¥20–40 或 ¥50 只是历史估算，不是本轮授权。
6. 最坏费用 = 各物理模型调用输入上限×输入价 + 输出上限×输出价；无缓存证据按未命中价，修复/重试/重规划全计入。verifier 计算资源单列；真实 embedding 调用另计。实际费用未知不得记 0。
7. 预注册沿用门槛：D gate pass 提高至少 8 个百分点，或 blocking issue 中位数下降至少 20%；gate pass 和 MUST 覆盖不得下降；参数有效率至少 95%；未授权执行为 0；最终无效 Schema 输出为 0。D/A P95、Token、费用比上限默认各 1.25。基线为 0 时必须预声明绝对上限，不能除以 0 后忽略限制。

**最小测试**：一个数据清单检查唯一 Case ID、分组/领域数量与 hash；一个 gold 不进入生成 payload 的边界。评测样本不是逐例单测，不增加 32 个几乎相同测试函数。

**验收**：数据集、manifest、费用上限和 rubric 审核状态冻结。缺授权时完成清单并标 P2-E blocked，不执行付费动作。

### P2-D：统一判分与统计

**入口**：`evaluation/metrics.py`、`evaluation/ablation.py`、`evaluation/rubric_review.py`、`schemas/evaluation.py`、`tests/test_autospec_evaluation.py`；统计实现可新增 `evaluation/statistics.py`。

**步骤**：

1. 先写每个指标的分子、分母和事实来源。工具请求被拒与实际越权执行分开；没有工具调用的参数有效率展示 N/A，不能用 100% 暗示工具能力好。
2. 超时/失败的已执行样本保留在分母，未执行/缺失项标明缺失并阻止晋级；延迟需注明是否包含排队和人工等待。Provider usage、计费估算与账单不得混称。
3. 用固定 verifier 和独立 rubric 判定质量；不能只读取模型自报分数。逐 Case 结果能追溯到 run/node/调用事实和源 Artifact。
4. 计算通过率 Wilson 95% 区间并注明其为调用级描述、同例重复存在相关性；D−A 配对差异按 Case ID 做固定随机种子的 cluster bootstrap，保持同例重复和组配对。质量差异判断使用配对统计，不能把同例三次调用当完全独立业务样本以夸大置信度。
5. 最终决策仅对完整 holdout 做一次：达到预注册门槛才 PROMOTE；质量/成本未达标为 REVISE；确认越权执行等安全硬失败为 REJECT；缺事实/组/重复为 NOT_EVALUATED。置信区间如实展示，不事后改变阈值换结论。
6. PROMOTE 是报告建议，不自动改默认。生成 JSON 事实和 Markdown 报告，保证统计可从逐例输入重新计算。

**最小测试**：一个手算小样本核对聚合/区间；一个参数化边界覆盖“缺组/缺事实、零基线无绝对上限、安全硬失败”；一个打乱输入顺序仍得到同一配对统计的用例。复用已有 release gate 测试。

**验收**：纯离线计算可重现；负面结果保留；前端和报告不得把 NOT_EVALUATED 显示为通过。

### P2-E：执行正式消融与少量整链路验收

**入口**：P2-A manifest、P2-C 数据集、修复后的现有 CLI、预算台账、正式控制面。

**步骤**：

1. 检查 BASE-02/03 和 P2-A/B/C/D 全部完成；审核实际部署模型/价率/版本 ID 与 manifest 相同。调用 `--validate-only`，若参数尚未实现回到 P2-B。
2. 先跑 fixture 与一个小额 smoke；检查物理调用、费用、审批和 verifier 事实齐全，再按原授权扩大到 dev。Token 接近额度不是失败原因结论，需看 finish_reason 和字段路径。
3. dev 可分析失败并修改候选；每次修改重冻结批次。预先固定最终候选后才打开 holdout，不能边看 holdout 边调 Prompt。
4. 预算不足时停止启动新样本、保留已提交 run 并采集其结果；不得通过降低重复数后仍宣称完整评测完成。
5. 完整 holdout 仅做一次晋级判断；少量正式六节点用例验证实际审批、Backend Replan、FULL L2、交付与成本链路，不把节点级成功代替整链路成功。
6. 保存执行命令、配置 hash、代码指纹、版本/环境、逐 Case 文件、失败原因、统计和决策。有残缺数据时报告 NOT_EVALUATED；没有成功 Replan 时如实写没有。

**必要验证**：不新增单测；这是实验验收。实际 live 与本地模型、fixture、离线回放分别标记。

**验收/停止**：结果满足完整性要求，即使结论 REVISE/REJECT 也可标“实验已完成”；不等于候选获准发布。缺授权/环境可停在 blocked，不能把写好的配置标作实验完成。

### P2-F：Trace、容量和故障证据

**入口**：`WorkflowTraceService.java`、`WorkflowRuntimeMetricsService.java`、`WorkflowTraceNodeResponse.java`、`runtime/agent_loop_trace.py`、已有集成测试与 `observability/`。

**步骤**：

1. 保存一条“缺陷 Candidate→verifier issue→Replan→修复通过”的 Trace 和一条预算耗尽/无进展/震荡终止 Trace。可用可控 provider 注入缺陷，但标签必须是 fixture，不能当 live 收益。
2. 在独立 Compose 项目使用真实 MySQL/Redis/Worker、可控模型替身分别跑 10 和 20 并发。冻结硬件、worker 数、输入规模、样本数量、队列/完成/拒绝计数；不对用户正在使用的库做故障注入。
3. 记录吞吐、队列时间、P50/P95、失败码、重复业务副作用、未释放预占和资源回收。短样本 P95 只描述该实验，不宣称生产 SLA。
4. 将 worker 中断、poison、发布进程中断、cancel 场景映射到 P3-R1–R4 的同一故障用例，不另造四套测试。当前失败先留证，修复后只重跑相关场景。
5. 发布脱敏证据索引，附环境与 commit/dirty fingerprint、bundle hash 和运行命令；明确未执行项。

**验收**：Trace 可按引用查到事实；容量实测与外部模型限流实验分开。故障未修复时这部分证据可以记录完成，但“可靠性验收通过”仍保持未完成。

## 6. P3：先可靠性，再独立能力扩展

### P3-R1：租约 watchdog 与唯一重派

**入口**：Java `workflow/runtime/WorkflowRecoveryService.java`、`WorkflowRecoveryJob.java`、`WorkflowRecoveryConfiguration.java`、`WorkflowNodeStatus.java`、`WorkflowFailureDecisionService.java`、`workflow/transport/WorkflowEventConsumer.java`；Python `runtime/worker.py`、`runtime/worker_runner.py`、`runtime/redis_stream_client.py`。

**步骤**：

1. 画出当前节点状态转换、心跳字段、Redis pending reclaim、数据库 fencing 与重试预算的职责。已有 watchdog 若存在，补缺而非增加第二个调度器。
2. 在现有 RecoveryJob 的有界扫描中处理 RUNNING 租约过期，区分从未启动的 QUEUED、等待审批、正常长任务和失联任务；审批等待不属于失联。
3. 以状态、execution ID、lock_version、心跳时间做 CAS，将过期执行记为 ORPHANED，记录事件；同一事务生成唯一 replacement/Outbox。重派仍受冻结 retry_policy、deadline 和预算限制，不能无限增加 attempt。
4. 旧执行到达时按 execution/fencing/status 拒绝结果和业务写入。新执行遵循已有 fencing 生成语义，不手动复用旧 token。已发生的模型费用仍结算一次，不能因结果被拒就当调用免费。
5. 与 cancel/人工审批互斥：CAS 发现父 run 已取消/完成则不重派。恢复任务多实例同时扫描只能产生一个有效 replacement。

**最小测试**：一个过期租约与晚到结果场景；一个双 watchdog 竞争只重派一次；审批等待不重派可作为同一参数化边界。复用 `WorkflowRecoveryServiceTest`、`WorkflowEventConsumerTest`，数据库竞争使用现有 MySQL 集成支持。

**验收/回退**：真实 worker 中断后在配置窗口内恢复，记录检测/恢复耗时而非强套旧 5 秒阈值。关闭新 watchdog 可停止新扫描，已发出的 replacement 不能删除；保留 fencing 和幂等检查。

### P3-R2：poison 事件投递上限与 DLQ

**入口**：`workflow/transport/WorkflowEventPoller.java`、`WorkflowEventStreamClient.java`、`WorkflowEventDeadLetterSink.java`、相关 Redis 实现和 `WorkflowDeadLetterService.java`。

**步骤**：

1. 区分格式无效、业务不可处理、暂时性数据库/网络异常。无效格式延续现有隔离；普通异常不可永远无限 reclaim。
2. 使用可跨实例/重启保留的投递次数，按 stream + message ID 识别，不能用进程内计数。上限和重试间隔写入配置，首版沿用已有重试默认值，确需区别时说明原因。
3. 到上限先幂等持久化 DLQ，再 ACK 原事件；DLQ 入库失败不 ACK，避免丢消息。不要仅依赖可能在 XAUTOCLAIM 时变化的消费者身份。
4. 一条 poison 不应长期饿死同批正常事件；重放继续走原事件幂等/授权路径，保留原 ID 与重放审计，不伪造为全新成功事件。

**最小测试**：一个“poison 达上限且正常事件继续消费”；一个“DLQ 写失败不 ACK，恢复后仅有一条 DLQ”。复用 `WorkflowEventPollerTest` 和 Redis 集成测试，不穷举异常类。

**验收/回退**：计数重启不归零，DLQ 与 ACK 顺序可证。重试上限不可通过清 Redis pending 来实现。

### P3-R3：Outbox 多发布者占用

**入口**：`workflow/transport/WorkflowOutboxPublisher.java`、`WorkflowOutboxMapper.java`、`entity/WorkflowOutbox.java`、`OutboxRetryPolicy.java`，迁移目录。

**步骤**：

1. 核对现有 PENDING→发布→状态更新间隙。只在确有必要时新增 `claim_owner / claim_until / claim_version` 等占用字段，用一个新增迁移描述同一功能；实施时分配版本，不能改 V1。
2. 在短事务内批量领取待发布行；采用 MySQL 行锁/跳过已锁行或严格 CAS，占用租约持久化。网络发布不持有数据库事务长锁。
3. 发布后只有持有当前 claim 的进程可以写确认；进程死亡后租约到期可接管。数据库确认失败仍可能重复发布，继续使用稳定 event ID 和下游幂等，不能声称跨 MySQL/Redis exactly-once。
4. 失败分类、退避、最大重试和 DEAD_LETTER 复用现有逻辑；不要添加另一张平行队列表或重复发布器。

**最小测试**：一个真实 MySQL 双发布者竞争；一个“Redis 已接收、数据库确认前崩溃”重试不产生重复 Artifact 的场景。复用 `MySqlOutboxIT`、`RedisOutboxRecoveryIT`，H2 通过不能证明行锁行为。

**验收/回退**：占用到期可恢复、旧 owner 确认被拒，事件至少一次但业务投影一次。回退代码前确认新增列兼容；不删除运行中的 outbox 行。

### P3-R4：取消传递与已计费调用收尾

**入口**：`service/impl/WorkflowRunServiceImpl.java`、`controller/WorkflowController.java`、现有 Outbox/命令 Schema、`runtime/worker.py`、`runtime/node_executor.py`、`runtime/backend_agent_loop.py`、模型/工具网关。

**步骤**：

1. 定义可取消状态，包括 RUNNING、审批等待和 MANUAL_INTERVENTION 的实际表示；已终结重复取消为稳定结果，不能把已完成 run 改成取消。沿用项目权限校验。
2. 一个事务写取消状态、节点终结/预算释放、审批关闭及控制命令；不要让用户依赖手工 SQL 取消待人工介入任务。
3. 取消消息按 execution 定向到实际 worker，不能假设共享消费组广播到所有执行者。复用已知 worker/执行登记；未知接收者必须在心跳或受控状态检查时发现取消。Worker 不直连业务库。
4. 在每次模型/工具调用前、长调用返回后、重规划前检查取消；支持的 HTTP/子进程尽力中止，不能承诺撤销已经被 provider 接收的调用或费用。
5. 晚到结果不得改变 Artifact 或恢复 run。已用费用照常入账；释放仅未用预占，幂等处理取消重复投递和结算重复投递。

**最小测试**：一个参数化“调用前/调用中取消”；一个“重复取消＋晚到成功”不写业务产物、不重复退预算；一个待人工介入状态通过正式 API 取消。复用 `WorkflowRunServiceTest`、`test_worker_runner.py`、`test_agent_loop.py`。

**验收/回退**：真实 Worker 取消演练有响应时延、最后调用与费用记录；不能仅以 UI 按钮变灰验收。通知失败时 run 仍保持取消，由控制面 fencing 阻止晚写；不要恢复成 RUNNING。

### P3-S：注入边界与 30 条红队数据

**入口**：`runtime/context_policy.py`、`runtime/tool_gateway.py`、`runtime/tool_harness.py`、`runtime/production_handlers.py`、`model_gateway.py`、`schemas/citation.py`；拟新增 `evaluation/datasets/prompt_injection.json`。

**步骤**：

1. 冻结 30 条样本，检索片段、工具结果、Artifact 文本各 10 条，覆盖指令覆盖、越权工具、跨项目数据请求、伪造验证成功、读取密钥、绕过额度等风险；样本只用假密钥和脱敏数据。
2. 不可信内容放结构化数据区并保留来源；权限、模型路由、工具地址、预算、verification fact 由可信控制面提供，不能从正文解析后覆盖。
3. 不只检查模型是否“说了服从”：断言攻击未改变 allowlist、项目范围、实际执行工具、预算和事实台账。模型可能输出错误请求，但网关必须阻止执行并记录拒绝。
4. 先用可控 provider 跑参数化回归；live 测试单列预算与模型快照，30 条零成功只说明该集合，不宣称普遍免疫。自然文本回答偏离和越权执行分开计数。
5. 为工具结果大小、控制字符/HTML 等沿用已有限制；不要新建与网关并行的权限判断体系。

**最小测试**：一个参数化数据集 runner＋一个正常引用/工具结果对照。30 条是数据行，不是 30 个复制的测试函数；控制面越权反例复用现有 ToolGateway 测试。

**验收**：测试集合中实际越权/跨项目/伪造事实接受次数为 0；攻击请求本身可以非零。任何越权执行都阻断后续扩展发布。

### P3-F：原生 function calling 与旧协议对照

**入口**：`model_gateway.py`、`schemas/agent_loop.py`、`runtime/backend_agent_loop.py`、`runtime/tool_gateway.py`、`runtime/model_telemetry.py`、provider capability 配置。

**步骤**：

1. 实施时查目标 provider 官方工具调用规范，冻结支持的模型和协议能力；不要因为 OpenAI-compatible 就假设原生 tools 全部兼容。
2. 增加明确协议选择字段，把原生 tool_calls 解析为现有受限 ToolCall/AgentTurn。仍由既有 Gateway 执行，模型响应不直接触发业务调用。
3. 工具 schema 只来自 registry 与 allowlist；校验名称、参数、调用 ID。首版顺序执行即可，不附带并行工具调度改造。
4. JSON-in-prompt 与原生调用必须是显式选择的两个模式。冻结为 native 的实验遇到不支持直接记录失败，不悄悄回退 JSON 后算 native 成功；普通产品如要 fallback 需另有显式策略并计费记录。
5. 在同一组工具意图/参数 gold 上配对对照选择准确率、参数有效率、调用次数、Token/费用和失败类型。no-tool、拒绝、未知工具、异常参数都有明确统计口径。

**最小测试**：一个正常调用；一个参数化“未知工具/无效参数/重复调用 ID”；一个 native 不支持时不隐式跨协议重试。复用 `test_model_gateway.py`、`test_agent_loop.py` 与 ToolGateway 幂等测试。

**验收**：两种协议走同一审计/预算/权限链路；报告逐例差异。没有 live 对照时可标适配实现完成，但实测收益仍为未执行。

### P3-K1：用户领域文档入库

**入口**：`controller/KnowledgeController.java`、`KnowledgeIndexService.java`、`KnowledgeDocumentService.java`、`KnowledgeChunkService.java`、`KnowledgeCorpusEpochService.java`、`ProjectAccessService.java`、OpenAPI 与 `frontend/src/api/`。

**步骤**：

1. 先核对现有上传/文档生命周期接口；当前 sources GET 不是上传能力证明。复用已有 document/chunk/index 表，不另建向量数据库。
2. 首版支持 UTF-8 TXT/Markdown，建议上限 1 MiB/文件；在接口和配置中写明。PDF/OCR、压缩包、自动抓网址不属于首版，不为了扩展格式引入复杂解析链。
3. 如无现有入口，新增项目范围内的上传接口，地址先写入 OpenAPI 再同步消费者。上传必须 OWNER/EDITOR；来源、content hash、版本、上传者、corpus 与审批状态进入现有元数据。
4. 重复内容幂等；失败索引保留可诊断状态；替换/撤回使旧版本失效并推进 corpus epoch，避免旧缓存继续召回。文件原文按不可信内容处理。
5. 检索前与结果返回前都保留项目/ACL过滤，不能只在 UI 筛选。向真实 embedding 服务发送用户文档前核对数据外传范围与授权。

**最小测试**：一个上传→索引→可召回成功链路；一个参数化“跨项目、超限/不支持格式”；一个重复上传或撤回后旧缓存不返回。复用 Knowledge 生命周期测试。

**验收**：同一份真实或脱敏领域文档可在本项目按权限引用，其他项目不可见。暂不宣称语义召回比 hashing 更好。

### P3-K2：批准状态与相关性记忆召回

**入口**：`service/ProjectMemoryService.java`、`WorkflowNodeInputAssembler.java`、`ProjectMemoryFact.java`、`runtime/context_policy.py`、现有审批处理器和 corpus epoch。

**步骤**：

1. 区分“本次运行可用的生成上下文”和“跨运行可信记忆”。只有已批准、未撤回且来源可验证的事实进入后者；未批准产物仍可存为候选，但不因 ACTIVE 标志就当已批准。
2. 在来源审批/撤回/替代事件中同步现有事实有效期/状态和缓存失效。不要为了增加记忆命中率，把所有生成 Artifact 自动改为 APPROVED。
3. 复用 fact_key/version/source_ref/superseded 机制，避免重新造记忆版本表。旧事实来源状态不明时排除可信召回，并提供可审核的回填策略。
4. 先实现可解释的相关性排序：节点目标/当前需求→限定 fact_type 与 project→词项或现有向量评分→来源/有效性过滤→稳定排序→token 上限；新评分版本入快照，不引入无限摘要层。
5. 保留引用来源并与最新批准事实一致；同分稳定排序，不能因数据库返回顺序变化导致上下文随机漂移。

**最小测试**：一个新批准版本替代旧版本；一个参数化“未批准/已撤回/其他项目”不入可信上下文；一个小查询验证相关事实优先且不超预算。复用 `ProjectMemoryServiceTest`、`test_context_policy.py`。

**验收**：人工修改/撤回后召回立即反映新状态；现有版本与来源链可追溯。无新评测不能写“记忆提升准确率”。

### P3-K3：真实 embedding 与 hashing 的同集对照

**入口**：`runtime/embedding_provider.py`、`runtime/hybrid_rag.py`、Java `KnowledgeEmbeddingService.java`、`evaluation/retrieval.py`、`evaluation/cli_retrieval_gold.py`、`evaluation/cli_retrieval_compare.py`、`evaluation/datasets/`。

**步骤**：

1. 建立至少 100 条经过复核的 query→gold document 样本，覆盖至少 5 个领域，并含 forbidden/expired 文档。已有三条 gold 只保留为快速回归，不放大解释。
2. 冻结语料、chunker、查询改写、reranker、top_k=5、ACL 与评分代码，实验只改变 embedding provider。避免 provider 切换后实际仍命中旧向量缓存。
3. 向量缓存键包含模型/维度/内容 hash/版本；区分冷启动索引成本和在线查询成本，维度不匹配明确拒绝或重建，不截断向量凑长度。
4. hashing 与真实 embedding 同集逐例输出 Recall@5、MRR、nDCG、跨项目/过期泄漏、耗时和费用。若服务不可用，记录未执行，不能拿另一个伪向量模型替代后仍叫真实 embedding。
5. 报告宏平均与失败案例；100 条是评测样本量，不是新增 100 个单测。没有改善也保留报告，不改 gold 或筛掉失败查询。

**最小测试**：一个参数化检索器同集 harness；一个 ACL/过期反例；一个模型版本/维度变化不复用旧缓存的边界。复用检索策略与 embedding 现有测试。

**验收**：两组逐例可比较，跨项目召回为 0；写清真实服务还是本地真实模型。预算不足只阻塞外部对照，数据和 harness 可先完成。

### P3-MCP：条件性外部工具接入

**启动条件**：记录一个真实外部只读工具需求、数据范围、服务所有者、认证方式与可用环境；没有这些就保持 deferred，不为面试术语安装服务。

**入口**：`runtime/tool_gateway.py`、`runtime/tool_harness.py`、`schemas/tool_gateway.py`、现有 ToolGateway Java 服务；拟新增单一 MCP adapter 模块。SDK/协议版本实施时查官方文档并锁定。

**步骤**：

1. 划清 server/client 两条方向：对外暴露的只读工具和 Backend 调用的外部只读工具分别列 allowlist，不能互相递归调用形成环。
2. 固定 endpoint/认证/资源范围，不能接受模型提供的任意 MCP 地址；工具 Schema 与权限由可信配置加载。先接一个只读工具，不开 WRITE 或任意命令执行。
3. MCP 调用通过同一个 Gateway 做参数、ACL、deadline、大小、预算、幂等与审计检查。外部返回的身份/权限/fact 均不可信。
4. 连接失败、工具 schema 漂移、超时均明确失败；不能从 MCP 自动退到更宽权限的本地工具。外部服务不支持幂等时限制为只读并记录重试语义。

**最小测试**：一个真实只读调用及台账；一个未授权工具拒绝；一个超时/超大返回的参数化边界。协议 SDK 自身不重复单测。

**验收**：原生工具和 MCP 工具进入同一治理台账。禁用 adapter 即可回退，不需要更改业务 DAG；不附带 A2A、技能市场或插件系统。

### P3-I：可选变更影响分析与增量重生成

**启动条件**：主线完成且用户选择该扩展。只有一个需求变更场景作为首版，暂不做任意文本语义 diff。

**入口**：`ArtifactTraceGraphService.java`、`ArtifactTraceEdgeMapper.java`、`ReworkPlanner.java`、`ReworkPlanExecutionService.java`、`WorkflowReplayService.java`、前端现有 diff/rework 入口。

**步骤**：

1. 输入明确 REQ ID、旧/新内容 hash、基线 Artifact 集合；授权和乐观锁先检查，变更陈旧时冲突退出。
2. 用现有追踪图求受影响 REQ→STORY/AC→API/TABLE→PAGE 闭包；缺追踪边时显式扩大重跑范围，不能猜测为“不受影响”。记录扩大原因。
3. 复用现有 ReworkPlanner，在冻结 DAG 上计算节点集合和审批要求。只改变必要组件，未受影响组件作独立 hash 校验；发现越界改动拒绝合并，而非静默接受。
4. 旧验证事实按来源失效。切片验证可以减少中间成本，但最终交付仍需绑定完整最终集合的 FULL 证据，不能拼接两个不相容的局部 PASS。
5. 同一变更、同一模型预算对照全量与增量，报告实际重跑节点、Token、费用与未变化组件哈希。无付费额度先用 fixture 验证行为，不声称成本收益。

**最小测试**：一个只影响 Backend/Frontend 的变更；一个缺边扩大范围；一个未受影响组件被改动或旧 fact 复用被拒的参数化边界。

**验收/回退**：增量与全量结果受同一门禁，未受影响组件 hash 不变；回退为显式全量重跑选项，不在无授权时自动花费更多额度。

## 7. P4：用已有事实展示，不另建生成产品

### P4-A：统一前端数据契约和只读证据接口

**入口**：`frontend/src/api/workflow.ts`、`hooks/useProjectDetailData.ts`、`pages/ProjectDetailPage.tsx`；后端 `WorkflowTraceNodeResponse.java`、`WorkflowTraceStepResponse.java`、`WorkflowTraceService.java`、`ArtifactTraceGraphController.java`、OpenAPI。

**步骤**：

1. 为 `nodes[].steps[]` 补 TypeScript DTO，字段与后端一一对应；老运行无 steps 显示“未记录”，不造虚拟步骤。
2. 列出展示数据到实际来源的映射：节点/DAG→冻结运行快照；调用/步骤→Trace；REQ 矩阵→Evaluation/trace graph；验证→可信 fact/report；消融→P2 冻结证据。
3. 如果报告明细/评测结果没有现有只读接口，再定义最小后端 DTO 与授权接口。不得假设后端已经有不存在的评测 dashboard API；不得让浏览器任意读取本地证据路径或服务器文件。
4. 实现运行/项目范围检查、分页或有界条数、脱敏；API 不返回 Key、完整内部 Prompt、无关项目数据或未经处理的 HTML。
5. 统一 `NOT_RUN / UNAVAILABLE / FAILED / PASSED / EXPIRED` 等展示状态与可用单位；未知费用用空值/未知，不用 0。

**最小测试**：一个真实结构 fixture 的 DTO/适配；一个跨项目证据访问拒绝；老 Trace 无 steps 的渲染并入同一组件测试。复用 `WorkflowRuntimeControllerTest` 和前端 API 测试。

**验收**：前后端契约一致，后续组件不再直接解析多种不稳定 JSON。先实现这些数据，再开始图表。

### P4-B：步骤时间线与冻结 DAG

**入口**：现有 `WorkflowReplayPanel.tsx`、`ProjectDetailPage.tsx`、数据 hook；可拆出拟新增 `WorkflowStepTimeline.tsx`、`WorkflowDagView.tsx`。

**步骤**：

1. 时间线按 run→node revision/attempt→step 排序，展示 Plan/Act/Observe/Validate/Replan、耗时、错误码和事实引用；不同 attempt 的 step=1 不合并。
2. DAG 从该运行冻结的 nodes/edges 生成，显示 Backend∥Frontend、Reviewer 汇合和 REWORK 边；不硬编码六个节点的绘图连线。
3. 轮询复用现有请求生命周期；切 run 时取消旧请求或丢弃旧响应，终态停止无意义轮询，避免旧 run 响应覆盖新页面。
4. 图提供文本/列表等价视图、图例和键盘可达节点；非必要不引入大型图编辑库，不做拖拽修改冻结 DAG。

**最小测试**：一个含并行与重试的状态映射；一个切 run 后旧响应被丢弃；一个无 steps 的空态。其余交互通过一次人工浏览验收，不逐颜色/图标测试。

**验收**：点击失败步骤能打开具体 issue/调用事实；截图来自实际 run，不能用演示动画冒充执行过程。

### P4-C：追踪矩阵与验证报告

**入口**：`ArtifactTabs.tsx`、前述数据 hook、Artifact trace graph 与 Evaluation 数据；拟新增 `RequirementTraceMatrix.tsx`、`VerificationReportPanel.tsx`。

**步骤**：

1. 行为 REQ，列为 STORY/AC、API、TABLE、PAGE、验证证据；保持稳定 ID，点击到具体 Artifact 版本和 JSON path。
2. “已覆盖”和“已验证”分列显示。L1/L2、BACKEND/FULL、源版本、验证时间/过期时间明确可见；不能把一份总 PASS 涂绿全部 MUST。
3. 验证面板展示稳定 issue code、路径、事实引用、来源 digest 与等级；前端不自行判定门禁，通过后端 readiness/可信摘要显示允许交付。
4. 过期/来源变化显示原因及受控复验入口；没有该权限时只读展示，不把“下载失败”当成唯一反馈。
5. Artifact 文本默认转义。色块附文字，表格在小屏可横向滚动，不为热力图再造一套来源计算。

**最小测试**：一个 MUST 未覆盖/未验证被明确标示；一个 BACKEND 或过期 fact 不展示 FULL 可交付；一个链接保留 run/artifact version。复用同一数据 fixture。

**验收**：从一次运行能下钻到某个 MUST 的 API、表、页面与真实验证证据；未验证、失败和未知不能同色混淆为成功。

### P4-D：只读消融看板

**入口**：P4-A 的评测结果 DTO/接口、`evaluation/metrics.py` 输出；拟新增小型 `EvaluationComparisonPanel.tsx`，优先接入现有工作台，不另建大后台。

**步骤**：

1. 按批次/数据集/split/执行模式选择完整结果，展示四组样本量、通过率/区间、blocking、MUST、Token/费用/时延、安全失败和最终决策。
2. 节点级与整链路、fixture 与 live 分开选择，禁止一张图混算。缺组/样本不齐时展示 NOT_EVALUATED 与缺项。
3. 下钻逐 Case 的四组差异，链接 Trace 和配置 hash；列出模型、thinking、verifier、价格日期和统计版本。
4. 页面不重算与后端不同的晋级规则，不提供无授权的一键批量付费重跑按钮。图表只展示已有证据。

**最小测试**：一个完整对照正确渲染；一个缺组/未知费用不显示 0 或 PROMOTE。无需给每个图表像素写快照。

**验收**：负面结论与缺数据也可正常展示；没有 live 数据时显示未执行，不放演示百分比。

### P4-E：README、五分钟 Demo 与真实截图

**入口**：`README.md`、现有 `docs/archive/` 索引与图片目录、Compose 启动配置、P2/P3/P4 证据。

**步骤**：

1. README 明确产品能力、六节点正式入口、数据库 V1、新用户启动条件、取舍与局限；删除过时默认版本说明，不重命名历史技术 ID。
2. 架构图只画实际部署组件和信任边界；截图/GIF 使用本人实际运行结果，保存到 `docs/archive/` 的图片目录，确认无密钥和真实个人信息。
3. 分开两个 Demo：预备好环境后的五分钟 fixture/已有 Trace 演示；获授权的 live 生成演示。首次下载镜像/依赖耗时单列，不承诺空机器五分钟冷启动完成。
4. 五分钟脚本固定“登录→输入需求→审批→观察 Trace/验证→查看交付”；标明 fixture 不调用模型，示范一个失败/过期门禁，说明如何复验。
5. 演示账号仅由本地 development 配置提供，README 说明如何安全设置，不提交实际密码；不能为了演示关闭后端权限或门禁。
6. 证据表注明执行时间、模式、commit/工作区指纹、环境、失败/未执行项。Spec Sandbox 不等于生成的完整业务应用已通过验收。

**必要验证**：一次按文档从预备环境走通完整 Demo、链接检查、截图脱敏与视觉核对。不新增 README 单元测试，不为录屏再跑付费矩阵。2026-10-01 已按 Docker fixture 走通登录→输入需求→审批→Trace/验证；截图接口只返回受控会话图片字节，未生成仓库图片文件。用户已明确豁免截图归档，因此该项不再作为 P4-E 阻断条件。

**验收**：他人按步骤能复现已声明的演示；缺外部服务时有准确停止提示，而不是偷偷切模式后仍宣称 live。

### P4-F：15 张可核验问答卡片

**输出**：拟新增 `docs/archive/interview-cards.md`，只写基于当前仓库能举证的内容。

**固定主题**：①控制面/执行面；②动态 DAG/并行；③Backend 循环与终止；④结构化输出与修复；⑤函数调用；⑥工具治理/MCP 取舍；⑦RAG/embedding；⑧记忆批准与版本；⑨注入与权限；⑩MySQL/Redis 幂等；⑪租约/fencing/取消；⑫人工审批与定向返工；⑬Spec Sandbox 与交付；⑭评测/消融/统计；⑮成本、可观测与失败复盘。

**步骤**：

1. 每张卡统一包含“一句话答案→实际调用链→代码链接与当前行号→脱敏证据→一个失败场景→取舍和能力边界”。
2. 对照源码重新定位行号，路径用相对链接，行号作为文字或 Git 托管锚点；不能复制旧文件行号。引用证据同时记版本/模式。
3. MCP 未做就写“没有具体集成需求，因此未引入；现有治理边界是……”，不能写已接入。A2A/Skills 无对应实现时同样如实说明。
4. 不编造个人贡献比例、吞吐、准确率提升或 SLA。负面实验、未执行 CI、尚存兼容适配都可以成为取舍内容。

**必要验证**：15 张卡片主题齐全；全部本地文件链接存在；抽查关键代码/证据引用与表述一致。不运行模型自评打分，也不新增业务测试。

**验收**：每张卡都能沿引用复核；没有证据的数字删除或标待测，不留看似正式的占位百分比。

## 8. END-01：里程碑回归与最终交接

**前置**：必选任务逐项有结果；暂缺付费预算/外部条件时允许先做阶段交接，但不得声称完整 P2–P4 完成。

**步骤**：

1. 对照第 3 节逐项核验状态与产物。实现完成、fixture 通过、live 通过、质量晋级、远端 CI 通过分别记录，不互相代替。
2. 执行一次第 10.4 节完整三端回归、契约验证、三种 Compose 静态检查；只运行本轮改变的高风险基础设施 IT，不因测试数量减少而补回重复测试。
3. 数据库有新迁移时，在隔离测试库验证“空库 V1→最新”和“本轮起点版本→最新”。比较保留的运行/Artifact 引用和冻结 hash；使用脱敏快照，不对业务库做回滚演练。禁止修改 V1 让测试通过。
4. 正式 fixture 六节点运行一次，检查审批、至少一次受控返工、最终交付门禁及 Markdown/PDF/ZIP。复用 P4-E 演示的同一次运行；高风险拒绝场景复用 BASE-03 测试，不重复生成三套数据。
5. live 证据复用 P2-E 已授权批次；若 P3-F/P3-K 改变拟交付候选的实际执行配置，只补受影响的对照/最小 smoke，并重新核对预算。不把旧批次的 PROMOTE 直接贴到新配置。
6. 读取 `.github/workflows/quality.yml`，区分本地单元回归与 CI 的集成任务。远端 CI 未执行或失败时如实记载；只有获得发布授权后才 push/触发远端操作。旧 CI 失败不能靠本地通过“注销”。
7. 更新总计划对应条目、本文状态、README 中已实现能力及一份最终脱敏证据。拟用 `docs/archive/evidence/p2-p4-<实际日期>.md`；日期取实际执行日，不预填成功。
8. 检查无密钥、真实用户文档、模型完整敏感输入、本机绝对路径、原始数据库备份进入提交候选。只在得到明确提交授权时按任务分组提交，避免混入本轮起点已有修改。

**完成定义**：必选任务实现与验收证据齐全，必要边界无未解释失败；实验可以得出 REJECT，但不能缺样本却自称完成质量验证。可选任务保留 deferred 并写理由。若远端 CI、live 或某个必选任务缺结果，交付标题写“阶段交接”，顶层状态不改为 done。

**停止条件**：出现跨项目数据访问、预算超限、重复有效调度、来源不匹配却允许交付，立即停止相关候选/实验并保留证据；不得以“其他测试通过”抵消。

## 9. 最小测试策略：按风险保留，不按时间删除

“只保留最近几次”适用于可清理的运行记录/本地测试产物，不适用于回归测试源码。删除测试的依据必须是功能已删除，或当前行为已有等价覆盖；不是它写得早、曾经通过或文件行数多。

### 9.1 必须保留的边界

| 风险 | 一份核心覆盖 | 主要位置 | 不重复做什么 |
| --- | --- | --- | --- |
| 显式规格与版本 | 新规格成功、关键字段拒绝、旧版仍解析 | Schema / verifier 测试 | 不穷举所有 SQL 类型和业务行业 |
| 交付事实可信性 | 台账存在、同项目/run、FULL/L2、来源一致、未过期 | DeliveryGate + 一个来源变化集成用例 | 不给每种导出重复整套反例 |
| 模型与预算 | 截断不盲重试、一次字段修复、耗尽停止、未知费用不为零 | model gateway / structured output / 台账现有测试 | 不断言 LLM 文案或每个同义措辞 |
| 冻结与实验公平 | hash 不变、组间差异合法、默认版本不被候选发布影响 | executable contract / manifest | 不复制四份相同完整工作流测试 |
| 采集器恢复 | 预占后中断、提交后中断、完成后漏采，不重复付费 | control plane evaluation | 一个参数化状态机用例，不为每个 split 重写 |
| 统计与晋级 | 配对结构、缺样本、零分母/未知成本、阈值相等 | evaluation metrics | 不用大量随机快照碰运气 |
| 租约/发布/重复事件 | 竞争只有一个有效重派、旧 fence 不生效、ACK/DLQ 顺序 | runtime 单测 + 必要 MySQL/Redis IT | 不用 H2 模拟行锁后声称验证 MySQL 并发 |
| 取消 | 重复取消、迟到结果不恢复业务、已花费用仍入账 | run service + worker | 不重复测试框架 HTTP 反序列化 |
| 注入/工具权限 | 三类来源、参数与目标受限、禁止越权 | tool gateway + 30 条数据 | 30 条放数据集，用一个参数化入口 |
| 语料/记忆 | 项目隔离、未批准不进长期记忆、旧版本失效、重复导入幂等 | knowledge / project memory | 不为每个文件扩展名写全链路 |
| 前端诚实展示 | 缺数据不显示成功、过期证据不显示可交付、Trace 不串 run | 现有 hook/API + 小型组件测试 | 不做整页大快照或颜色/像素断言 |

已有测试满足某一行就复用并记录其名称。30 条注入样本、100 条检索 gold 和消融数据是实验输入，不是要求新建对应数量的测试文件。不要为了减数量把独立的高风险断言删掉；可以用参数化合并重复布置。

### 9.2 分层运行频率

- **单任务**：只跑涉及的测试类/文件；文档任务只查链接、内容与证据。某一个模块构建不是三端回归。
- **跨模块契约/迁移**：运行相关消费者测试、契约脚本；迁移增加隔离库集成验证。按照仓库要求在这类变更收口时执行完整三端回归，尽量将同批契约变更合并到一次收口。
- **里程碑**：BASE 可信链路、P2 冻结、P3 收口、最终交接，复用最近一次同指纹结果；仅文档变化不重复计算无关测试。
- **外部付费**：只在明确阶段、冻结批次和授权预算内运行。单元测试默认替身 provider，不能读取 `.env` 后顺便调用 live。

## 10. 执行命令手册（PowerShell）

以下命令是实施阶段模板，不应因阅读本文而自动运行。先完成 10.1；后续代码块共用其变量和函数。任何未实现的新 CLI 参数必须先完成对应任务并检查 `--help`，不能靠临时改命令绕过校验。

### 10.1 初始化、只读核验

从仓库内打开 PowerShell。工具路径从 AGENTS.md 固定配置读取；不要猜新的 Python 环境，也不要把本机路径写进新源码。

```powershell
$ErrorActionPreference = 'Stop'
$repoRoot = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw '当前目录不是 Git 仓库' }
Set-Location -LiteralPath $repoRoot
$guideText = Get-Content -LiteralPath (Join-Path $repoRoot 'AGENTS.md') -Raw
$mavenMatch = [regex]::Match($guideText, '(?m)^- Maven：`([^`]+)`')
$pythonMatch = [regex]::Match($guideText, '(?m)^- Agent Python：`([^`]+)`')
if (-not $mavenMatch.Success -or -not $pythonMatch.Success) {
    throw 'AGENTS.md 工具路径结构已变，请读取该节后更新解析，不猜路径'
}
$mavenExe = $mavenMatch.Groups[1].Value
$agentPython = $pythonMatch.Groups[1].Value
foreach ($toolPath in @($mavenExe, $agentPython)) {
    if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) {
        throw '仓库指定的工具不存在，停止该模块执行并记录环境缺口'
    }
}
function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败：$Executable，退出码 $LASTEXITCODE" }
}
Invoke-Checked -Executable git -Arguments @('branch', '--show-current')
Invoke-Checked -Executable git -Arguments @('rev-parse', 'HEAD')
Invoke-Checked -Executable git -Arguments @('status', '--short')
Get-ChildItem -LiteralPath 'backend/src/main/resources/db/migration' -Filter 'V*.sql' |
    Select-Object -ExpandProperty Name
Invoke-Checked -Executable $agentPython -Arguments @('scripts/verify_workflow_contract.py')
Invoke-Checked -Executable docker -Arguments @('compose', 'config', '--quiet')
Invoke-Checked -Executable docker -Arguments @('compose', 'ps')
```

这里 `config --quiet` 不展开输出含密钥的完整 Compose 配置。端口从 `compose ps` 及配置源码核对，不打印 `.env`。缺 Docker 不影响文档/纯单元任务，影响真实 L2/IT 时必须标明阻塞。

工作区指纹至少包含：HEAD、已跟踪改动文件的内容 SHA-256、任务相关未跟踪文件 SHA-256、冻结 manifest/dataset/hash。只散列本任务的源码/契约/证据；排除 `.env`、备份、依赖与构建目录。不把包含秘密的 diff 直接保存为公开证据。

### 10.2 单任务测试命令

以下是**当前存在**的入口示例；先按任务选一组，不整段全跑。新增测试文件必须创建后才能加入命令，且不得用“找不到测试但返回成功”选项。

Agent 示例（BASE-02）：

```powershell
Push-Location -LiteralPath (Join-Path $repoRoot 'agent-engine')
try {
    Invoke-Checked -Executable $agentPython -Arguments @(
        '-m', 'pytest', '-q', 'tests/test_schemas.py', 'tests/test_spec_verifier.py',
        'tests/test_candidate_verification_loop.py'
    )
} finally { Pop-Location }
```

Backend 示例（BASE-03）：

```powershell
Push-Location -LiteralPath (Join-Path $repoRoot 'backend')
try {
    Invoke-Checked -Executable $mavenExe -Arguments @(
        '-Dtest=DeliveryGateServiceTest,ToolGatewayServiceTest', 'test'
    )
} finally { Pop-Location }
```

Frontend 示例（P4-A 数据契约）：

```powershell
Push-Location -LiteralPath (Join-Path $repoRoot 'frontend')
try {
    Invoke-Checked -Executable 'npm.cmd' -Arguments @(
        'test', '--', 'src/api/workflow.test.ts', 'src/hooks/useProjectDetailData.test.ts'
    )
    Invoke-Checked -Executable 'npm.cmd' -Arguments @('run', 'build')
} finally { Pop-Location }
```

其他任务优先选择这些现有入口；名字省略公共测试目录，不代表新建同名文件：

| 任务 | Agent 测试文件 | Java 测试类 / Frontend 文件 |
| --- | --- | --- |
| P2-A/B | `test_agent_loop.py`、`test_control_plane_evaluation.py` | `WorkflowExecutableContractTest` |
| P2-C/D/E | `test_autospec_evaluation.py`、`test_control_plane_evaluation.py` | 无独立新 Java 套件要求 |
| P3-R1 | `test_worker_runner.py`、`test_worker_protocol.py` | `WorkflowRecoveryServiceTest`、`WorkflowEventConsumerTest` |
| P3-R2/R3 | `test_redis_stream_client.py`（有相关改动时） | `WorkflowEventPollerTest`、`WorkflowOutboxPublisherTest` |
| P3-R4 | `test_worker_runner.py`、`test_worker_protocol.py` | `WorkflowRunServiceTest`、`WorkflowRuntimeControllerTest` |
| P3-S/F | `test_tool_gateway.py`、`test_model_gateway.py`、`test_agent_loop.py` | `ToolGatewayServiceTest` |
| P3-K1/K2/K3 | `test_context_policy.py`、`test_retrieval_policy.py`、`test_p1_parallel_and_retrieval.py` | `ProjectMemoryServiceTest`、`KnowledgeCorpusEpochTest`、`HarnessH0KnowledgeTest`、`EmbeddingProviderTest` |
| P4-B/C/D | 无新 Agent 测试要求 | 当前 `ProjectDetailPage.test.ts` / hook 测试；新组件仅补第 7 节规定边界 |

任务内新增的断言应加到对应入口。只有现有文件职责明显不匹配时才新增小测试文件，并将真实路径更新到本文和交接记录。

### 10.3 MySQL / Redis 必要集成验证

先读所选 `*IT.java` 和 Testcontainers/代理配置，确认运行在隔离容器；禁止把故障代理指向现有业务库或全局停止 Docker。下面仅示范选择已有类，新增 watchdog/claim 并发断言应放入合适的现有 IT 或一个职责明确的新 IT。

```powershell
Push-Location -LiteralPath (Join-Path $repoRoot 'backend')
try {
    Invoke-Checked -Executable $mavenExe -Arguments @(
        '-Pintegration-test', '-Dit.test=MySqlOutboxIT,RedisOutboxRecoveryIT', 'verify'
    )
} finally { Pop-Location }
```

`integration-test` profile 不是普通 `mvn test` 的替代；它跳过单元测试并由 Failsafe 运行 IT。P3-R1 补测锁竞争，P3-R3 补测 claim 租约竞争；不因上述两个类通过就宣称全部恢复边界通过。原 CI 的 `MySqlFailureRecoveryIT` 检测超时需要定位实际轮询/超时配置；不单纯增大断言或删除测试。

### 10.4 里程碑完整验证

仅在第 9.2 节规定的时点执行。测试命令默认不付费；开跑前确认所选测试不依赖 live Key，不自动安装/升级依赖。

```powershell
Push-Location -LiteralPath (Join-Path $repoRoot 'backend')
try {
    Invoke-Checked -Executable $mavenExe -Arguments @('test')
} finally { Pop-Location }
Push-Location -LiteralPath (Join-Path $repoRoot 'agent-engine')
try {
    Invoke-Checked -Executable $agentPython -Arguments @('-m', 'pytest', '-q')
} finally { Pop-Location }
Push-Location -LiteralPath (Join-Path $repoRoot 'frontend')
try {
    Invoke-Checked -Executable 'npm.cmd' -Arguments @('test')
    Invoke-Checked -Executable 'npm.cmd' -Arguments @('run', 'build')
} finally { Pop-Location }
Push-Location -LiteralPath $repoRoot
try {
    Invoke-Checked -Executable $agentPython -Arguments @('scripts/verify_workflow_contract.py')
    Invoke-Checked -Executable docker -Arguments @('compose', 'config', '--quiet')
    Invoke-Checked -Executable docker -Arguments @('compose', '--profile', 'monitoring', 'config', '--quiet')
    Invoke-Checked -Executable docker -Arguments @('compose', '--profile', 'verification', 'config', '--quiet')
    Invoke-Checked -Executable git -Arguments @('diff', '--check')
} finally { Pop-Location }
```

verification profile 若要求独立沙箱密码/变量，按配置说明在本地安全设置；`config` 失败不能通过放松隔离或输出密钥解决。这里三种配置是默认、monitoring、verification，并不存在本文要求新建的生产 override 文件。`git diff --check` 不覆盖未跟踪文件，新增文件需额外检查行末空白和链接。不运行 `docker compose down -v`。

### 10.5 评测命令分为“已存在入口”和“实施后新增参数”

当前入口为 `python -m evaluation.run_control_plane --config FILE --output DIR`，需要会话环境中的 `AUTOSPEC_EVAL_SESSION_TOKEN` 与正确的 `AUTOSPEC_EVAL_BASE_URL`。本轮已补齐显式 manifest、`--validate-only`、`--resume`、组子集和持久 journal；**仍不得在缺少本批次预算和冻结候选时批量付费**。

P2-B 已将 `--validate-only` 实现为不建项目、不提交 run、不调用模型的校验，将 `--resume` 实现为读取持久 journal 后恢复；以下顺序用于获得真实批次授权后的验收：

1. `--help` 确认真实参数；fixture 假控制面证明校验模式零提交，恢复模式不重复生成。
2. 选定已冻结的配置与输出目录；原始输出必须位于已忽略目录，脱敏后才复制到 archive。不要将密钥放进配置或命令参数。
3. 运行 `--validate-only`，核对 dataset、group、预算、价格、hash、执行模式；其成功不构成付款授权。
4. 确认有覆盖该批次的有效预算，记录剩余预占额度和单次最坏成本；再由执行者启用一次运行。授权不够则留在 blocked，继续离线任务。
5. 中断后先检查 journal，再使用同一 config/output 和 `--resume`；禁止删 journal 或换 experiment_id 绕过保护。配置 hash 改变时停止，按新实验重新审批预算。

以下模板用于复核 P2-B 的离线校验；不会自动开始付费执行：

```powershell
$experimentConfig = Read-Host '输入已冻结的评测配置绝对路径（不含密钥）'
$experimentOutput = Read-Host '输入已确认被 Git 忽略的评测输出目录绝对路径'
if (-not [System.IO.Path]::IsPathRooted($experimentConfig) -or
    -not [System.IO.Path]::IsPathRooted($experimentOutput)) {
    throw '必须使用已核对的绝对路径，避免切目录后读错文件'
}
if (-not (Test-Path -LiteralPath $experimentConfig -PathType Leaf)) { throw '缺少冻结配置' }
Push-Location -LiteralPath (Join-Path $repoRoot 'agent-engine')
try {
    Invoke-Checked -Executable $agentPython -Arguments @('-m', 'evaluation.run_control_plane', '--help')
    Invoke-Checked -Executable $agentPython -Arguments @(
        '-m', 'evaluation.run_control_plane', '--config', $experimentConfig,
        '--output', $experimentOutput, '--validate-only'
    )
} finally { Pop-Location }
```

真正执行时在同目录调用相同入口，去掉 `--validate-only`；仅续跑时增加 `--resume`。先检查会话 Token 已设置但不输出其值。节点级离线控制实验的入口由 P2-B 实施时登记到本节；不得冒用当前整链路入口声称已支持该模式。

## 11. 失败分类、回退与停止规则

| 现象 | 先查什么 | 允许的处理 | 禁止的处理 |
| --- | --- | --- | --- |
| Schema/Prompt 不匹配 | 字段 path、实际 Handler/Schema hash | 修新版本、定向测试；冻结后另起批次 | 删除 MUST/AC 字段、覆盖旧发布 hash |
| MODEL_OUTPUT_LIMIT | finish reason、输出额度、usage、修复预占 | 记录失败；调整未冻结候选并重新预估 | 相同上限无限重试、拼接残缺 JSON |
| 验证事实拒绝 | 项目/run、scope/等级、来源 digest、TTL、台账 | 修授权链路或受控复验，保留旧事实 | 刷时间戳、把 UNKNOWN 变 PASS、改 Evaluation 伪造 fact |
| 实验缺组/中断 | journal、服务状态、预占与实际费用 | 同配置幂等续采；不足样本标 NOT_EVALUATED | 重建项目重新烧钱、把失败样本剔除 |
| 预算耗尽/费用未知 | 共享台账、未结算预占、已发出请求 | 停止新付费调用，完成可做的离线工作 | 费用记零、增加额度、换批次绕限 |
| 租约/重复发布失败 | owner、lease、fence、eventId、事务边界 | 隔离复现；恢复旧可运行候选，保留失败证据 | 删除幂等记录、清 Redis/库、宣称恰好一次 |
| 数据库迁移失败 | 当前 Flyway、目标 SQL、备份与锁 | 隔离库修未发布迁移；已发布用后续修复迁移 | 改 V1、直接 repair 掩盖校验差异、清业务卷 |
| Provider 不支持原生工具 | 核实模型能力、请求格式、真实响应 | 明确标不支持；保留显式 JSON 对照候选 | 静默降级后仍统计为 native 成功 |
| UI 与后端状态不同 | 版本、DTO、旧请求响应、readiness | 修接口映射/竞态，临时展示未知 | 在前端自行放行交付、硬编码成功样本 |
| IT/CI 超时 | 环境就绪、采样时间、重试间隔、资源与日志 | 解释时间预算，修具体错误后重跑相关类 | 盲目放宽阈值、删除失败断言、反复全量重试 |

回退以停止使用新候选、恢复明确选择的上一可用版本为主；不改写历史运行。代码回退仅限本任务已确认的修改，先检查并发编辑；不用 `reset --hard`。数据库回退恢复方案必须单独核对目标库和可恢复备份，不能把本文当作删库授权。

同一失败连续两次且证据没有变化时，停止机械重试，记录已尝试的假设与下一项可区分原因的检查。基础设施失败与产品失败分别统计；无法确认已发出模型请求是否计费时按未知保守处理。

## 12. 状态记录与交给下一个模型的模板

### 12.1 每个任务只追加一条紧凑记录

执行者在本轮脱敏证据文件中使用以下结构，随后更新第 3 节状态表；大段日志保存在忽略目录，不粘进计划。使用 `n/a` 说明不适用，不用空字符串冒充完成。

```yaml
task: P2-B
status: in_progress
completed_steps: []
next_step: "对应任务的具体步骤编号与动作"
baseline:
  branch: "实际分支"
  head: "实际提交"
  worktree_fingerprint: "相关文件内容指纹；不能只填 HEAD"
changed_files: []
contract_or_migration_change: "无，或列出真实版本和消费者"
validation:
  commands: []
  results: []
  execution_mode: "static / unit / fixture-worker / live / integration"
  not_run: []
evidence:
  public_paths: []
  private_log_location: "仅记录安全的仓库相对位置，不包含密钥"
  run_ids: []
  manifest_hash: "n/a"
budget:
  paid_calls: 0
  actual_cost: "n/a；未知写 unknown，不默认写 0"
  unresolved_reservations: "n/a"
  authorization_scope: "未授权 / 当前有效批次与上限"
remaining_risks: []
blocked_by: []
rollback: "仅本任务范围内的具体操作；不得泛写清库重来"
next_task: "前置满足的下一项"
```

新增版本、表、环境变量、CLI 参数、HTTP 接口时，同条记录列出真实名称、默认行为和同步的消费者。不要在计划与实现之间留下“应该有一个脚本”但没有路径的交接。

### 12.2 下一执行模型首条指令（可复制）

> 阅读 AGENTS.md、docs/autospec-v5-spec-sandbox-plan.md、docs/p2-p4-execution-plan.md，以及最新一条脱敏交接记录。先只读核对当前分支、未提交改动、任务状态和实际版本，不重置数据库，不恢复旧迁移，不覆盖当前工作区。选择手册第 3 节首个前置满足的未完成必选任务，一次只执行这一项；从其 next_step 继续，不重做已有证据支持的步骤。按对应文件入口修改，复用最小必要边界测试，完成后写第 12.1 节记录并更新状态。新参数/脚本必须先实现并验证，不能假设存在。付费调用前核对该批次的有效授权、剩余预算和冻结配置；不足时停止付费但继续独立离线任务。不要自动实现 deferred 扩展、切默认版本、提交、push 或删除用户数据。最后明确交付了什么、实际运行了什么、尚未执行什么，以及下一项。

### 12.3 本轮阶段交接状态

- 离线实施范围已完成：显式规格适配、评测 manifest/数据集/统计、可靠性边界、原生协议拒绝降级、项目知识上传与可信记忆、Trace/验证展示、OpenAPI 同步和 15 张问答卡片。
- 必须保持 `blocked` 的项目：P2-E 的完整 dev/holdout 正式消融与 P4-D 的完整 live/holdout 晋级证据；P4-E 截图归档已按用户要求豁免。当前批次已通过正式 API 发布 A/B/C/D `#10/#11/#12/#13`；r10 已修正 manifest 身份，fixture smoke 的 run `119–122` 四组成功，manifest hash 为 `689a6e56618ffbf521da79d43ddacbc65093ccb61103aef421d85f3c8907cb9a`。其 `AUTO_APPROVE_FOR_TEST` 是预注册 fixture 规则，不是人工批准，结果仍为 `FIXTURE_BASELINE/NOT_EVALUATED`，不能替代 live 结论；历史 r9 因 manifest 内部 experiment ID 与配置不一致，现已被 `--validate-only` 拒绝，不作为当前批次。用户随后授权 13 元 DeepSeek 预算，live smoke 的配置、run、迁移修复和 D-only 重试见 2026-10-01 证据；五次尝试共保守预占 10 元，完整 dev/holdout 所需样本超出本批次的可审计单次预算，不继续追加付费调用。中间 r7 因根目录 `.env` 的 live 模式误注入而启动的两个 live 运行已停止采集并正式取消，不纳入证据；隔离 Agent API/Worker 随后强制固定为 fixture。P4-D 已增加 `AUTOSPEC_EVALUATION_RESULT_DIR` 只读结果目录加载、混合模式和 fixture 伪晋级拒绝；结果目录在缺组或缺重复时仍明确返回 `NOT_EVALUATED`。P4-E 的 Docker fixture 浏览器演示已走通并捕获审批、Evaluator 门禁负例、Evaluator 通过和交付 bundle 画面，截图归档按用户要求不提交。P2-F 已完成冻结输入下的 10/20 成功容量证据：项目 41 的 run 56–65、项目 42 的 run 66–85 均为 `COMPLETED`，各批次六节点成功、Evaluator `100/A/PASSED`，队列 P50/P95 为 10/13 秒与 18/20 秒，执行 P50/P95 为 101/108 毫秒与 101/107 毫秒；此前动态知识索引造成的失败 smoke 仍保留为负面边界，不将本地 fixture 观测写成生产 SLA 或跨配置质量结论。提交 `00fe70d` 已补齐 Agent Engine 本地/Compose 地址边界，并通过重建后的健康检查与首页复验。
- P3-K3 已完成本地真实模型对照：100 条、5 个领域、`top_k=5`，hashing 与 local semantic 的 Recall@5 都为 `1.0`，ACL/过期泄漏均为 `0`；MRR/nDCG、耗时和模型指纹见 [2026-10-01 证据](archive/evidence/p2-p4-2026-10-01.md)。该结果不宣称语义质量提升，也不替代外部 provider 生产配置。
- P3-MCP、P3-I 继续 `deferred`，因为本轮没有明确外部集成或变更影响范围选择。
- 可复核命令、实际结果、变更文件、阻塞条件和下一步写入 [p2-p4-2026-09-30.md](archive/evidence/p2-p4-2026-09-30.md)；浏览器演示补充写入 [p2-p4-2026-10-01.md](archive/evidence/p2-p4-2026-10-01.md)。后续续做先读最新记录和实际工作区，不依赖口头结论。
- 本轮收口回归已通过：Agent Engine `226 passed`、Backend `185 tests`、Frontend `28 tests` 与生产构建；WorkflowSpec 同步校验、三个 Compose 配置面和隔离项目 9 服务健康检查均通过。该结果不解除 P2-E 完整 dev/holdout 或 P4-D 完整晋级矩阵的阻塞；P4-E 截图归档已按用户要求豁免。
