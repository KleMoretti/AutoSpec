# AutoSpec V4 Sample Artifacts

These sanitized examples are for local verification and review. They contain no credentials, API keys, database secrets, or local absolute paths.

## Workflow Spec Summary

```json
{
  "workflow_key": "autospec-v4",
  "version": "v4",
  "nodes": [
    {
      "node_id": "product_manager",
      "agent_name": "ProductManagerAgent_v1",
      "input_schema": "GenerateRequest",
      "output_schema": "PrdArtifact",
      "artifact_type": "PRD"
    },
    {
      "node_id": "human_prd_approval",
      "agent_name": "HumanApprovalGate",
      "input_schema": "PrdArtifact",
      "output_schema": "PrdArtifact",
      "artifact_type": "PRD",
      "requires_human_approval": true
    },
    {
      "node_id": "evaluator",
      "agent_name": "EvaluatorAgent_v1",
      "input_schema": "EvaluationInput",
      "output_schema": "EvaluationReport",
      "artifact_type": "EVALUATION_REPORT",
      "depends_on": ["reviewer"]
    }
  ]
}
```

## Evaluation Report

```json
{
  "overall_score": 92,
  "final_grade": "A",
  "dimension_scores": [
    {
      "dimension": "SCHEMA_VALIDITY",
      "score": 100,
      "rationale": "Artifacts were parsed through strict Pydantic schemas before evaluation."
    },
    {
      "dimension": "REQUIREMENT_COVERAGE",
      "score": 90,
      "rationale": "Core PRD capability terms are represented in downstream artifacts."
    },
    {
      "dimension": "CROSS_ARTIFACT_CONSISTENCY",
      "score": 90,
      "rationale": "Backend API paths are represented in frontend bindings."
    },
    {
      "dimension": "PERMISSION_COVERAGE",
      "score": 100,
      "rationale": "Project-scoped APIs require authentication and roles."
    },
    {
      "dimension": "RAG_CITATION_QUALITY",
      "score": 100,
      "rationale": "Retrieved historical sources are attached."
    },
    {
      "dimension": "RUNTIME_RELIABILITY",
      "score": 100,
      "rationale": "All recorded Agent nodes succeeded."
    },
    {
      "dimension": "EXPORT_READINESS",
      "score": 100,
      "rationale": "Generated files use environment placeholders."
    }
  ],
  "issues": []
}
```

## Evaluation Issue Example

```json
{
  "severity": "HIGH",
  "issue_type": "FRONTEND_COVERAGE",
  "description": "Frontend skeleton is missing API bindings for: /api/projects/{projectId}/artifacts.",
  "suggestion": "Add frontend api_bindings for backend APIs used by the user workflow.",
  "evidence": ["/api/projects/{projectId}/artifacts"]
}
```

## Persisted Artifact Contract

```json
{
  "type": "EVALUATION_REPORT",
  "title": "Evaluation Project Evaluation Report",
  "format": "JSON",
  "version": 1,
  "status": "GENERATED",
  "source_agent": "EvaluatorAgent_v1"
}
```
