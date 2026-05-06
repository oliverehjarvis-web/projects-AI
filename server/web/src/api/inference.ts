import { apiFetch } from "./client";
import { parseSseStream } from "./sse";
import type { Message } from "./sync";

export interface GenerateOptions {
  systemPrompt: string;
  messages: Message[];
  model: string;
  userName?: string;
  globalRules?: string;
  /** Forwarded to Ollama as num_ctx — the project's per-conversation context window. */
  numCtx?: number;
  /** Disable the server-side reasoning preamble for short-form requests like Refine. */
  applyDefaultPreamble?: boolean;
  /** Cap on output tokens for the model. */
  maxTokens?: number;
}

export async function streamGenerate(
  opts: GenerateOptions,
  onToken: (token: string) => void,
  onDone: () => void,
  signal?: AbortSignal,
): Promise<void> {
  const body = {
    system_prompt: opts.systemPrompt,
    messages: opts.messages.map((m) => ({ role: m.role, content: m.content })),
    config: {
      model: opts.model,
      ...(opts.numCtx ? { num_ctx: opts.numCtx } : {}),
      ...(opts.applyDefaultPreamble === false ? { apply_default_preamble: false } : {}),
      ...(opts.maxTokens ? { max_tokens: opts.maxTokens } : {}),
    },
    user_name: opts.userName ?? "",
    global_rules: opts.globalRules ?? "",
  };

  const response = await apiFetch("/v1/generate", {
    method: "POST",
    body: JSON.stringify(body),
    headers: { Accept: "text/event-stream" },
    signal,
  });

  for await (const payload of parseSseStream(response)) {
    if (payload === "[DONE]") {
      onDone();
      return;
    }
    try {
      const obj = JSON.parse(payload) as { token?: string; error?: string };
      if (obj.error) throw new Error(obj.error);
      if (obj.token) onToken(obj.token);
    } catch (e) {
      if (e instanceof Error && e.message) throw e;
    }
  }
  onDone();
}
