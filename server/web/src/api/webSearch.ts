import { apiFetch } from "./client";

export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
}

/**
 * Hits the server's /v1/web_search proxy, which then talks to the user's SearXNG instance. The
 * Android app calls SearXNG directly; the web app can't because of CORS, so we route through the
 * server. Same JSON shape on both surfaces.
 */
export async function searxngSearch(
  searxngUrl: string,
  query: string,
  count = 5,
): Promise<SearchResult[]> {
  const params = new URLSearchParams({
    url: searxngUrl,
    q: query,
    count: String(count),
  });
  const r = await apiFetch(`/v1/web_search?${params.toString()}`);
  if (!r.ok) {
    const detail = await r.text().catch(() => "");
    throw new Error(`Search failed (${r.status}): ${detail.slice(0, 200)}`);
  }
  const data = (await r.json()) as { results?: SearchResult[] };
  return data.results ?? [];
}

export async function fetchPage(url: string, maxChars = 4000): Promise<string> {
  const r = await apiFetch("/v1/fetch_page", {
    method: "POST",
    body: JSON.stringify({ url, max_chars: maxChars }),
  });
  if (!r.ok) {
    const detail = await r.text().catch(() => "");
    throw new Error(`Fetch failed (${r.status}): ${detail.slice(0, 200)}`);
  }
  const data = (await r.json()) as { text: string };
  return data.text;
}

export const AUTO_FETCH_INSTRUCTIONS = `You have access to a web search tool. When the user's \
question needs current or specific information you don't already know (news, dates, stats, \
recent events, specific facts), respond with exactly:

<search>your concise search query</search>

and nothing else on that turn. You will receive search results AND the full text of the top \
pages, then give your final answer using them.

If you can answer from what you already know, answer directly — do not use <search> tags in \
normal answers. Only use the tag when you would otherwise need to look something up.`;

export function formatResultsForPrompt(query: string, results: SearchResult[]): string {
  if (results.length === 0) {
    return `Search for "${query}" returned no results.`;
  }
  const lines: string[] = [`Search results for "${query}":`];
  results.forEach((r, idx) => {
    lines.push("");
    lines.push(`[${idx + 1}] ${r.title}`);
    lines.push(`    ${r.url}`);
    if (r.snippet) lines.push(`    ${r.snippet}`);
  });
  return lines.join("\n").trim();
}

export const SEARCH_TAG_RE = /<search>([\s\S]*?)<\/search>/;

export function stripToolTags(text: string): string {
  const idx = text.indexOf("<search>");
  return idx < 0 ? text : text.slice(0, idx);
}
