import type { ReactNode } from "react";
import { palette, radius, space, shadow } from "../theme";

interface Props {
  open: boolean;
  title?: string;
  onClose?: () => void;
  width?: number;
  children: ReactNode;
}

export default function Dialog({ open, title, onClose, width = 460, children }: Props) {
  if (!open) return null;
  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.6)",
        zIndex: 50,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: space.lg,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width,
          maxWidth: "100%",
          maxHeight: "90vh",
          overflowY: "auto",
          background: palette.surface,
          border: `1px solid ${palette.border}`,
          borderRadius: radius.lg,
          padding: space.xl,
          boxShadow: shadow.pop,
        }}
      >
        {title && (
          <div
            style={{
              fontSize: 18,
              fontWeight: 600,
              color: palette.text,
              marginBottom: space.lg,
            }}
          >
            {title}
          </div>
        )}
        {children}
      </div>
    </div>
  );
}
