update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, Agent task diagnostics):
- BE-04: Project diagnostics now includes agent_task coverage through agentTaskCount and failedAgentTaskCount, so a generation run can be traced across workflow_run, agent_task, audit_event, external_call_log, and model_invocation records.
- BE-06: ProjectDiagnosticsResponse documents agentTaskCount and failedAgentTaskCount in the packaged OpenAPI contract, and BackendApiContractTest guards the response schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%failedAgentTaskCount%';
