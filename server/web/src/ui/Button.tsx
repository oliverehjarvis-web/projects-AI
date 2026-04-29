import type { ButtonHTMLAttributes, ReactNode } from "react";
import { palette, radius, font } from "../theme";

type Variant = "primary" | "outlined" | "text" | "danger";
type Size = "sm" | "md";

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  leadingIcon?: ReactNode;
}

const base: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 6,
  border: "none",
  borderRadius: radius.md,
  cursor: "pointer",
  fontWeight: 600,
  fontSize: font.body,
  fontFamily: "inherit",
  lineHeight: 1.2,
  transition: "background 120ms, opacity 120ms",
};

const sizes: Record<Size, React.CSSProperties> = {
  sm: { padding: "6px 12px", fontSize: font.bodySm },
  md: { padding: "10px 18px", fontSize: font.body },
};

const variants: Record<Variant, React.CSSProperties> = {
  primary: { background: palette.primary, color: palette.onPrimary },
  outlined: {
    background: "transparent",
    color: palette.text,
    border: `1px solid ${palette.border}`,
  },
  text: { background: "transparent", color: palette.textMuted },
  danger: { background: palette.error, color: palette.onPrimary },
};

export default function Button({
  variant = "primary",
  size = "md",
  loading,
  leadingIcon,
  children,
  disabled,
  style,
  ...rest
}: Props) {
  const isDisabled = disabled || loading;
  return (
    <button
      {...rest}
      disabled={isDisabled}
      style={{
        ...base,
        ...sizes[size],
        ...variants[variant],
        ...(isDisabled ? { opacity: 0.55, cursor: "not-allowed" } : {}),
        ...style,
      }}
    >
      {loading && <Spinner />}
      {!loading && leadingIcon}
      {children}
    </button>
  );
}

function Spinner() {
  return (
    <span
      aria-hidden
      style={{
        width: 12,
        height: 12,
        border: `2px solid ${palette.onPrimary}`,
        borderTopColor: "transparent",
        borderRadius: "50%",
        display: "inline-block",
        animation: "spin 700ms linear infinite",
      }}
    />
  );
}
