import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Icon, type IconName } from "./Icon";

/** One line of the window's right-click menu. */
export interface ContextAction {
  label: string;
  icon?: IconName;
  /** A quieter word at the end of the line: what the action would act on. */
  hint?: string;
  /** There to be seen, with nothing to do: no phone, an empty clipboard. */
  disabled?: boolean;
  onSelect: () => void;
}

/**
 * The window's own right-click menu, in place of the webview's — Back,
 * Refresh, Save as, Print — which is a browser's menu in an app that is not
 * a browser. Mounted once, it covers every right-click in the window; a text
 * field keeps the webview's, which cuts, copies and pastes into it.
 *
 * `actions` is asked each time the menu opens, so what it offers can depend
 * on what there is to act on then: the files on the clipboard, the phone that
 * is connected.
 */
export function ContextMenu({ actions }: { actions: () => Promise<ContextAction[]> }) {
  const [open, setOpen] = useState<{ at: { x: number; y: number }; items: ContextAction[] } | null>(null);
  /** Where the list ended up once measured: never off the window's edge. */
  const [place, setPlace] = useState<{ left: number; top: number } | null>(null);
  const list = useRef<HTMLDivElement>(null);
  const ask = useRef(actions);
  ask.current = actions;
  /** Which opening is the current one: an older one's answer, arriving late, is dropped. */
  const seq = useRef(0);

  const shut = useRef(() => {});
  shut.current = () => {
    seq.current++;
    setOpen(null);
    setPlace(null);
  };

  useEffect(() => {
    const close = () => shut.current();
    const onMenu = (e: MouseEvent) => {
      // A surface that takes the right-click itself keeps it: the remote
      // desktop sends it to Android, and a text field keeps the webview's own
      // menu, which pastes into it.
      if (e.defaultPrevented) return;
      if ((e.target as HTMLElement | null)?.closest("input, textarea, [contenteditable='true']")) return;
      e.preventDefault();
      const mine = ++seq.current;
      const at = { x: e.clientX, y: e.clientY };
      ask.current().then((items) => {
        if (mine !== seq.current || items.length === 0) return;
        setPlace(null);
        setOpen({ at, items });
      });
    };
    const onDown = (e: MouseEvent) => {
      if (!list.current?.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("contextmenu", onMenu);
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("resize", close);
    window.addEventListener("scroll", close, true);
    window.addEventListener("blur", close);
    return () => {
      window.removeEventListener("contextmenu", onMenu);
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("resize", close);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("blur", close);
    };
  }, []);

  // Measured before it is painted, so a list near an edge never shows there first.
  useLayoutEffect(() => {
    const el = list.current;
    if (!open || !el) return;
    const { offsetWidth: w, offsetHeight: h } = el;
    setPlace({
      left: Math.max(8, Math.min(open.at.x, window.innerWidth - w - 8)),
      top: open.at.y + h > window.innerHeight - 8 ? Math.max(8, open.at.y - h) : open.at.y,
    });
    el.focus({ preventScroll: true });
  }, [open]);

  if (!open) return null;

  const walk = (e: React.KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const items = [...(list.current?.querySelectorAll<HTMLButtonElement>(".menu-item:not(:disabled)") ?? [])];
    if (!items.length) return;
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    const next = e.key === "ArrowDown" ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
    items[at === -1 ? (e.key === "ArrowDown" ? 0 : items.length - 1) : next].focus();
  };

  return createPortal(
    <div
      ref={list}
      role="menu"
      aria-label="Actions here"
      tabIndex={-1}
      onKeyDown={walk}
      className="menu card-raised"
      style={{
        position: "fixed",
        left: place?.left ?? open.at.x,
        top: place?.top ?? open.at.y,
        minWidth: 184,
        transformOrigin: "top left",
        visibility: place ? undefined : "hidden",
      }}
    >
      {open.items.map((a) => (
        <button
          key={a.label}
          type="button"
          role="menuitem"
          className="menu-item"
          disabled={a.disabled}
          onClick={() => {
            shut.current();
            a.onSelect();
          }}
        >
          {a.icon && <Icon name={a.icon} size={16} />}
          <span className="flex-1">{a.label}</span>
          {a.hint && <span className="text-[11.5px] font-normal opacity-60">{a.hint}</span>}
        </button>
      ))}
    </div>,
    document.body,
  );
}
