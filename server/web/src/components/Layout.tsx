import { Link, useLocation } from "react-router-dom";
import { useEffect, useRef, type ReactNode } from "react";
import { useStore } from "../store/useStore";
import { syncFull } from "../api/sync";
import { fetchGlobalContext } from "../api/global";
import { getBaseUrl, getToken } from "../api/client";

const s: Record<string, React.CSSProperties> = {
  shell: { display: "flex", height: "100dvh" },
  sidebar: {
    width: 220, background: "#1a1a1a", borderRight: "1px solid #2a2a2a",
    display: "flex", flexDirection: "column", padding: "16px 0",
  },
  brand: { padding: "0 16px 16px", fontSize: 18, fontWeight: 700, color: "#fff" },
  nav: { display: "flex", flexDirection: "column", gap: 4, padding: "0 8px", flex: 1 },
  link: {
    padding: "8px 12px", borderRadius: 8, color: "#b0b0b0",
    textDecoration: "none", fontSize: 14,
  },
  linkActive: { background: "#2a2a2a", color: "#fff" },
  main: { flex: 1, overflow: "hidden", display: "flex", flexDirection: "column" },
};

// How often to pull remote changes while the tab is visible. Short enough that
// a delete on the phone feels immediate here; long enough that a dozen open
// tabs don't hammer the server.
const POLL_INTERVAL_MS = 15_000;

function useBackgroundSync() {
  const { mergeSyncResult, setGlobalContext, lastSyncTs } = useStore();
  // Keep a ref so the interval closure always sees the current cursor without
  // re-subscribing — otherwise the poller would restart on every successful
  // sync and effectively become a debounce rather than a cadence.
  const cursorRef = useRef(lastSyncTs);
  useEffect(() => { cursorRef.current = lastSyncTs; }, [lastSyncTs]);

  useEffect(() => {
    let cancelled = false;

    const tick = async () => {
      if (cancelled) return;
      if (!getBaseUrl() || !getToken()) return;
      if (document.visibilityState !== "visible") return;
      try {
        const since = cursorRef.current;
        // The initial boot sync uses since=0 so we see tombstones from the last
        // 30 days — anything deleted while this tab was closed still propagates.
        const res = await syncFull(since);
        mergeSyncResult(res, Date.now());
      } catch {
        // transient network failures are fine; next tick will retry.
      }
    };

    const loadGlobal = async () => {
      if (!getBaseUrl() || !getToken()) return;
      try {
        const ctx = await fetchGlobalContext();
        setGlobalContext(ctx);
      } catch { /* ignore */ }
    };

    loadGlobal();
    tick();
    const id = setInterval(tick, POLL_INTERVAL_MS);
    const onVisible = () => { if (document.visibilityState === "visible") tick(); };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      cancelled = true;
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [mergeSyncResult, setGlobalContext]);
}

export default function Layout({ children }: { children: ReactNode }) {
  const loc = useLocation();
  useBackgroundSync();
  const link = (to: string, label: string) => (
    <Link to={to} style={{ ...s.link, ...(loc.pathname.startsWith(to) ? s.linkActive : {}) }}>
      {label}
    </Link>
  );
  return (
    <div style={s.shell}>
      <aside style={s.sidebar}>
        <div style={s.brand}>Projects AI</div>
        <nav style={s.nav}>
          {link("/projects", "Projects")}
          {link("/settings", "Settings")}
        </nav>
      </aside>
      <main style={s.main}>{children}</main>
    </div>
  );
}
