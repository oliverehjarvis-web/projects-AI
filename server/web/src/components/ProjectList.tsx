import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchProjects } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, overflowY: "auto", flex: 1 },
  title: { fontSize: 22, fontWeight: 700, marginBottom: 20, color: "#fff" },
  card: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 12,
    padding: 16, marginBottom: 12, cursor: "pointer",
  },
  name: { fontWeight: 600, fontSize: 16, color: "#fff", marginBottom: 4 },
  desc: { fontSize: 13, color: "#888" },
};

export default function ProjectList() {
  const { projects, setProjects } = useStore();
  const nav = useNavigate();

  useEffect(() => {
    fetchProjects().then(setProjects).catch(console.error);
  }, [setProjects]);

  return (
    <div style={s.page}>
      <div style={s.title}>Projects</div>
      {projects.length === 0 && <p style={{ color: "#666" }}>No projects synced yet.</p>}
      {projects.map((p) => (
        <div key={p.remote_id} style={s.card} onClick={() => nav(`/projects/${p.remote_id}`)}>
          <div style={s.name}>{p.name}</div>
          {p.description && <div style={s.desc}>{p.description}</div>}
        </div>
      ))}
    </div>
  );
}
