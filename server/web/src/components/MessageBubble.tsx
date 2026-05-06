import { useState } from "react";
import Markdown from "react-markdown";
import type { Message } from "../api/sync";
import { useStore } from "../store/useStore";
import styles from "./MessageBubble.module.css";

interface Props {
  msg: Message;
  /** Show the regenerate button. Caller decides which message is "last assistant". */
  canRegenerate?: boolean;
  onRegenerate?: () => void;
  isStreaming?: boolean;
}

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
    <div className={styles.codeWrap}>
      <div className={styles.codeHeader}>
        <span>{lang || "code"}</span>
        <button className={styles.copyBtn} onClick={handleCopy}>
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre className={styles.codeBody}>{code}</pre>
    </div>
  );
}

export default function MessageBubble({ msg, canRegenerate, onRegenerate, isStreaming }: Props) {
  const isUser = msg.role === "user";
  const pushSnack = useStore((s) => s.pushSnack);

  if (isUser) {
    return (
      <div className={`${styles.wrap} ${styles.wrapUser}`}>
        <div className={`${styles.bubble} ${styles.bubbleUser}`}>{msg.content}</div>
      </div>
    );
  }
  const segments = parseSegments(msg.content);
  return (
    <div className={`${styles.wrap} ${styles.wrapAssistant}`}>
      <div className={`${styles.bubble} ${styles.bubbleAssistant}`}>
        {segments.map((seg, i) =>
          seg.kind === "think" ? (
            <details key={i} className={styles.thinking} open={!seg.closed}>
              <summary className={styles.thinkingSummary}>
                {seg.closed ? "Thinking" : "Thinking…"}
              </summary>
              <div className={styles.thinkingBody}>{seg.text.trim()}</div>
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
        <div className={styles.msgActions}>
          <button
            className={styles.actionBtn}
            onClick={async () => {
              if (await copyText(msg.content)) pushSnack("Copied message");
            }}
          >
            Copy
          </button>
          {canRegenerate && onRegenerate && (
            <button className={styles.actionBtn} onClick={onRegenerate}>
              ↻ Regenerate
            </button>
          )}
        </div>
      )}
    </div>
  );
}
