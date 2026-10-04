from typing import Any, Mapping

from agents.base import ModelClient
from fixtures.software_domains import fixture_for_requirement
from runtime.structured_output import generate_structured_output
from schemas.clarification import ProductManagerResult
from schemas.prd import PrdArtifact


class ProductManagerAgent:
    prompt_name = "ProductManagerAgent_v1"
    clarification_prompt_name = "ProductManagerAgent_v3"

    def __init__(self, model_client: ModelClient | None = None):
        self.model_client = model_client

    def run(
        self,
        requirement: str,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
    ) -> PrdArtifact:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if self.model_client is not None:
            return generate_structured_output(
                self.model_client, self.prompt_name, input_payload, PrdArtifact
            )

        # Deterministic fixtures are an explicit no-model execution mode.  A
        # live model client never reaches this branch, so fixture data cannot
        # silently mask a provider or schema failure.
        return fixture_for_requirement(requirement).prd

    def run_clarification(
        self,
        requirement: str,
        *,
        clarification_context: Mapping[str, Any] | None = None,
        retrieved_sources: list[dict[str, Any]] | None = None,
        context_manifest: dict[str, Any] | None = None,
    ) -> ProductManagerResult:
        input_payload: Mapping[str, Any] = {
            "requirement": requirement,
            "clarification_context": dict(clarification_context or {}),
            "retrieved_sources": retrieved_sources or [],
            "context_manifest": context_manifest or {},
        }
        if self.model_client is not None:
            return generate_structured_output(
                self.model_client,
                self.clarification_prompt_name,
                input_payload,
                ProductManagerResult,
            )

        context = dict(clarification_context or {})
        if _clarification_is_satisfied(requirement, context):
            return ProductManagerResult(
                kind="PRD_READY",
                prd=fixture_for_requirement(requirement).prd,
            )

        return ProductManagerResult(
            kind="CLARIFICATION_REQUIRED",
            clarification_request=_fixture_clarification_request(requirement, context),
        )

        return PrdArtifact.model_validate(
            {
                "project_name": _infer_project_name(requirement),
                "target_users": ["student", "admin"],
                "core_features": [
                    {
                        "requirement_id": "REQ-PUBLISH",
                        "name": "Product publishing",
                        "description": "Students can publish second-hand products with structured listing details.",
                        "priority": "MUST",
                    },
                    {
                        "requirement_id": "REQ-SEARCH",
                        "name": "Product search",
                        "description": "Students can search and browse available campus listings.",
                        "priority": "MUST",
                    },
                    {
                        "requirement_id": "REQ-FAVORITE",
                        "name": "Favorite products",
                        "description": "Students can save products they are interested in.",
                        "priority": "SHOULD",
                    },
                    {
                        "requirement_id": "REQ-AUDIT",
                        "name": "Admin audit",
                        "description": "Admins can review listings before they become visible.",
                        "priority": "MUST",
                    },
                ],
                "user_stories": [
                    {
                        "story_id": "STORY-PUBLISH",
                        "role": "student",
                        "goal": "publish an idle item",
                        "benefit": "find a buyer inside the campus community",
                        "requirement_refs": ["REQ-PUBLISH"],
                        "acceptance_criteria": [
                            {
                                "acceptance_id": "AC-PUBLISH-DETAILS",
                                "criterion": "The listing stores title, price, category, description, and images.",
                                "requirement_refs": ["REQ-PUBLISH"],
                            },
                            {
                                "acceptance_id": "AC-PUBLISH-PENDING",
                                "criterion": "The listing enters a pending audit state after submission.",
                                "requirement_refs": ["REQ-PUBLISH"],
                            },
                        ],
                    },
                    {
                        "story_id": "STORY-AUDIT",
                        "role": "admin",
                        "goal": "audit product listings",
                        "benefit": "keep prohibited goods out of the marketplace",
                        "requirement_refs": ["REQ-AUDIT"],
                        "acceptance_criteria": [
                            {
                                "acceptance_id": "AC-AUDIT-ROLE",
                                "criterion": "Only admins can approve or reject pending listings.",
                                "requirement_refs": ["REQ-AUDIT"],
                            },
                            {
                                "acceptance_id": "AC-AUDIT-REASON",
                                "criterion": "Rejected listings include a visible reason.",
                                "requirement_refs": ["REQ-AUDIT"],
                            },
                        ],
                    },
                ],
                "business_boundaries": [
                    "Payment escrow is outside the current delivery scope.",
                    "Off-campus logistics are outside the current delivery scope.",
                ],
                "non_functional_requirements": [
                    "Every agent input, output, status, duration, and error is observable.",
                    "Agent artifacts must be valid JSON matching the published schema.",
                ],
                "risks": [
                    "Listings may include prohibited or unsafe goods.",
                    "Generated API and database designs may drift without reviewer checks.",
                ],
            }
        )


def _infer_project_name(requirement: str) -> str:
    lowered = requirement.lower()
    if "marketplace" in lowered or "second-hand" in lowered:
        return "Campus Second-Hand Marketplace"
    return "AutoSpec Generated Project"


def _clarification_is_satisfied(
    requirement: str,
    context: Mapping[str, Any],
) -> bool:
    if context.get("clarification_responses"):
        return True
    if context.get("accepted_assumption_ids"):
        return True
    if context.get("conflict_resolutions"):
        return True
    marker = "[[clarification-required]]"
    return marker not in requirement.lower() and len(requirement.split()) >= 4


def _fixture_clarification_request(
    requirement: str,
    context: Mapping[str, Any],
) -> Any:
    from schemas.clarification import (
        ClarificationQuestion,
        ClarificationRequest,
        RequirementAssumption,
    )
    from schemas.traceability import stable_id

    round_number = int(context.get("round", 1) or 1)
    return ClarificationRequest(
        request_id=stable_id("CL", requirement, round_number),
        lock_version=int(context.get("lock_version", 0) or 0),
        round=round_number,
        original_requirement_ref=requirement,
        questions=[
            ClarificationQuestion(
                question_id=stable_id("QUESTION", requirement),
                category="SCOPE",
                question="What is the primary user role and the first must-have workflow for this release?",
                reason="The current requirement does not identify the responsibility boundary needed to produce a safe PRD.",
                blocking=True,
                options=["End user completes the main workflow", "Operator/admin manages the workflow"],
            )
        ],
        assumptions=[
            RequirementAssumption(
                assumption_id=stable_id("ASSUMPTION", requirement),
                statement="The first release should cover one primary workflow and its necessary access control.",
                impact="Choosing a different scope changes the PRD stories, permissions, and downstream contracts.",
                origin="fixture-clarification-v1",
            )
        ],
        summary="The requirement needs one scope decision before the Product Manager can produce a reviewable PRD.",
    )
