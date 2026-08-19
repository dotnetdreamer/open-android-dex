import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { DisplayEvent, MirrorEvent, SessionInfo } from "../../lib/types";

export interface SessionState {
  status: "running" | "reconnecting";
  pid?: number | null;
  serial: string;
  appPackage?: string | null;
  displayId?: number | null;
}

/** Live map of sessionKey → session state, fed by Rust mirror:status events. */
export function useSessions(): Record<string, SessionState> {
  const [sessions, setSessions] = useState<Record<string, SessionState>>({});

  useEffect(() => {
    let cancelled = false;
    invoke<SessionInfo[]>("list_mirror_sessions")
      .then((live) => {
        if (cancelled) return;
        setSessions((prev) => {
          const next = { ...prev };
          for (const s of live)
            next[s.sessionKey] = {
              status: "running",
              pid: s.pid,
              serial: s.serial,
              appPackage: s.appPackage,
              displayId: s.displayId,
            };
          return next;
        });
      })
      .catch(() => {});

    const un = listen<MirrorEvent>("mirror:status", ({ payload }) => {
      setSessions((prev) => {
        const next = { ...prev };
        if (payload.status === "stopped") {
          delete next[payload.sessionKey];
        } else {
          next[payload.sessionKey] = {
            status: payload.status,
            pid: payload.pid,
            serial: payload.serial,
            appPackage: payload.appPackage,
            displayId: next[payload.sessionKey]?.displayId,
          };
        }
        return next;
      });
    });
    const unDisplay = listen<DisplayEvent>("mirror:display", ({ payload }) => {
      setSessions((prev) => {
        // Never dropped for want of a session entry: the display id is what
        // the launch pipeline is waiting for, and losing it to an event-order
        // race would hang the boot screen with nothing to show for it.
        const existing = prev[payload.sessionKey] ?? {
          status: "running" as const,
          serial: payload.serial,
        };
        return {
          ...prev,
          [payload.sessionKey]: { ...existing, displayId: payload.displayId },
        };
      });
    });
    return () => {
      cancelled = true;
      un.then((f) => f());
      unDisplay.then((f) => f());
    };
  }, []);

  return sessions;
}
