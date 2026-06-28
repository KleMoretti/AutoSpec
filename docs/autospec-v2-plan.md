# AutoSpec V2 Implementation Plan

> Canonical superpowers plan: `docs/superpowers/plans/2026-06-28-autospec-v2-implementation-plan.md`
>
> The detailed V2 implementation plan has been rewritten with `superpowers:writing-plans`.
> Use the canonical plan above for execution; the earlier database snapshot below is retained only as historical context.
>
> Implementation status on 2026-06-28: V2 code is implemented. See `docs/examples/v2-sample-artifacts.md` for sanitized sample artifacts and verification evidence.

## Database Record

- Project: `AutoSpec V2 Plan`
- Project ID: `2`
- Artifact type: `ROADMAP_PLAN`
- Artifact ID: `4`
- Artifact version: `2`
- Source: `docs/autospec-implementation-plan.md#V2 Backlog`

## Objective

在 V1 自然语言需求输入、PRD、数据库/API 设计、Reviewer 一致性检查和 Markdown 导出闭环上，增强多角色设计能力、人审协作、实时观测、提示词治理、失败恢复和前端骨架生成。

## Plan Items

| ID | Title | Priority | Status | Description | Deliverables |
| --- | --- | --- | --- | --- | --- |
| V2-01 | Architect Agent | P0 | TODO | 新增架构师 Agent，输出系统上下文、模块边界、关键技术决策、非功能约束和跨端一致性风险。 | `ARCHITECTURE_DESIGN` Artifact; `architect_v1` prompt; architecture schema and tests |
| V2-02 | Frontend Agent | P0 | TODO | 新增前端 Agent，根据 PRD、架构和 API 生成 React 页面骨架、路由、组件树和 API 调用约定。 | `FRONTEND_SKELETON` Artifact; `frontend_engineer_v1` prompt; frontend skeleton schema and tests |
| V2-03 | Human-in-the-loop PRD editing | P0 | TODO | PRD 生成后进入可编辑和确认环节，后续 Agent 只消费已确认版本，并保留编辑记录。 | artifact versioning rule; PRD edit/approve API; frontend edit and approve view |
| V2-04 | SSE or WebSocket real-time streaming | P1 | TODO | 将 Agent 节点状态、耗时、错误和增量输出实时推送到前端，替换或补强轮询。 | event stream endpoint; agent task event model; frontend live timeline |
| V2-05 | PDF export | P1 | TODO | 在 Markdown 导出基础上新增 PDF 导出，覆盖 PRD、架构/API、前端骨架和审查报告。 | PDF export service; export API `format=PDF`; layout regression sample |
| V2-06 | Prompt version management table | P1 | TODO | 新增提示词版本管理表，记录 prompt key、版本、内容、启用状态、校验摘要和发布时间，支持 Agent 可追溯。 | `prompt_version` table; prompt repository service; prompt version tests |
| V2-07 | Failed-node retry and local regeneration | P1 | TODO | 基于已持久化的节点输入和输出，支持失败节点单独重试，以及从指定 Artifact 局部重新生成后续产物。 | retry API; node state transition rules; retry regression tests |

## Milestones

| ID | Name | Items | Exit Criteria |
| --- | --- | --- | --- |
| M1 | Governance and review gate | V2-03, V2-06 | PRD 可编辑确认，Prompt 可按版本追踪。 |
| M2 | Expanded agent outputs | V2-01, V2-02 | 架构设计和前端骨架作为结构化 Artifact 入库并可预览。 |
| M3 | Runtime observability and recovery | V2-04, V2-07 | 前端可实时看到 Agent 节点进度，失败节点可单独重试。 |
| M4 | Export polish | V2-05 | 完整项目可导出 Markdown 和 PDF。 |

## Acceptance Criteria

- 每个新增 Agent 的输入、输出、耗时、状态和错误都必须落库或可从日志追踪。
- 所有新增 Artifact 优先使用 JSON Schema 约束。
- Reviewer 仍作为强制质量门，覆盖 PRD、架构、数据库/API、前端页面和权限一致性。
- 新增功能必须有最小回归测试或可复现样例。
- 不得提交 API Key、模型密钥、数据库密码或本机绝对路径。
