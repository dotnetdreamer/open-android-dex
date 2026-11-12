/**
 * The Photos tab's data, as the computer's half sends it (src-tauri/src/send).
 * Every sentence a status line says comes from there already worded; this
 * file only shapes what the page groups and shows.
 */

/** One picture on the phone (send/photos.rs, PhotoView). */
export interface Photo {
  id: number;
  name: string;
  album: string;
  /** Its folder and name on the phone: "DCIM/Camera/20261106_114611.jpg". */
  path: string;
  /** When it was taken, ms. */
  taken: number;
  /** When it appeared on the phone, seconds. */
  added: number;
  modified: number;
  size: number;
  w: number;
  h: number;
  mime: string;
  /** "Today", "Yesterday", "Monday 9 November". */
  day: string;
  /** It is on this computer already (kept or saved): Show in Finder shows it. */
  here: boolean;
}

export interface PhotosView {
  state: "loading" | "ready" | "failed" | "none";
  message: string;
  items: Photo[];
  more: boolean;
  count: number;
  bytes: number;
  /** "193 photos (612 MB)", or "" while none are counted. */
  everything: string;
}

/** "Send to computer" for one phone (send/mod.rs, SendView). */
export interface SendView {
  share: boolean;
  openLinks: boolean;
  linked: boolean;
  /** "ready", "old", "missing", or "" while not linked. */
  launcher: string;
  entry: boolean;
  /** The launcher's knock reaches the phone's server: no other app has its port. */
  port: boolean;
  refused: string | null;
  phoneName: string;
  computer: string;
  thisComputer: string;
  showInFolder: string;
}

/** Keep a copy for one phone (send/keep.rs, KeepView). */
export interface KeepView {
  on: boolean;
  scope: "new" | "all";
  folder: string;
  /** "Pictures › Open Android DeX › Galaxy S24". */
  shown: string;
  status: string;
  /** Said beside the status: the folder is inside OneDrive. */
  notice: string | null;
  saving: [number, number] | null;
  everything: string;
  folderGone: boolean;
  oneDrive: boolean;
  thisComputer: string;
}

/** One arrival from the phone (send/inbox.rs, Recent). */
export interface Arrival {
  id: number;
  at: number;
  phone: string;
  phoneName: string;
  kind: "receiving" | "files" | "failed" | "link" | "text";
  title: string;
  paths: string[];
  url?: string | null;
  text?: string | null;
  pct?: number | null;
  share?: number | null;
}

/** The page's offset from UTC, minutes east, for the day headings. */
export function tzMinutes(): number {
  return -new Date().getTimezoneOffset();
}

/** The photos under their day headings, in the order they came. */
export function byDay(items: Photo[]): { day: string; items: Photo[] }[] {
  const out: { day: string; items: Photo[] }[] = [];
  for (const p of items) {
    const last = out[out.length - 1];
    if (last && last.day === p.day) last.items.push(p);
    else out.push({ day: p.day, items: [p] });
  }
  return out;
}

/** An arrival is fresh for this long: the strip at the top of the page shows it. */
export const FRESH_MS = 2 * 60_000;

/** The arrival the strip shows: one coming in now, else the newest still fresh. */
export function stripArrival(list: Arrival[], dismissed: Set<number>, now: number): Arrival | undefined {
  const coming = list.find((a) => a.kind === "receiving");
  if (coming) return coming;
  return list.find((a) => !dismissed.has(a.id) && now - a.at < FRESH_MS && a.kind !== "text");
}
