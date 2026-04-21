import { useState, useEffect, useCallback } from "react";
import { getBaseUrl, setBaseUrl, getToken, setToken } from "../api/client";
import { useStore } from "../store/useStore";
import { checkHealth } from "../api/sync";
import { fetchModels, pullModel, deleteModel, type CatalogueModel } from "../api/models";

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, maxWidth: 560, overflowY: "auto", flex: 1 },
  title: { fontSize: 22, fontWeight: 700, marginBottom: 24, color: "#fff" },
  section: { marginBottom: 28 },
  sectionTitle: { fontSize: 13, fontWeight: 600, color: "#888", textTransform: "uppercase", letterSpacing: 1, marginBottom: 12 },
  label: { display: "block", fontSize: 13, color: "#888", marginBottom: 6 },
  input: {
    width: "100%", background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none", marginBottom: 16,
    boxSizing: "border-box" as const,
  },
  select: {
    width: "100%", background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none", marginBottom: 16,
    appearance: "none" as const, cursor: "pointer",
  },
  btn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 8,
    padding: "10px 18px", cursor: "pointer", fontWeight: 600, fontSize: 14, marginRight: 8,
  },
  btnSm: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 6,
    padding: "6px 12px", cursor: "pointer", fontWeight: 600, fontSize: 12,
  },
  btnDanger: { background: "#7f1d1d" },
  btnGhost: { background: "transparent", border: "1px solid #2a2a2a", color: "#888" },
  status: { marginTop: 8, fontSize: 13, color: "#4caf50" },
  statusErr: { color: "#f44336" },
  card: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 10,
    padding: "12px 14px", marginBottom: 8,
  },
  cardInstalled: { borderColor: "#1d4ed8" },
  cardRow: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 },
  modelLabel: { fontWeight: 600, fontSize: 14, color: "#fff" },
  modelMeta: { fontSize: 12, color: "#888", marginTop: 2 },
  badge: {
    fontSize: 11, fontWeight: 600, padding: "2px 7px", borderRadius: 99,
    background: "#1d4ed8", color: "#93c5fd",
  },
  progressBar: {
    height: 4, background: "#2a2a2a", borderRadius: 2, marginTop: 8, overflow: "hidden",
  },
  progressFill: { height: "100%", background: "#2563eb", transition: "width 0.3s" },
  familyHeader: { fontSize: 12, fontWeight: 600, color: "#555", marginTop: 16, marginBottom: 6 },
};

interface PullState { status: string; pct: number | null; error?: string }

export default function Settings() {
  const { model, setModel } = useStore();
  const [url, setUrl] = useState(getBaseUrl());
  const [token, setTokenState] = useState(getToken());
  const [connStatus, setConnStatus] = useState<{ ok: boolean; msg: string } | null>(null);
  const [catalogue, setCatalogue] = useState<CatalogueModel[]>([]);
  const [pulling, setPulling] = useState<Record<string, PullState>>({});
  const [loadingModels, setLoadingModels] = useState(false);

  const installedModels = catalogue.filter((m) => m.installed);

  const loadModels = useCallback(async () => {
    setLoadingModels(true);
    try {
      const data = await fetchModels();
      setCatalogue(data.catalogue);
    } catch { /* server not connected yet */ }
    finally { setLoadingModels(false); }
  }, []);

  useEffect(() => { if (getBaseUrl() && getToken()) loadModels(); }, [loadModels]);

  const save = () => {
    setBaseUrl(url);
    setToken(token);
  };

  const test = async () => {
    save();
    try {
      const h = await checkHealth();
      setConnStatus({ ok: true, msg: `Connected — Ollama: ${h.ollama}` });
      loadModels();
    } catch (e) {
      setConnStatus({ ok: false, msg: `Failed: ${String(e)}` });
    }
  };

  const handlePull = async (modelId: string) => {
    setPulling((p) => ({ ...p, [modelId]: { status: "Starting…", pct: null } }));
    const clearAfterDelay = (id: string) =>
      setTimeout(() => setPulling((p) => { const n = { ...p }; delete n[id]; return n; }), 4000);
    try {
      await pullModel(
        modelId,
        (status, pct) => setPulling((p) => ({ ...p, [modelId]: { status, pct } })),
        () => {
          setPulling((p) => { const n = { ...p }; delete n[modelId]; return n; });
          loadModels();
        },
        (error) => {
          setPulling((p) => ({ ...p, [modelId]: { status: "Error", pct: null, error } }));
          clearAfterDelay(modelId);
        }
      );
    } catch (e) {
      setPulling((p) => ({ ...p, [modelId]: { status: "Error", pct: null, error: String(e) } }));
      clearAfterDelay(modelId);
    }
  };

  const handleDelete = async (modelId: string) => {
    await deleteModel(modelId);
    if (model === modelId) setModel(installedModels.find((m) => m.id !== modelId)?.id ?? "");
    loadModels();
  };

  const families = [...new Set(catalogue.map((m) => m.family))];

  return (
    <div style={s.page}>
      <div style={s.title}>Settings</div>

      {/* Connection */}
      <div style={s.section}>
        <div style={s.sectionTitle}>Server connection</div>
        <label style={s.label}>Server URL</label>
        <input style={s.input} value={url} onChange={(e) => setUrl(e.target.value)}
          placeholder="http://100.x.x.x:8765" />
        <label style={s.label}>API Token</label>
        <input style={s.input} type="password" value={token} onChange={(e) => setTokenState(e.target.value)} />
        <button style={s.btn} onClick={test}>Save & Test</button>
        {connStatus && (
          <div style={{ ...s.status, ...(connStatus.ok ? {} : s.statusErr) }}>{connStatus.msg}</div>
        )}
      </div>

      {/* Active model */}
      {installedModels.length > 0 && (
        <div style={s.section}>
          <div style={s.sectionTitle}>Active model</div>
          <select
            style={s.select}
            value={model}
            onChange={(e) => setModel(e.target.value)}
          >
            {installedModels.map((m) => (
              <option key={m.id} value={m.id}>
                {m.label} ({m.size_gb} GB)
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Model library */}
      <div style={s.section}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12 }}>
          <div style={s.sectionTitle}>Model library</div>
          <button style={{ ...s.btnSm, ...s.btnGhost }} onClick={loadModels}>
            {loadingModels ? "Refreshing…" : "Refresh"}
          </button>
        </div>

        {catalogue.length === 0 && (
          <p style={{ color: "#555", fontSize: 14 }}>
            Save & Test your connection above to browse models.
          </p>
        )}

        {families.map((family) => (
          <div key={family}>
            <div style={s.familyHeader}>{family}</div>
            {catalogue.filter((m) => m.family === family).map((m) => {
              const pullState = pulling[m.id];
              return (
                <div key={m.id} style={{ ...s.card, ...(m.installed ? s.cardInstalled : {}) }}>
                  <div style={s.cardRow}>
                    <div style={{ flex: 1 }}>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <span style={s.modelLabel}>{m.label}</span>
                        {m.installed && <span style={s.badge}>Installed</span>}
                        {m.id === model && m.installed && (
                          <span style={{ ...s.badge, background: "#14532d", color: "#86efac" }}>Active</span>
                        )}
                      </div>
                      <div style={s.modelMeta}>{m.size_gb} GB · {m.notes}</div>
                    </div>
                    <div style={{ display: "flex", gap: 6, flexShrink: 0 }}>
                      {m.installed ? (
                        <>
                          {m.id !== model && (
                            <button style={s.btnSm} onClick={() => setModel(m.id)}>Use</button>
                          )}
                          <button
                            style={{ ...s.btnSm, ...s.btnDanger }}
                            onClick={() => handleDelete(m.id)}
                          >
                            Delete
                          </button>
                        </>
                      ) : (
                        <button
                          style={{ ...s.btnSm, ...(pullState ? s.btnGhost : {}) }}
                          onClick={() => !pullState && handlePull(m.id)}
                          disabled={!!pullState}
                        >
                          {pullState ? (pullState.error ? "Failed" : "Downloading…") : "Download"}
                        </button>
                      )}
                    </div>
                  </div>
                  {pullState && (
                    <div>
                      {pullState.error ? (
                        <div style={{ fontSize: 11, color: "#f44336", marginTop: 6 }}>
                          {pullState.error}
                        </div>
                      ) : (
                        <>
                          <div style={s.progressBar}>
                            <div style={{ ...s.progressFill, width: `${pullState.pct ?? 0}%` }} />
                          </div>
                          <div style={{ fontSize: 11, color: "#555", marginTop: 4 }}>
                            {pullState.status}{pullState.pct != null ? ` — ${pullState.pct}%` : ""}
                          </div>
                        </>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
