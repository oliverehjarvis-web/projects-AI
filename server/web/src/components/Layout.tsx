import { Link, useLocation } from "react-router-dom";
import type { ReactNode } from "react";

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

export default function Layout({ children }: { children: ReactNode }) {
  const loc = useLocation();
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
