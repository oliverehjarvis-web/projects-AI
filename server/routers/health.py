from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
import httpx
from auth import require_auth
from config import OLLAMA_URL

router = APIRouter()


@router.get("/v1/health")
async def health(_: None = Depends(require_auth)) -> JSONResponse:
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
        ollama_status = "reachable" if r.status_code == 200 else "unreachable"
    except Exception:
        ollama_status = "unreachable"
    return JSONResponse({"status": "ok", "ollama": ollama_status})
