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
  thinking: {
    background: "#141414", border: "1px solid #2a2a2a", borderRadius: 10,
    margin: "6px 0", fontSize: 13, color: "#9aa0a6",
  },
  thinkingSummary: {
    cursor: "pointer", padding: "6px 10px", userSelect: "none",
    color: "#9aa0a6", fontSize: 12,
  },
  thinkingBody: { padding: "4px 12px 10px", whiteSpace: "pre-wrap" as const },
};

type Segment = { kind: "text" | "think"; text: string; closed: boolean };

function parseSegments(content: string): Segment[] {
  if (!content.includes("<think>")) return [{ kind: "text", text: content, closed: true }];
  const out: Segment[] = [];
  const re = /<think>([\s\S]*?)(<\/think>|$)/g;
  let cursor = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(content)) !== null) {
    if (m.index > cursor) out.push({ kind: "text", text: content.slice(cursor, m.index), closed: true });
    out.push({ kind: "think", text: m[1], closed: m[2] === "</think>" });
    cursor = m.index + m[0].length;
  }
  if (cursor < content.length) out.push({ kind: "text", text: content.slice(cursor), closed: true });
  return out;
}

export default function MessageBubble({ msg }: { msg: Message }) {
  const isUser = msg.role === "user";
  if (isUser) {
    return (
      <div style={{ ...s.wrap, ...s.wrapUser }}>
        <div style={{ ...s.bubble, ...s.user }}>{msg.content}</div>
      </div>
    );
  }
  const segments = parseSegments(msg.content);
  return (
    <div style={s.wrap}>
      <div style={{ ...s.bubble, ...s.assistant }}>
        {segments.map((seg, i) =>
          seg.kind === "think" ? (
            <details key={i} style={s.thinking} open={!seg.closed}>
              <summary style={s.thinkingSummary}>
                {seg.closed ? "Thinking" : "Thinking…"}
              </summary>
              <div style={s.thinkingBody}>{seg.text.trim()}</div>
            </details>
          ) : seg.text.trim() ? (
            <Markdown key={i}>{seg.text}</Markdown>
          ) : null,
        )}
      </div>
    </div>
  );
}
