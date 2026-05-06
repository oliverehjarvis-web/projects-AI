import time
import uuid
from contextlib import asynccontextmanager
from typing import Any

import aiosqlite
from fastapi import APIRouter, Depends
from pydantic import BaseModel

from auth import require_auth
from db import get_db

router = APIRouter()

_THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000


# ── Pydantic models ──────────────────────────────────────────────────────────

class ProjectItem(BaseModel):
    remote_id: str | None = None
    name: str
    description: str = ""
    manual_context: str = ""
    accumulated_memory: str = ""
    pinned_memories: str = ""
    preferred_backend: str = "LOCAL"
    memory_token_limit: int = 4000
    context_length: int = 16384
    is_secret: bool = False
    created_at: int
    updated_at: int
    deleted_at: int | None = None


class ChatItem(BaseModel):
    remote_id: str | None = None
    project_remote_id: str
    title: str = "New Chat"
    web_search_enabled: bool = False
    created_at: int
    updated_at: int
    deleted_at: int | None = None


class MessageItem(BaseModel):
    remote_id: str | None = None
    chat_remote_id: str
    role: str
    content: str
    token_count: int = 0
    attachment_paths: str = ""
    created_at: int
    updated_at: int
    deleted_at: int | None = None


class QuickActionItem(BaseModel):
    remote_id: str | None = None
    project_remote_id: str
    name: str
    prompt_template: str
    sort_order: int = 0
    created_at: int
    updated_at: int
    deleted_at: int | None = None


class PushBody(BaseModel):
    items: list[Any]


# ── Connection lifecycle ─────────────────────────────────────────────────────

@asynccontextmanager
async def _sync_db():
    """Open a sqlite connection and guarantee close() — replaces the try/finally pattern
    that was hand-rolled in every sync endpoint."""
    db = await get_db()
    try:
        yield db
    finally:
        await db.close()


# ── Read helpers ─────────────────────────────────────────────────────────────

async def _fetch_rows(db: aiosqlite.Connection, table: str, since: int, **filters) -> list[dict]:
    cutoff = int(time.time() * 1000) - _THIRTY_DAYS_MS
    where = ["updated_at > ?", "(deleted_at IS NULL OR deleted_at > ?)"]
    params: list[Any] = [since, cutoff]
    for col, val in filters.items():
        where.append(f"{col} = ?")
        params.append(val)
    sql = f"SELECT * FROM {table} WHERE {' AND '.join(where)}"
    async with db.execute(sql, params) as cur:
        rows = await cur.fetchall()
    return [dict(r) for r in rows]


# ── Upsert ───────────────────────────────────────────────────────────────────

async def _upsert(
    db: aiosqlite.Connection,
    table: str,
    values: dict[str, Any],
    update_cols: list[str],
) -> str:
    """
    Generic last-write-wins upsert. Caller passes:
      - `values`: column→value (must include remote_id, created_at, updated_at; remote_id may be None)
      - `update_cols`: which columns get refreshed on ON CONFLICT (typically all writeable
        columns minus `created_at` and `remote_id`).

    Returns the remote_id (newly minted if `values["remote_id"]` was None).
    """
    rid = values.get("remote_id") or str(uuid.uuid4())
    values = {**values, "remote_id": rid}
    cols = list(values.keys())
    placeholders = ",".join(["?"] * len(cols))
    update_clause = ", ".join(f"{c}=excluded.{c}" for c in update_cols)
    sql = (
        f"INSERT INTO {table} ({','.join(cols)}) VALUES ({placeholders}) "
        f"ON CONFLICT(remote_id) DO UPDATE SET {update_clause} "
        f"WHERE excluded.updated_at >= {table}.updated_at"
    )
    await db.execute(sql, [values[c] for c in cols])
    return rid


# Tombstones don't cascade through the FK (that only fires on physical DELETE), so we
# propagate deleted_at manually. Any chat under the project (or message under the chat)
# that isn't already tombstoned earlier than this call gets marked.
async def _cascade_delete_chats(db: aiosqlite.Connection, project_remote_id: str, deleted_at: int) -> None:
    await db.execute(
        """UPDATE chats
              SET deleted_at = ?, updated_at = ?
            WHERE project_remote_id = ?
              AND (deleted_at IS NULL OR deleted_at > ?)""",
        (deleted_at, deleted_at, project_remote_id, deleted_at),
    )
    async with db.execute(
        "SELECT remote_id FROM chats WHERE project_remote_id = ?",
        (project_remote_id,),
    ) as cur:
        chat_ids = [row[0] for row in await cur.fetchall()]
    for cid in chat_ids:
        await _cascade_delete_messages(db, cid, deleted_at)


async def _cascade_delete_messages(db: aiosqlite.Connection, chat_remote_id: str, deleted_at: int) -> None:
    await db.execute(
        """UPDATE messages
              SET deleted_at = ?, updated_at = ?
            WHERE chat_remote_id = ?
              AND (deleted_at IS NULL OR deleted_at > ?)""",
        (deleted_at, deleted_at, chat_remote_id, deleted_at),
    )


# ── Per-entity upsert wrappers ───────────────────────────────────────────────

_PROJECT_UPDATE_COLS = [
    "name", "description", "manual_context", "accumulated_memory",
    "pinned_memories", "preferred_backend", "memory_token_limit",
    "context_length", "is_secret", "updated_at", "deleted_at",
]
_CHAT_UPDATE_COLS = ["title", "web_search_enabled", "updated_at", "deleted_at"]
_MESSAGE_UPDATE_COLS = ["content", "token_count", "updated_at", "deleted_at"]
_QUICK_ACTION_UPDATE_COLS = [
    "name", "prompt_template", "sort_order", "updated_at", "deleted_at",
]


def _bool_to_int(values: dict[str, Any], *fields: str) -> dict[str, Any]:
    """SQLite has no bool — coerce the named fields to 0/1 so the row matches the column type."""
    for f in fields:
        values[f] = int(values[f])
    return values


async def _upsert_project(db: aiosqlite.Connection, item: ProjectItem) -> str:
    rid = await _upsert(db, "projects",
                        _bool_to_int(item.model_dump(), "is_secret"),
                        _PROJECT_UPDATE_COLS)
    if item.deleted_at is not None:
        await _cascade_delete_chats(db, rid, item.deleted_at)
    return rid


async def _upsert_chat(db: aiosqlite.Connection, item: ChatItem) -> str:
    rid = await _upsert(db, "chats",
                        _bool_to_int(item.model_dump(), "web_search_enabled"),
                        _CHAT_UPDATE_COLS)
    if item.deleted_at is not None:
        await _cascade_delete_messages(db, rid, item.deleted_at)
    return rid


async def _upsert_message(db: aiosqlite.Connection, item: MessageItem) -> str:
    return await _upsert(db, "messages", item.model_dump(), _MESSAGE_UPDATE_COLS)


async def _upsert_quick_action(db: aiosqlite.Connection, item: QuickActionItem) -> str:
    return await _upsert(db, "quick_actions", item.model_dump(), _QUICK_ACTION_UPDATE_COLS)


# ── Endpoint helpers ─────────────────────────────────────────────────────────

async def _list_table(table: str, since: int, **filters) -> list[dict]:
    """Connection-scoped wrapper around _fetch_rows used by every GET endpoint."""
    async with _sync_db() as db:
        return await _fetch_rows(db, table, since, **filters)


async def _push_items(raw_items: list[Any], item_cls, upsert) -> dict:
    """Validate, upsert, commit, and shape the response — used by every PUT endpoint."""
    items = [item_cls(**i) for i in raw_items]
    async with _sync_db() as db:
        assigned = [await upsert(db, item) for item in items]
        await db.commit()
    return {"accepted": len(assigned), "remote_ids": assigned}


# ── Full pull ────────────────────────────────────────────────────────────────

@router.get("/v1/sync/full")
async def sync_full(since: int = 0, _: None = Depends(require_auth)):
    async with _sync_db() as db:
        return {
            "projects": await _fetch_rows(db, "projects", since),
            "chats": await _fetch_rows(db, "chats", since),
            "messages": await _fetch_rows(db, "messages", since),
            "quick_actions": await _fetch_rows(db, "quick_actions", since),
        }


# ── Per-entity endpoints ─────────────────────────────────────────────────────

@router.get("/v1/sync/projects")
async def get_projects(since: int = 0, _: None = Depends(require_auth)):
    return await _list_table("projects", since)


@router.put("/v1/sync/projects")
async def put_projects(body: PushBody, _: None = Depends(require_auth)):
    return await _push_items(body.items, ProjectItem, _upsert_project)


@router.get("/v1/sync/chats")
async def get_chats(
    since: int = 0,
    project_remote_id: str | None = None,
    _: None = Depends(require_auth),
):
    filters = {"project_remote_id": project_remote_id} if project_remote_id else {}
    return await _list_table("chats", since, **filters)


@router.put("/v1/sync/chats")
async def put_chats(body: PushBody, _: None = Depends(require_auth)):
    return await _push_items(body.items, ChatItem, _upsert_chat)


@router.get("/v1/sync/messages")
async def get_messages(
    since: int = 0,
    chat_remote_id: str | None = None,
    _: None = Depends(require_auth),
):
    filters = {"chat_remote_id": chat_remote_id} if chat_remote_id else {}
    return await _list_table("messages", since, **filters)


@router.put("/v1/sync/messages")
async def put_messages(body: PushBody, _: None = Depends(require_auth)):
    return await _push_items(body.items, MessageItem, _upsert_message)


@router.get("/v1/sync/quick_actions")
async def get_quick_actions(
    since: int = 0,
    project_remote_id: str | None = None,
    _: None = Depends(require_auth),
):
    filters = {"project_remote_id": project_remote_id} if project_remote_id else {}
    return await _list_table("quick_actions", since, **filters)


@router.put("/v1/sync/quick_actions")
async def put_quick_actions(body: PushBody, _: None = Depends(require_auth)):
    return await _push_items(body.items, QuickActionItem, _upsert_quick_action)


# ── Global context ───────────────────────────────────────────────────────────

class GlobalContext(BaseModel):
    user_name: str = ""
    rules: str = ""
    updated_at: int = 0


@router.get("/v1/global_context")
async def get_global_context(_: None = Depends(require_auth)) -> GlobalContext:
    async with _sync_db() as db:
        async with db.execute(
            "SELECT user_name, rules, updated_at FROM global_context WHERE id = 1"
        ) as cur:
            row = await cur.fetchone()
    if row is None:
        return GlobalContext()
    return GlobalContext(user_name=row["user_name"], rules=row["rules"], updated_at=row["updated_at"])


@router.put("/v1/global_context")
async def put_global_context(body: GlobalContext, _: None = Depends(require_auth)) -> GlobalContext:
    now = int(time.time() * 1000)
    async with _sync_db() as db:
        # Last-write-wins on `updated_at`: an older write (stale tab) loses to a
        # newer one. Matches how the rest of the sync tables resolve conflicts.
        await db.execute(
            """UPDATE global_context
                  SET user_name = ?, rules = ?, updated_at = ?
                WHERE id = 1 AND ? >= updated_at""",
            (body.user_name, body.rules, now, now),
        )
        await db.commit()
        async with db.execute(
            "SELECT user_name, rules, updated_at FROM global_context WHERE id = 1"
        ) as cur:
            row = await cur.fetchone()
    return GlobalContext(user_name=row["user_name"], rules=row["rules"], updated_at=row["updated_at"])
