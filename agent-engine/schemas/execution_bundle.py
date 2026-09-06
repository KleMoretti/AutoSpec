from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator


class PromptSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    key: str = Field(min_length=1)
    version: str = Field(min_length=1)
    checksum: str = Field(pattern=r"^[0-9a-f]{64}$")
    content: str = Field(min_length=1)


class ExecutionBundleNode(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    node_id: str = Field(min_length=1)
    handler_key: str = Field(min_length=1)
    handler_version: str = Field(min_length=1)
    input_schema: str | None = Field(default=None, min_length=1)
    input_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    output_schema: str | None = Field(default=None, min_length=1)
    output_schema_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    artifact_type: str | None = Field(default=None, min_length=1)
    prompt_key: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    prompt: PromptSnapshot | None = None
    context_policy: dict[str, Any] = Field(default_factory=dict)
    model_policy: dict[str, Any] = Field(default_factory=dict)
    retry_policy: dict[str, Any] = Field(default_factory=dict)
    fallback: dict[str, Any] = Field(default_factory=dict)
    tool_policy: dict[str, Any] = Field(default_factory=dict)
    retrieval_policy: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_prompt(self) -> "ExecutionBundleNode":
        if self.prompt_key is None:
            if self.prompt is not None:
                raise ValueError("prompt snapshot requires prompt metadata")
            return self
        if self.prompt is None:
            raise ValueError("executable bundle nodes require a prompt snapshot")
        if self.prompt.key != self.prompt_key:
            raise ValueError("prompt snapshot key does not match node metadata")
        if self.prompt.version != self.prompt_version:
            raise ValueError("prompt snapshot version does not match node metadata")
        if self.prompt.checksum != self.prompt_checksum:
            raise ValueError("prompt snapshot checksum does not match node metadata")
        return self


class ExecutionBundle(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: str = Field(pattern=r"^execution-bundle-v[0-9]+$")
    bundle_version: str = Field(min_length=1)
    workflow_key: str = Field(min_length=1)
    workflow_version: str = Field(min_length=1)
    workflow_spec_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    protocol_version: int = Field(ge=0, le=2)
    runtime: dict[str, int] = Field(default_factory=dict)
    nodes: list[ExecutionBundleNode] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_nodes(self) -> "ExecutionBundle":
        ids = [node.node_id for node in self.nodes]
        if len(ids) != len(set(ids)):
            raise ValueError("execution bundle node ids must be unique")
        if self.protocol_version >= 1 and any(
            node.input_schema is None or node.output_schema is None or node.artifact_type is None
            for node in self.nodes
        ):
            raise ValueError("executable bundle nodes require schema and artifact metadata")
        return self
