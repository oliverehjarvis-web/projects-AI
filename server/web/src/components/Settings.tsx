import { useState, useEffect, useCallback } from "react";
import { getBaseUrl, setBaseUrl, getToken, setToken } from "../api/client";
import { useStore } from "../store/useStore";
import { checkHealth } from "../api/sync";
import { fetchModels, pullModel, deleteModel, type CatalogueModel } from "../api/models";
import { fetchGlobalContext, saveGlobalContext } from "../api/global";
import { whoami as ghWhoami } from "../api/github";
import Button from "../ui/Button";
import Card from "../ui/Card";
import Section from "../ui/Section";
import { Label, Hint, TextInput, TextArea, Select } from "../ui/Field";
import { palette, radius, font } from "../theme";

interface PullState { status: string; pct: number | null; error?: string }

export default function Settings() {
  const {
    model, setModel, globalContext, setGlobalContext, pushSnack,
    searxngUrl, setSearxngUrl,
    githubPat, setGithubPat, githubDefaultRepo, setGithubDefaultRepo,
  } = useStore();
  const [url, setUrl] = useState(getBaseUrl());
  const [token, setTokenState] = useState(getToken());
  const [connStatus, setConnStatus] = useState<{ ok: boolean; msg: string } | null>(null);
  const [catalogue, setCatalogue] = useState<CatalogueModel[]>([]);
  const [pulling, setPulling] = useState<Record<string, PullState>>({});
  const [loadingModels, setLoadingModels] = useState(false);

  const [gcDraft, setGcDraft] = useState({ user_name: globalContext.user_name, rules: globalContext.rules });
  const [gcSaving, setGcSaving] = useState(false);

  const [searxngDraft, setSearxngDraft] = useState(searxngUrl);
  const [patDraft, setPatDraft] = useState(githubPat);
  const [defaultRepoDraft, setDefaultRepoDraft] = useState(githubDefaultRepo);
  const [ghTesting, setGhTesting] = useState(false);

  useEffect(() => {
    setGcDraft({ user_name: globalContext.user_name, rules: globalContext.rules });
  }, [globalContext.user_name, globalContext.rules]);

  useEffect(() => setSearxngDraft(searxngUrl), [searxngUrl]);
  useEffect(() => setPatDraft(githubPat), [githubPat]);
  useEffect(() => setDefaultRepoDraft(githubDefaultRepo), [githubDefaultRepo]);

  useEffect(() => {
    if (!getBaseUrl() || !getToken()) return;
    fetchGlobalContext().then(setGlobalContext).catch(() => {});
  }, [setGlobalContext]);

  const installedModels = catalogue.filter((m) => m.installed);

  const loadModels = useCallback(async () => {
    setLoadingModels(true);
    try {
      const data = await fetchModels();
      setCatalogue(data.catalogue);
    } catch { /* not connected */ }
    finally { setLoadingModels(false); }
  }, []);

  useEffect(() => { if (getBaseUrl() && getToken()) loadModels(); }, [loadModels]);

  const saveConnection = () => {
    setBaseUrl(url);
    setToken(token);
  };

  const test = async () => {
    saveConnection();
    try {
      const h = await checkHealth();
      setConnStatus({ ok: true, msg: `Connected — Ollama: ${h.ollama}` });
      loadModels();
    } catch (e) {
      setConnStatus({ ok: false, msg: `Failed: ${String(e)}` });
    }
  };

  const saveGlobal = async () => {
    setGcSaving(true);
    try {
      const saved = await saveGlobalContext(gcDraft);
      setGlobalContext(saved);
      pushSnack("Saved");
    } catch (e) {
      pushSnack(`Failed: ${e}`, { tone: "error" });
    } finally {
      setGcSaving(false);
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
        },
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

  const testGithub = async () => {
    if (!patDraft.trim()) return;
    setGhTesting(true);
    try {
      // Save first so subsequent calls (RepoBrowser) use the new token.
      setGithubPat(patDraft);
      setGithubDefaultRepo(defaultRepoDraft);
      const login = await ghWhoami(patDraft);
      pushSnack(`Connected as ${login}`);
    } catch (e) {
      pushSnack(`Test failed: ${e}`, { tone: "error" });
    } finally {
      setGhTesting(false);
    }
  };

  return (
    <div style={{ padding: 24, maxWidth: 720, overflowY: "auto", flex: 1 }}>
      <h1 style={{ fontSize: 22, fontWeight: 700, color: palette.text, margin: "0 0 24px" }}>Settings</h1>

      <Section title="Server connection">
        <Label>Server URL</Label>
        <TextInput value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://100.x.x.x:8765" />
        <Label>API token</Label>
        <TextInput type="password" value={token} onChange={(e) => setTokenState(e.target.value)} />
        <div style={{ marginTop: 12 }}>
          <Button onClick={test}>Save & Test</Button>
          {connStatus && (
            <span style={{
              marginLeft: 12,
              fontSize: 13,
              color: connStatus.ok ? palette.success : palette.error,
            }}>
              {connStatus.msg}
            </span>
          )}
        </div>
      </Section>

      <Section
        title="Global context"
        description="Soft preferences the assistant applies across every project. Treated as guidelines, not commands."
      >
        <Label>Your name</Label>
        <TextInput
          value={gcDraft.user_name}
          onChange={(e) => setGcDraft({ ...gcDraft, user_name: e.target.value })}
          placeholder="e.g. Oli"
        />
        <Label>Standing guidelines</Label>
        <TextArea
          value={gcDraft.rules}
          onChange={(e) => setGcDraft({ ...gcDraft, rules: e.target.value })}
          placeholder={"Examples: British English, avoid em-dashes, be concise."}
        />
        <div style={{ marginTop: 12 }}>
          <Button
            onClick={saveGlobal}
            loading={gcSaving}
            disabled={
              gcDraft.user_name === globalContext.user_name &&
              gcDraft.rules === globalContext.rules
            }
          >
            Save
          </Button>
        </div>
      </Section>

      <Section
        title="AI tools"
        description="Optional capabilities the assistant can reach for during a chat."
      >
        <Card padding={16} style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: palette.text, marginBottom: 8 }}>
            Web search
          </div>
          <Hint>
            Point at a SearXNG instance (e.g. running on TrueNAS over Tailscale) to enable per-chat
            web search. Include scheme and port.
          </Hint>
          <div style={{ marginTop: 8, display: "flex", gap: 8 }}>
            <TextInput
              style={{ marginTop: 0 }}
              value={searxngDraft}
              onChange={(e) => setSearxngDraft(e.target.value)}
              placeholder="http://100.x.x.x:8888"
            />
            <Button
              onClick={() => { setSearxngUrl(searxngDraft); pushSnack("Saved"); }}
              disabled={searxngDraft === searxngUrl}
            >
              Save
            </Button>
          </div>
        </Card>

        <Card padding={16}>
          <div style={{ fontSize: 13, fontWeight: 600, color: palette.text, marginBottom: 8 }}>
            GitHub
          </div>
          <Hint>
            Personal Access Token used by the in-chat repo browser to read your repos. Fine-grained
            PATs work best — minimum scope, expires automatically. Stays in your browser; never sent
            to the server.
          </Hint>
          <Label>PAT</Label>
          <TextInput
            type="password"
            value={patDraft}
            onChange={(e) => setPatDraft(e.target.value)}
            placeholder="github_pat_…"
          />
          <Label>Default repo (optional)</Label>
          <TextInput
            value={defaultRepoDraft}
            onChange={(e) => setDefaultRepoDraft(e.target.value)}
            placeholder="owner/repo"
          />
          <div style={{ marginTop: 12, display: "flex", gap: 8 }}>
            <Button
              onClick={() => {
                setGithubPat(patDraft);
                setGithubDefaultRepo(defaultRepoDraft);
                pushSnack("Saved");
              }}
              disabled={patDraft === githubPat && defaultRepoDraft === githubDefaultRepo}
            >
              Save
            </Button>
            <Button variant="outlined" loading={ghTesting} onClick={testGithub} disabled={!patDraft.trim()}>
              Test connection
            </Button>
          </div>
        </Card>
      </Section>

      {installedModels.length > 0 && (
        <Section title="Active model">
          <Select value={model} onChange={(e) => setModel((e.target as unknown as HTMLSelectElement).value)}>
            {installedModels.map((m) => (
              <option key={m.id} value={m.id}>{m.label} ({m.size_gb} GB)</option>
            ))}
          </Select>
        </Section>
      )}

      <Section
        title="Model library"
        action={
          <Button size="sm" variant="outlined" onClick={loadModels}>
            {loadingModels ? "Refreshing…" : "Refresh"}
          </Button>
        }
      >
        {catalogue.length === 0 && (
          <Hint>Save & Test your connection above to browse models.</Hint>
        )}

        {families.map((family) => (
          <div key={family} style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: palette.textDim, marginBottom: 6 }}>
              {family}
            </div>
            {catalogue.filter((m) => m.family === family).map((m) => {
              const pullState = pulling[m.id];
              const installed = m.installed;
              return (
                <Card
                  key={m.id}
                  padding={12}
                  style={{
                    marginBottom: 8,
                    borderColor: installed ? palette.primary : palette.border,
                  }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                    <div style={{ flex: 1 }}>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <span style={{ fontWeight: 600, color: palette.text }}>{m.label}</span>
                        {installed && (
                          <span style={badge(palette.primary, "#bfdbfe")}>Installed</span>
                        )}
                        {m.id === model && installed && (
                          <span style={badge(palette.success, "#bbf7d0")}>Active</span>
                        )}
                      </div>
                      <div style={{ fontSize: 12, color: palette.textMuted, marginTop: 2 }}>
                        {m.size_gb} GB · {m.notes}
                      </div>
                    </div>
                    <div style={{ display: "flex", gap: 6, flexShrink: 0 }}>
                      {installed ? (
                        <>
                          {m.id !== model && (
                            <Button size="sm" onClick={() => setModel(m.id)}>Use</Button>
                          )}
                          <Button size="sm" variant="danger" onClick={() => handleDelete(m.id)}>
                            Delete
                          </Button>
                        </>
                      ) : (
                        <Button
                          size="sm"
                          variant="outlined"
                          loading={!!pullState && !pullState.error}
                          disabled={!!pullState}
                          onClick={() => handlePull(m.id)}
                        >
                          {pullState ? (pullState.error ? "Failed" : "Downloading…") : "Download"}
                        </Button>
                      )}
                    </div>
                  </div>
                  {pullState && (
                    <div style={{ marginTop: 8 }}>
                      {pullState.error ? (
                        <div style={{ fontSize: 11, color: palette.error }}>{pullState.error}</div>
                      ) : (
                        <>
                          <div
                            style={{
                              height: 4,
                              background: palette.surfaceVariant,
                              borderRadius: 2,
                              overflow: "hidden",
                            }}
                          >
                            <div
                              style={{
                                width: `${pullState.pct ?? 0}%`,
                                height: "100%",
                                background: palette.primary,
                                transition: "width 200ms",
                              }}
                            />
                          </div>
                          <div style={{ fontSize: 11, color: palette.textDim, marginTop: 4 }}>
                            {pullState.status}
                            {pullState.pct != null ? ` — ${pullState.pct}%` : ""}
                          </div>
                        </>
                      )}
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        ))}
      </Section>
    </div>
  );
}

function badge(bg: string, fg: string): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 600,
    padding: "2px 7px",
    borderRadius: radius.pill,
    background: bg,
    color: fg,
  };
}
