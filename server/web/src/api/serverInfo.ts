import { apiJson } from "./client";

export interface ServerInfo {
  ram_total_gb: number;
  ram_available_gb: number;
  model_params_b?: number | null;
  kv_per_token_kb?: number;
}

export function fetchServerInfo(model?: string): Promise<ServerInfo> {
  const q = model ? `?model=${encodeURIComponent(model)}` : "";
  return apiJson<ServerInfo>(`/v1/server_info${q}`);
}

const PICKER_STEPS = [2048, 4096, 8192, 16384, 32768, 65536, 131072];

/**
 * Picks a context window the NAS can actually carry.
 *
 * Math: weights ≈ 0.6 GB per billion params (Q4), reserve 2 GB free, 1 GB activations,
 * remainder → KV cache at the model's tabulated KB/token. Snap to the nearest picker step
 * that fits.
 */
export function recommendContextLength(info: ServerInfo): { tokens: number; rationale: string } {
  const totalGb = info.ram_total_gb || 0;
  const paramsB = info.model_params_b ?? 4;
  const kvPerTokenKb = info.kv_per_token_kb ?? 80;
  const weightsGb = paramsB * 0.6;
  const reservedGb = 3; // 2 GB free for OS + 1 GB activations/output
  const kvBudgetGb = Math.max(0, totalGb - weightsGb - reservedGb);
  const tokensCanFit = (kvBudgetGb * 1024 * 1024) / kvPerTokenKb;
  const fits = PICKER_STEPS.filter((t) => t <= tokensCanFit);
  const tokens = fits.length ? fits[fits.length - 1] : PICKER_STEPS[0];
  const rationale =
    `Server has ${totalGb.toFixed(1)} GB RAM; ${weightsGb.toFixed(1)} GB for the ` +
    `${paramsB}B weights leaves ~${kvBudgetGb.toFixed(1)} GB for KV cache.`;
  return { tokens, rationale };
}

export { PICKER_STEPS };
