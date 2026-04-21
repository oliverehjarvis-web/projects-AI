import { apiFetch } from "./client";
import type { Message } from "./sync";

export async function streamGenerate(
  systemPrompt: string,
  messages: Message[],
  model: string,
  onToken: (token: string) => void,
  onDone: () => void,
  signal?: AbortSignal
): Promise<void> {
  const body = {
    system_prompt: systemPrompt,
    messages: messages.map((m) => ({ role: m.role, content: m.content })),
    config: { model },
  };

  const response = await apiFetch("/v1/generate", {
    method: "POST",
    body: JSON.stringify(body),
    headers: { Accept: "text/event-stream" },
    signal,
  });

  if (!response.body) throw new Error("No response body");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.startsWith("data: ")) continue;
      const payload = line.slice(6);
      if (payload === "[DONE]") {
        onDone();
        return;
      }
      try {
        const { token } = JSON.parse(payload) as { token: string };
        onToken(token);
      } catch {
        // ignore malformed lines
      }
    }
  }
  onDone();
}
