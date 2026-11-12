import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { Icon } from "../../ui/Icon";
import { Switch } from "../../ui/kit";
import type { Arrival, SendView } from "./model";

type Say = (text: string, tone?: "ok" | "warn" | "bad" | "info", detail?: string) => void;

/** One arrival's Show, Open, Copy link — by its id; the computer's half knows where it is. */
export function act(id: number, what: "show" | "open" | "copy" | "stop" | "forget", say: Say) {
  invoke("send_act", { id, act: what }).catch((e) => say("That didn't work", "bad", String(e)));
}

/** "Received report.pdf  Show in Finder · Open · ✕", or how far one coming in is. */
export function ArrivalStrip({ a, send, onDismiss, say }: { a: Arrival; send: SendView | null; onDismiss: () => void; say: Say }) {
  const coming = a.kind === "receiving";
  const folder = send?.showInFolder ?? "Show in folder";
  return (
    <div className={`arrival card ${a.kind === "failed" ? "arrival-bad" : ""}`} role="status" aria-live="polite">
      <span className={`tile ${a.kind === "failed" ? "tile-warn" : "tile-ok"}`}>
        {coming ? <span className="spinner" aria-hidden="true" /> : <Icon name={a.kind === "link" ? "link" : a.kind === "failed" ? "alert" : "download"} size={15} />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] text-[var(--ink-1)]">{a.title}</p>
        {coming && a.pct != null && (
          <div className="keep-bar mt-1.5" aria-hidden="true">
            <span style={{ width: `${a.pct}%` }} />
          </div>
        )}
      </div>
      {a.kind === "files" && (
        <>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => act(a.id, "show", say)}>
            {folder}
          </button>
          {a.paths.length === 1 && (
            <button type="button" className="btn btn-quiet btn-sm" onClick={() => act(a.id, "open", say)}>
              Open
            </button>
          )}
        </>
      )}
      {a.kind === "link" && (
        <button type="button" className="btn btn-quiet btn-sm" onClick={() => act(a.id, "open", say)}>
          Open
        </button>
      )}
      <button
        type="button"
        className="btn btn-quiet btn-sm btn-icon"
        onClick={() => (coming ? act(a.id, "stop", say) : onDismiss())}
        aria-label={coming ? "Stop" : "Dismiss"}
        title={coming ? "Stop" : "Dismiss"}
      >
        <Icon name="x" size={14} />
      </button>
    </div>
  );
}

/**
 * The foot of the Photos tab: what was sent from the phone, the two switches,
 * and where "Send to computer" is — or the button that puts it on the phone.
 */
export function Sent({
  serial,
  send,
  arrivals,
  say,
  onSetUp,
}: {
  serial: string;
  send: SendView | null;
  arrivals: Arrival[];
  say: Say;
  onSetUp: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [settingUp, setSettingUp] = useState(false);
  const list = arrivals.filter((a) => a.kind !== "receiving");
  const folder = send?.showInFolder ?? "Show in folder";

  const set = (args: { share?: boolean; openLinks?: boolean }) => {
    invoke("send_set", args).catch((e) => say("Couldn't change this setting", "bad", String(e)));
  };
  const setUp = () => {
    setSettingUp(true);
    invoke("send_setup", { serial })
      .then(() => {
        // Installed is all that is known here: the line below says when the share menu has it.
        say("Open Android DeX is installed on your phone", "ok");
        onSetUp();
      })
      .catch((e) => say("Couldn't set up Send to computer", "bad", String(e)))
      .finally(() => setSettingUp(false));
  };

  const missing = send?.linked && send.launcher === "missing";
  const old = send?.linked && send.launcher === "old";
  const where = whereItIs(send);

  return (
    <section className="mt-8" aria-label="Received">
      {list.length > 0 && (
        <div className="mb-4">
          <div className="flex items-center gap-2">
            <button type="button" className="link muted text-[12.5px]" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
              <Icon name="chevronRight" size={13} className="chev" />
              Received
            </button>
            {open && (
              <button type="button" className="link muted ml-auto text-[12px]" onClick={() => invoke("send_clear").catch(() => {})}>
                Clear
              </button>
            )}
          </div>
          {open && (
            <ul className="sent-list card rows rise-in mt-2.5">
              {list.map((a) => (
                <li key={a.id} className="sent-row">
                  <Icon
                    name={a.kind === "link" ? "link" : a.kind === "text" ? "clipboard" : a.kind === "failed" ? "alert" : "download"}
                    size={14}
                    className="shrink-0 text-[var(--ink-3)]"
                  />
                  <span className="min-w-0 flex-1 truncate">{a.kind === "link" ? (a.url ?? a.title) : a.title}</span>
                  <span className="shrink-0 text-[11.5px] text-[var(--ink-4)]">{when(a.at)}</span>
                  {a.kind === "files" && (
                    <button type="button" className="link text-[12px]" onClick={() => act(a.id, "show", say)}>
                      {folder}
                    </button>
                  )}
                  {a.kind === "link" && (
                    <>
                      <button type="button" className="link text-[12px]" onClick={() => act(a.id, "open", say)}>
                        Open
                      </button>
                      <button type="button" className="link text-[12px]" onClick={() => act(a.id, "copy", say)}>
                        Copy link
                      </button>
                    </>
                  )}
                  {a.kind === "text" && (
                    <button type="button" className="link text-[12px]" onClick={() => act(a.id, "copy", say)}>
                      Copy again
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {missing || old ? (
        <div className="callout items-center">
          <Icon name="send" size={15} />
          <div className="min-w-0 flex-1">
            {missing ? (
              <>
                <p className="text-[var(--ink-1)]">Send from your phone to {send?.thisComputer}</p>
                <p className="mt-0.5">This installs Open Android DeX on your phone.</p>
              </>
            ) : (
              <p className="text-[var(--ink-1)]">Update Open Android DeX on your phone to send to {send?.thisComputer}</p>
            )}
          </div>
          <button type="button" className="btn btn-secondary btn-sm" disabled={settingUp} onClick={setUp}>
            {settingUp && <span className="spinner" aria-hidden="true" />}
            {missing ? "Set up" : "Update"}
          </button>
        </div>
      ) : (
        where && (
          <p className="caption flex items-center gap-2">
            <Icon name="send" size={14} className="shrink-0" />
            <span>{where}</span>
          </p>
        )
      )}
      {send && (
        <div className="mt-3 flex flex-wrap gap-x-8 gap-y-3">
          <label className="send-switch">
            <Switch size="sm" checked={send.share} onChange={(share) => set({ share })} label="Receive files from my phone" />
            <span>Receive files from my phone</span>
          </label>
          <label className="send-switch">
            <Switch
              size="sm"
              checked={send.openLinks}
              disabled={!send.share}
              onChange={(openLinks) => set({ openLinks })}
              label="Open links automatically"
            />
            <span>Open links automatically</span>
          </label>
        </div>
      )}
    </section>
  );
}

/** Where "Send to computer" is on the phone, as it is now: said only when it is so. */
export function whereItIs(send: SendView | null): string | null {
  if (!send) return null;
  if (!send.share) return "Receiving is off.";
  if (!send.linked || send.launcher !== "ready") return null;
  if (!send.port) return "Another app on your phone is blocking Send to computer. Trying again every 10 seconds.";
  if (!send.entry) return "Setting up Send to computer on your phone…";
  return "To send from your phone, tap Share, then Send to computer.";
}

/** "14:02", or "Yesterday", or "9 Nov". */
function when(at: number): string {
  const d = new Date(at);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString([], { day: "numeric", month: "short" });
}
