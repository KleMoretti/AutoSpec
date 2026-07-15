from __future__ import annotations

from copy import deepcopy

from schemas.workflow_spec import WorkflowSpec


def get_workflow_spec(workflow_key: str) -> WorkflowSpec:
    specs = {
        "autospec-v4": _AUTOSPEC_V4_SPEC,
        "autospec-v5": _AUTOSPEC_V5_SPEC,
    }
    if workflow_key not in specs:
        raise KeyError(f"unknown workflow spec: {workflow_key}")
    return WorkflowSpec.model_validate(specs[workflow_key])


_MODEL_POLICY = {
    "provider_key": "local",
    "model_name": "deterministic-fixture",
    "temperature": 0.0,
}

_RETRY_POLICY = {
    "max_attempts": 1,
    "retry_on_validation_error": False,
}

_AUTOSPEC_V4_SPEC = {
    "workflow_key": "autospec-v4",
    "version": "v4",
    "nodes": [
        {
            "node_id": "product_manager",
            "agent_name": "ProductManagerAgent_v1",
            "input_schema": "GenerateRequest",
            "output_schema": "PrdArtifact",
            "artifact_type": "PRD",
            "prompt_key": "product_manager",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": [],
        },
        {
            "node_id": "human_prd_approval",
            "agent_name": "HumanApprovalGate",
            "input_schema": "PrdArtifact",
            "output_schema": "PrdArtifact",
            "artifact_type": "PRD",
            "prompt_key": "human_prd_approval",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": True,
            "depends_on": ["product_manager"],
        },
        {
            "node_id": "architect",
            "agent_name": "ArchitectAgent_v1",
            "input_schema": "ArchitectureInput",
            "output_schema": "ArchitectureDesignArtifact",
            "artifact_type": "ARCHITECTURE_DESIGN",
            "prompt_key": "architect",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": ["human_prd_approval"],
        },
        {
            "node_id": "backend_engineer",
            "agent_name": "BackendEngineerAgent_v1",
            "input_schema": "BackendDesignInput",
            "output_schema": "BackendDesignArtifact",
            "artifact_type": "BACKEND_DESIGN",
            "prompt_key": "backend_engineer",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": ["human_prd_approval", "architect"],
        },
        {
            "node_id": "frontend_engineer",
            "agent_name": "FrontendEngineerAgent_v1",
            "input_schema": "FrontendSkeletonInput",
            "output_schema": "FrontendSkeletonArtifact",
            "artifact_type": "FRONTEND_SKELETON",
            "prompt_key": "frontend_engineer",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": ["human_prd_approval", "architect", "backend_engineer"],
        },
        {
            "node_id": "reviewer",
            "agent_name": "ReviewerAgent_v1",
            "input_schema": "ReviewInput",
            "output_schema": "ReviewReport",
            "artifact_type": "REVIEW_REPORT",
            "prompt_key": "reviewer",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": ["backend_engineer", "frontend_engineer"],
        },
        {
            "node_id": "evaluator",
            "agent_name": "EvaluatorAgent_v1",
            "input_schema": "EvaluationInput",
            "output_schema": "EvaluationReport",
            "artifact_type": "EVALUATION_REPORT",
            "prompt_key": "evaluator",
            "prompt_version": "v1",
            "model_policy": _MODEL_POLICY,
            "retry_policy": _RETRY_POLICY,
            "timeout_ms": 30000,
            "requires_human_approval": False,
            "depends_on": ["reviewer"],
        },
    ],
    "edges": [
        {"from_node": "product_manager", "to_node": "human_prd_approval"},
        {"from_node": "human_prd_approval", "to_node": "architect"},
        {"from_node": "architect", "to_node": "backend_engineer"},
        {"from_node": "backend_engineer", "to_node": "frontend_engineer"},
        {"from_node": "frontend_engineer", "to_node": "reviewer"},
        {"from_node": "reviewer", "to_node": "evaluator"},
    ],
}


_AUTOSPEC_V5_SPEC = deepcopy(_AUTOSPEC_V4_SPEC)
_AUTOSPEC_V5_SPEC["workflow_key"] = "autospec-v5"
_AUTOSPEC_V5_SPEC["version"] = "v5"
_AUTOSPEC_V5_SPEC["runtime"] = {
    "max_parallel_nodes": 4,
    "max_review_rounds": 2,
    "default_timeout_ms": 60000,
}

_V5_NODES = {node["node_id"]: node for node in _AUTOSPEC_V5_SPEC["nodes"]}
_V5_NODES["product_manager"]["approval"] = {
    "mode": "AFTER_NODE",
    "allowed_actions": ["APPROVE", "REJECT", "EDIT_AND_APPROVE"],
}
_V5_NODES["backend_engineer"]["depends_on"] = ["architect"]
_V5_NODES["frontend_engineer"]["depends_on"] = ["architect"]
_V5_NODES["reviewer"]["depends_on"] = ["backend_engineer", "frontend_engineer"]

_AUTOSPEC_V5_SPEC["edges"] = [
    {"from_node": "product_manager", "to_node": "human_prd_approval"},
    {"from_node": "human_prd_approval", "to_node": "architect"},
    {"from_node": "architect", "to_node": "backend_engineer"},
    {"from_node": "architect", "to_node": "frontend_engineer"},
    {"from_node": "backend_engineer", "to_node": "reviewer"},
    {"from_node": "frontend_engineer", "to_node": "reviewer"},
    {"from_node": "reviewer", "to_node": "evaluator"},
    {
        "from_node": "reviewer",
        "to_node": "architect",
        "edge_type": "REWORK",
        "condition": {"path": "$.routes", "operator": "EXISTS"},
    },
    {
        "from_node": "reviewer",
        "to_node": "backend_engineer",
        "edge_type": "REWORK",
        "condition": {"path": "$.routes", "operator": "EXISTS"},
    },
    {
        "from_node": "reviewer",
        "to_node": "frontend_engineer",
        "edge_type": "REWORK",
        "condition": {"path": "$.routes", "operator": "EXISTS"},
    },
]
