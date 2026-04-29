import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "../store/useStore";
import { fetchProjects, createProject, deleteProject } from "../api/sync";
import Button from "../ui/Button";
import Card from "../ui/Card";
import Dialog from "../ui/Dialog";
import { Label, TextInput } from "../ui/Field";
import { palette, font } from "../theme";

export default function ProjectList() {
  const { projects, setProjects, addProject, removeProject, pushSnack } = useStore();
  const nav = useNavigate();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetchProjects().then(setProjects).catch(console.error);
  }, [setProjects]);

  const visible = projects.filter((p) => !p.is_secret && !p.deleted_at);

  const handleCreate = async () => {
    if (!name.trim()) return;
    setBusy(true);
    try {
      const project = await createProject(name.trim(), description.trim());
      addProject(project);
      setDialogOpen(false);
      setName("");
      setDescription("");
      nav(`/projects/${project.remote_id}`);
    } catch (e) {
      pushSnack(`Couldn't create project: ${e}`, { tone: "error" });
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (e: React.MouseEvent, project: (typeof projects)[number]) => {
    e.stopPropagation();
    if (!confirm(`Delete "${project.name}" and all its chats?`)) return;
    try {
      await deleteProject(project);
      removeProject(project.remote_id);
    } catch (err) {
      pushSnack(`Delete failed: ${err}`, { tone: "error" });
    }
  };

  return (
    <div style={{ padding: 24, overflowY: "auto", flex: 1 }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 20 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: palette.text, margin: 0 }}>Projects</h1>
        <Button onClick={() => setDialogOpen(true)}>+ New project</Button>
      </div>

      {visible.length === 0 && (
        <div style={{ color: palette.textDim, fontSize: font.bodySm }}>
          No projects yet. Create one to get started.
        </div>
      )}

      {visible.map((p) => (
        <Card
          key={p.remote_id}
          padding={16}
          style={{
            marginBottom: 10,
            display: "flex",
            alignItems: "center",
            gap: 12,
          }}
          onClick={() => nav(`/projects/${p.remote_id}`)}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 15, color: palette.text, marginBottom: 4 }}>
              {p.name}
            </div>
            {p.description && (
              <div style={{ fontSize: 13, color: palette.textMuted }}>{p.description}</div>
            )}
          </div>
          <Button size="sm" variant="text" onClick={(e) => handleDelete(e as unknown as React.MouseEvent, p)}>
            Delete
          </Button>
        </Card>
      ))}

      <Dialog
        open={dialogOpen}
        title="New project"
        onClose={() => !busy && setDialogOpen(false)}
      >
        <Label>Name</Label>
        <TextInput
          value={name}
          autoFocus
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleCreate()}
          placeholder="Project name"
        />
        <Label>Description (optional)</Label>
        <TextInput
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleCreate()}
          placeholder="Short description"
        />
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 16 }}>
          <Button variant="outlined" onClick={() => setDialogOpen(false)} disabled={busy}>
            Cancel
          </Button>
          <Button onClick={handleCreate} loading={busy} disabled={!name.trim()}>
            {busy ? "Creating…" : "Create"}
          </Button>
        </div>
      </Dialog>
    </div>
  );
}
