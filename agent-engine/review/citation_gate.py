from __future__ import annotations

import hashlib
import re
from typing import Any, Iterable

from schemas.citation import SourceCitation


_SPACE = re.compile(r"\s+")


def validate_citations(
    citations: Iterable[SourceCitation],
    retrieved_sources: Iterable[dict[str, Any]],
    *,
    project_id: str | int | None = None,
    retrieval_snapshot_hash: str | None = None,
) -> list[str]:
    """Return deterministic citation failures without invoking a model."""

    source_by_id = {
        str(source.get("citation_id")): source
        for source in retrieved_sources
        if isinstance(source, dict) and source.get("citation_id")
    }
    failures: list[str] = []
    seen: set[str] = set()
    for citation in citations:
        citation_id = citation.citation_id
        if citation_id in seen:
            failures.append(f"{citation_id}: duplicate citation id")
            continue
        seen.add(citation_id)
        source = source_by_id.get(citation_id)
        if source is None:
            failures.append(f"{citation_id}: citation id is not present in the retrieval snapshot")
            continue
        if not _supported(citation.excerpt, str(source.get("content", ""))):
            failures.append(f"{citation_id}: excerpt is not supported by the cited chunk")
        failures.extend(_metadata_failures(citation, source))
        if project_id is not None and str(source.get("project_id")) != str(project_id):
            failures.append(f"{citation_id}: source is outside the requested project")
        if (
            retrieval_snapshot_hash is not None
            and source.get("retrieval_snapshot_hash") is not None
            and source.get("retrieval_snapshot_hash") != retrieval_snapshot_hash
        ):
            failures.append(f"{citation_id}: retrieval snapshot hash does not match")
    return failures


def source_chunk_hash(source: dict[str, Any]) -> str:
    return hashlib.sha256(str(source.get("content", "")).encode("utf-8")).hexdigest()


def _metadata_failures(citation: SourceCitation, source: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    pairs = {
        "project_id": citation.project_id,
        "artifact_id": citation.artifact_id,
        "artifact_type": citation.artifact_type,
        "artifact_version": citation.artifact_version,
        "chunk_id": citation.chunk_id,
        "chunk_index": citation.chunk_index,
        "artifact_content_hash": citation.artifact_content_hash,
        "chunk_content_hash": citation.chunk_content_hash,
    }
    aliases = {
        "artifact_content_hash": "artifact_content_hash",
        "chunk_content_hash": "chunk_content_hash",
    }
    for field, expected in pairs.items():
        if expected is None:
            continue
        actual = source.get(aliases.get(field, field))
        if actual is None and field == "artifact_version":
            actual = source.get("artifactVersion")
        if actual is None and field == "artifact_id":
            actual = source.get("artifactId")
        if actual is None and field == "chunk_id":
            actual = source.get("chunkId")
        if actual is None and field == "chunk_index":
            actual = source.get("chunkIndex")
        if str(actual) != str(expected):
            failures.append(f"{citation.citation_id}: {field} does not match the source")
    declared_chunk_hash = source.get("chunk_content_hash")
    if declared_chunk_hash and declared_chunk_hash != source_chunk_hash(source):
        failures.append(f"{citation.citation_id}: source chunk hash does not match its content")
    return failures


def _supported(excerpt: str, content: str) -> bool:
    normalized_excerpt = _SPACE.sub(" ", excerpt.strip()).lower()
    normalized_content = _SPACE.sub(" ", content.strip()).lower()
    return len(normalized_excerpt) >= 3 and normalized_excerpt in normalized_content
