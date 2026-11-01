import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Icon, type IconName } from "./Icon";

/**
 * The few controls the screens share, beyond the classes in index.css.
 */

// ── Segmented control ──────────────────────────────────────────────────

export interface SegmentOption<T extends string> {
  value: T;
  label: string;
  icon?: IconName;
  /** Something is under way behind this option: a small live dot says so. */
  busy?: boolean;
}

/**
 * One of a few, with a thumb that slides to the choice. Rendered as tabs or as
 * a radio group, whichever the caller says it is, so screen readers hear the
 * right thing.
 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  role = "tablist",
  size = "md",
  className = "",
}: {
  value: T;
  options: SegmentOption<T>[];
  onChange: (value: T) => void;
  label: string;
  role?: "tablist" | "radiogroup";
  size?: "md" | "lg";
  className?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [thumb, setThumb] = useState<{ x: number; w: number; ready: boolean } | null>(null);

  // The thumb sits under the chosen item, measured rather than computed, so it
  // stays right however the labels wrap in another language.
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const place = () => {
      const item = el.querySelector<HTMLElement>(`[data-value="${CSS.escape(value)}"]`);
      if (item) setThumb((t) => ({ x: item.offsetLeft, w: item.offsetWidth, ready: t !== null }));
    };
    place();
    const ro = new ResizeObserver(place);
    ro.observe(el);
    return () => ro.disconnect();
  }, [value, options.length]);

  const itemRole = role === "tablist" ? "tab" : "radio";
  const move = (e: React.KeyboardEvent, i: number) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const next = options[(i + step + options.length) % options.length];
    onChange(next.value);
    box.current?.querySelector<HTMLElement>(`[data-value="${CSS.escape(next.value)}"]`)?.focus();
  };

  return (
    <div ref={box} role={role} aria-label={label} className={`segmented ${size === "lg" ? "segmented-lg" : ""} ${className}`}>
      {thumb && (
        <span
          className="segmented-thumb"
          aria-hidden="true"
          style={{
            width: thumb.w,
            transform: `translateX(${thumb.x - 2}px)`,
            left: 2,
            transition: thumb.ready ? undefined : "none",
          }}
        />
      )}
      {options.map((o, i) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role={itemRole}
            data-value={o.value}
            aria-selected={itemRole === "tab" ? on : undefined}
            aria-checked={itemRole === "radio" ? on : undefined}
            tabIndex={on ? 0 : -1}
            className="segmented-item"
            onClick={() => onChange(o.value)}
            onKeyDown={(e) => move(e, i)}
          >
            {o.icon && <Icon name={o.icon} size={15} />}
            {o.label}
            {o.busy && (
              <>
                <span className="dot dot-accent dot-live !h-1.5 !w-1.5 !shadow-none" aria-hidden="true" />
                <span className="sr-only">(working)</span>
              </>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ── Menu ───────────────────────────────────────────────────────────────

/**
 * A button that opens a short list of actions. Closes on a choice, a click
 * anywhere else, Escape — which also hands focus back to the button — or the
 * page moving under it. Arrow keys walk the items.
 *
 * The list is drawn into the body, not beside its button. The help menu's
 * button lives in the titlebar, which is a drag region all the way down: a
 * list inside it started a window drag from any pixel that was not a button
 * (a label, a separator, its padding), and Tauri's drag handler stops the
 * mousedown at the document, so a click elsewhere on the titlebar never
 * reached the listener that closes the list. Hence the portal, and hence the
 * capture-phase listener, which hears the click before the document does.
 */
export function Menu({
  trigger,
  children,
  align = "right",
  label,
}: {
  trigger: (props: { open: boolean; toggle: () => void; ref: React.Ref<HTMLButtonElement> }) => ReactNode;
  children: (close: () => void) => ReactNode;
  align?: "left" | "right";
  label: string;
}) {
  const [open, setOpen] = useState(false);
  /** Where the list sits, in the window's coordinates; `up` when it opened above its button. */
  const [at, setAt] = useState<{ top: number; left?: number; right?: number; up?: boolean } | null>(null);
  const button = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);

  const close = useCallback((refocus = false) => {
    setOpen(false);
    if (refocus) button.current?.focus();
  }, []);

  const toggle = () => {
    if (open) {
      close();
      return;
    }
    const r = button.current?.getBoundingClientRect();
    if (!r) return;
    setAt(align === "right" ? { top: r.bottom + 6, right: window.innerWidth - r.right } : { top: r.bottom + 6, left: r.left });
    setOpen(true);
  };

  // Not enough room below: open upwards instead of off the bottom of the window.
  useLayoutEffect(() => {
    if (!open || !at || at.up || !list.current || !button.current) return;
    const h = list.current.offsetHeight;
    const r = button.current.getBoundingClientRect();
    if (at.top + h > window.innerHeight - 8 && r.top - h - 6 > 8) setAt({ ...at, top: r.top - h - 6, up: true });
  }, [open, at]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node;
      if (list.current?.contains(t) || button.current?.contains(t)) return;
      close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        close(true);
      }
    };
    const away = () => close();
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("resize", away);
    window.addEventListener("scroll", away, true);
    window.addEventListener("blur", away);
    list.current?.focus({ preventScroll: true });
    return () => {
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("resize", away);
      window.removeEventListener("scroll", away, true);
      window.removeEventListener("blur", away);
    };
  }, [open, close]);

  const walk = (e: React.KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const items = [...(list.current?.querySelectorAll<HTMLButtonElement>(".menu-item:not(:disabled)") ?? [])];
    if (!items.length) return;
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    const next = e.key === "ArrowDown" ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
    items[at === -1 ? (e.key === "ArrowDown" ? 0 : items.length - 1) : next].focus();
  };

  return (
    <>
      {trigger({ open, toggle, ref: button })}
      {open &&
        at &&
        createPortal(
          <div
            ref={list}
            role="menu"
            aria-label={label}
            tabIndex={-1}
            onKeyDown={walk}
            className="menu card-raised"
            style={{
              position: "fixed",
              top: at.top,
              left: at.left,
              right: at.right,
              transformOrigin: `${at.up ? "bottom" : "top"} ${align}`,
            }}
          >
            {children(() => close())}
          </div>,
          document.body,
        )}
    </>
  );
}

export function MenuItem({
  icon,
  children,
  onSelect,
  disabled,
  danger,
  hint,
}: {
  icon?: IconName;
  children: ReactNode;
  onSelect: () => void;
  disabled?: boolean;
  danger?: boolean;
  hint?: string;
}) {
  return (
    <button type="button" role="menuitem" className={`menu-item ${danger ? "danger" : ""}`} onClick={onSelect} disabled={disabled}>
      {icon && <Icon name={icon} size={16} />}
      <span className="flex-1">{children}</span>
      {hint && <span className="text-[11.5px] font-normal opacity-60">{hint}</span>}
    </button>
  );
}

// ── Popover ────────────────────────────────────────────────────────────

/**
 * A panel that opens from a button and stays while it is used — switches, a
 * search, a list — where a Menu goes with the first choice. Drawn into the
 * body for the titlebar's sake, as a Menu is (see there), right-aligned under
 * its button. It closes on a click anywhere else and on Escape (focus back to
 * the button); not on a scroll inside it, which a long list needs, and the
 * window resizing only moves it.
 *
 * Not on the window losing focus, as a Menu does: a switch pressed by a
 * screen reader (UI Automation's Toggle) takes the window's focus away and
 * back, and the panel closed under the press, which never landed.
 */
export function Popover({
  open,
  onOpenChange,
  trigger,
  children,
  label,
  width = 360,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  trigger: (props: { open: boolean; toggle: () => void; ref: React.Ref<HTMLButtonElement> }) => ReactNode;
  children: ReactNode;
  label: string;
  width?: number;
}) {
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState<{ top: number; right: number } | null>(null);

  const place = useCallback(() => {
    const r = button.current?.getBoundingClientRect();
    if (r) setAt({ top: r.bottom + 6, right: Math.max(8, window.innerWidth - r.right) });
  }, []);

  useLayoutEffect(() => {
    if (open) place();
  }, [open, place]);

  const close = useCallback(
    (refocus = false) => {
      onOpenChange(false);
      if (refocus) button.current?.focus();
    },
    [onOpenChange],
  );

  useEffect(() => {
    if (!open) return;
    const inside = (t: EventTarget | null) =>
      !!t && (panel.current?.contains(t as Node) || button.current?.contains(t as Node));
    const onDown = (e: MouseEvent) => {
      if (!inside(e.target)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        close(true);
      }
    };
    const onScroll = (e: Event) => {
      if (!inside(e.target)) close();
    };
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("scroll", onScroll, true);
    window.addEventListener("resize", place);
    panel.current?.focus({ preventScroll: true });
    return () => {
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", place);
    };
  }, [open, close, place]);

  return (
    <>
      {trigger({ open, toggle: () => onOpenChange(!open), ref: button })}
      {open &&
        at &&
        createPortal(
          <div
            ref={panel}
            role="dialog"
            aria-label={label}
            tabIndex={-1}
            className="popover card-raised"
            style={{
              position: "fixed",
              top: at.top,
              right: at.right,
              width: `min(${width}px, calc(100vw - 16px))`,
              maxHeight: `calc(100vh - ${at.top + 10}px)`,
            }}
          >
            {children}
          </div>,
          document.body,
        )}
    </>
  );
}

// ── Dialog ─────────────────────────────────────────────────────────────

/**
 * A question the window waits on. Drawn over all of it, the rest out of reach
 * until it is answered: to the pointer, the keyboard and a screen reader
 * (`inert`). Focus starts on `initialFocus`, the safe answer, and Tab goes
 * round the dialog's own controls. Escape, or a click beside it, is the safe
 * answer too, and focus goes back where it was when it closes.
 *
 * `nudge` changing plays a small pulse: the same question asked again while
 * it is up (✕ pressed twice) says "here" rather than stacking a second one.
 */
export function Dialog({
  open,
  title,
  children,
  actions,
  onDismiss,
  initialFocus,
  nudge = 0,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  actions: ReactNode;
  onDismiss: () => void;
  initialFocus?: React.RefObject<HTMLElement | null>;
  nudge?: number;
}) {
  const box = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const bodyId = useId();
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;

  useEffect(() => {
    if (!open) return;
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const root = document.getElementById("root");
    root?.setAttribute("inert", "");
    (initialFocus?.current ?? box.current)?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopImmediatePropagation();
        dismiss.current();
        return;
      }
      if (e.key !== "Tab" || !box.current) return;
      const items = [
        ...box.current.querySelectorAll<HTMLElement>(
          "button:not(:disabled), a[href], input:not(:disabled), [tabindex]:not([tabindex='-1'])",
        ),
      ];
      if (!items.length) return;
      e.preventDefault();
      const at = items.indexOf(document.activeElement as HTMLElement);
      const next = e.shiftKey ? (at <= 0 ? items.length - 1 : at - 1) : at === items.length - 1 ? 0 : at + 1;
      items[next].focus();
    };
    window.addEventListener("keydown", onKey, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      root?.removeAttribute("inert");
      before?.focus({ preventScroll: true });
    };
  }, [open, initialFocus]);

  if (!open) return null;
  return createPortal(
    <div
      className="dialog-scrim"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) dismiss.current();
      }}
    >
      <div
        ref={box}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        tabIndex={-1}
        className={`dialog card-raised ${nudge ? (nudge % 2 ? "nudge-a" : "nudge-b") : ""}`}
      >
        <h2 id={titleId} className="dialog-title">
          {title}
        </h2>
        <div id={bodyId} className="dialog-body">
          {children}
        </div>
        <div className="dialog-actions">{actions}</div>
      </div>
    </div>,
    document.body,
  );
}

// ── Switch ─────────────────────────────────────────────────────────────

/**
 * On or off, as a switch — to a screen reader too (role="switch"). The thumb
 * stretches while it is pressed and springs across when let go.
 */
export function Switch({
  checked,
  onChange,
  label,
  disabled = false,
  size = "md",
}: {
  checked: boolean;
  onChange: (on: boolean) => void;
  label: string;
  disabled?: boolean;
  size?: "md" | "sm";
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`switch ${size === "sm" ? "switch-sm" : ""}`}
      onClick={() => onChange(!checked)}
    >
      <span className="switch-thumb" aria-hidden="true" />
    </button>
  );
}

// ── Toast ──────────────────────────────────────────────────────────────

export type Tone = "info" | "ok" | "warn" | "bad";

export interface ToastMessage {
  id: number;
  text: string;
  tone: Tone;
  /** A second, quieter line: a path, a detail. */
  detail?: string;
}

const TONE_ICON: Record<Tone, { icon: IconName; color: string }> = {
  info: { icon: "info", color: "text-[var(--accent-ink)]" },
  ok: { icon: "check", color: "text-[var(--ok)]" },
  warn: { icon: "alert", color: "text-[var(--warn)]" },
  bad: { icon: "alert", color: "text-[var(--bad)]" },
};

/**
 * One line of feedback at the foot of the window, for things that happened
 * because of a click: a report written, a phone that would not do as asked.
 * Good news goes by itself; a problem stays until it is dismissed.
 */
export function useToast() {
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const seq = useRef(0);
  const timer = useRef(0);

  const dismiss = useCallback(() => {
    window.clearTimeout(timer.current);
    setToast(null);
  }, []);

  const say = useCallback((text: string, tone: Tone = "info", detail?: string) => {
    window.clearTimeout(timer.current);
    const id = ++seq.current;
    setToast({ id, text, tone, detail });
    if (tone === "ok" || tone === "info") {
      timer.current = window.setTimeout(() => setToast((t) => (t?.id === id ? null : t)), 6000);
    }
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);
  return { toast, say, dismiss };
}

export function Toast({ toast, onDismiss }: { toast: ToastMessage | null; onDismiss: () => void }) {
  if (!toast) return null;
  const { icon, color } = TONE_ICON[toast.tone];
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-40 flex justify-center px-4" role="status" aria-live="polite">
      <div key={toast.id} className="toast card-raised pointer-events-auto">
        <Icon name={icon} size={16} className={color} />
        <div className="min-w-0 flex-1">
          <p className="text-[var(--ink-1)]">{toast.text}</p>
          {toast.detail && <p className="mt-0.5 truncate text-[11.5px] text-[var(--ink-3)]">{toast.detail}</p>}
        </div>
        <button type="button" className="btn btn-quiet btn-sm btn-icon -my-1" onClick={onDismiss} aria-label="Dismiss">
          <Icon name="x" size={14} />
        </button>
      </div>
    </div>
  );
}

// ── Small pieces ───────────────────────────────────────────────────────

/** A section that opens in place, for detail most people never need. */
export function Disclosure({
  title,
  children,
  defaultOpen = false,
  className = "",
}: {
  title: ReactNode;
  children: ReactNode;
  defaultOpen?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className={className}>
      <button type="button" className="link muted text-[12.5px]" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon name="chevronRight" size={13} className="chev" />
        {title}
      </button>
      {open && <div className="rise-in mt-2.5">{children}</div>}
    </div>
  );
}

/** "samsung SM-S938B" → "Samsung SM-S938B"; an mDNS name → something a person can read. */
export function phoneLabel(label: string): string {
  if (/^adb-[A-Za-z0-9]+-/.test(label)) return "Android phone";
  return label.replace(/^([a-z])/, (c) => c.toUpperCase());
}
