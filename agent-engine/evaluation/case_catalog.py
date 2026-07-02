from __future__ import annotations

from schemas.evaluation import EvaluationCase


REQUIRED_V4_ARTIFACTS = [
    "PRD",
    "ARCHITECTURE_DESIGN",
    "BACKEND_DESIGN",
    "FRONTEND_SKELETON",
    "REVIEW_REPORT",
    "EVALUATION_REPORT",
]

V4_SCORING_DIMENSIONS = [
    "SCHEMA_VALIDITY",
    "REQUIREMENT_COVERAGE",
    "CROSS_ARTIFACT_CONSISTENCY",
    "PERMISSION_COVERAGE",
    "RAG_CITATION_QUALITY",
    "RUNTIME_RELIABILITY",
    "EXPORT_READINESS",
]


def list_evaluation_cases() -> list[EvaluationCase]:
    return [EvaluationCase.model_validate(case) for case in _CASES]


def get_evaluation_case(case_id: str) -> EvaluationCase:
    for case in list_evaluation_cases():
        if case.case_id == case_id:
            return case
    raise KeyError(f"unknown evaluation case: {case_id}")


_CASES = [
    {
        "case_id": "campus_marketplace",
        "title": "Campus Second-Hand Marketplace",
        "requirement": (
            "Build a campus second-hand marketplace where students can publish products, "
            "search listings, favorite products, start order transactions, and admins can audit listings."
        ),
        "expected_capabilities": [
            "product publishing",
            "product search",
            "favorite products",
            "order transaction",
            "admin audit permission",
        ],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "dorm_repair",
        "title": "Dorm Repair Workflow",
        "requirement": (
            "Build a dorm repair system where students submit repair tickets with images, "
            "staff assign workers, workers update status, and admins review completion quality."
        ),
        "expected_capabilities": [
            "repair ticket submission",
            "image attachment storage",
            "worker assignment",
            "status tracking",
            "admin completion review",
        ],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "club_activity",
        "title": "Club Activity Platform",
        "requirement": (
            "Build a club activity platform where organizers publish activities, students register, "
            "organizers check in attendees, and historical approved events can be reused as planning references."
        ),
        "expected_capabilities": [
            "activity publishing",
            "student registration",
            "attendance check-in",
            "historical RAG reuse",
            "permission protected organizer operations",
        ],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
]
