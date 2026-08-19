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
    {
        "case_id": "clinic_scheduling",
        "title": "Clinic Scheduling",
        "requirement": "Build a clinic scheduling system for patients, doctors, appointments, reminders, and access-controlled medical notes.",
        "expected_capabilities": ["patient booking", "doctor availability", "appointment reminders", "medical note permissions"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "expense_approval",
        "title": "Expense Approval",
        "requirement": "Build an employee expense platform with receipt upload, manager approval, finance audit, and reimbursement status tracking.",
        "expected_capabilities": ["expense submission", "receipt storage", "manager approval", "finance audit"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "warehouse_inventory",
        "title": "Warehouse Inventory",
        "requirement": "Build a multi-warehouse inventory system with stock movements, reorder alerts, cycle counts, and operator permissions.",
        "expected_capabilities": ["stock movement", "reorder alert", "cycle count", "warehouse permissions"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "restaurant_booking",
        "title": "Restaurant Booking",
        "requirement": "Build a restaurant reservation service with table availability, booking changes, waitlists, and staff seating controls.",
        "expected_capabilities": ["table availability", "reservation changes", "waitlist", "staff seating"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "incident_response",
        "title": "Incident Response",
        "requirement": "Build an incident response workspace with severity triage, responder assignment, timeline updates, and postmortem approval.",
        "expected_capabilities": ["severity triage", "responder assignment", "incident timeline", "postmortem approval"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "course_enrollment",
        "title": "Course Enrollment",
        "requirement": "Build a course enrollment portal with prerequisites, seat limits, waitlists, schedule conflicts, and registrar overrides.",
        "expected_capabilities": ["prerequisite validation", "seat limits", "waitlist", "registrar override"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "sales_crm",
        "title": "Sales CRM",
        "requirement": "Build a sales CRM with lead capture, pipeline stages, account ownership, activity history, and manager forecasting.",
        "expected_capabilities": ["lead capture", "pipeline stages", "account ownership", "sales forecasting"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "insurance_claims",
        "title": "Insurance Claims",
        "requirement": "Build an insurance claims platform with policy validation, evidence upload, adjuster assignment, fraud review, and settlement approval.",
        "expected_capabilities": ["policy validation", "claim evidence", "adjuster assignment", "settlement approval"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "last_mile_delivery",
        "title": "Last-Mile Delivery",
        "requirement": "Build a last-mile delivery platform with route assignment, proof of delivery, failed-delivery handling, and dispatcher visibility.",
        "expected_capabilities": ["route assignment", "proof of delivery", "delivery exception handling", "dispatcher visibility"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "hotel_housekeeping",
        "title": "Hotel Housekeeping",
        "requirement": "Build a hotel housekeeping system with room priorities, attendant assignment, inspection checklists, and maintenance escalation.",
        "expected_capabilities": ["room prioritization", "attendant assignment", "inspection checklist", "maintenance escalation"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "manufacturing_quality",
        "title": "Manufacturing Quality Control",
        "requirement": "Build a manufacturing quality system with inspection plans, defect capture, lot quarantine, corrective actions, and supervisor release.",
        "expected_capabilities": ["inspection plans", "defect capture", "lot quarantine", "corrective action approval"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "legal_case_intake",
        "title": "Legal Case Intake",
        "requirement": "Build a legal case intake portal with conflict checks, secure document collection, attorney assignment, deadlines, and privileged access controls.",
        "expected_capabilities": ["conflict check", "secure document collection", "attorney assignment", "privileged access"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "subscription_billing",
        "title": "Subscription Billing",
        "requirement": "Build a subscription billing service with plans, trials, proration, invoice retries, entitlements, and finance reconciliation.",
        "expected_capabilities": ["plan lifecycle", "billing proration", "invoice retry", "entitlement enforcement"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "energy_maintenance",
        "title": "Energy Asset Maintenance",
        "requirement": "Build an energy asset maintenance platform with meter alerts, work orders, technician safety permits, parts usage, and outage reporting.",
        "expected_capabilities": ["meter alert", "work order", "safety permit", "outage reporting"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "volunteer_coordination",
        "title": "Volunteer Coordination",
        "requirement": "Build a volunteer coordination system with opportunities, eligibility checks, shift signup, attendance, and safeguarding permissions.",
        "expected_capabilities": ["opportunity publishing", "eligibility check", "shift signup", "safeguarding permissions"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "library_lending",
        "title": "Library Lending",
        "requirement": "Build a library lending system with catalog search, reservations, loans, overdue notices, fines, and librarian overrides.",
        "expected_capabilities": ["catalog search", "reservation queue", "loan tracking", "librarian override"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
    {
        "case_id": "recruitment_pipeline",
        "title": "Recruitment Pipeline",
        "requirement": "Build a recruitment platform with requisition approval, candidate consent, interview scheduling, scorecards, offers, and recruiter permissions.",
        "expected_capabilities": ["requisition approval", "candidate consent", "interview scorecard", "offer approval"],
        "required_artifact_types": REQUIRED_V4_ARTIFACTS,
        "scoring_dimensions": V4_SCORING_DIMENSIONS,
    },
]
