import { create } from "zustand";
import type { Project, Chat, Message, SyncFullResult } from "../api/sync";
import type { GlobalContext } from "../api/global";

interface AppStore {
  projects: Project[];
  chats: Record<string, Chat[]>;
  messages: Record<string, Message[]>;
  activeProjectId: string | null;
  activeChatId: string | null;
  model: string;
  globalContext: GlobalContext;
  lastSyncTs: number;

  setProjects: (p: Project[]) => void;
  addProject: (p: Project) => void;
  removeProject: (remoteId: string) => void;
  setChats: (projectId: string, c: Chat[]) => void;
  addChat: (projectId: string, c: Chat) => void;
  removeChat: (projectId: string, remoteId: string) => void;
  setMessages: (chatId: string, m: Message[]) => void;
  appendToken: (chatId: string, messageId: string, token: string) => void;
  addMessage: (chatId: string, m: Message) => void;
  reconcileMessageId: (chatId: string, tmpId: string, real: Message) => void;
  setActiveProject: (id: string | null) => void;
  setActiveChat: (id: string | null) => void;
  setModel: (m: string) => void;
  setGlobalContext: (ctx: GlobalContext) => void;
  mergeSyncResult: (res: SyncFullResult, receivedAt: number) => void;
}

// Apply an incoming list (which may include tombstones) over an existing one.
// If an incoming item has `deleted_at` set, drop any local copy; otherwise
// upsert. This is the rule that makes remote deletions actually propagate — the
// previous implementation filtered tombstones *out* of the incoming list, which
// meant a delete on one device never reached the other.
function mergeWithTombstones<T extends { remote_id: string; deleted_at: number | null }>(
  existing: T[],
  incoming: T[],
): T[] {
  if (incoming.length === 0) return existing;
  const byId = new Map(existing.map((x) => [x.remote_id, x]));
  for (const item of incoming) {
    if (item.deleted_at) byId.delete(item.remote_id);
    else byId.set(item.remote_id, item);
  }
  return Array.from(byId.values());
}

function groupBy<T>(items: T[], key: (t: T) => string): Record<string, T[]> {
  const out: Record<string, T[]> = {};
  for (const item of items) {
    const k = key(item);
    (out[k] ??= []).push(item);
  }
  return out;
}

export const useStore = create<AppStore>((set) => ({
  projects: [],
  chats: {},
  messages: {},
  activeProjectId: null,
  activeChatId: null,
  model: localStorage.getItem("model") ?? "gemma4:2b",
  globalContext: { user_name: "", rules: "", updated_at: 0 },
  lastSyncTs: 0,

  // Replace-style setters still exist for initial scoped fetches, but they now
  // go through the tombstone-aware merge so a re-fetch never resurrects a
  // remotely-deleted item that's still in local state.
  setProjects: (p) =>
    set((s) => ({ projects: mergeWithTombstones(s.projects, p) })),
  addProject: (p) =>
    set((s) => ({ projects: [p, ...s.projects.filter((x) => x.remote_id !== p.remote_id)] })),
  removeProject: (remoteId) =>
    set((s) => ({ projects: s.projects.filter((x) => x.remote_id !== remoteId) })),
  setChats: (projectId, c) =>
    set((s) => ({
      chats: {
        ...s.chats,
        [projectId]: mergeWithTombstones(s.chats[projectId] ?? [], c),
      },
    })),
  addChat: (projectId, c) =>
    set((s) => ({
      chats: {
        ...s.chats,
        [projectId]: [c, ...(s.chats[projectId] ?? []).filter((x) => x.remote_id !== c.remote_id)],
      },
    })),
  removeChat: (projectId, remoteId) =>
    set((s) => ({
      chats: {
        ...s.chats,
        [projectId]: (s.chats[projectId] ?? []).filter((x) => x.remote_id !== remoteId),
      },
    })),
  setMessages: (chatId, m) =>
    set((s) => ({
      messages: {
        ...s.messages,
        [chatId]: mergeWithTombstones(s.messages[chatId] ?? [], m),
      },
    })),
  appendToken: (chatId, messageId, token) =>
    set((s) => ({
      messages: {
        ...s.messages,
        [chatId]: (s.messages[chatId] ?? []).map((m) =>
          m.remote_id === messageId ? { ...m, content: m.content + token } : m
        ),
      },
    })),
  addMessage: (chatId, m) =>
    set((s) => ({ messages: { ...s.messages, [chatId]: [...(s.messages[chatId] ?? []), m] } })),
  reconcileMessageId: (chatId, tmpId, real) =>
    set((s) => {
      const list = s.messages[chatId] ?? [];
      // Swap the optimistic entry's id for the server-assigned one so the next
      // incremental sync doesn't produce a duplicate.
      const next = list.map((m) => (m.remote_id === tmpId ? { ...real, content: m.content } : m));
      return { messages: { ...s.messages, [chatId]: next } };
    }),
  setActiveProject: (id) => set({ activeProjectId: id }),
  setActiveChat: (id) => set({ activeChatId: id }),
  setModel: (m) => {
    localStorage.setItem("model", m);
    set({ model: m });
  },
  setGlobalContext: (ctx) => set({ globalContext: ctx }),
  mergeSyncResult: (res, receivedAt) =>
    set((s) => {
      const projects = mergeWithTombstones(s.projects, res.projects);
      const chatsByProject = groupBy(res.chats, (c) => c.project_remote_id);
      const chats = { ...s.chats };
      for (const [pid, list] of Object.entries(chatsByProject)) {
        chats[pid] = mergeWithTombstones(chats[pid] ?? [], list);
      }
      const messagesByChat = groupBy(res.messages, (m) => m.chat_remote_id);
      const messages = { ...s.messages };
      for (const [cid, list] of Object.entries(messagesByChat)) {
        // Incremental sync includes tombstones and updates. The store is the
        // source-of-truth for streaming partials keyed by tmp- ids, so we merge
        // by remote_id and leave any unseen local entries intact.
        messages[cid] = mergeWithTombstones(messages[cid] ?? [], list);
      }
      return { projects, chats, messages, lastSyncTs: receivedAt };
    }),
}));
