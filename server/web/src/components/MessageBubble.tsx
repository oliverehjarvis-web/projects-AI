import { useState } from "react";
import Markdown from "react-markdown";
import type { Message } from "../api/sync";
import { useStore } from "../store/useStore";
import { palette, radius, space, font } from "../theme";

interface Props {
  msg: Message;
  /** Show the regenerate button. Caller decides which message is "last assistant". */
  canRegenerate?: boolean;
  onRegenerate?: () => void;
  isStreaming?: boolean;
}

const styles: Record<string, React.CSSProperties> = {
  wrap: { display: "flex", marginBottom: space.md, flexDirection: "column" },
  wrapUser: { alignItems: "flex-end" },
  wrapAssistant: { alignItems: "flex-start" },
  bubbleBase: {
    maxWidth: "78%",
    padding: "10px 14px",
    borderRadius: 16,
    fontSize: font.body,
    lineHeight: 1.6,
    wordBreak: "break-word",
    userSelect: "text",
    WebkitUserSelect: "text",
  },
  user: { background: palette.primary, color: palette.onPrimary, borderBottomRightRadius: 4 },
  assistant: {
    background: palette.surface,
    color: palette.text,
    borderBottomLeftRadius: 4,
    border: `1px solid ${palette.border}`,
  },
  thinking: {
    background: "#141414",
    border: `1px solid ${palette.border}`,
    borderRadius: 10,
    margin: "6px 0",
    fontSize: font.bodySm,
    color: palette.textMuted,
  },
  thinkingSummary: {
    cursor: "pointer",
    padding: "6px 10px",
    userSelect: "none",
    color: palette.textMuted,
    fontSize: font.label,
  },
  thinkingBody: { padding: "4px 12px 10px", whiteSpace: "pre-wrap" as const },
  codeWrap: {
    background: palette.bg,
    border: `1px solid ${palette.border}`,
    borderRadius: radius.md,
    margin: "8px 0",
    overflow: "hidden",
    maxWidth: "100%",
  },
  codeHeader: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    padding: "4px 10px",
    background: "#111",
    borderBottom: `1px solid ${palette.border}`,
    fontSize: 11,
    color: palette.textMuted,
    textTransform: "lowercase" as const,
  },
  codeBody: {
    padding: "10px 12px",
    margin: 0,
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 12.5,
    lineHeight: 1.5,
    color: palette.text,
    overflowX: "auto" as const,
    whiteSpace: "pre" as const,
  },
  copyBtn: {
    background: "transparent",
    border: `1px solid ${palette.border}`,
    color: palette.textMuted,
    borderRadius: radius.sm,
    fontSize: 11,
    padding: "2px 8px",
    cursor: "pointer",
  },
  msgActions: {
    display: "flex",
    gap: 6,
    marginTop: 4,
    fontSize: 11,
  },
  actionBtn: {
    background: "transparent",
    border: `1px solid ${palette.border}`,
    color: palette.textMuted,
    borderRadius: radius.sm,
    fontSize: 11,
    padding: "3px 9px",
    cursor: "pointer",
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
  } catch { /* fall through */ }
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
    if (await copyText(code)) {
      setCopied(true);
      onCopied();
      setTimeout(() => setCopied(false), 1500);
    }
  };
  return (
    <div style={styles.codeWrap}>
      <div style={styles.codeHeader}>
        <span>{lang || "code"}</span>
        <button style={styles.copyBtn} onClick={handleCopy}>
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre style={styles.codeBody}>{code}</pre>
    </div>
  );
}

export default function MessageBubble({ msg, canRegenerate, onRegenerate, isStreaming }: Props) {
  const isUser = msg.role === "user";
  const pushSnack = useStore((s) => s.pushSnack);

  if (isUser) {
    return (
      <div style={{ ...styles.wrap, ...styles.wrapUser }}>
        <div style={{ ...styles.bubbleBase, ...styles.user }}>{msg.content}</div>
      </div>
    );
  }
  const segments = parseSegments(msg.content);
  return (
    <div style={{ ...styles.wrap, ...styles.wrapAssistant }}>
      <div style={{ ...styles.bubbleBase, ...styles.assistant }}>
        {segments.map((seg, i) =>
          seg.kind === "think" ? (
            <details key={i} style={styles.thinking} open={!seg.closed}>
              <summary style={styles.thinkingSummary}>
                {seg.closed ? "Thinking" : "Thinking…"}
              </summary>
              <div style={styles.thinkingBody}>{seg.text.trim()}</div>
            </details>
          ) : seg.text.trim() ? (
            <Markdown
              key={i}
              components={{
                code(props) {
                  const { className, children } = props as { className?: string; children?: React.ReactNode };
                  const match = /language-([\w+\-.]+)/.exec(className || "");
                  if (match && typeof children === "string") {
                    return (
                      <CodeBlock
                        lang={match[1]}
                        code={children.replace(/\n$/, "")}
                        onCopied={() => pushSnack("Copied code")}
                      />
                    );
                  }
                  return <code {...(props as object)}>{children}</code>;
                },
                pre({ children }) { return <>{children}</>; },
              }}
            >
              {seg.text}
            </Markdown>
          ) : null,
        )}
      </div>
      {!isStreaming && (
        <div style={styles.msgActions}>
          <button
            style={styles.actionBtn}
            onClick={async () => {
              if (await copyText(msg.content)) pushSnack("Copied message");
            }}
          >
            Copy
          </button>
          {canRegenerate && onRegenerate && (
            <button style={styles.actionBtn} onClick={onRegenerate}>
              ↻ Regenerate
            </button>
          )}
        </div>
      )}
    </div>
  );
}
