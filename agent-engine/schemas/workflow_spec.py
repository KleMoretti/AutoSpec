from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ModelPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    route_key: str | None = Field(default=None, min_length=1)
    provider_key: str | None = Field(default=None, min_length=1)
    model_name: str | None = Field(default=None, min_length=1)
    temperature: float = Field(default=0.0, ge=0.0, le=2.0)
    context_window_tokens: int = Field(default=128_000, ge=1_024)
    max_output_tokens: int = Field(default=4_096, ge=1, le=128_000)
    max_calls: int = Field(default=1, ge=1, le=8)
    input_cost_per_million: float = Field(default=0.0, ge=0.0)
    cached_input_cost_per_million: float = Field(default=0.0, ge=0.0)
    output_cost_per_million: float = Field(default=0.0, ge=0.0)
    required_capabilities: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_target(self) -> "ModelPolicy":
        if self.route_key is None and not (self.provider_key and self.model_name):
            raise ValueError(
                "model_policy requires route_key or provider_key plus model_name"
            )
        if self.max_output_tokens >= self.context_window_tokens:
            raise ValueError("max_output_tokens must be smaller than context_window_tokens")
        if len(set(self.required_capabilities)) != len(self.required_capabilities):
            raise ValueError("required_capabilities must not contain duplicates")
        return self


class ContextPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = Field(min_length=1)
    tokenizer: str = Field(min_length=1)
    max_input_tokens: int = Field(ge=256, le=1_000_000)
    prompt_token_reserve: int = Field(default=512, ge=0)
    manifest_token_reserve: int = Field(default=384, ge=0)
    field_priority: list[str] = Field(min_length=1)
    required_paths: list[str] = Field(min_length=1)
    compression_strategy: Literal["schema-aware-v2"] = "schema-aware-v2"
    rag_token_budget: int = Field(default=0, ge=0)
    long_text_token_budget: int = Field(default=0, ge=0)
    max_single_source_ratio: float = Field(default=0.35, gt=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_budget(self) -> "ContextPolicy":
        reserved = self.prompt_token_reserve + self.manifest_token_reserve
        if reserved >= self.max_input_tokens:
            raise ValueError("context policy reserves must leave input token capacity")
        payload_budget = self.max_input_tokens - reserved
        if self.rag_token_budget > payload_budget:
            raise ValueError("rag_token_budget exceeds available input capacity")
        if self.long_text_token_budget > payload_budget:
            raise ValueError("long_text_token_budget exceeds available input capacity")
        if len(set(self.field_priority)) != len(self.field_priority):
            raise ValueError("field_priority must not contain duplicates")
        if len(set(self.required_paths)) != len(self.required_paths):
            raise ValueError("required_paths must not contain duplicates")
        if any(not path.startswith("$.") for path in self.required_paths):
            raise ValueError("required_paths must use JSONPath-like $. prefixes")
        return self


class RetryPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_attempts: int = Field(default=1, ge=1, le=5)
    retry_on_validation_error: bool = False
    initial_delay_ms: int = Field(default=1000, ge=0)
    max_delay_ms: int = Field(default=10000, ge=0)
    multiplier: float = Field(default=2.0, ge=1.0)
    retryable_errors: list[str] = Field(default_factory=list)


class ToolRef(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    version: str = Field(min_length=1)


class ToolPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = Field(default="tools-v1", min_length=1)
    enabled: bool = False
    allowed_tools: list[ToolRef] = Field(default_factory=list)
    max_calls: int = Field(default=0, ge=0, le=32)
    per_call_timeout_ms: int = Field(default=5_000, ge=100, le=600_000)
    total_timeout_ms: int = Field(default=30_000, ge=100, le=900_000)
    max_result_bytes: int = Field(default=32_000, ge=256, le=1_000_000)
    allowed_side_effects: list[str] = Field(
        default_factory=lambda: ["READ_ONLY", "DETERMINISTIC"]
    )
    permission_policy: str = Field(default="workflow", min_length=1)
    retry_policy: RetryPolicy = Field(default_factory=RetryPolicy)

    @model_validator(mode="after")
    def validate_policy(self) -> "ToolPolicy":
        keys = [(tool.name, tool.version) for tool in self.allowed_tools]
        if len(keys) != len(set(keys)):
            raise ValueError("allowed_tools must not contain duplicate name/version pairs")
        if self.enabled and (not self.allowed_tools or self.max_calls < 1):
            raise ValueError("enabled tool policy requires an allowlist and max_calls")
        if self.total_timeout_ms < self.per_call_timeout_ms:
            raise ValueError("total_timeout_ms must cover one tool call")
        if not set(self.allowed_side_effects).issubset(
            {"READ_ONLY", "DETERMINISTIC", "WRITE"}
        ):
            raise ValueError("unsupported tool side effect level")
        return self


class RetrievalPolicySpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = Field(default="retrieval-v1", min_length=1)
    enabled: bool = False
    allowed_corpora: list[str] = Field(default_factory=list)
    top_n: int = Field(default=20, ge=1, le=100)
    top_k: int = Field(default=5, ge=1, le=50)
    max_per_artifact: int = Field(default=2, ge=1, le=10)
    token_budget: int = Field(default=0, ge=0, le=100_000)
    query_template: str = Field(default="node-aware-v1", min_length=1)
    retriever_version: str = Field(default="hybrid-rrf-rerank-v1", min_length=1)
    embedding_version: str = Field(default="hashing-ngram-v1", min_length=1)
    reranker_version: str = Field(default="deterministic-rerank-v1", min_length=1)
    timeout_ms: int = Field(default=5_000, ge=100, le=600_000)

    @model_validator(mode="after")
    def normalize_corpora(self) -> "RetrievalPolicySpec":
        self.allowed_corpora = list(dict.fromkeys(self.allowed_corpora))
        if self.top_k > self.top_n:
            raise ValueError("retrieval top_k must not exceed top_n")
        return self


class WorkflowRuntimePolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_parallel_nodes: int = Field(default=1, ge=1, le=32)
    max_review_rounds: int = Field(default=0, ge=0, le=10)
    default_timeout_ms: int = Field(default=30000, ge=1000)


class ApprovalMode(StrEnum):
    NONE = "NONE"
    BEFORE_NODE = "BEFORE_NODE"
    AFTER_NODE = "AFTER_NODE"


class ApprovalPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: ApprovalMode = ApprovalMode.NONE
    allowed_actions: list[str] = Field(default_factory=list)


class FallbackPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = False
    handler: str | None = None
    model_policy: ModelPolicy | None = None


class WorkflowEdgeType(StrEnum):
    NORMAL = "NORMAL"
    CONDITIONAL = "CONDITIONAL"
    REWORK = "REWORK"


class WorkflowCondition(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path: str | None = Field(default=None, pattern=r"^\$\.")
    operator: str | None = Field(default=None, pattern=r"^(EQ|NE|IN|EXISTS)$")
    value: Any = None
    all: list["WorkflowCondition"] | None = None
    any: list["WorkflowCondition"] | None = None

    @model_validator(mode="after")
    def validate_shape(self) -> "WorkflowCondition":
        groups = [name for name in ("all", "any") if getattr(self, name) is not None]
        if groups:
            if len(groups) != 1 or self.path is not None or self.operator is not None:
                raise ValueError("condition group must contain exactly one of all or any")
            children = getattr(self, groups[0])
            if not children:
                raise ValueError(f"condition {groups[0]} must not be empty")
            if "value" in self.model_fields_set:
                raise ValueError("condition group cannot declare value")
            return self
        if self.path is None or self.operator is None:
            raise ValueError("condition path and operator are required")
        if self.operator != "EXISTS" and "value" not in self.model_fields_set:
            raise ValueError(f"condition {self.operator} requires value")
        if self.operator == "IN" and not isinstance(self.value, list):
            raise ValueError("condition IN value must be an array")
        return self


class WorkflowNodeSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    node_id: str = Field(min_length=1)
    agent_name: str = Field(min_length=1)
    input_schema: str = Field(min_length=1)
    input_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    output_schema: str = Field(min_length=1)
    output_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    artifact_type: str = Field(min_length=1)
    prompt_key: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1)
    prompt_checksum: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    context_policy: ContextPolicy | None = None
    model_policy: ModelPolicy
    retry_policy: RetryPolicy
    timeout_ms: int = Field(default=30000, ge=1000)
    tool_policy: ToolPolicy = Field(default_factory=ToolPolicy)
    retrieval_policy: RetrievalPolicySpec | None = None
    requires_human_approval: bool = False
    depends_on: list[str] = Field(default_factory=list)
    approval: ApprovalPolicy = Field(default_factory=ApprovalPolicy)
    fallback: FallbackPolicy = Field(default_factory=FallbackPolicy)


class WorkflowEdgeSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")
    from_node: str = Field(min_length=1)
    to_node: str = Field(min_length=1)
    edge_type: WorkflowEdgeType = WorkflowEdgeType.NORMAL
    condition: WorkflowCondition | None = None


class WorkflowSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow_key: str = Field(min_length=1)
    version: str = Field(min_length=1)
    protocol_version: int = Field(default=0, ge=0, le=2)
    nodes: list[WorkflowNodeSpec] = Field(min_length=1)
    edges: list[WorkflowEdgeSpec] = Field(default_factory=list)
    runtime: WorkflowRuntimePolicy = Field(default_factory=WorkflowRuntimePolicy)
    entry_nodes: list[str] = Field(default_factory=list)

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
            if edge.edge_type == WorkflowEdgeType.NORMAL and edge.condition is not None:
                raise ValueError("NORMAL workflow edge cannot declare a condition")
            if edge.edge_type in {
                WorkflowEdgeType.CONDITIONAL,
                WorkflowEdgeType.REWORK,
            } and edge.condition is None:
                raise ValueError(f"{edge.edge_type} workflow edge requires a condition")

        for node in self.nodes:
            for dependency in node.depends_on:
                if dependency not in node_id_set:
                    raise ValueError(f"node {node.node_id} references unknown dependency: {dependency}")

        if any(edge.edge_type == WorkflowEdgeType.REWORK for edge in self.edges):
            if self.runtime.max_review_rounds < 1:
                raise ValueError("rework edges require max_review_rounds greater than zero")

        if self.protocol_version in {1, 2}:
            for node in self.nodes:
                if self.protocol_version < 2 and node.tool_policy.model_dump(exclude_defaults=True):
                    raise ValueError(
                        f"node {node.node_id} tool_policy requires protocol_version 2"
                    )
                missing = [
                    name
                    for name, value in {
                        "input_schema_hash": node.input_schema_hash,
                        "output_schema_hash": node.output_schema_hash,
                        "prompt_checksum": node.prompt_checksum,
                    }.items()
                    if value is None
                ]
                if missing:
                    raise ValueError(
                        f"node {node.node_id} executable contract is missing: "
                        + ", ".join(missing)
                    )
                if self.protocol_version == 2:
                    if node.context_policy is None:
                        raise ValueError(
                            f"node {node.node_id} executable contract is missing context_policy"
                        )
                    if (
                        node.context_policy.max_input_tokens
                        + node.model_policy.max_output_tokens
                        > node.model_policy.context_window_tokens
                    ):
                        raise ValueError(
                            f"node {node.node_id} context and output budgets exceed model window"
                        )

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
