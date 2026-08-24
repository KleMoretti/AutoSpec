from __future__ import annotations

import hashlib
import re
from typing import Annotated, Any, Iterable, Mapping

from pydantic import Field


REQUIREMENT_ID_PATTERN = r"^REQ-[A-Z0-9][A-Z0-9_-]{2,63}$"
COMPONENT_ID_PATTERN = (
    r"^(STORY|AC|MOD|ADR|NFR|TABLE|FIELD|API|ROUTE|PAGE|COMP|BIND)-"
    r"[A-Z0-9][A-Z0-9_-]{2,63}$"
)

RequirementId = Annotated[str, Field(pattern=REQUIREMENT_ID_PATTERN)]
ComponentId = Annotated[str, Field(pattern=COMPONENT_ID_PATTERN)]


def stable_id(prefix: str, *parts: object) -> str:
    """Return a stable semantic id that does not depend on array position."""
    material = "|".join(_normalize(str(part)) for part in parts if part is not None)
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()[:12].upper()
    return f"{prefix}-{digest}"


def unique_refs(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value.strip().upper() for value in values if value.strip()))


def fallback_requirement_mapping(requirement_ids: Iterable[str | None]) -> dict[str, str]:
    """Map deterministic fixture aliases onto the actual PRD identities."""
    available = [value for value in requirement_ids if value]
    if not available:
        return {}
    aliases = ["REQ-PUBLISH", "REQ-SEARCH", "REQ-FAVORITE", "REQ-AUDIT"]
    return {
        alias: available[min(index, len(available) - 1)]
        for index, alias in enumerate(aliases)
    }


def remap_requirement_refs(value: Any, mapping: Mapping[str, str]) -> Any:
    """Return a copy whose requirement_refs cannot point outside the input PRD."""
    if isinstance(value, dict):
        return {
            key: (
                unique_refs(mapping.get(str(ref), str(ref)) for ref in child)
                if key == "requirement_refs" and isinstance(child, list)
                else remap_requirement_refs(child, mapping)
            )
            for key, child in value.items()
        }
    if isinstance(value, list):
        return [remap_requirement_refs(child, mapping) for child in value]
    return value


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())
