update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, model prompt traceability):
- BE-04: GET /api/projects/{projectId}/model-invocations now exposes promptVersionId so each model invocation can be traced back to the active Agent prompt version used for that call.
- BE-01: DefaultPromptSeeder registers default active v1 prompts for the core Agent roles when no active version exists, so cold-start model invocations have prompt-version lineage without overwriting later prompt governance changes.
- BE-06: ModelInvocationResponse documents promptVersionId in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest plus ProjectControllerTest guard the response contract.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%model prompt traceability%';
