import aiosqlite

from config import DB_PATH
from migrate import run_migrations


async def init_db() -> None:
    """Apply any unapplied migrations under `migrations/`. Idempotent on re-run."""
    await run_migrations(DB_PATH)


async def get_db() -> aiosqlite.Connection:
    db = await aiosqlite.connect(DB_PATH)
    db.row_factory = aiosqlite.Row
    return db
