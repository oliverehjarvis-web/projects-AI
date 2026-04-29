import { useEffect, useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import {
  fetchChats,
  fetchQuickActions,
  createChat,
  deleteChat,
  updateProject,
  upsertQuickAction,
  deleteQuickAction,
  pushMessage,
} from "../api/sync";
import type { Project, QuickAction } from "../api/sync";
import { refineText } from "../api/refine";
import { fetchServerInfo, recommendContextLength, PICKER_STEPS } from "../api/serverInfo";
import Button from "../ui/Button";
import Card from "../ui/Card";
import Section from "../ui/Section";
import { Label, Hint, TextInput, TextArea } from "../ui/Field";
import Dialog from "../ui/Dialog";
import { palette, radius, space, font } from "../theme";

export default function ProjectDetail() {
  const { projectId } = useParams<{ projectId: string }>();
  const {
    projects, chats, quickActions, setChats, addChat, removeChat, addProject,
    setQuickActions, upsertQuickAction: storeUpsertQA, removeQuickAction,
    model, pushSnack,
  } = useStore();
  const nav = useNavigate();
  const project = projects.find((p) => p.remote_id === projectId);
  const chatList = projectId ? (chats[projectId] ?? []) : [];
  const qaList = projectId ? (quickActions[projectId] ?? []) : [];
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Project | null>(null);
  const [saving, setSaving] = useState(false);
  const [refining, setRefining] = useState(false);
  const [autoBusy, setAutoBusy] = useState(false);
  const [autoHint, setAutoHint] = useState<string | null>(null);
  const [editingQa, setEditingQa] = useState<QuickAction | null>(null);
  const [showNewQaDialog, setShowNewQaDialog] = useState(false);

  useEffect(() => {
    if (!projectId) return;
    fetchChats(projectId).then((c) => setChats(projectId, c)).catch(console.error);
    fetchQuickActions(projectId).then((q) => setQuickActions(projectId, q)).catch(console.error);
  }, [projectId, setChats, setQuickActions]);

  if (!project) return <div style={{ padding: 24, color: palette.textDim }}>Project not found.</div>;
  if (project.is_secret) return <div style={{ padding: 24, color: palette.textDim }}>Project not found.</div>;

  const startEdit = () => { setDraft({ ...project }); setEditing(true); setAutoHint(null); };
  const cancelEdit = () => { setEditing(false); setDraft(null); };

  const saveEdit = async () => {
    if (!draft || !draft.name.trim()) return;
    setSaving(true);
    try {
      await updateProject(draft);
      addProject({ ...draft, updated_at: Date.now() });
      setEditing(false);
      setDraft(null);
      pushSnack("Project saved");
    } catch (err) {
      pushSnack(`Save failed: ${err}`, { tone: "error" });
    } finally {
      setSaving(false);
    }
  };

  const handleNewChat = async () => {
    if (!projectId) return;
    try {
      const chat = await createChat(projectId, "New chat");
      addChat(projectId, chat);
      nav(`/projects/${projectId}/chats/${chat.remote_id}`);
    } catch (e) {
      pushSnack(`Couldn't create chat: ${e}`, { tone: "error" });
    }
  };

  const handleDeleteChat = async (e: React.MouseEvent, chat: (typeof chatList)[number]) => {
    e.stopPropagation();
    if (!confirm(`Delete "${chat.title}"?`)) return;
    try {
      await deleteChat(chat);
      if (projectId) removeChat(projectId, chat.remote_id);
    } catch (err) {
      pushSnack(`Delete failed: ${err}`, { tone: "error" });
    }
  };

  const refineContext = async () => {
    if (!draft || !draft.manual_context.trim()) return;
    setRefining(true);
    try {
      const out = await refineText({
        raw: draft.manual_context,
        model,
        numCtx: draft.context_length,
        kind: "context",
      });
      if (out.trim()) {
        setDraft({ ...draft, manual_context: out });
        pushSnack("Context refined");
      }
    } catch (e) {
      pushSnack(`Refine failed: ${e}`, { tone: "error" });
    } finally {
      setRefining(false);
    }
  };

  const autoFillContext = async () => {
    if (!draft) return;
    setAutoBusy(true);
    try {
      const info = await fetchServerInfo(model);
      const rec = recommendContextLength(info);
      setDraft({ ...draft, context_length: rec.tokens });
      setAutoHint(`Auto-picked ${rec.tokens / 1024}k. ${rec.rationale}`);
    } catch (e) {
      pushSnack(`Auto-pick failed: ${e}`, { tone: "error" });
    } finally {
      setAutoBusy(false);
    }
  };

  const handleRunQuickAction = async (qa: QuickAction) => {
    if (!projectId) return;
    try {
      const chat = await createChat(projectId, qa.name);
      addChat(projectId, chat);
      // Persist the user turn server-side so the message survives a refresh, then route to the
      // chat. ChatView reads the seeded prompt from location state and auto-fires generation
      // with applyDefaultPreamble=false — matches Android's NEW_CHAT quickActionId path.
      const now = Date.now();
      await pushMessage({
        chat_remote_id: chat.remote_id,
        role: "user",
        content: qa.prompt_template,
        token_count: 0,
        created_at: now,
        deleted_at: null,
      });
      nav(`/projects/${projectId}/chats/${chat.remote_id}`, {
        state: { quickActionPrompt: qa.prompt_template },
      });
    } catch (e) {
      pushSnack(`Run failed: ${e}`, { tone: "error" });
    }
  };

  const handleSaveQa = async (qa: Partial<QuickAction>) => {
    if (!projectId) return;
    const now = Date.now();
    const baseExisting = editingQa;
    const item: Omit<QuickAction, "remote_id"> & { remote_id?: string | null } = {
      remote_id: baseExisting?.remote_id ?? null,
      project_remote_id: projectId,
      name: qa.name ?? "",
      prompt_template: qa.prompt_template ?? "",
      sort_order: baseExisting?.sort_order ?? qaList.length,
      created_at: baseExisting?.created_at ?? now,
      updated_at: now,
      deleted_at: null,
    };
    try {
      const saved = await upsertQuickAction(item);
      storeUpsertQA(projectId, saved);
      pushSnack(baseExisting ? "Action updated" : "Action created");
    } catch (e) {
      pushSnack(`Save failed: ${e}`, { tone: "error" });
    } finally {
      setEditingQa(null);
      setShowNewQaDialog(false);
    }
  };

  const handleDeleteQa = async (qa: QuickAction) => {
    if (!projectId) return;
    if (!confirm(`Delete action "${qa.name}"?`)) return;
    try {
      await deleteQuickAction(qa);
      removeQuickAction(projectId, qa.remote_id);
    } catch (e) {
      pushSnack(`Delete failed: ${e}`, { tone: "error" });
    }
  };

  const visibleQa = qaList.filter((q) => !q.deleted_at).sort((a, b) => a.sort_order - b.sort_order);

  return (
    <div style={{ padding: 24, overflowY: "auto", flex: 1 }}>
      <span
        onClick={() => nav("/projects")}
        style={{
          color: palette.textMuted,
          fontSize: 13,
          cursor: "pointer",
          marginBottom: 12,
          display: "inline-block",
        }}
      >
        ← Projects
      </span>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 16, marginBottom: 20 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: palette.text, margin: "0 0 4px" }}>
            {project.name}
          </h1>
          {project.description && (
            <div style={{ color: palette.textMuted, fontSize: 14 }}>{project.description}</div>
          )}
        </div>
        <div style={{ display: "flex", gap: 8, flexShrink: 0 }}>
          {!editing && <Button variant="outlined" onClick={startEdit}>Edit</Button>}
          <Button onClick={handleNewChat}>+ New chat</Button>
        </div>
      </div>

      {editing && draft && (
        <Card padding={20} style={{ marginBottom: 24 }}>
          <Label>Name</Label>
          <TextInput value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />

          <Label>Description</Label>
          <TextInput value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })} />

          <Label>Manual context</Label>
          <TextArea
            value={draft.manual_context}
            onChange={(e) => setDraft({ ...draft, manual_context: e.target.value })}
            placeholder="Permanent system-prompt-level information the model should always know about this project."
          />
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 6 }}>
            <Hint>~{Math.ceil(draft.manual_context.length / 4)} tokens</Hint>
            <Button
              variant="outlined"
              size="sm"
              loading={refining}
              disabled={!draft.manual_context.trim()}
              onClick={refineContext}
            >
              Refine with AI
            </Button>
          </div>

          <div style={{ borderTop: `1px solid ${palette.border}`, margin: "20px -20px" }} />

          <ContextBudgetSection
            draft={draft}
            onChange={(v) => setDraft({ ...draft, ...v })}
            autoBusy={autoBusy}
            autoHint={autoHint}
            onAuto={autoFillContext}
          />

          <div style={{ borderTop: `1px solid ${palette.border}`, margin: "20px -20px" }} />

          <Label>Backend (Android only)</Label>
          <div style={{ display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" }}>
            {(["LOCAL", "REMOTE"] as const).map((b) => (
              <label key={b} style={{ display: "flex", gap: 6, color: palette.text, cursor: "pointer" }}>
                <input
                  type="radio"
                  name="backend"
                  checked={draft.preferred_backend === b}
                  onChange={() => setDraft({ ...draft, preferred_backend: b })}
                />
                {b === "LOCAL" ? "Local (on-device)" : "Remote (server)"}
              </label>
            ))}
          </div>
          <Hint>The web app always runs on the server. This setting controls the Android app.</Hint>

          <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 24 }}>
            <Button variant="outlined" onClick={cancelEdit} disabled={saving}>Cancel</Button>
            <Button onClick={saveEdit} loading={saving} disabled={!draft.name.trim()}>
              {saving ? "Saving…" : "Save"}
            </Button>
          </div>
        </Card>
      )}

      <Section
        title="Quick actions"
        description="Tap to start a chat with a pre-filled prompt. Synced from the Android app."
        action={
          <Button size="sm" variant="outlined" onClick={() => setShowNewQaDialog(true)}>+ New action</Button>
        }
      >
        {visibleQa.length === 0 ? (
          <Hint>No quick actions yet.</Hint>
        ) : (
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(220px,1fr))", gap: 8 }}>
            {visibleQa.map((qa) => (
              <Card key={qa.remote_id} padding={12}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
                  <div style={{ fontWeight: 600, color: palette.text }}>{qa.name}</div>
                </div>
                <div
                  style={{
                    fontSize: 12,
                    color: palette.textMuted,
                    marginBottom: 8,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    display: "-webkit-box",
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: "vertical",
                  }}
                >
                  {qa.prompt_template}
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <Button size="sm" onClick={() => handleRunQuickAction(qa)}>Run</Button>
                  <Button size="sm" variant="outlined" onClick={() => setEditingQa(qa)}>Edit</Button>
                  <Button size="sm" variant="text" onClick={() => handleDeleteQa(qa)}>Delete</Button>
                </div>
              </Card>
            ))}
          </div>
        )}
      </Section>

      <Section title="Chats" action={
        <Button size="sm" variant="text" onClick={() => nav(`/projects/${projectId}/memory`)}>
          🧠 Memory
        </Button>
      }>
        {chatList.length === 0 ? (
          <Hint>No chats yet. Start one above.</Hint>
        ) : (
          chatList
            .slice()
            .filter((c) => !c.deleted_at)
            .sort((a, b) => b.updated_at - a.updated_at)
            .map((c) => (
              <Card
                key={c.remote_id}
                padding={14}
                style={{ marginBottom: 8, display: "flex", alignItems: "center", gap: 12 }}
                onClick={() => nav(`/projects/${projectId}/chats/${c.remote_id}`)}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, color: palette.text, fontSize: 15 }}>{c.title}</div>
                  <div style={{ fontSize: 12, color: palette.textDim, marginTop: 2 }}>
                    {new Date(c.updated_at).toLocaleString()}
                  </div>
                </div>
                <Button
                  size="sm"
                  variant="text"
                  onClick={(e) => handleDeleteChat(e as unknown as React.MouseEvent, c)}
                >
                  Delete
                </Button>
              </Card>
            ))
        )}
      </Section>

      {(showNewQaDialog || editingQa) && (
        <QuickActionDialog
          initial={editingQa ?? undefined}
          onClose={() => { setShowNewQaDialog(false); setEditingQa(null); }}
          onSave={handleSaveQa}
        />
      )}
    </div>
  );
}

function ContextBudgetSection({
  draft, onChange, autoBusy, autoHint, onAuto,
}: {
  draft: Project;
  onChange: (v: Partial<Project>) => void;
  autoBusy: boolean;
  autoHint: string | null;
  onAuto: () => void;
}) {
  // Stacked allocation bar: memory + manual context filling the chosen window. The live
  // conversation gets whatever's left.
  const total = Math.max(1, draft.context_length);
  const memTokens = draft.memory_token_limit;
  const manualTokens = Math.ceil(draft.manual_context.length / 4);
  const memShare = Math.min(1, memTokens / total);
  const manualShare = Math.min(1 - memShare, manualTokens / total);
  const freeShare = Math.max(0, 1 - memShare - manualShare);

  const ceiling = Math.max(1000, draft.context_length - 2000);
  const memValue = Math.min(memTokens, ceiling);

  const selectedIdx = useMemo(() => {
    const i = PICKER_STEPS.indexOf(draft.context_length);
    return i < 0 ? 3 : i;
  }, [draft.context_length]);

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <Label>Context budget</Label>
        <Button size="sm" variant="text" loading={autoBusy} onClick={onAuto}>
          Auto
        </Button>
      </div>
      <Hint>
        Memory and manual context share this window with the live conversation. Larger values
        remember more but slow generation.
      </Hint>

      <div
        style={{
          display: "flex",
          height: 10,
          borderRadius: 5,
          overflow: "hidden",
          background: palette.surfaceVariant,
          marginTop: 12,
        }}
      >
        {memShare > 0 && (
          <div style={{ flex: memShare, background: "#a78bfa" }} title={`Memory ~${memTokens / 1000}k`} />
        )}
        {manualShare > 0 && (
          <div style={{ flex: manualShare, background: palette.primary }} title={`Context ~${manualTokens}`} />
        )}
        {freeShare > 0 && <div style={{ flex: freeShare }} />}
      </div>
      <div style={{ display: "flex", gap: 14, marginTop: 6, fontSize: 11, color: palette.textDim }}>
        <Legend dot="#a78bfa" label={`Memory ${Math.round(memTokens / 1000)}k`} />
        <Legend dot={palette.primary} label={`Context ${Math.round(manualTokens / 1000)}k`} />
        <span style={{ marginLeft: "auto" }}>
          Free ≈{Math.round((freeShare * total) / 1000)}k
        </span>
      </div>

      <div style={{ marginTop: space.lg }}>
        <div style={{ fontSize: 13, color: palette.text, marginBottom: 4 }}>
          Window: {draft.context_length / 1024}k tokens
        </div>
        <input
          type="range"
          min={0}
          max={PICKER_STEPS.length - 1}
          step={1}
          value={selectedIdx}
          onChange={(e) => {
            const idx = Number(e.target.value);
            const tokens = PICKER_STEPS[idx];
            const newCeiling = Math.max(1000, tokens - 2000);
            onChange({
              context_length: tokens,
              memory_token_limit: Math.min(draft.memory_token_limit, newCeiling),
            });
          }}
          style={{ width: "100%", accentColor: palette.primary }}
        />
        <div style={{ display: "flex", justifyContent: "space-between", fontSize: 11, color: palette.textDim }}>
          {PICKER_STEPS.map((v) => (
            <span
              key={v}
              style={{ color: v === draft.context_length ? palette.primary : undefined }}
            >
              {v / 1024}K
            </span>
          ))}
        </div>
        {autoHint && <Hint>{autoHint}</Hint>}
      </div>

      <div style={{ marginTop: space.lg }}>
        <div style={{ fontSize: 13, color: palette.text, marginBottom: 4 }}>
          Memory cap: {Math.round(memValue / 1000)}k tokens
        </div>
        <input
          type="range"
          min={1000}
          max={ceiling}
          step={500}
          value={memValue}
          onChange={(e) => onChange({ memory_token_limit: Number(e.target.value) })}
          style={{ width: "100%", accentColor: palette.primary }}
        />
        <Hint>
          Memory is capped at the window minus 2k so replies always have room. Increase the window
          first if you want a bigger memory.
        </Hint>
      </div>
    </div>
  );
}

function Legend({ dot, label }: { dot: string; label: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
      <span style={{
        width: 8, height: 8, borderRadius: 4, background: dot, display: "inline-block",
      }} />
      {label}
    </span>
  );
}

function QuickActionDialog({
  initial, onClose, onSave,
}: {
  initial?: QuickAction;
  onClose: () => void;
  onSave: (v: Partial<QuickAction>) => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [prompt, setPrompt] = useState(initial?.prompt_template ?? "");
  return (
    <Dialog open onClose={onClose} title={initial ? "Edit quick action" : "New quick action"}>
      <Label>Name</Label>
      <TextInput
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="e.g. Summarise"
      />
      <Label>Prompt template</Label>
      <TextArea
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        placeholder="What should the assistant do when this action runs?"
        style={{ minHeight: 140 }}
      />
      <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 16 }}>
        <Button variant="outlined" onClick={onClose}>Cancel</Button>
        <Button
          onClick={() => onSave({ name: name.trim(), prompt_template: prompt.trim() })}
          disabled={!name.trim() || !prompt.trim()}
        >
          Save
        </Button>
      </div>
    </Dialog>
  );
}
