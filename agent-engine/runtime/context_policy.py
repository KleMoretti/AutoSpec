from __future__ import annotations

import copy
import hashlib
import json
import math
import re
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, Iterator, Mapping

from schemas.workflow_spec import ContextPolicy


_MANIFEST_COLLECTOR: ContextVar[list[dict[str, Any]] | None] = ContextVar(
    "autospec_context_manifest_collector",
    default=None,
)
_TOKEN_PIECE = re.compile(r"[\u3400-\u9fff]|[A-Za-z0-9_]+|[^\s]")
_IDENTIFIER = re.compile(
    r"\b(?:REQ|STORY|AC|API|TABLE|UI)-[A-Za-z0-9._:-]+\b",
    re.IGNORECASE,
)


class ContextPolicyError(RuntimeError):
    error_code = "CONTEXT_POLICY_ERROR"


@contextmanager
def capture_context_manifests() -> Iterator[list[dict[str, Any]]]:
    manifests: list[dict[str, Any]] = []
    token = _MANIFEST_COLLECTOR.set(manifests)
    try:
        yield manifests
    finally:
        _MANIFEST_COLLECTOR.reset(token)


@dataclass(frozen=True)
class ContextLimits:
    max_characters: int
    max_string_characters: int
    max_list_items: int


LIMITS = {
    "FAST": ContextLimits(15_000, 1_500, 8),
    "BALANCED": ContextLimits(40_000, 4_000, 25),
    "DEEP": ContextLimits(100_000, 10_000, 80),
}


def estimate_tokens(value: str, tokenizer: str = "conservative-multilingual-v1") -> int:
    """Conservative deterministic estimate used when a provider tokenizer is unavailable."""
    if not value:
        return 0
    if tokenizer != "conservative-multilingual-v1":
        raise ContextPolicyError(f"Unsupported frozen tokenizer: {tokenizer}")
    total = 0
    for piece in _TOKEN_PIECE.findall(value):
        if len(piece) == 1 and "\u3400" <= piece <= "\u9fff":
            total += 1
        elif piece[0].isalnum() or piece[0] == "_":
            total += max(1, math.ceil(len(piece) / 4))
        else:
            total += 1
    return total


def estimate_value_tokens(value: Any, tokenizer: str) -> int:
    return estimate_tokens(_canonical(value), tokenizer)


def apply_context_policy(
    node_name: str,
    payload: dict[str, Any],
    quality_profile: str | None,
    context_policy: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if context_policy is None:
        compacted, manifest = _apply_legacy_policy(
            node_name,
            payload,
            quality_profile,
        )
    else:
        policy = ContextPolicy.model_validate(context_policy)
        compacted, manifest = _apply_frozen_policy(node_name, payload, policy)
    collector = _MANIFEST_COLLECTOR.get()
    if collector is not None:
        collector.append(manifest)
    compacted["context_manifest"] = manifest
    return compacted, manifest


def _apply_frozen_policy(
    node_name: str,
    payload: dict[str, Any],
    policy: ContextPolicy,
) -> tuple[dict[str, Any], dict[str, Any]]:
    original = copy.deepcopy(payload)
    original.pop("context_manifest", None)
    source_material = _canonical(original)
    payload_budget = (
        policy.max_input_tokens
        - policy.prompt_token_reserve
        - policy.manifest_token_reserve
    )
    required_paths = tuple(policy.required_paths)
    required = {
        key: copy.deepcopy(value)
        for key, value in original.items()
        if _is_required(f"$.{key}", required_paths)
    }
    required_tokens = estimate_value_tokens(required, policy.tokenizer)
    if required_tokens > payload_budget:
        raise ContextPolicyError(
            "Required context exceeds the frozen input budget before model invocation"
        )

    trimmed_paths: list[str] = []
    reasons: list[str] = []
    compacted = copy.deepcopy(original)
    rag_used = 0
    if isinstance(compacted.get("retrieved_sources"), list):
        compacted["retrieved_sources"], rag_used = _compact_rag_sources(
            compacted["retrieved_sources"],
            policy,
            trimmed_paths,
            reasons,
        )

    long_state = {"remaining": policy.long_text_token_budget, "used": 0}
    compacted = _semantic_compact(
        compacted,
        "$",
        policy,
        required_paths,
        long_state,
        trimmed_paths,
        reasons,
    )
    compacted = _ordered_top_level(compacted, policy.field_priority)

    for string_limit, optional_items in ((256, 16), (128, 8), (64, 4), (32, 1)):
        if estimate_value_tokens(compacted, policy.tokenizer) <= payload_budget:
            break
        compacted = _tighten_optional_context(
            compacted,
            "$",
            string_limit,
            optional_items,
            policy,
            required_paths,
            trimmed_paths,
            reasons,
        )

    output_tokens_without_manifest = estimate_value_tokens(compacted, policy.tokenizer)
    if output_tokens_without_manifest > payload_budget:
        raise ContextPolicyError(
            "Schema-aware compaction could not satisfy the frozen input budget"
        )

    missing_identifiers = sorted(
        _identifiers_in(original) - _identifiers_in(compacted)
    )
    if missing_identifiers:
        raise ContextPolicyError(
            "Context compaction removed referenced identifiers: "
            + ", ".join(missing_identifiers[:20])
        )

    manifest = {
        "policy": f"{node_name}:{policy.version}",
        "policy_version": policy.version,
        "tokenizer": policy.tokenizer,
        "compression_strategy": policy.compression_strategy,
        "max_input_tokens": policy.max_input_tokens,
        "prompt_token_reserve": policy.prompt_token_reserve,
        "manifest_token_reserve": policy.manifest_token_reserve,
        "input_tokens": estimate_tokens(source_material, policy.tokenizer),
        "output_tokens": output_tokens_without_manifest,
        "quota_tokens": {
            "rag_limit": policy.rag_token_budget,
            "rag_used": rag_used,
            "long_text_limit": policy.long_text_token_budget,
            "long_text_used": long_state["used"],
        },
        "trimmed": bool(trimmed_paths),
        "trimmed_paths": list(dict.fromkeys(trimmed_paths))[:40],
        "compression_reasons": list(dict.fromkeys(reasons))[:20],
        "source_hash": hashlib.sha256(source_material.encode("utf-8")).hexdigest(),
        "source_hashes": {
            key: hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()
            for key, value in list(original.items())[:30]
        },
        "preserved_fields": [
            key
            for key in (
                "requirement",
                "retrieved_sources",
                "retrieval_policy",
                "execution_policy",
                "rework_directive",
                "error_message",
            )
            if key in compacted
        ],
        "preserved_identifier_count": len(_defined_identifiers(compacted)),
    }
    compacted["context_manifest"] = manifest
    final_tokens = estimate_value_tokens(compacted, policy.tokenizer)
    manifest["output_tokens"] = final_tokens
    compacted["context_manifest"] = manifest
    final_tokens = estimate_value_tokens(compacted, policy.tokenizer)
    if final_tokens > policy.max_input_tokens - policy.prompt_token_reserve:
        raise ContextPolicyError(
            "Context Manifest exceeds its frozen token reservation"
        )
    manifest["output_tokens"] = final_tokens
    compacted.pop("context_manifest", None)
    return compacted, manifest


def _compact_rag_sources(
    sources: list[Any],
    policy: ContextPolicy,
    trimmed_paths: list[str],
    reasons: list[str],
) -> tuple[list[Any], int]:
    if policy.rag_token_budget <= 0:
        if sources:
            trimmed_paths.append("$.retrieved_sources")
            reasons.append("rag_quota")
        return [], 0
    result: list[Any] = []
    used = 0
    per_source_limit = max(
        1,
        math.floor(policy.rag_token_budget * policy.max_single_source_ratio),
    )
    for index, source in enumerate(sources):
        remaining = policy.rag_token_budget - used
        if remaining <= 0:
            trimmed_paths.append(f"$.retrieved_sources[{index}:]")
            reasons.append("rag_quota")
            break
        if not isinstance(source, dict):
            compacted = _truncate_value_to_tokens(
                source,
                min(remaining, per_source_limit),
                policy.tokenizer,
            )
        else:
            compacted = copy.deepcopy(source)
            content = compacted.get("content")
            without_content = dict(compacted)
            without_content.pop("content", None)
            metadata_tokens = estimate_value_tokens(without_content, policy.tokenizer)
            content_budget = max(
                0,
                min(remaining, per_source_limit) - metadata_tokens,
            )
            if isinstance(content, str):
                compacted["content"] = _truncate_text(
                    content,
                    content_budget,
                    policy.tokenizer,
                )
                if compacted["content"] != content:
                    trimmed_paths.append(f"$.retrieved_sources[{index}].content")
                    reasons.append("rag_source_ratio")
        source_tokens = estimate_value_tokens(compacted, policy.tokenizer)
        if source_tokens > remaining or source_tokens > per_source_limit:
            trimmed_paths.append(f"$.retrieved_sources[{index}]")
            reasons.append("rag_quota")
            continue
        result.append(compacted)
        used += source_tokens
    return result, used


def _semantic_compact(
    value: Any,
    path: str,
    policy: ContextPolicy,
    required_paths: tuple[str, ...],
    long_state: dict[str, int],
    trimmed_paths: list[str],
    reasons: list[str],
) -> Any:
    if _is_required(path, required_paths):
        return copy.deepcopy(value)
    if path == "$.retrieved_sources":
        return value
    if isinstance(value, str):
        tokens = estimate_tokens(value, policy.tokenizer)
        allowed = min(tokens, long_state["remaining"], 512)
        compacted = _truncate_text(value, allowed, policy.tokenizer)
        used = estimate_tokens(compacted, policy.tokenizer)
        long_state["remaining"] = max(0, long_state["remaining"] - used)
        long_state["used"] += used
        if compacted != value:
            trimmed_paths.append(path)
            reasons.append("long_text_quota")
        return compacted
    if isinstance(value, list):
        result: list[Any] = []
        optional_kept = 0
        for index, item in enumerate(value):
            protected = bool(_identifiers_in(item))
            if not protected and optional_kept >= 24:
                trimmed_paths.append(f"{path}[{index}:]")
                reasons.append("optional_list_limit")
                break
            result.append(
                _semantic_compact(
                    item,
                    f"{path}[{index}]",
                    policy,
                    required_paths,
                    long_state,
                    trimmed_paths,
                    reasons,
                )
            )
            if not protected:
                optional_kept += 1
        return result
    if isinstance(value, dict):
        return {
            key: _semantic_compact(
                item,
                f"{path}.{key}",
                policy,
                required_paths,
                long_state,
                trimmed_paths,
                reasons,
            )
            for key, item in value.items()
        }
    return value


def _tighten_optional_context(
    value: Any,
    path: str,
    string_limit: int,
    optional_items: int,
    policy: ContextPolicy,
    required_paths: tuple[str, ...],
    trimmed_paths: list[str],
    reasons: list[str],
) -> Any:
    if _is_required(path, required_paths):
        return value
    if isinstance(value, str):
        compacted = _truncate_text(value, string_limit, policy.tokenizer)
        if compacted != value:
            trimmed_paths.append(path)
            reasons.append("total_token_budget")
        return compacted
    if isinstance(value, list):
        result: list[Any] = []
        optional_kept = 0
        for index, item in enumerate(value):
            protected = bool(_identifiers_in(item))
            if not protected and optional_kept >= optional_items:
                trimmed_paths.append(f"{path}[{index}:]")
                reasons.append("total_token_budget")
                continue
            result.append(
                _tighten_optional_context(
                    item,
                    f"{path}[{index}]",
                    string_limit,
                    optional_items,
                    policy,
                    required_paths,
                    trimmed_paths,
                    reasons,
                )
            )
            if not protected:
                optional_kept += 1
        return result
    if isinstance(value, dict):
        return {
            key: _tighten_optional_context(
                item,
                f"{path}.{key}",
                string_limit,
                optional_items,
                policy,
                required_paths,
                trimmed_paths,
                reasons,
            )
            for key, item in value.items()
        }
    return value


def _truncate_value_to_tokens(value: Any, limit: int, tokenizer: str) -> Any:
    if isinstance(value, str):
        return _truncate_text(value, limit, tokenizer)
    if estimate_value_tokens(value, tokenizer) <= limit:
        return value
    return {"summary": _truncate_text(_canonical(value), limit, tokenizer)}


def _truncate_text(value: str, limit: int, tokenizer: str) -> str:
    if limit <= 0:
        return ""
    if estimate_tokens(value, tokenizer) <= limit:
        return value
    identifiers = list(dict.fromkeys(_IDENTIFIER.findall(value)))
    protected = " ".join(identifiers)
    marker = "\n...[context compacted]...\n"
    low = 0
    high = len(value)
    best = protected
    while low <= high:
        characters = (low + high) // 2
        prefix_length = math.ceil(characters * 0.7)
        suffix_length = characters - prefix_length
        candidate = value[:prefix_length] + marker
        if protected:
            candidate += protected + "\n"
        if suffix_length:
            candidate += value[-suffix_length:]
        if estimate_tokens(candidate, tokenizer) <= limit:
            best = candidate
            low = characters + 1
        else:
            high = characters - 1
    if estimate_tokens(best, tokenizer) > limit:
        return ""
    return best


def _defined_identifiers(value: Any) -> set[str]:
    result: set[str] = set()
    if isinstance(value, dict):
        for key, item in value.items():
            normalized = key.lower()
            if (
                isinstance(item, str)
                and (normalized == "id" or normalized.endswith("_id"))
                and _IDENTIFIER.fullmatch(item)
            ):
                result.add(item.upper())
            result.update(_defined_identifiers(item))
    elif isinstance(value, list):
        for item in value:
            result.update(_defined_identifiers(item))
    return result


def _identifiers_in(value: Any) -> set[str]:
    return {match.upper() for match in _IDENTIFIER.findall(_canonical(value))}


def _is_required(path: str, required_paths: tuple[str, ...]) -> bool:
    return any(path == required or path.startswith(required + ".") for required in required_paths)


def _ordered_top_level(value: Any, priority: list[str]) -> Any:
    if not isinstance(value, dict):
        return value
    ordered: dict[str, Any] = {}
    for key in priority:
        if key in value:
            ordered[key] = value[key]
    for key, item in value.items():
        if key not in ordered:
            ordered[key] = item
    return ordered


def _canonical(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _apply_legacy_policy(
    node_name: str,
    payload: dict[str, Any],
    quality_profile: str | None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    profile = (quality_profile or "BALANCED").upper()
    limits = LIMITS.get(profile, LIMITS["BALANCED"])
    original = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    trimmed_paths: list[str] = []
    compacted = _legacy_compact(
        copy.deepcopy(payload),
        "$",
        limits.max_string_characters,
        limits.max_list_items,
        trimmed_paths,
    )
    final = json.dumps(compacted, ensure_ascii=False, separators=(",", ":"))
    if len(final) > limits.max_characters:
        for string_limit, list_limit in ((1000, 6), (500, 3), (250, 1)):
            compacted = _legacy_compact(
                compacted,
                "$",
                string_limit,
                list_limit,
                trimmed_paths,
            )
            final = json.dumps(compacted, ensure_ascii=False, separators=(",", ":"))
            if len(final) <= limits.max_characters:
                break
    manifest = {
        "policy": f"{node_name}:{profile}:v1",
        "original_characters": len(original),
        "final_characters": len(final),
        "estimated_tokens": max(1, (len(final) + 3) // 4),
        "trimmed": bool(trimmed_paths),
        "trimmed_paths": list(dict.fromkeys(trimmed_paths))[:100],
        "source_hash": hashlib.sha256(original.encode("utf-8")).hexdigest(),
        "preserved_fields": [
            key
            for key in (
                "requirement",
                "retrieved_sources",
                "retrieval_policy",
                "execution_policy",
                "rework_directive",
                "error_message",
            )
            if key in compacted
        ],
    }
    return compacted, manifest


def _legacy_compact(
    value: Any,
    path: str,
    max_string: int,
    max_items: int,
    trimmed_paths: list[str],
) -> Any:
    if path == "$.rework_directive":
        return value
    if isinstance(value, str):
        if len(value) <= max_string:
            return value
        trimmed_paths.append(path)
        suffix_length = min(200, max_string // 4)
        prefix_length = max(1, max_string - suffix_length - 32)
        omitted = len(value) - prefix_length - suffix_length
        return (
            value[:prefix_length]
            + f"\n...[{omitted} characters compacted]...\n"
            + value[-suffix_length:]
        )
    if isinstance(value, list):
        kept = value[:max_items]
        if len(value) > len(kept):
            trimmed_paths.append(path + f"[{len(kept)}:]")
        return [
            _legacy_compact(
                item,
                f"{path}[{index}]",
                max_string,
                max_items,
                trimmed_paths,
            )
            for index, item in enumerate(kept)
        ]
    if isinstance(value, dict):
        return {
            key: _legacy_compact(
                item,
                f"{path}.{key}",
                max_string,
                max_items,
                trimmed_paths,
            )
            for key, item in value.items()
        }
    return value
