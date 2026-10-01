"""Versioned 16/8 split. Rubrics are authored expectations, pending human review.

Endpoint/table/UI labels are semantic review hints, never string-match gold.
Only requirement is sent to the workflow. Holdout cases must not tune prompts.
"""
from __future__ import annotations

from evaluation.autospec_case_catalog import ARTIFACT_TYPES, list_autospec_cases
from schemas.evaluation import AutoSpecEvalCase, AutoSpecRequirementExpectation

DATASET_VERSION = "autospec-interview-eval-v2"


_DOMAIN_BY_ID = {
    "crud_inventory": "warehouse-operations",
    "approval_expense": "corporate-finance",
    "permission_clinic": "healthcare",
    "multi_entity_enrollment": "education",
    "external_payment": "commerce",
    "ambiguous_marketplace": "local-services",
    "conflict_scheduling": "scheduling",
    "rework_traceability": "procurement",
    "dev_asset_register": "warehouse-operations",
    "dev_leave_approval": "human-resources",
    "dev_support_scope": "customer-support",
    "dev_room_booking": "facilities",
    "dev_shipping_callback": "logistics",
    "dev_smart_matching": "education",
    "dev_privacy_audit": "healthcare",
    "dev_export_correction": "analytics",
    "holdout_lab_samples": "healthcare",
    "holdout_purchase_orders": "corporate-finance",
    "holdout_school_records": "education",
    "holdout_equipment_loans": "university-operations",
    "holdout_calendar_sync": "scheduling",
    "holdout_document_rank": "knowledge-management",
    "holdout_offline_capacity": "events",
    "holdout_booking_correction": "hospitality",
}

# id, category, user requirement, two independently reviewable MUST facts
_DEVELOPMENT = [
    ("dev_asset_register", "CRUD", "IT staff register, edit and archive laptops. Serial numbers must be unique; archived laptops remain auditable and are excluded from active searches.",
     ("Reject duplicate serial numbers on create and edit.", "serial uniqueness"),
     ("Archive without deleting history; default searches exclude archived assets.", "archive filter")),
    ("dev_leave_approval", "APPROVAL", "Employees request leave; their direct manager approves or rejects it. Employees cannot approve their own leave and only pending requests can be decided.",
     ("Authorize only the requester's direct manager, excluding self approval.", "manager scope"),
     ("Allow one decision only from pending status.", "approval transition")),
    ("dev_support_scope", "PERMISSION", "A multi-tenant support portal lets customers read their own tickets and agents handle tickets in their assigned tenant. No role may read another tenant's tickets.",
     ("Enforce tenant isolation on list and detail APIs.", "tenant isolation"),
     ("Customers cannot access another customer's ticket in the same tenant.", "owner scope")),
    ("dev_room_booking", "MULTI_ENTITY", "Employees reserve meeting rooms and invite attendees. Overlapping confirmed room reservations and attendee schedule conflicts must be rejected atomically.",
     ("Prevent overlapping room reservations under concurrent requests.", "room interval constraint"),
     ("Reject attendee conflicts without partially persisting the booking.", "atomic booking")),
    ("dev_shipping_callback", "EXTERNAL_INTEGRATION", "A shipment tracker receives signed carrier events. Duplicate events do not create duplicate status entries; delayed older events must not regress delivered shipments.",
     ("Verify signatures before processing and deduplicate carrier event IDs.", "authenticated idempotent callback"),
     ("Preserve delivered state on stale events while retaining an audit record.", "event ordering")),
    ("dev_smart_matching", "AMBIGUOUS_REQUIREMENT", "Build smart mentor matching. The matching criteria and meaning of success are not decided. Record these as unresolved questions and label provisional assumptions explicitly.",
     ("Keep matching criteria and success definition as unresolved decisions.", "open questions"),
     ("Identify provisional assumptions instead of asserting them as approved requirements.", "assumption provenance")),
    ("dev_privacy_audit", "CONFLICTING_CONSTRAINTS", "A customer database is required to erase all personal data immediately on request and also retain full identifiable audit history forever. Surface this conflict and request an explicit retention decision.",
     ("Explain the incompatible erasure and identifiable retention requirements.", "constraint conflict"),
     ("Leave retention policy unresolved rather than silently choosing either rule.", "decision needed")),
    ("dev_export_correction", "REWORK", "Design report exports. A human correction supersedes the old public-download requirement: only the report owner may download exports and links expire after ten minutes. Preserve the correction and provenance.",
     ("Apply owner authorization on download and remove public access.", "superseded access rule"),
     ("Enforce ten-minute expiry and trace both changes to the human correction.", "correction trace")),
]

_HOLDOUT = [
    ("holdout_lab_samples", "CRUD", "Lab staff register and edit samples with unique barcodes. Disposal archives a sample without losing custody history; disposed samples are excluded from active searches.",
     ("Enforce barcode uniqueness on creation and updates.", "barcode uniqueness"),
     ("Disposal preserves custody history and excludes the sample from active search.", "disposal trace")),
    ("holdout_purchase_orders", "APPROVAL", "Buyers submit purchase orders. The budget owner approves orders up to 10000; larger orders also require finance approval. Requesters cannot approve their own orders and spending starts only after all required approvals.",
     ("Choose required approval stages using the order amount and prohibit self approval.", "conditional approval"),
     ("Block spending until all applicable approvals succeed.", "spending gate")),
    ("holdout_school_records", "PERMISSION", "Guardians see records only for their linked children; teachers see students only in assigned classes. School administrators remain scoped to their school, including exports and search.",
     ("Enforce guardian-child and teacher-class relationships on reads.", "relationship access"),
     ("Enforce school scope for administrators, searches and exports.", "school isolation")),
    ("holdout_equipment_loans", "MULTI_ENTITY", "A university lends equipment kits containing several items. Reserving a kit succeeds only if every item is available for the interval; cancellation releases all items and concurrent reservations must not double allocate.",
     ("Atomically reserve every item or reserve none, preventing concurrent double allocation.", "kit atomicity"),
     ("Cancellation releases all item reservations exactly once.", "reservation release")),
    ("holdout_calendar_sync", "EXTERNAL_INTEGRATION", "Synchronize appointments to an external calendar using OAuth. Revoked credentials stop synchronization; retries after timeouts must not create duplicate events. Record failures for authorized operators.",
     ("Handle revoked OAuth credentials without retrying unauthorized writes indefinitely.", "credential revocation"),
     ("Use stable idempotency to avoid duplicate events and expose scoped failure records.", "retry idempotency")),
    ("holdout_document_rank", "AMBIGUOUS_REQUIREMENT", "Rank documents by importance. Stakeholders have not agreed on importance, data sources or evaluation criteria. List these decisions as open and separate assumptions from confirmed requirements.",
     ("Preserve all three unresolved decisions without invented agreed criteria.", "unresolved ranking criteria"),
     ("Clearly distinguish provisional assumptions from confirmed requirements.", "assumption boundary")),
    ("holdout_offline_capacity", "CONFLICTING_CONSTRAINTS", "Sell the last remaining event seat from any fully offline device, accept every sale immediately, and guarantee global zero overselling without coordination. Explain the conflicting constraints and request a tradeoff decision.",
     ("Identify the conflict between offline unconditional acceptance and global capacity safety.", "consistency conflict"),
     ("Present an explicit unresolved tradeoff without claiming all constraints can be guaranteed.", "tradeoff decision")),
    ("holdout_booking_correction", "REWORK", "Design an appointment system. A documented human correction supersedes the earlier rule that staff may cancel any booking: only the booking owner may cancel, only before its start time, and cancellations must be audited.",
     ("Replace staff-wide cancellation with owner-only authorization.", "corrected ownership"),
     ("Reject cancellation at or after start time and audit accepted cancellations with correction provenance.", "time and audit rule")),
]


def _case(row: tuple) -> AutoSpecEvalCase:
    identifier, category, requirement, *facts = row
    return AutoSpecEvalCase(
        case_id=identifier, title=identifier.replace("_", " "), category=category,
        business_domain=_DOMAIN_BY_ID.get(identifier, category.lower()),
        requirement=requirement, dataset_version=DATASET_VERSION,
        must_requirements=[AutoSpecRequirementExpectation(
            requirement_id=f"{identifier}-MUST-{i}", statement=statement,
            acceptance_evidence=[hint],
        ) for i, (statement, hint) in enumerate(facts, 1)],
        expected_artifact_types=ARTIFACT_TYPES,
        allowed_tools=["knowledge.search", "artifact.get", "contract.lookup", "spec.verify"],
        prohibited_tools=["trace.query", "bundle.verify"],
        failure_conditions=[f"Missing or contradicted: {statement}" for statement, _ in facts],
    )


def experiment_cases(split: str) -> list[AutoSpecEvalCase]:
    if split == "smoke":
        return [case.model_copy(update={"business_domain": _DOMAIN_BY_ID.get(case.case_id, case.category.lower())})
                for case in list_autospec_cases()]
    if split == "development":
        return [c.model_copy(update={"dataset_version": DATASET_VERSION}) for c in list_autospec_cases()] + [_case(r) for r in _DEVELOPMENT]
    if split == "holdout":
        return [_case(r) for r in _HOLDOUT]
    raise ValueError("unknown dataset split; use smoke, development or holdout")
