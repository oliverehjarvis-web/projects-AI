"""
Tiny SQL migration runner.

Schema changes go in `server/migrations/NNN_description.sql`. The numeric prefix is
the version; files are applied in ascending order, and a `_migrations` table tracks
which versions are already applied so re-runs are no-ops on existing volumes.

Why not Alembic? For a single-user homelab DB, alembic adds a metadata model layer
plus `alembic.ini`, `env.py`, and `versions/` machinery to track ~5 tables. A 60-line
runner is enough — and SQL files stay readable diff-by-diff.

Caveat: `executescript` commits implicitly per file in SQLite, so a partial failure
mid-file leaves the DB in an in-between state. For a single-user system that's
acceptable; if a migration starts being non-trivial, split it across two files or
wrap statements in `BEGIN; ... COMMIT;` blocks.
"""
from __future__ import annotations

import logging
import os
import time
from pathlib import Path

import aiosqlite

logger = logging.getLogger(__name__)

_MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"


async def run_migrations(db_path: str) -> None:
    """Apply every unapplied `NNN_*.sql` file in the migrations directory, in order."""
    os.makedirs(os.path.dirname(db_path) or ".", exist_ok=True)
    async with aiosqlite.connect(db_path) as db:
        await db.execute(
            """CREATE TABLE IF NOT EXISTS _migrations (
                   version INTEGER PRIMARY KEY,
                   applied_at INTEGER NOT NULL
               )"""
        )
        await db.commit()
        async with db.execute("SELECT version FROM _migrations") as cur:
            applied = {row[0] for row in await cur.fetchall()}

        for path in sorted(_MIGRATIONS_DIR.glob("*.sql")):
            try:
                version = int(path.name.split("_", 1)[0])
            except ValueError:
                logger.warning("ignoring migration with non-numeric prefix: %s", path.name)
                continue
            if version in applied:
                continue
            logger.info("applying migration %s", path.name)
            sql = path.read_text(encoding="utf-8")
            await db.executescript(sql)
            await db.execute(
                "INSERT INTO _migrations(version, applied_at) VALUES (?, ?)",
                (version, int(time.time() * 1000)),
            )
            await db.commit()
            logger.info("migration %s applied", path.name)
