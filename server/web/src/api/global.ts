import { apiJson } from "./client";

export interface GlobalContext {
  user_name: string;
  rules: string;
  updated_at: number;
}

export function fetchGlobalContext(): Promise<GlobalContext> {
  return apiJson<GlobalContext>("/v1/global_context");
}

export function saveGlobalContext(
  ctx: Pick<GlobalContext, "user_name" | "rules">,
): Promise<GlobalContext> {
  return apiJson<GlobalContext>("/v1/global_context", {
    method: "PUT",
    body: JSON.stringify(ctx),
  });
}
