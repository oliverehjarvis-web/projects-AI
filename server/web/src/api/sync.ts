import { apiFetch } from "./client";

export interface Project {
  remote_id: string;
  name: string;
  description: string;
  manual_context: string;
  accumulated_memory: string;
  is_secret: boolean;
  preferred_backend: string;
  updated_at: number;
  deleted_at: number | null;
}

export interface Chat {
  remote_id: string;
  project_remote_id: string;
  title: string;
  web_search_enabled: boolean;
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

export async function checkHealth(): Promise<{ status: string; ollama: string }> {
  const r = await apiFetch("/v1/health");
  return r.json();
}
