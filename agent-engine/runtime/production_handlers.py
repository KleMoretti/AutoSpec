from __future__ import annotations

import asyncio
import hashlib
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agents.base import ModelClient
from model_gateway import model_routing_request
from review.evaluator import evaluate_artifacts
from runtime.agent_node_runner import run_agent_node
from runtime.agent_loop_trace import publish_agent_loop_trace
from runtime.backend_agent_loop import BackendAgentLoopError, run_backend_agent_loop
from runtime.handler_registry import HandlerRegistry
from runtime.context_policy import ContextPolicyError, apply_context_policy
from runtime.execution_context import current_model_execution_contract
from runtime.model_telemetry import ModelInvocationTelemetry, record_model_invocation
from runtime.tool_harness import execute_current_tool
from schemas.architecture_design import ArchitectureDesignArtifact, ArchitectureDesignArtifactV2
from schemas.agent_loop import LoopPolicy
from schemas.backend_design import BackendDesignArtifact, ExplicitBackendDesignArtifact
from schemas.evaluation import (
    EvaluationInput,
    EvaluationInputV2,
    EvaluationInputV3,
    EvaluationRuntimeInput,
    EvaluationReport,
    EvaluationReportV2,
)
from schemas.frontend_skeleton import (
    ExplicitFrontendSkeletonArtifact,
    FrontendSkeletonArtifact,
)
from schemas.prd import PrdArtifact
from schemas.rework import ReworkDirective
from schemas.review import ReviewIssue, ReviewReport, ReviewReportV2, ReworkRoute
from schemas.tool import ToolCallRequest
from schemas.verification import VerificationFact, VerificationReport
from spec_verifier.compiler import compile_spec
from spec_verifier.artifact_adapter import explicit_spec_contract_from_artifacts
from spec_verifier.fixtures import spec_contract_from_artifacts
from review.shared_contract import (
    validate_backend_contract,
    validate_explicit_backend_contract,
)


class ProductManagerInput(BaseModel):
    model_config = ConfigDict(extra="allow")

    requirement: str = Field(min_length=1)
    retrieved_sources: list[dict[str, Any]] = Field(default_factory=list)


class PrdNodeInput(ProductManagerInput):
    prd: dict[str, Any]
    rework_directive: ReworkDirective | None = None


class BackendDesignInput(PrdNodeInput):
    architecture_design: dict[str, Any]


class FrontendNodeInput(PrdNodeInput):
    architecture_design: dict[str, Any]
    backend_design: dict[str, Any]


class ReviewerNodeInput(FrontendNodeInput):
    backend_design: dict[str, Any]
    frontend_skeleton: dict[str, Any]
    generated_files: list[dict[str, Any] | str] = Field(default_factory=list)
    model_invocations: list[dict[str, Any]] = Field(default_factory=list)


class FrontendNodeInputV2(PrdNodeInput):
    architecture_design: dict[str, Any]


class ReviewerNodeInputV2(FrontendNodeInputV2):
    backend_design: dict[str, Any]
    frontend_skeleton: dict[str, Any]
    generated_files: list[dict[str, Any] | str] = Field(default_factory=list)
    model_invocations: list[dict[str, Any]] = Field(default_factory=list)


class QualityGateBlockedError(RuntimeError):
    error_code = "QUALITY_GATE_BLOCKED"


FIXTURE_CROSS_NODE_REWORK_MARKER = "[[fixture-cross-node-rework]]"


def build_production_registry(model_client: ModelClient | None = None) -> HandlerRegistry:
    registry = HandlerRegistry()
    _register_agent_node(
        registry,
        "ProductManagerAgent",
        "v1",
        "product_manager",
        ProductManagerInput,
        PrdArtifact,
        "GenerateRequest",
        "PrdArtifact",
        "product_manager",
        model_client,
    )
    _register_agent_node(
        registry,
        "ArchitectAgent",
        "v1",
        "architect",
        PrdNodeInput,
        ArchitectureDesignArtifact,
        "ArchitectureInput",
        "ArchitectureDesignArtifact",
        "architect",
        model_client,
    )
    _register_agent_node(
        registry,
        "BackendEngineerAgent",
        "v1",
        "backend_engineer",
        BackendDesignInput,
        BackendDesignArtifact,
        "BackendDesignInput",
        "BackendDesignArtifact",
        "backend_engineer",
        model_client,
    )
    _register_agent_node(
        registry,
        "FrontendEngineerAgent",
        "v1",
        "frontend_engineer",
        FrontendNodeInput,
        FrontendSkeletonArtifact,
        "FrontendSkeletonInput",
        "FrontendSkeletonArtifact",
        "frontend_engineer",
        model_client,
    )
    _register_agent_node(
        registry,
        "ReviewerAgent",
        "v1",
        "reviewer",
        ReviewerNodeInput,
        ReviewReport,
        "ReviewInput",
        "ReviewReport",
        "reviewer",
        model_client,
    )
    for handler_key, version, node_name, input_model, output_model, input_name, output_name, prompt_key in (
        ("ArchitectAgent", "v2", "architect", PrdNodeInput, ArchitectureDesignArtifactV2, "ArchitectureInput", "ArchitectureDesignArtifactV2", "architect_shared"),
        ("ArchitectAgent", "v3", "architect", PrdNodeInput, ArchitectureDesignArtifactV2, "ArchitectureInput", "ArchitectureDesignArtifactV2", "architect_schema"),
        ("BackendEngineerAgent", "v3", "backend_engineer", BackendDesignInput, BackendDesignArtifact, "BackendDesignInput", "BackendDesignArtifact", "backend_engineer_shared"),
        ("BackendEngineerAgent", "v4", "backend_engineer", BackendDesignInput, BackendDesignArtifact, "BackendDesignInput", "BackendDesignArtifact", "backend_engineer_loop"),
         ("BackendEngineerAgent", "v5", "backend_engineer", BackendDesignInput, BackendDesignArtifact, "BackendDesignInput", "BackendDesignArtifact", "backend_engineer_loop_v2"),
         ("BackendEngineerAgent", "v6", "backend_engineer", BackendDesignInput, BackendDesignArtifact, "BackendDesignInput", "BackendDesignArtifact", "backend_engineer_loop_v3"),
         ("BackendEngineerAgent", "v7", "backend_engineer", BackendDesignInput, ExplicitBackendDesignArtifact, "BackendDesignInput", "ExplicitBackendDesignArtifact", "backend_engineer_explicit"),
         ("BackendEngineerAgent", "v8", "backend_engineer", BackendDesignInput, ExplicitBackendDesignArtifact, "BackendDesignInput", "ExplicitBackendDesignArtifact", "backend_engineer_explicit_loop"),
         ("FrontendEngineerAgent", "v2", "frontend_engineer", FrontendNodeInputV2, FrontendSkeletonArtifact, "FrontendSkeletonInputV2", "FrontendSkeletonArtifact", "frontend_engineer_shared"),
         ("FrontendEngineerAgent", "v3", "frontend_engineer", FrontendNodeInputV2, FrontendSkeletonArtifact, "FrontendSkeletonInputV2", "FrontendSkeletonArtifact", "frontend_schema"),
         ("FrontendEngineerAgent", "v4", "frontend_engineer", FrontendNodeInputV2, ExplicitFrontendSkeletonArtifact, "FrontendSkeletonInputV2", "ExplicitFrontendSkeletonArtifact", "frontend_explicit"),
         ("FrontendEngineerAgent", "v5", "frontend_engineer", FrontendNodeInputV2, ExplicitFrontendSkeletonArtifact, "FrontendSkeletonInputV2", "ExplicitFrontendSkeletonArtifact", "frontend_explicit_v2"),
         ("FrontendEngineerAgent", "v6", "frontend_engineer", FrontendNodeInputV2, ExplicitFrontendSkeletonArtifact, "FrontendSkeletonInputV2", "ExplicitFrontendSkeletonArtifact", "frontend_explicit_v3"),
         ("FrontendEngineerAgent", "v7", "frontend_engineer", FrontendNodeInputV2, ExplicitFrontendSkeletonArtifact, "FrontendSkeletonInputV2", "ExplicitFrontendSkeletonArtifact", "frontend_explicit_v4"),
         ("FrontendEngineerAgent", "v8", "frontend_engineer", FrontendNodeInputV2, ExplicitFrontendSkeletonArtifact, "FrontendSkeletonInputV2", "ExplicitFrontendSkeletonArtifact", "frontend_explicit_v5"),
        ("ReviewerAgent", "v2", "reviewer", ReviewerNodeInputV2, ReviewReport, "ReviewInputV2", "ReviewReport", "reviewer_shared"),
        ("ReviewerAgent", "v4", "reviewer", ReviewerNodeInputV2, ReviewReportV2, "ReviewInputV4", "ReviewReportV2", "reviewer_schema"),
        ("ReviewerAgent", "v5", "reviewer", ReviewerNodeInputV2, ReviewReportV2, "ReviewInputV4", "ReviewReportV2", "reviewer_schema_v2"),
    ):
        _register_agent_node(registry, handler_key, version, node_name, input_model, output_model, input_name, output_name, prompt_key, model_client)
    _register_agent_node(
        registry,
        "ReviewerAgent",
        "v3",
        "reviewer",
        ReviewerNodeInputV2,
        ReviewReportV2,
        "ReviewInputV3",
        "ReviewReportV2",
        "reviewer_shared",
        model_client,
        rule_profile="spec-full-v1",
    )
    _register_agent_node(
        registry, "BackendEngineerAgent", "v2", "backend_engineer",
        BackendDesignInput, BackendDesignArtifact, "BackendDesignInput",
        "BackendDesignArtifact", "backend_engineer_loop", model_client,
    )
    _register_agent_node(
        registry, "ProductManagerAgent", "v2", "product_manager",
        ProductManagerInput, PrdArtifact, "GenerateRequest",
        "PrdArtifact", "product_manager_schema", model_client,
    )
    registry.register(
        "EvaluatorAgent",
        "v1",
        EvaluationInput,
        EvaluationReport,
        _execute_evaluator,
        input_schema="EvaluationInput",
        output_schema="EvaluationReport",
        prompt_key="evaluator",
        prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    registry.register(
        "EvaluatorAgent", "v2", EvaluationRuntimeInput, EvaluationReport, _execute_evaluator,
        input_schema="EvaluationRuntimeInput", output_schema="EvaluationReport",
        prompt_key="evaluator", prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    registry.register(
        "EvaluatorAgent", "v3", EvaluationInputV2, EvaluationReportV2, _execute_evaluator,
        input_schema="EvaluationInputV2", output_schema="EvaluationReportV2",
        prompt_key="evaluator", prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    registry.register(
        "EvaluatorAgent", "v4", EvaluationInputV3, EvaluationReportV2, _execute_evaluator,
        input_schema="EvaluationInputV3", output_schema="EvaluationReportV2",
        prompt_key="evaluator", prompt_version="v1",
        prompt_checksum=_prompt_checksum("evaluator", "v1"),
    )
    return registry


def _register_agent_node(
    registry: HandlerRegistry,
    handler_key: str,
    handler_version: str,
    node_name: str,
    input_model: type[BaseModel],
    output_model: type[BaseModel],
    input_schema: str,
    output_schema: str,
    prompt_key: str,
    model_client: ModelClient | None,
    rule_profile: str | None = None,
) -> None:
    def compact_input(input_payload: BaseModel) -> tuple[dict[str, Any], str | None]:
        serialized_input = input_payload.model_dump(mode="json")
        execution_policy = serialized_input.get("execution_policy", {})
        quality_profile = (
            execution_policy.get("quality_profile")
            if isinstance(execution_policy, dict)
            else None
        )
        compacted_input = _compile_context(
            node_name,
            serialized_input,
            quality_profile,
            input_model,
        )
        return compacted_input, quality_profile

    def execute_single_shot(input_payload: BaseModel) -> dict[str, Any]:
        compacted_input, quality_profile = compact_input(input_payload)
        if prompt_key.endswith("_shared") or (
            handler_key in {"ArchitectAgent", "FrontendEngineerAgent"}
            and handler_version == "v3"
        ) or (
            handler_key == "BackendEngineerAgent"
            and handler_version in {"v3", "v4", "v5", "v6"}
        ):
            compacted_input["shared_contract_required"] = True
        if output_model in {ExplicitBackendDesignArtifact, ExplicitFrontendSkeletonArtifact}:
            compacted_input["shared_contract_required"] = True
            compacted_input["explicit_contract_required"] = True
        if rule_profile is not None:
            compacted_input["rule_profile"] = rule_profile
        elif handler_key == "ReviewerAgent" and handler_version in {"v2", "v3"}:
            # v5-parallel publishes the shared-contract reviewer as v2.  Its
            # legacy profile contains marketplace/control-plane assumptions;
            # shared-contract runs must use the domain-neutral checks so a
            # valid inventory or leave fixture is not forced to implement
            # unrelated AutoSpec APIs.
            compacted_input["rule_profile"] = "spec-full-v1"
        with model_routing_request(quality_profile, node_name):
            record = run_agent_node(
                node_name,
                compacted_input,
                model_client=model_client,
            )
        if record.status != "SUCCEEDED" or record.output_payload is None:
            raise RuntimeError(record.error_message or f"{node_name} execution failed")
        _record_fixture_invocation(
            model_client=model_client,
            record=record,
            compacted_input=compacted_input,
            prompt_key=prompt_key,
            output_schema=output_schema,
        )
        return record.output_payload

    async def execute_reviewer(input_payload: BaseModel) -> dict[str, Any]:
        compacted_input, quality_profile = compact_input(input_payload)
        compacted_input["shared_contract_required"] = True
        if handler_version in {"v4", "v5"}:
            compacted_input["reviewer_prompt_name"] = (
                "ReviewerAgent_v5" if handler_version == "v5" else "ReviewerAgent_v4"
            )
        frozen = current_model_execution_contract()
        verification_policy = dict(frozen.verification_policy if frozen else {})
        if rule_profile is not None:
            compacted_input["rule_profile"] = rule_profile
        elif handler_version == "v5":
            # Candidate reviewers use the domain-neutral profile. The older
            # v4 handler intentionally remains unchanged for replay fidelity.
            compacted_input["rule_profile"] = verification_policy.get(
                "rule_profile", "spec-full-v1"
            )
        if not verification_policy.get("enabled"):
            raise RuntimeError("candidate reviewer requires an enabled verification policy")
        prd = PrdArtifact.model_validate(compacted_input["prd"])
        explicit_contract = _explicit_contract_required(verification_policy)
        if explicit_contract:
            from schemas.backend_design import ExplicitBackendDesignArtifact
            from schemas.frontend_skeleton import ExplicitFrontendSkeletonArtifact

            backend = ExplicitBackendDesignArtifact.model_validate(
                compacted_input["backend_design"]
            )
            frontend = ExplicitFrontendSkeletonArtifact.model_validate(
                compacted_input["frontend_skeleton"]
            )
            contract = explicit_spec_contract_from_artifacts(
                prd, backend, frontend, contract_id="GeneratedSpec"
            )
            compacted_input["explicit_contract_required"] = True
        else:
            backend = BackendDesignArtifact.model_validate(compacted_input["backend_design"])
            frontend = FrontendSkeletonArtifact.model_validate(compacted_input["frontend_skeleton"])
            contract = spec_contract_from_artifacts(
                prd,
                backend,
                frontend,
                contract_id="GeneratedSpec",
            )
        compiled = compile_spec(contract)
        result = await execute_current_tool(
            ToolCallRequest(
                name="spec.verify",
                version="v1",
                arguments={
                    "contract": contract.model_dump(mode="json"),
                    "required_level": verification_policy.get("required_level", "L1"),
                    "rule_profile": verification_policy.get("rule_profile", "spec-full-v1"),
                    "source_digest": compiled.source_digest,
                    "timeout_ms": verification_policy.get("timeout_ms", 30_000),
                },
            )
        )
        if result.status != "SUCCEEDED" or not isinstance(result.result, dict):
            raise RuntimeError(result.error_message or "spec.verify failed")
        verifier_payload = result.result.get("result", result.result)
        if not isinstance(verifier_payload, dict):
            raise RuntimeError("spec.verify returned an invalid result")
        report_payload = {
            key: value
            for key, value in verifier_payload.items()
            if key != "verification_fact"
        }
        report = VerificationReport.model_validate(report_payload)
        trusted_fact = VerificationFact.model_validate(
            verifier_payload.get("verification_fact")
        )
        if report.gate_status != "PASSED" or trusted_fact.status != "PASSED":
            raise RuntimeError("spec.verify blocked the candidate")
        with model_routing_request(quality_profile, node_name):
            record = await asyncio.to_thread(
                run_agent_node,
                node_name,
                compacted_input,
                model_client,
                verification_fact=trusted_fact.model_dump(mode="json"),
            )
        if record.status != "SUCCEEDED" or record.output_payload is None:
            raise RuntimeError(record.error_message or f"{node_name} execution failed")
        output_payload = record.output_payload
        if (
            model_client is None
            and FIXTURE_CROSS_NODE_REWORK_MARKER in str(compacted_input.get("requirement", ""))
            and frozen is not None
            and ":reviewer:1:" in frozen.execution_id
        ):
            report = ReviewReportV2.model_validate(output_payload)
            injected_issue = ReviewIssue(
                severity="HIGH",
                issue_type="FIXTURE_CROSS_NODE_REWORK",
                description="Fixture-only first review requires a bounded Backend responsibility handoff.",
                suggestion="Re-run backend_engineer against the frozen Shared Contract without changing stable API ids.",
                issue_id="ISS-FIXTURE-CROSS-NODE",
            )
            output_payload = ReviewReportV2(
                score=min(report.score, 80),
                issues=[*report.issues, injected_issue],
                decision="REWORK",
                routes=[ReworkRoute(
                    target_node="backend_engineer",
                    issue_ids=[injected_issue.issue_id],
                    required_changes=[injected_issue.suggestion],
                    invalidate_downstream=True,
                )],
                verification_fact=report.verification_fact,
            ).model_dump(mode="json")
            record = replace(record, output_payload=output_payload)
        _record_fixture_invocation(
            model_client=model_client,
            record=record,
            compacted_input=compacted_input,
            prompt_key=prompt_key,
            output_schema=output_schema,
        )
        return record.output_payload

    async def execute_backend(input_payload: BaseModel) -> dict[str, Any]:
        compacted_input, quality_profile = compact_input(input_payload)
        if handler_version in {"v3", "v4", "v5", "v6", "v7", "v8"}:
            compacted_input["shared_contract_required"] = True
        if output_model is ExplicitBackendDesignArtifact:
            compacted_input["explicit_contract_required"] = True
        contract = current_model_execution_contract()
        policy = LoopPolicy.model_validate(
            contract.agent_loop_policy if contract is not None else {}
        )
        if not policy.enabled:
            return await asyncio.to_thread(execute_single_shot, input_payload)
        with model_routing_request(quality_profile, node_name):
            result = await run_backend_agent_loop(
                requirement=str(compacted_input["requirement"]),
                prd=PrdArtifact.model_validate(compacted_input["prd"]),
                architecture_design=compacted_input["architecture_design"],
                retrieved_sources=list(compacted_input.get("retrieved_sources", [])),
                context_manifest=dict(compacted_input.get("context_manifest", {})),
                rework_directive=compacted_input.get("rework_directive"),
                model_client=model_client,
                policy=policy,
            )
        publish_agent_loop_trace(result)
        if not result.completed or result.candidate is None:
            raise BackendAgentLoopError(
                f"Backend Engineer loop stopped with {result.stop_reason.value}",
                result.stop_reason.value,
            )
        if output_model is ExplicitBackendDesignArtifact:
            validate_explicit_backend_contract(
                ArchitectureDesignArtifactV2.model_validate(compacted_input["architecture_design"]),
                ExplicitBackendDesignArtifact.model_validate(result.candidate),
            )
        elif prompt_key.endswith("_shared"):
            validate_backend_contract(
                ArchitectureDesignArtifactV2.model_validate(compacted_input["architecture_design"]),
                BackendDesignArtifact.model_validate(result.candidate),
            )
        return result.candidate

    if node_name == "backend_engineer":
        execute = execute_backend
    elif handler_key == "ReviewerAgent" and handler_version in {"v3", "v4", "v5"}:
        execute = execute_reviewer
    else:
        execute = execute_single_shot

    registry.register(
        handler_key,
        handler_version,
        input_model,
        output_model,
        execute,
        input_schema=input_schema,
        output_schema=output_schema,
        prompt_key=prompt_key,
        prompt_version="v1",
        prompt_checksum=_prompt_checksum(prompt_key, "v1"),
    )


def _record_fixture_invocation(
    *,
    model_client: ModelClient | None,
    record: Any,
    compacted_input: dict[str, Any],
    prompt_key: str,
    output_schema: str,
) -> None:
    if model_client is not None:
        return
    contract = current_model_execution_contract()
    model_policy = contract.model_policy if contract is not None else {}
    context_policy = contract.context_policy if contract is not None else {}
    record_model_invocation(
        ModelInvocationTelemetry(
            provider_key=record.provider_key,
            model_name=record.model_name,
            prompt_key=prompt_key,
            prompt_version="v1",
            prompt_checksum=_prompt_checksum(prompt_key, "v1"),
            schema_version=output_schema,
            contract_hash=contract.contract_hash if contract is not None else None,
            normalized_params_hash=_hash_json(compacted_input),
            result_hash=_hash_json(record.output_payload),
            status="SUCCEEDED",
            duration_ms=record.duration_ms,
            reserved_input_tokens=int(context_policy.get("max_input_tokens", 0)),
            reserved_output_tokens=int(model_policy.get("max_output_tokens", 0)),
            reserved_cost=_reserved_call_cost(context_policy, model_policy),
        )
    )


def _explicit_contract_required(verification_policy: dict[str, Any]) -> bool:
    # The verifier version is already frozen in the execution contract and is
    # accepted by the legacy WorkflowSpec schema.  Use it as the versioned
    # boundary so adding explicit artifact fields does not change old handler
    # input/output hashes or make old WorkflowSpecs fail validation.
    return verification_policy.get("verifier_version") == "spec-verifier-v2"


def _compile_context(
    node_name: str,
    serialized_input: dict[str, Any],
    quality_profile: str | None,
    input_model: type[BaseModel],
) -> dict[str, Any]:
    contract = current_model_execution_contract()
    frozen_policy = (
        contract.context_policy
        if contract is not None and contract.protocol_version >= 2
        else None
    )
    compacted_input, context_manifest = apply_context_policy(
        node_name,
        serialized_input,
        quality_profile,
        frozen_policy,
    )
    compacted_input.pop("context_manifest", None)
    try:
        _validate_artifact_context(compacted_input)
        validated = input_model.model_validate(compacted_input).model_dump(mode="json")
    except ValidationError as exception:
        raise ContextPolicyError(
            "Compacted context failed the frozen node input schema"
        ) from exception
    if input_model.model_config.get("extra") == "allow":
        validated["context_manifest"] = context_manifest
    return validated


def _validate_artifact_context(payload: dict[str, Any]) -> None:
    execution = current_model_execution_contract()
    input_policy = payload.get("verification_policy")
    verification_policy = (
        dict(input_policy)
        if isinstance(input_policy, dict)
        else dict(execution.verification_policy) if execution else {}
    )
    explicit_contract = _explicit_contract_required(verification_policy)
    if explicit_contract:
        from schemas.backend_design import ExplicitBackendDesignArtifact
        from schemas.frontend_skeleton import ExplicitFrontendSkeletonArtifact

    artifact_models: dict[str, type[BaseModel]] = {
        "prd": PrdArtifact,
        "architecture_design": ArchitectureDesignArtifact,
        "backend_design": (
            ExplicitBackendDesignArtifact if explicit_contract else BackendDesignArtifact
        ),
        "frontend_skeleton": (
            ExplicitFrontendSkeletonArtifact
            if explicit_contract
            else FrontendSkeletonArtifact
        ),
        "review_report": ReviewReport,
    }
    parsed: dict[str, BaseModel] = {}
    if isinstance(payload.get("architecture_design"), dict) and "shared_contract" in payload["architecture_design"]:
        artifact_models["architecture_design"] = ArchitectureDesignArtifactV2
    for field, model in artifact_models.items():
        value = payload.get(field)
        if value is not None:
            if field == "review_report" and isinstance(value, dict) and "verification_fact" in value:
                parsed[field] = ReviewReportV2.model_validate(value)
            else:
                parsed[field] = model.model_validate(value)

    prd = parsed.get("prd")
    if isinstance(prd, PrdArtifact):
        known_requirements = {
            feature.requirement_id
            for feature in prd.core_features
            if feature.requirement_id is not None
        }
        referenced = {
            str(reference)
            for field in artifact_models
            if field != "prd" and field in payload
            for reference in _values_for_key(payload[field], "requirement_refs")
            if isinstance(reference, str)
        }
        unknown = sorted(referenced - known_requirements)
        if unknown:
            raise ContextPolicyError(
                "Compacted context contains unknown requirement references: "
                + ", ".join(unknown[:20])
            )

    backend = parsed.get("backend_design")
    frontend = parsed.get("frontend_skeleton")
    if backend is not None and frontend is not None:
        known_api_ids = {api.api_id for api in backend.apis if api.api_id is not None}
        referenced_api_ids = {
            binding.backend_api_id
            for binding in frontend.api_bindings
            if binding.backend_api_id is not None
        }
        unknown_api_ids = sorted(referenced_api_ids - known_api_ids)
        if unknown_api_ids:
            raise ContextPolicyError(
                "Compacted frontend context references unknown backend APIs: "
                + ", ".join(unknown_api_ids[:20])
            )


def _values_for_key(value: Any, target: str) -> list[Any]:
    values: list[Any] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key == target and isinstance(item, list):
                values.extend(item)
            values.extend(_values_for_key(item, target))
    elif isinstance(value, list):
        for item in value:
            values.extend(_values_for_key(item, target))
    return values


def _execute_evaluator(input_payload: EvaluationInput) -> EvaluationReport | EvaluationReportV2:
    serialized = input_payload.model_dump(mode="json")
    execution_policy = serialized.get("execution_policy", {})
    quality_profile = (
        execution_policy.get("quality_profile")
        if isinstance(execution_policy, dict)
        else None
    )
    compiled = _compile_context(
        "evaluator",
        serialized,
        quality_profile,
        type(input_payload),
    )
    return _evaluate(type(input_payload).model_validate(compiled))


def _evaluate(input_payload: EvaluationInput) -> EvaluationReport | EvaluationReportV2:
    explicit_contract = _explicit_contract_required(
        getattr(input_payload, "verification_policy", {})
    )
    backend_model = ExplicitBackendDesignArtifact if explicit_contract else BackendDesignArtifact
    frontend_model = (
        ExplicitFrontendSkeletonArtifact if explicit_contract else FrontendSkeletonArtifact
    )
    report = evaluate_artifacts(
        requirement=input_payload.requirement,
        prd=PrdArtifact.model_validate(input_payload.prd),
        architecture_design=(ArchitectureDesignArtifactV2 if "shared_contract" in input_payload.architecture_design else ArchitectureDesignArtifact).model_validate(
            input_payload.architecture_design
        ),
        backend_design=backend_model.model_validate(input_payload.backend_design),
        frontend_skeleton=frontend_model.model_validate(
            input_payload.frontend_skeleton
        ),
        review_report=(
            ReviewReportV2.model_validate(input_payload.review_report)
            if "verification_fact" in input_payload.review_report
            else ReviewReport.model_validate(input_payload.review_report)
        ),
        records=input_payload.records,
        model_invocations=input_payload.model_invocations,
        retrieved_sources=input_payload.retrieved_sources,
        generated_files=input_payload.generated_files,
        verification_policy=getattr(input_payload, "verification_policy", {}),
        verification_fact=getattr(input_payload, "verification_fact", None),
    )
    if report.gate_status == "BLOCKED":
        blockers = [
            f"{issue.issue_type}: {issue.description}"
            for issue in report.issues
            if issue.blocking
        ]
        raise QualityGateBlockedError("; ".join(blockers[:5]))
    return report


def _prompt_checksum(prompt_key: str, prompt_version: str) -> str:
    if prompt_key == "evaluator":
        material = f"deterministic:{prompt_key}:{prompt_version}".encode("utf-8")
    else:
        path = (
            Path(__file__).resolve().parents[1]
            / "prompts"
            / f"{prompt_key}_{prompt_version}.md"
        )
        material = path.read_text(encoding="utf-8").encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def _hash_json(value: Any) -> str:
    material = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _reserved_call_cost(
    context_policy: dict[str, Any],
    model_policy: dict[str, Any],
) -> float:
    input_tokens = max(0, int(context_policy.get("max_input_tokens", 0)))
    output_tokens = max(0, int(model_policy.get("max_output_tokens", 0)))
    input_rate = max(0.0, float(model_policy.get("input_cost_per_million", 0.0)))
    output_rate = max(0.0, float(model_policy.get("output_cost_per_million", 0.0)))
    return round(
        (input_tokens * input_rate + output_tokens * output_rate) / 1_000_000,
        8,
    )
