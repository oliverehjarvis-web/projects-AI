import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchProjects, createProject, deleteProject } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, overflowY: "auto", flex: 1 },
  headerRow: { display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 20 },
  title: { fontSize: 22, fontWeight: 700, color: "#fff" },
  card: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 12,
    padding: 16, marginBottom: 12, cursor: "pointer",
    display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12,
  },
  name: { fontWeight: 600, fontSize: 16, color: "#fff", marginBottom: 4 },
  desc: { fontSize: 13, color: "#888" },
  btn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 8,
    padding: "8px 14px", cursor: "pointer", fontWeight: 600, fontSize: 13,
  },
  btnGhost: {
    background: "transparent", color: "#888", border: "1px solid #2a2a2a", borderRadius: 8,
    padding: "6px 10px", cursor: "pointer", fontWeight: 500, fontSize: 12,
  },
  dialogBg: {
    position: "fixed", inset: 0, background: "rgba(0,0,0,0.6)", zIndex: 10,
    display: "flex", alignItems: "center", justifyContent: "center",
  },
  dialog: {
    background: "#1a1a1a", borderRadius: 12, padding: 24, width: 400,
    border: "1px solid #2a2a2a",
  },
  dialogTitle: { fontSize: 18, fontWeight: 600, color: "#fff", marginBottom: 16 },
  input: {
    width: "100%", background: "#0d0d0d", border: "1px solid #2a2a2a", borderRadius: 8,
    color: "#e0e0e0", padding: "10px 12px", fontSize: 14, outline: "none", marginBottom: 12,
    boxSizing: "border-box" as const,
  },
  actionRow: { display: "flex", gap: 8, justifyContent: "flex-end" },
};

export default function ProjectList() {
  const { projects, setProjects, addProject, removeProject } = useStore();
  const nav = useNavigate();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetchProjects().then(setProjects).catch(console.error);
  }, [setProjects]);

  const handleCreate = async () => {
    if (!name.trim()) return;
    setBusy(true);
    try {
      const project = await createProject(name.trim(), description.trim());
      addProject(project);
      setDialogOpen(false);
      setName("");
      setDescription("");
      nav(`/projects/${project.remote_id}`);
    } catch (e) {
      console.error(e);
      alert(`Failed to create project: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (e: React.MouseEvent, project: (typeof projects)[number]) => {
    e.stopPropagation();
    if (!confirm(`Delete "${project.name}" and all its chats?`)) return;
    try {
      await deleteProject(project);
      removeProject(project.remote_id);
    } catch (err) {
      alert(`Failed to delete: ${err}`);
    }
  };

  return (
    <div style={s.page}>
      <div style={s.headerRow}>
        <div style={s.title}>Projects</div>
        <button style={s.btn} onClick={() => setDialogOpen(true)}>+ New project</button>
      </div>
      {projects.length === 0 && <p style={{ color: "#666" }}>No projects yet. Create one to get started.</p>}
      {projects.map((p) => (
        <div key={p.remote_id} style={s.card} onClick={() => nav(`/projects/${p.remote_id}`)}>
          <div style={{ flex: 1 }}>
            <div style={s.name}>{p.name}</div>
            {p.description && <div style={s.desc}>{p.description}</div>}
          </div>
          <button style={s.btnGhost} onClick={(e) => handleDelete(e, p)}>Delete</button>
        </div>
      ))}

      {dialogOpen && (
        <div style={s.dialogBg} onClick={() => !busy && setDialogOpen(false)}>
          <div style={s.dialog} onClick={(e) => e.stopPropagation()}>
            <div style={s.dialogTitle}>New project</div>
            <input
              style={s.input}
              placeholder="Project name"
              value={name}
              autoFocus
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleCreate()}
            />
            <input
              style={s.input}
              placeholder="Description (optional)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleCreate()}
            />
            <div style={s.actionRow}>
              <button style={{ ...s.btn, background: "transparent", border: "1px solid #2a2a2a" }}
                onClick={() => setDialogOpen(false)} disabled={busy}>Cancel</button>
              <button style={s.btn} onClick={handleCreate} disabled={busy || !name.trim()}>
                {busy ? "Creating…" : "Create"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
