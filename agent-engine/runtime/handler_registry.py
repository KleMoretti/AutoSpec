from __future__ import annotations

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
        )

    def resolve(self, handler_key: str, handler_version: str) -> HandlerRegistration:
        try:
            return self._registrations[(handler_key, handler_version)]
        except KeyError as exception:
            raise UnknownHandlerError(
                f"unknown handler: {handler_key}:{handler_version}"
            ) from exception
