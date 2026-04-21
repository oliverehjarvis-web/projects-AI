import { useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchChats } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  page: { padding: 24, overflowY: "auto", flex: 1 },
  title: { fontSize: 22, fontWeight: 700, marginBottom: 4, color: "#fff" },
  desc: { fontSize: 14, color: "#888", marginBottom: 24 },
  card: {
    background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 12,
    padding: 16, marginBottom: 10, cursor: "pointer",
  },
  chatTitle: { fontWeight: 600, fontSize: 15, color: "#fff" },
};

export default function ProjectDetail() {
  const { projectId } = useParams<{ projectId: string }>();
  const { projects, chats, setChats } = useStore();
  const nav = useNavigate();
  const project = projects.find((p) => p.remote_id === projectId);
  const chatList = projectId ? (chats[projectId] ?? []) : [];

  useEffect(() => {
    if (!projectId) return;
    fetchChats(projectId).then((c) => setChats(projectId, c)).catch(console.error);
  }, [projectId, setChats]);

  if (!project) return <div style={{ padding: 24, color: "#888" }}>Project not found.</div>;

  return (
    <div style={s.page}>
      <div style={s.title}>{project.name}</div>
      {project.description && <div style={s.desc}>{project.description}</div>}
      {chatList.length === 0 && <p style={{ color: "#666" }}>No chats yet. Start one from the Android app.</p>}
      {chatList
        .slice()
        .sort((a, b) => b.updated_at - a.updated_at)
        .map((c) => (
          <div
            key={c.remote_id}
            style={s.card}
            onClick={() => nav(`/projects/${projectId}/chats/${c.remote_id}`)}
          >
            <div style={s.chatTitle}>{c.title}</div>
          </div>
        ))}
    </div>
  );
}
