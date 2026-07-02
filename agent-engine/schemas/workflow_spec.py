from __future__ import annotations

from pydantic import BaseModel, Field, model_validator


class ModelPolicy(BaseModel):
    provider_key: str = Field(min_length=1)
    model_name: str = Field(min_length=1)
    temperature: float = Field(default=0.0, ge=0.0, le=2.0)


class RetryPolicy(BaseModel):
    max_attempts: int = Field(default=1, ge=1, le=5)
    retry_on_validation_error: bool = False


class WorkflowNodeSpec(BaseModel):
    node_id: str = Field(min_length=1)
    agent_name: str = Field(min_length=1)
    input_schema: str = Field(min_length=1)
    output_schema: str = Field(min_length=1)
    artifact_type: str = Field(min_length=1)
    prompt_key: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1)
    model_policy: ModelPolicy
    retry_policy: RetryPolicy
    timeout_ms: int = Field(default=30000, ge=1000)
    requires_human_approval: bool = False
    depends_on: list[str] = Field(default_factory=list)


class WorkflowEdgeSpec(BaseModel):
    from_node: str = Field(min_length=1)
    to_node: str = Field(min_length=1)


class WorkflowSpec(BaseModel):
    workflow_key: str = Field(min_length=1)
    version: str = Field(min_length=1)
    nodes: list[WorkflowNodeSpec] = Field(min_length=1)
    edges: list[WorkflowEdgeSpec] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_references(self) -> "WorkflowSpec":
        node_ids = [node.node_id for node in self.nodes]
        duplicate_ids = {node_id for node_id in node_ids if node_ids.count(node_id) > 1}
        if duplicate_ids:
            raise ValueError(f"duplicate workflow node ids: {sorted(duplicate_ids)}")

        node_id_set = set(node_ids)
        for edge in self.edges:
            if edge.from_node not in node_id_set:
                raise ValueError(f"edge references unknown node: {edge.from_node}")
            if edge.to_node not in node_id_set:
                raise ValueError(f"edge references unknown node: {edge.to_node}")

        for node in self.nodes:
            for dependency in node.depends_on:
                if dependency not in node_id_set:
                    raise ValueError(f"node {node.node_id} references unknown dependency: {dependency}")
        return self

    def node(self, node_id: str) -> WorkflowNodeSpec:
        for node in self.nodes:
            if node.node_id == node_id:
                return node
        raise KeyError(f"unknown workflow node: {node_id}")
