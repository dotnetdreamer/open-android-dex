import { useCallback, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { warn as logWarn } from "@tauri-apps/plugin-log";
import type { DeviceInfo } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Dialog } from "../../ui/kit";
import { deviceName } from "../desktop/model";

/** What quitting now would end, as the Rust side reads it (quit.rs). */
interface Open {
  /** Phones whose desktop is up or coming up, by adb serial. */
  desktops: string[];
  /** Phone apps in windows of their own here, by name. */
  apps: string[];
  linuxDesktops: number;
}

/** "Clock", "Clock and Maps", "Clock, Maps and Chrome", "Clock, Maps, Chrome and 2 more". */
export function listOf(names: string[], most = 3): string {
  if (names.length <= 1) return names[0] ?? "";
  if (names.length <= most) return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
  return `${names.slice(0, most).join(", ")} and ${names.length - most} more`;
}

/**
 * The question before this window's ✕ ends everything it has open.
 *
 * Closing the main window quits the app, and every phone's desktop, phone
 * app's window and Linux desktop goes with it. When something is open the
 * Rust side holds the close and asks here first; with nothing open it closes
 * without a word, as it always did. This says it has the question at once,
 * before it is drawn: the Rust side quits anyway if a page never says so.
 */
export function ClosePrompt({ devices }: { devices: DeviceInfo[] }) {
  const [asked, setAsked] = useState<Open | null>(null);
  const [nudge, setNudge] = useState(0);
  const [closing, setClosing] = useState(false);
  const showing = useRef(false);
  const keep = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const un = listen<Open>("app:close-asked", (e) => {
      invoke("app_close_seen").catch(() => {});
      setAsked(e.payload);
      if (showing.current) setNudge((n) => n + 1);
      showing.current = true;
    });
    return () => {
      un.then((f) => f());
    };
  }, []);

  const answer = useCallback((close: boolean) => {
    if (close) {
      setClosing(true);
    } else {
      setAsked(null);
      showing.current = false;
    }
    invoke("app_close_answer", { close }).catch((e) => {
      logWarn(`close: the answer did not reach the app — ${String(e)}`);
      setClosing(false);
    });
  }, []);

  const keepOpen = useCallback(() => {
    if (!closing) answer(false);
  }, [answer, closing]);

  /** What the ✕ may have been meant for: out of the way, everything still running. */
  const minimize = useCallback(() => {
    answer(false);
    getCurrentWindow()
      .minimize()
      .catch(() => {});
  }, [answer]);

  const phone = (serial: string) => {
    const d = devices.find((x) => x.serial === serial);
    const name = d ? deviceName(d) : "";
    return !name || name === "Your phone" ? "your phone" : name;
  };

  const rows: { icon: "laptop" | "apps" | "terminal"; text: string }[] = [];
  if (asked) {
    if (asked.desktops.length) {
      const names = [...new Set(asked.desktops.map(phone))];
      rows.push({ icon: "laptop", text: `DeX on ${listOf(names)}` });
    }
    if (asked.apps.length) rows.push({ icon: "apps", text: listOf(asked.apps) });
    if (asked.linuxDesktops === 1) rows.push({ icon: "terminal", text: "Linux's desktop" });
    else if (asked.linuxDesktops > 1) rows.push({ icon: "terminal", text: `${asked.linuxDesktops} Linux desktops` });
  }
  const some = rows.length > 1 || (asked?.apps.length ?? 0) > 1 || (asked?.desktops.length ?? 0) > 1;

  return (
    <Dialog
      open={asked !== null}
      title="Close Open Android DeX?"
      onDismiss={keepOpen}
      initialFocus={keep}
      nudge={nudge}
      actions={
        <>
          <button ref={keep} type="button" className="btn btn-secondary" onClick={keepOpen} disabled={closing}>
            Cancel
          </button>
          <button type="button" className="btn btn-danger" onClick={() => answer(true)} disabled={closing}>
            {closing ? "Closing…" : "Close"}
          </button>
        </>
      }
    >
      <p>{some ? "These close with it:" : "This closes with it:"}</p>
      <ul className="dialog-list">
        {rows.map((r) => (
          <li key={r.icon}>
            <Icon name={r.icon} size={16} />
            <span className="min-w-0">{r.text}</span>
          </li>
        ))}
      </ul>
      <p className="dialog-note">
        To keep {some ? "them" : "it"} running,{" "}
        <button type="button" className="link" onClick={minimize} disabled={closing}>
          minimize this window
        </button>{" "}
        instead.
      </p>
    </Dialog>
  );
}
