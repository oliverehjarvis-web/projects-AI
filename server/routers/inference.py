import asyncio
import json
from typing import AsyncIterator
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
import httpx
from pydantic import BaseModel
from auth import require_auth
from config import OLLAMA_URL, DEFAULT_MODEL

router = APIRouter()

# Seconds between SSE heartbeats while Ollama is silent (e.g. prompt processing
# on CPU). Heartbeats are SSE comment lines (": hb\n\n"); clients discard them.
# Keeps the socket alive across the app-side readTimeout.
_HEARTBEAT_INTERVAL_S = 10.0


class InferenceMessage(BaseModel):
    role: str
    content: str


class InferenceConfig(BaseModel):
    model: str = DEFAULT_MODEL
    max_tokens: int = 2048
    temperature: float = 0.7
    top_p: float = 0.95


class InferenceRequest(BaseModel):
    system_prompt: str = ""
    messages: list[InferenceMessage]
    config: InferenceConfig = InferenceConfig()


async def _stream_ollama(req: InferenceRequest) -> AsyncIterator[str]:
    ollama_messages = []
    if req.system_prompt:
        ollama_messages.append({"role": "system", "content": req.system_prompt})
    for m in req.messages:
        role = "assistant" if m.role == "model" else m.role
        ollama_messages.append({"role": role, "content": m.content})

    body = {
        "model": req.config.model,
        "messages": ollama_messages,
        "stream": True,
        "options": {
            "num_predict": req.config.max_tokens,
            "temperature": req.config.temperature,
            "top_p": req.config.top_p,
        },
    }

    # Flush a heartbeat before we even contact Ollama so any intermediate proxy
    # commits response headers immediately and the client knows we're alive.
    yield ": hb\n\n"

    # Big models (e.g. 26B) can take many minutes to load into RAM before Ollama
    # sends its response headers — i.e. `async with client.stream(...)` blocks
    # inside aenter. We need heartbeats to flow during that window, so the Ollama
    # side runs in a background task and the generator pulls events from a queue
    # with a timeout. The timeout branch is what lets us keep the socket alive.
    _SENTINEL = object()
    queue: asyncio.Queue = asyncio.Queue()

    async def pump() -> None:
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream(
                    "POST", f"{OLLAMA_URL}/api/chat", json=body
                ) as response:
                    if response.status_code != 200:
                        detail = ""
                        try:
                            raw = await response.aread()
                            detail = raw.decode("utf-8", errors="replace")[:500]
                            try:
                                detail = json.loads(detail).get("error", detail)
                            except Exception:
                                pass
                        except Exception:
                            pass
                        msg = (
                            f"Ollama HTTP {response.status_code}: {detail}"
                            if detail else f"Ollama HTTP {response.status_code}"
                        )
                        await queue.put(("error", msg))
                        return
                    async for line in response.aiter_lines():
                        if line:
                            await queue.put(("line", line))
        except asyncio.CancelledError:
            raise
        except Exception as e:
            await queue.put(("error", f"Upstream error: {e}"))
        finally:
            await queue.put((_SENTINEL, None))

    task = asyncio.create_task(pump())
    try:
        while True:
            try:
                kind, payload = await asyncio.wait_for(queue.get(), _HEARTBEAT_INTERVAL_S)
            except asyncio.TimeoutError:
                yield ": hb\n\n"
                continue
            if kind is _SENTINEL:
                return
            if kind == "error":
                yield f"data: {json.dumps({'error': payload})}\n\n"
                return
            # kind == "line"
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            token = chunk.get("message", {}).get("content", "")
            if token:
                yield f"data: {json.dumps({'token': token})}\n\n"
            if chunk.get("done"):
                yield "data: [DONE]\n\n"
                return
    finally:
        task.cancel()
        try:
            await task
        except BaseException:
            pass


@router.post("/v1/generate")
async def generate(req: InferenceRequest, _: None = Depends(require_auth)) -> StreamingResponse:
    return StreamingResponse(
        _stream_ollama(req),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
