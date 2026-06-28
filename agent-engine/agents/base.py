from typing import Any, Mapping, Protocol


class ModelClient(Protocol):
    def generate_json(self, prompt_name: str, input_payload: Mapping[str, Any]) -> Mapping[str, Any]:
        """Return a JSON-compatible mapping for the given prompt."""
