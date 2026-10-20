import type { DesktopReceived, PutProgress, PutReport } from "../../lib/types";
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
 * Over everything while files are dragged across the window. No blur: it lies
 * over a picture that repaints all the time.
 */
export function DropCover({ name, open, busy }: { name: string; open: boolean; busy: boolean }) {
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/60 p-6">
      <div
        className={`flex max-w-full flex-col items-center gap-2 rounded-[var(--item-radius)] border-2 border-dashed bg-[#0d1117]/95 px-10 py-8 text-center ${
          open ? "border-[var(--accent)]" : "border-white/20"
        }`}
      >
        <svg
          viewBox="0 0 24 24"
          className={`h-9 w-9 ${open ? "text-[var(--accent)]" : "text-slate-500"}`}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M12 3.5v11m0 0-4.2-4.2M12 14.5l4.2-4.2" />
          <path d="M4 14.5v3.3A2.2 2.2 0 0 0 6.2 20h11.6a2.2 2.2 0 0 0 2.2-2.2v-3.3" />
        </svg>
        <p className="text-[15px] font-semibold text-slate-100">
          {open ? `Drop to copy to the Desktop on ${name}` : `The desktop on ${name} is not open yet`}
        </p>
        <p className="text-[12.5px] text-slate-400">
          {!open
            ? "Wait for it to open, then drop again."
            : busy
              ? "It is copied as soon as the copy in progress has finished."
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
  return (
    <div className="fade-in pointer-events-auto w-[360px] max-w-full rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">{title}</p>
          <p className={copy.kind === "failed" ? "mt-0.5 leading-relaxed text-amber-300/90" : "mt-0.5 truncate text-slate-400"}>
            {detail}
          </p>
          {note && <p className="mt-0.5 truncate text-slate-500">{note}</p>}
        </div>
        {copy.kind === "copying" ? (
          <button className="link-btn shrink-0" onClick={onCancel}>
            Cancel
          </button>
        ) : (
          <button className="link-btn muted shrink-0" onClick={onClose} aria-label="Close">
            ✕
          </button>
        )}
      </div>
      {done !== null && (
        <div className="mt-2.5 h-1 overflow-hidden rounded-full bg-white/10">
          <div className="h-full rounded-full bg-[var(--accent)] transition-[width] duration-200" style={{ width: `${Math.round(done * 100)}%` }} />
        </div>
      )}
    </div>
  );
}

/** What Linux sent: on its way, then saved, with the way to it. */
export function InboxCard({ inbox, onShow, onClose }: { inbox: Inbox; onShow: (path: string) => void; onClose: () => void }) {
  if (inbox.kind === "receiving") {
    return (
      <div className="fade-in pointer-events-auto flex w-[360px] max-w-full items-center gap-3 rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
        <span
          className="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">Receiving from Linux…</p>
          <p className="mt-0.5 truncate text-slate-400">{inbox.name}</p>
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
    <div className="fade-in pointer-events-auto w-[360px] max-w-full rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">{saved ? "Saved to Downloads" : "Nothing was saved"}</p>
          <p className="mt-0.5 truncate text-slate-400">
            {saved ? `${names(got.landed)} · ${size(got.bytes)}` : "What was sent had gone from Linux, or could not be read there."}
          </p>
          {notes.length > 0 && <p className="mt-0.5 truncate text-slate-500">{notes.join(" · ")}</p>}
        </div>
        {saved && got.first && (
          <button className="link-btn shrink-0" onClick={() => onShow(got.first!)}>
            Show
          </button>
        )}
        <button className="link-btn muted shrink-0" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
    </div>
  );
}

/** A sentence over the picture, in the window's own words — never the guest's. */
export function Notice({ text }: { text: string }) {
  return (
    <div className="fade-in pointer-events-none absolute inset-x-0 top-14 flex justify-center px-4">
      <div className="max-w-[560px] rounded-xl border border-white/10 bg-black/85 px-4 py-2 text-center text-[12.5px] text-slate-200 shadow-lg">
        {text}
      </div>
    </div>
  );
}
