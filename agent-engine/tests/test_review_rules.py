from agents.architect import ArchitectAgent
from agents.backend_engineer import BackendEngineerAgent
from agents.frontend_engineer import FrontendEngineerAgent
from agents.product_manager import ProductManagerAgent
from agents.reviewer import ReviewerAgent
from review.rules import run_current_rule_checks


def current_artifacts():
    requirement = "Build a campus second-hand marketplace."
    prd = ProductManagerAgent().run(requirement)
    architecture = ArchitectAgent().run(requirement, prd)
    backend = BackendEngineerAgent().run(requirement, prd, architecture)
    frontend = FrontendEngineerAgent().run(
        requirement,
        prd,
        architecture,
        backend,
    )
    return prd, architecture, backend, frontend


def test_current_reviewer_detects_missing_feature_api() -> None:
    prd, architecture, backend, frontend = current_artifacts()
    backend.apis = [
        api
        for api in backend.apis
        if "favorite" not in f"{api.path} {api.description}".lower()
    ]

    issues = run_current_rule_checks(prd, architecture, backend, frontend)

    assert any(issue.issue_type == "API_COVERAGE" for issue in issues)


def test_current_reviewer_detects_missing_admin_permission() -> None:
    prd, architecture, backend, frontend = current_artifacts()
    audit_api = next(api for api in backend.apis if "admin" in api.path)
    audit_api.required_roles = []

    issues = run_current_rule_checks(prd, architecture, backend, frontend)

    assert any(issue.issue_type == "PERMISSION_COVERAGE" for issue in issues)


def test_reviewer_routes_blocking_issue_to_the_affected_agent() -> None:
    prd, architecture, backend, frontend = current_artifacts()
    backend.apis = [
        api
        for api in backend.apis
        if "favorite" not in f"{api.path} {api.description}".lower()
    ]

    report = ReviewerAgent().run(prd, backend, architecture, frontend)

    assert report.decision == "REWORK"
    assert any(route.target_node == "backend_engineer" for route in report.routes)
