update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, workflow timeout recovery):
- BE-03: Stale RUNNING workflow_run records can now be marked FAILED through WorkflowRunService.timeoutRunningRunsBefore, with error_message and completed_at set for operational recovery.
- BE-08: WorkflowRunServiceTest verifies timeout recovery only affects RUNNING workflow runs older than the cutoff and leaves recent or already terminal runs unchanged.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow timeout recovery%';
