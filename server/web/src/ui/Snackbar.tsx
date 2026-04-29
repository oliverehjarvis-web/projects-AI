import { useEffect } from "react";
import { useStore } from "../store/useStore";
import { palette, radius, space, shadow } from "../theme";

/**
 * Floating snackbar host. Reads from the global queue and auto-dismisses each entry after a
 * short delay. Mounted once near the root so any component can `pushSnack(...)`.
 */
export default function SnackbarHost() {
  const { snacks, dismissSnack } = useStore();

  useEffect(() => {
    if (snacks.length === 0) return;
    const head = snacks[0];
    const t = setTimeout(() => dismissSnack(head.id), head.durationMs ?? 3500);
    return () => clearTimeout(t);
  }, [snacks, dismissSnack]);

  if (snacks.length === 0) return null;
  return (
    <div
      style={{
        position: "fixed",
        bottom: space.xl,
        left: "50%",
        transform: "translateX(-50%)",
        display: "flex",
        flexDirection: "column-reverse",
        gap: space.sm,
        zIndex: 100,
        pointerEvents: "none",
      }}
    >
      {snacks.map((s) => (
        <div
          key={s.id}
          onClick={() => dismissSnack(s.id)}
          style={{
            background: s.tone === "error" ? palette.errorContainer : palette.surfaceElevated,
            color: s.tone === "error" ? palette.onErrorContainer : palette.text,
            border: `1px solid ${s.tone === "error" ? palette.error : palette.borderStrong}`,
            borderRadius: radius.md,
            padding: "10px 16px",
            fontSize: 13,
            boxShadow: shadow.pop,
            pointerEvents: "auto",
            cursor: "pointer",
            maxWidth: 480,
          }}
        >
          {s.message}
        </div>
      ))}
    </div>
  );
}
