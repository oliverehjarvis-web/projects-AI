import { create } from "zustand";
import type { Project, Chat, Message, SyncFullResult, QuickAction } from "../api/sync";
import type { GlobalContext } from "../api/global";

export interface StagedRepoFile {
  path: string;
  size_bytes: number;
  text: string;
}

export interface StagedRepoSelection {
  owner: string;
  repo: string;
  ref: string;
  files: StagedRepoFile[];
}

export interface Snack {
  id: string;
  message: string;
  tone?: "neutral" | "error";
  durationMs?: number;
}

interface AppStore {
  projects: Project[];
  chats: Record<string, Chat[]>;
  messages: Record<string, Message[]>;
  /** quick actions keyed by project_remote_id */
  quickActions: Record<string, QuickAction[]>;
  /** files attached to the next outgoing message in a given chat */
  stagedRepoFiles: Record<string, StagedRepoSelection>;
  /** floating snackbar queue, drained by SnackbarHost */
  snacks: Snack[];
  activeProjectId: string | null;
  activeChatId: string | null;
  model: string;
  globalContext: GlobalContext;
  lastSyncTs: number;
  /** Local-only preferences mirrored from localStorage on boot. */
  searxngUrl: string;
  githubPat: string;
  githubDefaultRepo: string;

  setProjects: (p: Project[]) => void;
  addProject: (p: Project) => void;
  removeProject: (remoteId: string) => void;
  setChats: (projectId: string, c: Chat[]) => void;
  addChat: (projectId: string, c: Chat) => void;
  removeChat: (projectId: string, remoteId: string) => void;
  setMessages: (chatId: string, m: Message[]) => void;
  removeMessage: (chatId: string, remoteId: string) => void;
  appendToken: (chatId: string, messageId: string, token: string) => void;
  addMessage: (chatId: string, m: Message) => void;
  reconcileMessageId: (chatId: string, tmpId: string, real: Message) => void;
  setActiveProject: (id: string | null) => void;
  setActiveChat: (id: string | null) => void;
  setModel: (m: string) => void;
  setGlobalContext: (ctx: GlobalContext) => void;
  mergeSyncResult: (res: SyncFullResult, receivedAt: number) => void;

  setQuickActions: (projectId: string, qa: QuickAction[]) => void;
  upsertQuickAction: (projectId: string, qa: QuickAction) => void;
  removeQuickAction: (projectId: string, remoteId: string) => void;

  stageRepoFiles: (chatId: string, sel: StagedRepoSelection) => void;
  clearStagedRepoFiles: (chatId: string) => void;

  pushSnack: (message: string, opts?: { tone?: "neutral" | "error"; durationMs?: number }) => void;
  dismissSnack: (id: string) => void;

  setSearxngUrl: (url: string) => void;
  setGithubPat: (pat: string) => void;
  setGithubDefaultRepo: (repo: string) => void;
}

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
  quickActions: {},
  stagedRepoFiles: {},
  snacks: [],
  activeProjectId: null,
  activeChatId: null,
  model: localStorage.getItem("model") ?? "gemma4:2b",
  globalContext: { user_name: "", rules: "", updated_at: 0 },
  lastSyncTs: 0,
  searxngUrl: localStorage.getItem("searxng_url") ?? "",
  githubPat: localStorage.getItem("github_pat") ?? "",
  githubDefaultRepo: localStorage.getItem("github_default_repo") ?? "",

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
  removeMessage: (chatId, remoteId) =>
    set((s) => ({
      messages: {
        ...s.messages,
        [chatId]: (s.messages[chatId] ?? []).filter((m) => m.remote_id !== remoteId),
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
        messages[cid] = mergeWithTombstones(messages[cid] ?? [], list);
      }
      const qaByProject = groupBy(res.quick_actions ?? [], (q) => q.project_remote_id);
      const quickActions = { ...s.quickActions };
      for (const [pid, list] of Object.entries(qaByProject)) {
        quickActions[pid] = mergeWithTombstones(quickActions[pid] ?? [], list);
      }
      return { projects, chats, messages, quickActions, lastSyncTs: receivedAt };
    }),

  setQuickActions: (projectId, qa) =>
    set((s) => ({
      quickActions: {
        ...s.quickActions,
        [projectId]: mergeWithTombstones(s.quickActions[projectId] ?? [], qa),
      },
    })),
  upsertQuickAction: (projectId, qa) =>
    set((s) => ({
      quickActions: {
        ...s.quickActions,
        [projectId]: [
          qa,
          ...(s.quickActions[projectId] ?? []).filter((x) => x.remote_id !== qa.remote_id),
        ],
      },
    })),
  removeQuickAction: (projectId, remoteId) =>
    set((s) => ({
      quickActions: {
        ...s.quickActions,
        [projectId]: (s.quickActions[projectId] ?? []).filter((x) => x.remote_id !== remoteId),
      },
    })),

  stageRepoFiles: (chatId, sel) =>
    set((s) => ({ stagedRepoFiles: { ...s.stagedRepoFiles, [chatId]: sel } })),
  clearStagedRepoFiles: (chatId) =>
    set((s) => {
      const next = { ...s.stagedRepoFiles };
      delete next[chatId];
      return { stagedRepoFiles: next };
    }),

  pushSnack: (message, opts) =>
    set((s) => ({
      snacks: [
        ...s.snacks,
        { id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, message, ...opts },
      ],
    })),
  dismissSnack: (id) => set((s) => ({ snacks: s.snacks.filter((x) => x.id !== id) })),

  setSearxngUrl: (url) => {
    localStorage.setItem("searxng_url", url);
    set({ searxngUrl: url });
  },
  setGithubPat: (pat) => {
    localStorage.setItem("github_pat", pat);
    set({ githubPat: pat });
  },
  setGithubDefaultRepo: (repo) => {
    localStorage.setItem("github_default_repo", repo);
    set({ githubDefaultRepo: repo });
  },
}));
