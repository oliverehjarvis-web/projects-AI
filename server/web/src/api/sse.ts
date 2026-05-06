/**
 * Async-iterates `data: ...` payloads from a fetch Response carrying a Server-Sent Events
 * stream. Yields the raw payload text (without the `data: ` prefix); callers parse it as JSON
 * — the surrounding format is endpoint-specific.
 *
 * Returns when the stream closes naturally; callers stop early by breaking out of the loop.
 */
export async function* parseSseStream(response: Response): AsyncGenerator<string> {
  if (!response.body) throw new Error("No response body");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (line.startsWith("data: ")) yield line.slice(6);
      }
    }
  } finally {
    reader.releaseLock();
  }
}
