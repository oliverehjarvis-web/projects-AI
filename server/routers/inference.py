import asyncio
import json
import sys
import time
from typing import AsyncIterator
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
import httpx
from pydantic import BaseModel
from auth import require_auth
from config import OLLAMA_URL, DEFAULT_MODEL


def _log(fmt: str, *args: object) -> None:
    # Uvicorn doesn't attach a handler to arbitrary loggers, so we write to
    # stdout directly to guarantee the line shows up in `docker logs`.
    print(f"[inference] {fmt % args}" if args else f"[inference] {fmt}", file=sys.stdout, flush=True)


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


# Prefix prepended to every system prompt so thinking-capable models don't
# re-evaluate persistent project context for trivial prompts. Keep this short —
# long system prompts themselves provoke longer reasoning on some models.
_REASONING_PREAMBLE = (
    "Match the depth of your reasoning to the complexity of the user's request. "
    "For simple greetings, one-word answers, or short factual replies, respond "
    "directly with little or no internal deliberation. "
    "Treat the project instructions and memory below as persistent background "
    "preferences — apply them naturally, do not re-analyse them on every turn."
)


async def _stream_ollama(req: InferenceRequest) -> AsyncIterator[str]:
    ollama_messages = []
    system_prompt = req.system_prompt.strip()
    combined_system = (
        f"{_REASONING_PREAMBLE}\n\n---\n{system_prompt}"
        if system_prompt else _REASONING_PREAMBLE
    )
    ollama_messages.append({"role": "system", "content": combined_system})
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
    started = time.monotonic()
    _log("generate start model=%s msgs=%d", req.config.model, len(ollama_messages))

    async def pump() -> None:
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream(
                    "POST", f"{OLLAMA_URL}/api/chat", json=body
                ) as response:
                    _log(
                        "ollama connected status=%d after %.1fs",
                        response.status_code, time.monotonic() - started,
                    )
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
                        _log("ollama non-200: %s", msg)
                        await queue.put(("error", msg))
                        return
                    async for line in response.aiter_lines():
                        if line:
                            await queue.put(("line", line))
                    _log("ollama stream closed cleanly after %.1fs", time.monotonic() - started)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            import traceback
            _log("ollama pump exception:\n%s", traceback.format_exc())
            await queue.put(("error", f"Upstream error: {e}"))
        finally:
            await queue.put((_SENTINEL, None))

    task = asyncio.create_task(pump())
    tokens_sent = 0
    thinking_tokens_sent = 0
    first_line_logged = False
    in_thinking_block = False  # whether we've opened a <think> wrapper the client hasn't seen close yet
    try:
        while True:
            try:
                kind, payload = await asyncio.wait_for(queue.get(), _HEARTBEAT_INTERVAL_S)
            except asyncio.TimeoutError:
                yield ": hb\n\n"
                continue
            if kind is _SENTINEL:
                if in_thinking_block:
                    # Close the wrapper so the app doesn't render a dangling tag.
                    yield f"data: {json.dumps({'token': '</think>\n\n'})}\n\n"
                _log(
                    "generate end content=%d thinking=%d elapsed=%.1fs",
                    tokens_sent, thinking_tokens_sent, time.monotonic() - started,
                )
                return
            if kind == "error":
                yield f"data: {json.dumps({'error': payload})}\n\n"
                return
            # kind == "line"
            if not first_line_logged:
                # First line from Ollama tells us whether we're streaming tokens
                # or hitting a response-shaped error we weren't expecting.
                _log("ollama first line (%d bytes): %s", len(payload), payload[:300])
                first_line_logged = True
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            # Ollama can return a 200 with a JSON line carrying an error field.
            if "error" in chunk:
                msg = chunk.get("error") or "Ollama returned an error"
                _log("ollama in-stream error: %s", msg)
                yield f"data: {json.dumps({'error': str(msg)})}\n\n"
                return
            message = chunk.get("message", {}) or {}
            # Thinking models (QwQ, gemma4 thinking variants, DeepSeek-R1) stream
            # chain-of-thought in message.thinking before any message.content. If
            # we drop those, the app sees silence for minutes. Wrap them in
            # <think>…</think> so the current client renders them as visible text.
            thinking = message.get("thinking") or ""
            content = message.get("content") or ""
            if thinking:
                if not in_thinking_block:
                    yield f"data: {json.dumps({'token': '<think>'})}\n\n"
                    in_thinking_block = True
                thinking_tokens_sent += 1
                yield f"data: {json.dumps({'token': thinking})}\n\n"
            if content:
                if in_thinking_block:
                    yield f"data: {json.dumps({'token': '</think>\n\n'})}\n\n"
                    in_thinking_block = False
                tokens_sent += 1
                yield f"data: {json.dumps({'token': content})}\n\n"
            if chunk.get("done"):
                if in_thinking_block:
                    yield f"data: {json.dumps({'token': '</think>\n\n'})}\n\n"
                    in_thinking_block = False
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
