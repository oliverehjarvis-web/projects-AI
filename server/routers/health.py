from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from auth import require_auth
import ollama_client

router = APIRouter()


@router.get("/v1/health")
async def health(_: None = Depends(require_auth)) -> JSONResponse:
    reachable = await ollama_client.is_reachable()
    return JSONResponse({
        "status": "ok",
        "ollama": "reachable" if reachable else "unreachable",
    })
