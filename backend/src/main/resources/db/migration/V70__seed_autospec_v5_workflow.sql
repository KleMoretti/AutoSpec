insert into workflow_definition (
    workflow_key, name, description, status, created_at, updated_at
)
select
    'autospec-v5',
    'AutoSpec V5 Dynamic Workflow',
    'Versioned DAG with parallel engineering, approvals, targeted rework, recovery, and replay.',
    'ACTIVE',
    current_timestamp,
    current_timestamp
where not exists (
    select 1 from workflow_definition where workflow_key = 'autospec-v5'
);

insert into workflow_version (
    definition_id, version, spec_json, content_hash, status, published_at, created_at
)
select
    definition.id,
    'v5',
    '{
      "workflow_key":"autospec-v5",
      "version":"v5",
      "runtime":{"max_parallel_nodes":4,"max_review_rounds":2,"default_timeout_ms":60000},
      "nodes":[
        {
          "node_id":"product_manager","agent_name":"ProductManagerAgent_v1",
          "input_schema":"GenerateRequest","output_schema":"PrdArtifact","artifact_type":"PRD",
          "prompt_key":"product_manager","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-fixture","temperature":0},
          "retry_policy":{"max_attempts":2,"retryable_errors":["MODEL_TIMEOUT","PROVIDER_UNAVAILABLE"]},
          "timeout_ms":60000,"depends_on":[],
          "approval":{"mode":"AFTER_NODE","allowed_actions":["APPROVE","REJECT","EDIT_AND_APPROVE"]}
        },
        {
          "node_id":"architect","agent_name":"ArchitectAgent_v1",
          "input_schema":"ArchitectureInput","output_schema":"ArchitectureDesignArtifact","artifact_type":"ARCHITECTURE_DESIGN",
          "prompt_key":"architect","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-fixture","temperature":0},
          "retry_policy":{"max_attempts":2,"retryable_errors":["MODEL_TIMEOUT","PROVIDER_UNAVAILABLE"]},
          "timeout_ms":60000,"depends_on":["product_manager"]
        },
        {
          "node_id":"backend_engineer","agent_name":"BackendEngineerAgent_v1",
          "input_schema":"BackendDesignInput","output_schema":"BackendDesignArtifact","artifact_type":"BACKEND_DESIGN",
          "prompt_key":"backend_engineer","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-fixture","temperature":0},
          "retry_policy":{"max_attempts":2,"retryable_errors":["MODEL_TIMEOUT","PROVIDER_UNAVAILABLE"]},
          "timeout_ms":60000,"depends_on":["architect"]
        },
        {
          "node_id":"frontend_engineer","agent_name":"FrontendEngineerAgent_v1",
          "input_schema":"FrontendSkeletonInput","output_schema":"FrontendSkeletonArtifact","artifact_type":"FRONTEND_SKELETON",
          "prompt_key":"frontend_engineer","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-fixture","temperature":0},
          "retry_policy":{"max_attempts":2,"retryable_errors":["MODEL_TIMEOUT","PROVIDER_UNAVAILABLE"]},
          "timeout_ms":60000,"depends_on":["architect"]
        },
        {
          "node_id":"reviewer","agent_name":"ReviewerAgent_v1",
          "input_schema":"ReviewInput","output_schema":"ReviewReport","artifact_type":"REVIEW_REPORT",
          "prompt_key":"reviewer","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-fixture","temperature":0},
          "retry_policy":{"max_attempts":2,"retryable_errors":["MODEL_TIMEOUT","PROVIDER_UNAVAILABLE"]},
          "timeout_ms":60000,"depends_on":["backend_engineer","frontend_engineer"]
        },
        {
          "node_id":"evaluator","agent_name":"EvaluatorAgent_v1",
          "input_schema":"EvaluationInput","output_schema":"EvaluationReport","artifact_type":"EVALUATION_REPORT",
          "prompt_key":"evaluator","prompt_version":"v1",
          "model_policy":{"provider_key":"local","model_name":"deterministic-rules","temperature":0},
          "retry_policy":{"max_attempts":1},
          "timeout_ms":60000,"depends_on":["reviewer"]
        }
      ],
      "edges":[
        {"from_node":"product_manager","to_node":"architect"},
        {"from_node":"architect","to_node":"backend_engineer"},
        {"from_node":"architect","to_node":"frontend_engineer"},
        {"from_node":"backend_engineer","to_node":"reviewer"},
        {"from_node":"frontend_engineer","to_node":"reviewer"},
        {"from_node":"reviewer","to_node":"evaluator"},
        {"from_node":"reviewer","to_node":"architect","edge_type":"REWORK","condition":{"path":"$.routes","operator":"EXISTS"}},
        {"from_node":"reviewer","to_node":"backend_engineer","edge_type":"REWORK","condition":{"path":"$.routes","operator":"EXISTS"}},
        {"from_node":"reviewer","to_node":"frontend_engineer","edge_type":"REWORK","condition":{"path":"$.routes","operator":"EXISTS"}}
      ]
    }',
    'builtin-autospec-v5-v5',
    'PUBLISHED',
    current_timestamp,
    current_timestamp
from workflow_definition definition
where definition.workflow_key = 'autospec-v5'
  and not exists (
      select 1
      from workflow_version version
      where version.definition_id = definition.id and version.version = 'v5'
  );
