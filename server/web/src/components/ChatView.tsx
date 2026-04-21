import { useEffect, useRef, useState, useCallback } from "react";
import { useParams } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchMessages, pushMessage } from "../api/sync";
import { streamGenerate } from "../api/inference";
import MessageBubble from "./MessageBubble";
import type { Message } from "../api/sync";

const s: Record<string, React.CSSProperties> = {
  page: { display: "flex", flexDirection: "column", height: "100%" },
  header: { padding: "12px 20px", borderBottom: "1px solid #2a2a2a", fontWeight: 600, color: "#fff" },
  messages: { flex: 1, overflowY: "auto", padding: "16px 20px" },
  inputRow: { padding: "12px 20px", borderTop: "1px solid #2a2a2a", display: "flex", gap: 8 },
  textarea: {
    flex: 1, background: "#1a1a1a", border: "1px solid #2a2a2a", borderRadius: 10,
    color: "#e0e0e0", padding: "10px 14px", fontSize: 14, resize: "none", outline: "none",
  },
  btn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 10,
    padding: "10px 18px", cursor: "pointer", fontWeight: 600, fontSize: 14,
  },
  btnDisabled: { background: "#333", cursor: "not-allowed" },
};

export default function ChatView() {
  const { projectId, chatId } = useParams<{ projectId: string; chatId: string }>();
  const { projects, chats, messages, setMessages, addMessage, appendToken, model } = useStore();
  const project = projects.find((p) => p.remote_id === projectId);
  const chat = chats[projectId ?? ""]?.find((c) => c.remote_id === chatId);
  const msgList = chatId ? (messages[chatId] ?? []) : [];
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!chatId) return;
    fetchMessages(chatId).then((m) => setMessages(chatId, m)).catch(console.error);
  }, [chatId, setMessages]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [msgList.length]);

  const send = useCallback(async () => {
    if (!input.trim() || !chatId || !projectId || streaming) return;
    const text = input.trim();
    setInput("");
    setStreaming(true);

    const now = Date.now();
    const userMsg: Message = {
      remote_id: `tmp-user-${now}`,
      chat_remote_id: chatId,
      role: "user",
      content: text,
      token_count: 0,
      created_at: now,
      deleted_at: null,
    };
    addMessage(chatId, userMsg);
    const { remote_id: _rid, ...userMsgWithoutId } = userMsg;
    pushMessage(userMsgWithoutId).catch(console.error);

    const streamingId = `tmp-assistant-${now}`;
    const assistantMsg: Message = {
      remote_id: streamingId,
      chat_remote_id: chatId,
      role: "assistant",
      content: "",
      token_count: 0,
      created_at: now + 1,
      deleted_at: null,
    };
    addMessage(chatId, assistantMsg);

    abortRef.current = new AbortController();
    const systemPrompt = [project?.manual_context, project?.accumulated_memory]
      .filter(Boolean)
      .join("\n\n");

    try {
      await streamGenerate(
        systemPrompt,
        [...msgList, userMsg],
        model,
        (token) => appendToken(chatId, streamingId, token),
        () => setStreaming(false),
        abortRef.current.signal
      );
    } catch {
      setStreaming(false);
    }
  }, [input, chatId, projectId, streaming, msgList, project, model, addMessage, appendToken]);

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  };

  return (
    <div style={s.page}>
      <div style={s.header}>{chat?.title ?? "Chat"}</div>
      <div style={s.messages}>
        {msgList.filter((m) => m.role !== "system").map((m) => (
          <MessageBubble key={m.remote_id} msg={m} />
        ))}
        <div ref={bottomRef} />
      </div>
      <div style={s.inputRow}>
        <textarea
          style={s.textarea}
          rows={2}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
          placeholder="Message… (Enter to send, Shift+Enter for newline)"
          disabled={streaming}
        />
        <button
          style={{ ...s.btn, ...(streaming ? s.btnDisabled : {}) }}
          onClick={streaming ? () => abortRef.current?.abort() : send}
        >
          {streaming ? "Stop" : "Send"}
        </button>
      </div>
    </div>
  );
}
