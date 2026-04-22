import aiosqlite
from config import DB_PATH

_CREATE_SQL = """
CREATE TABLE IF NOT EXISTS projects (
    remote_id   TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    manual_context      TEXT NOT NULL DEFAULT '',
    accumulated_memory  TEXT NOT NULL DEFAULT '',
    pinned_memories     TEXT NOT NULL DEFAULT '',
    preferred_backend   TEXT NOT NULL DEFAULT 'LOCAL',
    memory_token_limit  INTEGER NOT NULL DEFAULT 4000,
    context_length      INTEGER NOT NULL DEFAULT 16384,
    is_secret           INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    deleted_at          INTEGER
);

CREATE TABLE IF NOT EXISTS chats (
    remote_id           TEXT PRIMARY KEY,
    project_remote_id   TEXT NOT NULL REFERENCES projects(remote_id) ON DELETE CASCADE,
    title               TEXT NOT NULL DEFAULT 'New Chat',
    web_search_enabled  INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    deleted_at          INTEGER
);

CREATE TABLE IF NOT EXISTS messages (
    remote_id        TEXT PRIMARY KEY,
    chat_remote_id   TEXT NOT NULL REFERENCES chats(remote_id) ON DELETE CASCADE,
    role             TEXT NOT NULL,
    content          TEXT NOT NULL,
    token_count      INTEGER NOT NULL DEFAULT 0,
    attachment_paths TEXT NOT NULL DEFAULT '',
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    deleted_at       INTEGER
);

CREATE TABLE IF NOT EXISTS quick_actions (
    remote_id           TEXT PRIMARY KEY,
    project_remote_id   TEXT NOT NULL REFERENCES projects(remote_id) ON DELETE CASCADE,
    name                TEXT NOT NULL,
    prompt_template     TEXT NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    deleted_at          INTEGER
);

-- Singleton row (id=1) holding cross-project user preferences. Exposed via
-- /v1/global_context so both the web UI and Android can share one source of
-- truth for the user's name and the soft rules injected into every system
-- prompt.
CREATE TABLE IF NOT EXISTS global_context (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    user_name   TEXT NOT NULL DEFAULT '',
    rules       TEXT NOT NULL DEFAULT '',
    updated_at  INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO global_context (id, user_name, rules, updated_at)
VALUES (1, '', '', 0);
"""


async def init_db() -> None:
    import os
    os.makedirs(DB_PATH.rsplit("/", 1)[0], exist_ok=True)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executescript(_CREATE_SQL)
        await db.commit()


async def get_db() -> aiosqlite.Connection:
    db = await aiosqlite.connect(DB_PATH)
    db.row_factory = aiosqlite.Row
    return db
