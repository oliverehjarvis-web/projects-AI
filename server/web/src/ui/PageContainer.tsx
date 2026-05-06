import type { ReactNode } from "react";
import styles from "./PageContainer.module.css";

interface Props {
  children: ReactNode;
  /** Constrain content width. Defaults to 720 — wider for memory which has long-form text. */
  maxWidth?: number;
}

/**
 * Standard page wrapper for the project / memory / settings screens. Replaces the
 * `style={{ padding: 24, maxWidth: 720, overflowY: "auto", flex: 1 }}` boilerplate
 * that was hand-copied across three components.
 */
export default function PageContainer({ children, maxWidth = 720 }: Props) {
  return (
    <div className={styles.page} style={{ maxWidth }}>
      {children}
    </div>
  );
}
