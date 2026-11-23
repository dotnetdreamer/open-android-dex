import { useEffect, useRef, useState } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { error as logError } from "@tauri-apps/plugin-log";
import type { DesktopEvent, RemotePhone, TunnelVideo } from "../../../lib/types";
import { PhoneView } from "./PhoneView";

/**
 * A paired phone's own screen, in a window of its own: Remote desktop
 * (remote_desktop.rs, remote_open_phone). The phone's Android, not its Linux:
 * what the phone shows, seen and used from here through the same tunnel the
 * Linux desktop comes through, direct where the networks allow and through a
 * relay where they do not.
 *
 * This is the part that reaches the phone and comes back after the network
 * drops; PhoneView is the picture and the input.
 */

const cover: React.CSSProperties = {
  position: "fixed", inset: 0, display: "flex", flexDirection: "column", alignItems: "center",
  justifyContent: "center", gap: 12, padding: 24, textAlign: "center", background: "#090b10",
  color: "#e8ecf3", font: "13px system-ui, sans-serif",
};

/**
 * The wait before a try to come back: at once, then every 2 s for about a
 * minute (an app Android has stopped, as a Xiaomi phone's Clear all does, is
 * back by itself 2 to 20 s later), then every 15 s.
 */
function backoff(tries: number) {
  return tries <= 0 ? 0 : tries === 1 ? 500 : tries <= 30 ? 2_000 : 15_000;
}

type Link =
  | { kind: "connecting" }
  | { kind: "up"; url: string; route: string }
  | { kind: "failed"; message: string };

export function PhoneWindow({ phoneId }: { phoneId: string }) {
  const [name, setName] = useState("the phone");
  const [link, setLink] = useState<Link>({ kind: "connecting" });
  /** Each round is one try to reach the phone. */
  const [round, setRound] = useState(0);
  /** Tries since the picture was last up: each one waits a little longer. */
  const tries = useRef(0);

  useEffect(() => {
    let live = true;
    invoke<RemotePhone[]>("remote_phones")
      .then((list) => {
        const phone = list.find((p) => p.id === phoneId);
        if (phone && live) setName(phone.name);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [phoneId]);

  useEffect(() => {
    let live = true;
    const events = new Channel<DesktopEvent>();
    events.onmessage = (e) => {
      if (!live || e.kind !== "path" || !e.route) return;
      const route = e.route;
      setLink((l) => (l.kind === "up" && l.route !== route ? { ...l, route } : l));
    };
    const timer = window.setTimeout(async () => {
      try {
        const v = await invoke<TunnelVideo>("remote_phone_stream", { id: phoneId, events });
        if (live) setLink({ kind: "up", url: v.url, route: v.route ?? "Remote" });
      } catch (e) {
        logError(`remote: phone screen: ${String(e)}`);
        if (!live) return;
        setLink({ kind: "failed", message: String(e) });
        tries.current++;
        setRound((r) => r + 1);
      }
    }, backoff(tries.current));
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [phoneId, round]);

  if (link.kind !== "up") {
    return (
      <div style={cover}>
        <div style={{ fontSize: 18, fontWeight: 700 }}>{name}</div>
        <div style={{ maxWidth: 360, lineHeight: 1.45, color: "#b8c0cc" }}>
          {link.kind === "connecting" ? "Reaching the phone…" : link.message}
        </div>
        {link.kind === "failed" && <div style={{ color: "#7c8594", fontSize: 12 }}>Trying again by itself.</div>}
      </div>
    );
  }
  return (
    <PhoneView
      url={link.url}
      route={link.route}
      name={name}
      onUp={() => {
        tries.current = 0;
      }}
      onLost={() => {
        setLink({ kind: "connecting" });
        tries.current++;
        setRound((r) => r + 1);
      }}
    />
  );
}
