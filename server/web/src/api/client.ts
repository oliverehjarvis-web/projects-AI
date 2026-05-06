export function getToken(): string {
  return localStorage.getItem("api_token") ?? "";
}

export function setToken(t: string): void {
  localStorage.setItem("api_token", t);
}

export function getBaseUrl(): string {
  return localStorage.getItem("base_url") ?? "";
}

export function setBaseUrl(u: string): void {
  localStorage.setItem("base_url", u.replace(/\/$/, ""));
}

/** Thrown by [apiJson] on non-2xx responses. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly body: string, readonly path: string) {
    super(`${path} → HTTP ${status}${body ? `: ${body.slice(0, 200)}` : ""}`);
    this.name = "ApiError";
  }
}

/** Low-level fetch — returns the raw Response. Use [apiJson] for typical JSON calls. */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const base = getBaseUrl();
  const url = base ? `${base}${path}` : path;
  return fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${getToken()}`,
      ...(init.headers ?? {}),
    },
  });
}

/**
 * Typical JSON request: validates `response.ok` and parses the body. Throws [ApiError]
 * on non-2xx so callers can `try/catch` and surface a useful message — replaces the
 * previous pattern of `apiFetch(...).then(r => r.json())` which silently masked HTTP
 * failures by passing them through as JSON parse errors.
 */
export async function apiJson<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  const r = await apiFetch(path, init);
  if (!r.ok) {
    let body = "";
    try { body = await r.text(); } catch { /* ignore */ }
    throw new ApiError(r.status, body, path);
  }
  return r.json() as Promise<T>;
}
