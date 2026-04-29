import { useEffect, useMemo, useState } from "react";
import { useStore } from "../store/useStore";
import { fetchFile, fetchTree, listRepos, type GhRepo, type GhTreeEntry } from "../api/github";
import Dialog from "../ui/Dialog";
import Button from "../ui/Button";
import Card from "../ui/Card";
import { palette, radius, space, font } from "../theme";

interface Props {
  chatId: string;
  projectContextTokens: number;
  onClose: () => void;
  onInjected: () => void;
}

interface TreeNode {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
  children: TreeNode[];
}

type Phase =
  | { kind: "needsSetup" }
  | { kind: "pickRepo"; repos: GhRepo[] }
  | { kind: "loading"; label: string }
  | { kind: "tree"; owner: string; repo: string; ref: string; root: TreeNode; truncated: boolean }
  | { kind: "error"; message: string };

function buildTree(entries: GhTreeEntry[]): TreeNode {
  const root: TreeNode = { name: "", path: "", isDir: true, size: 0, children: [] };
  const byPath = new Map<string, TreeNode>();
  byPath.set("", root);
  const sorted = [...entries].sort((a, b) => a.path.localeCompare(b.path));
  for (const e of sorted) {
    const parts = e.path.split("/");
    const name = parts[parts.length - 1];
    const parentPath = parts.slice(0, -1).join("/");
    const node: TreeNode = {
      name,
      path: e.path,
      isDir: e.type === "tree",
      size: e.size,
      children: [],
    };
    byPath.set(e.path, node);
    byPath.get(parentPath)?.children.push(node);
  }
  function sort(n: TreeNode) {
    n.children.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    n.children.forEach((c) => c.isDir && sort(c));
  }
  sort(root);
  return root;
}

function flatten(root: TreeNode, expanded: Set<string>): TreeNode[] {
  const out: TreeNode[] = [];
  for (const child of root.children) {
    out.push(child);
    if (child.isDir && expanded.has(child.path)) out.push(...flatten(child, expanded));
  }
  return out;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function RepoBrowser({ chatId, projectContextTokens, onClose, onInjected }: Props) {
  const { githubPat, githubDefaultRepo, stageRepoFiles, pushSnack } = useStore();
  const [phase, setPhase] = useState<Phase>(
    githubPat ? { kind: "loading", label: "Loading repositories…" } : { kind: "needsSetup" },
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(new Set([""]));
  const [injecting, setInjecting] = useState(false);

  useEffect(() => {
    if (!githubPat) return;
    let cancelled = false;
    (async () => {
      try {
        const repos = await listRepos(githubPat);
        if (cancelled) return;
        // If the user has a default-repo preference, jump straight into it.
        if (githubDefaultRepo) {
          const match = repos.find((r) => `${r.owner}/${r.name}` === githubDefaultRepo);
          if (match) {
            await pickRepo(match, repos);
            return;
          }
        }
        setPhase({ kind: "pickRepo", repos });
      } catch (e) {
        if (!cancelled) setPhase({ kind: "error", message: (e as Error).message });
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function pickRepo(repo: GhRepo, _allRepos?: GhRepo[]) {
    setPhase({ kind: "loading", label: `Loading ${repo.owner}/${repo.name}…` });
    try {
      const tree = await fetchTree(repo.owner, repo.name, repo.default_branch, githubPat);
      const root = buildTree(tree.entries);
      setSelected(new Set());
      setExpanded(new Set([""]));
      setPhase({
        kind: "tree",
        owner: repo.owner,
        repo: repo.name,
        ref: tree.ref,
        root,
        truncated: tree.truncated,
      });
    } catch (e) {
      setPhase({ kind: "error", message: (e as Error).message });
    }
  }

  function toggleExpanded(path: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  function toggleSelected(path: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  const stats = useMemo(() => {
    if (phase.kind !== "tree") return { files: 0, bytes: 0, tokens: 0 };
    const collect = (n: TreeNode): TreeNode[] => [
      ...(!n.isDir ? [n] : []),
      ...n.children.flatMap(collect),
    ];
    const all = collect(phase.root);
    const sel = all.filter((n) => selected.has(n.path));
    const bytes = sel.reduce((a, n) => a + n.size, 0);
    return { files: sel.length, bytes, tokens: Math.ceil(bytes / 3.5) };
  }, [phase, selected]);

  const pctOfWindow = projectContextTokens > 0 ? stats.tokens / projectContextTokens : 0;
  const warning = pctOfWindow > 0.6;

  async function inject() {
    if (phase.kind !== "tree" || stats.files === 0) return;
    setInjecting(true);
    try {
      const fetched = await Promise.all(
        Array.from(selected).sort().map(async (path) => {
          try {
            const f = await fetchFile(phase.owner, phase.repo, path, phase.ref, githubPat);
            return { path: f.path, size_bytes: f.size_bytes, text: f.text };
          } catch {
            return null;
          }
        }),
      );
      const files = fetched.filter((x): x is NonNullable<typeof x> => x !== null);
      if (files.length === 0) {
        pushSnack("Couldn't fetch any of the selected files.", { tone: "error" });
        return;
      }
      stageRepoFiles(chatId, {
        owner: phase.owner,
        repo: phase.repo,
        ref: phase.ref,
        files,
      });
      onInjected();
    } finally {
      setInjecting(false);
    }
  }

  return (
    <Dialog open onClose={onClose} width={760}
      title={
        phase.kind === "tree"
          ? `${phase.owner}/${phase.repo}`
          : phase.kind === "needsSetup"
            ? "GitHub isn't set up yet"
            : "Pick a repository"
      }
    >
      {phase.kind === "needsSetup" && <NeedsSetupPanel onClose={onClose} />}
      {phase.kind === "loading" && <Loading label={phase.label} />}
      {phase.kind === "error" && (
        <Card tone="error" padding={12}>
          <div style={{ marginBottom: 8 }}>{phase.message}</div>
          <Button variant="outlined" size="sm" onClick={onClose}>Close</Button>
        </Card>
      )}
      {phase.kind === "pickRepo" && (
        <RepoListPanel repos={phase.repos} onPick={(r) => pickRepo(r)} />
      )}
      {phase.kind === "tree" && (
        <TreePanel
          root={phase.root}
          truncated={phase.truncated}
          selected={selected}
          expanded={expanded}
          stats={stats}
          pctOfWindow={pctOfWindow}
          warning={warning}
          contextWindow={projectContextTokens}
          injecting={injecting}
          onToggleExpanded={toggleExpanded}
          onToggleSelected={toggleSelected}
          onClear={() => setSelected(new Set())}
          onInject={inject}
          onClose={onClose}
        />
      )}
    </Dialog>
  );
}

function NeedsSetupPanel({ onClose }: { onClose: () => void }) {
  return (
    <div>
      <div style={{ color: palette.textMuted, lineHeight: 1.5, marginBottom: 16 }}>
        Add a Personal Access Token in Settings → GitHub to browse your repos and inject files
        into chat. Fine-grained PATs work best — pick read access to the repos you want the
        assistant to see. The token stays in your browser and never reaches the server.
      </div>
      <Button onClick={onClose}>OK</Button>
    </div>
  );
}

function Loading({ label }: { label: string }) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 12,
        color: palette.textMuted,
        padding: "32px 0",
        justifyContent: "center",
      }}
    >
      <span
        style={{
          width: 14,
          height: 14,
          border: `2px solid ${palette.textMuted}`,
          borderTopColor: "transparent",
          borderRadius: "50%",
          animation: "spin 700ms linear infinite",
        }}
      />
      {label}
    </div>
  );
}

function RepoListPanel({ repos, onPick }: { repos: GhRepo[]; onPick: (r: GhRepo) => void }) {
  if (repos.length === 0) {
    return (
      <div style={{ color: palette.textMuted, padding: "24px 0", textAlign: "center" }}>
        No repositories returned. Check your PAT scope in Settings → GitHub.
      </div>
    );
  }
  return (
    <div style={{ maxHeight: 480, overflowY: "auto" }}>
      {repos.map((r) => (
        <div
          key={`${r.owner}/${r.name}`}
          onClick={() => onPick(r)}
          style={{
            padding: "10px 12px",
            cursor: "pointer",
            borderRadius: radius.md,
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
          }}
          onMouseEnter={(e) => (e.currentTarget.style.background = palette.surfaceVariant)}
          onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
        >
          <div>
            <div style={{ color: palette.text, fontWeight: 500 }}>{r.owner}/{r.name}</div>
            <div style={{ color: palette.textDim, fontSize: 12 }}>
              {r.size_kb} KB · {r.default_branch}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

function TreePanel({
  root, truncated, selected, expanded, stats, pctOfWindow, warning, contextWindow,
  injecting, onToggleExpanded, onToggleSelected, onClear, onInject, onClose,
}: {
  root: TreeNode;
  truncated: boolean;
  selected: Set<string>;
  expanded: Set<string>;
  stats: { files: number; bytes: number; tokens: number };
  pctOfWindow: number;
  warning: boolean;
  contextWindow: number;
  injecting: boolean;
  onToggleExpanded: (p: string) => void;
  onToggleSelected: (p: string) => void;
  onClear: () => void;
  onInject: () => void;
  onClose: () => void;
}) {
  const visible = useMemo(() => flatten(root, expanded), [root, expanded]);
  return (
    <div>
      {truncated && (
        <Card tone="error" padding={10} style={{ marginBottom: 12, fontSize: 12 }}>
          GitHub returned a truncated tree (very large repo). Some files may not be listed.
        </Card>
      )}
      <div style={{ maxHeight: 380, overflowY: "auto", border: `1px solid ${palette.border}`,
        borderRadius: radius.md, padding: space.sm }}>
        {visible.map((n) => {
          const indent = (n.path.match(/\//g) || []).length * 14;
          const isSelected = selected.has(n.path);
          const onClick = () => (n.isDir ? onToggleExpanded(n.path) : onToggleSelected(n.path));
          return (
            <div
              key={n.path}
              onClick={onClick}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                padding: "4px 8px",
                paddingLeft: 8 + indent,
                cursor: "pointer",
                fontSize: font.bodySm,
                borderRadius: radius.sm,
              }}
              onMouseEnter={(e) => (e.currentTarget.style.background = palette.surfaceVariant)}
              onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
            >
              {n.isDir ? (
                <span style={{ width: 18, color: palette.textMuted }}>
                  {expanded.has(n.path) ? "▾" : "▸"}
                </span>
              ) : (
                <input
                  type="checkbox"
                  checked={isSelected}
                  readOnly
                  style={{ accentColor: palette.primary }}
                />
              )}
              <span style={{ width: 16, color: palette.textMuted }}>
                {n.isDir ? "📁" : "📄"}
              </span>
              <span style={{ flex: 1, color: palette.text }}>{n.name}</span>
              {!n.isDir && (
                <span style={{ color: palette.textDim, fontSize: 11 }}>{formatSize(n.size)}</span>
              )}
            </div>
          );
        })}
      </div>
      <Card
        tone={warning ? "error" : "neutral"}
        padding={12}
        style={{ marginTop: 12 }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 6 }}>
          <div style={{ flex: 1, fontSize: 13, color: palette.text }}>
            {stats.files} file{stats.files === 1 ? "" : "s"} · {formatSize(stats.bytes)} ·
            ≈{Math.ceil(stats.tokens / 1000)}k tokens
          </div>
          {stats.files > 0 && (
            <Button variant="outlined" size="sm" onClick={onClear}>Clear</Button>
          )}
        </div>
        <div style={{ height: 6, background: palette.surfaceVariant, borderRadius: 3, overflow: "hidden" }}>
          <div
            style={{
              width: `${Math.min(100, pctOfWindow * 100)}%`,
              height: "100%",
              background: warning ? palette.error : palette.primary,
              transition: "width 120ms",
            }}
          />
        </div>
        <div style={{
          fontSize: 11, color: warning ? palette.onErrorContainer : palette.textDim, marginTop: 4,
        }}>
          {contextWindow > 0
            ? `${Math.round(pctOfWindow * 100)}% of the project's ${contextWindow / 1024}k window`
            : "Context window unknown"}
        </div>
      </Card>
      <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 16 }}>
        <Button variant="outlined" onClick={onClose}>Cancel</Button>
        <Button onClick={onInject} loading={injecting} disabled={stats.files === 0}>
          {injecting ? "Fetching files…" : "Attach to chat"}
        </Button>
      </div>
    </div>
  );
}
