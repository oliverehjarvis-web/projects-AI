import { Link, useLocation } from "react-router-dom";
import { useEffect, useRef, type ReactNode } from "react";
import { useStore } from "../store/useStore";
import { syncFull } from "../api/sync";
import { fetchGlobalContext } from "../api/global";
import { getBaseUrl, getToken } from "../api/client";
import SnackbarHost from "../ui/Snackbar";
import { palette, radius, space } from "../theme";

const POLL_INTERVAL_MS = 15_000;

function useBackgroundSync() {
  const { mergeSyncResult, setGlobalContext, lastSyncTs } = useStore();
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
        const res = await syncFull(since);
        mergeSyncResult(res, Date.now());
      } catch {
        /* ignore — next tick retries */
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

const navItems = [
  { to: "/projects", label: "Projects", icon: "📁" },
  { to: "/settings", label: "Settings", icon: "⚙" },
];

export default function Layout({ children }: { children: ReactNode }) {
  const loc = useLocation();
  useBackgroundSync();
  return (
    <div style={{ display: "flex", height: "100dvh", background: palette.bg }}>
      <aside
        style={{
          width: 224,
          background: palette.surface,
          borderRight: `1px solid ${palette.border}`,
          display: "flex",
          flexDirection: "column",
          padding: `${space.lg}px 0`,
          flexShrink: 0,
        }}
      >
        <div
          style={{
            padding: `0 ${space.lg}px ${space.md}px`,
            fontSize: 17,
            fontWeight: 700,
            color: palette.text,
            display: "flex",
            alignItems: "center",
            gap: 8,
          }}
        >
          <span style={{ fontSize: 18 }}>✦</span>
          Projects AI
        </div>
        <nav
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 2,
            padding: `0 ${space.sm}px`,
            flex: 1,
          }}
        >
          {navItems.map((item) => {
            const active = loc.pathname.startsWith(item.to);
            return (
              <Link
                key={item.to}
                to={item.to}
                style={{
                  padding: "9px 12px",
                  borderRadius: radius.md,
                  color: active ? palette.text : palette.textMuted,
                  background: active ? palette.surfaceVariant : "transparent",
                  textDecoration: "none",
                  fontSize: 14,
                  fontWeight: active ? 600 : 500,
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                }}
              >
                <span style={{ width: 16, textAlign: "center" }}>{item.icon}</span>
                {item.label}
              </Link>
            );
          })}
        </nav>
      </aside>
      <main
        style={{
          flex: 1,
          overflow: "hidden",
          display: "flex",
          flexDirection: "column",
        }}
      >
        {children}
      </main>
      <SnackbarHost />
    </div>
  );
}
