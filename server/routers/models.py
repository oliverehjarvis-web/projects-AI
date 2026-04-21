import json
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
import httpx
from auth import require_auth
from config import OLLAMA_URL

router = APIRouter()

# Curated model catalogue — shown in the "Browse" list.
# Ollama will error gracefully if a tag doesn't exist yet.
CATALOGUE = [
    # ── Gemma 4 ────────────────────────────────────────────────────────────
    {"id": "gemma4:2b",  "family": "Gemma 4",  "label": "Gemma 4 E2B",  "size_gb": 1.9,  "notes": "Fastest · good for most tasks"},
    {"id": "gemma4:9b",  "family": "Gemma 4",  "label": "Gemma 4 9B",   "size_gb": 5.8,  "notes": "Balanced speed & quality"},
    {"id": "gemma4:27b", "family": "Gemma 4",  "label": "Gemma 4 27B",  "size_gb": 17.0, "notes": "Best quality · GPU recommended"},
    # ── Gemma 3 ────────────────────────────────────────────────────────────
    {"id": "gemma3:1b",  "family": "Gemma 3",  "label": "Gemma 3 1B",   "size_gb": 0.8,  "notes": "Ultra-fast · limited quality"},
    {"id": "gemma3:4b",  "family": "Gemma 3",  "label": "Gemma 3 4B",   "size_gb": 2.5,  "notes": "Fast · great quality/size ratio"},
    {"id": "gemma3:12b", "family": "Gemma 3",  "label": "Gemma 3 12B",  "size_gb": 7.8,  "notes": "High quality"},
    {"id": "gemma3:27b", "family": "Gemma 3",  "label": "Gemma 3 27B",  "size_gb": 17.0, "notes": "Best Gemma 3 · GPU recommended"},
    # ── Llama ──────────────────────────────────────────────────────────────
    {"id": "llama3.2:3b",  "family": "Llama",  "label": "Llama 3.2 3B",  "size_gb": 2.0,  "notes": "Fast general assistant"},
    {"id": "llama3.1:8b",  "family": "Llama",  "label": "Llama 3.1 8B",  "size_gb": 4.9,  "notes": "Strong reasoning"},
    {"id": "llama3.3:70b", "family": "Llama",  "label": "Llama 3.3 70B", "size_gb": 43.0, "notes": "Flagship · GPU required"},
    # ── Mistral ────────────────────────────────────────────────────────────
    {"id": "mistral:7b",         "family": "Mistral", "label": "Mistral 7B",          "size_gb": 4.5,  "notes": "Fast & capable"},
    {"id": "mistral-nemo:12b",   "family": "Mistral", "label": "Mistral Nemo 12B",    "size_gb": 7.5,  "notes": "Great instruction following"},
    # ── Qwen ───────────────────────────────────────────────────────────────
    {"id": "qwen2.5:7b",  "family": "Qwen",   "label": "Qwen 2.5 7B",   "size_gb": 4.7,  "notes": "Strong at coding & math"},
    {"id": "qwen2.5:32b", "family": "Qwen",   "label": "Qwen 2.5 32B",  "size_gb": 20.0, "notes": "Top tier · GPU recommended"},
    # ── Phi ────────────────────────────────────────────────────────────────
    {"id": "phi4:14b",    "family": "Phi",    "label": "Phi 4 14B",      "size_gb": 9.0,  "notes": "Microsoft · strong reasoning"},
]


@router.get("/v1/models")
async def list_models(_: None = Depends(require_auth)):
    """Returns installed models (from Ollama) and the browseable catalogue."""
    installed: list[dict] = []
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
            if r.status_code == 200:
                installed = [
                    {"id": m["name"], "size_gb": round(m.get("size", 0) / 1e9, 1)}
                    for m in r.json().get("models", [])
                ]
    except Exception:
        pass

    installed_ids = {m["id"] for m in installed}
    catalogue = [
        {**m, "installed": m["id"] in installed_ids}
        for m in CATALOGUE
    ]
    # Also include any locally installed models not in the catalogue
    for m in installed:
        if m["id"] not in {c["id"] for c in CATALOGUE}:
            catalogue.append({**m, "family": "Other", "label": m["id"],
                              "notes": "Locally installed", "installed": True})

    return {"installed": installed, "catalogue": catalogue}


@router.delete("/v1/models/{model_name:path}")
async def delete_model(model_name: str, _: None = Depends(require_auth)):
    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.delete(f"{OLLAMA_URL}/api/delete", json={"name": model_name})
    return {"ok": r.status_code in (200, 404)}


async def _pull_stream(model_name: str):
    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream("POST", f"{OLLAMA_URL}/api/pull",
                                 json={"name": model_name}) as r:
            async for line in r.aiter_lines():
                if not line:
                    continue
                try:
                    data = json.loads(line)
                except json.JSONDecodeError:
                    continue
                status = data.get("status", "")
                total = data.get("total", 0)
                completed = data.get("completed", 0)
                progress = round(completed / total * 100) if total else None
                yield f"data: {json.dumps({'status': status, 'progress': progress})}\n\n"
    yield "data: {\"status\": \"done\", \"progress\": 100}\n\n"


@router.post("/v1/models/pull/{model_name:path}")
async def pull_model(model_name: str, _: None = Depends(require_auth)):
    return StreamingResponse(
        _pull_stream(model_name),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
