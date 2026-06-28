# Repository Guidelines

## Local Tool Paths

本项目固定使用以下本机工具路径，后续执行命令时不要再询问：

- Use these exact local executables for this project and do not ask the user to repeat them.
- Maven: `D:\apache-maven-3.8.9\bin\mvn.cmd`
- Agent Python: `D:\miniconda3\envs\CrewAI_Study\python.exe`
- Agent pytest: `D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest`

## Project Scope

本仓库用于实现 **AutoSpec：基于多 Agent 协作的软件需求分析与原型生成系统**。目标不是直接部署 MetaGPT，而是参考其“软件公司流程 + 多角色协作”的思想，自研一个轻量、可解释、可观测的平台：用户输入一句需求，系统生成 PRD、用户故事、架构设计、数据库设计、API 文档、前端页面骨架、任务拆解和一致性审查报告。

## Project Structure & Module Organization

计划采用多模块结构：

- `backend/`：Spring Boot 后端，负责用户、项目、Artifact、Agent 任务、审查结果、导出接口。
- `agent-engine/`：Python FastAPI + LangGraph Agent 编排服务，包含 `agents/`、`graph/`、`prompts/`、`schemas/`、`review/`。
- `frontend/`：React + TypeScript + Ant Design 前端，展示需求输入、Agent 进度、产物预览和历史项目。
- `docs/`：开发计划、接口约定、示例 PRD/API/Review 文档。
- `docker-compose.yml`：本地编排 MySQL、Redis、后端、Agent 服务和前端。

## Build, Test, and Development Commands

当前源码模块尚未创建。模块落地后，优先维护这些根目录可复现命令：

- `cd backend && mvn test`：运行 Spring Boot 单元与集成测试。
- `cd backend && mvn spring-boot:run`：启动后端 API。
- `cd agent-engine && pytest`：运行 Agent Schema、工作流和 Reviewer 规则测试。
- `cd agent-engine && uvicorn main:app --reload`：启动 Agent 服务。
- `cd frontend && npm run dev`：启动前端开发服务器。
- `cd frontend && npm run build`：检查前端生产构建。
- `docker compose up --build`：启动完整本地环境。

## Coding Style & Naming Conventions

Java 使用 4 空格缩进，包名小写，如 `com.autospec`；类名 `PascalCase`，方法和字段 `camelCase`，常量 `UPPER_SNAKE_CASE`。后端按 `controller/service/entity/mapper/dto/config` 分层。

Python 使用类型标注，Agent 文件命名使用小写下划线，如 `product_manager.py`。Prompt 模板必须带版本号，如 `ProductManagerAgent_v1`。TypeScript 组件使用 `PascalCase`，页面放在 `frontend/src/pages`。

所有 Agent 输出优先使用 JSON Schema 约束，避免只保存大段自由文本。

## Testing Guidelines

后端使用 JUnit 与 Spring Boot Test；Agent 服务使用 pytest；前端使用 Vitest 或 React Testing Library。重点测试不是“LLM 文案是否好看”，而是结构化协议、状态流转、失败重试和 Reviewer 规则。

测试命名示例：`ProjectServiceTest`、`test_reviewer_detects_missing_favorite_api.py`、`ProjectProgressPage.test.tsx`。新增功能必须附带最小回归测试或可复现样例。

## Commit & Pull Request Guidelines

当前 `.git` 目录不可用，无法从历史推断项目提交规范。采用 Conventional Commits：`feat: add agent workflow`、`docs: update implementation plan`、`test: add reviewer rule cases`。

PR 需包含变更摘要、测试命令与结果、相关 issue/任务、接口或 Schema 变更说明；涉及前端页面时附截图，涉及 Agent 输出时附一份脱敏示例 Artifact。

## Agent-Specific Instructions

不要把 LLM 当成黑盒文本生成器。每个 Agent 的输入、输出、耗时、状态、错误信息都应记录到任务表或日志中。Reviewer Agent 必须做“规则检查 + LLM 语义审查”两层校验，重点检查 PRD、数据库、API、前端页面和权限的一致性。

禁止提交 API Key、模型密钥、数据库密码和本机绝对路径。使用 `.env.example` 说明配置项，用本地未跟踪文件保存真实凭据。
