import type { ButtonHTMLAttributes, ReactNode } from "react";
import { palette, radius } from "../theme";

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  active?: boolean;
  size?: number;
  children: ReactNode;
}

export default function IconButton({ active, size = 32, style, children, ...rest }: Props) {
  return (
    <button
      {...rest}
      style={{
        width: size,
        height: size,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        borderRadius: radius.md,
        background: active ? palette.primaryContainer : "transparent",
        color: active ? palette.primary : palette.textMuted,
        border: "none",
        cursor: "pointer",
        ...style,
      }}
    >
      {children}
    </button>
  );
}
