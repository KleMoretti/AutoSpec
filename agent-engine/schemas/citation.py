from pydantic import BaseModel, ConfigDict, Field


class SourceCitation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    citation_id: str = Field(min_length=1)
    claim: str = Field(min_length=1)
    excerpt: str = Field(min_length=3, max_length=500)
    project_id: str | None = Field(default=None, min_length=1)
    artifact_id: int | str | None = None
    artifact_type: str | None = Field(default=None, min_length=1)
    artifact_version: int | str | None = None
    chunk_id: int | str | None = None
    chunk_index: int | None = Field(default=None, ge=0)
    artifact_content_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    chunk_content_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    retrieval_snapshot_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
