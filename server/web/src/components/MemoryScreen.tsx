import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useStore } from "../store/useStore";
import { updateProject } from "../api/sync";
import { refineText } from "../api/refine";
import Button from "../ui/Button";
import { TextArea } from "../ui/Field";
import Card from "../ui/Card";
import { palette, radius, space, font } from "../theme";

export default function MemoryScreen() {
  const { projectId } = useParams<{ projectId: string }>();
  const nav = useNavigate();
  const { projects, addProject, model, pushSnack } = useStore();
  const project = projects.find((p) => p.remote_id === projectId);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [compressing, setCompressing] = useState(false);

  useEffect(() => {
    if (project) setDraft(project.accumulated_memory);
  }, [project?.remote_id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!project) {
    return <div style={{ padding: 24, color: palette.textDim }}>Project not found.</div>;
  }
  if (project.is_secret) {
    return <div style={{ padding: 24, color: palette.textDim }}>Project not found.</div>;
  }

  const dirty = draft !== project.accumulated_memory;

  const save = async () => {
    setSaving(true);
    try {
      const next = { ...project, accumulated_memory: draft, updated_at: Date.now() };
      await updateProject(next);
      addProject(next);
      pushSnack("Memory saved");
    } catch (e) {
      pushSnack(`Save failed: ${e}`, { tone: "error" });
    } finally {
      setSaving(false);
    }
  };

  const compress = async () => {
    if (!draft.trim()) return;
    setCompressing(true);
    try {
      const out = await refineText({
        raw: draft,
        model,
        numCtx: project.context_length,
        kind: "memory",
      });
      if (out.trim()) {
        setDraft(out);
        pushSnack("Memory compressed — review and save.");
      }
    } catch (e) {
      pushSnack(`Compression failed: ${e}`, { tone: "error" });
    } finally {
      setCompressing(false);
    }
  };

  const tokens = Math.ceil(draft.length / 4);
  const limit = project.memory_token_limit || 4000;
  const pct = Math.min(100, (tokens / limit) * 100);

  return (
    <div style={{ padding: 24, overflowY: "auto", flex: 1, maxWidth: 800 }}>
      <span
        onClick={() => nav(`/projects/${projectId}`)}
        style={{
          color: palette.textMuted,
          fontSize: 13,
          cursor: "pointer",
          marginBottom: 12,
          display: "inline-block",
        }}
      >
        ← {project.name}
      </span>
      <h1 style={{ fontSize: 22, fontWeight: 700, color: palette.text, margin: "8px 0 4px" }}>
        Project memory
      </h1>
      <div style={{ color: palette.textDim, fontSize: font.bodySm, marginBottom: space.lg, lineHeight: 1.5 }}>
        Durable facts the assistant remembers for this project. Either edit by hand or paste an
        existing chat and tap "Compress with AI" to extract the facts worth keeping.
      </div>

      <Card padding={14} style={{ marginBottom: 16 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
          <span style={{ fontSize: 13, color: palette.text }}>
            ~{tokens} tokens
          </span>
          <span style={{ fontSize: 12, color: palette.textDim }}>
            Limit: {limit / 1000}k
          </span>
        </div>
        <div style={{ height: 6, background: palette.surfaceVariant, borderRadius: 3, overflow: "hidden" }}>
          <div
            style={{
              width: `${pct}%`,
              height: "100%",
              background: pct > 100 ? palette.error : palette.primary,
              borderRadius: 3,
            }}
          />
        </div>
      </Card>

      <TextArea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder="Persistent facts about this project — paste chat output here and compress, or edit directly."
        style={{ minHeight: 320, fontFamily: "inherit" }}
      />

      <div style={{ display: "flex", gap: 8, marginTop: 16, justifyContent: "flex-end" }}>
        <Button variant="outlined" onClick={compress} loading={compressing} disabled={!draft.trim()}>
          Compress with AI
        </Button>
        <Button onClick={save} loading={saving} disabled={!dirty}>
          {saving ? "Saving…" : "Save"}
        </Button>
      </div>
    </div>
  );
}
