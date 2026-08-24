from __future__ import annotations

import copy
import hashlib
import json
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any
from typing import Iterator


_MANIFEST_COLLECTOR: ContextVar[list[dict[str, Any]] | None] = ContextVar(
    "autospec_context_manifest_collector",
    default=None,
)


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


def apply_context_policy(
    node_name: str,
    payload: dict[str, Any],
    quality_profile: str | None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    profile = (quality_profile or "BALANCED").upper()
    limits = LIMITS.get(profile, LIMITS["BALANCED"])
    original = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    trimmed_paths: list[str] = []
    compacted = _compact(
        copy.deepcopy(payload),
        "$",
        limits.max_string_characters,
        limits.max_list_items,
        trimmed_paths,
    )
    final = json.dumps(compacted, ensure_ascii=False, separators=(",", ":"))
    if len(final) > limits.max_characters:
        for string_limit, list_limit in ((1000, 6), (500, 3), (250, 1)):
            compacted = _compact(
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
                "error_message",
            )
            if key in compacted
        ],
    }
    collector = _MANIFEST_COLLECTOR.get()
    if collector is not None:
        collector.append(manifest)
    compacted["context_manifest"] = manifest
    return compacted, manifest


def _compact(
    value: Any,
    path: str,
    max_string: int,
    max_items: int,
    trimmed_paths: list[str],
) -> Any:
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
            _compact(item, f"{path}[{index}]", max_string, max_items, trimmed_paths)
            for index, item in enumerate(kept)
        ]
    if isinstance(value, dict):
        return {
            key: _compact(item, f"{path}.{key}", max_string, max_items, trimmed_paths)
            for key, item in value.items()
        }
    return value
