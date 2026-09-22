from __future__ import annotations

import hashlib
import math
import os
import re
import unicodedata
from typing import Protocol

from openai import OpenAI


class EmbeddingProvider(Protocol):
    @property
    def model_version(self) -> str: ...

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class HashEmbeddingProvider:
    """Deterministic fixture only; not a semantic embedding model."""

    model_version = "fixture-hashing-ngram-v1"
    dimensions = 128

    def embed(self, texts: list[str]) -> list[list[float]]:
        result = []
        for value in texts:
            vector = [0.0] * self.dimensions
            tokens = re.findall(r"[\u3400-\u9fff]|[A-Za-z0-9_]+", unicodedata.normalize("NFKC", value).lower())
            for token in tokens:
                features = [f"token:{token}"] + [
                    f"ngram:{token[index:index + width]}"
                    for width in (2, 3)
                    for index in range(max(0, len(token) - width + 1))
                ]
                for feature in features:
                    digest = hashlib.sha256(feature.encode()).digest()
                    index = int.from_bytes(digest[:2], "big") % self.dimensions
                    vector[index] += (1 if digest[2] % 2 == 0 else -1) * (1.5 if feature.startswith("token:") else 0.8)
            norm = math.sqrt(sum(item * item for item in vector))
            result.append([item / norm for item in vector] if norm else vector)
        return result


class OpenAIEmbeddingProvider:
    """OpenAI-compatible embeddings endpoint with explicit, separate model config."""

    def __init__(self, *, base_url: str, api_key: str, model: str, timeout: float = 10.0):
        if not base_url or not api_key or not model:
            raise ValueError("Embedding base URL, API key and model must be configured")
        self.model_version = f"openai-compatible:{model}:{hashlib.sha256(base_url.encode()).hexdigest()[:12]}"
        self._model = model
        self._client = OpenAI(base_url=base_url, api_key=api_key, timeout=timeout, max_retries=1)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        response = self._client.embeddings.create(model=self._model, input=texts)
        ordered = sorted(response.data, key=lambda item: item.index)
        vectors = [item.embedding for item in ordered]
        if len(vectors) != len(texts) or not vectors or not vectors[0]:
            raise ValueError("Embedding endpoint returned an invalid vector count")
        dimension = len(vectors[0])
        if any(len(vector) != dimension or any(not math.isfinite(value) for value in vector) for vector in vectors):
            raise ValueError("Embedding endpoint returned invalid vector dimensions")
        return vectors


def configured_embedding_provider() -> EmbeddingProvider:
    mode = os.getenv("AUTOSPEC_EMBEDDING_MODE", "fixture").lower()
    environment = os.getenv("AUTOSPEC_ENV", "development").lower()
    if mode == "fixture" and environment not in {"production", "prod"}:
        return HashEmbeddingProvider()
    if mode != "live":
        raise ValueError("Production retrieval requires AUTOSPEC_EMBEDDING_MODE=live")
    return OpenAIEmbeddingProvider(
        base_url=os.getenv("EMBEDDING_BASE_URL", ""),
        api_key=os.getenv("EMBEDDING_API_KEY", ""),
        model=os.getenv("EMBEDDING_MODEL", ""),
    )
