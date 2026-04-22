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
  const { projects, chats, messages, setMessages, addMessage, appendToken, reconcileMessageId, model, globalContext } = useStore();
  const project = projects.find((p) => p.remote_id === projectId);
  const chat = chats[projectId ?? ""]?.find((c) => c.remote_id === chatId);
  const msgList = chatId ? (messages[chatId] ?? []) : [];
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [thinkingSince, setThinkingSince] = useState<number | null>(null);
  const [thinkingElapsed, setThinkingElapsed] = useState(0);
  const [streamError, setStreamError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (thinkingSince === null) { setThinkingElapsed(0); return; }
    const id = setInterval(() => setThinkingElapsed(Math.floor((Date.now() - thinkingSince) / 1000)), 1000);
    return () => clearInterval(id);
  }, [thinkingSince]);

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
    setStreamError(null);
    setThinkingSince(Date.now());

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
    // Push immediately so the server persists the user's turn. Reconcile the
    // tmp id back so the next /v1/sync/full doesn't see the real server entry
    // as a new item and duplicate it alongside the optimistic one.
    const tmpUserId = userMsg.remote_id;
    const { remote_id: _rid, ...userMsgWithoutId } = userMsg;
    pushMessage(userMsgWithoutId)
      .then((realId) => {
        reconcileMessageId(chatId, tmpUserId, { ...userMsg, remote_id: realId });
      })
      .catch(console.error);

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
        {
          systemPrompt,
          messages: [...msgList, userMsg],
          model,
          userName: globalContext.user_name,
          globalRules: globalContext.rules,
        },
        (token) => {
          if (thinkingSince !== null) setThinkingSince(null);
          appendToken(chatId, streamingId, token);
        },
        () => { setStreaming(false); setThinkingSince(null); },
        abortRef.current.signal
      );
      // Persist the assistant's reply. Until this call, the reply only lives
      // in memory — without it, the web UI quietly dropped every response on
      // refresh and never synced them to the phone.
      const streamed = useStore.getState().messages[chatId]?.find((m) => m.remote_id === streamingId);
      if (streamed && streamed.content.trim()) {
        const { remote_id: _aid, ...asstWithoutId } = streamed;
        pushMessage(asstWithoutId)
          .then((realId) => reconcileMessageId(chatId, streamingId, { ...streamed, remote_id: realId }))
          .catch(console.error);
      }
    } catch (e) {
      setStreaming(false);
      setThinkingSince(null);
      const msg = e instanceof Error ? e.message : String(e);
      if (!/abort/i.test(msg)) setStreamError(msg);
    }
  }, [input, chatId, projectId, streaming, msgList, project, model, globalContext, addMessage, appendToken, reconcileMessageId, thinkingSince]);

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  };

  if (project?.is_secret) {
    return <div style={{ padding: 24, color: "#888" }}>Chat not found.</div>;
  }

  return (
    <div style={s.page}>
      <div style={s.header}>{chat?.title ?? "Chat"}</div>
      <div style={s.messages}>
        {msgList.filter((m) => m.role !== "system").map((m) => (
          <MessageBubble key={m.remote_id} msg={m} />
        ))}
        {streaming && thinkingSince !== null && (
          <div style={{ color: "#888", fontSize: 13, padding: "8px 4px", fontStyle: "italic" }}>
            {thinkingElapsed < 5
              ? "Thinking…"
              : thinkingElapsed < 20
                ? `Processing prompt… ${thinkingElapsed}s`
                : `Still working… ${thinkingElapsed}s (large prompts can take a few minutes on CPU)`}
          </div>
        )}
        {streamError && (
          <div style={{
            background: "#3a1d1d", border: "1px solid #5a2a2a", color: "#f0a0a0",
            borderRadius: 8, padding: "10px 12px", margin: "8px 0", fontSize: 13,
          }}>
            {streamError}
          </div>
        )}
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
