# AutoSpec Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个轻量自研的多 Agent 软件需求分析平台，先完成“自然语言需求输入 -> PRD -> 数据库/API -> Reviewer 一致性检查 -> Markdown 导出”的 V1 闭环。

**Architecture:** 前端 React 负责输入、进度和产物展示；Spring Boot 负责项目、Artifact、任务状态、导出和权限；Python FastAPI + LangGraph 负责 Agent 编排与 LLM 调用。MySQL 持久化项目、Agent 任务、Artifact 和审查问题，Redis 缓存执行进度、限流状态和临时任务状态。

**Tech Stack:** React, TypeScript, Ant Design, Spring Boot, MyBatis-Plus, MySQL, Redis, Python FastAPI, LangGraph, pytest, JUnit, Docker Compose.

---

## Reference Positioning

MetaGPT 的 README 将其定位为 multi-agent framework，强调用不同角色组成协作实体，并将一句需求转成 user stories、requirements、data structures、APIs、documents 等产物。本项目只参考这个思想，不复制 MetaGPT 的完整实现，核心差异是：面向校招/后端项目展示，强调结构化 JSON 输出、Reviewer 规则校验、任务可观测性和 Spring Boot 工程落地。

## Target Repository Layout

```text
autospec-agent/
+-- backend/
|   +-- src/main/java/com/autospec/
|   |   +-- controller/
|   |   +-- service/
|   |   +-- entity/
|   |   +-- mapper/
|   |   +-- dto/
|   |   +-- config/
|   +-- pom.xml
+-- agent-engine/
|   +-- agents/
|   +-- graph/
|   +-- prompts/
|   +-- schemas/
|   +-- review/
|   +-- tests/
|   +-- main.py
+-- frontend/
|   +-- src/pages/
|   +-- src/components/
|   +-- package.json
+-- docs/
+-- docker-compose.yml
```

## V1 Scope

V1 只做可演示闭环：

- 创建项目并保存原始需求。
- Product Manager Agent 生成结构化 PRD 和用户故事。
- Backend Agent 生成数据库表设计与 API 文档。
- Reviewer Agent 检查 PRD、数据库、API 的一致性。
- 后端保存 Agent 执行记录、Artifact、Review Issue。
- 前端展示生成进度、Artifact 列表和审查问题。
- 支持导出 Markdown。

V1 暂不做完整 React 页面代码生成、PDF 导出、历史 RAG、代码骨架 ZIP 和复杂微服务拆分。

---

## Task 1: Repository Baseline

**Files:**
- Create: `backend/pom.xml`
- Create: `agent-engine/requirements.txt`
- Create: `frontend/package.json`
- Create: `docker-compose.yml`
- Modify: `AGENTS.md`

- [ ] **Step 1: Create backend Maven skeleton**

  Add Spring Boot, MyBatis-Plus, MySQL driver, Redis, validation, Lombok, and test dependencies in `backend/pom.xml`.

- [ ] **Step 2: Create Agent service Python dependencies**

  Add `fastapi`, `uvicorn`, `langgraph`, `pydantic`, `pytest`, `httpx`, and model SDK dependencies to `agent-engine/requirements.txt`.

- [ ] **Step 3: Create frontend package baseline**

  Add React, TypeScript, Vite, Ant Design, routing, request client, test runner, and build scripts to `frontend/package.json`.

- [ ] **Step 4: Add local infrastructure**

  Define MySQL and Redis services in `docker-compose.yml`, with named volumes and `.env`-driven credentials.

- [ ] **Step 5: Verify baseline commands**

  Run:

  ```bash
  cd backend && mvn test
  cd agent-engine && pytest
  cd frontend && npm run build
  docker compose config
  ```

---

## Task 2: Core Data Model

**Files:**
- Create: `backend/src/main/java/com/autospec/entity/Project.java`
- Create: `backend/src/main/java/com/autospec/entity/AgentTask.java`
- Create: `backend/src/main/java/com/autospec/entity/Artifact.java`
- Create: `backend/src/main/java/com/autospec/entity/ReviewIssue.java`
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`

- [ ] **Step 1: Define project table**

  Include `id`, `user_id`, `name`, `original_requirement`, `status`, `created_at`, and `updated_at`.

- [ ] **Step 2: Define agent_task table**

  Include `project_id`, `agent_name`, `status`, `input_text`, `output_text`, `error_message`, `start_time`, `end_time`, and `created_at`.

- [ ] **Step 3: Define artifact table**

  Include `project_id`, `type`, `title`, `content`, `format`, `version`, and `created_at`.

- [ ] **Step 4: Define review_issue table**

  Include `project_id`, `severity`, `issue_type`, `description`, `suggestion`, `status`, and `created_at`.

- [ ] **Step 5: Add mapper and service tests**

  Verify create/read/update flows for projects, artifacts, task records, and review issues.

---

## Task 3: Agent Output Schemas

**Files:**
- Create: `agent-engine/schemas/prd.py`
- Create: `agent-engine/schemas/backend_design.py`
- Create: `agent-engine/schemas/review.py`
- Create: `agent-engine/tests/test_schemas.py`

- [ ] **Step 1: Define PRD schema**

  Include `project_name`, `target_users`, `core_features`, `user_stories`, `business_boundaries`, `non_functional_requirements`, and `risks`.

- [ ] **Step 2: Define backend design schema**

  Include `tables`, `fields`, `apis`, `request_params`, `response_fields`, `auth_required`, and `required_roles`.

- [ ] **Step 3: Define review schema**

  Include `score`, `issues`, `severity`, `issue_type`, `description`, and `suggestion`.

- [ ] **Step 4: Add schema validation tests**

  Use the campus second-hand marketplace sample requirement as a fixture and verify valid JSON parses into typed Pydantic models.

---

## Task 4: V1 Agent Workflow

**Files:**
- Create: `agent-engine/agents/product_manager.py`
- Create: `agent-engine/agents/backend_engineer.py`
- Create: `agent-engine/agents/reviewer.py`
- Create: `agent-engine/graph/workflow.py`
- Create: `agent-engine/prompts/product_manager_v1.md`
- Create: `agent-engine/prompts/backend_engineer_v1.md`
- Create: `agent-engine/prompts/reviewer_v1.md`
- Create: `agent-engine/tests/test_workflow.py`

- [ ] **Step 1: Implement Product Manager Agent**

  Input is original requirement. Output must match `PrdArtifact` schema and include roles, core features, priorities, and user stories.

- [ ] **Step 2: Implement Backend Agent**

  Input is original requirement plus PRD. Output must match `BackendDesignArtifact` and include database tables plus REST APIs.

- [ ] **Step 3: Implement Reviewer Agent**

  Input is PRD plus backend design. Output must match `ReviewReport` and include rule-based issues before optional LLM semantic review.

- [ ] **Step 4: Implement LangGraph workflow**

  Execute nodes in order: `product_manager -> backend_engineer -> reviewer`. Persist every node input and output through callback hooks.

- [ ] **Step 5: Test deterministic workflow with mocked LLM**

  Mock model responses and verify the workflow returns PRD, backend design, and review report artifacts.

---

## Task 5: Reviewer Rule Engine

**Files:**
- Create: `agent-engine/review/rules.py`
- Create: `agent-engine/tests/test_review_rules.py`

- [ ] **Step 1: Check PRD feature to API coverage**

  If PRD contains features such as 收藏商品, 商品发布, 商品搜索, 订单交易, or 管理员审核, verify matching API paths or descriptions exist.

- [ ] **Step 2: Check API to database coverage**

  If an API references `productId`, `orderId`, `favorite`, `message`, or `audit`, verify a related table or field exists.

- [ ] **Step 3: Check permission coverage**

  Verify admin audit APIs require authentication and include `ADMIN` in required roles.

- [ ] **Step 4: Add regression fixtures**

  Add one passing fixture and at least three failing fixtures: missing favorite API, missing product image storage, and admin API without role restriction.

---

## Task 6: Backend API Integration

**Files:**
- Create: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Create: `backend/src/main/java/com/autospec/service/ProjectService.java`
- Create: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Create: `backend/src/main/java/com/autospec/dto/CreateProjectRequest.java`
- Create: `backend/src/main/java/com/autospec/dto/ProjectProgressResponse.java`
- Create: `backend/src/test/java/com/autospec/ProjectControllerTest.java`

- [ ] **Step 1: Add project creation endpoint**

  Implement `POST /api/projects` with `name` and `requirement`, returning `projectId` and `CREATED`.

- [ ] **Step 2: Add generation endpoint**

  Implement `POST /api/projects/{projectId}/generate`, create Agent task records, call Agent service, and return running status.

- [ ] **Step 3: Add progress endpoint**

  Implement `GET /api/projects/{projectId}/progress`, returning current Agent, percent, and per-step status.

- [ ] **Step 4: Add artifact endpoint**

  Implement `GET /api/projects/{projectId}/artifacts`, returning saved PRD, backend design, and review report.

- [ ] **Step 5: Add review endpoint**

  Implement `GET /api/projects/{projectId}/review`, returning score and issue list.

---

## Task 7: Markdown Export

**Files:**
- Create: `backend/src/main/java/com/autospec/service/MarkdownExportService.java`
- Create: `backend/src/main/java/com/autospec/controller/ExportController.java`
- Create: `backend/src/test/java/com/autospec/MarkdownExportServiceTest.java`

- [ ] **Step 1: Render PRD section**

  Convert PRD JSON into Markdown headings, tables for user stories, and bullet lists for requirements and risks.

- [ ] **Step 2: Render database and API sections**

  Convert backend design JSON into table definitions and endpoint blocks.

- [ ] **Step 3: Render review section**

  Convert review issues into a severity-sorted Markdown table.

- [ ] **Step 4: Add export endpoint**

  Implement `POST /api/projects/{projectId}/export` with `format=MARKDOWN`.

---

## Task 8: Frontend V1 Experience

**Files:**
- Create: `frontend/src/pages/HomePage.tsx`
- Create: `frontend/src/pages/ProjectDetailPage.tsx`
- Create: `frontend/src/components/AgentTimeline.tsx`
- Create: `frontend/src/components/ArtifactTabs.tsx`
- Create: `frontend/src/components/ReviewIssueTable.tsx`
- Create: `frontend/src/api/projects.ts`

- [ ] **Step 1: Build requirement input page**

  Provide project name and requirement text area, then call `POST /api/projects`.

- [ ] **Step 2: Build generation timeline**

  Show Product Manager, Backend Engineer, and Reviewer states using polling or SSE.

- [ ] **Step 3: Build artifact preview**

  Display PRD, backend design, and review report in tabs.

- [ ] **Step 4: Build review issue table**

  Show severity, type, description, suggestion, and status.

- [ ] **Step 5: Build export action**

  Call Markdown export endpoint and download the generated file.

---

## V2 Backlog

Canonical V2 plan: `docs/superpowers/plans/2026-06-28-autospec-v2-implementation-plan.md`.

Implementation status on 2026-06-28: V2 code has been implemented across backend, agent-engine, and frontend.

- Implemented Architect Agent and Frontend Agent structured artifacts.
- Implemented human-in-the-loop PRD editing and approval before downstream generation.
- Implemented persisted Agent execution events plus SSE/history endpoints.
- Implemented PDF export alongside Markdown export.
- Implemented prompt version persistence and Agent task prompt linkage.
- Implemented failed-node retry from persisted node input.
- Implemented frontend V2 workflow, artifact previews, event list, retry, and PDF download.

## V3 Backlog

- Add multi-model routing and result scoring.
- Add historical project RAG.
- Add Spring Boot / React code skeleton ZIP generation.
- Add LangGraph workflow visualization.
- Add permission model and user login.

## Verification Checklist

- [ ] `AGENTS.md` reflects AutoSpec-specific contributor rules.
- [ ] `docs/autospec-implementation-plan.md` exists and matches V1/V2/V3 scope.
- [ ] V1 has a demonstrable path from requirement input to Markdown export.
- [ ] Reviewer Agent remains a required quality gate.
- [ ] No secrets, API keys, or local credentials are documented as real values.
