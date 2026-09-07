from __future__ import annotations

from schemas.evaluation import AutoSpecEvalCase, AutoSpecRequirementExpectation


ARTIFACT_TYPES = [
    "PRD",
    "ARCHITECTURE_DESIGN",
    "BACKEND_DESIGN",
    "FRONTEND_SKELETON",
    "REVIEW_REPORT",
    "EVALUATION_REPORT",
]


def list_autospec_cases() -> list[AutoSpecEvalCase]:
    return [AutoSpecEvalCase.model_validate(case) for case in _CASES]


def get_autospec_case(case_id: str) -> AutoSpecEvalCase:
    for case in list_autospec_cases():
        if case.case_id == case_id:
            return case
    raise KeyError(f"unknown AutoSpec evaluation case: {case_id}")


def _must(
    requirement_id: str,
    statement: str,
    *,
    api: list[str],
    data: list[str],
    ui: list[str],
    acceptance: list[str],
) -> dict:
    return AutoSpecRequirementExpectation(
        requirement_id=requirement_id,
        statement=statement,
        api_evidence=api,
        data_evidence=data,
        ui_evidence=ui,
        acceptance_evidence=acceptance,
    ).model_dump(mode="json")


_CASES = [
    {
        "case_id": "crud_inventory",
        "title": "Warehouse inventory CRUD",
        "category": "CRUD",
        "requirement": "Warehouse operators create, update, search, and archive products and stock locations.",
        "must_requirements": [
            _must("MUST-CRUD-1", "Products can be created and edited.", api=["POST /products", "PATCH /products/{id}"], data=["product", "stock_location"], ui=["product form", "product table"], acceptance=["valid product is persisted"]),
            _must("MUST-CRUD-2", "Operators can search active products.", api=["GET /products"], data=["product.status"], ui=["search results"], acceptance=["archived products are excluded by default"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["missing CRUD API", "untraceable product table", "archive state has no acceptance evidence"],
    },
    {
        "case_id": "approval_expense",
        "title": "Expense approval workflow",
        "category": "APPROVAL",
        "requirement": "Employees submit receipts, managers approve or reject expenses, and finance tracks reimbursement.",
        "must_requirements": [
            _must("MUST-APPROVAL-1", "A manager can approve or reject a submitted expense.", api=["POST /expenses/{id}/approval"], data=["expense.approval_status", "expense.approved_by"], ui=["manager approval queue"], acceptance=["non-manager cannot approve"]),
            _must("MUST-APPROVAL-2", "Finance can update reimbursement status after approval.", api=["PATCH /expenses/{id}/reimbursement"], data=["expense.reimbursement_status"], ui=["finance reimbursement view"], acceptance=["rejected expense cannot be reimbursed"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["approval transition is not role protected", "reimbursement bypasses approval"],
    },
    {
        "case_id": "permission_clinic",
        "title": "Clinic records and permissions",
        "category": "PERMISSION",
        "requirement": "Patients book appointments while doctors and staff access medical notes according to role and patient scope.",
        "must_requirements": [
            _must("MUST-PERM-1", "Only authorized clinical roles can read medical notes.", api=["GET /patients/{id}/notes"], data=["medical_note", "patient"], ui=["role-aware notes view"], acceptance=["unauthorized role receives denial"]),
            _must("MUST-PERM-2", "Patients can book available appointments.", api=["POST /appointments"], data=["appointment", "doctor_availability"], ui=["appointment booking"], acceptance=["double booking is rejected"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["medical notes lack authorization rule", "appointment uniqueness is absent"],
    },
    {
        "case_id": "multi_entity_enrollment",
        "title": "Course enrollment with waitlist",
        "category": "MULTI_ENTITY",
        "requirement": "Students enroll in courses with prerequisites, seat limits, schedule conflict checks, and a waitlist.",
        "must_requirements": [
            _must("MUST-MULTI-1", "Enrollment verifies prerequisites and seat availability.", api=["POST /courses/{id}/enrollments"], data=["course", "enrollment", "prerequisite"], ui=["course enrollment"], acceptance=["missing prerequisite blocks enrollment"]),
            _must("MUST-MULTI-2", "A full course places eligible students on a waitlist.", api=["POST /courses/{id}/waitlist"], data=["waitlist_entry"], ui=["waitlist position"], acceptance=["waitlist position is deterministic"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["enrollment ignores prerequisite", "waitlist has no ordering rule"],
    },
    {
        "case_id": "external_payment",
        "title": "Subscription billing integration",
        "category": "EXTERNAL_INTEGRATION",
        "requirement": "Customers subscribe to plans, payment failures are retried, and entitlements reflect the billing provider result.",
        "must_requirements": [
            _must("MUST-EXT-1", "Billing provider callbacks are authenticated and idempotent.", api=["POST /billing/webhooks"], data=["payment_event", "idempotency_key"], ui=["billing status"], acceptance=["duplicate callback has no duplicate charge"]),
            _must("MUST-EXT-2", "Entitlements are revoked after a terminal payment failure.", api=["GET /accounts/{id}/entitlements"], data=["subscription", "entitlement"], ui=["plan access state"], acceptance=["failed subscription cannot use paid feature"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["webhook is not idempotent", "entitlement transition is missing"],
    },
    {
        "case_id": "ambiguous_marketplace",
        "title": "Ambiguous marketplace brief",
        "category": "AMBIGUOUS_REQUIREMENT",
        "requirement": "Build a marketplace for local services with listings, messaging, bookings, ratings, and an admin safety process; clarify unresolved payment and cancellation rules.",
        "must_requirements": [
            _must("MUST-AMB-1", "The PRD records unresolved payment and cancellation decisions.", api=["decision record"], data=["open decision"], ui=["clarification prompt"], acceptance=["unknown rule is not silently invented"]),
            _must("MUST-AMB-2", "The design still defines a safe booking state boundary.", api=["POST /bookings"], data=["booking.status"], ui=["booking state"], acceptance=["unsafe cancellation path is blocked"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["ambiguous rule presented as confirmed", "booking path has no safety boundary"],
    },
    {
        "case_id": "conflict_scheduling",
        "title": "Conflicting appointment constraints",
        "category": "CONFLICTING_CONSTRAINTS",
        "requirement": "A scheduling service must support recurring availability, blackout periods, time zones, and an urgent override that needs explicit approval.",
        "must_requirements": [
            _must("MUST-CONFLICT-1", "Blackout periods override recurring availability.", api=["GET /availability"], data=["availability_rule", "blackout_period"], ui=["availability calendar"], acceptance=["blackout slot cannot be booked"]),
            _must("MUST-CONFLICT-2", "Urgent overrides are explicit and audited.", api=["POST /appointments/{id}/override"], data=["override_audit"], ui=["override approval"], acceptance=["override without approval is rejected"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["constraint precedence is undefined", "override has no audit trail"],
    },
    {
        "case_id": "rework_traceability",
        "title": "Reviewer-directed rework",
        "category": "REWORK",
        "requirement": "Generate an internal procurement system, then route reviewer findings back to the affected backend and frontend artifacts with traceable fixes.",
        "must_requirements": [
            _must("MUST-REWORK-1", "Reviewer findings identify affected requirements and artifact paths.", api=["review issue trace"], data=["review_issue"], ui=["review issue panel"], acceptance=["each blocking issue has a target"]),
            _must("MUST-REWORK-2", "A reworked API and UI preserve the original requirement trace.", api=["changed API evidence"], data=["trace edge"], ui=["changed screen evidence"], acceptance=["resolved issue links to revised evidence"]),
        ],
        "expected_artifact_types": ARTIFACT_TYPES,
        "allowed_tools": ["knowledge.search", "artifact.get", "contract.lookup"],
        "prohibited_tools": ["trace.query", "bundle.verify"],
        "failure_conditions": ["blocking issue has no route", "rework drops requirement trace"],
    },
]
