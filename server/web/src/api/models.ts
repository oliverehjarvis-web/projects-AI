import { apiFetch, apiJson } from "./client";
import { parseSseStream } from "./sse";

export interface CatalogueModel {
  id: string;
  family: string;
  label: string;
  size_gb: number;
  notes: string;
  installed: boolean;
}

export interface ModelsResponse {
  installed: { id: string; size_gb: number }[];
  catalogue: CatalogueModel[];
  /** False if the server couldn't reach Ollama; `installed` will be empty in that case. */
  ollama_reachable?: boolean;
}

export function fetchModels(): Promise<ModelsResponse> {
  return apiJson<ModelsResponse>("/v1/models");
}

export async function deleteModel(modelId: string): Promise<void> {
  await apiFetch(`/v1/models/${encodeURIComponent(modelId)}`, { method: "DELETE" });
}

export async function pullModel(
  modelId: string,
  onProgress: (status: string, pct: number | null) => void,
  onDone: () => void,
  onError: (msg: string) => void,
): Promise<void> {
  const r = await apiFetch(`/v1/models/pull/${encodeURIComponent(modelId)}`, {
    method: "POST",
    headers: { Accept: "text/event-stream" },
  });
  if (!r.ok) {
    onError(`Server error ${r.status}`);
    return;
  }
  for await (const payload of parseSseStream(r)) {
    try {
      const evt = JSON.parse(payload) as {
        status: string;
        progress: number | null;
        error?: string;
      };
      if (evt.status === "done") {
        onDone();
        return;
      }
      if (evt.status === "error") {
        onError(evt.error ?? "Pull failed");
        return;
      }
      onProgress(evt.status, evt.progress);
    } catch {
      // skip malformed lines
    }
  }
  onDone();
}
