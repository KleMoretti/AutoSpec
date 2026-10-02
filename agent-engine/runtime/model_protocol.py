"""Explicit model/tool protocol boundary; there is no implicit fallback."""
from __future__ import annotations

from typing import Any, Literal


ModelOutputProtocol = Literal["JSON_OBJECT", "NATIVE_TOOL_CALL"]


def validate_output_protocol(protocol: str, capabilities: set[str]) -> ModelOutputProtocol:
    normalized = protocol.strip().upper()
    if normalized not in {"JSON_OBJECT", "NATIVE_TOOL_CALL"}:
        raise ValueError(f"unsupported model output protocol: {protocol}")
    if normalized == "NATIVE_TOOL_CALL" and "native_tool_calls" not in capabilities:
        raise ValueError("native tool protocol is not declared by the provider")
    return normalized  # type: ignore[return-value]


def normalize_native_tool_call(payload: Any) -> dict[str, Any]:
    """Normalize only a provider-native function call envelope.

    JSON text that merely resembles a native call is rejected here; callers
    must select JSON_OBJECT explicitly for the legacy turn protocol.
    """

    if not isinstance(payload, dict) or payload.get("protocol") != "NATIVE_TOOL_CALL":
        raise ValueError("native tool response must carry protocol=NATIVE_TOOL_CALL")
    name = payload.get("name")
    version = payload.get("version")
    arguments = payload.get("arguments")
    call_id = payload.get("id")
    if not isinstance(name, str) or not name or not isinstance(version, str) or not version:
        raise ValueError("native tool response requires a name and version")
    if not isinstance(arguments, dict):
        raise ValueError("native tool response requires object arguments")
    if not isinstance(call_id, str) or not call_id:
        raise ValueError("native tool response requires a provider call id")
    return {
        "turn_type": "TOOL_CALL",
        "name": name,
        "version": version,
        "arguments": arguments,
        "reason": str(payload.get("reason") or "provider-native tool call"),
        "expected_evidence": list(payload.get("expected_evidence") or []),
        "provider_call_id": call_id,
    }
