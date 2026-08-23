import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

/* ── Buttons ────────────────────────────────────────────────────────────── */

export function Button({
  children,
  hint,
  variant = "default",
  className = "",
  ...rest
}: {
  children: ReactNode;
  hint?: string;
  variant?: "default" | "primary" | "quiet" | "danger";
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const base =
    "flex items-center justify-between gap-2.5 rounded border font-semibold text-left " +
    "disabled:opacity-40 disabled:cursor-not-allowed transition-colors";
  const styles = {
    default: "bg-surface2 text-ink border-line hover:enabled:border-accent px-4 py-3",
    primary:
      "bg-accent text-accentink border-accent hover:enabled:bg-accent2 hover:enabled:border-accent2 px-4 py-4 text-[17px]",
    quiet: "bg-transparent text-inksoft border-line hover:enabled:border-accent hover:enabled:text-accent px-2.5 py-1 text-xs font-medium",
    danger: "bg-dangerwash text-danger border-danger/30 hover:enabled:border-danger px-4 py-3",
  }[variant];

  return (
    <button className={`${base} ${styles} ${className}`} {...rest}>
      <span>{children}</span>
      {hint && <Key inverted={variant === "primary"}>{hint}</Key>}
    </button>
  );
}

export function Key({ children, inverted }: { children: ReactNode; inverted?: boolean }) {
  return (
    <kbd
      className={
        "font-mono text-[11.5px] font-medium px-1.5 py-0.5 rounded-sm border " +
        (inverted
          ? "bg-white/15 text-accentink border-transparent"
          : "bg-ground text-inksoft border-line")
      }
    >
      {children}
    </kbd>
  );
}

/* ── Overlay panel ──────────────────────────────────────────────────────── */

export function Panel({
  title,
  hint,
  onClose,
  children,
  wide,
}: {
  title: string;
  hint?: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center p-5"
      style={{ background: "rgba(10,14,16,.55)" }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`bg-surface border border-line rounded w-full ${wide ? "max-w-3xl" : "max-w-xl"} max-h-[90vh] overflow-y-auto`}
        style={{ boxShadow: "var(--shadow)" }}
      >
        <div className="flex items-baseline justify-between gap-3 px-5 py-4 border-b border-line">
          <h2 className="m-0 text-[17px] font-semibold">{title}</h2>
          {hint && <span className="font-mono text-xs text-inkfaint">{hint}</span>}
        </div>
        <div className="p-5 grid gap-4">{children}</div>
      </div>
    </div>
  );
}

/* ── Fields ─────────────────────────────────────────────────────────────── */

export function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="grid gap-1.5">
      <span className="text-xs uppercase tracking-[.05em] text-inkfaint">{label}</span>
      {children}
      {error && <span className="text-[13px] text-danger">{error}</span>}
    </label>
  );
}

export const inputClass =
  "w-full px-3 py-2.5 bg-surface2 text-ink border border-line rounded " +
  "focus:outline-none focus:border-accent focus:ring-[3px] focus:ring-accentwash";

/* ── Callout ────────────────────────────────────────────────────────────── */

export function Callout({
  tone = "warn",
  children,
}: {
  tone?: "warn" | "danger" | "info";
  children: ReactNode;
}) {
  const styles = {
    warn: "bg-warnwash text-warn border-l-warn",
    danger: "bg-dangerwash text-danger border-l-danger",
    info: "bg-accentwash text-accent border-l-accent",
  }[tone];
  return (
    <div className={`px-3.5 py-3 rounded text-[13.5px] leading-relaxed border-l-[3px] ${styles}`}>
      {children}
    </div>
  );
}

/* ── Toasts ─────────────────────────────────────────────────────────────── */

type Tone = "info" | "good" | "warn" | "danger";
interface Toast {
  id: number;
  title: string;
  body?: string;
  tone: Tone;
}

const ToastContext = createContext<(t: Omit<Toast, "id">) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);

  const push = useCallback((t: Omit<Toast, "id">) => {
    const id = next.current++;
    setToasts((prev) => [...prev, { ...t, id }]);
    // Long enough to read a sentence while serving somebody, short enough not
    // to stack up over a busy hour.
    window.setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), 5200);
  }, []);

  const value = useMemo(() => push, [push]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="fixed left-4 bottom-4 z-[60] grid gap-2 max-w-[420px]" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={
              "rise bg-surface border border-line rounded px-3.5 py-2.5 text-[13.5px] border-l-[3px] " +
              {
                info: "border-l-accent",
                good: "border-l-good",
                warn: "border-l-warn",
                danger: "border-l-danger",
              }[t.tone]
            }
            style={{ boxShadow: "var(--shadow)" }}
          >
            <strong className="block mb-0.5">{t.title}</strong>
            {t.body && <span className="text-inksoft">{t.body}</span>}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/* ── Hotkeys ────────────────────────────────────────────────────────────── */

/**
 * Document-level key handling.
 *
 * Deliberately not scoped to a focused element. A till is driven from the
 * keyboard wherever the caret happens to be, and requiring focus on the right
 * region before F9 works is exactly the kind of hidden state that makes staff
 * distrust a system.
 */
export function useHotkeys(
  handler: (e: KeyboardEvent) => void,
  deps: React.DependencyList = [],
) {
  const ref = useRef(handler);
  useEffect(() => {
    ref.current = handler;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    const fn = (e: KeyboardEvent) => ref.current(e);
    document.addEventListener("keydown", fn);
    return () => document.removeEventListener("keydown", fn);
  }, []);
}
