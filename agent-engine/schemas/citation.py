from pydantic import BaseModel, ConfigDict, Field


class SourceCitation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    citation_id: str = Field(min_length=1)
    claim: str = Field(min_length=1)
    excerpt: str = Field(min_length=3, max_length=500)
