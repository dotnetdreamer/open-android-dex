import { useCallback, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { info as logInfo, warn as logWarn } from "@tauri-apps/plugin-log";
import type { PmosPhone, PmosSessionEvent } from "../../lib/types";

/**
 * A postmarketOS phone on the Desktop tab (pmos.rs): which one is on the
 * cable, signing in to it, and its desktop's session.
 *
 * Beside the Android flow in App.tsx, not part of it: it has its own phone,
 * its own poll and its own session, and the adb path is not touched. Like
 * that flow it opens the desktop by itself when a phone it knows is plugged
 * in, once per plug-in, and never while an Android desktop is opening or open.
 */

export type PmosSession =
  | { kind: "idle" }
  /** Connecting (0), putting the helper on the phone (1), starting its screen (2). */
  | { kind: "launching"; step: number }
  | { kind: "running" }
  /** `message`: why, when it was not closed on purpose. */
  | { kind: "ended"; message?: string }
  | { kind: "failed"; message: string };

export interface Pmos {
  /** The phone on the cable, or null. */
  phone: PmosPhone | null;
  session: PmosSession;
  /** The name of the phone the session is, or was, for. */
  name: string;
  /** A sign-in is under way. */
  signingIn: boolean;
  /** Why the last sign-in failed. */
  signInError: string;
  signIn: (user: string, password: string) => void;
  open: () => void;
  show: () => void;
  disconnect: () => void;
  /** Back from a closed or failed session to whatever the cable has now. */
  dismiss: () => void;
}

/** As often as the Android phones are looked for: the poll is a routing-table lookup. */
const POLL_MS = 2500;

export function usePmos(androidBusy: boolean): Pmos {
  const [phone, setPhone] = useState<PmosPhone | null>(null);
  const [session, setSession] = useState<PmosSession>({ kind: "idle" });
  const [name, setName] = useState("");
  const [signingIn, setSigningIn] = useState(false);
  const [signInError, setSignInError] = useState("");
  /** Opens by itself when a phone signed in to is plugged in: once per plug-in. */
  const armed = useRef(true);
  const phoneRef = useRef(phone);
  phoneRef.current = phone;

  useEffect(() => {
    let live = true;
    let inFlight = false;
    const poll = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const found = await invoke<PmosPhone | null>("pmos_status");
        if (!live) return;
        setPhone((was) => (JSON.stringify(was) === JSON.stringify(found) ? was : found));
        // Unplugged: its next plug-in opens the desktop again.
        if (!found) armed.current = true;
      } catch {
        // the next tick asks again
      } finally {
        inFlight = false;
      }
    };
    void poll();
    const timer = setInterval(poll, POLL_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    const apply = (e: PmosSessionEvent, live: boolean) => {
      // Opened from anywhere (a card here, the Linux tab): this plug-in's open
      // has happened, and closing it must not open it again by itself.
      if (e.state === "launching" || e.state === "running") armed.current = false;
      setName(e.name);
      if (e.state === "launching") setSession({ kind: "launching", step: e.step ?? 0 });
      else if (e.state === "running") setSession({ kind: "running" });
      else if (e.state === "ended") setSession({ kind: "ended", message: e.message });
      else setSession({ kind: "failed", message: e.message || "The desktop didn't open." });
      if (!live) return;
      // As DeX does: the phone's desktop window is the star while it is up,
      // and this one comes back to say why when it goes.
      const main = getCurrentWindow();
      if (e.state === "running") setTimeout(() => main.minimize().catch(() => {}), 1400);
      else if (e.state === "ended" || e.state === "failed") {
        main.unminimize().catch(() => {});
        main.setFocus().catch(() => {});
      }
    };
    // A session that outlived this page (the main window reloaded).
    invoke<PmosSessionEvent | null>("pmos_session")
      .then((e) => {
        if (e && (e.state === "launching" || e.state === "running")) {
          armed.current = false;
          apply(e, false);
        }
      })
      .catch(() => {});
    const un = listen<PmosSessionEvent>("pmos:session", ({ payload }) => apply(payload, true));
    return () => {
      un.then((f) => f());
    };
  }, []);

  const openId = useCallback((id: string) => {
    armed.current = false;
    // A failure comes back as the session's own event, with the reason.
    invoke("pmos_open", { id }).catch((e) => logWarn(`pmos: ${String(e)}`));
  }, []);

  // Plugged in, signed in to before, nothing else opening: open its desktop.
  useEffect(() => {
    if (!phone?.paired || !phone.id || androidBusy || !armed.current) return;
    if (session.kind === "launching" || session.kind === "running") return;
    logInfo(`pmos: ${phone.name ?? "a postmarketOS phone"} is on the cable; opening its desktop`);
    openId(phone.id);
  }, [phone, androidBusy, session.kind, openId]);

  const signIn = useCallback(
    (user: string, password: string) => {
      setSigningIn(true);
      setSignInError("");
      invoke<PmosPhone>("pmos_pair", { user, password })
        .then((p) => {
          setPhone(p);
          openId(p.id);
        })
        .catch((e) => setSignInError(String(e)))
        .finally(() => setSigningIn(false));
    },
    [openId],
  );

  const open = useCallback(() => {
    const id = phoneRef.current?.id;
    if (id) openId(id);
  }, [openId]);

  const show = useCallback(() => {
    const id = phoneRef.current?.id;
    if (id) invoke("pmos_open", { id }).catch(() => {});
  }, []);

  const disconnect = useCallback(() => {
    invoke("pmos_close").catch(() => {});
  }, []);

  const dismiss = useCallback(() => setSession({ kind: "idle" }), []);

  return { phone, session, name, signingIn, signInError, signIn, open, show, disconnect, dismiss };
}
