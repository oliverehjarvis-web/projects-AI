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
  setChats: (projectId: string, c: Chat[]) => void;
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
  setChats: (projectId, c) =>
    set((s) => ({ chats: { ...s.chats, [projectId]: c.filter((x) => !x.deleted_at) } })),
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
