"""
Thin async wrapper around the Ollama HTTP API. Centralises base URL, default timeouts,
and the silent-failure pattern that was otherwise scattered across health.py,
models.py, server_info.py, and inference.py.

Read calls (`tags`, `show`) return None on any failure and log the reason at WARNING.
Callers that want to surface unreachable-Ollama state to the UI can use the helper
[is_reachable] or check the return value directly.

Streaming calls expose the raw httpx response/iterator since the SSE pump in
inference.py wants access to status_code and aread() on non-200 responses.
"""
from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import httpx

from config import OLLAMA_URL

logger = logging.getLogger(__name__)


def _api_url(endpoint: str) -> str:
    return f"{OLLAMA_URL}/api/{endpoint.lstrip('/')}"


async def tags(timeout_s: float = 5.0) -> dict[str, Any] | None:
    """List installed models. Returns None if Ollama is unreachable or returns non-200."""
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.get(_api_url("tags"))
    except httpx.HTTPError as e:
        logger.warning("ollama /api/tags unreachable: %s", e)
        return None
    if r.status_code != 200:
        logger.warning("ollama /api/tags returned %d", r.status_code)
        return None
    return r.json()


async def show(model: str, timeout_s: float = 5.0) -> dict[str, Any] | None:
    """Fetch model card via /api/show. Returns None if Ollama or the model is unavailable."""
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.post(_api_url("show"), json={"name": model})
    except httpx.HTTPError as e:
        logger.warning("ollama /api/show %s unreachable: %s", model, e)
        return None
    if r.status_code != 200:
        logger.warning("ollama /api/show %s returned %d", model, r.status_code)
        return None
    return r.json()


async def delete(model: str, timeout_s: float = 30.0) -> bool:
    """Delete a model. Returns True if the deletion landed (200) or the model was already gone (404)."""
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            r = await client.delete(_api_url("delete"), json={"name": model})
    except httpx.HTTPError as e:
        logger.warning("ollama /api/delete %s failed: %s", model, e)
        return False
    return r.status_code in (200, 404)


async def pull(model: str) -> AsyncIterator[dict[str, Any]]:
    """
    Stream pull progress events as decoded JSON objects. Each event has a `status`
    field plus `total` / `completed` while a layer is downloading. No timeout —
    pulls take minutes.

    Errors are yielded as `{"error": "..."}` dicts so the caller can surface them
    in the SSE stream rather than swallowing them.
    """
    try:
        async with httpx.AsyncClient(timeout=None) as client:
            async with client.stream("POST", _api_url("pull"), json={"name": model}) as r:
                async for line in r.aiter_lines():
                    if not line:
                        continue
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        continue
    except httpx.HTTPError as e:
        logger.warning("ollama /api/pull %s failed: %s", model, e)
        yield {"error": str(e)}


@asynccontextmanager
async def chat_stream(body: dict[str, Any]):
    """
    Open a streaming chat completion. Caller receives the raw httpx response so it
    can inspect status_code, read non-200 error bodies, and iterate aiter_lines().
    No timeout — chat may take many minutes for large models on cold start.
    """
    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream("POST", _api_url("chat"), json=body) as response:
            yield response


async def is_reachable(timeout_s: float = 5.0) -> bool:
    """Convenience wrapper used by /v1/health to surface upstream state."""
    return await tags(timeout_s=timeout_s) is not None
