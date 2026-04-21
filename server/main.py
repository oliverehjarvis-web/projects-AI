from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
import os

from db import init_db
from routers import health, inference, sync


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield


app = FastAPI(title="Projects AI Server", lifespan=lifespan)

app.include_router(health.router)
app.include_router(inference.router)
app.include_router(sync.router)

_WEB_DIST = os.path.join(os.path.dirname(__file__), "web", "dist")
if os.path.isdir(_WEB_DIST):
    app.mount("/assets", StaticFiles(directory=os.path.join(_WEB_DIST, "assets")), name="assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    async def spa_fallback(full_path: str):
        index = os.path.join(_WEB_DIST, "index.html")
        return FileResponse(index)
