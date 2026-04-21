import { useState } from "react";
import { getBaseUrl, setBaseUrl, getToken, setToken } from "../api/client";
import { useStore } from "../store/useStore";
import { checkHealth } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, maxWidth: 480 },
  title: { fontSize: 22, fontWeight: 700, marginBottom: 24, color: "#fff" },
  label: { display: "block", fontSize: 13, color: "#888", marginBottom: 6 },
  input: {
    width: "100%", background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none", marginBottom: 16,
  },
  btn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 8,
    padding: "10px 18px", cursor: "pointer", fontWeight: 600, fontSize: 14, marginRight: 8,
  },
  status: { marginTop: 12, fontSize: 13, color: "#4caf50" },
  statusErr: { color: "#f44336" },
};

export default function Settings() {
  const { model, setModel } = useStore();
  const [url, setUrl] = useState(getBaseUrl());
  const [token, setTokenState] = useState(getToken());
  const [modelInput, setModelInput] = useState(model);
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null);

  const save = () => {
    setBaseUrl(url);
    setToken(token);
    setModel(modelInput);
    setStatus({ ok: true, msg: "Saved." });
  };

  const test = async () => {
    setBaseUrl(url);
    setToken(token);
    try {
      const h = await checkHealth();
      setStatus({ ok: true, msg: `Connected — Ollama: ${h.ollama}` });
    } catch (e) {
      setStatus({ ok: false, msg: `Failed: ${String(e)}` });
    }
  };

  return (
    <div style={s.page}>
      <div style={s.title}>Settings</div>
      <label style={s.label}>Server URL (e.g. http://100.x.x.x:8765)</label>
      <input style={s.input} value={url} onChange={(e) => setUrl(e.target.value)} />
      <label style={s.label}>API Token</label>
      <input style={s.input} type="password" value={token} onChange={(e) => setTokenState(e.target.value)} />
      <label style={s.label}>Model name</label>
      <input style={s.input} value={modelInput} onChange={(e) => setModelInput(e.target.value)} placeholder="gemma3:4b-it-q4_K_M" />
      <button style={s.btn} onClick={save}>Save</button>
      <button style={{ ...s.btn, background: "#333" }} onClick={test}>Test Connection</button>
      {status && (
        <div style={{ ...s.status, ...(status.ok ? {} : s.statusErr) }}>{status.msg}</div>
      )}
    </div>
  );
}
