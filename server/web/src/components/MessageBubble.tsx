import { useState } from "react";
import Markdown from "react-markdown";
import type { Message } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  wrap: { display: "flex", marginBottom: 12, flexDirection: "column" },
  wrapUser: { alignItems: "flex-end" },
  wrapAssistant: { alignItems: "flex-start" },
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
  codeWrap: {
    background: "#0d0d0d", border: "1px solid #2a2a2a", borderRadius: 8,
    margin: "8px 0", overflow: "hidden", maxWidth: "100%",
  },
  codeHeader: {
    display: "flex", alignItems: "center", justifyContent: "space-between",
    padding: "4px 10px", background: "#161616", borderBottom: "1px solid #2a2a2a",
    fontSize: 11, color: "#9aa0a6", textTransform: "lowercase" as const,
  },
  codeBody: {
    padding: "10px 12px", margin: 0, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 12.5, lineHeight: 1.5, color: "#e0e0e0", overflowX: "auto" as const,
    whiteSpace: "pre" as const,
  },
  copyBtn: {
    background: "transparent", border: "1px solid #333", color: "#bbb",
    borderRadius: 6, fontSize: 11, padding: "2px 8px", cursor: "pointer",
  },
  msgActions: {
    display: "flex", gap: 6, marginTop: 4, opacity: 0.7, fontSize: 11,
  },
  actionBtn: {
    background: "transparent", border: "1px solid #2a2a2a", color: "#9aa0a6",
    borderRadius: 6, fontSize: 11, padding: "2px 8px", cursor: "pointer",
  },
  toast: {
    position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)",
    background: "#2a2a2a", color: "#e0e0e0", padding: "8px 14px",
    borderRadius: 8, fontSize: 13, border: "1px solid #3a3a3a",
    zIndex: 1000,
  },
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

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* fall through to legacy path */ }
  // Fallback for older browsers / non-secure contexts.
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand("copy");
  document.body.removeChild(ta);
  return ok;
}

function CodeBlock({ lang, code, onCopied }: { lang: string; code: string; onCopied: () => void }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    const ok = await copyText(code);
    if (ok) {
      setCopied(true);
      onCopied();
      setTimeout(() => setCopied(false), 1500);
    }
  };
  return (
    <div style={s.codeWrap}>
      <div style={s.codeHeader}>
        <span>{lang || "code"}</span>
        <button style={s.copyBtn} onClick={handleCopy}>
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre style={s.codeBody}>{code}</pre>
    </div>
  );
}

export default function MessageBubble({ msg }: { msg: Message }) {
  const isUser = msg.role === "user";
  const [toast, setToast] = useState<string | null>(null);
  const showToast = (label: string) => {
    setToast(label);
    setTimeout(() => setToast(null), 1500);
  };

  if (isUser) {
    return (
      <div style={{ ...s.wrap, ...s.wrapUser }}>
        <div style={{ ...s.bubble, ...s.user }}>{msg.content}</div>
        {toast && <div style={s.toast}>{toast}</div>}
      </div>
    );
  }
  const segments = parseSegments(msg.content);
  return (
    <div style={{ ...s.wrap, ...s.wrapAssistant }}>
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
            <Markdown
              key={i}
              components={{
                code(props) {
                  const { className, children } = props as { className?: string; children?: React.ReactNode };
                  const match = /language-([\w+\-.]+)/.exec(className || "");
                  // Fenced code blocks arrive as a string child with a language- class.
                  // Inline code (no language) keeps the default <code> rendering.
                  if (match && typeof children === "string") {
                    return (
                      <CodeBlock
                        lang={match[1]}
                        code={children.replace(/\n$/, "")}
                        onCopied={() => showToast("Copied code")}
                      />
                    );
                  }
                  return <code {...(props as object)}>{children}</code>;
                },
                // react-markdown wraps the customised <code> in a <pre>; unwrap it
                // because CodeBlock already provides its own container.
                pre({ children }) { return <>{children}</>; },
              }}
            >
              {seg.text}
            </Markdown>
          ) : null,
        )}
      </div>
      <div style={s.msgActions}>
        <button
          style={s.actionBtn}
          onClick={async () => {
            if (await copyText(msg.content)) showToast("Copied message");
          }}
        >
          Copy
        </button>
      </div>
      {toast && <div style={s.toast}>{toast}</div>}
    </div>
  );
}
