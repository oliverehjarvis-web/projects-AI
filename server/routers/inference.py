import json
from typing import AsyncIterator
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
import httpx
from pydantic import BaseModel
from auth import require_auth
from config import OLLAMA_URL, DEFAULT_MODEL

router = APIRouter()


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

    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream(
            "POST", f"{OLLAMA_URL}/api/chat", json=body
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                except json.JSONDecodeError:
                    continue
                token = chunk.get("message", {}).get("content", "")
                if token:
                    yield f"data: {json.dumps({'token': token})}\n\n"
                if chunk.get("done"):
                    yield "data: [DONE]\n\n"
                    return


@router.post("/v1/generate")
async def generate(req: InferenceRequest, _: None = Depends(require_auth)) -> StreamingResponse:
    return StreamingResponse(
        _stream_ollama(req),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
