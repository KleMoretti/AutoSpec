# AutoSpec 需求、交付证据与澄清链路修复

日期：2026-10-14。本文记录本次实现和定向验证，不替代根目录的任务计划。

| 问题 | 修复结果 |
| --- | --- |
| 需求基线在模型请求中丢失，Evaluator 拒绝字段 | Worker 的运行时输入封装明确声明需求基线和来源绑定。各角色的实际模型请求及 Backend Engineer 多轮循环接收基线、项目记忆和冲突；Evaluator 校对批准 PRD 的 ID、版本、摘要、范围与约束。 |
| 验证与实际交付材料不一致 | 输入装配从对应节点的实际 Artifact 版本取材，并保存 `artifact_bindings`。交付同时校对 Reviewer 与 Evaluator 的输入来源、真实内容摘要、验证事实的运行/节点/fencing 身份和冻结策略；Python 重新编译当前材料计算验证来源摘要。 |
| 代码包清单只检查字段形状 | 清单逐项匹配类型、ID、版本和真实 SHA-256，拒绝遗漏、重复和伪造摘要；构建报告摘要、持久化清单摘要与清单内容一致后才允许复用。 |
| 澄清冲突无法提交、权限和后续轮次未接通 | 提供保留当前值、采用来源值、自定义决定三种操作。服务端拒绝缺少阻断问题或冲突决定的回答，规范化来源值。项目详情返回 `canEdit`，面板默认只读，持续刷新并在新轮次重置表单和幂等键。澄清审批通过专用面板处理。 |
| Java 与 Python 工具策略不一致 | Java 接受并校验 `required_tools`、`optional_max_calls`，冻结时保留字段，网关规范化与 Python 的默认字段省略规则一致。Worker 与网关分别对自身执行范围计数，限制可选调用并为尚未使用的必需工具预留次数；预算拒绝审计记录不消耗网关执行次数。 |

运行时封装见 [runtime_input.py](../../agent-engine/schemas/runtime_input.py)。历史 Agent 输入 Schema 的名称和哈希仍描述冻结的业务契约；封装由生产 Handler 注册并在 Worker 入口验证。历史 WorkflowSpec、Prompt、输出 Schema 和 Flyway SQL 均未改写。用户原有的 `AGENTS.md` 修改保留。

需求基线及 Artifact 来源绑定参与上下文预算，并完整保留；预算不足时明确失败。项目记忆冲突进入澄清契约前去除项目作用域冗余字段，当前运行中已经由用户解决的冲突标为已解决，其决定仍保存在澄清上下文和基线中。

材料、版本或来源身份改变后，旧报告不能继续放行。缺少来源绑定的历史验证报告也需要使用现有返工/回放流程重新审查、评估并生成代码包；报告和历史 Artifact 本身不删除。

## 定向验证

- Python：生产 Handler、Evaluator、工具网关和上下文策略的相关测试；补充基线/知识/记忆进入实际模型请求及 Evaluator 的回归、当前材料摘要变化回归，并在已有 Backend Engineer 返工测试中核对每轮模型请求的上下文。
- Java：交付门禁、代码骨架生成、工作流契约、输入装配、工具网关与澄清回应的相关用例。覆盖真实内容和版本变更、陈旧 Reviewer 来源、清单摘要、跨语言工具策略哈希、可选预算耗尽后的必需调用及阻断冲突校验。
- 前端：TypeScript 类型检查通过；项目详情、审批面板、详情数据 Hook、国际化共 10 个现有 Vitest 用例通过。
- 浏览器：使用真实面板和受控 API 响应，走通回答、假设确认、自定义冲突提交、新轮次表单重置及只读权限。收到的提交体包含全部三个决策部分。此验证未使用真实后端运行或外部模型。
- `scripts/verify_workflow_contract.py` 与 `git diff --check` 通过。

未执行真实模型调用、Compose 端到端发布验收、生产数据库变更、提交或推送。

复验时使用 `AGENTS.md` 指定工具路径，仅运行上述受影响用例。例如：

```powershell
# agent-engine 目录
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q tests/test_production_handlers.py tests/test_evaluator.py tests/test_tool_gateway.py tests/test_context_policy.py
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q tests/test_agent_loop.py::test_backend_loop_replans_after_deterministic_validation_failure

# backend 目录
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' '-Dtest=DeliveryGateServiceTest,WorkflowNodeInputAssemblerTest,WorkflowExecutableContractTest,ToolGatewayServiceTest,WorkflowApprovalServiceTest#inputRequiredPersistsClarificationAndResumesProductManagerRevision' test -q

# frontend 目录
npx tsc -b --pretty false
npm test -- src/pages/ProjectDetailPage.test.ts src/components/WorkflowApprovalPanel.test.tsx src/hooks/useProjectDetailData.test.ts src/i18n/index.test.tsx

# 仓库根目录
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' scripts/verify_workflow_contract.py
git diff --check
```
