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
