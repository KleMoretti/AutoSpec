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
    arguments = payload.get("arguments")
    if not isinstance(name, str) or not name or not isinstance(arguments, dict):
        raise ValueError("native tool response requires a name and object arguments")
    return {"type": "TOOL_CALL", "name": name, "version": str(payload.get("version", "v1")),
            "arguments": arguments}
