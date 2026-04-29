import type { InputHTMLAttributes, TextareaHTMLAttributes } from "react";
import * as React from "react";
import { palette, radius, font, space } from "../theme";

const inputBase: React.CSSProperties = {
  width: "100%",
  background: palette.bg,
  border: `1px solid ${palette.border}`,
  borderRadius: radius.md,
  color: palette.text,
  padding: "10px 12px",
  fontSize: font.body,
  outline: "none",
  fontFamily: "inherit",
  boxSizing: "border-box",
  transition: "border-color 120ms",
};

export function Label({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        fontSize: font.label,
        color: palette.textMuted,
        marginBottom: 6,
        marginTop: space.md,
        textTransform: "uppercase",
        letterSpacing: 0.5,
      }}
    >
      {children}
    </div>
  );
}

export function Hint({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ fontSize: font.label, color: palette.textDim, marginTop: 4, lineHeight: 1.5 }}>
      {children}
    </div>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  const { style, ...rest } = props;
  return <input {...rest} style={{ ...inputBase, ...style }} />;
}

export function TextArea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const { style, ...rest } = props;
  return (
    <textarea
      {...rest}
      style={{
        ...inputBase,
        minHeight: 120,
        resize: "vertical",
        ...style,
      }}
    />
  );
}

type SelectProps = React.SelectHTMLAttributes<HTMLSelectElement> & { children: React.ReactNode };

export function Select(props: SelectProps) {
  const { style, children, ...rest } = props;
  return (
    <select {...rest} style={{ ...inputBase, appearance: "none", cursor: "pointer", ...style }}>
      {children}
    </select>
  );
}
