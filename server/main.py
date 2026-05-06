from contextlib import asynccontextmanager
import logging
import os

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from db import init_db
from routers import health, inference, server_info, sync, models, web_search

# Force-configure the root logger before any router emits. Uvicorn ships with its own
# `uvicorn.*` handlers but leaves the root at WARNING, which silences our routers.
# `force=True` overrides any handler uvicorn may have attached on the root.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
    force=True,
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield


app = FastAPI(title="Projects AI Server", lifespan=lifespan)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # Clients (Android SyncRepository, model browser) need a structured message to surface in
    # the UI; a bare 500 leaks a stack trace and gives no actionable feedback.
    logger.exception("Unhandled error on %s %s", request.method, request.url.path)
    return JSONResponse(
        status_code=500,
        content={"error": str(exc) or exc.__class__.__name__, "type": exc.__class__.__name__},
    )


app.include_router(health.router)
app.include_router(inference.router)
app.include_router(server_info.router)
app.include_router(sync.router)
app.include_router(models.router)
app.include_router(web_search.router)

_WEB_DIST = os.path.join(os.path.dirname(__file__), "web", "dist")
if os.path.isdir(_WEB_DIST):
    app.mount("/assets", StaticFiles(directory=os.path.join(_WEB_DIST, "assets")), name="assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    async def spa_fallback(full_path: str):
        index = os.path.join(_WEB_DIST, "index.html")
        return FileResponse(index)
