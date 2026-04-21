import uuid
import time
from typing import Any
from fastapi import APIRouter, Depends
from pydantic import BaseModel
import aiosqlite
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


# ── Helpers ──────────────────────────────────────────────────────────────────

def _cutoff(since: int) -> int:
    return int(time.time() * 1000) - _THIRTY_DAYS_MS if since == 0 else 0


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


async def _upsert_project(db: aiosqlite.Connection, item: ProjectItem) -> str:
    rid = item.remote_id or str(uuid.uuid4())
    await db.execute(
        """INSERT INTO projects
           (remote_id,name,description,manual_context,accumulated_memory,pinned_memories,
            preferred_backend,memory_token_limit,context_length,is_secret,created_at,updated_at,deleted_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(remote_id) DO UPDATE SET
             name=excluded.name, description=excluded.description,
             manual_context=excluded.manual_context, accumulated_memory=excluded.accumulated_memory,
             pinned_memories=excluded.pinned_memories, preferred_backend=excluded.preferred_backend,
             memory_token_limit=excluded.memory_token_limit, context_length=excluded.context_length,
             is_secret=excluded.is_secret, updated_at=excluded.updated_at, deleted_at=excluded.deleted_at
           WHERE excluded.updated_at >= projects.updated_at""",
        (rid, item.name, item.description, item.manual_context, item.accumulated_memory,
         item.pinned_memories, item.preferred_backend, item.memory_token_limit,
         item.context_length, int(item.is_secret), item.created_at, item.updated_at, item.deleted_at),
    )
    return rid


async def _upsert_chat(db: aiosqlite.Connection, item: ChatItem) -> str:
    rid = item.remote_id or str(uuid.uuid4())
    await db.execute(
        """INSERT INTO chats
           (remote_id,project_remote_id,title,web_search_enabled,created_at,updated_at,deleted_at)
           VALUES (?,?,?,?,?,?,?)
           ON CONFLICT(remote_id) DO UPDATE SET
             title=excluded.title, web_search_enabled=excluded.web_search_enabled,
             updated_at=excluded.updated_at, deleted_at=excluded.deleted_at
           WHERE excluded.updated_at >= chats.updated_at""",
        (rid, item.project_remote_id, item.title, int(item.web_search_enabled),
         item.created_at, item.updated_at, item.deleted_at),
    )
    return rid


async def _upsert_message(db: aiosqlite.Connection, item: MessageItem) -> str:
    rid = item.remote_id or str(uuid.uuid4())
    await db.execute(
        """INSERT INTO messages
           (remote_id,chat_remote_id,role,content,token_count,attachment_paths,created_at,updated_at,deleted_at)
           VALUES (?,?,?,?,?,?,?,?,?)
           ON CONFLICT(remote_id) DO UPDATE SET
             content=excluded.content, token_count=excluded.token_count,
             updated_at=excluded.updated_at, deleted_at=excluded.deleted_at
           WHERE excluded.updated_at >= messages.updated_at""",
        (rid, item.chat_remote_id, item.role, item.content, item.token_count,
         item.attachment_paths, item.created_at, item.updated_at, item.deleted_at),
    )
    return rid


async def _upsert_quick_action(db: aiosqlite.Connection, item: QuickActionItem) -> str:
    rid = item.remote_id or str(uuid.uuid4())
    await db.execute(
        """INSERT INTO quick_actions
           (remote_id,project_remote_id,name,prompt_template,sort_order,created_at,updated_at,deleted_at)
           VALUES (?,?,?,?,?,?,?,?)
           ON CONFLICT(remote_id) DO UPDATE SET
             name=excluded.name, prompt_template=excluded.prompt_template,
             sort_order=excluded.sort_order, updated_at=excluded.updated_at, deleted_at=excluded.deleted_at
           WHERE excluded.updated_at >= quick_actions.updated_at""",
        (rid, item.project_remote_id, item.name, item.prompt_template,
         item.sort_order, item.created_at, item.updated_at, item.deleted_at),
    )
    return rid


# ── Full pull ─────────────────────────────────────────────────────────────────

@router.get("/v1/sync/full")
async def sync_full(since: int = 0, _: None = Depends(require_auth)):
    db = await get_db()
    try:
        projects = await _fetch_rows(db, "projects", since)
        chats = await _fetch_rows(db, "chats", since)
        messages = await _fetch_rows(db, "messages", since)
        quick_actions = await _fetch_rows(db, "quick_actions", since)
    finally:
        await db.close()
    return {"projects": projects, "chats": chats, "messages": messages, "quick_actions": quick_actions}


# ── Projects ──────────────────────────────────────────────────────────────────

@router.get("/v1/sync/projects")
async def get_projects(since: int = 0, _: None = Depends(require_auth)):
    db = await get_db()
    try:
        rows = await _fetch_rows(db, "projects", since)
    finally:
        await db.close()
    return rows


@router.put("/v1/sync/projects")
async def put_projects(body: PushBody, _: None = Depends(require_auth)):
    items = [ProjectItem(**i) for i in body.items]
    db = await get_db()
    try:
        assigned = [await _upsert_project(db, item) for item in items]
        await db.commit()
    finally:
        await db.close()
    return {"accepted": len(assigned), "remote_ids": assigned}


# ── Chats ─────────────────────────────────────────────────────────────────────

@router.get("/v1/sync/chats")
async def get_chats(since: int = 0, project_remote_id: str | None = None, _: None = Depends(require_auth)):
    db = await get_db()
    try:
        filters = {"project_remote_id": project_remote_id} if project_remote_id else {}
        rows = await _fetch_rows(db, "chats", since, **filters)
    finally:
        await db.close()
    return rows


@router.put("/v1/sync/chats")
async def put_chats(body: PushBody, _: None = Depends(require_auth)):
    items = [ChatItem(**i) for i in body.items]
    db = await get_db()
    try:
        assigned = [await _upsert_chat(db, item) for item in items]
        await db.commit()
    finally:
        await db.close()
    return {"accepted": len(assigned), "remote_ids": assigned}


# ── Messages ──────────────────────────────────────────────────────────────────

@router.get("/v1/sync/messages")
async def get_messages(since: int = 0, chat_remote_id: str | None = None, _: None = Depends(require_auth)):
    db = await get_db()
    try:
        filters = {"chat_remote_id": chat_remote_id} if chat_remote_id else {}
        rows = await _fetch_rows(db, "messages", since, **filters)
    finally:
        await db.close()
    return rows


@router.put("/v1/sync/messages")
async def put_messages(body: PushBody, _: None = Depends(require_auth)):
    items = [MessageItem(**i) for i in body.items]
    db = await get_db()
    try:
        assigned = [await _upsert_message(db, item) for item in items]
        await db.commit()
    finally:
        await db.close()
    return {"accepted": len(assigned), "remote_ids": assigned}


# ── Quick actions ─────────────────────────────────────────────────────────────

@router.get("/v1/sync/quick_actions")
async def get_quick_actions(since: int = 0, project_remote_id: str | None = None, _: None = Depends(require_auth)):
    db = await get_db()
    try:
        filters = {"project_remote_id": project_remote_id} if project_remote_id else {}
        rows = await _fetch_rows(db, "quick_actions", since, **filters)
    finally:
        await db.close()
    return rows


@router.put("/v1/sync/quick_actions")
async def put_quick_actions(body: PushBody, _: None = Depends(require_auth)):
    items = [QuickActionItem(**i) for i in body.items]
    db = await get_db()
    try:
        assigned = [await _upsert_quick_action(db, item) for item in items]
        await db.commit()
    finally:
        await db.close()
    return {"accepted": len(assigned), "remote_ids": assigned}
