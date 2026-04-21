import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchChats, createChat, deleteChat, updateProject } from "../api/sync";
import type { Project } from "../api/sync";

const CONTEXT_OPTIONS = [2048, 4096, 8192, 16384, 32768, 65536, 131072];

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, overflowY: "auto", flex: 1 },
  headerRow: { display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 16, marginBottom: 16 },
  titleBox: { flex: 1 },
  title: { fontSize: 22, fontWeight: 700, marginBottom: 4, color: "#fff" },
  desc: { fontSize: 14, color: "#888" },
  card: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 12,
    padding: 16, marginBottom: 10, cursor: "pointer",
    display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12,
  },
  chatTitle: { fontWeight: 600, fontSize: 15, color: "#fff" },
  chatMeta: { fontSize: 12, color: "#666", marginTop: 2 },
  btn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 8,
    padding: "8px 14px", cursor: "pointer", fontWeight: 600, fontSize: 13,
  },
  btnGhost: {
    background: "transparent", color: "#888", border: "1px solid #2a2a2a", borderRadius: 8,
    padding: "6px 10px", cursor: "pointer", fontWeight: 500, fontSize: 12,
  },
  btnRow: { display: "flex", gap: 8 },
  backLink: { color: "#888", textDecoration: "none", fontSize: 13, marginBottom: 8, display: "inline-block", cursor: "pointer" },
  editPanel: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 12,
    padding: 16, marginBottom: 20,
  },
  label: { display: "block", fontSize: 12, color: "#888", marginBottom: 6, marginTop: 12, textTransform: "uppercase", letterSpacing: 0.5 },
  input: {
    width: "100%", background: "#0d0d0d", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none",
    boxSizing: "border-box" as const, fontFamily: "inherit",
  },
  textarea: {
    width: "100%", background: "#0d0d0d", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none",
    boxSizing: "border-box" as const, fontFamily: "inherit", resize: "vertical" as const,
    minHeight: 120,
  },
  hint: { fontSize: 12, color: "#666", marginTop: 4 },
  fieldRow: { display: "flex", gap: 12 },
  radioRow: { display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" as const },
  radioLabel: { display: "flex", alignItems: "center", gap: 6, color: "#e0e0e0", fontSize: 14, cursor: "pointer" },
  sectionDivider: { borderTop: "1px solid #2a2a2a", margin: "16px -16px" },
  saveRow: { display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 16 },
};

export default function ProjectDetail() {
  const { projectId } = useParams<{ projectId: string }>();
  const { projects, chats, setChats, addChat, removeChat, addProject } = useStore();
  const nav = useNavigate();
  const project = projects.find((p) => p.remote_id === projectId);
  const chatList = projectId ? (chats[projectId] ?? []) : [];
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Project | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!projectId) return;
    fetchChats(projectId).then((c) => setChats(projectId, c)).catch(console.error);
  }, [projectId, setChats]);

  if (!project) return <div style={{ padding: 24, color: "#888" }}>Project not found.</div>;

  const startEdit = () => {
    setDraft({ ...project });
    setEditing(true);
  };

  const cancelEdit = () => {
    setEditing(false);
    setDraft(null);
  };

  const saveEdit = async () => {
    if (!draft || !draft.name.trim()) return;
    setSaving(true);
    try {
      await updateProject(draft);
      addProject({ ...draft, updated_at: Date.now() });
      setEditing(false);
      setDraft(null);
    } catch (err) {
      alert(`Failed to save: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const handleNewChat = async () => {
    if (!projectId) return;
    try {
      const chat = await createChat(projectId, "New chat");
      addChat(projectId, chat);
      nav(`/projects/${projectId}/chats/${chat.remote_id}`);
    } catch (e) {
      alert(`Failed to create chat: ${e}`);
    }
  };

  const handleDeleteChat = async (e: React.MouseEvent, chat: (typeof chatList)[number]) => {
    e.stopPropagation();
    if (!confirm(`Delete "${chat.title}"?`)) return;
    try {
      await deleteChat(chat);
      if (projectId) removeChat(projectId, chat.remote_id);
    } catch (err) {
      alert(`Failed to delete: ${err}`);
    }
  };

  return (
    <div style={s.page}>
      <span style={s.backLink} onClick={() => nav("/projects")}>← Projects</span>
      <div style={s.headerRow}>
        <div style={s.titleBox}>
          <div style={s.title}>{project.name}</div>
          {project.description && <div style={s.desc}>{project.description}</div>}
        </div>
        <div style={s.btnRow}>
          {!editing && <button style={s.btnGhost} onClick={startEdit}>Edit</button>}
          <button style={s.btn} onClick={handleNewChat}>+ New chat</button>
        </div>
      </div>

      {editing && draft && (
        <div style={s.editPanel}>
          <label style={{ ...s.label, marginTop: 0 }}>Name</label>
          <input
            style={s.input}
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />

          <label style={s.label}>Description</label>
          <input
            style={s.input}
            value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          />

          <label style={s.label}>Manual context</label>
          <textarea
            style={s.textarea}
            value={draft.manual_context}
            onChange={(e) => setDraft({ ...draft, manual_context: e.target.value })}
            placeholder="Permanent instructions the assistant should always follow for this project."
          />
          <div style={s.hint}>~{Math.ceil(draft.manual_context.length / 4)} tokens</div>

          <div style={s.sectionDivider} />

          <div style={s.fieldRow}>
            <div style={{ flex: 1 }}>
              <label style={{ ...s.label, marginTop: 0 }}>Memory token limit</label>
              <input
                style={s.input}
                type="number"
                min={0}
                value={draft.memory_token_limit}
                onChange={(e) => setDraft({ ...draft, memory_token_limit: Number(e.target.value) || 0 })}
              />
            </div>
            <div style={{ flex: 1 }}>
              <label style={{ ...s.label, marginTop: 0 }}>Context length</label>
              <select
                style={s.input}
                value={draft.context_length}
                onChange={(e) => setDraft({ ...draft, context_length: Number(e.target.value) })}
              >
                {CONTEXT_OPTIONS.map((n) => (
                  <option key={n} value={n}>
                    {n >= 1024 ? `${n / 1024}K` : n} tokens
                  </option>
                ))}
              </select>
            </div>
          </div>

          <label style={s.label}>Backend</label>
          <div style={s.radioRow}>
            {(["LOCAL", "REMOTE"] as const).map((b) => (
              <label key={b} style={s.radioLabel}>
                <input
                  type="radio"
                  name="backend"
                  checked={draft.preferred_backend === b}
                  onChange={() => setDraft({ ...draft, preferred_backend: b })}
                />
                {b === "LOCAL" ? "Local (on-device)" : "Remote (server)"}
              </label>
            ))}
          </div>
          <div style={s.hint}>
            The web app always runs on the server, but this setting controls where the Android app
            runs inference for this project.
          </div>

          <label style={s.label}>Accumulated memory</label>
          <textarea
            style={{ ...s.textarea, minHeight: 80, opacity: 0.8 }}
            value={draft.accumulated_memory}
            onChange={(e) => setDraft({ ...draft, accumulated_memory: e.target.value })}
            placeholder="Auto-generated by the assistant over time. Editable if you want to trim or reset it."
          />

          <div style={s.saveRow}>
            <button style={{ ...s.btn, background: "transparent", border: "1px solid #2a2a2a" }}
              onClick={cancelEdit} disabled={saving}>Cancel</button>
            <button style={s.btn} onClick={saveEdit} disabled={saving || !draft.name.trim()}>
              {saving ? "Saving…" : "Save"}
            </button>
          </div>
        </div>
      )}

      {chatList.length === 0 && <p style={{ color: "#666" }}>No chats yet. Start one above.</p>}
      {chatList
        .slice()
        .sort((a, b) => b.updated_at - a.updated_at)
        .map((c) => (
          <div
            key={c.remote_id}
            style={s.card}
            onClick={() => nav(`/projects/${projectId}/chats/${c.remote_id}`)}
          >
            <div style={{ flex: 1 }}>
              <div style={s.chatTitle}>{c.title}</div>
              <div style={s.chatMeta}>{new Date(c.updated_at).toLocaleString()}</div>
            </div>
            <button style={s.btnGhost} onClick={(e) => handleDeleteChat(e, c)}>Delete</button>
          </div>
        ))}
    </div>
  );
}
