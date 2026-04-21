import { useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchChats, createChat, deleteChat } from "../api/sync";

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
  backLink: { color: "#888", textDecoration: "none", fontSize: 13, marginBottom: 8, display: "inline-block" },
};

export default function ProjectDetail() {
  const { projectId } = useParams<{ projectId: string }>();
  const { projects, chats, setChats, addChat, removeChat } = useStore();
  const nav = useNavigate();
  const project = projects.find((p) => p.remote_id === projectId);
  const chatList = projectId ? (chats[projectId] ?? []) : [];

  useEffect(() => {
    if (!projectId) return;
    fetchChats(projectId).then((c) => setChats(projectId, c)).catch(console.error);
  }, [projectId, setChats]);

  if (!project) return <div style={{ padding: 24, color: "#888" }}>Project not found.</div>;

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
      <a style={s.backLink} onClick={() => nav("/projects")} href="#">← Projects</a>
      <div style={s.headerRow}>
        <div style={s.titleBox}>
          <div style={s.title}>{project.name}</div>
          {project.description && <div style={s.desc}>{project.description}</div>}
        </div>
        <button style={s.btn} onClick={handleNewChat}>+ New chat</button>
      </div>
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
