import { palette, radius } from "../theme";

interface Props {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  ariaLabel?: string;
}

export default function Switch({ checked, onChange, disabled, ariaLabel }: Props) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={() => !disabled && onChange(!checked)}
      style={{
        width: 38,
        height: 22,
        borderRadius: radius.pill,
        background: checked ? palette.primary : palette.surfaceVariant,
        border: `1px solid ${checked ? palette.primary : palette.border}`,
        position: "relative",
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        padding: 0,
        transition: "background 120ms",
      }}
    >
      <span
        style={{
          position: "absolute",
          top: 2,
          left: checked ? 18 : 2,
          width: 16,
          height: 16,
          borderRadius: "50%",
          background: palette.onPrimary,
          transition: "left 120ms",
        }}
      />
    </button>
  );
}
