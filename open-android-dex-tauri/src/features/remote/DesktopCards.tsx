import type { DesktopReceived, PutProgress, PutReport } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Stage } from "../../ui/Stage";
import type { ClipboardShare, ToGuest } from "./clipboardShare";

/**
 * What a phone's desktop window shows around the desktop, whichever way the
 * desktop reaches it (DesktopRouter.tsx): files dropped on it on their way to
 * the guest's Desktop, what Linux sent on its way to Downloads, and the
 * window's own sentences.
 */

/** What the window does for the view that shows the desktop, over adb or through the tunnel. */
export interface DesktopHost {
  /** The clipboard, once the switch for this phone has been read. */
  readonly clipboard: Promise<ClipboardShare>;
  /**
   * Where the clipboard's word for the guest goes while this view has the
   * desktop. Returns the way to let go, which lets go only if no other view
   * has taken over since.
   */
  attach(sink: (msg: ToGuest) => void): () => void;
  /** The desktop is showing — a drop lands on it — or it is not. */
  showing(up: boolean): void;
}

/** A byte count the way a file manager says one (as dexremote::sftp::size). */
export function size(bytes: number) {
  if (bytes < 1000) return bytes === 1 ? "1 byte" : `${bytes} bytes`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1000;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/** "report.pdf", "report.pdf and photos", "report.pdf and 2 more". */
function names(landed: string[]) {
  if (landed.length <= 2) return landed.join(" and ");
  return `${landed[0]} and ${landed.length - 1} more`;
}

/** What a copy did not bring along, in a line; empty when it brought everything. */
function leftOut(r: PutReport) {
  const parts: string[] = [];
  if (r.links) parts.push(r.links === 1 ? "1 link was left out" : `${r.links} links were left out`);
  if (r.unreadable) parts.push(r.unreadable === 1 ? "1 item could not be read" : `${r.unreadable} items could not be read`);
  return parts.join(" · ");
}

export function merge(a: PutReport | null, b: PutReport): PutReport {
  if (!a) return b;
  return {
    ...b,
    landed: [...a.landed, ...b.landed],
    files: a.files + b.files,
    bytes: a.bytes + b.bytes,
    links: a.links + b.links,
    unreadable: a.unreadable + b.unreadable,
  };
}

/** What Linux sent, as the card in the corner shows it. */
export type Inbox = { kind: "receiving"; name: string } | { kind: "received"; got: DesktopReceived };

/** Files dropped on the window, as the card in its corner shows them. */
export type Copy =
  | { kind: "copying"; progress: PutProgress | null; waiting: number }
  | { kind: "done"; report: PutReport }
  | { kind: "stopped"; report: PutReport }
  | { kind: "failed"; message: string };

/**
 * What the window shows instead of the desktop: reaching the phone, starting
 * the desktop on it, or why it cannot. The same picture as the main window —
 * phone, computer, the link between them — so a person who has seen one knows
 * how to read the other.
 */
export function DesktopCover({
  name,
  state,
  message,
  via = "wifi",
  onAgain,
  onClose,
}: {
  name: string;
  state: "connecting" | "starting" | "stopped" | "failed";
  message?: string;
  /** Over adb on a cable, or over the network. */
  via?: "usb" | "wifi";
  onAgain?: () => void;
  onClose?: () => void;
}) {
  const busy = state === "connecting" || state === "starting";
  const copy: Record<typeof state, [string, string]> = {
    connecting: [`Reaching ${name}…`, ""],
    starting: [`Starting the desktop on ${name}…`, "The first start can take a minute."],
    stopped: [`The desktop isn't running on ${name}`, "It was shut down, or logged out of, on the phone."],
    failed: [`Couldn't open the desktop on ${name}`, message ?? ""],
  };
  const [title, detail] = copy[state];
  return (
    <div className="app-backdrop fade-in absolute inset-0 flex overflow-y-auto p-6">
      <div className="m-auto flex w-[460px] max-w-full flex-col items-center text-center">
        <div className="w-[min(300px,62vw)]">
          <Stage
            scene={state === "failed" ? "error" : state === "stopped" ? "ended" : "launching"}
            via={via}
            progress={state === "starting" ? 1 : 0}
          />
        </div>
        <h1 className="display mt-5 !text-[21px] !leading-[27px]">{title}</h1>
        {detail && <p className="lede mt-2 select-text">{detail}</p>}
        {busy ? (
          <span className="spinner spinner-lg mt-6" aria-hidden="true" />
        ) : (
          (onAgain || onClose) && (
            <div className="mt-6 flex gap-2.5">
              {onAgain && (
                <button className="btn btn-primary btn-lg" onClick={onAgain}>
                  <Icon name={state === "stopped" ? "play" : "refresh"} size={state === "stopped" ? 14 : 16} />
                  {state === "stopped" ? "Start it" : "Try again"}
                </button>
              )}
              {onClose && (
                <button className="btn btn-secondary btn-lg" onClick={onClose}>
                  Close
                </button>
              )}
            </div>
          )
        )}
      </div>
    </div>
  );
}

/**
 * Over everything while files are dragged across the window. No blur: it lies
 * over a picture that repaints all the time.
 */
export function DropCover({ name, open, busy }: { name: string; open: boolean; busy: boolean }) {
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/65 p-6">
      <div
        className={`flex w-[440px] max-w-full flex-col items-center gap-2 rounded-[var(--r-xl)] border-[1.5px] border-dashed bg-[var(--bg-overlay)]/95 px-10 py-9 text-center ${
          open ? "border-[var(--accent)] shadow-[0_0_0_6px_var(--accent-soft)]" : "border-white/20"
        }`}
      >
        <span className={`tile tile-lg mb-1 ${open ? "tile-accent" : ""}`}>
          <Icon name="upload" size={22} />
        </span>
        <p className="heading text-[15px]">
          {open ? `Drop to copy to the Desktop on ${name}` : `The desktop on ${name} isn't open yet`}
        </p>
        <p className="body">
          {!open
            ? "Wait for it to open, then drop again."
            : busy
              ? "It's copied as soon as the copy in progress has finished."
              : "Folders come with everything in them. Nothing already there is replaced."}
        </p>
      </div>
    </div>
  );
}

/** The copy in the window's corner: how far, and the way to stop it. */
export function CopyCard({ copy, name, onCancel, onClose }: { copy: Copy; name: string; onCancel: () => void; onClose: () => void }) {
  let title: string;
  let detail: string;
  let note = "";
  let done: number | null = null;
  if (copy.kind === "copying") {
    const p = copy.progress;
    title = `Copying to the Desktop on ${name}`;
    if (!p) detail = "Reaching the phone…";
    else if (p.filesTotal > 1) detail = `${p.current || "…"} · ${Math.min(p.filesDone + 1, p.filesTotal)} of ${p.filesTotal}`;
    else detail = p.current || "…";
    if (p) done = p.bytesTotal > 0 ? p.bytesDone / p.bytesTotal : p.filesTotal > 0 ? p.filesDone / p.filesTotal : 0;
    if (copy.waiting > 0) note = copy.waiting === 1 ? "Then 1 more drop" : `Then ${copy.waiting} more drops`;
  } else if (copy.kind === "done") {
    title = `Copied to the Desktop on ${name}`;
    detail = `${names(copy.report.landed)} · ${size(copy.report.bytes)}`;
    note = leftOut(copy.report);
  } else if (copy.kind === "stopped") {
    title = "Copy stopped";
    detail = copy.report.landed.length
      ? `${names(copy.report.landed)} stayed; what was in progress was taken back.`
      : "What was in progress was taken back; nothing was left on the phone.";
  } else {
    title = "The copy did not finish";
    detail = copy.message;
  }
  const tone = copy.kind === "failed" ? "tile-warn" : copy.kind === "done" ? "tile-ok" : "tile-accent";
  return (
    <div className="toast card-raised pointer-events-auto !w-[380px] max-w-full !items-start !py-3 !pl-3 !pr-2.5">
      <span className={`tile ${tone}`}>
        <Icon name={copy.kind === "done" ? "check" : copy.kind === "failed" ? "alert" : "upload"} size={16} />
      </span>
      <div className="min-w-0 flex-1 pt-px">
        <p className="heading truncate">{title}</p>
        <p className={copy.kind === "failed" ? "caption mt-0.5 !text-[#f3dcb2]" : "caption mt-0.5 truncate"}>{detail}</p>
        {note && <p className="caption mt-0.5 truncate !text-[var(--ink-4)]">{note}</p>}
        {done !== null && (
          <div className="mt-2.5 h-1 overflow-hidden rounded-full bg-white/10">
            <div
              className="h-full rounded-full bg-[var(--accent)] transition-[width] duration-200"
              style={{ width: `${Math.round(done * 100)}%` }}
            />
          </div>
        )}
      </div>
      {copy.kind === "copying" ? (
        <button className="btn btn-quiet btn-sm shrink-0" onClick={onCancel}>
          Cancel
        </button>
      ) : (
        <button className="btn btn-quiet btn-sm btn-icon shrink-0" onClick={onClose} aria-label="Close">
          <Icon name="x" size={14} />
        </button>
      )}
    </div>
  );
}

/** What Linux sent: on its way, then saved, with the way to it. */
export function InboxCard({ inbox, onShow, onClose }: { inbox: Inbox; onShow: (path: string) => void; onClose: () => void }) {
  if (inbox.kind === "receiving") {
    return (
      <div className="toast card-raised pointer-events-auto !w-[380px] max-w-full !items-center !py-3 !pl-3">
        <span className="tile tile-accent">
          <span className="spinner !h-3.5 !w-3.5 !border-[1.5px]" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="heading truncate">Receiving from Linux…</p>
          <p className="caption mt-0.5 truncate">{inbox.name}</p>
        </div>
      </div>
    );
  }
  const got = inbox.got;
  const saved = got.landed.length > 0;
  const notes: string[] = [];
  if (got.links) notes.push(got.links === 1 ? "1 link was left out" : `${got.links} links were left out`);
  if (got.missing) notes.push(got.missing === 1 ? "1 item was gone from Linux" : `${got.missing} items were gone from Linux`);
  if (got.failed.length) notes.push(got.failed.length === 1 ? `Not saved: ${got.failed[0]}` : `${got.failed.length} could not be saved here`);
  return (
    <div className="toast card-raised pointer-events-auto !w-[380px] max-w-full !items-start !py-3 !pl-3 !pr-2.5">
      <span className={`tile ${saved ? "tile-ok" : "tile-warn"}`}>
        <Icon name={saved ? "download" : "alert"} size={16} />
      </span>
      <div className="min-w-0 flex-1 pt-px">
        <p className="heading truncate">{saved ? "Saved to Downloads" : "Nothing was saved"}</p>
        <p className="caption mt-0.5 truncate">
          {saved ? `${names(got.landed)} · ${size(got.bytes)}` : "What was sent had gone from Linux, or couldn't be read there."}
        </p>
        {notes.length > 0 && <p className="caption mt-0.5 truncate !text-[var(--ink-4)]">{notes.join(" · ")}</p>}
      </div>
      {saved && got.first && (
        <button className="btn btn-secondary btn-sm shrink-0" onClick={() => onShow(got.first!)}>
          Show
        </button>
      )}
      <button className="btn btn-quiet btn-sm btn-icon shrink-0" onClick={onClose} aria-label="Close">
        <Icon name="x" size={14} />
      </button>
    </div>
  );
}

/** A sentence over the picture, in the window's own words — never the guest's. */
export function Notice({ text }: { text: string }) {
  return (
    <div className="pointer-events-none absolute inset-x-0 top-14 flex justify-center px-4">
      <div className="toast card-raised !w-auto max-w-[560px] !items-center !px-4 !py-2.5 text-center">
        <Icon name="info" size={15} className="text-[var(--accent-ink)]" />
        <span>{text}</span>
      </div>
    </div>
  );
}
