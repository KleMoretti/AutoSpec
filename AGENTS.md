# Repository Guidelines

## Local Tool Paths

本项目固定使用以下本机工具路径，执行命令时不要再次询问用户：

- Maven：`D:\apache-maven-3.8.9\bin\mvn.cmd`
- Agent Python：`D:\miniconda3\envs\CrewAI_Study\python.exe`
- Agent pytest：`D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest`
- Shell：PowerShell

后端最低要求 Java 17，本机 Java 21 可直接使用；前端使用 Node.js 22；完整本地环境优先使用 Docker Desktop 与 Docker Compose。

## Current Product Baseline

产品名称统一为 **AutoSpec**。新增文档文件名、标题、README、图示与用户界面不使用 V5 等代际后缀。已有 WorkflowSpec ID、发布版本、Prompt/Schema/Handler 版本、历史迁移及运行证据是兼容与追溯标识，不能因产品改名而重写。`docs/autospec-v5-spec-sandbox-plan.md` 是当前任务总计划；按用户指定保留该文件名，正文产品名称仍为 AutoSpec。按用户后续要求，`docs/p0-p1-execution-plan.md` 和 `docs/p2-p4-execution-plan.md` 同样保留在根目录，作为该任务的分阶段详细执行手册，不是独立路线。其他文档、样例与图片全部放入 `docs/archive/`，仅供参考，不作为实施待办。

本仓库实现 **AutoSpec：基于多 Agent 协作的软件需求分析、原型生成与交付验证平台**。用户输入一句需求后，系统生成并管理 PRD、用户故事、架构、数据库/API、前端骨架、审查、评估和代码骨架等结构化 Artifact。

当前产品工作流为 `autospec-v5`：新数据库仅初始化经过本地验收的 `pm-schema-repair-v12`。工作流/Handler 的版本号与数据库 Flyway 版本相互独立；已有运行继续使用自己的冻结快照。旧 `v5`、`v5-parallel` 等 JSON 仅作为兼容测试输入，不再自动写入新数据库。工作流必须保留完整的六节点能力：

1. Product Manager
2. Architect
3. Backend Engineer
4. Frontend Engineer
5. Reviewer
6. Evaluator

动态 DAG、Redis Streams、Python Worker、人工审批、定向返工、取消、恢复、回放、死信、Artifact 版本、项目级 RAG、模型路由与预算、审计、诊断、交付门禁以及 Markdown/PDF/ZIP 导出均属于当前产品能力。不得将产品再次简化为“三个 Agent、三个 Schema 和一个 `/generate`”。

正式生成入口是后端 `POST /api/workflow-runs`。Agent Engine 的 FastAPI 进程只提供健康检查、评测用例和实验比较；业务节点由 Redis Worker 执行，不提供同步 `/generate*` 流水线。

## Architecture and Module Organization

- `backend/`：Spring Boot 控制面，以 MySQL 为事实源，负责项目、权限、WorkflowSpec 版本、DAG 调度、事务 Outbox、事件幂等消费、审批、返工、恢复、回放、Artifact、审查、评估和导出。
- `agent-engine/`：Python Agent 与 Worker 运行时。`agents/` 保存角色实现，`runtime/` 保存节点执行、Redis Worker、台账、指标和追踪，`schemas/` 保存 Pydantic 契约，`review/` 保存规则审查与 Evaluator，`model_gateway.py` 保存 OpenAI 兼容模型网关。
- `frontend/`：React、TypeScript、Ant Design 产品工作台，覆盖 Intake -> Generate -> Review & fix -> Deliver 完整旅程。
- `backend/src/main/resources/contracts/autospec.openapi.yaml`：当前后端 OpenAPI 契约。
- `agent-engine/contracts/autospec-pm-schema-repair-v12.workflow.json`：当前可执行 WorkflowSpec，必须与 V1 数据库种子同步；其他保留的 Workflow JSON 用于历史兼容测试。
- `backend/src/main/resources/db/migration/`：Flyway 数据库升级历史。
- `observability/`：Prometheus、Grafana 与 Tempo 配置。
- `docs/`：当前任务总计划为 `autospec-v5-spec-sandbox-plan.md`，分阶段步骤见 `p0-p1-execution-plan.md`、`p2-p4-execution-plan.md`；其余设计、契约说明、样例、图示与证据全部归档至 `docs/archive/`。
- `docker-compose.yml`：MySQL、Redis、Agent API、两个 Agent Worker、后端、前端及可选监控栈。

## Legacy Code Boundary

V1–V4 固定流水线和兼容层已被删除，不得重新引入以下内容：

- `/generate`、`/generate-prd`、`/generate-v4`、旧继续生成、旧进度、旧任务重试和 SSE Agent 事件接口。
- `graph/workflow.py` 固定编排和 LangGraph 运行时依赖。
- `AgentOrchestrationService`、`AgentEngineClient`、`HttpAgentEngineClient`、旧 `AgentTask`、`AgentEvent`、`ExternalCallLog` 和项目级 `WorkflowSnapshot` 服务。
- 前端 `api/v3`、`AgentTimeline`、`ExecutionEventList`、`PrdEditor` 和固定 `WorkflowGraph`。
- `scheme/init.sql` 及 `DB_SCHEMA_DIR`、`SQL_INIT_MODE`、`AGENT_ENGINE_BASE_URL` 等旧配置。

2026-09-30 经用户明确授权，旧 V1–V117 已合并为 `V1__autospec_baseline.sql`，本地保留最近五次运行及关联数据，完整旧库另有本地备份。该操作是一次基线重置，不是常规升级；不要恢复旧 SQL 到迁移目录。后续修改从 V2 开始，只新增迁移，已应用的基线不可改写。其他旧数据库升级前必须先备份、核对结构和保留范围，不能直接删除 Flyway 历史。

## Environment and Model Configuration

- `.env.example` 描述配置结构；真实值只保存在根目录未跟踪的 `.env`。
- 不得读取后直接回显 `.env` 全文。诊断时只报告密钥是缺失、占位符还是已配置。
- 本地 Compose 至少需要 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`AGENT_ENGINE_SERVICE_TOKEN`；启用演示登录还需要 `AUTH_DEMO_USER_PASSWORD`。
- 本地开发使用 `AUTOSPEC_ENV=development`、`AUTH_DEMO_USER_ENABLED=true` 和非 root 数据库用户 `MYSQL_USER=autospec`。
- `AGENT_MODEL_MODE=fixture` 不调用外部模型，适合测试和无 Key 启动。
- 当前 DeepSeek live 配置使用 `MODEL_BASE_URL=https://api.deepseek.com`，主模型、fast、deep 均为 `deepseek-flash`，`MODEL_PROVIDER_KEY=deepseek`。
- 只有 `MODEL_API_KEY` 已在本地安全配置后，才能切换为 `AGENT_MODEL_MODE=live`。不得生成、猜测、提交或在日志中打印真实模型 Key。
- 生产环境不得使用 fixture、演示用户、非 Secure Cookie、root 数据库用户、明文数据库/Redis 连接或空密钥。

## Build and Development Commands

完整产品优先从仓库根目录启动：

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose logs --tail=200 -f backend agent-worker-1 agent-worker-2
```

前端默认地址为 `http://localhost:5173`，后端 readiness 为 `http://localhost:8080/actuator/health/readiness`，Agent API health 为 `http://localhost:8000/health`。

后端本地运行：

```powershell
Set-Location 'D:\@Java\MetaGPT\backend'
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' spring-boot:run
```

Agent 依赖与测试：

```powershell
Set-Location 'D:\@Java\MetaGPT\agent-engine'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pip install -r requirements.txt
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q
```

前端本地运行：

```powershell
Set-Location 'D:\@Java\MetaGPT\frontend'
npm ci
npm run dev
```

停止 Compose 但保留数据使用 `docker compose down`。`docker compose down -v` 会删除 MySQL、Redis 和监控数据卷，只有用户明确要求彻底重置数据时才允许执行。

## Testing Guidelines

后端使用 JUnit/Spring Boot Test，Agent Engine 使用 pytest，前端使用 Vitest/React Testing Library。测试重点是结构化协议、状态流转、幂等、失败恢复、Reviewer 规则、Evaluator 门禁及跨 Artifact 一致性，不测试 LLM 文案是否“好看”。

- 不要在每次小修改后机械执行全量回归。日常修改只运行直接相关的测试文件、测试类或单模块构建。
- 跨模块 API/Schema 变更、依赖或数据库变更、里程碑收尾和发布前，才执行完整三端回归。
- 默认覆盖核心成功流程和确有现实风险的失败流程；不要穷举低价值输入排列、框架自身行为、getter/setter 或极难发生的组合边界。
- 权限、幂等、重复消息、数据损坏、预算、交付门禁、不可变历史和外部契约属于高风险边界，必须保留针对性测试。
- 新增或修改功能应提供最小回归测试或可复现样例；删除旧测试时要确认当前行为已有等价覆盖。

完整发布验证命令：

```powershell
Set-Location 'D:\@Java\MetaGPT\backend'
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' test

Set-Location 'D:\@Java\MetaGPT\agent-engine'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q

Set-Location 'D:\@Java\MetaGPT\frontend'
npm test
npm run build

Set-Location 'D:\@Java\MetaGPT'
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' scripts/verify_workflow_contract.py
docker compose config --quiet
docker compose --profile monitoring config --quiet
```

## Coding Style and Contracts

- Java 使用 4 空格缩进；包名小写，例如 `com.autospec`；类名 `PascalCase`，方法与字段 `camelCase`，常量 `UPPER_SNAKE_CASE`。
- Python 使用类型标注和小写下划线文件名。Prompt、Schema、Handler 和 Artifact 必须带可追踪版本。
- TypeScript 组件使用 `PascalCase`，页面放在 `frontend/src/pages`，共享请求定义放在 `frontend/src/api`。
- Agent 输出必须通过 Pydantic/JSON Schema 校验，不得只保存不可验证的大段自由文本。
- OpenAPI 或 WorkflowSpec 改动必须同步消费者、测试和文档，并运行 `scripts/verify_workflow_contract.py`。
- Flyway 迁移只增不改，禁止修改已经发布的版本号或历史 SQL。

## Git, Commit, and Pull Request Guidelines

Git 仓库当前可用。保留用户已有修改，不覆盖、不重置、不把无关文件混入提交。只有用户明确要求时才创建 commit 或执行远程 push。

采用 Conventional Commits，例如：`feat: add workflow approval`、`fix: reject stale worker event`、`refactor: consolidate autospec product`、`docs: update startup guide`、`test: cover evaluator delivery gate`。

PR 需包含变更摘要、测试命令与结果、相关 issue/任务、API/Schema/迁移影响；涉及前端页面时附截图，涉及 Agent 输出时附脱敏 Artifact 示例。

## Agent-Specific Instructions

- 不要把 LLM 当成黑盒文本生成器。每个节点的输入、输出、耗时、状态、错误、Prompt/Schema 版本、模型路由、Token、成本和引用来源都应进入持久化台账或结构化日志。
- Reviewer 必须执行“确定性规则检查 + 模型语义审查”两层校验，重点检查 PRD、架构、数据库、API、前端页面、权限和追踪 ID 的一致性。
- Evaluator 必须生成需求到故事/验收、API、数据和 UI 的追踪矩阵；缺失 MUST 覆盖或存在 HIGH/CRITICAL 问题时必须阻断完成与交付。
- 工作流顺序只能来自冻结的 WorkflowSpec；不要在 Java 或 Python 中重新硬编码固定节点顺序。
- Worker 与 Redis Streams 是正式节点执行链路。保持至少一次投递下的幂等、fencing token、心跳、超时、重试、死信和恢复语义。
- 除本文件明确要求的固定工具路径外，禁止提交 API Key、模型密钥、数据库密码、Redis 密码、服务 Token、真实用户数据或其他本机绝对路径。仅 `.env.example` 可记录无密钥的配置说明。
