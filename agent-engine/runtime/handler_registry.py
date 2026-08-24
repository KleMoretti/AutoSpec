from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Callable

from pydantic import BaseModel


class UnknownHandlerError(KeyError):
    pass


@dataclass(frozen=True)
class HandlerRegistration:
    handler_key: str
    handler_version: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    handler: Callable[[BaseModel], Any]
    input_schema: str | None = None
    input_schema_hash: str | None = None
    output_schema: str | None = None
    output_schema_hash: str | None = None
    prompt_key: str | None = None
    prompt_version: str | None = None
    prompt_checksum: str | None = None


class HandlerRegistry:
    def __init__(self) -> None:
        self._registrations: dict[tuple[str, str], HandlerRegistration] = {}

    def register(
        self,
        handler_key: str,
        handler_version: str,
        input_model: type[BaseModel],
        output_model: type[BaseModel],
        handler: Callable[[BaseModel], Any],
        *,
        input_schema: str | None = None,
        output_schema: str | None = None,
        prompt_key: str | None = None,
        prompt_version: str | None = None,
        prompt_checksum: str | None = None,
    ) -> None:
        key = (handler_key, handler_version)
        if key in self._registrations:
            raise ValueError(f"handler already registered: {handler_key}:{handler_version}")
        self._registrations[key] = HandlerRegistration(
            handler_key=handler_key,
            handler_version=handler_version,
            input_model=input_model,
            output_model=output_model,
            handler=handler,
            input_schema=input_schema,
            input_schema_hash=(
                schema_fingerprint(input_model) if input_schema is not None else None
            ),
            output_schema=output_schema,
            output_schema_hash=(
                schema_fingerprint(output_model) if output_schema is not None else None
            ),
            prompt_key=prompt_key,
            prompt_version=prompt_version,
            prompt_checksum=prompt_checksum,
        )

    def resolve(self, handler_key: str, handler_version: str) -> HandlerRegistration:
        try:
            return self._registrations[(handler_key, handler_version)]
        except KeyError as exception:
            raise UnknownHandlerError(
                f"unknown handler: {handler_key}:{handler_version}"
            ) from exception


def schema_fingerprint(model: type[BaseModel]) -> str:
    canonical = json.dumps(
        model.model_json_schema(),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
