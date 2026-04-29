import type { ReactNode } from "react";
import { palette, font, space } from "../theme";

interface Props {
  title: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
}

export default function Section({ title, description, action, children }: Props) {
  return (
    <section style={{ marginBottom: space.xl }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: space.md,
        }}
      >
        <div
          style={{
            fontSize: font.sectionTitle,
            fontWeight: 600,
            color: palette.textMuted,
            textTransform: "uppercase",
            letterSpacing: 1,
          }}
        >
          {title}
        </div>
        {action}
      </div>
      {description && (
        <div
          style={{
            fontSize: font.bodySm,
            color: palette.textDim,
            marginBottom: space.md,
            lineHeight: 1.5,
          }}
        >
          {description}
        </div>
      )}
      {children}
    </section>
  );
}
