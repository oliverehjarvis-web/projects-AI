import { apiFetch } from "./client";

export interface Project {
  remote_id: string;
  name: string;
  description: string;
  manual_context: string;
  accumulated_memory: string;
  pinned_memories: string;
  is_secret: boolean;
  preferred_backend: string;
  memory_token_limit: number;
  context_length: number;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
}

export interface Chat {
  remote_id: string;
  project_remote_id: string;
  title: string;
  web_search_enabled: boolean;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
}

export interface Message {
  remote_id: string;
  chat_remote_id: string;
  role: string;
  content: string;
  token_count: number;
  created_at: number;
  deleted_at: number | null;
}

export async function fetchProjects(): Promise<Project[]> {
  const r = await apiFetch("/v1/sync/projects?since=0");
  return r.json();
}

export async function fetchChats(projectRemoteId: string): Promise<Chat[]> {
  const r = await apiFetch(`/v1/sync/chats?since=0&project_remote_id=${projectRemoteId}`);
  return r.json();
}

export async function fetchMessages(chatRemoteId: string): Promise<Message[]> {
  const r = await apiFetch(`/v1/sync/messages?since=0&chat_remote_id=${chatRemoteId}`);
  return r.json();
}

export async function pushMessage(msg: Omit<Message, "remote_id">): Promise<string> {
  const now = Date.now();
  const item = { ...msg, remote_id: null, updated_at: now };
  const r = await apiFetch("/v1/sync/messages", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
  const data = await r.json();
  return data.remote_ids[0] as string;
}

export async function createProject(name: string, description: string): Promise<Project> {
  const now = Date.now();
  const item = {
    remote_id: null,
    name,
    description,
    manual_context: "",
    accumulated_memory: "",
    pinned_memories: "",
    preferred_backend: "REMOTE",
    memory_token_limit: 4000,
    context_length: 16384,
    is_secret: false,
    created_at: now,
    updated_at: now,
    deleted_at: null,
  };
  const r = await apiFetch("/v1/sync/projects", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
  const data = await r.json();
  return { ...item, remote_id: data.remote_ids[0] } as Project;
}

export async function createChat(projectRemoteId: string, title = "New Chat"): Promise<Chat> {
  const now = Date.now();
  const item = {
    remote_id: null,
    project_remote_id: projectRemoteId,
    title,
    web_search_enabled: false,
    created_at: now,
    updated_at: now,
    deleted_at: null,
  };
  const r = await apiFetch("/v1/sync/chats", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
  const data = await r.json();
  return { ...item, remote_id: data.remote_ids[0] } as Chat;
}

export async function updateChat(chat: Chat): Promise<void> {
  const now = Date.now();
  const item = { ...chat, updated_at: now };
  await apiFetch("/v1/sync/chats", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
}

export async function deleteChat(chat: Chat): Promise<void> {
  const now = Date.now();
  const item = { ...chat, updated_at: now, deleted_at: now };
  await apiFetch("/v1/sync/chats", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
}

export async function updateProject(project: Project): Promise<void> {
  const now = Date.now();
  const item = { ...project, updated_at: now };
  await apiFetch("/v1/sync/projects", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
}

export async function deleteProject(project: Project): Promise<void> {
  const now = Date.now();
  const item = { ...project, updated_at: now, deleted_at: now };
  await apiFetch("/v1/sync/projects", {
    method: "PUT",
    body: JSON.stringify({ items: [item] }),
  });
}

export async function checkHealth(): Promise<{ status: string; ollama: string }> {
  const r = await apiFetch("/v1/health");
  return r.json();
}
