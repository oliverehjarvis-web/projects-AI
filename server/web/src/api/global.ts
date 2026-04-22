import { apiFetch } from "./client";

export interface GlobalContext {
  user_name: string;
  rules: string;
  updated_at: number;
}

export async function fetchGlobalContext(): Promise<GlobalContext> {
  const r = await apiFetch("/v1/global_context");
  return r.json();
}

export async function saveGlobalContext(ctx: Pick<GlobalContext, "user_name" | "rules">): Promise<GlobalContext> {
  const r = await apiFetch("/v1/global_context", {
    method: "PUT",
    body: JSON.stringify(ctx),
  });
  return r.json();
}
