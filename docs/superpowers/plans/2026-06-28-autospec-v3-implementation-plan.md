# AutoSpec V3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build AutoSpec V3 by adding project-level auth, historical artifact retrieval, model routing and invocation scoring, code skeleton ZIP export, and workflow visualization on top of the completed V2 workflow.

**Execution Status:** Implemented in the workspace on `2026-06-29`. The implementation covers V3 persistence, auth guards, historical artifact retrieval, model invocation logging, code skeleton ZIP export, workflow visualization, V3 reviewer rules, documentation samples, and regression tests.

**Architecture:** V3 keeps Spring Boot as the system of record and adds durable ownership, knowledge, model governance, code generation jobs, and workflow snapshots through Flyway migrations and MyBatis-Plus services. The Python Agent engine receives optional retrieved context and routing metadata but remains stateless between calls. The React frontend adds session state, guarded project operations, code export controls, and an execution graph view backed by persisted backend APIs.

**Tech Stack:** Spring Boot, MyBatis-Plus, MySQL, Flyway, Redis, FastAPI, LangGraph, Pydantic, React, TypeScript, Ant Design, Vitest, JUnit, pytest, Java ZipOutputStream.

---

## Scope

V3 delivers five capabilities:

- `V3-01` Permission model and user login.
- `V3-02` Historical project RAG using approved AutoSpec artifacts as source material.
- `V3-03` Multi-model routing and result scoring with persisted invocation logs.
- `V3-04` Spring Boot and React code skeleton ZIP generation.
- `V3-05` LangGraph workflow visualization from persisted snapshots, tasks, and events.

V3 excludes billing, production OAuth providers, real vector database deployment, full generated business logic, and collaborative real-time editing. Those can be planned after V3 is stable.

## Current Baseline

- Backend persists `project`, `agent_task`, `artifact`, `review_issue`, `prompt_version`, and `agent_event`.
- Backend supports PRD review, artifact versioning, retry, event history/SSE, Markdown export, and PDF export.
- Agent engine supports V2 nodes: `product_manager`, `architect`, `backend_engineer`, `frontend_engineer`, and `reviewer`.
- Frontend supports project creation, PRD approval, live events, artifact previews, retry, Markdown export, and PDF export.
- Local database currently has a V3 roadmap artifact: project `AutoSpec V3 Plan`, artifact type `ROADMAP_PLAN`, version `3`.

## Target File Structure

### Backend Persistence

- Create: `backend/src/main/resources/db/migration/V3__autospec_v3_extensions.sql`
- Modify: `scheme/init.sql`
- Create: `backend/src/main/java/com/autospec/entity/UserAccount.java`
- Create: `backend/src/main/java/com/autospec/entity/ProjectMember.java`
- Create: `backend/src/main/java/com/autospec/entity/KnowledgeDocument.java`
- Create: `backend/src/main/java/com/autospec/entity/KnowledgeChunk.java`
- Create: `backend/src/main/java/com/autospec/entity/ModelProvider.java`
- Create: `backend/src/main/java/com/autospec/entity/ModelConfig.java`
- Create: `backend/src/main/java/com/autospec/entity/ModelInvocation.java`
- Create: `backend/src/main/java/com/autospec/entity/CodeGenerationJob.java`
- Create: `backend/src/main/java/com/autospec/entity/ExportFile.java`
- Create: `backend/src/main/java/com/autospec/entity/WorkflowSnapshot.java`
- Create mapper and service pairs for each new entity.

### Backend APIs

- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/autospec/controller/AuthController.java`
- Create: `backend/src/main/java/com/autospec/controller/KnowledgeController.java`
- Create: `backend/src/main/java/com/autospec/controller/ModelGovernanceController.java`
- Create: `backend/src/main/java/com/autospec/controller/CodeGenerationController.java`
- Create: `backend/src/main/java/com/autospec/controller/WorkflowController.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Create: `backend/src/main/java/com/autospec/service/AuthService.java`
- Create: `backend/src/main/java/com/autospec/service/ProjectAccessService.java`
- Create: `backend/src/main/java/com/autospec/service/KnowledgeIndexService.java`
- Create: `backend/src/main/java/com/autospec/service/ModelRoutingService.java`
- Create: `backend/src/main/java/com/autospec/service/CodeSkeletonService.java`
- Create: `backend/src/main/java/com/autospec/service/WorkflowSnapshotService.java`

### Agent Engine

- Modify: `agent-engine/schemas/prd.py`
- Modify: `agent-engine/schemas/architecture_design.py`
- Modify: `agent-engine/schemas/backend_design.py`
- Modify: `agent-engine/schemas/frontend_skeleton.py`
- Modify: `agent-engine/schemas/review.py`
- Modify: `agent-engine/agents/base.py`
- Modify: `agent-engine/graph/workflow.py`
- Modify: `agent-engine/main.py`
- Modify: `agent-engine/review/rules.py`

### Frontend

- Modify: `frontend/src/api/projects.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/v3.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/HomePage.tsx`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/components/WorkflowGraph.tsx`
- Create: `frontend/src/components/KnowledgeSourceList.tsx`
- Create: `frontend/src/components/ModelInvocationTable.tsx`
- Create: `frontend/src/components/CodeExportPanel.tsx`

---

## Task 1: V3 Schema Migration and Entity Foundation

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__autospec_v3_extensions.sql`
- Modify: `scheme/init.sql`
- Modify: `backend/src/test/java/com/autospec/SchemaInitSqlTest.java`
- Modify: `backend/src/test/java/com/autospec/CoreDataModelTest.java`
- Create: new entity, mapper, and service files listed in Target File Structure

- [ ] **Step 1: Write the failing V3 schema test**

Add this test to `SchemaInitSqlTest`:

```java
@Test
void flywayMigrationsCreateV3Tables() throws Exception {
    try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:v3_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa",
            "")) {
        Flyway.configure()
                .dataSource(() -> connection)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(tableNames(connection))
                .contains(
                        "user_account",
                        "project_member",
                        "knowledge_document",
                        "knowledge_chunk",
                        "model_provider",
                        "model_config",
                        "model_invocation",
                        "code_generation_job",
                        "export_file",
                        "workflow_snapshot"
                );
        assertThat(columnNames(connection, "project_member"))
                .contains("project_id", "user_id", "role");
        assertThat(columnNames(connection, "model_invocation"))
                .contains("project_id", "task_id", "provider_key", "model_name", "status", "duration_ms", "score");
    }
}
```

- [ ] **Step 2: Run the failing schema test**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=SchemaInitSqlTest test`

Expected: FAIL because `V3__autospec_v3_extensions.sql` does not exist.

- [ ] **Step 3: Create the V3 migration**

Create `backend/src/main/resources/db/migration/V3__autospec_v3_extensions.sql`:

```sql
create table if not exists user_account (
    id bigint primary key auto_increment,
    username varchar(128) not null,
    display_name varchar(128) not null,
    password_hash varchar(256) not null,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_user_account_username unique (username)
);

create table if not exists project_member (
    id bigint primary key auto_increment,
    project_id bigint not null,
    user_id bigint not null,
    role varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_project_member_project foreign key (project_id) references project (id),
    constraint fk_project_member_user foreign key (user_id) references user_account (id),
    constraint uk_project_member unique (project_id, user_id),
    index idx_project_member_project_id (project_id),
    index idx_project_member_user_id (user_id)
);

create table if not exists knowledge_document (
    id bigint primary key auto_increment,
    project_id bigint not null,
    artifact_id bigint not null,
    artifact_type varchar(64) not null,
    artifact_version int not null,
    title varchar(256) not null,
    status varchar(32) not null default 'INDEXED',
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_document_project foreign key (project_id) references project (id),
    constraint fk_knowledge_document_artifact foreign key (artifact_id) references artifact (id),
    constraint uk_knowledge_document_artifact unique (artifact_id),
    index idx_knowledge_document_project_id (project_id)
);

create table if not exists knowledge_chunk (
    id bigint primary key auto_increment,
    document_id bigint not null,
    chunk_index int not null,
    content text not null,
    token_hint int not null default 0,
    retrieval_terms varchar(512) not null,
    vector_ref varchar(256) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_chunk_document foreign key (document_id) references knowledge_document (id),
    constraint uk_knowledge_chunk unique (document_id, chunk_index),
    index idx_knowledge_chunk_terms (retrieval_terms)
);

create table if not exists model_provider (
    id bigint primary key auto_increment,
    provider_key varchar(64) not null,
    display_name varchar(128) not null,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_model_provider_key unique (provider_key)
);

create table if not exists model_config (
    id bigint primary key auto_increment,
    provider_key varchar(64) not null,
    model_name varchar(128) not null,
    agent_node varchar(128) not null,
    priority int not null default 100,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_model_config unique (provider_key, model_name, agent_node),
    index idx_model_config_node (agent_node, enabled, priority)
);

create table if not exists model_invocation (
    id bigint primary key auto_increment,
    project_id bigint not null,
    task_id bigint null,
    provider_key varchar(64) not null,
    model_name varchar(128) not null,
    agent_node varchar(128) not null,
    prompt_version_id bigint null,
    status varchar(32) not null,
    duration_ms int not null default 0,
    input_tokens int not null default 0,
    output_tokens int not null default 0,
    estimated_cost decimal(12, 6) null,
    score decimal(5, 2) null,
    error_message text null,
    created_at timestamp not null default current_timestamp,
    constraint fk_model_invocation_project foreign key (project_id) references project (id),
    index idx_model_invocation_project_id (project_id),
    index idx_model_invocation_agent_node (agent_node)
);

create table if not exists code_generation_job (
    id bigint primary key auto_increment,
    project_id bigint not null,
    status varchar(32) not null,
    manifest longtext null,
    error_message text null,
    created_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    constraint fk_code_generation_job_project foreign key (project_id) references project (id),
    index idx_code_generation_job_project_id (project_id)
);

create table if not exists export_file (
    id bigint primary key auto_increment,
    project_id bigint not null,
    job_id bigint null,
    file_name varchar(256) not null,
    media_type varchar(128) not null,
    encoding varchar(32) not null,
    content longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_export_file_project foreign key (project_id) references project (id),
    index idx_export_file_project_id (project_id)
);

create table if not exists workflow_snapshot (
    id bigint primary key auto_increment,
    project_id bigint not null,
    workflow_key varchar(128) not null,
    version varchar(32) not null,
    graph_json longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_workflow_snapshot_project foreign key (project_id) references project (id),
    index idx_workflow_snapshot_project_id (project_id)
);
```

- [ ] **Step 4: Update `scheme/init.sql`**

Copy the V3 table definitions into `scheme/init.sql` after the V2 tables so local bootstrap and Flyway-created databases remain aligned.

- [ ] **Step 5: Create V3 entity classes**

Create `UserAccount`:

```java
@Data
@TableName("user_account")
public class UserAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String displayName;
    private String passwordHash;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Create `ProjectMember`:

```java
@Data
@TableName("project_member")
public class ProjectMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long userId;
    private String role;
    private LocalDateTime createdAt;
}
```

Create the remaining V3 entities using the exact column names from the migration and existing MyBatis-Plus camel-case mapping.

- [ ] **Step 6: Create mappers and services**

For each V3 entity create a mapper:

```java
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
```

Create a service interface:

```java
public interface UserAccountService extends IService<UserAccount> {
}
```

Create a service implementation:

```java
@Service
public class UserAccountServiceImpl
        extends ServiceImpl<UserAccountMapper, UserAccount>
        implements UserAccountService {
}
```

Repeat the same pattern for `ProjectMember`, `KnowledgeDocument`, `KnowledgeChunk`, `ModelProvider`, `ModelConfig`, `ModelInvocation`, `CodeGenerationJob`, `ExportFile`, and `WorkflowSnapshot`.

- [ ] **Step 7: Extend core data model test**

Add this persistence check to `CoreDataModelTest`:

```java
UserAccount user = new UserAccount();
user.setUsername("alice");
user.setDisplayName("Alice");
user.setPasswordHash("sha256:test");
user.setEnabled(true);
assertThat(userAccountService.save(user)).isTrue();

ProjectMember member = new ProjectMember();
member.setProjectId(project.getId());
member.setUserId(user.getId());
member.setRole("OWNER");
assertThat(projectMemberService.save(member)).isTrue();

WorkflowSnapshot snapshot = new WorkflowSnapshot();
snapshot.setProjectId(project.getId());
snapshot.setWorkflowKey("autospec-v3");
snapshot.setVersion("v3");
snapshot.setGraphJson("{\"nodes\":[\"product_manager\"],\"edges\":[]}");
assertThat(workflowSnapshotService.save(snapshot)).isTrue();
```

- [ ] **Step 8: Run V3 persistence verification**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=SchemaInitSqlTest,CoreDataModelTest test`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__autospec_v3_extensions.sql scheme/init.sql backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: add v3 persistence foundation"
```

---

## Task 2: Login and Project Access Guard

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/autospec/controller/AuthController.java`
- Create: `backend/src/main/java/com/autospec/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/autospec/dto/LoginResponse.java`
- Create: `backend/src/main/java/com/autospec/service/AuthService.java`
- Create: `backend/src/main/java/com/autospec/service/ProjectAccessService.java`
- Modify: `backend/src/main/java/com/autospec/controller/ProjectController.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`

- [ ] **Step 1: Write failing auth API tests**

Add tests:

```java
@Test
void loginReturnsDemoSessionUser() throws Exception {
    mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"username":"owner","password":"owner-pass"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").isNumber())
            .andExpect(jsonPath("$.username").value("owner"));
}

@Test
void projectArtifactsRequireMembership() throws Exception {
    long projectId = createProject("Private Project", "Build private project");

    mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId)
                    .header("X-AutoSpec-User-Id", "99999"))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run failing auth tests**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: FAIL because auth endpoints and membership checks are missing.

- [ ] **Step 3: Add password hashing dependency**

Add to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **Step 4: Implement login DTOs**

Create `LoginRequest`:

```java
public record LoginRequest(String username, String password) {
}
```

Create `LoginResponse`:

```java
public record LoginResponse(Long userId, String username, String displayName) {
    public static LoginResponse from(UserAccount user) {
        return new LoginResponse(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
```

- [ ] **Step 5: Implement `AuthService`**

```java
@Service
public class AuthService {
    private final UserAccountService userAccountService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Transactional
    public UserAccount ensureDemoOwner() {
        return userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, "owner")
                .oneOpt()
                .orElseGet(() -> {
                    UserAccount user = new UserAccount();
                    user.setUsername("owner");
                    user.setDisplayName("Owner");
                    user.setPasswordHash(passwordEncoder.encode("owner-pass"));
                    user.setEnabled(true);
                    userAccountService.save(user);
                    return user;
                });
    }

    public UserAccount login(String username, String password) {
        UserAccount user = userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, username)
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!Boolean.TRUE.equals(user.getEnabled()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return user;
    }
}
```

- [ ] **Step 6: Implement `AuthController`**

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        authService.ensureDemoOwner();
        return LoginResponse.from(authService.login(request.username(), request.password()));
    }
}
```

- [ ] **Step 7: Implement project access service**

```java
@Service
public class ProjectAccessService {
    private final ProjectMemberService projectMemberService;

    public ProjectAccessService(ProjectMemberService projectMemberService) {
        this.projectMemberService = projectMemberService;
    }

    public Long requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user header");
        }
        try {
            return Long.parseLong(headerValue);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user header", ex);
        }
    }

    public void requireProjectRole(Long projectId, Long userId, String... acceptedRoles) {
        Set<String> roles = Set.of(acceptedRoles);
        boolean allowed = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .list()
                .stream()
                .anyMatch(member -> roles.contains(member.getRole()));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project access denied");
        }
    }
}
```

- [ ] **Step 8: Assign project owner on creation**

In `ProjectController.create`, read `X-AutoSpec-User-Id`. If the header is absent, call `authService.ensureDemoOwner()` and use that user ID. After saving the project, insert a `project_member` row with role `OWNER`.

- [ ] **Step 9: Guard project endpoints**

For project read/generate/update/export operations, call:

```java
Long userId = projectAccessService.requireUserId(userHeader);
projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR", "VIEWER");
```

For mutation operations, use `OWNER` and `EDITOR`.

- [ ] **Step 10: Run auth verification**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: add project login and access guard"
```

---

## Task 3: Frontend Session and Guarded Requests

**Files:**
- Create: `frontend/src/api/auth.ts`
- Modify: `frontend/src/api/projects.ts`
- Create: `frontend/src/pages/LoginPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/HomePage.tsx`
- Modify: `frontend/src/api/projects.test.ts`

- [ ] **Step 1: Write failing frontend auth tests**

Add to `frontend/src/api/projects.test.ts`:

```ts
it('attaches the autospec user header to project requests', async () => {
  localStorage.setItem('autospec.session', JSON.stringify({ userId: 3, username: 'owner', displayName: 'Owner' }));
  fetchMock.mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'CREATED' }));

  await createProject({ name: 'V3', requirement: 'Build V3' });

  expect(fetchMock).toHaveBeenCalledWith('/api/projects', expect.objectContaining({
    headers: expect.objectContaining({ 'X-AutoSpec-User-Id': '3' })
  }));
});
```

- [ ] **Step 2: Run failing frontend test**

Run: `cd frontend && npm run test -- projects.test.ts`

Expected: FAIL because the session header is not attached.

- [ ] **Step 3: Create auth API client**

Create `frontend/src/api/auth.ts`:

```ts
export interface LoginResponse {
  userId: number;
  username: string;
  displayName: string;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!response.ok) {
    throw new Error(`Login failed: ${response.status}`);
  }
  return response.json() as Promise<LoginResponse>;
}

export function readSession(): LoginResponse | null {
  const raw = localStorage.getItem('autospec.session');
  return raw ? JSON.parse(raw) as LoginResponse : null;
}
```

- [ ] **Step 4: Attach user header in project requests**

In `frontend/src/api/projects.ts`, add:

```ts
function sessionHeaders(init?: HeadersInit): HeadersInit {
  const session = localStorage.getItem('autospec.session');
  const parsed = session ? JSON.parse(session) as { userId?: number } : null;
  return parsed?.userId
    ? { ...init, 'X-AutoSpec-User-Id': String(parsed.userId) }
    : { ...init };
}
```

Use `sessionHeaders` in every request created by the shared `request` function.

- [ ] **Step 5: Create login page**

`LoginPage` stores the login response in `localStorage` and navigates to `/`:

```tsx
const handleSubmit = async () => {
  const session = await login(username, password);
  localStorage.setItem('autospec.session', JSON.stringify(session));
  navigate('/');
};
```

- [ ] **Step 6: Guard app routes**

In `App.tsx`, redirect users without `readSession()` to `/login`, except when already on `/login`.

- [ ] **Step 7: Run frontend auth verification**

Run:

```bash
cd frontend && npm run test -- projects.test.ts
cd frontend && npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat: add frontend session guard"
```

---

## Task 4: Historical Artifact Knowledge Index

**Files:**
- Create: `backend/src/main/java/com/autospec/service/KnowledgeIndexService.java`
- Create: `backend/src/main/java/com/autospec/dto/KnowledgeSourceResponse.java`
- Create: `backend/src/main/java/com/autospec/controller/KnowledgeController.java`
- Modify: `backend/src/main/java/com/autospec/service/ArtifactVersionService.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`

- [ ] **Step 1: Write failing knowledge indexing test**

```java
@Test
void approvingArtifactIndexesKnowledgeChunks() throws Exception {
    long projectId = createProject("Knowledge Source", "Build a campus marketplace");
    long artifactId = savePrdArtifact(projectId, "{\"project_name\":\"Campus\",\"core_features\":[\"favorites\"]}");

    mockMvc.perform(post("/api/projects/{projectId}/artifacts/{artifactId}/approve", projectId, artifactId)
                    .header("X-AutoSpec-User-Id", ownerUserId()))
            .andExpect(status().isOk());

    mockMvc.perform(get("/api/projects/{projectId}/knowledge/sources", projectId)
                    .header("X-AutoSpec-User-Id", ownerUserId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].artifactType").value("PRD"));
}
```

- [ ] **Step 2: Run failing knowledge test**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: FAIL because knowledge indexing is missing.

- [ ] **Step 3: Implement chunking service**

```java
@Service
public class KnowledgeIndexService {
    private final KnowledgeDocumentService documentService;
    private final KnowledgeChunkService chunkService;

    public void indexApprovedArtifact(Artifact artifact) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setProjectId(artifact.getProjectId());
        document.setArtifactId(artifact.getId());
        document.setArtifactType(artifact.getType());
        document.setArtifactVersion(artifact.getVersion());
        document.setTitle(artifact.getTitle());
        document.setStatus("INDEXED");
        documentService.save(document);

        List<String> chunks = splitIntoChunks(artifact.getContent(), 900);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(chunks.get(index));
            chunk.setTokenHint(Math.max(1, chunks.get(index).length() / 4));
            chunk.setRetrievalTerms(extractTerms(chunks.get(index)));
            chunkService.save(chunk);
        }
    }
}
```

Use deterministic term extraction: lowercase, keep letters/digits/Chinese characters, drop tokens shorter than 2 characters, store at most 40 distinct terms joined by spaces.

- [ ] **Step 4: Index on artifact approval**

In `ArtifactVersionService.approve`, after setting `APPROVED`, call:

```java
knowledgeIndexService.indexApprovedArtifact(artifact);
```

- [ ] **Step 5: Add knowledge source endpoint**

```java
@GetMapping("/api/projects/{projectId}/knowledge/sources")
public List<KnowledgeSourceResponse> sources(
        @PathVariable Long projectId,
        @RequestHeader("X-AutoSpec-User-Id") String userHeader
) {
    Long userId = projectAccessService.requireUserId(userHeader);
    projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR", "VIEWER");
    return knowledgeIndexService.sources(projectId).stream()
            .map(KnowledgeSourceResponse::from)
            .toList();
}
```

- [ ] **Step 6: Run knowledge verification**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: index approved artifacts as knowledge"
```

---

## Task 5: Retrieval Context Injection into Agent Engine

**Files:**
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/main/java/com/autospec/service/HttpAgentEngineClient.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentEngineClient.java`
- Modify: `agent-engine/main.py`
- Modify: `agent-engine/graph/workflow.py`
- Modify: `agent-engine/tests/test_main.py`
- Modify: `agent-engine/tests/test_workflow.py`

- [ ] **Step 1: Write failing agent context test**

In `agent-engine/tests/test_main.py`, add:

```python
def test_generate_accepts_retrieved_sources(client):
    response = client.post("/generate", json={
        "requirement": "Build favorite products",
        "retrieved_sources": [{
            "artifact_id": 9,
            "artifact_type": "PRD",
            "title": "Marketplace PRD",
            "content": "Favorites require create and delete APIs."
        }]
    })

    assert response.status_code == 200
    first_record = response.json()["records"][0]
    assert first_record["input_json"]["retrieved_sources"][0]["artifact_id"] == 9
```

- [ ] **Step 2: Run failing agent test**

Run: `cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest tests/test_main.py -q`

Expected: FAIL because requests do not accept `retrieved_sources`.

- [ ] **Step 3: Extend Agent engine request schema**

In `agent-engine/main.py`:

```python
class RetrievedSource(BaseModel):
    artifact_id: int
    artifact_type: str
    title: str
    content: str


class GenerateRequest(BaseModel):
    requirement: str = Field(min_length=1)
    retrieved_sources: list[RetrievedSource] = Field(default_factory=list)
```

- [ ] **Step 4: Pass retrieved context through workflow input**

Add `retrieved_sources` to workflow input payloads:

```python
input_payload={
    "requirement": requirement,
    "retrieved_sources": [source.model_dump() for source in retrieved_sources],
}
```

Keep existing Agent outputs unchanged so V2 clients remain compatible.

- [ ] **Step 5: Retrieve sources before backend generation**

In `AgentOrchestrationService.generatePrd` and `continueAfterApprovedPrd`, call:

```java
List<KnowledgeSourceResponse> sources = knowledgeIndexService.retrieve(project.getOriginalRequirement(), 5);
AgentGenerationResult result = agentEngineClient.generatePrd(project.getOriginalRequirement(), sources);
```

Store the retrieved source list inside `AgentTask.inputText` through the Agent engine response.

- [ ] **Step 6: Run retrieval context verification**

Run:

```bash
cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest tests/test_main.py tests/test_workflow.py -q
cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent-engine backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: inject retrieved artifact context"
```

---

## Task 6: Model Routing and Invocation Logging

**Files:**
- Create: `backend/src/main/java/com/autospec/service/ModelRoutingService.java`
- Create: `backend/src/main/java/com/autospec/dto/ModelInvocationResponse.java`
- Create: `backend/src/main/java/com/autospec/controller/ModelGovernanceController.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`
- Modify: `agent-engine/agents/base.py`
- Modify: `agent-engine/main.py`

- [ ] **Step 1: Write failing model invocation test**

```java
@Test
void generationRecordsModelInvocations() throws Exception {
    long projectId = createProject("Routed Project", "Build model routing");

    mockMvc.perform(post("/api/projects/{projectId}/generate-prd", projectId)
                    .header("X-AutoSpec-User-Id", ownerUserId()))
            .andExpect(status().isOk());

    mockMvc.perform(get("/api/projects/{projectId}/model-invocations", projectId)
                    .header("X-AutoSpec-User-Id", ownerUserId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].agentNode").value("product_manager"))
            .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
}
```

- [ ] **Step 2: Run failing model test**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: FAIL because model invocation logging is missing.

- [ ] **Step 3: Implement routing policy**

```java
public ModelConfig route(String agentNode) {
    return modelConfigService.lambdaQuery()
            .eq(ModelConfig::getAgentNode, agentNode)
            .eq(ModelConfig::getEnabled, true)
            .orderByAsc(ModelConfig::getPriority)
            .last("limit 1")
            .oneOpt()
            .orElseGet(() -> defaultConfig(agentNode));
}
```

`defaultConfig(agentNode)` returns provider key `local`, model name `deterministic-fixture`, enabled `true`, and priority `100`.

- [ ] **Step 4: Record invocation per Agent task**

After `recordTask`, persist:

```java
ModelInvocation invocation = new ModelInvocation();
invocation.setProjectId(projectId);
invocation.setTaskId(task.getId());
invocation.setProviderKey(record.providerKey() == null ? "local" : record.providerKey());
invocation.setModelName(record.modelName() == null ? "deterministic-fixture" : record.modelName());
invocation.setAgentNode(task.getNodeName());
invocation.setPromptVersionId(task.getPromptVersionId());
invocation.setStatus(task.getStatus());
invocation.setDurationMs(task.getDurationMs() == null ? 0 : task.getDurationMs());
invocation.setScore("SUCCEEDED".equals(task.getStatus()) ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
modelInvocationService.save(invocation);
```

- [ ] **Step 5: Return model metadata from Agent engine**

Extend `AgentExecutionRecord` in `agent-engine/graph/workflow.py`:

```python
provider_key: str = "local"
model_name: str = "deterministic-fixture"
```

Serialize these fields in `main.py` responses.

- [ ] **Step 6: Add model invocation API**

```java
@GetMapping("/api/projects/{projectId}/model-invocations")
public List<ModelInvocationResponse> invocations(
        @PathVariable Long projectId,
        @RequestHeader("X-AutoSpec-User-Id") String userHeader
) {
    Long userId = projectAccessService.requireUserId(userHeader);
    projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR", "VIEWER");
    return modelInvocationService.lambdaQuery()
            .eq(ModelInvocation::getProjectId, projectId)
            .orderByAsc(ModelInvocation::getId)
            .list()
            .stream()
            .map(ModelInvocationResponse::from)
            .toList();
}
```

- [ ] **Step 7: Run model governance verification**

Run:

```bash
cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q
cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add agent-engine backend/src/main/java/com/autospec backend/src/test/java/com/autospec
git commit -m "feat: log model routing invocations"
```

---

## Task 7: Code Skeleton ZIP Generation

**Files:**
- Create: `backend/src/main/java/com/autospec/service/CodeSkeletonService.java`
- Create: `backend/src/main/java/com/autospec/controller/CodeGenerationController.java`
- Create: `backend/src/main/java/com/autospec/dto/CodeGenerationResponse.java`
- Create: `backend/src/test/java/com/autospec/CodeSkeletonServiceTest.java`
- Modify: `frontend/src/api/v3.ts`
- Create: `frontend/src/components/CodeExportPanel.tsx`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`

- [ ] **Step 1: Write failing ZIP service test**

```java
@Test
void exportsSpringAndReactSkeletonZip() throws Exception {
    long projectId = saveProjectWithV2Artifacts();

    CodeGenerationResponse response = codeSkeletonService.generate(projectId);

    assertThat(response.fileName()).endsWith(".zip");
    byte[] bytes = Base64.getDecoder().decode(response.content());
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
        List<String> names = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            names.add(entry.getName());
        }
        assertThat(names).contains(
                "backend/pom.xml",
                "backend/src/main/java/com/generated/Application.java",
                "frontend/package.json",
                "frontend/src/App.tsx",
                "AUTOSPEC_MANIFEST.json"
        );
    }
}
```

- [ ] **Step 2: Run failing ZIP test**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=CodeSkeletonServiceTest test`

Expected: FAIL because `CodeSkeletonService` is missing.

- [ ] **Step 3: Implement ZIP generation**

Use `ZipOutputStream` and add deterministic files:

```java
private void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
}
```

Generate:

```java
put(zip, "backend/pom.xml", backendPom());
put(zip, "backend/src/main/java/com/generated/Application.java", springApplication());
put(zip, "backend/src/main/resources/application.yml", generatedApplicationYml());
put(zip, "frontend/package.json", frontendPackageJson());
put(zip, "frontend/src/App.tsx", generatedReactApp());
put(zip, "AUTOSPEC_MANIFEST.json", manifestJson(projectId, artifacts));
```

Do not include API keys, passwords, `.env`, or local absolute paths in any generated file.

- [ ] **Step 4: Persist job and export file**

Persist `code_generation_job` as `RUNNING`, then `SUCCEEDED`. Store the base64 ZIP in `export_file` with media type `application/zip`.

- [ ] **Step 5: Add generation endpoint**

```java
@PostMapping("/api/projects/{projectId}/code-skeleton")
public CodeGenerationResponse generate(
        @PathVariable Long projectId,
        @RequestHeader("X-AutoSpec-User-Id") String userHeader
) {
    Long userId = projectAccessService.requireUserId(userHeader);
    projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR");
    return codeSkeletonService.generate(projectId);
}
```

- [ ] **Step 6: Add frontend code export panel**

In `frontend/src/api/v3.ts`:

```ts
export async function generateCodeSkeleton(projectId: number): Promise<ExportMetadataResponse> {
  return request(`/api/projects/${projectId}/code-skeleton`, { method: 'POST' });
}
```

`CodeExportPanel` renders a download button and decodes base64 content into an `application/zip` Blob.

- [ ] **Step 7: Run ZIP verification**

Run:

```bash
cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=CodeSkeletonServiceTest,ProjectControllerTest test
cd frontend && npm run test -- projects.test.ts
cd frontend && npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec frontend/src
git commit -m "feat: export generated code skeleton zip"
```

---

## Task 8: Workflow Snapshot and Visualization

**Files:**
- Create: `backend/src/main/java/com/autospec/service/WorkflowSnapshotService.java`
- Create: `backend/src/main/java/com/autospec/controller/WorkflowController.java`
- Create: `backend/src/main/java/com/autospec/dto/WorkflowSnapshotResponse.java`
- Modify: `backend/src/main/java/com/autospec/service/AgentOrchestrationService.java`
- Modify: `backend/src/test/java/com/autospec/ProjectControllerTest.java`
- Create: `frontend/src/components/WorkflowGraph.tsx`
- Modify: `frontend/src/api/v3.ts`
- Modify: `frontend/src/pages/ProjectDetailPage.tsx`

- [ ] **Step 1: Write failing workflow snapshot test**

```java
@Test
void workflowEndpointReturnsNodesEdgesAndEvents() throws Exception {
    long projectId = createProject("Workflow Project", "Visualize workflow");
    saveWorkflowSnapshot(projectId);

    mockMvc.perform(get("/api/projects/{projectId}/workflow", projectId)
                    .header("X-AutoSpec-User-Id", ownerUserId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowKey").value("autospec-v3"))
            .andExpect(jsonPath("$.nodes[0].id").value("product_manager"));
}
```

- [ ] **Step 2: Run failing workflow test**

Run: `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test`

Expected: FAIL because workflow snapshot API is missing.

- [ ] **Step 3: Persist default V3 workflow snapshot**

Create graph JSON:

```json
{
  "nodes": [
    {"id": "product_manager", "label": "Product Manager", "artifactType": "PRD"},
    {"id": "architect", "label": "Architect", "artifactType": "ARCHITECTURE_DESIGN"},
    {"id": "backend_engineer", "label": "Backend Engineer", "artifactType": "BACKEND_DESIGN"},
    {"id": "frontend_engineer", "label": "Frontend Engineer", "artifactType": "FRONTEND_SKELETON"},
    {"id": "reviewer", "label": "Reviewer", "artifactType": "REVIEW_REPORT"}
  ],
  "edges": [
    {"from": "product_manager", "to": "architect"},
    {"from": "product_manager", "to": "backend_engineer"},
    {"from": "architect", "to": "frontend_engineer"},
    {"from": "backend_engineer", "to": "frontend_engineer"},
    {"from": "frontend_engineer", "to": "reviewer"}
  ]
}
```

Save it when a V3 project first enters generation.

- [ ] **Step 4: Add workflow endpoint**

Return the latest snapshot plus task statuses and event history for the project:

```java
@GetMapping("/api/projects/{projectId}/workflow")
public WorkflowSnapshotResponse workflow(
        @PathVariable Long projectId,
        @RequestHeader("X-AutoSpec-User-Id") String userHeader
) {
    Long userId = projectAccessService.requireUserId(userHeader);
    projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR", "VIEWER");
    return workflowSnapshotService.latestResponse(projectId);
}
```

- [ ] **Step 5: Build frontend graph component**

`WorkflowGraph` renders stable columns for nodes and SVG lines for edges. Each node shows node label, current status, duration, and latest event message. Use fixed node dimensions so statuses do not shift the layout.

- [ ] **Step 6: Run workflow verification**

Run:

```bash
cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd -Dtest=ProjectControllerTest test
cd frontend && npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/autospec backend/src/test/java/com/autospec frontend/src
git commit -m "feat: visualize agent workflow"
```

---

## Task 9: V3 Reviewer Consistency Rules

**Files:**
- Modify: `agent-engine/review/rules.py`
- Modify: `agent-engine/agents/reviewer.py`
- Modify: `agent-engine/tests/test_review_rules.py`
- Modify: `agent-engine/schemas/review.py`

- [ ] **Step 1: Write failing V3 reviewer tests**

```python
def test_v3_reviewer_detects_missing_permission_boundary():
    prd = _prd_with_features("user login", "private projects")
    backend = _backend_with_api("GET", "/api/projects/{projectId}/artifacts", auth_required=False)

    issues = run_v3_rule_checks(prd=prd, backend_design=backend, retrieved_sources=[], generated_files=[])

    assert issues[0].issue_type == "PERMISSION_BOUNDARY"
```

```python
def test_v3_reviewer_requires_rag_source_citations():
    prd = _prd_with_features("historical project reuse")

    issues = run_v3_rule_checks(prd=prd, backend_design=_backend_ok(), retrieved_sources=[], generated_files=[])

    assert issues[0].issue_type == "RAG_SOURCE_CITATION"
```

- [ ] **Step 2: Run failing V3 reviewer tests**

Run: `cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest tests/test_review_rules.py -q`

Expected: FAIL because V3 rule checks are missing.

- [ ] **Step 3: Implement V3 rule checks**

Add:

```python
def run_v3_rule_checks(prd, backend_design, retrieved_sources, generated_files):
    issues = []
    prd_text = _dump_text(prd)
    if _mentions_any(prd_text, ["login", "permission", "private project", "权限", "登录"]):
        issues.extend(_require_authenticated_project_apis(backend_design))
    if _mentions_any(prd_text, ["history", "rag", "historical", "历史", "知识复用"]) and not retrieved_sources:
        issues.append(_issue(
            severity="HIGH",
            issue_type="RAG_SOURCE_CITATION",
            description="Historical reuse is requested but no retrieved artifact source is attached.",
            suggestion="Attach approved artifact source references to Agent task input."
        ))
    if generated_files and any(_contains_secret_marker(file) for file in generated_files):
        issues.append(_issue(
            severity="CRITICAL",
            issue_type="CODE_EXPORT_SECRET",
            description="Generated code skeleton contains secret-like configuration.",
            suggestion="Use placeholder variables and .env.example only."
        ))
    return issues
```

- [ ] **Step 4: Call V3 checks from Reviewer Agent**

When reviewer input contains `retrieved_sources`, `model_invocations`, or `generated_files`, append `run_v3_rule_checks` results after V2 checks.

- [ ] **Step 5: Run reviewer verification**

Run: `cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest tests/test_review_rules.py -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-engine/review agent-engine/agents agent-engine/schemas agent-engine/tests
git commit -m "feat: add v3 reviewer consistency rules"
```

---

## Task 10: V3 Documentation, Database Plan Artifact, and Full Verification

**Files:**
- Modify: `docs/autospec-v3-plan.md`
- Modify: `docs/autospec-implementation-plan.md`
- Create: `docs/examples/v3-sample-artifacts.md`

- [ ] **Step 1: Update V3 docs**

Ensure `docs/autospec-v3-plan.md` points to:

```markdown
> Canonical superpowers plan: `docs/superpowers/plans/2026-06-28-autospec-v3-implementation-plan.md`
```

Keep the database record section with project `AutoSpec V3 Plan`, artifact type `ROADMAP_PLAN`, and artifact version `3`.

- [ ] **Step 2: Add V3 sample artifact document**

Create `docs/examples/v3-sample-artifacts.md` with sanitized examples for:

- Login response.
- Knowledge source list.
- Agent task input with retrieved sources.
- Model invocation list.
- Code skeleton manifest.
- Workflow snapshot response.
- V3 reviewer issues.

- [ ] **Step 3: Sync the plan into the database artifact**

Run a parameterized script from the repository root that reads the canonical plan and updates the existing artifact:

```powershell
@'
from pathlib import Path
import pymysql

root = Path.cwd()
config = {}
for raw_line in (root / ".env").read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        config[key.strip()] = value.strip()

content = (root / "docs" / "superpowers" / "plans" / "2026-06-28-autospec-v3-implementation-plan.md").read_text(encoding="utf-8")
connection = pymysql.connect(
    host=config.get("DB_HOST", "localhost"),
    port=int(config.get("DB_PORT", "3306")),
    user=config.get("MYSQL_USER", "root"),
    password=config.get("MYSQL_PASSWORD", ""),
    database=config.get("MYSQL_DATABASE", "autospec"),
    charset="utf8mb4",
)
try:
    with connection.cursor() as cursor:
        cursor.execute("select id from project where name = %s", ("AutoSpec V3 Plan",))
        row = cursor.fetchone()
        if row is None:
            cursor.execute(
                "insert into project (user_id, name, original_requirement, status) values (%s, %s, %s, %s)",
                (1, "AutoSpec V3 Plan", "AutoSpec V3 implementation plan", "PLANNED"),
            )
            project_id = cursor.lastrowid
        else:
            project_id = row[0]
        cursor.execute(
            "select id from artifact where project_id = %s and type = %s and version = %s",
            (project_id, "ROADMAP_PLAN", 3),
        )
        artifact = cursor.fetchone()
        if artifact is None:
            cursor.execute(
                "insert into artifact (project_id, type, title, content, format, version) values (%s, %s, %s, %s, %s, %s)",
                (project_id, "ROADMAP_PLAN", "AutoSpec V3 Implementation Plan", content, "MARKDOWN", 3),
            )
        else:
            cursor.execute(
                "update artifact set title = %s, content = %s, format = %s where id = %s",
                ("AutoSpec V3 Implementation Plan", content, "MARKDOWN", artifact[0]),
            )
    connection.commit()
finally:
    connection.close()
'@ | D:\miniconda3\envs\CrewAI_Study\python.exe -
```

- [ ] **Step 4: Run complete verification**

Run:

```bash
cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest
cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd test
cd frontend && npm run test
cd frontend && npm run build
docker compose config
```

Expected: all commands exit with code `0`.

- [ ] **Step 5: Verify database artifact**

Run:

```sql
select p.id as project_id,
       p.name,
       p.status,
       a.id as artifact_id,
       a.type,
       a.title,
       a.version,
       a.format,
       char_length(a.content) as content_length
from project p
join artifact a on a.project_id = p.id
where p.name = 'AutoSpec V3 Plan'
  and a.type = 'ROADMAP_PLAN'
  and a.version = 3;
```

Expected: one row with non-zero `content_length`.

- [ ] **Step 6: Commit**

```bash
git add docs
git commit -m "docs: add autospec v3 implementation plan"
```

---

## Execution Order

1. Task 1 establishes V3 persistence boundaries.
2. Task 2 creates the trust boundary that later V3 features depend on.
3. Task 3 makes frontend requests obey the backend trust boundary.
4. Task 4 indexes approved artifacts as durable historical knowledge.
5. Task 5 injects retrieved knowledge into Agent execution inputs.
6. Task 6 records model routing decisions and invocation scores.
7. Task 7 exports code skeleton ZIP files from structured artifacts.
8. Task 8 visualizes the workflow from backend snapshots and events.
9. Task 9 extends Reviewer checks to V3 risks.
10. Task 10 syncs docs, database plan artifact, and full verification evidence.

## Acceptance Checklist

- [ ] Users can log in with a local demo account and project APIs reject non-members.
- [ ] New projects create an owner membership row.
- [ ] Approved artifacts are indexed into knowledge documents and chunks.
- [ ] Agent task inputs include retrieved source metadata when matches exist.
- [ ] Model invocations record provider, model, node, task, status, duration, score, and error.
- [ ] Code skeleton ZIP export includes Spring Boot files, React files, and `AUTOSPEC_MANIFEST.json`.
- [ ] Generated ZIP content excludes API keys, passwords, `.env`, and local absolute paths.
- [ ] Workflow graph API returns nodes, edges, task statuses, and event summaries.
- [ ] Frontend supports login, guarded requests, knowledge sources, model invocation table, code export, and workflow graph.
- [ ] Reviewer detects missing permission boundaries, missing RAG source citations, and secret-like generated code.
- [ ] `cd agent-engine && D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest` passes.
- [ ] `cd backend && D:\apache-maven-3.8.9\bin\mvn.cmd test` passes.
- [ ] `cd frontend && npm run test` passes.
- [ ] `cd frontend && npm run build` passes.
- [ ] `docker compose config` passes.
- [ ] The local database contains the V3 `ROADMAP_PLAN` artifact with the canonical plan content.

## Self-Review

- Spec coverage: all five V3 roadmap items map to tasks and acceptance checks.
- Placeholder scan: the plan contains no intentionally blank implementation steps.
- Type consistency: table names, DTO names, endpoint paths, and artifact names are consistent across tasks.
- Scope control: production OAuth, external vector databases, billing, and full generated business logic are excluded from V3.
