from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field, model_validator


class ModelPolicy(BaseModel):
    provider_key: str = Field(min_length=1)
    model_name: str = Field(min_length=1)
    temperature: float = Field(default=0.0, ge=0.0, le=2.0)


class RetryPolicy(BaseModel):
    max_attempts: int = Field(default=1, ge=1, le=5)
    retry_on_validation_error: bool = False
    initial_delay_ms: int = Field(default=1000, ge=0)
    max_delay_ms: int = Field(default=10000, ge=0)
    multiplier: float = Field(default=2.0, ge=1.0)
    retryable_errors: list[str] = Field(default_factory=list)


class WorkflowRuntimePolicy(BaseModel):
    max_parallel_nodes: int = Field(default=1, ge=1, le=32)
    max_review_rounds: int = Field(default=0, ge=0, le=10)
    default_timeout_ms: int = Field(default=30000, ge=1000)


class ApprovalMode(StrEnum):
    NONE = "NONE"
    BEFORE_NODE = "BEFORE_NODE"
    AFTER_NODE = "AFTER_NODE"


class ApprovalPolicy(BaseModel):
    mode: ApprovalMode = ApprovalMode.NONE
    allowed_actions: list[str] = Field(default_factory=list)


class FallbackPolicy(BaseModel):
    enabled: bool = False
    handler: str | None = None
    model_policy: ModelPolicy | None = None


class WorkflowEdgeType(StrEnum):
    NORMAL = "NORMAL"
    CONDITIONAL = "CONDITIONAL"
    REWORK = "REWORK"


class WorkflowCondition(BaseModel):
    path: str = Field(pattern=r"^\$\.")
    operator: str = Field(pattern=r"^(EQ|NE|IN|EXISTS)$")
    value: Any = None


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
    approval: ApprovalPolicy = Field(default_factory=ApprovalPolicy)
    fallback: FallbackPolicy = Field(default_factory=FallbackPolicy)


class WorkflowEdgeSpec(BaseModel):
    from_node: str = Field(min_length=1)
    to_node: str = Field(min_length=1)
    edge_type: WorkflowEdgeType = WorkflowEdgeType.NORMAL
    condition: WorkflowCondition | None = None


class WorkflowSpec(BaseModel):
    workflow_key: str = Field(min_length=1)
    version: str = Field(min_length=1)
    nodes: list[WorkflowNodeSpec] = Field(min_length=1)
    edges: list[WorkflowEdgeSpec] = Field(default_factory=list)
    runtime: WorkflowRuntimePolicy = Field(default_factory=WorkflowRuntimePolicy)

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

        if any(edge.edge_type == WorkflowEdgeType.REWORK for edge in self.edges):
            if self.runtime.max_review_rounds < 1:
                raise ValueError("rework edges require max_review_rounds greater than zero")

        adjacency = {node_id: set() for node_id in node_ids}
        indegree = {node_id: 0 for node_id in node_ids}
        ordinary_edges = {
            (dependency, node.node_id)
            for node in self.nodes
            for dependency in node.depends_on
        }
        ordinary_edges.update(
            (edge.from_node, edge.to_node)
            for edge in self.edges
            if edge.edge_type != WorkflowEdgeType.REWORK
        )
        for source, target in ordinary_edges:
            if target not in adjacency[source]:
                adjacency[source].add(target)
                indegree[target] += 1

        ready = [node_id for node_id, degree in indegree.items() if degree == 0]
        visited = 0
        while ready:
            current = ready.pop()
            visited += 1
            for target in adjacency[current]:
                indegree[target] -= 1
                if indegree[target] == 0:
                    ready.append(target)
        if visited != len(node_ids):
            raise ValueError("ordinary workflow graph contains a cycle")
        return self

    def node(self, node_id: str) -> WorkflowNodeSpec:
        for node in self.nodes:
            if node.node_id == node_id:
                return node
        raise KeyError(f"unknown workflow node: {node_id}")
