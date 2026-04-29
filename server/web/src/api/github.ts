/**
 * Direct calls to api.github.com using the user's PAT (stored in localStorage). The server is
 * never touched. CORS works for these endpoints when an `Authorization: Bearer <PAT>` header
 * is set, so the browser can hit GitHub directly.
 */

const GITHUB_BASE = "https://api.github.com";

export interface GhRepo {
  owner: string;
  name: string;
  default_branch: string;
  size_kb: number;
}

export interface GhTreeEntry {
  path: string;
  type: "blob" | "tree";
  size: number;
}

export interface GhTreeResult {
  ref: string;
  truncated: boolean;
  entries: GhTreeEntry[];
}

export interface GhFile {
  path: string;
  size_bytes: number;
  text: string;
}

function authHeaders(pat: string): HeadersInit {
  return {
    Authorization: `Bearer ${pat}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

async function ghFetch(path: string, pat: string): Promise<Response> {
  const r = await fetch(`${GITHUB_BASE}${path}`, { headers: authHeaders(pat) });
  if (!r.ok) {
    const detail = await r.text().catch(() => "");
    throw new Error(`GitHub ${r.status}: ${detail.slice(0, 200) || r.statusText}`);
  }
  return r;
}

export async function whoami(pat: string): Promise<string> {
  const r = await ghFetch("/user", pat);
  const data = await r.json();
  return data.login as string;
}

export async function listRepos(pat: string): Promise<GhRepo[]> {
  // sort=updated brings recently-touched repos to the top, which is almost always what the user
  // means when they go to "browse my repo".
  const r = await ghFetch("/user/repos?per_page=100&sort=updated", pat);
  const data = (await r.json()) as Array<{
    owner: { login: string };
    name: string;
    default_branch: string;
    size: number;
  }>;
  return data.map((x) => ({
    owner: x.owner.login,
    name: x.name,
    default_branch: x.default_branch,
    size_kb: x.size,
  }));
}

export async function fetchTree(
  owner: string,
  repo: string,
  ref: string,
  pat: string,
): Promise<GhTreeResult> {
  const r = await ghFetch(
    `/repos/${owner}/${repo}/git/trees/${ref}?recursive=1`,
    pat,
  );
  const data = (await r.json()) as {
    sha: string;
    truncated: boolean;
    tree: Array<{ path: string; type: string; size?: number }>;
  };
  return {
    ref: data.sha,
    truncated: data.truncated,
    entries: data.tree
      .filter((e) => e.type === "blob" || e.type === "tree")
      .map((e) => ({
        path: e.path,
        type: e.type === "tree" ? "tree" : "blob",
        size: e.size ?? 0,
      })),
  };
}

export async function fetchFile(
  owner: string,
  repo: string,
  path: string,
  ref: string,
  pat: string,
): Promise<GhFile> {
  const r = await ghFetch(
    `/repos/${owner}/${repo}/contents/${encodeURIComponent(path).replace(/%2F/g, "/")}?ref=${ref}`,
    pat,
  );
  const data = (await r.json()) as { content: string; encoding: string; size: number; path: string };
  let text = "";
  if (data.encoding === "base64") {
    // GitHub base64 uses standard alphabet but may include line breaks every 60 chars.
    const stripped = data.content.replace(/\n/g, "");
    text = decodeBase64Utf8(stripped);
  } else {
    text = data.content;
  }
  return { path: data.path, size_bytes: data.size, text };
}

function decodeBase64Utf8(b64: string): string {
  // atob gives us a binary string; convert through Uint8Array so multi-byte UTF-8 (emoji, accented
  // characters, etc.) decodes correctly. atob alone produces mojibake for anything outside ASCII.
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder("utf-8").decode(bytes);
}
