"""
Server-side proxy for SearXNG search and arbitrary page fetches.

The web client lives in the user's browser, but most SearXNG instances don't set CORS headers,
so the browser can't call them directly. The Android app makes these calls itself (no CORS) —
the web app routes them through here. The user passes the SearXNG URL as a query param so the
server doesn't need it baked in (matches the Android "URL is per-device" model).

Request shape:
- GET /v1/web_search?url=<searxng-base>&q=<query>&count=<n=5>
- POST /v1/fetch_page  body: {"url": "https://..."}

Both endpoints return JSON. Both require auth.
"""

from __future__ import annotations

import re
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from auth import require_auth


router = APIRouter()

_FETCH_TIMEOUT_S = 15.0
_MAX_PAGE_CHARS = 4000


def _validate_http_url(url: str) -> None:
    """Reject schemes other than http/https and obviously-malformed URLs.

    Without this anyone with an API token could ask the server to read file://, gopher://, or
    internal-only schemes. We only need plain web URLs for both the SearXNG endpoint and page
    fetching, so it's safe to be strict.
    """
    if not url:
        raise HTTPException(status_code=400, detail="url is required")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise HTTPException(status_code=400, detail="Only http(s) URLs are allowed.")
    if not parsed.netloc:
        raise HTTPException(status_code=400, detail="Malformed URL.")


@router.get("/v1/web_search")
async def web_search(
    url: str = Query(..., description="SearXNG instance base URL (no trailing /search)."),
    q: str = Query(..., description="Search query."),
    count: int = Query(5, ge=1, le=20),
    _: None = Depends(require_auth),
) -> dict[str, Any]:
    base = url.rstrip("/")
    _validate_http_url(base)
    target = f"{base}/search"
    params = {"q": q, "format": "json", "safesearch": "1"}
    headers = {
        "Accept": "application/json",
        "User-Agent": "ProjectsAI/1.0 (web)",
    }
    try:
        async with httpx.AsyncClient(timeout=_FETCH_TIMEOUT_S) as client:
            r = await client.get(target, params=params, headers=headers)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"SearXNG unreachable: {exc}") from exc
    if r.status_code != 200:
        snippet = r.text[:200] if r.text else ""
        raise HTTPException(
            status_code=502,
            detail=f"SearXNG returned HTTP {r.status_code}: {snippet}",
        )
    try:
        data = r.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail=f"SearXNG returned non-JSON: {exc}") from exc
    raw = data.get("results") or []
    out: list[dict[str, str]] = []
    for item in raw:
        if len(out) >= count:
            break
        item_url = (item.get("url") or "").strip()
        if not item_url:
            continue
        out.append({
            "title": (item.get("title") or item_url).strip(),
            "url": item_url,
            "snippet": (item.get("content") or "").strip(),
        })
    return {"query": q, "results": out}


class FetchPageBody(BaseModel):
    url: str
    max_chars: int = _MAX_PAGE_CHARS


@router.post("/v1/fetch_page")
async def fetch_page(body: FetchPageBody, _: None = Depends(require_auth)) -> dict[str, Any]:
    _validate_http_url(body.url)
    headers = {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent": "Mozilla/5.0 (compatible; ProjectsAI/1.0; +https://github.com/oliverehjarvis-web/projects-AI)",
    }
    try:
        async with httpx.AsyncClient(
            timeout=_FETCH_TIMEOUT_S, follow_redirects=True, max_redirects=4
        ) as client:
            r = await client.get(body.url, headers=headers)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Fetch failed: {exc}") from exc
    if r.status_code != 200:
        raise HTTPException(
            status_code=502,
            detail=f"Page returned HTTP {r.status_code}",
        )
    content_type = r.headers.get("content-type", "")
    body_text = r.text or ""
    if "html" in content_type.lower():
        text = _extract_text(body_text)
    else:
        text = body_text
    text = _collapse_whitespace(text)
    if len(text) > body.max_chars:
        text = text[: body.max_chars] + "…"
    return {"url": str(r.url), "text": text}


# ── HTML → plain-text extraction ─────────────────────────────────────────────

class _TextExtractor(HTMLParser):
    """Tiny visible-text extractor that drops scripts, styles, and obviously-noise tags.

    Doesn't try to be smart about main-content detection; the model handles noise gracefully and
    a simpler extractor is one less moving part to debug. ~50 lines beats pulling in beautifulsoup4
    just for this.
    """

    _SKIP_TAGS = {"script", "style", "noscript", "svg", "header", "footer", "nav", "form"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._buf: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self._SKIP_TAGS:
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in self._SKIP_TAGS and self._skip_depth > 0:
            self._skip_depth -= 1
        # Add a paragraph break for block-ish elements so collapsing whitespace doesn't smush
        # everything onto one line.
        if tag in {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5"}:
            self._buf.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            self._buf.append(data)

    @property
    def text(self) -> str:
        return "".join(self._buf)


def _extract_text(html: str) -> str:
    parser = _TextExtractor()
    try:
        parser.feed(html)
    except Exception:
        return html
    return parser.text


_WHITESPACE_RE = re.compile(r"[ \t]+")
_BLANKLINE_RE = re.compile(r"\n{3,}")


def _collapse_whitespace(text: str) -> str:
    text = _WHITESPACE_RE.sub(" ", text)
    text = _BLANKLINE_RE.sub("\n\n", text)
    lines = [line.strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)
