// Single source of truth for the dark-only palette and the spacing/radius/font scales the
// UI primitives consume. Switching to a light theme later is a matter of swapping `palette`.

export const palette = {
  bg: "#0d0d0d",
  surface: "#1a1a1a",
  surfaceVariant: "#222",
  surfaceElevated: "#262626",
  border: "#2a2a2a",
  borderStrong: "#3a3a3a",
  primary: "#2563eb",
  primaryHover: "#1d4ed8",
  primaryContainer: "#172554",
  onPrimary: "#fff",
  text: "#e4e4e7",
  textMuted: "#a1a1aa",
  textDim: "#71717a",
  success: "#22c55e",
  successContainer: "#14532d",
  warning: "#f59e0b",
  warningContainer: "#451a03",
  error: "#ef4444",
  errorContainer: "#3a1d1d",
  onErrorContainer: "#fecaca",
};

export const space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
};

export const radius = {
  sm: 6,
  md: 8,
  lg: 12,
  pill: 999,
};

export const font = {
  body: 14,
  bodySm: 13,
  label: 12,
  caption: 11,
  title: 22,
  sectionTitle: 13,
};

export const shadow = {
  pop: "0 6px 24px rgba(0,0,0,0.45)",
};
