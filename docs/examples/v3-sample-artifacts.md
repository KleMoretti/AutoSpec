# AutoSpec V3 Sample Artifacts

These sanitized examples are for local verification and review. They contain no credentials, API keys, database passwords, or local absolute paths.

## Login Response

```json
{
  "userId": 1,
  "username": "owner",
  "displayName": "Demo Owner",
  "roleHint": "OWNER"
}
```

## Knowledge Source List

```json
[
  {
    "artifactId": 42,
    "artifactType": "PRD",
    "artifactTitle": "Campus Marketplace PRD",
    "chunkId": 101,
    "snippet": "Students can publish listings, search listings, favorite products, and request admin review.",
    "score": 7
  },
  {
    "artifactId": 45,
    "artifactType": "BACKEND_DESIGN",
    "artifactTitle": "Campus Marketplace Backend Design",
    "chunkId": 117,
    "snippet": "Project-scoped APIs require authentication and project membership roles.",
    "score": 5
  }
]
```

## Agent Task Input With Retrieved Sources

```json
{
  "requirement": "Build a private project workspace that reuses approved historical requirements.",
  "retrieved_sources": [
    {
      "artifact_id": 42,
      "artifact_type": "PRD",
      "title": "Campus Marketplace PRD",
      "chunk_id": 101,
      "snippet": "Approved PRD source snippet used for requirements reuse."
    }
  ]
}
```

## Model Invocation Summary

```json
{
  "projectId": 7,
  "nodeName": "reviewer",
  "agentName": "ReviewerAgent_v1",
  "providerKey": "local",
  "modelName": "deterministic-fixture",
  "promptVersion": "ReviewerAgent",
  "status": "SUCCEEDED",
  "durationMs": 84,
  "score": 100,
  "errorMessage": null
}
```

## Code Skeleton ZIP Manifest

```json
{
  "generatedBy": "AutoSpec V3",
  "projectId": 7,
  "files": [
    "backend/pom.xml",
    "backend/src/main/java/com/generated/Application.java",
    "backend/src/main/resources/application.yml",
    "frontend/package.json",
    "frontend/src/App.tsx",
    "AUTOSPEC_MANIFEST.json"
  ],
  "configurationPolicy": "Use environment placeholders. Do not embed credentials."
}
```

## Workflow Snapshot

```json
{
  "workflowKey": "autospec-v3",
  "version": "v3",
  "nodes": [
    { "id": "product_manager", "label": "Product Manager", "artifactType": "PRD" },
    { "id": "architect", "label": "Architect", "artifactType": "ARCHITECTURE_DESIGN" },
    { "id": "backend_engineer", "label": "Backend Engineer", "artifactType": "BACKEND_DESIGN" },
    { "id": "frontend_engineer", "label": "Frontend Engineer", "artifactType": "FRONTEND_SKELETON" },
    { "id": "reviewer", "label": "Reviewer", "artifactType": "REVIEW_REPORT" }
  ],
  "edges": [
    { "from": "product_manager", "to": "architect" },
    { "from": "product_manager", "to": "backend_engineer" },
    { "from": "architect", "to": "frontend_engineer" },
    { "from": "backend_engineer", "to": "frontend_engineer" },
    { "from": "frontend_engineer", "to": "reviewer" }
  ]
}
```

## V3 Reviewer Issues

```json
[
  {
    "severity": "HIGH",
    "issue_type": "PERMISSION_BOUNDARY",
    "description": "Project API '/api/projects/{projectId}/artifacts' is outside the authenticated project/member boundary.",
    "suggestion": "Require authentication and at least one project member role for project-scoped APIs."
  },
  {
    "severity": "HIGH",
    "issue_type": "RAG_SOURCE_CITATION",
    "description": "Historical reuse is requested but no retrieved artifact source is attached.",
    "suggestion": "Attach approved artifact source references to Agent task input."
  },
  {
    "severity": "CRITICAL",
    "issue_type": "CODE_EXPORT_SECRET",
    "description": "Generated code skeleton contains secret-like configuration.",
    "suggestion": "Use placeholder variables and .env.example only."
  }
]
```
