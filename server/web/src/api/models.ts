import { apiFetch } from "./client";

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
}

export async function fetchModels(): Promise<ModelsResponse> {
  const r = await apiFetch("/v1/models");
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

export async function deleteModel(modelId: string): Promise<void> {
  await apiFetch(`/v1/models/${encodeURIComponent(modelId)}`, { method: "DELETE" });
}

export async function pullModel(
  modelId: string,
  onProgress: (status: string, pct: number | null) => void,
  onDone: () => void
): Promise<void> {
  const r = await apiFetch(`/v1/models/pull/${encodeURIComponent(modelId)}`, {
    method: "POST",
    headers: { Accept: "text/event-stream" },
  });
  if (!r.body) return;
  const reader = r.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    const lines = buf.split("\n");
    buf = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.startsWith("data: ")) continue;
      try {
        const { status, progress } = JSON.parse(line.slice(6)) as {
          status: string;
          progress: number | null;
        };
        if (status === "done") { onDone(); return; }
        onProgress(status, progress);
      } catch { /* skip */ }
    }
  }
  onDone();
}
