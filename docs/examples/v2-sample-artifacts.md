# AutoSpec V2 Sample Artifacts

These are sanitized examples for local verification and review. They are not production output and contain no credentials.

## Approved PRD

```json
{
  "title": "AutoSpec V2",
  "goals": [
    "Generate a reviewable PRD before downstream agent work.",
    "Persist architecture, backend, frontend, and review artifacts."
  ],
  "features": [
    {
      "name": "PRD approval gate",
      "description": "Users can edit and approve the PRD before architecture and implementation design continue."
    },
    {
      "name": "Real-time progress",
      "description": "Agent node events are persisted and streamed to the project detail page."
    }
  ],
  "acceptance_criteria": [
    "Only the approved PRD version is used by downstream nodes.",
    "Every agent task records input, output, status, duration, and error detail."
  ]
}
```

## Architecture Design

```json
{
  "system_context": "Spring Boot owns durable project state; FastAPI owns agent execution; React owns review workflow.",
  "modules": [
    {
      "name": "backend",
      "responsibility": "Persist projects, artifacts, prompt versions, tasks, and events.",
      "depends_on": ["mysql", "redis", "agent-engine"]
    },
    {
      "name": "agent-engine",
      "responsibility": "Run typed V2 agent nodes and reviewer rules.",
      "depends_on": ["llm-provider"]
    },
    {
      "name": "frontend",
      "responsibility": "Review PRD, monitor progress, preview artifacts, retry failed tasks, and export outputs.",
      "depends_on": ["backend"]
    }
  ],
  "decisions": [
    {
      "title": "Backend owns artifact approval",
      "context": "Human approval and retry require durable state.",
      "decision": "Store artifact version and approval state in Spring Boot.",
      "consequences": ["Agent engine remains stateless between HTTP calls."]
    }
  ],
  "non_functional_constraints": [
    {
      "category": "observability",
      "requirement": "Every agent node emits persisted execution events."
    }
  ],
  "integration_risks": [
    "SSE clients can disconnect, so event history remains queryable through REST."
  ]
}
```

## Backend Design

```json
{
  "tables": [
    {
      "name": "artifact",
      "columns": ["status", "source_agent", "parent_artifact_id", "approved_at", "updated_at"]
    },
    {
      "name": "prompt_version",
      "columns": ["prompt_key", "version", "content", "checksum", "active", "created_at"]
    },
    {
      "name": "agent_event",
      "columns": ["project_id", "task_id", "event_type", "node_name", "message", "payload", "created_at"]
    }
  ],
  "apis": [
    "POST /api/projects/{projectId}/generate-prd",
    "PUT /api/projects/{projectId}/artifacts/{artifactId}",
    "POST /api/projects/{projectId}/artifacts/{artifactId}/approve",
    "POST /api/projects/{projectId}/continue",
    "GET /api/projects/{projectId}/events/history",
    "GET /api/projects/{projectId}/events",
    "POST /api/projects/{projectId}/tasks/{taskId}/retry",
    "POST /api/projects/{projectId}/export?format=PDF"
  ]
}
```

## Frontend Skeleton

```json
{
  "routes": [
    {
      "path": "/projects/:projectId",
      "page": "ProjectDetailPage"
    }
  ],
  "pages": [
    {
      "name": "ProjectDetailPage",
      "purpose": "Review PRD, continue generation, monitor events, inspect artifacts, retry failures, and export files.",
      "components": ["PrdEditor", "AgentTimeline", "ExecutionEventList", "ArtifactTabs", "FrontendSkeletonPreview"]
    }
  ],
  "api_bindings": [
    {
      "method": "POST",
      "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
      "consumer": "PrdEditor"
    },
    {
      "method": "GET",
      "path": "/api/projects/{projectId}/events",
      "consumer": "ExecutionEventList"
    }
  ]
}
```

## Review Report

```json
{
  "score": 96,
  "issues": [
    {
      "issue_type": "TRACEABILITY",
      "severity": "LOW",
      "description": "Confirm PDF output sample is attached before release documentation is finalized."
    }
  ],
  "summary": "PRD, architecture, backend APIs, frontend bindings, and retry/event flows are consistent."
}
```

## Export Evidence

- Markdown export includes PRD, architecture design, backend design, frontend skeleton, and review report sections.
- PDF export returns a base64 payload with `mediaType` set to `application/pdf` and a `%PDF` file header after decoding.

Verified commands:

```powershell
& 'D:\apache-maven-3.8.9\bin\mvn.cmd' test
& 'D:\miniconda3\envs\CrewAI_Study\python.exe' -m pytest -q
npm run test
npm run build
```
