// What a file is, said the way people say it, and drawn: Files' kinds,
// sizes, times and icons (files.js). The parts that are words and numbers
// run under Node too (test/linux-files-test.mjs); the icons are made only
// when asked for, in a page.

const EXT = {};
const add = (kind, list) => {
  for (const e of list.split(' ')) EXT[e] = kind;
};
add('image', 'jpg jpeg jfif png gif webp avif bmp svg ico heic heif tif tiff raw cr2 nef dng psd xcf kra');
add('video', 'mp4 m4v mkv webm mov avi wmv flv ogv 3gp ts mts m2ts mpg mpeg');
add('audio', 'mp3 m4a aac ogg oga opus flac wav weba wma aiff aif mid midi');
add('pdf', 'pdf');
add('archive', 'zip tar gz tgz bz2 tbz xz txz zst 7z rar lz4 lzma cab ar');
add('package', 'deb rpm apk flatpak snap appimage msi pkg');
add('disk', 'iso img dmg qcow2 vdi vmdk vhd');
add('code', 'js mjs cjs ts tsx jsx py rb php pl lua go rs c h cc cpp hpp cs java kt kts swift dart scala r sh bash zsh fish '
  + 'ps1 bat sql css scss less html htm vue svelte json jsonl yaml yml toml xml gradle cmake mk make diff patch ini conf cfg env '
  + 'properties lock dockerfile makefile');
add('text', 'txt text md markdown log csv tsv rst adoc tex srt vtt nfo');
add('doc', 'doc docx odt rtf pages epub');
add('sheet', 'xls xlsx ods numbers');
add('slides', 'ppt pptx odp key');
add('font', 'ttf otf woff woff2');
add('app', 'exe so run elf o a ko');

/** The extensions that are two: "backup.tar.gz" is a .tar.gz. */
const DOUBLE = ['.tar.gz', '.tar.xz', '.tar.bz2', '.tar.zst', '.tar.lz4'];

/** ["photo", ".jpg"]; a dot file or a folder has no extension. */
export function splitExt(name, isDir = false) {
  if (isDir) return [name, ''];
  const lower = name.toLowerCase();
  for (const d of DOUBLE) {
    if (lower.endsWith(d) && name.length > d.length) return [name.slice(0, -d.length), name.slice(-d.length)];
  }
  const dot = name.lastIndexOf('.');
  return dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
}

export function extOf(name) {
  const [, ext] = splitExt(name);
  return ext ? ext.slice(ext.lastIndexOf('.') + 1).toLowerCase() : name.toLowerCase();
}

/** folder, image, video, audio, pdf, archive, package, disk, code, text, doc, sheet, slides, font, app or file. */
export function kindOf(name, isDir = false) {
  if (isDir) return 'folder';
  return EXT[extOf(name)] || 'file';
}

const LABEL = {
  folder: 'Folder', image: 'image', video: 'video', audio: 'audio', pdf: 'PDF document', archive: 'archive',
  package: 'package', disk: 'disk image', code: 'source code', text: 'text', doc: 'document', sheet: 'spreadsheet',
  slides: 'presentation', font: 'font', app: 'program', file: 'file',
};

/** "PNG image", "Markdown text", "Folder". */
export function kindLabel(name, kind) {
  if (kind === 'folder') return 'Folder';
  if (kind === 'pdf') return 'PDF document';
  const ext = extOf(name);
  const known = { md: 'Markdown', js: 'JavaScript', ts: 'TypeScript', py: 'Python', rs: 'Rust', sh: 'Shell script', html: 'HTML', htm: 'HTML' };
  const head = known[ext] || (ext && ext.length <= 5 && ext !== name.toLowerCase() ? ext.toUpperCase() : '');
  if (kind === 'file') return head ? `${head} file` : 'File';
  return head ? `${head} ${LABEL[kind]}` : LABEL[kind][0].toUpperCase() + LABEL[kind].slice(1);
}

/** "0 bytes", "1 byte", "512 bytes", "1.5 kB", "4.1 MB", "12.3 GB": the way Linux's file managers count. */
export function fmtSize(n) {
  if (n == null || n < 0 || !Number.isFinite(n)) return '';
  if (n < 1000) return n === 1 ? '1 byte' : `${n} bytes`;
  const units = ['kB', 'MB', 'GB', 'TB', 'PB'];
  let v = n / 1000;
  let u = 0;
  while (v >= 999.95 && u < units.length - 1) {
    v /= 1000;
    u++;
  }
  return `${v.toFixed(1)} ${units[u]}`;
}

export function fmtSpeed(bytesPerSecond) {
  if (!bytesPerSecond || bytesPerSecond < 1) return '';
  if (bytesPerSecond < 1e6) return `${Math.max(1, Math.round(bytesPerSecond / 1000))} kB/s`;
  const mb = bytesPerSecond / 1e6;
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB/s`;
}

/** "8 s left", "2 min left", "2 h 2 min left". */
export function fmtEta(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '';
  const s = Math.round(seconds);
  if (s < 60) return `${Math.max(1, s)} s left`;
  if (s < 3600) return `${Math.round(s / 60)} min left`;
  const h = Math.floor(s / 3600);
  const m = Math.round((s - h * 3600) / 60);
  return m ? `${h} h ${m} min left` : `${h} h left`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const two = (n) => String(n).padStart(2, '0');

/** "Today 09:05", "Yesterday 23:59", "Mon 14:02", "3 Mar", "5 Jan 2019". */
export function fmtDate(ms, now = Date.now()) {
  if (!ms) return '';
  const d = new Date(ms);
  const t = new Date(now);
  const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((day(t) - day(d)) / 86400000);
  const hm = `${two(d.getHours())}:${two(d.getMinutes())}`;
  if (days === 0) return `Today ${hm}`;
  if (days === 1) return `Yesterday ${hm}`;
  if (days > 1 && days < 7) return `${DAYS[d.getDay()]} ${hm}`;
  if (d.getFullYear() === t.getFullYear()) return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

/** The full date, for a tooltip: "Friday 20 November 2026, 15:04". */
export function fmtDateLong(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const long = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][d.getDay()];
  const month = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'][d.getMonth()];
  return `${long} ${d.getDate()} ${month} ${d.getFullYear()}, ${two(d.getHours())}:${two(d.getMinutes())}`;
}

/** {@code name}, or the first of "name (2)", "name (3)", … not in {@code taken}: as the phone picks (LinuxFiles.freeName). */
export function freeName(taken, name, isDir) {
  if (!taken.has(name)) return name;
  const [base, ext] = splitExt(name, isDir);
  for (let i = 2; i < 100000; i++) {
    const n = `${base} (${i})${ext}`;
    if (!taken.has(n)) return n;
  }
  return `${base} (${Date.now()})${ext}`;
}

/** Names in the order people expect: "file 2" before "file 10", case aside. */
export const collator = typeof Intl !== 'undefined' ? new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' }) : null;
export const byName = (a, b) => (collator ? collator.compare(a, b) : a < b ? -1 : a > b ? 1 : 0);

// ── drawn ───────────────────────────────────────────────────────────────

const NS = 'http://www.w3.org/2000/svg';

function svg(viewBox, cls) {
  const s = document.createElementNS(NS, 'svg');
  s.setAttribute('viewBox', viewBox);
  s.setAttribute('aria-hidden', 'true');
  s.setAttribute('focusable', 'false');
  if (cls) s.setAttribute('class', cls);
  return s;
}

function el(parent, tag, attrs) {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  parent.appendChild(e);
  return e;
}

/** Each kind's colour: its label on a file, and its tile in the grid. */
export const COLORS = {
  image: '#a78bfa', video: '#f472b6', audio: '#34d399', pdf: '#f87171', archive: '#d4a056', package: '#c08b5c',
  disk: '#94a3b8', code: '#60a5fa', text: '#9fb0c6', doc: '#3b82f6', sheet: '#22c55e', slides: '#fb923c',
  font: '#e879f9', app: '#a3e635', file: '#8492a6',
};

/** The small marks a special folder wears on its front: home, desktop, and the rest. */
const EMBLEM = {
  home: 'M24 22.5l-6 5.1V34h4v-4h4v4h4v-6.4z',
  desktop: 'M17 23h14v9H17zM22 33h4v2h-4z',
  documents: 'M19 22h7l3 3v10H19zM21.5 28h5M21.5 31h5',
  downloads: 'M24 22v9M20.2 27.6 24 31.4l3.8-3.8M19 34h10',
  pictures: 'M18 23h12v11H18zM18.8 32.6l3.6-4 2.6 2.7 1.6-1.6 2.6 2.9',
  music: 'M22 32.5V23.6l7-1.6v8.8M22 32.5a1.9 1.9 0 1 1-3.8 0 1.9 1.9 0 0 1 3.8 0zM29 30.8a1.9 1.9 0 1 1-3.8 0 1.9 1.9 0 0 1 3.8 0z',
  videos: 'M21.5 22.8v11.4L31 28.5z',
  shared: 'M20.5 21.5h7a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 19 34V23a1.5 1.5 0 0 1 1.5-1.5zM23 33h2',
  trash: 'M19 24h10M22 24v-1.5h4V24M20.3 24l.9 10.5h5.6l.9-10.5',
};

/**
 * A folder, LinuxOnDroid's amber, and on its front the emblem of a place
 * (home, downloads, the phone's shared folder…) when it is one.
 */
export function folderIcon(emblem, size = 48) {
  const s = svg('0 0 48 48', 'ic ic-folder');
  s.setAttribute('width', size);
  s.setAttribute('height', size);
  el(s, 'path', { class: 'f-back', d: 'M5 12.5A3.5 3.5 0 0 1 8.5 9h10.3a3.5 3.5 0 0 1 2.5 1l2.8 2.8h15.4A3.5 3.5 0 0 1 43 16.3V37a3.5 3.5 0 0 1-3.5 3.5h-31A3.5 3.5 0 0 1 5 37z' });
  el(s, 'path', { class: 'f-front', d: 'M5 19.5A3.5 3.5 0 0 1 8.5 16h31a3.5 3.5 0 0 1 3.5 3.5V37a3.5 3.5 0 0 1-3.5 3.5h-31A3.5 3.5 0 0 1 5 37z' });
  el(s, 'path', { class: 'f-shine', d: 'M8.5 17.2h31' });
  if (emblem && EMBLEM[emblem]) el(s, 'path', { class: 'f-emblem', d: EMBLEM[emblem] });
  return s;
}

/** A page with a folded corner, and its kind's colour as a label with the extension on it. */
export function fileIcon(name, kind, size = 48) {
  const s = svg('0 0 48 48', `ic ic-file k-${kind}`);
  s.setAttribute('width', size);
  s.setAttribute('height', size);
  el(s, 'path', { class: 'p-page', d: 'M12.5 4h16.8L40 14.7V41.5a2.5 2.5 0 0 1-2.5 2.5h-25a2.5 2.5 0 0 1-2.5-2.5v-35A2.5 2.5 0 0 1 12.5 4z' });
  el(s, 'path', { class: 'p-fold', d: 'M29.3 4v8.2a2.5 2.5 0 0 0 2.5 2.5H40z' });
  const ext = extOf(name);
  const short = ext && ext !== name.toLowerCase() && ext.length <= 4 ? ext.toUpperCase() : '';
  if (short) {
    el(s, 'rect', { class: 'p-band', x: '5', y: '26', width: short.length > 3 ? '30' : '26', height: '12', rx: '3', fill: COLORS[kind] || COLORS.file });
    const t = el(s, 'text', { class: 'p-ext', x: short.length > 3 ? '20' : '18', y: '34.6', 'text-anchor': 'middle' });
    t.textContent = short;
  } else {
    for (const y of [20, 25, 30, 35]) el(s, 'path', { class: 'p-line', d: `M16 ${y}h${y === 35 ? 10 : 17}` });
  }
  if (kind === 'app') el(s, 'path', { class: 'p-mark', d: 'M33 31.5l3 3-3 3' });
  return s;
}

/** A link's small arrow, over the icon of what it leads to. */
export function linkBadge() {
  const s = svg('0 0 16 16', 'ic-link');
  el(s, 'circle', { cx: '8', cy: '8', r: '7.2' });
  el(s, 'path', { d: 'M5.5 10.5l5-5M6.8 5.5h3.7v3.7' });
  return s;
}

/** The sidebar's line icons, 20 px, drawn in the text's colour. */
const PLACE = {
  home: 'M3.5 9.2 10 3.8l6.5 5.4V16a.8.8 0 0 1-.8.8h-3.5v-4.6H7.8v4.6H4.3a.8.8 0 0 1-.8-.8z',
  desktop: 'M3 4.5h14v9H3zM8 16.5h4M10 13.5v3',
  documents: 'M5.5 2.8h6l3 3v11.4h-9zM11.5 2.8v3h3M7.8 10h4.4M7.8 13h4.4',
  downloads: 'M10 3.5v9M6.2 8.8 10 12.6l3.8-3.8M4 16.5h12',
  pictures: 'M3.2 4.2h13.6v11.6H3.2zM4 14l4-4.4 3 3 1.8-1.8L16 14.2M12.8 7.6a1.2 1.2 0 1 0 0-.01',
  music: 'M7.5 15V5l8-1.8v10M7.5 15a2.1 2.1 0 1 1-4.2 0 2.1 2.1 0 0 1 4.2 0zM15.5 13.2a2.1 2.1 0 1 1-4.2 0 2.1 2.1 0 0 1 4.2 0z',
  videos: 'M3 5h14v10H3zM8.2 7.6v4.8l4-2.4z',
  shared: 'M6.8 2.5h6.4A1.3 1.3 0 0 1 14.5 3.8v12.4a1.3 1.3 0 0 1-1.3 1.3H6.8a1.3 1.3 0 0 1-1.3-1.3V3.8a1.3 1.3 0 0 1 1.3-1.3zM9 15h2',
  root: 'M3.5 4.5h13v4h-13zM3.5 11.5h13v4h-13zM6 6.5h.01M6 13.5h.01',
  trash: 'M4 5.5h12M8 5.5V4h4v1.5M5.4 5.5l.9 11h7.4l.9-11M8.4 8.5v5M11.6 8.5v5',
  search: 'M8.6 3.5a5.1 5.1 0 1 1 0 10.2 5.1 5.1 0 0 1 0-10.2zM12.4 12.4l4.1 4.1',
};

export function placeIcon(id) {
  const s = svg('0 0 20 20', 'ic-place');
  el(s, 'path', { d: PLACE[id] || PLACE.documents });
  return s;
}

/** The icon for an entry of a listing: {n, t, lt, v}. */
export function entryIcon(e, size = 48) {
  const dir = e.t === 'd' || e.lt === 'd';
  if (dir) return folderIcon(e.v === 'shared' ? 'shared' : e.emblem || null, size);
  return fileIcon(e.n, kindOf(e.n), size);
}
