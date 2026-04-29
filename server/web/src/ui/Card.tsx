import type { ReactNode } from "react";
import { palette, radius, space } from "../theme";

type Tone = "neutral" | "error" | "primary" | "success";

const tones: Record<Tone, React.CSSProperties> = {
  neutral: { background: palette.surface, border: `1px solid ${palette.border}` },
  error: {
    background: palette.errorContainer,
    border: `1px solid ${palette.error}`,
    color: palette.onErrorContainer,
  },
  primary: {
    background: palette.primaryContainer,
    border: `1px solid ${palette.primary}`,
  },
  success: {
    background: palette.successContainer,
    border: `1px solid ${palette.success}`,
  },
};

interface Props {
  tone?: Tone;
  padding?: number;
  children: ReactNode;
  style?: React.CSSProperties;
  onClick?: () => void;
}

export default function Card({ tone = "neutral", padding = space.lg, children, style, onClick }: Props) {
  return (
    <div
      onClick={onClick}
      style={{
        borderRadius: radius.lg,
        padding,
        ...tones[tone],
        ...(onClick ? { cursor: "pointer" } : {}),
        ...style,
      }}
    >
      {children}
    </div>
  );
}
