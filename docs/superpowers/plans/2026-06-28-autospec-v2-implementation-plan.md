# AutoSpec V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build AutoSpec V2 on top of the V1 requirement-to-artifacts loop by adding architecture and frontend agents, PRD human review, prompt versioning, real-time execution events, retry/regeneration, and PDF export.

**Architecture:** V2 keeps Spring Boot as the system of record and Python FastAPI as the Agent execution boundary. The backend owns artifact versioning, prompt version records, human approval state, Agent task/event persistence, export, and retry orchestration. The Agent engine adds typed V2 schemas and node-level runners so the backend can run full workflows or rerun a single failed node without losing observability.

**Tech Stack:** Spring Boot, MyBatis-Plus, MySQL, Flyway, Redis, FastAPI, LangGraph, Pydantic, React, TypeScript, Ant Design, Vitest, JUnit, pytest, PDFBox.

---

## Scope

V2 delivers seven capabilities:

- `V2-01` Architect Agent produces architecture design artifacts.
- `V2-02` Frontend Agent produces React page skeleton artifacts.
- `V2-03` Human-in-the-loop PRD editing gates downstream generation on approved PRD content.
- `V2-04` SSE streaming shows Agent task/event progress without polling as the primary path.
- `V2-05` PDF export covers the same artifact set as Markdown export.
- `V2-06` Prompt version management makes Agent output traceable to prompt content.
- `V2-07` Failed-node retry and local regeneration reruns from a selected node with persisted inputs.

V2 excludes login, full permission model, RAG, ZIP code export, multi-model routing, and visual LangGraph editing. Those remain V3 scope.

## Current V1 Baseline

- Backend persists `project`, `agent_task`, `artifact`, and `review_issue` through `scheme/init.sql`.
- Backend `POST /api/projects/{projectId}/generate` calls `AgentEngineClient.generate(requirement)` synchronously.
- Backend stores `PRD`, `BACKEND_DESIGN`, and `REVIEW_REPORT` artifacts and review issues.
- Agent engine runs `product_manager -> backend_engineer -> reviewer`.
- Frontend creates a project, triggers generation, polls progress, previews artifacts, shows review issues, and exports Markdown.

## Target File Structure

### Backend Persistence

- Modify: `backend/pom.xml` for Flyway and PDFBox dependencies.
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`.
- Create: `backend/src/main/resources/db/migration/V2__autospec_v2_extensions.sql`.
- Modify: `scheme/init.sql` to match the latest local bootstrap schema.
- Modify: `backend/src/main/resources/application.yml`.
- Modify: `backend/src/test/resources/application-test.yml`.
- Modify: `backend/src/main/java/com/autospec/entity/Artifact.java`.
- Modify: `backend/src/main/java/com/autospec/entity/AgentTask.java`.
- Create: `backend/src/main/java/com/autospec/entity/PromptVersion.java`.
- Create: `backend/src/main/java/com/autospec/entity/AgentEvent.java`.
- Create: `backend/src/main/java/com/autospec/mapper/PromptVersionMapper.java`.
- Create: `backend/src/main/java/com/autospec/mapper/AgentEventMapper.java`.
- Create: `backend/src/main/java/com/autospec/service/PromptVersionService.java`.
- Create: `backend/src/main/java/com/autospec/service/AgentEventService.java`.
- Create: `backend/src/main/java/com/autospec/service/impl/PromptVersionServiceImpl.java`.
- Create: `backend/src/main/java/com/autospec/service/impl/AgentEventServiceImpl.java`.
- Create: `backend/src/main/java/com/autospec/service/PromptRegistryService.java`.
- Create: `backend/src/main/java/com/autospec/service/ArtifactVersionService.java`.
- Create: `backend/src/main/java/com/autospec/service/AgentEventStreamService.java`.

### Backend API and Export

- Modify: `backend/src/main/java/com/autospec/service/AgentEngineClient.java`.
- Modify: `backend/src/main/java/com/autospec/service/HttpAgentEngineClient.java`.
- Modify: `backend/src/main/java/com/autospec/service/AgentGenerationResult.java`.
- Modify: `backend/src/main/java/com/autospec/service/AgentEngineExecutionRecord.java`.
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`.
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`.
- Modify: `backend/src/main/java/com/autospec/controller/ExportController.java`.
- Create: `backend/src/main/java/com/autospec/service/PdfExportService.java`.
- Modify: `backend/src/main/java/com/autospec/dto/ExportResponse.java`.
- Create: `backend/src/main/java/com/autospec/dto/UpdateArtifactRequest.java`.
- Create: `backend/src/main/java/com/autospec/dto/ApproveArtifactResponse.java`.
- Create: `backend/src/main/java/com/autospec/dto/AgentEventResponse.java`.
- Create: `backend/src/main/java/com/autospec/dto/RetryTaskResponse.java`.

### Agent Engine

- Create: `agent-engine/schemas/architecture_design.py`.
- Create: `agent-engine/schemas/frontend_skeleton.py`.
- Modify: `agent-engine/schemas/review.py`.
- Create: `agent-engine/agents/architect.py`.
- Create: `agent-engine/agents/frontend_engineer.py`.
- Create: `agent-engine/prompts/architect_v1.md`.
- Create: `agent-engine/prompts/frontend_engineer_v1.md`.
- Modify: `agent-engine/agents/reviewer.py`.
- Modify: `agent-engine/graph/workflow.py`.
- Modify: `agent-engine/main.py`.

### Frontend

- Modify: `frontend/src/api/projects.ts`.
- Modify: `frontend/src/pages/HomePage.tsx`.
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`.
- Modify: `frontend/src/components/ArtifactTabs.tsx`.
- Modify: `frontend/src/components/AgentTimeline.tsx`.
- Create: `frontend/src/components/PrdEditor.tsx`.
- Create: `frontend/src/components/FrontendSkeletonPreview.tsx`.
- Create: `frontend/src/components/ExecutionEventList.tsx`.

---

## Task 1: Schema Migration and V2 Persistence Foundation

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__autospec_v2_extensions.sql`
- Modify: `scheme/init.sql`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Modify: `backend/src/test/java/com/autospec/SchemaInitSqlTest.java`
- Modify: `backend/src/test/java/com/autospec/CoreDataModelTest.java`
- Modify: `backend/src/main/java/com/autospec/entity/Artifact.java`
- Modify: `backend/src/main/java/com/autospec/entity/AgentTask.java`
- Create: `backend/src/main/java/com/autospec/entity/PromptVersion.java`
- Create: `backend/src/main/java/com/autospec/entity/AgentEvent.java`
- Create: mapper and service classes for `PromptVersion` and `AgentEvent`

- [ ] **Step 1: Write the failing Flyway schema test**

Replace the current `SchemaInitSqlTest` body with a migration-based test:

```java
@Test
void flywayMigrationsCreateV2TablesAndColumns() throws Exception {
    try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:v2_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa",
            "")) {
        Flyway.configure()
                .dataSource(() -> connection)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(tableNames(connection))
                .contains("project", "agent_task", "artifact", "review_issue", "prompt_version", "agent_event");
        assertThat(columnNames(connection, "artifact"))
                .contains("status", "source_agent", "parent_artifact_id", "approved_at", "updated_at");
        assertThat(columnNames(connection, "agent_task"))
                .contains("node_name", "duration_ms", "retry_of_task_id", "prompt_version_id");
    }
}
```

Add this helper:

```java
private Set<String> columnNames(Connection connection, String tableName) throws Exception {
    try (ResultSet resultSet = connection.getMetaData().getColumns(null, null, tableName, "%")) {
        Set<String> names = new java.util.HashSet<>();
        while (resultSet.next()) {
            names.add(resultSet.getString("COLUMN_NAME").toLowerCase());
        }
        return names;
    }
}
```

- [ ] **Step 2: Run the failing schema test**

Run: `cd backend && mvn -Dtest=SchemaInitSqlTest test`

Expected: FAIL because Flyway and V2 migrations are not present.

- [ ] **Step 3: Add migration and PDF dependencies**

Add these dependencies to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.3</version>
</dependency>
```

- [ ] **Step 4: Create migrations**

Copy the current four-table schema into `V1__init_schema.sql`. Create `V2__autospec_v2_extensions.sql`:

```sql
alter table artifact add column status varchar(32) not null default 'GENERATED';
alter table artifact add column source_agent varchar(128) null;
alter table artifact add column parent_artifact_id bigint null;
alter table artifact add column approved_at timestamp null;
alter table artifact add column updated_at timestamp not null default current_timestamp;
alter table artifact add index idx_artifact_status (status);

alter table agent_task add column node_name varchar(128) null;
alter table agent_task add column duration_ms int null;
alter table agent_task add column retry_of_task_id bigint null;
alter table agent_task add column prompt_version_id bigint null;
alter table agent_task add index idx_agent_task_node_name (node_name);

create table if not exists prompt_version (
    id bigint primary key auto_increment,
    prompt_key varchar(128) not null,
    version varchar(32) not null,
    content longtext not null,
    checksum varchar(128) not null,
    active tinyint(1) not null default 1,
    created_at timestamp not null default current_timestamp,
    unique key uk_prompt_version (prompt_key, version),
    index idx_prompt_version_active (prompt_key, active)
);

create table if not exists agent_event (
    id bigint primary key auto_increment,
    project_id bigint not null,
    task_id bigint null,
    event_type varchar(64) not null,
    node_name varchar(128) not null,
    message varchar(512) not null,
    payload longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_agent_event_project foreign key (project_id) references project (id),
    index idx_agent_event_project_id (project_id),
    index idx_agent_event_created_at (created_at)
);
```

- [ ] **Step 5: Update Spring configuration**

In `application.yml`, set:

```yaml
spring:
  sql:
    init:
      mode: never
  flyway:
    enabled: true
    locations: classpath:db/migration
```

In `application-test.yml`, keep the H2 datasource and set:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- [ ] **Step 6: Add entity fields**

Add to `Artifact`:

```java
private String status;
private String sourceAgent;
private Long parentArtifactId;
private LocalDateTime approvedAt;
private LocalDateTime updatedAt;
```

Add to `AgentTask`:

```java
private String nodeName;
private Integer durationMs;
private Long retryOfTaskId;
private Long promptVersionId;
```

- [ ] **Step 7: Add new entities and services**

Create `PromptVersion`:

```java
@Data
@TableName("prompt_version")
public class PromptVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String promptKey;
    private String version;
    private String content;
    private String checksum;
    private Boolean active;
    private LocalDateTime createdAt;
}
```

Create `AgentEvent`:

```java
@Data
@TableName("agent_event")
public class AgentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long taskId;
    private String eventType;
    private String nodeName;
    private String message;
    private String payload;
    private LocalDateTime createdAt;
}
```

Use the existing `ServiceImpl<Mapper, Entity>` pattern for mappers and services.

- [ ] **Step 8: Extend core data model test**

Add persistence checks for prompt versions, Agent events, and artifact review status:

```java
PromptVersion prompt = new PromptVersion();
prompt.setPromptKey("ProductManagerAgent");
prompt.setVersion("v2");
prompt.setContent("Generate structured PRD JSON.");
prompt.setChecksum("sha256:test");
prompt.setActive(true);
assertThat(promptVersionService.save(prompt)).isTrue();

AgentEvent event = new AgentEvent();
event.setProjectId(project.getId());
event.setEventType("NODE_SUCCEEDED");
event.setNodeName("product_manager");
event.setMessage("Product Manager completed.");
event.setPayload("{\"agent\":\"ProductManagerAgent_v2\"}");
assertThat(agentEventService.save(event)).isTrue();
```

- [ ] **Step 9: Run backend persistence verification**

Run: `cd backend && mvn -Dtest=SchemaInitSqlTest,CoreDataModelTest test`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/pom.xml backend/src/main/resources backend/src/main/java/com/autospec scheme/init.sql backend/src/test
git commit -m "feat: add v2 persistence foundation"
```

---

## Task 2: Prompt Version Management

**Files:**
- Create: `backend/src/main/java/com/autospec/service/PromptRegistryService.java`
- Create: `backend/src/main/java/com/autospec/dto/PromptVersionResponse.java`
- Create: `backend/src/main/java/com/autospec/controller/PromptVersionController.java`
- Create: `backend/src/test/java/com/autospec/PromptRegistryServiceTest.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`

- [ ] **Step 1: Write failing prompt registry tests**

```java
@Test
void registersPromptVersionWithStableChecksumAndSingleActiveVersion() {
    PromptVersion first = promptRegistryService.registerActive(
            "ProductManagerAgent", "v2", "prompt text v2");
    PromptVersion second = promptRegistryService.registerActive(
            "ProductManagerAgent", "v3", "prompt text v3");

    assertThat(first.getChecksum()).startsWith("sha256:");
    assertThat(promptVersionService.getById(first.getId()).getActive()).isFalse();
    assertThat(promptVersionService.getById(second.getId()).getActive()).isTrue();
}

@Test
void returnsActivePromptByKey() {
    promptRegistryService.registerActive("ArchitectAgent", "v1", "architecture prompt");

    PromptVersion active = promptRegistryService.activePrompt("ArchitectAgent");

    assertThat(active.getVersion()).isEqualTo("v1");
    assertThat(active.getContent()).isEqualTo("architecture prompt");
}
```

- [ ] **Step 2: Run failing prompt tests**

Run: `cd backend && mvn -Dtest=PromptRegistryServiceTest test`

Expected: FAIL because `PromptRegistryService` is missing.

- [ ] **Step 3: Implement prompt registry**

```java
@Transactional
public PromptVersion registerActive(String promptKey, String version, String content) {
    promptVersionService.lambdaUpdate()
            .eq(PromptVersion::getPromptKey, promptKey)
            .set(PromptVersion::getActive, false)
            .update();
    PromptVersion prompt = new PromptVersion();
    prompt.setPromptKey(promptKey);
    prompt.setVersion(version);
    prompt.setContent(content);
    prompt.setChecksum("sha256:" + sha256Hex(content));
    prompt.setActive(true);
    promptVersionService.save(prompt);
    return prompt;
}
```

Use Java `MessageDigest` for `sha256Hex`.

- [ ] **Step 4: Add active prompt API**

```java
@GetMapping("/active")
public List<PromptVersionResponse> activePrompts() {
    return promptVersionService.lambdaQuery()
            .eq(PromptVersion::getActive, true)
            .orderByAsc(PromptVersion::getPromptKey)
            .list()
            .stream()
            .map(PromptVersionResponse::from)
            .toList();
}
```

- [ ] **Step 5: Link Agent tasks to prompt versions**

When recording an Agent task, resolve the active prompt by Agent key and store `promptVersionId`. Keep `promptVersionId = null` when a matching prompt row is absent so V1 compatibility is preserved.

- [ ] **Step 6: Run prompt tests**

Run: `cd backend && mvn -Dtest=PromptRegistryServiceTest,ProjectControllerTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: add prompt version registry"
```

---

## Task 3: Agent Engine V2 Schemas

**Files:**
- Create: `agent-engine/schemas/architecture_design.py`
- Create: `agent-engine/schemas/frontend_skeleton.py`
- Modify: `agent-engine/schemas/__init__.py`
- Modify: `agent-engine/tests/test_schemas.py`

- [ ] **Step 1: Write failing schema tests**

```python
def test_architecture_design_schema_accepts_modules_and_decisions():
    artifact = ArchitectureDesignArtifact.model_validate({
        "system_context": "AutoSpec coordinates backend, frontend, and agent-engine services.",
        "modules": [{
            "name": "backend",
            "responsibility": "Persist projects, artifacts, events, and exports.",
            "depends_on": ["agent-engine", "mysql", "redis"]
        }],
        "decisions": [{
            "title": "Backend owns artifact state",
            "context": "Human approval and retry require durable state.",
            "decision": "Spring Boot stores artifact versions and approval state.",
            "consequences": ["Agent engine remains stateless between calls."]
        }],
        "non_functional_constraints": [{
            "category": "observability",
            "requirement": "Every Agent node emits persisted events."
        }],
        "integration_risks": ["SSE connections can drop and recover from persisted events."]
    })

    assert artifact.modules[0].name == "backend"
```

```python
def test_frontend_skeleton_schema_accepts_routes_pages_components_and_api_bindings():
    artifact = FrontendSkeletonArtifact.model_validate({
        "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
        "pages": [{
            "name": "ProjectDetailPage",
            "purpose": "Review PRD, monitor generation, and inspect artifacts.",
            "components": ["PrdEditor", "AgentTimeline", "ArtifactTabs"]
        }],
        "components": [{
            "name": "PrdEditor",
            "type": "form",
            "props": ["artifact", "onSave", "onApprove"],
            "state": ["draftContent", "saving"]
        }],
        "api_bindings": [{
            "method": "POST",
            "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
            "consumer": "PrdEditor"
        }]
    })

    assert artifact.api_bindings[0].consumer == "PrdEditor"
```

- [ ] **Step 2: Run failing schema tests**

Run: `cd agent-engine && pytest tests/test_schemas.py -q`

Expected: FAIL because V2 schemas are missing.

- [ ] **Step 3: Implement architecture schema**

```python
from pydantic import BaseModel, ConfigDict, Field


class ModuleDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    responsibility: str = Field(min_length=1)
    depends_on: list[str] = Field(default_factory=list)


class DecisionRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1)
    context: str = Field(min_length=1)
    decision: str = Field(min_length=1)
    consequences: list[str] = Field(default_factory=list)


class NonFunctionalConstraint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str = Field(min_length=1)
    requirement: str = Field(min_length=1)


class ArchitectureDesignArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    system_context: str = Field(min_length=1)
    modules: list[ModuleDesign] = Field(min_length=1)
    decisions: list[DecisionRecord] = Field(default_factory=list)
    non_functional_constraints: list[NonFunctionalConstraint] = Field(default_factory=list)
    integration_risks: list[str] = Field(default_factory=list)
```

- [ ] **Step 4: Implement frontend skeleton schema**

```python
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class RouteDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path: str = Field(pattern=r"^/.*")
    page: str = Field(min_length=1)


class PageDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    purpose: str = Field(min_length=1)
    components: list[str] = Field(default_factory=list)


class ComponentDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    type: Literal["form", "table", "timeline", "tabs", "preview", "toolbar", "layout"]
    props: list[str] = Field(default_factory=list)
    state: list[str] = Field(default_factory=list)


class ApiBinding(BaseModel):
    model_config = ConfigDict(extra="forbid")

    method: str = Field(min_length=1)
    path: str = Field(pattern=r"^/.*")
    consumer: str = Field(min_length=1)


class FrontendSkeletonArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    routes: list[RouteDesign] = Field(min_length=1)
    pages: list[PageDesign] = Field(min_length=1)
    components: list[ComponentDesign] = Field(min_length=1)
    api_bindings: list[ApiBinding] = Field(default_factory=list)
```

- [ ] **Step 5: Run schema tests**

Run: `cd agent-engine && pytest tests/test_schemas.py -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-engine/schemas agent-engine/tests/test_schemas.py
git commit -m "feat: add v2 artifact schemas"
```

---

## Task 4: Agent Engine V2 Workflow and Node Runner

**Files:**
- Create: `agent-engine/agents/architect.py`
- Create: `agent-engine/agents/frontend_engineer.py`
- Create: `agent-engine/prompts/architect_v1.md`
- Create: `agent-engine/prompts/frontend_engineer_v1.md`
- Modify: `agent-engine/agents/reviewer.py`
- Modify: `agent-engine/graph/workflow.py`
- Modify: `agent-engine/main.py`
- Modify: `agent-engine/tests/test_workflow.py`

- [ ] **Step 1: Write failing V2 workflow test**

```python
def test_v2_workflow_runs_architect_frontend_and_reviewer_after_prd():
    records = []
    model_client = V2FakeModelClient()

    result = run_v2_workflow(
        "Build AutoSpec V2.",
        model_client=model_client,
        callbacks=[records.append],
    )

    assert [call[0] for call in model_client.calls] == [
        "ProductManagerAgent_v1",
        "ArchitectAgent_v1",
        "BackendEngineerAgent_v1",
        "FrontendEngineerAgent_v1",
        "ReviewerAgent_v1",
    ]
    assert result.architecture_design.modules[0].name == "backend"
    assert result.frontend_skeleton.routes[0].path == "/projects/:projectId"
    assert [record.node_name for record in records] == [
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
    ]
```

- [ ] **Step 2: Write failing node runner test**

```python
def test_run_v2_node_runs_single_frontend_engineer_node():
    model_client = V2FakeModelClient()
    payload = {
        "requirement": "Build AutoSpec V2.",
        "prd": valid_prd_payload(),
        "architecture_design": valid_architecture_payload(),
        "backend_design": valid_backend_payload(),
    }

    result = run_v2_node("frontend_engineer", payload, model_client=model_client)

    assert result.node_name == "frontend_engineer"
    assert result.output_payload["routes"][0]["page"] == "ProjectDetailPage"
```

- [ ] **Step 3: Run failing workflow tests**

Run: `cd agent-engine && pytest tests/test_workflow.py -q`

Expected: FAIL because V2 workflow and node runner are missing.

- [ ] **Step 4: Implement Architect Agent**

Create `architect.py` with:

```python
class ArchitectAgent:
    prompt_name = "ArchitectAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(self, requirement: str, prd: PrdArtifact) -> ArchitectureDesignArtifact:
        input_payload = {"requirement": requirement, "prd": prd.model_dump()}
        if self.model_client is not None:
            return ArchitectureDesignArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )
        return ArchitectureDesignArtifact.model_validate({
            "system_context": "AutoSpec coordinates frontend, backend, and agent-engine services.",
            "modules": [
                {
                    "name": "backend",
                    "responsibility": "Persist projects, artifacts, Agent tasks, events, and exports.",
                    "depends_on": ["agent-engine", "mysql", "redis"],
                },
                {
                    "name": "agent-engine",
                    "responsibility": "Run typed Agent nodes and return structured artifacts.",
                    "depends_on": [],
                },
            ],
            "decisions": [
                {
                    "title": "Backend owns workflow state",
                    "context": "PRD approval and retry require durable state.",
                    "decision": "Store artifact versions and task events in Spring Boot.",
                    "consequences": ["Agent engine can remain stateless between requests."],
                }
            ],
            "non_functional_constraints": [
                {
                    "category": "observability",
                    "requirement": "Every Agent node records input, output, duration, status, and errors.",
                }
            ],
            "integration_risks": ["Dropped SSE connections must recover from persisted event history."],
        })
```

- [ ] **Step 5: Implement Frontend Agent**

Create `frontend_engineer.py` with:

```python
class FrontendEngineerAgent:
    prompt_name = "FrontendEngineerAgent_v1"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(self, requirement, prd, architecture_design, backend_design) -> FrontendSkeletonArtifact:
        input_payload = {
            "requirement": requirement,
            "prd": prd.model_dump(),
            "architecture_design": architecture_design.model_dump(),
            "backend_design": backend_design.model_dump(),
        }
        if self.model_client is not None:
            return FrontendSkeletonArtifact.model_validate(
                self.model_client.generate_json(self.prompt_name, input_payload)
            )
        return FrontendSkeletonArtifact.model_validate({
            "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
            "pages": [{
                "name": "ProjectDetailPage",
                "purpose": "Approve PRD, monitor Agent execution, and inspect generated artifacts.",
                "components": ["PrdEditor", "AgentTimeline", "ArtifactTabs", "ReviewIssueTable"],
            }],
            "components": [
                {
                    "name": "PrdEditor",
                    "type": "form",
                    "props": ["artifact", "onSave", "onApprove"],
                    "state": ["draftContent", "saving"],
                },
                {
                    "name": "AgentTimeline",
                    "type": "timeline",
                    "props": ["progress", "events"],
                    "state": [],
                },
            ],
            "api_bindings": [
                {
                    "method": "POST",
                    "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                    "consumer": "PrdEditor",
                }
            ],
        })
```

- [ ] **Step 6: Add workflow result and node runner**

Add `WorkflowV2Result` with `prd`, `architecture_design`, `backend_design`, `frontend_skeleton`, `review_report`, and `records`. Add:

```python
SUPPORTED_V2_NODES = {
    "product_manager",
    "architect",
    "backend_engineer",
    "frontend_engineer",
    "reviewer",
}
```

`run_v2_node(node_name, payload, model_client=None)` returns one `AgentExecutionRecord`.

- [ ] **Step 7: Add FastAPI endpoints**

```python
@app.post("/generate/v2")
def generate_v2(request: GenerateRequest) -> dict:
    result = run_v2_workflow(request.requirement)
    return v2_response(result)

@app.post("/nodes/{node_name}/run")
def run_node(node_name: str, payload: dict) -> dict:
    record = run_v2_node(node_name, payload)
    return record.__dict__
```

- [ ] **Step 8: Run Agent tests**

Run: `cd agent-engine && pytest -q`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add agent-engine
git commit -m "feat: add v2 agent workflow"
```

---

## Task 5: PRD Review Gate and Artifact Versioning

**Files:**
- Create: `backend/src/main/java/com/autospec/service/ArtifactVersionService.java`
- Create: `backend/src/main/java/com/autospec/dto/UpdateArtifactRequest.java`
- Create: `backend/src/main/java/com/autospec/dto/ApproveArtifactResponse.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`

- [ ] **Step 1: Write failing PRD gate test**

```java
@Test
void prdGenerationCreatesPendingReviewArtifactAndApprovalEnablesContinuation() throws Exception {
    when(agentEngineClient.generatePrd(contains("AutoSpec V2")))
            .thenReturn(agentResultWithOnlyPrd());

    long projectId = createProject("AutoSpec V2", "Build AutoSpec V2");

    mockMvc.perform(post("/api/projects/{projectId}/generate-prd", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PRD_REVIEW"));

    String artifactsJson = mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("PRD"))
            .andExpect(jsonPath("$[0].version").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long artifactId = objectMapper.readTree(artifactsJson).get(0).get("id").asLong();

    mockMvc.perform(post("/api/projects/{projectId}/artifacts/{artifactId}/approve", projectId, artifactId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
}
```

- [ ] **Step 2: Run failing PRD gate test**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: FAIL because V2 endpoints do not exist.

- [ ] **Step 3: Implement artifact edit and approve rules**

```java
@Transactional
public Artifact updateDraft(Long projectId, Long artifactId, String content) {
    Artifact current = requireProjectArtifact(projectId, artifactId);
    Artifact next = new Artifact();
    next.setProjectId(projectId);
    next.setType(current.getType());
    next.setTitle(current.getTitle());
    next.setContent(content);
    next.setFormat(current.getFormat());
    next.setVersion(current.getVersion() + 1);
    next.setStatus("PENDING_REVIEW");
    next.setSourceAgent("HUMAN_EDITOR");
    next.setParentArtifactId(current.getId());
    artifactService.save(next);
    return next;
}
```

`approve(projectId, artifactId)` sets `status = "APPROVED"` and `approvedAt = LocalDateTime.now()`.

- [ ] **Step 4: Add PRD-only generation endpoint**

`POST /api/projects/{projectId}/generate-prd` calls `agentEngineClient.generatePrd(requirement)`, saves `PRD` with `PENDING_REVIEW`, sets project status to `PRD_REVIEW`, and records a `NODE_SUCCEEDED` event.

- [ ] **Step 5: Add artifact update and approve endpoints**

```java
@PutMapping("/{projectId}/artifacts/{artifactId}")
public ArtifactResponse updateArtifact(
        @PathVariable Long projectId,
        @PathVariable Long artifactId,
        @Valid @RequestBody UpdateArtifactRequest request) {
    return ArtifactResponse.from(artifactVersionService.updateDraft(projectId, artifactId, request.content()));
}

@PostMapping("/{projectId}/artifacts/{artifactId}/approve")
public ApproveArtifactResponse approveArtifact(@PathVariable Long projectId, @PathVariable Long artifactId) {
    Artifact approved = artifactVersionService.approve(projectId, artifactId);
    return new ApproveArtifactResponse(approved.getId(), approved.getStatus(), approved.getVersion());
}
```

- [ ] **Step 6: Run backend tests**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: add prd approval gate"
```

---

## Task 6: V2 Backend Orchestration and Artifact Persistence

**Files:**
- Modify: `backend/src/main/java/com/autospec/service/AgentEngineClient.java`
- Modify: `backend/src/main/java/com/autospec/service/HttpAgentEngineClient.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentGenerationResult.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentEngineExecutionRecord.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`

- [ ] **Step 1: Write failing continuation test**

```java
@Test
void continueAfterApprovedPrdPersistsArchitectureFrontendBackendAndReviewArtifacts() throws Exception {
    long projectId = createProject("AutoSpec V2", "Build AutoSpec V2");
    saveApprovedPrd(projectId);
    when(agentEngineClient.continueAfterPrd(eq("Build AutoSpec V2"), anyString()))
            .thenReturn(v2ContinuationResult());

    mockMvc.perform(post("/api/projects/{projectId}/continue", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.percent").value(100));

    mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("PRD"))
            .andExpect(jsonPath("$[1].type").value("ARCHITECTURE_DESIGN"))
            .andExpect(jsonPath("$[2].type").value("BACKEND_DESIGN"))
            .andExpect(jsonPath("$[3].type").value("FRONTEND_SKELETON"))
            .andExpect(jsonPath("$[4].type").value("REVIEW_REPORT"));
}
```

- [ ] **Step 2: Run failing continuation test**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: FAIL because continuation is missing.

- [ ] **Step 3: Extend Agent engine client contract**

```java
public interface AgentEngineClient {
    AgentGenerationResult generate(String requirement);
    AgentGenerationResult generatePrd(String requirement);
    AgentGenerationResult continueAfterPrd(String requirement, String approvedPrdJson);
    AgentEngineExecutionRecord runNode(String nodeName, String inputJson);
}
```

- [ ] **Step 4: Extend generation result**

```java
public record AgentGenerationResult(
        String prdJson,
        String architectureDesignJson,
        String backendDesignJson,
        String frontendSkeletonJson,
        String reviewReportJson,
        List<AgentEngineExecutionRecord> records
) {
}
```

Keep a compatibility constructor for V1 tests that pass only PRD, backend design, review report, and records.

- [ ] **Step 5: Save V2 artifacts in deterministic order**

```java
Artifact approvedPrd = artifactVersionService.latestApproved(projectId, "PRD");
AgentGenerationResult result = agentEngineClient.continueAfterPrd(
        project.getOriginalRequirement(),
        approvedPrd.getContent());
saveArtifact(projectId, "ARCHITECTURE_DESIGN", project.getName() + " Architecture Design", result.architectureDesignJson(), "ArchitectAgent_v1");
saveArtifact(projectId, "BACKEND_DESIGN", project.getName() + " Backend Design", result.backendDesignJson(), "BackendEngineerAgent_v1");
saveArtifact(projectId, "FRONTEND_SKELETON", project.getName() + " Frontend Skeleton", result.frontendSkeletonJson(), "FrontendEngineerAgent_v1");
saveArtifact(projectId, "REVIEW_REPORT", project.getName() + " Review Report", result.reviewReportJson(), "ReviewerAgent_v1");
saveReviewIssues(projectId, result.reviewReportJson());
project.setStatus("COMPLETED");
projectService.updateById(project);
```

- [ ] **Step 6: Emit node events**

For each `AgentEngineExecutionRecord`, persist events through `AgentEventService`. Store the task id in `agent_event.task_id` after saving `AgentTask`.

- [ ] **Step 7: Run backend controller tests**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: persist v2 generation artifacts"
```

---

## Task 7: V2 Reviewer Coverage Rules

**Files:**
- Modify: `agent-engine/agents/reviewer.py`
- Modify: `agent-engine/review/rules.py`
- Modify: `agent-engine/tests/test_review_rules.py`
- Modify: `agent-engine/tests/test_workflow.py`

- [ ] **Step 1: Write failing V2 reviewer tests**

```python
def test_v2_reviewer_detects_frontend_missing_prd_edit_api_binding():
    prd = _prd_with_features("PRD editing")
    architecture = _architecture_with_modules("backend", "frontend")
    backend_design = _backend_with_api("POST", "/api/projects/{projectId}/artifacts/{artifactId}/approve")
    frontend = _frontend_without_api_binding("/api/projects/{projectId}/artifacts/{artifactId}/approve")

    issues = run_v2_rule_checks(prd, architecture, backend_design, frontend)

    assert [issue.issue_type for issue in issues] == ["FRONTEND_API_COVERAGE"]
```

```python
def test_v2_reviewer_detects_architecture_missing_agent_event_storage():
    prd = _prd_with_features("real-time progress")
    architecture = _architecture_without_text("event")
    backend_design = _backend_with_api("GET", "/api/projects/{projectId}/events")
    frontend = _frontend_with_event_stream()

    issues = run_v2_rule_checks(prd, architecture, backend_design, frontend)

    assert "event" in issues[0].description.lower()
```

- [ ] **Step 2: Run failing reviewer tests**

Run: `cd agent-engine && pytest tests/test_review_rules.py -q`

Expected: FAIL because V2 rule checks are missing.

- [ ] **Step 3: Implement V2 rule checks**

Add `run_v2_rule_checks(prd, architecture_design, backend_design, frontend_skeleton)` that combines V1 rules with:

```python
if _mentions_any(prd_text, ["prd edit", "prd editing", "人工编辑", "编辑确认"]):
    _require_api_binding(frontend_skeleton, "/api/projects/{projectId}/artifacts/{artifactId}/approve")

if _mentions_any(prd_text, ["real-time", "stream", "sse", "websocket", "实时"]):
    _require_backend_api(backend_design, "/api/projects/{projectId}/events")
    _require_architecture_text(architecture_design, "event")

if _mentions_any(prd_text, ["retry", "regeneration", "重试", "重新生成"]):
    _require_backend_api(backend_design, "/api/projects/{projectId}/tasks/{taskId}/retry")
```

- [ ] **Step 4: Update Reviewer Agent V2 path**

When architecture and frontend artifacts are provided, call `run_v2_rule_checks`. Otherwise keep V1 behavior.

- [ ] **Step 5: Run Agent tests**

Run: `cd agent-engine && pytest -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-engine/agents agent-engine/review agent-engine/tests
git commit -m "feat: extend reviewer for v2 consistency"
```

---

## Task 8: SSE Event Streaming

**Files:**
- Create: `backend/src/main/java/com/autospec/service/AgentEventStreamService.java`
- Create: `backend/src/main/java/com/autospec/dto/AgentEventResponse.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`
- Modify: `frontend/src/api/projects.ts`
- Create: `frontend/src/components/ExecutionEventList.tsx`

- [ ] **Step 1: Write failing event history test**

```java
@Test
void eventEndpointReturnsPersistedEventsInOrder() throws Exception {
    long projectId = createProject("AutoSpec V2", "Build AutoSpec V2");
    agentEventService.record(projectId, null, "NODE_STARTED", "architect", "Architect started.", "{}");
    agentEventService.record(projectId, null, "NODE_SUCCEEDED", "architect", "Architect completed.", "{}");

    mockMvc.perform(get("/api/projects/{projectId}/events/history", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventType").value("NODE_STARTED"))
            .andExpect(jsonPath("$[1].eventType").value("NODE_SUCCEEDED"));
}
```

- [ ] **Step 2: Run failing event test**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: FAIL because event history endpoint is missing.

- [ ] **Step 3: Implement event recording**

```java
public AgentEvent record(Long projectId, Long taskId, String eventType, String nodeName, String message, String payload) {
    AgentEvent event = new AgentEvent();
    event.setProjectId(projectId);
    event.setTaskId(taskId);
    event.setEventType(eventType);
    event.setNodeName(nodeName);
    event.setMessage(message);
    event.setPayload(payload);
    save(event);
    streamService.publish(event);
    return event;
}
```

- [ ] **Step 4: Implement SSE stream service**

```java
public SseEmitter subscribe(Long projectId) {
    SseEmitter emitter = new SseEmitter(0L);
    emitters.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(projectId, emitter));
    emitter.onTimeout(() -> remove(projectId, emitter));
    return emitter;
}
```

`publish(event)` sends `AgentEventResponse.from(event)` to active emitters and removes emitters that throw `IOException`.

- [ ] **Step 5: Add event endpoints**

```java
@GetMapping("/{projectId}/events/history")
public List<AgentEventResponse> eventHistory(@PathVariable Long projectId) {
    return agentEventService.lambdaQuery()
            .eq(AgentEvent::getProjectId, projectId)
            .orderByAsc(AgentEvent::getId)
            .list()
            .stream()
            .map(AgentEventResponse::from)
            .toList();
}

@GetMapping(value = "/{projectId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamEvents(@PathVariable Long projectId) {
    return agentEventStreamService.subscribe(projectId);
}
```

- [ ] **Step 6: Add frontend EventSource client**

```ts
export function subscribeProjectEvents(projectId: number, onEvent: (event: AgentEventResponse) => void): EventSource {
  const source = new EventSource(`/api/projects/${projectId}/events`);
  source.onmessage = (message) => onEvent(JSON.parse(message.data) as AgentEventResponse);
  return source;
}
```

- [ ] **Step 7: Run event tests**

Run:

```bash
cd backend && mvn -Dtest=ProjectControllerTest test
cd frontend && npm run test -- projects.test.ts
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec frontend/src
git commit -m "feat: stream agent execution events"
```

---

## Task 9: Failed-Node Retry and Local Regeneration

**Files:**
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Create: `backend/src/main/java/com/autospec/dto/RetryTaskResponse.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`
- Modify: `agent-engine/main.py`
- Modify: `agent-engine/tests/test_workflow.py`
- Modify: `frontend/src/api/projects.ts`
- Modify: `frontend/src/components/AgentTimeline.tsx`

- [ ] **Step 1: Write failing retry test**

```java
@Test
void retryFailedTaskUsesPersistedInputAndCreatesRetryTask() throws Exception {
    long projectId = createProject("AutoSpec V2", "Build AutoSpec V2");
    AgentTask failed = saveFailedTask(projectId, "frontend_engineer", "{\"requirement\":\"Build AutoSpec V2\"}");
    when(agentEngineClient.runNode(eq("frontend_engineer"), anyString()))
            .thenReturn(successfulNodeRecord("frontend_engineer"));

    mockMvc.perform(post("/api/projects/{projectId}/tasks/{taskId}/retry", projectId, failed.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.retryOfTaskId").value(failed.getId()));
}
```

- [ ] **Step 2: Run failing retry test**

Run: `cd backend && mvn -Dtest=ProjectControllerTest test`

Expected: FAIL because retry endpoint is missing.

- [ ] **Step 3: Implement retry command**

```java
AgentTask failed = requireTask(projectId, taskId);
if (!"FAILED".equals(failed.getStatus())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed tasks can be retried");
}
AgentEngineExecutionRecord record = agentEngineClient.runNode(failed.getNodeName(), failed.getInputText());
AgentTask retry = recordTask(projectId, record, failed.getId());
if ("SUCCEEDED".equals(retry.getStatus())) {
    saveNodeArtifactIfArtifactNode(projectId, retry.getNodeName(), retry.getOutputText());
}
return retry;
```

- [ ] **Step 4: Add retry endpoint and frontend client**

Backend:

```java
@PostMapping("/{projectId}/tasks/{taskId}/retry")
public RetryTaskResponse retryTask(@PathVariable Long projectId, @PathVariable Long taskId) {
    AgentTask retry = agentOrchestrationService.retryTask(projectId, taskId);
    return RetryTaskResponse.from(retry);
}
```

Frontend:

```ts
export async function retryTask(projectId: number, taskId: number): Promise<RetryTaskResponse> {
  return request(`/api/projects/${projectId}/tasks/${taskId}/retry`, { method: 'POST' });
}
```

- [ ] **Step 5: Run retry tests**

Run:

```bash
cd backend && mvn -Dtest=ProjectControllerTest test
cd frontend && npm run test -- projects.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec frontend/src
git commit -m "feat: retry failed agent nodes"
```

---

## Task 10: PDF Export

**Files:**
- Create: `backend/src/main/java/com/autospec/service/PdfExportService.java`
- Modify: `backend/src/main/java/com/autospec/controller/ExportController.java`
- Modify: `backend/src/main/java/com/autospec/dto/ExportResponse.java`
- Create: `backend/src/test/java/com/autospec/PdfExportServiceTest.java`
- Modify: `frontend/src/api/projects.ts`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`

- [ ] **Step 1: Write failing PDF test**

```java
@Test
void rendersProjectArtifactsToNonEmptyPdf() {
    long projectId = saveProjectWithV2Artifacts();

    byte[] pdf = pdfExportService.exportProject(projectId);

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
}
```

- [ ] **Step 2: Run failing PDF test**

Run: `cd backend && mvn -Dtest=PdfExportServiceTest test`

Expected: FAIL because `PdfExportService` is missing.

- [ ] **Step 3: Implement PDF renderer**

```java
public byte[] exportProject(Long projectId) {
    String markdown = markdownExportService.exportProject(projectId);
    try (PDDocument document = new PDDocument();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            stream.setLeading(14);
            stream.newLineAtOffset(48, 780);
            for (String line : markdown.split("\\R")) {
                stream.showText(sanitizePdfText(line));
                stream.newLine();
            }
            stream.endText();
        }
        document.save(output);
        return output.toByteArray();
    } catch (IOException ex) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF export failed", ex);
    }
}
```

- [ ] **Step 4: Extend export endpoint**

For `format=PDF`, return base64 content:

```java
byte[] pdf = pdfExportService.exportProject(projectId);
return new ExportResponse(
        "PDF",
        Base64.getEncoder().encodeToString(pdf),
        "autospec-project-" + projectId + ".pdf",
        "application/pdf",
        "base64");
```

- [ ] **Step 5: Add frontend PDF export**

```ts
export async function exportPdf(projectId: number): Promise<ExportResponse> {
  return request(`/api/projects/${projectId}/export?format=PDF`, { method: 'POST' });
}
```

Decode with:

```ts
const bytes = Uint8Array.from(atob(response.content), (char) => char.charCodeAt(0));
const blob = new Blob([bytes], { type: response.mediaType });
```

- [ ] **Step 6: Run export tests**

Run:

```bash
cd backend && mvn -Dtest=MarkdownExportServiceTest,PdfExportServiceTest,ProjectControllerTest test
cd frontend && npm run test -- projects.test.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec frontend/src
git commit -m "feat: add pdf export"
```

---

## Task 11: Frontend V2 Workflow

**Files:**
- Modify: `frontend/src/api/projects.ts`
- Modify: `frontend/src/pages/HomePage.tsx`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`
- Modify: `frontend/src/components/ArtifactTabs.tsx`
- Modify: `frontend/src/components/AgentTimeline.tsx`
- Create: `frontend/src/components/PrdEditor.tsx`
- Create: `frontend/src/components/FrontendSkeletonPreview.tsx`
- Create: `frontend/src/components/ExecutionEventList.tsx`
- Modify: `frontend/src/api/projects.test.ts`
- Create: `frontend/src/components/PrdEditor.test.tsx`

- [ ] **Step 1: Write failing API client tests**

```ts
it('supports v2 prd gate approve continue retry events and pdf export', async () => {
  fetchMock
    .mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'PRD_REVIEW', percent: 20 }))
    .mockReturnValueOnce(jsonResponse({ id: 3, status: 'APPROVED', version: 2 }))
    .mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'COMPLETED', percent: 100 }))
    .mockReturnValueOnce(jsonResponse({ taskId: 9, status: 'SUCCEEDED', retryOfTaskId: 8 }))
    .mockReturnValueOnce(jsonResponse({ format: 'PDF', content: 'JVBERi0=', fileName: 'autospec-project-7.pdf', mediaType: 'application/pdf', encoding: 'base64' }));

  await expect(generatePrd(7)).resolves.toMatchObject({ status: 'PRD_REVIEW' });
  await expect(approveArtifact(7, 3)).resolves.toMatchObject({ status: 'APPROVED' });
  await expect(continueGeneration(7)).resolves.toMatchObject({ status: 'COMPLETED' });
  await expect(retryTask(7, 8)).resolves.toMatchObject({ retryOfTaskId: 8 });
  await expect(exportPdf(7)).resolves.toMatchObject({ format: 'PDF' });
});
```

- [ ] **Step 2: Run failing frontend tests**

Run: `cd frontend && npm run test -- projects.test.ts`

Expected: FAIL because V2 client functions are missing.

- [ ] **Step 3: Implement V2 API client functions**

```ts
export async function generatePrd(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/generate-prd`, { method: 'POST' });
}

export async function approveArtifact(projectId: number, artifactId: number): Promise<ApproveArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}/approve`, { method: 'POST' });
}

export async function continueGeneration(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/continue`, { method: 'POST' });
}
```

- [ ] **Step 4: Build PRD editor component**

`PrdEditor` accepts the latest PRD artifact, exposes Save and Approve actions, and disables Approve when content is not valid JSON.

```tsx
interface PrdEditorProps {
  artifact: ArtifactResponse;
  onSave: (content: string) => Promise<void>;
  onApprove: () => Promise<void>;
}
```

- [ ] **Step 5: Build execution event list**

`ExecutionEventList` renders event type, node name, message, and timestamp from `AgentEventResponse[]`.

- [ ] **Step 6: Update project detail state machine**

| Project status | Primary action |
| --- | --- |
| `CREATED` | Generate PRD |
| `PRD_REVIEW` | Save PRD draft or approve PRD |
| `PRD_APPROVED` | Continue generation |
| `GENERATING` | Show live events |
| `COMPLETED` | Export Markdown or PDF |
| `FAILED` | Retry failed task |

- [ ] **Step 7: Run frontend verification**

Run:

```bash
cd frontend && npm run test
cd frontend && npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat: add v2 project workflow ui"
```

---

## Task 12: Full Verification and Documentation

**Files:**
- Modify: `docs/autospec-implementation-plan.md`
- Modify: `docs/autospec-v2-plan.md`
- Create: `docs/examples/v2-sample-artifacts.md`

- [ ] **Step 1: Add V2 sample artifact document**

Create `docs/examples/v2-sample-artifacts.md` with sanitized examples for approved PRD, architecture design, backend design, frontend skeleton, review report, Markdown export, and PDF export evidence.

- [ ] **Step 2: Update plan index**

In `docs/autospec-implementation-plan.md`, replace the compact V2 backlog with a link to this plan and keep V3 backlog unchanged.

- [ ] **Step 3: Run complete verification**

Run:

```bash
cd agent-engine && pytest
cd backend && mvn test
cd frontend && npm run test
cd frontend && npm run build
docker compose config
```

Expected: all commands exit with code `0`.

- [ ] **Step 4: Inspect git diff**

Run: `git diff --stat`

Expected: changes are limited to V2 backend, Agent engine, frontend, docs, and schema files listed in this plan.

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs: expand autospec v2 implementation plan"
```

---

## Execution Order

1. Task 1 establishes durable schema and model boundaries.
2. Task 2 adds prompt traceability before new Agent outputs depend on prompt versions.
3. Task 3 adds typed V2 artifact contracts.
4. Task 4 adds Agent engine V2 generation.
5. Task 5 adds the PRD human approval gate.
6. Task 6 connects approved PRD to full V2 artifact persistence.
7. Task 7 strengthens Reviewer coverage across architecture, backend, frontend, and permissions.
8. Task 8 adds real-time event streaming backed by persisted event history.
9. Task 9 adds failed-node retry using stored node input.
10. Task 10 adds PDF export.
11. Task 11 exposes V2 in the frontend.
12. Task 12 verifies the whole V2 workflow and updates docs.

## Acceptance Checklist

- [ ] V2 PRD can be generated independently and stored as `PENDING_REVIEW`.
- [ ] Edited PRD creates a new artifact version instead of overwriting generated content.
- [ ] Approved PRD is the only PRD version consumed by downstream V2 Agent nodes.
- [ ] V2 generation persists `ARCHITECTURE_DESIGN`, `BACKEND_DESIGN`, `FRONTEND_SKELETON`, and `REVIEW_REPORT` artifacts.
- [ ] Every Agent node records input JSON, output JSON, status, duration, error message, and prompt version when available.
- [ ] Agent events are persisted and available through history plus SSE stream endpoints.
- [ ] Reviewer checks include PRD-to-API, API-to-data, permission, architecture, and frontend API binding coverage.
- [ ] Failed Agent tasks can be retried from stored input without deleting unrelated artifacts.
- [ ] Markdown and PDF exports are both available for completed V2 projects.
- [ ] Frontend supports PRD editing/approval, live events, retry, new artifact previews, and PDF download.
- [ ] `cd agent-engine && pytest` passes.
- [ ] `cd backend && mvn test` passes.
- [ ] `cd frontend && npm run test` passes.
- [ ] `cd frontend && npm run build` passes.
- [ ] `docker compose config` passes.

## Self-Review

- Spec coverage: all seven V2 backlog items map to tasks and acceptance checks.
- Placeholder scan: every listed capability has files, steps, tests, commands, and expected outcomes.
- Type consistency: artifact types are `PRD`, `ARCHITECTURE_DESIGN`, `BACKEND_DESIGN`, `FRONTEND_SKELETON`, and `REVIEW_REPORT`; project states are `CREATED`, `PRD_REVIEW`, `PRD_APPROVED`, `GENERATING`, `COMPLETED`, and `FAILED`.
- Scope control: V3 items are excluded from implementation tasks.
