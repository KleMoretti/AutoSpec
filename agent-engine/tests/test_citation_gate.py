import hashlib

from review.citation_gate import source_chunk_hash, validate_citations
from schemas.citation import SourceCitation


def test_citation_gate_accepts_matching_identity_and_content_hash() -> None:
    content = "Approved API contract for the project workspace."
    source = {
        "citation_id": "artifact:7:v3:chunk:2",
        "project_id": "project-7",
        "artifact_id": 7,
        "artifact_version": 3,
        "chunk_id": 2,
        "chunk_index": 2,
        "content": content,
        "chunk_content_hash": hashlib.sha256(content.encode()).hexdigest(),
    }
    citation = SourceCitation(
        citation_id=source["citation_id"],
        claim="The contract is approved.",
        excerpt="approved API contract",
        project_id="project-7",
        artifact_id=7,
        artifact_version=3,
        chunk_id=2,
        chunk_index=2,
        chunk_content_hash=source["chunk_content_hash"],
    )

    assert source_chunk_hash(source) == source["chunk_content_hash"]
    assert validate_citations([citation], [source], project_id="project-7") == []


def test_citation_gate_rejects_cross_project_and_tampered_chunk() -> None:
    content = "Approved API contract for the project workspace."
    citation = SourceCitation(
        citation_id="artifact:7:v3:chunk:2",
        claim="The contract is approved.",
        excerpt="approved API contract",
        project_id="project-7",
        chunk_content_hash="0" * 64,
    )
    source = {
        "citation_id": citation.citation_id,
        "project_id": "project-8",
        "content": content,
        "chunk_content_hash": "f" * 64,
    }

    failures = validate_citations([citation], [source], project_id="project-7")

    assert any("outside the requested project" in failure for failure in failures)
    assert any("chunk_content_hash does not match" in failure for failure in failures)
    assert any("source chunk hash does not match" in failure for failure in failures)


def test_citation_gate_rejects_duplicate_and_unknown_ids() -> None:
    citation = SourceCitation(
        citation_id="missing",
        claim="A claim",
        excerpt="some evidence",
    )

    failures = validate_citations([citation, citation], [])

    assert failures == [
        "missing: citation id is not present in the retrieval snapshot",
        "missing: duplicate citation id",
    ]
