from graph.workflow import run_v1_workflow, run_v2_node, run_v2_workflow, run_v4_workflow
from main import v4_response


class FakeModelClient:
    def __init__(self):
        self.calls = []

    def generate_json(self, prompt_name, input_payload):
        self.calls.append((prompt_name, input_payload))
        if prompt_name == "ProductManagerAgent_v1":
            return {
                "project_name": "Campus Second-Hand Marketplace",
                "target_users": ["student", "admin"],
                "core_features": [
                    {
                        "name": "Product publishing",
                        "description": "Students publish products.",
                        "priority": "MUST",
                    }
                ],
                "user_stories": [
                    {
                        "role": "student",
                        "goal": "publish an idle textbook",
                        "benefit": "find a buyer on campus",
                        "acceptance_criteria": ["Product is saved."],
                    }
                ],
                "business_boundaries": ["V1 does not support payment escrow."],
                "non_functional_requirements": ["Persist every agent input and output."],
                "risks": ["Product moderation may be incomplete."],
            }
        if prompt_name == "BackendEngineerAgent_v1":
            return {
                "tables": [
                    {
                        "name": "product",
                        "description": "Product listing.",
                        "fields": [
                            {
                                "name": "id",
                                "type": "BIGINT",
                                "nullable": False,
                                "description": "Primary key.",
                            }
                        ],
                    }
                ],
                "apis": [
                    {
                        "method": "POST",
                        "path": "/api/products",
                        "description": "Publish a product.",
                        "request_params": [],
                        "response_fields": [
                            {
                                "name": "productId",
                                "type": "number",
                                "description": "Created product id.",
                            }
                        ],
                        "auth_required": True,
                        "required_roles": ["STUDENT"],
                    }
                ],
            }
        if prompt_name == "ReviewerAgent_v1":
            return {"score": 95, "issues": []}
        raise AssertionError(f"unexpected prompt {prompt_name}")


class V2FakeModelClient:
    def __init__(self):
        self.calls = []

    def generate_json(self, prompt_name, input_payload):
        self.calls.append((prompt_name, input_payload))
        if prompt_name == "ProductManagerAgent_v1":
            return valid_prd_payload()
        if prompt_name == "ArchitectAgent_v1":
            return valid_architecture_payload()
        if prompt_name == "BackendEngineerAgent_v1":
            return valid_backend_payload()
        if prompt_name == "FrontendEngineerAgent_v1":
            return valid_frontend_payload()
        if prompt_name == "ReviewerAgent_v1":
            return {"score": 97, "issues": []}
        raise AssertionError(f"unexpected prompt {prompt_name}")


def valid_prd_payload():
    return {
        "project_name": "AutoSpec V2",
        "target_users": ["product manager", "developer"],
        "core_features": [
            {
                "name": "PRD editing",
                "description": "Users can review and approve generated PRDs.",
                "priority": "MUST",
            },
            {
                "name": "real-time progress",
                "description": "Users can watch streaming Agent events.",
                "priority": "MUST",
            },
            {
                "name": "retry regeneration",
                "description": "Users can retry failed Agent nodes.",
                "priority": "SHOULD",
            },
        ],
        "user_stories": [
            {
                "role": "product manager",
                "goal": "approve a generated PRD",
                "benefit": "control downstream artifacts",
                "acceptance_criteria": ["Approved PRD content is used for generation."],
            }
        ],
        "business_boundaries": ["V2 does not include login."],
        "non_functional_requirements": ["Every Agent node emits events."],
        "risks": ["Artifacts can drift without reviewer checks."],
    }


def valid_architecture_payload():
    return {
        "system_context": "AutoSpec coordinates backend, frontend, and agent-engine event flows.",
        "modules": [
            {
                "name": "backend",
                "responsibility": "Persist projects, artifacts, tasks, and events.",
                "depends_on": ["agent-engine", "mysql", "redis"],
            },
            {
                "name": "frontend",
                "responsibility": "Render PRD editing and Agent event progress.",
                "depends_on": ["backend"],
            },
        ],
        "decisions": [
            {
                "title": "Persist Agent events",
                "context": "Real-time clients can disconnect.",
                "decision": "Store events in the backend and stream them with SSE.",
                "consequences": ["Clients can reload event history."],
            }
        ],
        "non_functional_constraints": [
            {
                "category": "observability",
                "requirement": "Every Agent node emits event records.",
            }
        ],
        "integration_risks": ["SSE clients need reconnect handling."],
    }


def valid_backend_payload():
    return {
        "tables": [
            {
                "name": "artifact",
                "description": "Generated and approved artifacts.",
                "fields": [
                    {
                        "name": "id",
                        "type": "BIGINT",
                        "nullable": False,
                        "description": "Primary key.",
                    },
                    {
                        "name": "approved_at",
                        "type": "TIMESTAMP",
                        "nullable": True,
                        "description": "Approval timestamp.",
                    },
                ],
            },
            {
                "name": "agent_event",
                "description": "Persisted Agent execution events.",
                "fields": [
                    {
                        "name": "id",
                        "type": "BIGINT",
                        "nullable": False,
                        "description": "Primary key.",
                    }
                ],
            },
        ],
        "apis": [
            {
                "method": "POST",
                "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                "description": "Approve a reviewed PRD artifact.",
                "request_params": [],
                "response_fields": [
                    {
                        "name": "status",
                        "type": "string",
                        "description": "Approval status.",
                    }
                ],
                "auth_required": True,
                "required_roles": ["USER"],
            },
            {
                "method": "GET",
                "path": "/api/projects/{projectId}/events",
                "description": "Stream persisted Agent events.",
                "request_params": [],
                "response_fields": [
                    {
                        "name": "events",
                        "type": "AgentEvent[]",
                        "description": "Execution events.",
                    }
                ],
                "auth_required": True,
                "required_roles": ["USER"],
            },
            {
                "method": "POST",
                "path": "/api/projects/{projectId}/tasks/{taskId}/retry",
                "description": "Retry a failed Agent node.",
                "request_params": [],
                "response_fields": [
                    {
                        "name": "status",
                        "type": "string",
                        "description": "Retry status.",
                    }
                ],
                "auth_required": True,
                "required_roles": ["USER"],
            },
        ],
    }


def valid_frontend_payload():
    return {
        "routes": [{"path": "/projects/:projectId", "page": "ProjectDetailPage"}],
        "pages": [
            {
                "name": "ProjectDetailPage",
                "purpose": "Review PRD, monitor generation, and inspect artifacts.",
                "components": ["PrdEditor", "AgentTimeline", "ArtifactTabs"],
            }
        ],
        "components": [
            {
                "name": "PrdEditor",
                "type": "form",
                "props": ["artifact", "onSave", "onApprove"],
                "state": ["draftContent", "saving"],
            },
            {
                "name": "AgentTimeline",
                "type": "timeline",
                "props": ["events"],
                "state": [],
            },
        ],
        "api_bindings": [
            {
                "method": "POST",
                "path": "/api/projects/{projectId}/artifacts/{artifactId}/approve",
                "consumer": "PrdEditor",
            },
            {
                "method": "GET",
                "path": "/api/projects/{projectId}/events",
                "consumer": "AgentTimeline",
            },
        ],
    }


def test_v1_workflow_runs_nodes_in_order_and_records_callbacks():
    records = []
    model_client = FakeModelClient()

    result = run_v1_workflow(
        "Build a campus second-hand marketplace.",
        model_client=model_client,
        callbacks=[records.append],
    )

    assert [call[0] for call in model_client.calls] == [
        "ProductManagerAgent_v1",
        "BackendEngineerAgent_v1",
        "ReviewerAgent_v1",
    ]
    assert result.prd.project_name == "Campus Second-Hand Marketplace"
    assert result.backend_design.apis[0].path == "/api/products"
    assert result.review_report.score == 95
    assert [record.node_name for record in records] == [
        "product_manager",
        "backend_engineer",
        "reviewer",
    ]
    assert all(record.status == "SUCCEEDED" for record in records)


def test_default_v1_workflow_does_not_flag_product_publish_as_admin_audit_api():
    result = run_v1_workflow("Build a campus second-hand marketplace.")

    assert result.review_report.score == 100
    assert result.review_report.issues == []


def test_v2_workflow_runs_architect_frontend_and_reviewer_after_prd():
    records = []
    model_client = V2FakeModelClient()

    result = run_v2_workflow(
        "Build AutoSpec V2.",
        model_client=model_client,
        callbacks=[records.append],
    )

    assert [call[0] for call in model_client.calls] == [
        "ProductManagerAgent_v1",
        "ArchitectAgent_v1",
        "BackendEngineerAgent_v1",
        "FrontendEngineerAgent_v1",
        "ReviewerAgent_v1",
    ]
    assert result.architecture_design.modules[0].name == "backend"
    assert result.frontend_skeleton.routes[0].path == "/projects/:projectId"
    assert [record.node_name for record in records] == [
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
    ]


def test_v2_workflow_passes_retrieved_sources_to_model_prompts():
    model_client = V2FakeModelClient()
    retrieved_sources = [
        {
            "artifact_id": 9,
            "artifact_type": "PRD",
            "title": "Approved Marketplace PRD",
            "content": "Favorites require create and delete APIs.",
        }
    ]

    run_v2_workflow(
        "Build AutoSpec V2 with historical reuse.",
        model_client=model_client,
        retrieved_sources=retrieved_sources,
    )

    assert model_client.calls[0][1]["retrieved_sources"] == retrieved_sources
    assert model_client.calls[1][1]["retrieved_sources"] == retrieved_sources
    assert model_client.calls[2][1]["retrieved_sources"] == retrieved_sources
    assert model_client.calls[3][1]["retrieved_sources"] == retrieved_sources
    assert model_client.calls[4][1]["retrieved_sources"] == retrieved_sources


def test_run_v2_node_runs_single_frontend_engineer_node():
    model_client = V2FakeModelClient()
    payload = {
        "requirement": "Build AutoSpec V2.",
        "prd": valid_prd_payload(),
        "architecture_design": valid_architecture_payload(),
        "backend_design": valid_backend_payload(),
    }

    result = run_v2_node("frontend_engineer", payload, model_client=model_client)

    assert result.node_name == "frontend_engineer"
    assert result.output_payload["routes"][0]["page"] == "ProjectDetailPage"


def test_v4_workflow_appends_evaluator_report_after_reviewer():
    records = []
    model_client = V2FakeModelClient()

    result = run_v4_workflow(
        "Build AutoSpec V4 with Agent orchestration evaluation.",
        model_client=model_client,
        callbacks=[records.append],
        retrieved_sources=[{"artifact_id": 9, "title": "Approved V3 Plan"}],
    )

    assert [record.node_name for record in result.records] == [
        "product_manager",
        "architect",
        "backend_engineer",
        "frontend_engineer",
        "reviewer",
        "evaluator",
    ]
    assert result.evaluation_report.overall_score >= 80
    assert result.evaluation_report.dimension("RUNTIME_RELIABILITY").score == 100
    evaluator_record = result.records[-1]
    assert evaluator_record.agent_name == "EvaluatorAgent_v1"
    assert evaluator_record.output_payload["final_grade"] in ["A", "B"]


def test_v4_response_serializes_evaluation_report():
    result = run_v4_workflow(
        "Build AutoSpec V4 with Agent orchestration evaluation.",
        model_client=V2FakeModelClient(),
        retrieved_sources=[{"artifact_id": 9, "title": "Approved V3 Plan"}],
    )

    response = v4_response(result)

    assert response["evaluation_report"]["overall_score"] == result.evaluation_report.overall_score
    assert response["records"][-1]["node_name"] == "evaluator"
