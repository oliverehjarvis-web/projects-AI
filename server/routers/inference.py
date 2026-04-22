import asyncio
import datetime
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
from db import get_db


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
    # Base64-encoded raw image bytes (no data URI prefix). Ollama's multimodal
    # chat API consumes them via `images: [...]` on the message. Only meaningful
    # on user messages and only when the upstream model is vision-capable.
    images: list[str] = []


class InferenceConfig(BaseModel):
    model: str = DEFAULT_MODEL
    max_tokens: int = 2048
    temperature: float = 0.7
    top_p: float = 0.95


class InferenceRequest(BaseModel):
    system_prompt: str = ""
    messages: list[InferenceMessage]
    config: InferenceConfig = InferenceConfig()
    # Optional explicit global context overrides. If omitted, the server looks
    # them up from the global_context table so all clients share the same values.
    user_name: str | None = None
    global_rules: str | None = None


# Prefix prepended to every system prompt. Two jobs:
#   1. Scale reasoning depth to the request — a greeting should not trigger
#      paragraphs of deliberation.
#   2. Soft-frame the project/global guidelines so the model doesn't fall into a
#      rule-checking loop where it re-evaluates each constraint against each
#      draft. Explicitly license it to deviate (with a short note) rather than
#      keep ruminating.
#   3. Give it an explicit stop-condition for thinking: if it revisits the same
#      concern twice, commit and state the remaining uncertainty.
_REASONING_PREAMBLE = (
    "Match the depth of your reasoning to the complexity of the user's request. "
    "For simple greetings, one-word answers, or short factual replies, respond "
    "directly with little or no internal deliberation.\n\n"
    "The project and user guidelines below are soft preferences, not hard rules. "
    "Apply them naturally as background context. If a specific request is better "
    "served by deviating from a guideline, deviate — but add one short line at the "
    "end of your answer explaining what you broke and why (e.g. \"Note: used "
    "American spelling here because the user quoted American sources\"). Do not "
    "re-analyse these guidelines on every turn; treat them like a colleague's "
    "standing preferences rather than a checklist.\n\n"
    "Thinking stop-condition: if you notice yourself revisiting the same concern "
    "a second time, stop deliberating. Commit to your best current answer and, if "
    "anything is still uncertain, surface that uncertainty in one line of the "
    "final reply. Never loop over the same three points — decide and move on."
)


def _temporal_block() -> str:
    now = datetime.datetime.now().astimezone()
    date = now.strftime("%A, %-d %B %Y")
    time_str = now.strftime("%H:%M")
    return f"Current context:\n- Date: {date}\n- Time: {time_str}\n- Timezone: {now.tzname()}"


async def _load_global_context() -> tuple[str, str]:
    db = await get_db()
    try:
        async with db.execute(
            "SELECT user_name, rules FROM global_context WHERE id = 1"
        ) as cur:
            row = await cur.fetchone()
    finally:
        await db.close()
    if row is None:
        return "", ""
    return (row["user_name"] or ""), (row["rules"] or "")


def _build_global_block(user_name: str, rules: str) -> str:
    parts: list[str] = []
    if user_name.strip():
        parts.append(f"You are speaking with {user_name.strip()}.")
    if rules.strip():
        # Framed as guidelines, not commands — paired with the preamble's
        # deviation-is-OK clause this avoids the rule-checking loop.
        parts.append(
            "The user has these standing guidelines (soft preferences — follow by default, "
            "deviate with a brief note when a specific request needs it):\n"
            f"{rules.strip()}"
        )
    return "\n\n".join(parts)


async def _stream_ollama(req: InferenceRequest) -> AsyncIterator[str]:
    ollama_messages = []
    # Pull global context either from the request (explicit override) or the
    # server-side singleton. None vs "" matters: None means "use server default",
    # "" means "explicitly empty — don't look up".
    if req.user_name is None and req.global_rules is None:
        user_name, global_rules = await _load_global_context()
    else:
        user_name = req.user_name or ""
        global_rules = req.global_rules or ""

    system_prompt = req.system_prompt.strip()
    sections = [_REASONING_PREAMBLE, _temporal_block()]
    global_block = _build_global_block(user_name, global_rules)
    if global_block:
        sections.append(global_block)
    if system_prompt:
        sections.append(system_prompt)
    combined_system = "\n\n---\n\n".join(sections)
    ollama_messages.append({"role": "system", "content": combined_system})
    for m in req.messages:
        role = "assistant" if m.role == "model" else m.role
        msg: dict = {"role": role, "content": m.content}
        if m.images:
            msg["images"] = m.images
        ollama_messages.append(msg)

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
