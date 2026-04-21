import { create } from "zustand";
import type { Project, Chat, Message } from "../api/sync";

interface AppStore {
  projects: Project[];
  chats: Record<string, Chat[]>;
  messages: Record<string, Message[]>;
  activeProjectId: string | null;
  activeChatId: string | null;
  model: string;

  setProjects: (p: Project[]) => void;
  addProject: (p: Project) => void;
  removeProject: (remoteId: string) => void;
  setChats: (projectId: string, c: Chat[]) => void;
  addChat: (projectId: string, c: Chat) => void;
  removeChat: (projectId: string, remoteId: string) => void;
  setMessages: (chatId: string, m: Message[]) => void;
  appendToken: (chatId: string, messageId: string, token: string) => void;
  addMessage: (chatId: string, m: Message) => void;
  setActiveProject: (id: string | null) => void;
  setActiveChat: (id: string | null) => void;
  setModel: (m: string) => void;
}

export const useStore = create<AppStore>((set) => ({
  projects: [],
  chats: {},
  messages: {},
  activeProjectId: null,
  activeChatId: null,
  model: localStorage.getItem("model") ?? "gemma4:2b",

  setProjects: (p) => set({ projects: p.filter((x) => !x.deleted_at) }),
  addProject: (p) => set((s) => ({ projects: [p, ...s.projects.filter((x) => x.remote_id !== p.remote_id)] })),
  removeProject: (remoteId) =>
    set((s) => ({ projects: s.projects.filter((x) => x.remote_id !== remoteId) })),
  setChats: (projectId, c) =>
    set((s) => ({ chats: { ...s.chats, [projectId]: c.filter((x) => !x.deleted_at) } })),
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
    set((s) => ({ messages: { ...s.messages, [chatId]: m.filter((x) => !x.deleted_at) } })),
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
  setActiveProject: (id) => set({ activeProjectId: id }),
  setActiveChat: (id) => set({ activeChatId: id }),
  setModel: (m) => {
    localStorage.setItem("model", m);
    set({ model: m });
  },
}));
