"""
/v1/server_info — host RAM and the loaded Ollama model's footprint, so the
Android app can recommend a context window that fits in the NAS's available RAM
without needing the user to do the math.

Linux-only on the host side (reads /proc/meminfo). The server runs in Docker on
a Linux NAS, so this is fine; the endpoint returns 200 with best-effort fallback
values on non-Linux hosts.
"""

from __future__ import annotations

from typing import Any

import httpx
from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from auth import require_auth
from config import OLLAMA_URL


router = APIRouter()


def _read_meminfo() -> dict[str, int]:
    """Returns {total_kb, available_kb} from /proc/meminfo, or zeros if unavailable."""
    out = {"total_kb": 0, "available_kb": 0}
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as f:
            for line in f:
                # Lines look like: "MemTotal:       29384672 kB"
                if line.startswith("MemTotal:"):
                    out["total_kb"] = int(line.split()[1])
                elif line.startswith("MemAvailable:"):
                    out["available_kb"] = int(line.split()[1])
    except OSError:
        pass
    return out


async def _ollama_model_info(model: str) -> dict[str, Any] | None:
    """Pulls /api/show for a given model. Returns None on any failure."""
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.post(f"{OLLAMA_URL}/api/show", json={"name": model})
        if r.status_code != 200:
            return None
        return r.json()
    except Exception:
        return None


def _params_billions(model_info: dict[str, Any]) -> float | None:
    """
    Pulls a 'parameter size' number out of Ollama's /api/show response. The shape
    varies between Ollama versions; we look in `details.parameter_size` (e.g.
    '26.4B') and fall back to None.
    """
    details = model_info.get("details") or {}
    raw = details.get("parameter_size")
    if not isinstance(raw, str):
        return None
    s = raw.strip().upper().rstrip("B")
    try:
        return float(s)
    except ValueError:
        return None


# Per-token KV-cache cost at fp16, used when we can't extract anything more
# accurate from the model card. Empirically ~50 KB/token for a 4B-class model
# and ~100 KB/token for a 26B-class model — these are loose averages.
_KV_PER_TOKEN_KB_BY_PARAMS = {
    4: 50,
    8: 70,
    13: 90,
    26: 100,
    34: 130,
}


def _kv_per_token_kb(params_b: float | None) -> int:
    if params_b is None:
        return 80  # middle-of-the-road default
    # Pick the closest tabulated bracket.
    closest = min(_KV_PER_TOKEN_KB_BY_PARAMS.keys(), key=lambda k: abs(k - params_b))
    return _KV_PER_TOKEN_KB_BY_PARAMS[closest]


@router.get("/v1/server_info")
async def server_info(
    model: str | None = Query(default=None, description="Optional model name to size."),
    _: None = Depends(require_auth),
) -> JSONResponse:
    mem = _read_meminfo()
    payload: dict[str, Any] = {
        "ram_total_gb": round(mem["total_kb"] / (1024 * 1024), 2),
        "ram_available_gb": round(mem["available_kb"] / (1024 * 1024), 2),
    }
    if model:
        info = await _ollama_model_info(model)
        params_b = _params_billions(info) if info else None
        payload["model_params_b"] = params_b
        payload["kv_per_token_kb"] = _kv_per_token_kb(params_b)
    return JSONResponse(payload)
