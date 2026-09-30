-- Seed the downstream-context-budget candidate without changing active or historical versions.
insert into workflow_version (
    definition_id, version, spec_json, content_hash, status, published_at, immutable_at, created_at
)
select definition.id,
       'pm-schema-repair-v9',
       '{
  "workflow_key": "autospec-v5",
  "version": "pm-schema-repair-v9",
  "protocol_version": 2,
  "runtime": {
    "max_parallel_nodes": 4,
    "max_review_rounds": 2,
    "default_timeout_ms": 60000
  },
  "nodes": [
    {
      "node_id": "product_manager",
      "agent_name": "ProductManagerAgent_v2",
      "input_schema": "GenerateRequest",
      "input_schema_hash": "bd917cb829f2341ec31c77868d3061b13c49ee965d99f83feb363bb3e6d808e8",
      "output_schema": "PrdArtifact",
      "output_schema_hash": "86e9680fe28afcd4f0c884c90ad8a52b8da54be00d0edbdcaf2ebb1a7329dd53",
      "artifact_type": "PRD",
      "prompt_key": "product_manager_schema",
      "prompt_version": "v1",
      "prompt_checksum": "a1372c35d03ce368f8106a5ec81678dd029c292306796c67cc880d42c471f25d",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 12000,
        "prompt_token_reserve": 3072,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "retrieval_policy",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 5000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "fast",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 8000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 120000,
      "depends_on": [],
      "approval": {
        "mode": "AFTER_NODE",
        "allowed_actions": [
          "APPROVE",
          "REJECT",
          "EDIT_AND_APPROVE"
        ]
      }
    },
    {
      "node_id": "architect",
      "agent_name": "ArchitectAgent_v3",
      "input_schema": "ArchitectureInput",
      "input_schema_hash": "358314862f4058c09511512f8dd8ac899e78b96cd28880431f6d41e1a0d44197",
      "output_schema": "ArchitectureDesignArtifactV2",
      "output_schema_hash": "532bf9798d24a26c31779cc79a3996b3c71596dc35cfab6e264e0da1007bfea9",
      "artifact_type": "ARCHITECTURE_DESIGN",
      "prompt_key": "architect_schema",
      "prompt_version": "v1",
      "prompt_checksum": "978363d93b3a82441815a09435e2cea448350ec062b4e1a643820f668e4e1c80",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 16000,
        "prompt_token_reserve": 6000,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 7000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "balanced",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 8000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "product_manager"
      ]
    },
    {
      "node_id": "backend_engineer",
      "agent_name": "BackendEngineerAgent_v6",
      "input_schema": "BackendDesignInput",
      "input_schema_hash": "90ed365ac8e9c15c75c6141fcca8cc006556b40bc0b88f8023f1de6bc1f77d41",
      "output_schema": "BackendDesignArtifact",
      "output_schema_hash": "c613a1869f67b1147851f3b6a19263e778636503409bc45bb73234fb61b16456",
      "artifact_type": "BACKEND_DESIGN",
      "prompt_key": "backend_engineer_loop_v3",
      "prompt_version": "v1",
      "prompt_checksum": "2129ba20528487c4355133aa3e2d208424741dfba829e7e3134fb713f75fa569",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 24000,
        "prompt_token_reserve": 1500,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 8500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "balanced",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 5,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "tool_policy": {
        "version": "tools-v1",
        "enabled": true,
        "allowed_tools": [
          {
            "name": "spec.verify",
            "version": "v1"
          }
        ],
        "max_calls": 2,
        "per_call_timeout_ms": 30000,
        "total_timeout_ms": 30000,
        "max_result_bytes": 32000,
        "allowed_side_effects": [
          "SANDBOXED"
        ],
        "permission_policy": "workflow"
      },
      "agent_loop_policy": {
        "version": "agent-loop-v2",
        "enabled": true,
        "strategy": "plan-act-observe-validate-v1",
        "max_steps": 7,
        "max_replans": 1,
        "no_progress_limit": 1,
        "validator_profile": "backend-design-v2"
      },
      "verification_policy": {
        "enabled": true,
        "scope": "BACKEND",
        "required_level": "L1",
        "rule_profile": "spec-backend-v1",
        "verifier_version": "spec-verifier-v1",
        "compiler_version": "spec-compiler-v1",
        "timeout_ms": 30000,
        "policy_hash": "07f90333460fec2a396e2dabd8e275cfac90edf9e842af49083ff878f75e207e"
      },
      "timeout_ms": 60000,
      "depends_on": [
        "architect"
      ]
    },
    {
      "node_id": "frontend_engineer",
      "agent_name": "FrontendEngineerAgent_v3",
      "input_schema": "FrontendSkeletonInputV2",
      "input_schema_hash": "c64fc55ec5d53abec909ed8c53159cfa4a1418cf85142477e5498a66a524812b",
      "output_schema": "FrontendSkeletonArtifact",
      "output_schema_hash": "2490b7c0f0e5f8dfaaf62c4a2a8807f2f05d85ae75fb670fa0cbfbe9789e5dbd",
      "artifact_type": "FRONTEND_SKELETON",
      "prompt_key": "frontend_schema",
      "prompt_version": "v1",
      "prompt_checksum": "ce3301ab75f1572856630c8c40f030df4b6a4834837619f290375373b19ba79c",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 24000,
        "prompt_token_reserve": 2048,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 8500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "fast",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "architect"
      ]
    },
    {
      "node_id": "reviewer",
      "agent_name": "ReviewerAgent_v5",
      "input_schema": "ReviewInputV4",
      "input_schema_hash": "a7f3229e9dc9ad7fe825eba985328d9c55b5c3721946abe3a65c469c7686fd92",
      "output_schema": "ReviewReportV2",
      "output_schema_hash": "620549ea2a9afda11a3b07ddc10a8158770e65619690cc1193f2cb4241c90824",
      "artifact_type": "REVIEW_REPORT",
      "prompt_key": "reviewer_schema_v2",
      "prompt_version": "v1",
      "prompt_checksum": "462c89ca7b20c6f05969906e13311ca2626c128a2fa9f979a600e5f06624e8b7",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 30000,
        "prompt_token_reserve": 2048,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "prd",
          "architecture_design",
          "backend_design",
          "frontend_skeleton",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive",
          "$.architecture_design.shared_contract"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 9500,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "route_key": "deep",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 4000,
        "max_calls": 2,
        "input_cost_per_million": 2,
        "cached_input_cost_per_million": 0.04,
        "output_cost_per_million": 8,
        "required_capabilities": [
          "json_object",
          "usage",
          "idempotency"
        ],
        "structured_output_repair": {
          "enabled": true,
          "max_repairs": 1,
          "error_codes": [
            "STRUCTURED_OUTPUT_INVALID",
            "VALIDATION_ERROR"
          ]
        },
        "provider_key": "deepseek",
        "model_name": "deepseek-flash",
        "thinking_mode": "disabled"
      },
      "retry_policy": {
        "max_attempts": 2,
        "retryable_errors": [
          "MODEL_TIMEOUT",
          "PROVIDER_UNAVAILABLE"
        ]
      },
      "timeout_ms": 60000,
      "depends_on": [
        "backend_engineer",
        "frontend_engineer"
      ],
      "tool_policy": {
        "version": "tools-v1",
        "enabled": true,
        "allowed_tools": [
          {
            "name": "spec.verify",
            "version": "v1"
          }
        ],
        "max_calls": 1,
        "per_call_timeout_ms": 30000,
        "total_timeout_ms": 30000,
        "max_result_bytes": 32000,
        "allowed_side_effects": [
          "SANDBOXED"
        ],
        "permission_policy": "workflow"
      },
      "verification_policy": {
        "enabled": true,
        "scope": "FULL",
        "required_level": "L1",
        "rule_profile": "spec-full-v1",
        "verifier_version": "spec-verifier-v1",
        "compiler_version": "spec-compiler-v1",
        "timeout_ms": 30000,
        "policy_hash": "cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794"
      }
    },
    {
      "node_id": "evaluator",
      "agent_name": "EvaluatorAgent_v3",
      "input_schema": "EvaluationInputV2",
      "input_schema_hash": "0d9e4ea2027e3067ba8a9ebca1b98f1748ff5710391da9f7005394e98dce8280",
      "output_schema": "EvaluationReportV2",
      "output_schema_hash": "105c4913d48de2f875f8eafd17adc0870e1abb58d89eae44aeebfe0905ee9cf3",
      "artifact_type": "EVALUATION_REPORT",
      "prompt_key": "evaluator",
      "prompt_version": "v1",
      "prompt_checksum": "393abcdb2d663e41b8d7dfebe093f5f43fa4bdb6d3c46165ee6425aea3419fd2",
      "context_policy": {
        "version": "context-v2",
        "tokenizer": "conservative-multilingual-v1",
        "max_input_tokens": 40000,
        "prompt_token_reserve": 1500,
        "manifest_token_reserve": 512,
        "field_priority": [
          "requirement",
          "execution_policy",
          "rework_directive",
          "artifacts",
          "review_report",
          "invocation_ledger",
          "retrieved_sources"
        ],
        "required_paths": [
          "$.requirement",
          "$.execution_policy",
          "$.retrieval_policy",
          "$.rework_directive"
        ],
        "compression_strategy": "schema-aware-v2",
        "rag_token_budget": 2000,
        "long_text_token_budget": 22000,
        "max_single_source_ratio": 0.35
      },
      "model_policy": {
        "provider_key": "local",
        "model_name": "deterministic-rules",
        "temperature": 0,
        "context_window_tokens": 64000,
        "max_output_tokens": 2000,
        "max_calls": 1,
        "input_cost_per_million": 0,
        "cached_input_cost_per_million": 0,
        "output_cost_per_million": 0,
        "required_capabilities": [
          "deterministic"
        ]
      },
      "retry_policy": {
        "max_attempts": 1
      },
      "timeout_ms": 60000,
      "depends_on": [
        "reviewer"
      ]
    }
  ],
  "edges": [
    {
      "from_node": "product_manager",
      "to_node": "architect"
    },
    {
      "from_node": "architect",
      "to_node": "backend_engineer"
    },
    {
      "from_node": "architect",
      "to_node": "frontend_engineer"
    },
    {
      "from_node": "backend_engineer",
      "to_node": "reviewer"
    },
    {
      "from_node": "frontend_engineer",
      "to_node": "reviewer"
    },
    {
      "from_node": "reviewer",
      "to_node": "evaluator"
    },
    {
      "from_node": "reviewer",
      "to_node": "architect",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    },
    {
      "from_node": "reviewer",
      "to_node": "backend_engineer",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    },
    {
      "from_node": "reviewer",
      "to_node": "frontend_engineer",
      "edge_type": "REWORK",
      "condition": {
        "path": "$.routes",
        "operator": "EXISTS"
      }
    }
  ]
}',
       '7b4addbbad811aed414fe170a3560071a86c6291817cf0c8724de07562e0e76c',
       'DRAFT',
       null,
       null,
       now()
from workflow_definition definition
where definition.workflow_key = 'autospec-v5'
  and not exists (
      select 1 from workflow_version version
      where version.definition_id = definition.id
        and version.version = 'pm-schema-repair-v9'
  );
