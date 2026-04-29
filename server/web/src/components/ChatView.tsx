import { useEffect, useRef, useState, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import {
  fetchMessages,
  pushMessage,
  updateChat,
  deleteMessage,
} from "../api/sync";
import { streamGenerate } from "../api/inference";
import MessageBubble from "./MessageBubble";
import RepoBrowser from "./RepoBrowser";
import IconButton from "../ui/IconButton";
import Card from "../ui/Card";
import { palette, radius, space, font } from "../theme";
import type { Message } from "../api/sync";

const styles: Record<string, React.CSSProperties> = {
  page: { display: "flex", flexDirection: "column", height: "100%", background: palette.bg },
  header: {
    padding: "10px 20px",
    borderBottom: `1px solid ${palette.border}`,
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    gap: space.md,
  },
  headerTitle: { fontWeight: 600, color: palette.text, fontSize: 15, flex: 1, minWidth: 0,
    overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  toolbar: { display: "flex", alignItems: "center", gap: 4 },
  messages: {
    flex: 1,
    overflowY: "auto",
    padding: `${space.lg}px ${space.xl}px`,
    position: "relative",
  },
  inputRow: {
    padding: "12px 20px",
    borderTop: `1px solid ${palette.border}`,
    display: "flex",
    flexDirection: "column",
    gap: space.sm,
  },
  composer: { display: "flex", gap: space.sm, alignItems: "flex-end" },
  textarea: {
    flex: 1,
    background: palette.surface,
    border: `1px solid ${palette.border}`,
    borderRadius: radius.md,
    color: palette.text,
    padding: "10px 14px",
    fontSize: font.body,
    resize: "none",
    outline: "none",
    fontFamily: "inherit",
  },
  sendBtn: {
    background: palette.primary,
    color: palette.onPrimary,
    border: "none",
    borderRadius: radius.md,
    padding: "10px 18px",
    cursor: "pointer",
    fontWeight: 600,
    fontSize: 14,
  },
  jumpToLatest: {
    position: "absolute",
    bottom: 12,
    left: "50%",
    transform: "translateX(-50%)",
    background: palette.surfaceElevated,
    color: palette.text,
    border: `1px solid ${palette.borderStrong}`,
    borderRadius: radius.pill,
    padding: "6px 14px",
    cursor: "pointer",
    fontSize: 12,
    boxShadow: "0 4px 12px rgba(0,0,0,0.4)",
  },
};

export default function ChatView() {
  const { projectId, chatId } = useParams<{ projectId: string; chatId: string }>();
  const nav = useNavigate();
  const {
    projects, chats, messages, setMessages, addMessage, appendToken, reconcileMessageId,
    removeMessage, model, globalContext, searxngUrl,
    stagedRepoFiles, clearStagedRepoFiles, pushSnack,
  } = useStore();
  const project = projects.find((p) => p.remote_id === projectId);
  const chat = chats[projectId ?? ""]?.find((c) => c.remote_id === chatId);
  const msgList = chatId ? (messages[chatId] ?? []) : [];
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [thinkingSince, setThinkingSince] = useState<number | null>(null);
  const [thinkingElapsed, setThinkingElapsed] = useState(0);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [showJumpButton, setShowJumpButton] = useState(false);
  const [showRepoBrowser, setShowRepoBrowser] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const lastStreamingIdRef = useRef<string | null>(null);
  const stagedFiles = chatId ? stagedRepoFiles[chatId] : undefined;

  useEffect(() => {
    if (thinkingSince === null) { setThinkingElapsed(0); return; }
    const id = setInterval(
      () => setThinkingElapsed(Math.floor((Date.now() - thinkingSince) / 1000)),
      1000,
    );
    return () => clearInterval(id);
  }, [thinkingSince]);

  useEffect(() => {
    if (!chatId) return;
    fetchMessages(chatId).then((m) => setMessages(chatId, m)).catch(console.error);
  }, [chatId, setMessages]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [msgList.length]);

  // "Jump to latest" pill: visible when the user has scrolled away from the bottom
  // mid-stream. Leaves a small slack so micro-jitters don't toggle it on every paint.
  useEffect(() => {
    const el = messagesRef.current;
    if (!el) return;
    const onScroll = () => {
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
      setShowJumpButton(distance > 80);
    };
    el.addEventListener("scroll", onScroll);
    onScroll();
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  const buildSystemPrompt = useCallback((): string => {
    const parts: string[] = [];
    if (project?.manual_context.trim()) {
      parts.push(`<project_context>\n${project.manual_context}\n</project_context>`);
    }
    if (project?.accumulated_memory.trim()) {
      parts.push(`<memory>\n${project.accumulated_memory}\n</memory>`);
    }
    if (stagedFiles && stagedFiles.files.length > 0) {
      const body = stagedFiles.files
        .map((f) => `<file path="${f.path}">\n${f.text}\n</file>`)
        .join("\n");
      parts.push(
        `<repo_context owner="${stagedFiles.owner}" repo="${stagedFiles.repo}" ref="${stagedFiles.ref}">\n${body}\n</repo_context>`,
      );
    }
    return parts.join("\n\n");
  }, [project, stagedFiles]);

  const startGenerationFromHistory = useCallback(async (history: Message[]) => {
    if (!chatId) return;
    setStreaming(true);
    setStreamError(null);
    setThinkingSince(Date.now());

    const now = Date.now();
    const streamingId = `tmp-assistant-${now}`;
    lastStreamingIdRef.current = streamingId;
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
    const systemPrompt = buildSystemPrompt();
    try {
      await streamGenerate(
        {
          systemPrompt,
          messages: history,
          model,
          userName: globalContext.user_name,
          globalRules: globalContext.rules,
          numCtx: project?.context_length,
        },
        (token) => {
          setThinkingSince(null);
          appendToken(chatId, streamingId, token);
        },
        () => { setStreaming(false); setThinkingSince(null); },
        abortRef.current.signal,
      );
      const streamed = useStore.getState().messages[chatId]?.find((m) => m.remote_id === streamingId);
      if (streamed && streamed.content.trim()) {
        const { remote_id: _aid, ...asstWithoutId } = streamed;
        pushMessage(asstWithoutId)
          .then((realId) => reconcileMessageId(chatId, streamingId, { ...streamed, remote_id: realId }))
          .catch(console.error);
      }
      // Repo files attach to one turn only — clear after the streaming completes
      // (success or error) so the next message doesn't re-include them.
      if (stagedFiles && chatId) clearStagedRepoFiles(chatId);
    } catch (e) {
      setStreaming(false);
      setThinkingSince(null);
      const msg = e instanceof Error ? e.message : String(e);
      if (!/abort/i.test(msg)) setStreamError(msg);
    }
  }, [chatId, buildSystemPrompt, model, globalContext, project, addMessage, appendToken,
      reconcileMessageId, stagedFiles, clearStagedRepoFiles]);

  const send = useCallback(async () => {
    if (!input.trim() || !chatId || !projectId || streaming) return;
    const text = input.trim();
    setInput("");

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
    const tmpUserId = userMsg.remote_id;
    const { remote_id: _rid, ...userMsgWithoutId } = userMsg;
    pushMessage(userMsgWithoutId)
      .then((realId) => reconcileMessageId(chatId, tmpUserId, { ...userMsg, remote_id: realId }))
      .catch(console.error);
    const fullHistory = [...msgList, userMsg];
    await startGenerationFromHistory(fullHistory);
  }, [input, chatId, projectId, streaming, msgList, addMessage, reconcileMessageId,
      startGenerationFromHistory]);

  const regenerateLast = useCallback(async () => {
    if (!chatId || streaming) return;
    const lastAssistant = [...msgList].reverse().find((m) => m.role === "assistant");
    if (!lastAssistant) return;
    // Drop the assistant turn locally and on the server, then restream against the
    // (now-shorter) history. The user's last message is preserved.
    removeMessage(chatId, lastAssistant.remote_id);
    if (!lastAssistant.remote_id.startsWith("tmp-")) {
      deleteMessage(lastAssistant).catch(console.error);
    }
    const trimmed = msgList.filter((m) => m.remote_id !== lastAssistant.remote_id);
    await startGenerationFromHistory(trimmed);
  }, [chatId, streaming, msgList, removeMessage, startGenerationFromHistory]);

  const toggleWebSearch = useCallback(async () => {
    if (!chat || !projectId) return;
    const next = !chat.web_search_enabled;
    try {
      await updateChat({ ...chat, web_search_enabled: next });
      // Optimistically reflect locally so the toggle doesn't wait for the next sync tick.
      useStore.getState().addChat(projectId, { ...chat, web_search_enabled: next, updated_at: Date.now() });
    } catch (e) {
      pushSnack(`Couldn't update chat: ${e}`, { tone: "error" });
      return;
    }
    if (next && !searxngUrl.trim()) {
      pushSnack(
        "Web search is on but no SearXNG URL is set. Add one in Settings → Web search.",
        { tone: "error", durationMs: 5000 },
      );
    }
  }, [chat, projectId, searxngUrl, pushSnack]);

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  };

  if (project?.is_secret) {
    return <div style={{ padding: 24, color: palette.textDim }}>Chat not found.</div>;
  }
  if (!project || !chat || !chatId) {
    return <div style={{ padding: 24, color: palette.textDim }}>Loading…</div>;
  }

  const filtered = msgList.filter((m) => m.role !== "system");
  const lastAssistantIdx = (() => {
    for (let i = filtered.length - 1; i >= 0; i--) {
      if (filtered[i].role === "assistant") return i;
    }
    return -1;
  })();

  return (
    <div style={styles.page}>
      <div style={styles.header}>
        <span style={styles.headerTitle}>{chat.title}</span>
        <div style={styles.toolbar}>
          <IconButton
            title="Browse GitHub repo"
            onClick={() => setShowRepoBrowser(true)}
          >
            🗂
          </IconButton>
          <IconButton
            title={chat.web_search_enabled ? "Web search on" : "Web search off"}
            active={chat.web_search_enabled}
            onClick={toggleWebSearch}
          >
            🌐
          </IconButton>
          <IconButton
            title="Project memory"
            onClick={() => nav(`/projects/${projectId}/memory`)}
          >
            🧠
          </IconButton>
          <IconButton
            title="Project settings"
            onClick={() => nav(`/projects/${projectId}`)}
          >
            ⋯
          </IconButton>
        </div>
      </div>
      <div style={styles.messages} ref={messagesRef}>
        {filtered.map((m, i) => (
          <MessageBubble
            key={m.remote_id}
            msg={m}
            canRegenerate={i === lastAssistantIdx && !streaming}
            onRegenerate={regenerateLast}
            isStreaming={streaming && m.remote_id === lastStreamingIdRef.current}
          />
        ))}
        {streaming && thinkingSince !== null && (
          <div style={{ color: palette.textDim, fontSize: 13, padding: "8px 4px", fontStyle: "italic" }}>
            {thinkingElapsed < 5
              ? "Thinking…"
              : thinkingElapsed < 20
                ? `Processing prompt… ${thinkingElapsed}s`
                : `Still working… ${thinkingElapsed}s (large prompts can take a few minutes on CPU)`}
          </div>
        )}
        {streamError && (
          <Card tone="error" padding={12} style={{ margin: "8px 0", fontSize: 13 }}>
            {streamError}
          </Card>
        )}
        <div ref={bottomRef} />
        {showJumpButton && (
          <button
            style={styles.jumpToLatest}
            onClick={() => bottomRef.current?.scrollIntoView({ behavior: "smooth" })}
          >
            Jump to latest ↓
          </button>
        )}
      </div>
      {stagedFiles && stagedFiles.files.length > 0 && (
        <div style={{ padding: "0 20px" }}>
          <Card tone="primary" padding={10} style={{ marginBottom: 8 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 13 }}>
              <span>📎</span>
              <div style={{ flex: 1 }}>
                <div style={{ color: palette.text, fontWeight: 500 }}>
                  {stagedFiles.files.length} file{stagedFiles.files.length === 1 ? "" : "s"} from{" "}
                  {stagedFiles.owner}/{stagedFiles.repo}
                </div>
                <div style={{ color: palette.textMuted, fontSize: 11 }}>
                  Attached to your next message · ≈
                  {Math.ceil(stagedFiles.files.reduce((a, f) => a + f.text.length, 0) / 4 / 1000)}k tokens
                </div>
              </div>
              <button
                style={{
                  background: "transparent",
                  border: `1px solid ${palette.border}`,
                  color: palette.textMuted,
                  borderRadius: radius.sm,
                  padding: "4px 10px",
                  fontSize: 12,
                  cursor: "pointer",
                }}
                onClick={() => chatId && clearStagedRepoFiles(chatId)}
              >
                Clear
              </button>
            </div>
          </Card>
        </div>
      )}
      <div style={styles.inputRow}>
        <div style={styles.composer}>
          <textarea
            style={styles.textarea}
            rows={2}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKey}
            placeholder="Message… (Enter to send, Shift+Enter for newline)"
            disabled={streaming}
          />
          <button
            style={{
              ...styles.sendBtn,
              ...(streaming ? { background: palette.surfaceVariant } : {}),
            }}
            onClick={streaming ? () => abortRef.current?.abort() : send}
          >
            {streaming ? "Stop" : "Send"}
          </button>
        </div>
      </div>
      {showRepoBrowser && projectId && chatId && (
        <RepoBrowser
          chatId={chatId}
          projectContextTokens={project.context_length}
          onClose={() => setShowRepoBrowser(false)}
          onInjected={() => {
            setShowRepoBrowser(false);
            pushSnack("Files attached to your next message");
          }}
        />
      )}
    </div>
  );
}
