update artifact
set content = concat(content, '

Execution evidence update (2026-07-09, evaluation diagnostics):
- BE-04: GET /api/projects/{projectId}/diagnostics now exposes latestEvaluationOverallScore, latestEvaluationGrade, and latestEvaluationIssueCount from the latest EVALUATION_REPORT artifact so Evaluator output becomes an operational quality signal.
- BE-06: ProjectDiagnosticsResponse documents the evaluation summary fields in the packaged OpenAPI contract, and BackendApiContractTest guards those response fields.
- BE-07: latest EVALUATION_REPORT lookup is backed by idx_artifact_project_type_id on (project_id, type, id), avoiding scans across unrelated artifact types.
- BE-08: BackendDiagnosticsControllerTest verifies score, grade, and issue count extraction from a structured evaluation report; SchemaInitSqlTest verifies the supporting artifact type/latest index through Flyway.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%evaluation diagnostics%';
