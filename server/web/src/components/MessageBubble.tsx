import Markdown from "react-markdown";
import type { Message } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  wrap: { display: "flex", marginBottom: 12 },
  wrapUser: { justifyContent: "flex-end" },
  bubble: {
    maxWidth: "75%", padding: "10px 14px", borderRadius: 16,
    fontSize: 14, lineHeight: 1.55, wordBreak: "break-word",
  },
  user: { background: "#2563eb", color: "#fff", borderBottomRightRadius: 4 },
  assistant: { background: "#1e1e1e", color: "#e0e0e0", borderBottomLeftRadius: 4, border: "1px solid #2a2a2a" },
};

export default function MessageBubble({ msg }: { msg: Message }) {
  const isUser = msg.role === "user";
  return (
    <div style={{ ...s.wrap, ...(isUser ? s.wrapUser : {}) }}>
      <div style={{ ...s.bubble, ...(isUser ? s.user : s.assistant) }}>
        {isUser ? msg.content : <Markdown>{msg.content}</Markdown>}
      </div>
    </div>
  );
}
