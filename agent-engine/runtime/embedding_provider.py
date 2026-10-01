from __future__ import annotations

import hashlib
import math
import os
import re
import unicodedata
from pathlib import Path
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


class LocalSentenceTransformerEmbeddingProvider:
    """Explicit local semantic model for offline retrieval evaluation.

    This provider is opt-in and deliberately separate from the deterministic
    hashing fixture and the OpenAI-compatible production path.  The model
    fingerprint includes the local files so retrieval cache/version checks do
    not silently reuse vectors after a model replacement.
    """

    def __init__(self, *, model_path: str, device: str = "cpu", batch_size: int = 32):
        if not model_path:
            raise ValueError("local embedding mode requires EMBEDDING_LOCAL_MODEL_PATH")
        path = Path(model_path).expanduser()
        if not path.is_dir():
            raise ValueError("local embedding model path must be an existing directory")
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as error:  # pragma: no cover - depends on optional evaluation extra
            raise ValueError(
                "local embedding mode requires the optional sentence-transformers dependency"
            ) from error

        self._model = SentenceTransformer(str(path), device=device)
        dimension = self._model.get_sentence_embedding_dimension()
        if not isinstance(dimension, int) or dimension <= 0:
            raise ValueError("local embedding model returned an invalid dimension")
        self.dimensions = dimension
        self.model_version = (
            f"sentence-transformers:local:{_local_model_fingerprint(path)}:{dimension}"
        )
        self._batch_size = max(1, batch_size)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        vectors = self._model.encode(
            texts,
            batch_size=self._batch_size,
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        if getattr(vectors, "ndim", None) != 2 or len(vectors) != len(texts):
            raise ValueError("local embedding model returned an invalid vector count")
        if vectors.shape[1] != self.dimensions:
            raise ValueError("local embedding model returned invalid vector dimensions")
        result = [[float(value) for value in vector] for vector in vectors]
        if any(not math.isfinite(value) for vector in result for value in vector):
            raise ValueError("local embedding model returned non-finite values")
        return result


def _local_model_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    files = [item for item in path.rglob("*") if item.is_file()]
    for item in sorted(files, key=lambda value: value.relative_to(path).as_posix()):
        digest.update(item.relative_to(path).as_posix().encode("utf-8"))
        with item.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()[:16]


def configured_embedding_provider() -> EmbeddingProvider:
    mode = os.getenv("AUTOSPEC_EMBEDDING_MODE", "fixture").lower()
    environment = os.getenv("AUTOSPEC_ENV", "development").lower()
    if mode == "fixture" and environment not in {"production", "prod"}:
        return HashEmbeddingProvider()
    if mode == "local":
        if environment in {"production", "prod"}:
            raise ValueError("local embedding mode is evaluation-only")
        return LocalSentenceTransformerEmbeddingProvider(
            model_path=os.getenv("EMBEDDING_LOCAL_MODEL_PATH", ""),
            device=os.getenv("EMBEDDING_LOCAL_DEVICE", "cpu"),
        )
    if mode != "live":
        if environment in {"production", "prod"}:
            raise ValueError("production retrieval requires AUTOSPEC_EMBEDDING_MODE=live")
        raise ValueError("embedding mode must be fixture, local, or live")
    return OpenAIEmbeddingProvider(
        base_url=os.getenv("EMBEDDING_BASE_URL", ""),
        api_key=os.getenv("EMBEDDING_API_KEY", ""),
        model=os.getenv("EMBEDDING_MODEL", ""),
    )
