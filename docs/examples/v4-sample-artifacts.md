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

## Evaluation Case

```json
{
  "case_id": "campus_marketplace",
  "title": "Campus Second-Hand Marketplace",
  "requirement": "Build a campus second-hand marketplace where students can publish products, search listings, favorite products, start order transactions, and admins can audit listings.",
  "expected_capabilities": [
    "product publishing",
    "product search",
    "favorite products",
    "order transaction",
    "admin audit permission"
  ],
  "required_artifact_types": [
    "PRD",
    "ARCHITECTURE_DESIGN",
    "BACKEND_DESIGN",
    "FRONTEND_SKELETON",
    "REVIEW_REPORT",
    "EVALUATION_REPORT"
  ],
  "scoring_dimensions": [
    "SCHEMA_VALIDITY",
    "REQUIREMENT_COVERAGE",
    "CROSS_ARTIFACT_CONSISTENCY",
    "PERMISSION_COVERAGE",
    "RAG_CITATION_QUALITY",
    "RUNTIME_RELIABILITY",
    "EXPORT_READINESS"
  ]
}
```

## Experiment Comparison Report

```json
{
  "baseline_run_id": "autospec-v3-baseline",
  "best_run_id": "autospec-v4-candidate",
  "rankings": [
    {
      "run_id": "autospec-v4-candidate",
      "workflow_key": "autospec-v4",
      "workflow_version": "v4",
      "prompt_versions": {
        "reviewer": "v1",
        "evaluator": "v1"
      },
      "model_config": {
        "reviewer": "deterministic-fixture",
        "evaluator": "deterministic-rules"
      },
      "overall_score": 92,
      "duration_ms": 1500,
      "status": "SUCCEEDED",
      "estimated_cost": 0.1,
      "failure_count": 0
    },
    {
      "run_id": "autospec-v3-baseline",
      "workflow_key": "autospec-v3",
      "workflow_version": "v3",
      "prompt_versions": {
        "reviewer": "v1"
      },
      "model_config": {
        "reviewer": "deterministic-fixture"
      },
      "overall_score": 82,
      "duration_ms": 1200,
      "status": "SUCCEEDED",
      "estimated_cost": 0.08,
      "failure_count": 0
    }
  ],
  "comparisons": [
    {
      "baseline_run_id": "autospec-v3-baseline",
      "candidate_run_id": "autospec-v4-candidate",
      "score_delta": 10,
      "duration_delta_ms": 300,
      "cost_delta": 0.02,
      "failure_delta": 0
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

## V4 Execution Trace

```json
[
  {
    "node_name": "product_manager",
    "agent_name": "ProductManagerAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 31
  },
  {
    "node_name": "architect",
    "agent_name": "ArchitectAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 27
  },
  {
    "node_name": "backend_engineer",
    "agent_name": "BackendEngineerAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 29
  },
  {
    "node_name": "frontend_engineer",
    "agent_name": "FrontendEngineerAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 24
  },
  {
    "node_name": "reviewer",
    "agent_name": "ReviewerAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 18
  },
  {
    "node_name": "evaluator",
    "agent_name": "EvaluatorAgent_v1",
    "status": "SUCCEEDED",
    "duration_ms": 12
  }
]
```
