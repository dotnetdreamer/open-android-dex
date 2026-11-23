// Linux's files, in this browser, without the desktop's picture: the
// phone reads and writes them straight off its storage (LinuxFiles.java),
// so nothing is encoded, nothing waits on Linux, and Linux need not even be
// running. Design record: doc/linux-browser.md, "Files".
//
// A file manager as people know one: places down the side, the path across
// the top, a list or a grid of what is there, and everything a desktop's file
// manager does with the keyboard, the mouse and a drop. Drag files or whole
// folders from the computer onto it, or onto the desktop's picture, and they
// are copied to Linux; bring anything back as a download, or a folder of
// this computer's. Deleting goes to Linux's own Trash, with Undo.
//
// Remote desktop's page (phone.html) has the same Files for the phone's own
// storage: phone-files.js gives createFiles the phone's route (/pfs/), places
// and words in place of LINUX's, and the phone has no Trash, so there
// deleting asks first and is for good.

import { byName, entryIcon, fileIcon, fmtDate, fmtDateLong, fmtSize, folderIcon, kindLabel, kindOf, linkBadge, placeIcon } from './fileicons.js';
import { Peek } from './peek.js';
import { Api, Transfers, why } from './transfer.js';

const NS = 'http://www.w3.org/2000/svg';
const MAC = /mac|iphone|ipad/i.test((navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '');
const MOD = MAC ? '⌘' : 'Ctrl+';

/** The toolbar's and menus' line icons, 20 px. */
const I = {
  back: 'M12.5 4.5 7 10l5.5 5.5',
  fwd: 'M7.5 4.5 13 10l-5.5 5.5',
  up: 'M10 15.5v-11M5 9.5l5-5 5 5',
  list: 'M4 5.5h12M4 10h12M4 14.5h12',
  grid: 'M4 4h5v5H4zM11 4h5v5h-5zM4 11h5v5H4zM11 11h5v5h-5z',
  newdir: 'M3 6.2A1.7 1.7 0 0 1 4.7 4.5h3.4l1.6 1.6h5.6A1.7 1.7 0 0 1 17 7.8v6.5a1.7 1.7 0 0 1-1.7 1.7H4.7A1.7 1.7 0 0 1 3 14.3zM10 8.6v5M7.5 11.1h5',
  upload: 'M10 13.5V4M6 8l4-4 4 4M4 13.5v2h12v-2',
  download: 'M10 4v9.5M6 9.5l4 4 4-4M4 15.5h12',
  desktop: 'M3 4h14v9.5H3zM7.5 16.5h5M10 13.5v3',
  more: 'M5 10h.01M10 10h.01M15 10h.01',
  trash: 'M4.5 6h11M8 6V4.3h4V6M5.8 6l.8 10h6.8l.8-10',
  rename: 'M4 16h3l8.5-8.5-3-3L4 13zM11 6l3 3',
  close: 'M5.5 5.5l9 9M14.5 5.5l-9 9',
  restore: 'M5 9.5a5.5 5.5 0 1 1 1.4 4.3M5 5v4.5h4.5',
  copy: 'M7 7h8.5v9H7zM4.5 13V4H13',
  cut: 'M6 15.5a2 2 0 1 0 0-.01M14 15.5a2 2 0 1 0 0-.01M7.5 14l6.5-10M12.5 14 6 4',
  paste: 'M7 4.5h6M6 4.5H5v12h10v-12h-1M7.5 3h5v3h-5z',
  info: 'M10 16.5a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM10 9.2v4M10 6.6h.01',
  eye: 'M2.8 10s2.7-5 7.2-5 7.2 5 7.2 5-2.7 5-7.2 5-7.2-5-7.2-5zM10 12.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4z',
  link: 'M8.5 11.5l3-3M7 9.5 5.6 10.9a2.6 2.6 0 0 0 3.7 3.7l1.4-1.4M13 10.5l1.4-1.4a2.6 2.6 0 0 0-3.7-3.7L9.3 6.8',
  folder: 'M3 6.2A1.7 1.7 0 0 1 4.7 4.5h3.4l1.6 1.6h5.6A1.7 1.7 0 0 1 17 7.8v6.5a1.7 1.7 0 0 1-1.7 1.7H4.7A1.7 1.7 0 0 1 3 14.3z',
  check: 'M5 10.5l3.2 3.2L15 6.8',
  menu: 'M3.5 6h13M3.5 10h13M3.5 14h13',
  search: 'M8.6 3.5a5.1 5.1 0 1 1 0 10.2 5.1 5.1 0 0 1 0-10.2zM12.4 12.4l4.1 4.1',
  sort: 'M6 4v12M3.5 13.5 6 16l2.5-2.5M14 16V4M11.5 6.5 14 4l2.5 2.5',
  refresh: 'M15.5 10a5.5 5.5 0 1 1-1.6-3.9M15.5 4v3.5H12',
  hidden: 'M3 3l14 14M8.3 5.3A7.7 7.7 0 0 1 10 5c4.5 0 7.2 5 7.2 5a12.6 12.6 0 0 1-2 2.6M12.3 14.6A7.6 7.6 0 0 1 10 15c-4.5 0-7.2-5-7.2-5a12.7 12.7 0 0 1 2.8-3.2',
  computer: 'M3.5 4.5h13v8.5h-13zM2.5 15.5h15',
  play: 'M7 4.5v11L15.5 10z',
  phone: 'M6.8 2.5h6.4A1.3 1.3 0 0 1 14.5 3.8v12.4a1.3 1.3 0 0 1-1.3 1.3H6.8a1.3 1.3 0 0 1-1.3-1.3V3.8a1.3 1.3 0 0 1 1.3-1.3zM9 15h2',
};

function ico(name, cls = '') {
  const s = document.createElementNS(NS, 'svg');
  s.setAttribute('viewBox', '0 0 20 20');
  s.setAttribute('aria-hidden', 'true');
  s.setAttribute('class', `li ${cls}`);
  const p = document.createElementNS(NS, 'path');
  p.setAttribute('d', I[name]);
  s.appendChild(p);
  return s;
}

/** An element: tag, its attributes (on… are listeners, text its words), and what goes inside. */
function h(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) e.append(c);
  return e;
}

function button(icon, label, { cls = 'fx-btn', text = null, onclick, kbd } = {}) {
  const b = h('button', { type: 'button', class: cls, title: kbd ? `${label} (${kbd})` : label, 'aria-label': label, onclick });
  if (icon) b.append(ico(icon));
  if (text) b.append(h('span', { text }));
  return b;
}

const store = {
  get(k, d) {
    try {
      const v = localStorage.getItem(k);
      return v == null ? d : v;
    } catch {
      return d;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(k, v);
    } catch {
      // storage off: remembered for this page only
    }
  },
};

const PLACE_NAMES = {
  home: 'Home', desktop: 'Desktop', documents: 'Documents', downloads: 'Downloads', pictures: 'Pictures',
  music: 'Music', videos: 'Videos', shared: 'Phone’s folder', root: 'Linux', trash: 'Trash',
};

/**
 * Linux's Files (index.html, viewer.js): the door's route for them, the
 * places down the side, and every word that names Linux. Remote desktop's
 * Files (phone-files.js) gives createFiles the phone's own in their place;
 * the rest of this file is the same for both.
 */
export const LINUX = {
  /** The door's route for these files. */
  base: 'fs/',
  /** The panel, to a screen reader. */
  label: 'Linux’s files',
  home: '/root',
  /** / in the path: its name, and the place whose icon it wears. */
  rootLabel: 'Linux',
  rootPlace: 'root',
  /** The places down the side, in this order; '-' a gap. */
  order: ['home', 'desktop', 'documents', 'downloads', 'pictures', 'music', 'videos', '-', 'shared', 'root', 'trash'],
  names: PLACE_NAMES,
  placeTitle: (id, path) => (id === 'root' ? 'All of Linux: /' : id === 'shared' ? 'The phone’s shared folder. Linux’s desktop shows the same folder' : path),
  /** Where a drop goes with Files closed (onto the picture): a place's id. */
  dropPlace: 'desktop',
  /** Deleting moves to a Trash, with Undo. Without one, the page asks, then deletes for good. */
  trash: true,
  /** The button back to the picture. */
  desk: { icon: 'desktop', label: 'Show Linux’s desktop', text: 'Desktop' },
  /** Where the files are, as the transfers' panel says it ("to Documents on Linux"). */
  on: 'Linux',
  picker: 'linux-files',
  /** A big file's rest in one PUT (transfer.js): Linux's page is only ever on the phone's own network. */
  putMax: 0,
  /** transfer.js's why() words of this page's own, where they differ. */
  why: null,
  privateTitle: (here) => `Linux keeps “${here}” private`,
  privateText: (here) => `Only the owner of “${here}” in Linux can look inside “${here}”.`,
  gone: (here) => `“${here}” was moved or deleted in Linux.`,
  noEntry: (here) => `Linux does not let files into ${here}.`,
  nowhere: (n) => `The link “${n}” leads nowhere in Linux.`,
  slash: 'A name in Linux cannot contain a slash (/).',
  runs: 'A program or script Linux can run',
  dropDesk: 'Drop to put the files on Linux’s desktop',
  deskTarget: 'Linux’s desktop',
  dropSub: (phone) => `Linux on ${phone || 'the phone'}. A dropped folder arrives with every file and folder inside.`,
  onDesk: (what) => `${what} on Linux’s desktop now`,
  /** What Files says when there are no files to show: by the phone's word for why. */
  states: {
    installing: ['Linux is still being set up', 'LinuxOnDroid is installing Linux on the phone. Linux’s files show here once the install is done.'],
    missing: ['Linux is not installed yet', 'Open LinuxOnDroid on the phone to install Linux.'],
    offline: ['The phone did not answer', 'Check that the phone is on the same Wi-Fi as this computer.'],
  },
  stateElse: ['Linux’s files are not here', 'Something went wrong on the phone.'],
};

const ROW_H = 40;
const TILE_W = 132;
const TILE_H = 156;

const child = (dir, name) => (dir === '/' ? `/${name}` : `${dir}/${name}`);
const parentOf = (p) => {
  const i = p.lastIndexOf('/');
  return i <= 0 ? '/' : p.slice(0, i);
};
const baseName = (p) => p.slice(p.lastIndexOf('/') + 1);
const isDir = (e) => e.t === 'd' || e.lt === 'd';

/**
 * Files, built on first use. {@code app} is the viewer's: the token, the
 * phone's name, a sign-out, and the way back to the desktop. {@code kind}:
 * whose files, LINUX's by default (phone-files.js gives the phone's own).
 */
export function createFiles(app, kind = LINUX) {
  const K = { ...LINUX, ...kind };
  const api = new Api(app.token, () => app.unauthorized(), K.base);
  const whyHere = (e) => why(e, K.why);
  const S = {
    open: false,
    path: '',
    home: K.home,
    places: [],
    free: 0,
    total: 0,
    entries: [],
    view: [],
    sel: new Set(),
    focus: -1,
    anchor: -1,
    sort: { key: store.get('lod.fx.sort', 'name'), dir: Number(store.get('lod.fx.dir', '1')) || 1 },
    mode: store.get('lod.fx.mode', 'list') === 'grid' ? 'grid' : 'list',
    hidden: store.get('lod.fx.hidden', '0') === '1',
    filter: '',
    search: null,
    trash: false,
    writable: true,
    loading: false,
    error: null,
    back: [],
    fwd: [],
    clip: null,
    fresh: new Set(),
    state: 'ready',
    /** No Linux to show (not installed, still installing, the phone quiet): what renderState says stays. */
    down: false,
  };
  let D = null;
  let loadSeq = 0;
  let infoAt = 0;

  // ── the page's own small boxes: a question, a word with an action, a menu ──

  let snackTimer = 0;
  function snack(text, action, run, ms = 6000) {
    const el = ensureSnack();
    el.textContent = '';
    el.append(h('span', { text }));
    if (action) {
      el.append(h('button', {
        type: 'button', class: 'snack-act', text: action,
        onclick: () => {
          el.classList.remove('show');
          run();
        },
      }));
    }
    el.classList.add('show');
    clearTimeout(snackTimer);
    snackTimer = setTimeout(() => el.classList.remove('show'), ms);
  }
  let snackEl = null;
  function ensureSnack() {
    if (!snackEl) {
      snackEl = h('div', { class: 'snack', role: 'status', 'aria-live': 'polite' });
      document.body.append(snackEl);
    }
    return snackEl;
  }

  /** A question with buttons; resolves with the chosen button's value, or null for Cancel and Esc. */
  function ask({ title, text, list = null, more = 0, buttons, input = null }) {
    return new Promise((resolve) => {
      const dlg = h('dialog', { class: 'fx-dialog', 'aria-labelledby': 'fx-dlg-t' });
      const body = h('div', { class: 'dlg-body' }, h('h2', { id: 'fx-dlg-t', text: title }), text ? h('p', { text }) : null);
      if (list) {
        const ul = h('ul', { class: 'dlg-list' });
        for (const it of list) ul.append(h('li', {}, it.dir ? folderIcon(null, 22) : fileIcon(it.name, kindOf(it.name), 22), h('span', { text: it.name })));
        if (more) ul.append(h('li', { class: 'more', text: `and ${more} more` }));
        body.append(ul);
      }
      let field = null;
      if (input) {
        field = h('input', { class: 'dlg-input', value: input.value || '', spellcheck: 'false', 'aria-label': input.label || title });
        body.append(field);
      }
      const row = h('div', { class: 'dlg-actions' });
      let done = false;
      const finish = (v) => {
        if (done) return;
        done = true;
        dlg.close();
        dlg.remove();
        resolve(v);
      };
      row.append(h('button', { type: 'button', class: 'dlg-ghost', text: 'Cancel', onclick: () => finish(null) }));
      for (const b of buttons) {
        row.append(h('button', {
          type: 'button', class: b.danger ? 'dlg-danger' : b.primary ? 'dlg-primary' : 'dlg-ghost', text: b.label,
          onclick: () => finish(field ? (b.value ? field.value : null) : b.value),
        }));
      }
      dlg.append(body, row);
      dlg.addEventListener('cancel', (e) => {
        e.preventDefault();
        finish(null);
      });
      dlg.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && field && document.activeElement === field) {
          e.preventDefault();
          finish(field.value);
        }
        e.stopPropagation();
      });
      document.body.append(dlg);
      dlg.showModal();
      if (field) {
        field.focus();
        const dot = field.value.lastIndexOf('.');
        field.setSelectionRange(0, input.selectBase && dot > 0 ? dot : field.value.length);
      } else {
        const primary = row.querySelector('.dlg-primary, .dlg-danger');
        if (primary) primary.focus();
      }
    });
  }

  let menuEl = null;
  function closeMenu() {
    if (menuEl) {
      const m = menuEl;
      menuEl = null;
      m.remove();
    }
  }

  /** A menu at a point: [{label, icon, kbd, run, disabled, danger, checked}] and '-' between groups. */
  function menu(items, x, y, from = null) {
    closeMenu();
    const m = h('div', { class: 'fx-menu', role: 'menu' });
    for (const it of items) {
      if (it === '-') {
        m.append(h('div', { class: 'sep', role: 'separator' }));
        continue;
      }
      if (!it || it.hidden) continue;
      const b = h('button', {
        type: 'button', role: it.checked !== undefined ? 'menuitemcheckbox' : 'menuitem', class: it.danger ? 'danger' : '',
        'aria-checked': it.checked !== undefined ? String(!!it.checked) : null, disabled: it.disabled || null,
        onclick: () => {
          closeMenu();
          it.run();
        },
      });
      b.append(it.checked !== undefined ? ico(it.checked ? 'check' : 'check', it.checked ? 'on' : 'off') : ico(it.icon || 'more'));
      b.append(h('span', { class: 'lbl', text: it.label }));
      if (it.kbd) b.append(h('kbd', { text: it.kbd }));
      m.append(b);
    }
    document.body.append(m);
    const r = m.getBoundingClientRect();
    const px = Math.min(x, window.innerWidth - r.width - 8);
    const py = y + r.height > window.innerHeight - 8 ? Math.max(8, y - r.height) : y;
    m.style.left = `${Math.max(8, px)}px`;
    m.style.top = `${py}px`;
    menuEl = m;
    m.addEventListener('keydown', (e) => {
      const btns = [...m.querySelectorAll('button:not([disabled])')];
      const i = btns.indexOf(document.activeElement);
      if (e.key === 'ArrowDown') btns[(i + 1) % btns.length].focus();
      else if (e.key === 'ArrowUp') btns[(i - 1 + btns.length) % btns.length].focus();
      else if (e.key === 'Escape' || e.key === 'Tab') {
        closeMenu();
        if (from) from.focus();
        else focusList();
      } else return;
      e.preventDefault();
      e.stopPropagation();
    });
    const first = m.querySelector('button:not([disabled])');
    if (first) first.focus({ preventScroll: true });
  }
  document.addEventListener('pointerdown', (e) => {
    if (menuEl && !menuEl.contains(e.target)) closeMenu();
  }, true);
  window.addEventListener('blur', closeMenu);

  // ── transfers ──

  const transfers = new Transfers({
    api,
    ask,
    snack,
    nameOf: (p) => labelOf(p),
    words: { on: K.on, picker: K.picker, why: K.why },
    putMax: K.putMax,
    onDone: (job) => {
      if (job.dir === 'up') {
        if (S.open && (job.dest === S.path || job.dest.startsWith(`${S.path}/`) || S.path.startsWith(`${job.dest}/`))) {
          void refresh(job.dest === S.path ? job.tops : []);
        }
        const n = job.files;
        if (!S.open || job.dest !== S.path) {
          const what = job.tops.length === 1 ? `“${job.tops[0]}” is` : `${n.toLocaleString()} files are`;
          snack(job.dest === desktopPath() && !S.open ? K.onDesk(what) : `${what} in ${labelOf(job.dest)} now`, 'Show', () => showJob(job));
        }
        infoAt = 0;
      } else {
        snack(`${job.tops.length === 1 ? `“${job.tops[0]}” is` : `${job.files.toLocaleString()} files are`} in ${job.dest} on this computer now`);
      }
    },
  });
  transfers.onShow = (job) => showJob(job);

  function showJob(job) {
    if (S.open) void go(job.dest, { select: job.tops });
    else app.toFiles(job.dest, job.tops);
  }

  window.addEventListener('beforeunload', (e) => {
    if (!transfers.active()) return;
    e.preventDefault();
    e.returnValue = '';
  });

  // ── where things are ──

  function placeOf(path) {
    return S.places.find((p) => p.path === path);
  }

  function labelOf(path) {
    const p = placeOf(path);
    if (p) return K.names[p.id] || baseName(path);
    if (path === '/') return K.rootLabel;
    return baseName(path) || path;
  }

  /** Where a drop with Files closed goes: Linux's desktop, or the phone's Downloads. */
  function desktopPath() {
    const d = S.places.find((p) => p.id === K.dropPlace);
    return d ? d.path : S.home;
  }

  async function loadInfo(force = false) {
    if (!force && Date.now() - infoAt < 30000 && S.places.length) return true;
    try {
      const j = await api.json('info');
      S.home = j.home || K.home;
      S.places = j.places || [];
      S.free = j.free || 0;
      S.total = j.total || 0;
      S.state = 'ready';
      infoAt = Date.now();
      if (D) renderSide();
      return true;
    } catch (e) {
      if (e.status === 503 || turnedAway(e)) S.state = e.code || 'missing';
      else if (e.status === 0) S.state = 'offline';
      return false;
    }
  }

  /** The door turned these files away as a whole (the phone's, with Remote desktop switched off). */
  function turnedAway(e) {
    return e.status === 403 && e.code === 'off';
  }

  // ── building it ──

  function build() {
    const q = h('input', {
      id: 'fx-q', type: 'search', placeholder: 'Search', spellcheck: 'false', autocomplete: 'off', 'aria-label': 'Search the open folder',
      oninput: () => {
        S.filter = q.value.trim();
        if (S.search && !S.filter) S.search = null;
        resort();
        renderAll();
      },
      onkeydown: (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          if (q.value.trim()) void deepSearch(q.value.trim());
        } else if (e.key === 'Escape') {
          e.preventDefault();
          clearSearch();
          focusList();
        } else if (e.key === 'ArrowDown') {
          e.preventDefault();
          focusList();
          if (S.focus < 0 && S.view.length) setFocus(0, { select: true });
        }
        e.stopPropagation();
      },
    });
    const crumbs = h('ol', { class: 'fx-crumbs', 'aria-label': 'Path to the open folder' });
    const backBtn = button('back', 'Back', { kbd: 'Alt+←', onclick: () => travel(-1) });
    const fwdBtn = button('fwd', 'Forward', { kbd: 'Alt+→', onclick: () => travel(1) });
    const upBtn = button('up', 'Up one folder', { kbd: 'Alt+↑', onclick: () => goUp() });
    const menuBtn = button('menu', 'Places', { cls: 'fx-btn fx-menu-btn', onclick: () => D.root.classList.toggle('side-open') });
    const listBtn = button('list', 'List', { cls: 'seg-btn', onclick: () => setMode('list') });
    const gridBtn = button('grid', 'Grid', { cls: 'seg-btn', onclick: () => setMode('grid') });
    const newBtn = button('newdir', 'New folder', { kbd: `${MOD}Shift+N`, onclick: () => void newFolder() });
    const upload = button('upload', 'Upload', { cls: 'fx-primary', text: 'Upload', onclick: (e) => uploadMenu(e.currentTarget) });
    const desk = button(K.desk.icon, K.desk.label, { cls: 'fx-ghost fx-desk', text: K.desk.text, onclick: () => app.toDesktop() });
    const more = button('more', 'More', { onclick: (e) => moreMenu(e.currentTarget) });
    const emptyTrash = button('trash', 'Empty the Trash', { cls: 'fx-ghost fx-empty-trash', text: 'Empty Trash', onclick: () => void emptyTheTrash() });
    emptyTrash.hidden = true;
    const selCount = h('b', { class: 'sel-count' });
    const selbar = h('div', { class: 'fx-selbar', role: 'toolbar', 'aria-label': 'Actions for the chosen items' },
      button('close', 'Clear the choice', { kbd: 'Esc', onclick: () => selectNone() }), selCount,
      button('download', 'Download', { cls: 'fx-ghost sel-dl', text: 'Download', onclick: () => void download(selected()) }),
      button('restore', 'Put back', { cls: 'fx-ghost sel-restore', text: 'Put back', onclick: () => void restore(selected()) }),
      button('rename', 'Rename', { cls: 'fx-btn sel-rename', kbd: 'F2', onclick: () => renameFocused() }),
      button('trash', K.trash ? 'Move to the Trash' : 'Delete', { cls: 'fx-btn sel-trash', kbd: 'Del', onclick: () => void remove(selected(), false) }),
      button('more', 'More', { cls: 'fx-btn', onclick: (e) => itemMenu(e.currentTarget.getBoundingClientRect().left, e.currentTarget.getBoundingClientRect().bottom + 6, e.currentTarget) }));
    const top = h('header', { class: 'fx-top' },
      h('div', { class: 'fx-nav' }, menuBtn, backBtn, fwdBtn, upBtn),
      h('nav', { class: 'fx-path' }, crumbs),
      selbar,
      h('div', { class: 'fx-tools' },
        h('label', { class: 'fx-search' }, ico('search'), q),
        h('div', { class: 'fx-seg', role: 'group', 'aria-label': 'View' }, listBtn, gridBtn),
        emptyTrash, newBtn, upload, more, desk));
    const places = h('ul', { class: 'fx-places' });
    const meter = h('div', { class: 'fx-meter' }, h('i'));
    const room = h('div', { class: 'fx-room' });
    const side = h('aside', { class: 'fx-side', 'aria-label': 'Places' }, h('div', { class: 'fx-side-title', text: 'Places' }), places,
      h('div', { class: 'fx-storage' }, meter, room));
    const head = h('div', { class: 'fx-colhead', role: 'presentation' });
    for (const [key, label] of [['name', 'Name'], ['size', 'Size'], ['date', 'Modified'], ['kind', 'Kind']]) {
      head.append(h('button', { type: 'button', class: `col col-${key}`, 'data-key': key, onclick: () => setSort(key) }, h('span', { text: label }), ico('back', 'arrow')));
    }
    const canvas = h('div', { class: 'fx-canvas' });
    const scroll = h('div', {
      class: 'fx-scroll', role: 'listbox', tabindex: '0', 'aria-multiselectable': 'true', 'aria-label': 'Files',
    }, canvas);
    const empty = h('div', { class: 'fx-empty', hidden: true });
    const skel = h('div', { class: 'fx-skel', hidden: true });
    for (let i = 0; i < 9; i++) skel.append(h('div', { class: 'sk' }, h('i'), h('b'), h('u')));
    const status = h('footer', { class: 'fx-status', 'aria-live': 'polite' });
    const main = h('main', { class: 'fx-main' }, head, scroll, empty, skel, status);
    const pickFiles = h('input', { type: 'file', multiple: true, hidden: true, onchange: () => fromPicker(pickFiles) });
    const pickDir = h('input', { type: 'file', multiple: true, hidden: true, onchange: () => fromPicker(pickDir) });
    pickDir.webkitdirectory = true;
    const scrim = h('div', { class: 'fx-scrim', onclick: () => D.root.classList.remove('side-open') });
    const root = h('section', { id: 'files', class: `fx mode-${S.mode}`, 'aria-label': K.label, hidden: true },
      top, h('div', { class: 'fx-body' }, side, scrim, main), pickFiles, pickDir);
    document.body.append(root);
    D = {
      root, q, crumbs, backBtn, fwdBtn, upBtn, listBtn, gridBtn, newBtn, upload, more, desk, emptyTrash, selbar, selCount, places,
      meter, room, head, scroll, canvas, empty, skel, status, pickFiles, pickDir, main,
    };
    wire();
  }

  // ── what is shown ──

  function resort() {
    let list = S.search ? S.search.results : S.entries;
    if (!S.hidden) list = list.filter((e) => !e.n.startsWith('.'));
    if (S.filter && !S.search) {
      const f = S.filter.toLowerCase();
      list = list.filter((e) => e.n.toLowerCase().includes(f));
    }
    const { key, dir } = S.sort;
    const k = (e) => kindLabel(e.n, isDir(e) ? 'folder' : kindOf(e.n));
    list = list.slice().sort((a, b) => {
      const da = isDir(a) ? 0 : 1;
      const db = isDir(b) ? 0 : 1;
      if (da !== db) return da - db;
      let c = 0;
      if (key === 'size') c = (a.s || 0) - (b.s || 0);
      else if (key === 'date') c = (a.m || 0) - (b.m || 0);
      else if (key === 'kind') c = byName(k(a), k(b));
      if (!c) c = byName(a.n, b.n);
      return c * dir;
    });
    for (const e of list) {
      if (isDir(e) && !e.emblem) {
        const p = placeOf(e.p || child(S.path, e.n));
        if (p && p.id !== 'home' && p.id !== 'root') e.emblem = p.id;
      }
    }
    S.view = list;
  }

  function pathOf(e) {
    return e.p || child(S.path, e.n);
  }

  function selected() {
    return S.view.filter((e) => S.sel.has(e.n));
  }

  function renderAll() {
    if (!D) return;
    renderChrome();
    renderList(true);
  }

  function renderChrome() {
    const root = D.root;
    root.classList.toggle('mode-list', S.mode === 'list');
    root.classList.toggle('mode-grid', S.mode === 'grid');
    root.classList.toggle('in-trash', S.trash);
    root.classList.toggle('searching', !!S.search);
    D.listBtn.setAttribute('aria-pressed', String(S.mode === 'list'));
    D.gridBtn.setAttribute('aria-pressed', String(S.mode === 'grid'));
    D.backBtn.disabled = !S.back.length;
    D.fwdBtn.disabled = !S.fwd.length;
    D.upBtn.disabled = S.path === '/' || S.trash;
    D.newBtn.disabled = !S.writable || S.trash || !!S.search;
    D.upload.disabled = !S.writable || S.trash;
    D.emptyTrash.hidden = !S.trash;
    D.emptyTrash.disabled = !S.entries.length;
    D.q.placeholder = S.search ? `Results for “${S.search.q}”` : `Search ${labelOf(S.path)}`;
    D.q.setAttribute('aria-label', S.search ? `Search results for “${S.search.q}”` : `Search ${labelOf(S.path)}`);
    // The path, as buttons: each a folder to go to, and to drop onto.
    D.crumbs.textContent = '';
    const parts = [];
    const place = S.places.filter((p) => p.id !== 'root' && (S.path === p.path || S.path.startsWith(`${p.path}/`)))
      .sort((a, b) => b.path.length - a.path.length)[0];
    if (S.trash) {
      parts.push({ path: S.path, label: 'Trash', id: 'trash' });
    } else if (place) {
      parts.push({ path: place.path, label: K.names[place.id], id: place.id });
      const rest = S.path.slice(place.path.length).split('/').filter(Boolean);
      let p = place.path;
      for (const r of rest) {
        p = child(p, r);
        parts.push({ path: p, label: r });
      }
    } else {
      parts.push({ path: '/', label: K.rootLabel, id: K.rootPlace });
      let p = '/';
      for (const r of S.path.split('/').filter(Boolean)) {
        p = child(p, r);
        parts.push({ path: p, label: r });
      }
    }
    // A deep path keeps its two ends; the middle goes into a menu.
    let shown = parts;
    let hiddenParts = [];
    if (parts.length > 5) {
      shown = [parts[0], null, ...parts.slice(-3)];
      hiddenParts = parts.slice(1, -3);
    }
    shown.forEach((p, i) => {
      const li = h('li');
      if (!p) {
        li.append(h('button', {
          type: 'button', class: 'crumb more', text: '…', 'aria-label': 'More folders in the path',
          onclick: (e) => menu(hiddenParts.map((x) => ({ label: x.label, icon: 'folder', run: () => void go(x.path) })),
            e.currentTarget.getBoundingClientRect().left, e.currentTarget.getBoundingClientRect().bottom + 6, e.currentTarget),
        }));
      } else {
        const last = i === shown.length - 1;
        const b = h('button', {
          type: 'button', class: `crumb${last ? ' here' : ''}`, 'data-path': p.path, 'aria-current': last ? 'location' : null,
          onclick: () => (last ? null : void go(p.path)),
        });
        if (p.id) b.append(placeIcon(p.id));
        b.append(h('span', { text: p.label }));
        li.append(b);
      }
      D.crumbs.append(li);
    });
    if (S.search) {
      D.crumbs.append(h('li', {}, h('span', { class: 'crumb here search-crumb' }, ico('search'), h('span', { text: `“${S.search.q}”` }))));
    }
    renderSide();
    renderSel();
    renderStatus();
    renderHead();
  }

  function renderHead() {
    for (const b of D.head.querySelectorAll('.col')) {
      const on = b.dataset.key === S.sort.key;
      b.classList.toggle('on', on);
      b.classList.toggle('desc', on && S.sort.dir < 0);
      b.setAttribute('aria-sort', on ? (S.sort.dir > 0 ? 'ascending' : 'descending') : 'none');
    }
    D.head.querySelector('.col-kind span').textContent = S.trash ? 'From' : S.search ? 'Where' : 'Kind';
    D.head.querySelector('.col-date span').textContent = S.trash ? 'Deleted' : 'Modified';
  }

  function renderSide() {
    if (!D) return;
    D.places.textContent = '';
    for (const id of K.order) {
      if (id === '-') {
        D.places.append(h('li', { class: 'gap', role: 'presentation' }));
        continue;
      }
      const p = S.places.find((x) => x.id === id);
      if (!p) continue;
      const here = S.path === p.path || (id === 'trash' && S.trash);
      const b = h('button', {
        type: 'button', class: `place${here ? ' here' : ''}`, 'data-path': p.path, 'aria-current': here ? 'page' : null,
        title: K.placeTitle(id, p.path),
        onclick: () => {
          D.root.classList.remove('side-open');
          void go(p.path);
        },
      }, placeIcon(id), h('span', { text: K.names[id] }));
      D.places.append(h('li', {}, b));
    }
    if (S.total > 0) {
      const used = Math.max(0, Math.min(1, 1 - S.free / S.total));
      D.meter.firstChild.style.transform = `scaleX(${used})`;
      D.meter.classList.toggle('low', S.free < 2e9);
      D.room.textContent = `${fmtSize(S.free)} free on the phone`;
      D.room.title = `${fmtSize(S.free)} free of ${fmtSize(S.total)}`;
    }
  }

  function renderSel() {
    const sel = selected();
    const n = sel.length;
    D.root.classList.toggle('has-sel', n > 0);
    D.selCount.textContent = n === 1 ? '1 chosen' : `${n} chosen`;
    D.selbar.querySelector('.sel-rename').hidden = n !== 1 || S.trash;
    D.selbar.querySelector('.sel-dl').hidden = S.trash;
    D.selbar.querySelector('.sel-restore').hidden = !S.trash;
    D.selbar.querySelector('.sel-trash').title = S.trash ? 'Delete for good' : K.trash ? `Move to the Trash (Del)` : 'Delete (Del)';
  }

  function renderStatus() {
    const sel = selected();
    const items = (n) => (n === 1 ? '1 item' : `${n.toLocaleString()} items`);
    let s;
    if (S.loading) s = 'Opening…';
    else if (S.error) s = '';
    else if (S.search) s = `${items(S.view.length)} found in ${labelOf(S.search.path)}${S.search.more ? ', with more not shown. A longer search word finds fewer' : ''}`;
    else if (sel.length) {
      const bytes = sel.reduce((n, e) => n + (isDir(e) ? 0 : e.s || 0), 0);
      s = `${sel.length.toLocaleString()} of ${items(S.view.length)} chosen${bytes ? `, ${fmtSize(bytes)}` : ''}`;
    } else {
      const hiddenCount = S.hidden ? 0 : S.entries.filter((e) => e.n.startsWith('.')).length;
      s = items(S.view.length) + (hiddenCount ? ` · ${hiddenCount} hidden` : '') + (S.filter ? ` matching “${S.filter}”` : '');
    }
    D.status.textContent = '';
    D.status.append(h('span', { text: s }));
    if (!S.writable && !S.trash && !S.loading && !S.error) D.status.append(h('span', { class: 'ro', text: 'Read only' }));
    if (S.clip) D.status.append(h('span', { class: 'clip', text: `${S.clip.paths.length} ${S.clip.op === 'cut' ? 'to move' : 'to copy'}. Open a folder and press ${MOD}V` }));
  }

  // ── the list itself, drawn only where it can be seen ──

  const rows = new Map();
  let cols = 1;

  function geometry() {
    const w = D.scroll.clientWidth;
    if (S.mode === 'grid') {
      cols = Math.max(1, Math.floor((w - 16) / TILE_W));
      return { rowH: TILE_H, perRow: cols, total: Math.ceil(S.view.length / cols) };
    }
    cols = 1;
    return { rowH: ROW_H, perRow: 1, total: S.view.length };
  }

  function renderList(rebuild = false) {
    if (!D || S.down) return;
    const g = geometry();
    D.canvas.style.height = `${g.total * g.rowH + 12}px`;
    if (rebuild) {
      for (const el of rows.values()) el.remove();
      rows.clear();
    }
    const top = D.scroll.scrollTop;
    const height = D.scroll.clientHeight || 600;
    const first = Math.max(0, Math.floor(top / g.rowH) - 4);
    const last = Math.min(g.total - 1, Math.ceil((top + height) / g.rowH) + 4);
    const want = new Set();
    for (let r = first; r <= last; r++) {
      for (let c = 0; c < g.perRow; c++) {
        const i = r * g.perRow + c;
        if (i >= S.view.length) break;
        want.add(i);
        let el = rows.get(i);
        if (!el) {
          el = S.mode === 'grid' ? tile(S.view[i], i) : row(S.view[i], i);
          rows.set(i, el);
          D.canvas.append(el);
        }
        paintRow(el, i);
        if (S.mode === 'grid') el.style.transform = `translate(${8 + c * TILE_W}px, ${r * g.rowH + 6}px)`;
        else el.style.transform = `translateY(${r * g.rowH + 4}px)`;
      }
    }
    for (const [i, el] of rows) {
      if (!want.has(i)) {
        el.remove();
        rows.delete(i);
      }
    }
    const nothing = !S.loading && !S.error && !S.view.length && !!S.path;
    D.empty.hidden = !nothing && !S.error;
    if (nothing || S.error) paintEmpty();
    D.skel.hidden = !S.loading;
    D.scroll.setAttribute('aria-activedescendant', S.focus >= 0 && rows.has(S.focus) ? `fx-i${S.focus}` : '');
  }

  function paintRow(el, i) {
    const e = S.view[i];
    const on = S.sel.has(e.n);
    el.classList.toggle('sel', on);
    el.classList.toggle('focus', i === S.focus);
    el.classList.toggle('cut', !!(S.clip && S.clip.op === 'cut' && S.clip.paths.includes(pathOf(e))));
    el.setAttribute('aria-selected', String(on));
  }

  function check() {
    return h('span', { class: 'fx-check', 'aria-hidden': 'true' }, ico('check'));
  }

  function iconFor(e, size) {
    const wrap = h('span', { class: `fx-ic${e.lt === 'x' ? ' broken' : ''}` }, entryIcon(e, size));
    if (e.t === 'l') wrap.append(linkBadge());
    return wrap;
  }

  function row(e, i) {
    const el = h('div', {
      class: `fx-row${S.fresh.has(e.n) ? ' fresh' : ''}`, role: 'option', id: `fx-i${i}`, 'data-i': i, draggable: 'true',
    });
    const kind = isDir(e) ? 'folder' : kindOf(e.n);
    const name = h('span', { class: 'fx-name' }, h('span', { class: 'n', text: e.n }));
    if (e.t === 'l' && e.lt === 'x') name.append(h('span', { class: 'tag', text: 'link to nowhere' }));
    if (e.v === 'shared') name.append(h('span', { class: 'tag phone', text: 'on the phone' }));
    const kindText = S.trash ? (e.o ? parentOf(e.o) : '') : S.search ? whereIn(e.p) : kindLabel(e.n, kind);
    const date = S.trash ? trashDate(e.d) : e.m;
    el.append(check(), iconFor(e, 24), name,
      h('span', { class: 'fx-size', text: isDir(e) || e.lt === 'x' ? '' : fmtSize(e.s) }),
      h('span', { class: 'fx-date', text: typeof date === 'number' ? fmtDate(date) : date, title: typeof date === 'number' ? fmtDateLong(date) : '' }),
      h('span', { class: 'fx-kind', text: kindText, title: kindText }));
    return el;
  }

  function tile(e, i) {
    const el = h('div', { class: `fx-tile${S.fresh.has(e.n) ? ' fresh' : ''}`, role: 'option', id: `fx-i${i}`, 'data-i': i, draggable: 'true' });
    const thumb = h('div', { class: 'fx-thumb' }, iconFor(e, 64));
    el.append(check(), thumb, h('div', { class: 'fx-tname', text: e.n, title: e.n }),
      h('div', { class: 'fx-tmeta', text: isDir(e) || e.lt === 'x' ? '' : fmtSize(e.s) }));
    const k = kindOf(e.n);
    if (!isDir(e) && ['image', 'video', 'pdf', 'audio'].includes(k) && e.s > 0) thumbs.want(pathOf(e), e, thumb);
    return el;
  }

  /** Where a search found something, said from the folder searched: "Holiday › day one". */
  function whereIn(p) {
    const dir = parentOf(p);
    const from = S.search.path;
    if (dir === from) return labelOf(from);
    const rest = from === '/' ? dir.slice(1) : dir.startsWith(`${from}/`) ? dir.slice(from.length + 1) : dir;
    return rest.split('/').join(' › ');
  }

  function trashDate(d) {
    if (!d) return '';
    const t = Date.parse(d);
    return Number.isNaN(t) ? d : fmtDate(t);
  }

  function paintEmpty() {
    D.empty.textContent = '';
    let title;
    let text;
    let act = null;
    const e = S.error;
    if (e) {
      const here = labelOf(S.path);
      if (e.status === 403) {
        title = K.privateTitle(here);
        text = K.privateText(here);
      } else if (e.status === 404 || e.code === 'missing') {
        title = 'Folder not found';
        text = K.gone(here);
        act = h('button', { type: 'button', class: 'fx-primary', text: 'Go home', onclick: () => void go(S.home) });
      } else if (e.status === 0) {
        title = 'The phone did not answer';
        text = 'Check that the phone is still on the same Wi-Fi as this computer.';
        act = h('button', { type: 'button', class: 'fx-primary', text: 'Try again', onclick: () => void go(S.path, { push: false }) });
      } else {
        title = `“${here}” could not be opened`;
        text = whyHere(e);
      }
    } else if (S.search) {
      title = 'Nothing found';
      text = `No file or folder in ${labelOf(S.search.path)} has “${S.search.q}” in the name.`;
    } else if (S.filter) {
      title = 'Nothing matches';
      text = `No file or folder in ${labelOf(S.path)} has “${S.filter}” in the name. Press Enter to search every folder inside ${labelOf(S.path)} too.`;
    } else if (S.trash) {
      title = 'The Trash is empty';
      text = 'Deleted files and folders wait in the Trash until the Trash is emptied. Put back returns an item to the folder the item came from.';
    } else {
      title = `${labelOf(S.path)} is empty`;
      text = S.writable ? 'Drop files or folders from this computer onto the page, or choose Upload.' : K.noEntry(labelOf(S.path));
      if (S.writable) act = h('button', { type: 'button', class: 'fx-primary', onclick: (ev) => uploadMenu(ev.currentTarget) }, ico('upload'), h('span', { text: 'Upload' }));
    }
    const art = h('div', { class: `art${e ? ' bad' : ''}` }, S.trash && !e ? placeIcon('trash') : folderIcon(null, 96));
    D.empty.append(art, h('h3', { text: title }), h('p', { text }));
    if (act) D.empty.append(act);
  }

  // ── thumbnails: asked for as they come into view, two or four at a time ──

  const thumbs = (() => {
    const cache = new Map();
    const queue = [];
    let busy = 0;
    const key = (path, e) => `${path}|${e.m}|${e.s}`;
    function want(path, e, el) {
      const k = key(path, e);
      const hit = cache.get(k);
      if (hit === null) return;
      if (hit) {
        show(el, hit, e);
        return;
      }
      queue.push({ path, e, el, k });
      // Asked for while its tile is being made: looked at once the tile is on the page.
      Promise.resolve().then(pump);
    }
    function show(el, url, e) {
      if (!el.isConnected) return;
      const img = h('img', { alt: '', src: url, draggable: 'false', decoding: 'async' });
      img.addEventListener('load', () => el.classList.add('has-img'));
      el.textContent = '';
      el.append(img);
      if (kindOf(e.n) === 'video') el.append(h('span', { class: 'play' }, ico('play', 'tri')));
    }
    function pump() {
      while (busy < 4 && queue.length) {
        const job = queue.pop(); // the newest first: what came into view last
        if (!job.el.isConnected) continue;
        if (cache.has(job.k)) {
          if (cache.get(job.k)) show(job.el, cache.get(job.k), job.e);
          continue;
        }
        busy++;
        fetch(api.url('thumb', { path: job.path, s: 256 }), { headers: api.auth(), cache: 'force-cache' })
          .then(async (r) => {
            if (!r.ok) throw new Error(String(r.status));
            const url = URL.createObjectURL(await r.blob());
            cache.set(job.k, url);
            if (cache.size > 400) {
              const [oldK, oldV] = cache.entries().next().value;
              if (oldV) URL.revokeObjectURL(oldV);
              cache.delete(oldK);
            }
            show(job.el, url, job.e);
          })
          .catch(() => cache.set(job.k, null))
          .finally(() => {
            busy--;
            pump();
          });
      }
    }
    return { want };
  })();

  // ── going places ──

  async function go(path, { push = true, select = null } = {}) {
    if (!D) build();
    closeMenu();
    if (push && S.path && S.path !== path) {
      S.back.push(S.path);
      if (S.back.length > 100) S.back.shift();
      S.fwd = [];
    }
    const seq = ++loadSeq;
    S.path = path;
    S.trash = !!S.places.find((p) => p.id === 'trash' && p.path === path);
    S.search = null;
    S.filter = '';
    D.q.value = '';
    S.loading = true;
    S.error = null;
    S.fresh = new Set();
    D.root.classList.add('loading');
    renderChrome();
    // The old list stays a moment: a fast folder shows no skeleton at all.
    const slow = setTimeout(() => {
      if (seq === loadSeq && S.loading) {
        S.entries = [];
        resort();
        renderList(true);
      }
    }, 180);
    let j = null;
    try {
      j = S.trash ? await api.json('trashlist') : await api.json('list', { query: { path } });
    } catch (e) {
      if (seq !== loadSeq) return;
      if (e.status === 503 || turnedAway(e)) {
        S.state = e.code || 'missing';
        renderState();
        return;
      }
      S.error = e;
    }
    clearTimeout(slow);
    if (seq !== loadSeq) return;
    S.loading = false;
    D.root.classList.remove('loading');
    S.entries = j ? j.entries || [] : [];
    if (j && j.path && !S.trash) S.path = j.path;
    S.writable = !!j && !S.trash && j.w !== 0;
    S.sel.clear();
    S.focus = -1;
    S.anchor = -1;
    resort();
    if (select && select.length) {
      for (const n of select) S.sel.add(n);
      const i = S.view.findIndex((e) => S.sel.has(e.n));
      S.focus = i;
      S.anchor = i;
      if (!S.hidden && select.some((n) => n.startsWith('.'))) {
        // chosen but hidden: shown, so that what was asked for is there
        S.hidden = true;
        resort();
      }
    }
    D.scroll.scrollTop = 0;
    D.root.classList.remove('settled');
    void D.root.offsetWidth;
    D.root.classList.add('settled');
    renderAll();
    if (S.focus >= 0) ensureVisible(S.focus);
    setHash();
  }

  /** The folder read again, as it is now: what was chosen stays chosen, and what is new is shown arriving. */
  async function refresh(arrived = []) {
    if (!S.open || S.loading || S.search) return;
    const seq = loadSeq;
    let j;
    try {
      j = S.trash ? await api.json('trashlist') : await api.json('list', { query: { path: S.path } });
    } catch {
      return;
    }
    if (seq !== loadSeq || renaming) return;
    const before = new Map(S.entries.map((e) => [e.n, `${e.s}|${e.m}|${e.t}`]));
    const now = j.entries || [];
    const same = before.size === now.length && now.every((e) => before.get(e.n) === `${e.s}|${e.m}|${e.t}`);
    if (same && !arrived.length) return;
    const focusName = S.focus >= 0 && S.view[S.focus] ? S.view[S.focus].n : null;
    S.fresh = new Set([...now.filter((e) => !before.has(e.n)).map((e) => e.n), ...arrived]);
    S.entries = now;
    S.writable = !S.trash && j.w !== 0;
    const names = new Set(now.map((e) => e.n));
    for (const n of [...S.sel]) if (!names.has(n)) S.sel.delete(n);
    resort();
    S.focus = focusName ? S.view.findIndex((e) => e.n === focusName) : -1;
    renderAll();
    setTimeout(() => {
      S.fresh = new Set();
    }, 1600);
  }

  function travel(step) {
    const from = step < 0 ? S.back : S.fwd;
    const to = step < 0 ? S.fwd : S.back;
    if (!from.length) return;
    const p = from.pop();
    to.push(S.path);
    const came = S.path;
    void go(p, { push: false, select: step < 0 && parentOf(came) === p ? [baseName(came)] : null });
  }

  function goUp() {
    if (S.path === '/' || S.trash) return;
    const came = S.path;
    void go(parentOf(S.path), { select: [baseName(came)] });
  }

  function setHash() {
    const want = `#files=${encodeURI(S.path)}`;
    if (location.hash !== want) {
      try {
        window.history.replaceState(null, '', want);
      } catch {
        // a page that cannot: the folder is not remembered across a reload
      }
    }
  }

  async function deepSearch(q) {
    const from = S.search ? S.search.path : S.path;
    S.loading = true;
    renderChrome();
    renderList(true);
    try {
      const j = await api.json('find', { query: { path: from, q } });
      S.search = { q, path: j.path || from, results: j.results || [], more: !!j.more };
    } catch (e) {
      S.search = { q, path: from, results: [], more: false };
      snack(whyHere(e));
    }
    S.loading = false;
    S.filter = '';
    S.sel.clear();
    S.focus = -1;
    resort();
    renderAll();
  }

  function clearSearch() {
    D.q.value = '';
    S.filter = '';
    S.search = null;
    resort();
    renderAll();
  }

  /** What the Files say when there is no Linux to look at: not installed, still installing, a phone gone quiet. */
  function renderState() {
    if (!D) build();
    S.down = true;
    for (const el of rows.values()) el.remove();
    rows.clear();
    S.loading = false;
    D.root.classList.remove('loading');
    D.root.classList.add('no-linux');
    D.empty.hidden = false;
    D.skel.hidden = true;
    D.empty.textContent = '';
    const words = Object.prototype.hasOwnProperty.call(K.states, S.state) ? K.states[S.state] : K.stateElse;
    D.empty.append(h('div', { class: 'art bad' }, folderIcon(null, 96)), h('h3', { text: words[0] }), h('p', { text: words[1] }),
      h('button', {
        type: 'button', class: 'fx-primary', text: 'Try again',
        onclick: async () => {
          if (await loadInfo(true)) {
            S.down = false;
            D.root.classList.remove('no-linux');
            void go(S.path || S.home, { push: false });
          }
        },
      }));
  }

  // ── choosing ──

  function setFocus(i, { select = false, extend = false, toggle = false } = {}) {
    if (!S.view.length) return;
    i = Math.max(0, Math.min(S.view.length - 1, i));
    S.focus = i;
    const name = S.view[i].n;
    if (extend && S.anchor < 0) S.anchor = i;
    if (extend) {
      S.sel.clear();
      const [a, b] = S.anchor < i ? [S.anchor, i] : [i, S.anchor];
      for (let k = a; k <= b; k++) S.sel.add(S.view[k].n);
    } else if (toggle) {
      if (S.sel.has(name)) S.sel.delete(name);
      else S.sel.add(name);
      S.anchor = i;
    } else if (select) {
      S.sel.clear();
      S.sel.add(name);
      S.anchor = i;
    }
    ensureVisible(i);
    renderList();
    renderSel();
    renderStatus();
  }

  function ensureVisible(i) {
    const g = geometry();
    const r = Math.floor(i / g.perRow);
    const y = r * g.rowH;
    const sc = D.scroll;
    if (y < sc.scrollTop) sc.scrollTop = y;
    else if (y + g.rowH + 8 > sc.scrollTop + sc.clientHeight) sc.scrollTop = y + g.rowH + 8 - sc.clientHeight;
  }

  function selectNone() {
    S.sel.clear();
    renderList();
    renderSel();
    renderStatus();
  }

  function selectAll() {
    for (const e of S.view) S.sel.add(e.n);
    renderList();
    renderSel();
    renderStatus();
  }

  function focusList() {
    D.scroll.focus({ preventScroll: true });
  }

  // ── doing things ──

  function open(e) {
    if (!e) return;
    if (S.trash) {
      peek.show(S.view.filter((x) => !isDir(x)), e, pathOf);
      return;
    }
    if (isDir(e)) {
      if (e.lt === 'x') {
        snack(K.nowhere(e.n));
        return;
      }
      void go(pathOf(e));
    } else if (e.lt === 'x') {
      snack(K.nowhere(e.n));
    } else {
      peek.show(S.view.filter((x) => !isDir(x) && x.lt !== 'x'), e, pathOf);
    }
  }

  const peek = new Peek({
    api,
    download: (e) => void download([e]),
    onClose: () => focusList(),
  });

  /** A file as the browser's own download; several, or a folder, as one ZIP made on the way. */
  async function download(list) {
    if (!list.length) return;
    try {
      if (list.length === 1 && !isDir(list[0])) {
        const e = list[0];
        const { k } = await api.json('ticket', { form: { kind: 'get', p: pathOf(e) } });
        save(api.url('get', { k, dl: 1 }), e.n);
        snack(`Downloading “${e.n}”`);
      } else {
        const name = list.length === 1 ? `${list[0].n}.zip` : `${labelOf(S.path)}.zip`;
        const { k } = await api.json('ticket', { form: { kind: 'zip', p: list.map(pathOf), name } });
        save(api.url('zip', { k }), name);
        snack(`Packing ${list.length === 1 ? `“${list[0].n}”` : `${list.length} items`} into “${name}”. The browser starts saving “${name}” in a moment.`);
      }
    } catch (e) {
      snack(whyHere(e));
    }
  }

  function save(url, name) {
    const a = h('a', { href: url, download: name, hidden: true });
    document.body.append(a);
    a.click();
    a.remove();
  }

  async function saveToFolder(list) {
    const items = list.map((e) => ({ path: pathOf(e), name: e.n, dir: isDir(e), size: e.s, mtime: e.m }));
    await transfers.saveToFolder(items, list.length === 1 ? list[0].n : `${list.length} items`);
  }

  async function newFolder() {
    if (!S.writable || S.trash) return;
    try {
      const { path } = await api.json('mkdir', { form: { path: child(S.path, 'New folder'), on: 'rename' } });
      await refresh([baseName(path)]);
      const i = S.view.findIndex((e) => e.n === baseName(path));
      if (i >= 0) {
        setFocus(i, { select: true });
        renameAt(i);
      }
    } catch (e) {
      snack(whyHere(e));
    }
  }

  let renaming = null;
  function renameFocused() {
    if (S.trash) return;
    const sel = selected();
    const i = sel.length === 1 ? S.view.indexOf(sel[0]) : S.focus;
    if (i >= 0) renameAt(i);
  }

  /** The name edited where it is, as a desktop's file manager does. */
  function renameAt(i) {
    const e = S.view[i];
    if (!e) return;
    ensureVisible(i);
    renderList();
    const el = rows.get(i);
    if (!el) return;
    const target = el.querySelector(S.mode === 'grid' ? '.fx-tname' : '.fx-name .n');
    const field = h('input', { class: 'fx-rename', value: e.n, spellcheck: 'false', 'aria-label': `New name for ${e.n}` });
    renaming = { e, field };
    target.replaceWith(field);
    field.focus();
    const dot = e.n.lastIndexOf('.');
    field.setSelectionRange(0, !isDir(e) && dot > 0 ? dot : e.n.length);
    let done = false;
    const finish = async (keepIt) => {
      if (done) return;
      done = true;
      renaming = null;
      const name = field.value.trim();
      field.replaceWith(target);
      if (!keepIt || !name || name === e.n) {
        focusList();
        return;
      }
      if (name.includes('/')) {
        snack(K.slash);
        focusList();
        return;
      }
      try {
        await api.json('rename', { form: { path: pathOf(e), name } });
        S.sel = new Set([name]);
        await refresh([name]);
        const k = S.view.findIndex((x) => x.n === name);
        if (k >= 0) setFocus(k);
      } catch (err) {
        snack(err.code === 'exists' ? `“${name}” is already in ${labelOf(parentOf(pathOf(e)))}.` : whyHere(err));
      }
      focusList();
    };
    field.addEventListener('keydown', (ev) => {
      ev.stopPropagation();
      if (ev.key === 'Enter') void finish(true);
      else if (ev.key === 'Escape') void finish(false);
    });
    field.addEventListener('blur', () => void finish(true));
    field.addEventListener('pointerdown', (ev) => ev.stopPropagation());
    field.addEventListener('dblclick', (ev) => ev.stopPropagation());
  }

  /**
   * To the Trash, with Undo; for good when asked, or where there is no Trash
   * (the phone's shared folder; the phone's own files, which have none).
   */
  async function remove(list, forGood) {
    if (!list.length) return;
    const paths = list.map(pathOf);
    if (S.trash || !K.trash) forGood = true;
    if (forGood) {
      const one = list.length === 1;
      const ok = await ask(K.trash ? {
        title: one ? `Delete “${list[0].n}” for good?` : `Delete ${list.length} items for good?`,
        text: one ? `“${list[0].n}” skips the Trash and cannot be brought back.` : `The ${list.length} items skip the Trash and cannot be brought back.`,
        buttons: [{ label: 'Delete', value: 'yes', danger: true }],
      } : {
        ...K.deleteAsk(list),
        list: list.slice(0, 6).map((e) => ({ name: e.n, dir: isDir(e) })),
        more: Math.max(0, list.length - 6),
        buttons: [{ label: 'Delete', value: 'yes', danger: true }],
      });
      if (!ok) return;
      try {
        const r = await api.json('delete', { form: { p: paths } });
        if (r.failed.length) snack(`${r.failed.length} could not be deleted: ${whyHere({ code: r.failed[0][1] })}`);
      } catch (e) {
        snack(whyHere(e));
      }
      await refresh();
      return;
    }
    try {
      const r = await api.json('trash', { form: { p: paths } });
      const ids = r.trashed.map((t) => t.id);
      await refresh();
      if (ids.length) {
        snack(ids.length === 1 ? `“${baseName(r.trashed[0].p)}” is in the Trash` : `${ids.length} items are in the Trash`, 'Undo', async () => {
          try {
            const u = await api.json('untrash', { form: { id: ids } });
            await refresh(u.restored.map(baseName));
          } catch (e) {
            snack(whyHere(e));
          }
        }, 8000);
      }
      if (r.cannot.length) {
        const ok = await ask({
          title: r.cannot.length === 1 ? `“${baseName(r.cannot[0])}” cannot go to the Trash` : `${r.cannot.length} items cannot go to the Trash`,
          text: r.cannot.length === 1
            ? `“${baseName(r.cannot[0])}” is in the phone’s shared folder, and Linux’s Trash only holds files from Linux itself. Delete “${baseName(r.cannot[0])}” for good?`
            : `The ${r.cannot.length} items are in the phone’s shared folder, and Linux’s Trash only holds files from Linux itself. Delete the ${r.cannot.length} items for good?`,
          list: r.cannot.slice(0, 6).map((p) => ({ name: baseName(p), dir: list.some((e) => pathOf(e) === p && isDir(e)) })),
          more: Math.max(0, r.cannot.length - 6),
          buttons: [{ label: 'Delete for good', value: 'yes', danger: true }],
        });
        if (ok) {
          await api.json('delete', { form: { p: r.cannot } }).catch((e) => snack(whyHere(e)));
          await refresh();
        }
      }
      if (r.failed.length) snack(`${r.failed.length} could not go to the Trash: ${whyHere({ code: r.failed[0][1] })}`);
    } catch (e) {
      snack(whyHere(e));
    }
  }

  async function restore(list) {
    try {
      const r = await api.json('untrash', { form: { id: list.map((e) => e.id || e.n) } });
      await refresh();
      if (r.restored.length) {
        snack(r.restored.length === 1 ? `“${baseName(r.restored[0])}” is back in ${labelOf(parentOf(r.restored[0]))}` : `${r.restored.length} items are back where the items came from`, 'Show', () => {
          void go(parentOf(r.restored[0]), { select: r.restored.filter((p) => parentOf(p) === parentOf(r.restored[0])).map(baseName) });
        });
      }
      if (r.failed.length) snack(`${r.failed.length} could not be put back`);
    } catch (e) {
      snack(whyHere(e));
    }
  }

  async function emptyTheTrash() {
    const ok = await ask({
      title: 'Empty the Trash?',
      text: `${S.entries.length === 1 ? 'The item' : `All ${S.entries.length} items`} in Linux’s Trash will be deleted for good.`,
      buttons: [{ label: 'Empty Trash', value: 'yes', danger: true }],
    });
    if (!ok) return;
    try {
      await api.json('emptytrash', { form: {} });
    } catch (e) {
      snack(whyHere(e));
    }
    await refresh();
  }

  async function moveTo(paths, dir, copy) {
    if (!paths.length) return;
    try {
      const r = await api.json('move', { form: { p: paths, dir, copy: copy ? 1 : 0, on: 'rename' } });
      if (r.failed.length) snack(`${r.failed.length} could not be ${copy ? 'copied' : 'moved'}: ${whyHere({ code: r.failed[0][1] })}`);
      else if (r.moved.length) {
        snack(`${r.moved.length === 1 ? `“${baseName(r.moved[0][0])}”` : `${r.moved.length} items`} ${copy ? 'copied' : 'moved'} to ${labelOf(dir)}`,
          dir !== S.path ? 'Show' : null, () => void go(dir, { select: r.moved.map((m) => baseName(m[1])) }));
      }
      await refresh(dir === S.path ? r.moved.map((m) => baseName(m[1])) : []);
    } catch (e) {
      snack(whyHere(e));
    }
  }

  function copyPaths(list) {
    const text = list.map(pathOf).join('\n');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => snack(list.length === 1 ? `The path of “${list[0].n}” is on this computer’s clipboard` : `${list.length} paths are on this computer’s clipboard`),
        () => snack('The browser did not allow copying to the clipboard'));
    } else {
      snack('Over plain http the browser offers no clipboard. The https address can copy paths.');
    }
  }

  async function properties(e) {
    const p = pathOf(e);
    const kind = isDir(e) ? 'folder' : kindOf(e.n);
    const lines = [
      ['Kind', e.t === 'l' ? `Link to a ${isDir(e) ? 'folder' : 'file'}` : kindLabel(e.n, kind)],
      ['Where', parentOf(p)],
      [isDir(e) ? 'Holds' : 'Size', isDir(e) ? 'Counting…' : `${fmtSize(e.s)} (${(e.s || 0).toLocaleString()} bytes)`],
      ['Modified', fmtDateLong(e.m)],
    ];
    if (e.x && K.runs) lines.push(['Runs', K.runs]);
    const dlg = h('dialog', { class: 'fx-dialog props' });
    const dl = h('dl');
    const cells = [];
    for (const [k, v] of lines) {
      const dd = h('dd', { text: v });
      cells.push(dd);
      dl.append(h('dt', { text: k }), dd);
    }
    dlg.append(h('div', { class: 'dlg-body' }, h('div', { class: 'props-head' }, entryIcon(e, 56), h('h2', { text: e.n })), dl),
      h('div', { class: 'dlg-actions' }, h('button', { type: 'button', class: 'dlg-primary', text: 'Done', onclick: () => dlg.close() })));
    dlg.addEventListener('close', () => {
      dlg.remove();
      focusList();
    });
    dlg.addEventListener('keydown', (ev) => ev.stopPropagation());
    document.body.append(dlg);
    dlg.showModal();
    if (isDir(e)) {
      try {
        const d = await api.json('du', { query: { path: p } });
        cells[2].textContent = `${fmtSize(d.bytes)} in ${d.files.toLocaleString()} file${d.files === 1 ? '' : 's'}${d.dirs ? ` and ${d.dirs.toLocaleString()} folder${d.dirs === 1 ? '' : 's'}` : ''}${d.partial ? ', and more' : ''}`;
      } catch {
        cells[2].textContent = '';
      }
    }
  }

  // ── menus ──

  function uploadMenu(from) {
    const r = from.getBoundingClientRect();
    menu([
      { label: 'Files…', icon: 'upload', kbd: `${MOD}U`, run: () => D.pickFiles.click() },
      { label: 'A folder…', icon: 'folder', run: () => D.pickDir.click() },
    ], r.left, r.bottom + 6, from);
  }

  function moreMenu(from) {
    const r = from.getBoundingClientRect();
    menu([
      { label: 'Show hidden files', checked: S.hidden, kbd: `${MOD}H`, run: () => toggleHidden() },
      '-',
      { label: 'Sort by name', checked: S.sort.key === 'name', run: () => setSort('name', true) },
      { label: 'Sort by size', checked: S.sort.key === 'size', run: () => setSort('size', true) },
      { label: 'Sort by date', checked: S.sort.key === 'date', run: () => setSort('date', true) },
      { label: 'Sort by kind', checked: S.sort.key === 'kind', run: () => setSort('kind', true) },
      '-',
      { label: 'Read the folder again', icon: 'refresh', kbd: 'F5', run: () => void go(S.path, { push: false }) },
      { label: `Copy the path of “${labelOf(S.path)}”`, icon: 'link', run: () => copyPaths([{ n: baseName(S.path), p: S.path }]) },
    ], r.right - 240, r.bottom + 6, from);
  }

  function itemMenu(x, y, from = null) {
    const sel = selected();
    if (!sel.length) return backgroundMenu(x, y);
    const one = sel.length === 1 ? sel[0] : null;
    const anyDir = sel.some(isDir);
    if (S.trash) {
      menu([
        { label: 'Put back', icon: 'restore', run: () => void restore(sel) },
        { label: 'Delete for good', icon: 'trash', danger: true, kbd: 'Del', run: () => void remove(sel, true) },
      ], x, y, from);
      return;
    }
    menu([
      one && { label: isDir(one) ? 'Open' : 'Quick look', icon: isDir(one) ? 'folder' : 'eye', kbd: isDir(one) ? 'Enter' : 'Space', run: () => open(one) },
      { label: one && !anyDir ? 'Download' : 'Download as ZIP', icon: 'download', run: () => void download(sel) },
      Transfers.canSaveToFolder() && { label: 'Save into a folder on this computer…', icon: 'computer', run: () => void saveToFolder(sel) },
      '-',
      { label: 'Copy', icon: 'copy', kbd: `${MOD}C`, run: () => clip('copy', sel) },
      { label: 'Cut', icon: 'cut', kbd: `${MOD}X`, run: () => clip('cut', sel) },
      { label: 'Paste here', icon: 'paste', kbd: `${MOD}V`, hidden: !S.clip || !one || !isDir(one), run: () => paste(pathOf(one)) },
      { label: 'Duplicate', icon: 'copy', run: () => void moveTo(sel.map(pathOf), S.search ? parentOf(pathOf(sel[0])) : S.path, true) },
      one && { label: 'Rename', icon: 'rename', kbd: 'F2', run: () => renameAt(S.view.indexOf(one)) },
      { label: sel.length === 1 ? 'Copy the path' : 'Copy the paths', icon: 'link', run: () => copyPaths(sel) },
      one && { label: 'Properties', icon: 'info', kbd: 'Alt+Enter', run: () => void properties(one) },
      '-',
      // No Trash (the phone's own files): one Delete, which asks first.
      K.trash && { label: 'Move to the Trash', icon: 'trash', kbd: 'Del', run: () => void remove(sel, false) },
      { label: K.trash ? 'Delete for good' : 'Delete', icon: 'trash', danger: true, kbd: K.trash ? 'Shift+Del' : 'Del', run: () => void remove(sel, true) },
    ].filter((x) => x !== null && x !== false && x !== undefined), x, y, from);
  }

  function backgroundMenu(x, y) {
    if (S.trash) {
      menu([{ label: 'Empty the Trash', icon: 'trash', danger: true, disabled: !S.entries.length, run: () => void emptyTheTrash() }], x, y);
      return;
    }
    menu([
      { label: 'New folder', icon: 'newdir', kbd: `${MOD}Shift+N`, disabled: !S.writable, run: () => void newFolder() },
      { label: 'Upload files…', icon: 'upload', kbd: `${MOD}U`, disabled: !S.writable, run: () => D.pickFiles.click() },
      { label: 'Upload a folder…', icon: 'folder', disabled: !S.writable, run: () => D.pickDir.click() },
      { label: 'Paste', icon: 'paste', kbd: `${MOD}V`, hidden: !S.clip, disabled: !S.writable, run: () => paste(S.path) },
      '-',
      { label: 'Choose everything', icon: 'check', kbd: `${MOD}A`, run: () => selectAll() },
      { label: 'Show hidden files', checked: S.hidden, kbd: `${MOD}H`, run: () => toggleHidden() },
      { label: S.mode === 'grid' ? 'As a list' : 'As a grid', icon: S.mode === 'grid' ? 'list' : 'grid', run: () => setMode(S.mode === 'grid' ? 'list' : 'grid') },
      { label: 'Read the folder again', icon: 'refresh', kbd: 'F5', run: () => void go(S.path, { push: false }) },
    ], x, y);
  }

  function clip(op, list) {
    S.clip = { op, paths: list.map(pathOf) };
    renderList();
    renderStatus();
    snack(`${list.length === 1 ? `“${list[0].n}”` : `${list.length} items`} ready to ${op === 'cut' ? 'move' : 'copy'}: open a folder and paste`);
  }

  function paste(dir) {
    if (!S.clip) return;
    const { op, paths } = S.clip;
    if (op === 'cut') S.clip = null;
    void moveTo(paths, dir, op === 'copy');
  }

  function toggleHidden() {
    S.hidden = !S.hidden;
    store.set('lod.fx.hidden', S.hidden ? '1' : '0');
    resort();
    renderAll();
    snack(S.hidden ? 'Hidden files are shown' : 'Hidden files are out of sight');
  }

  function setMode(m) {
    if (S.mode === m) return;
    S.mode = m;
    store.set('lod.fx.mode', m);
    renderAll();
    if (S.focus >= 0) ensureVisible(S.focus);
  }

  function setSort(key, fromMenu = false) {
    if (S.sort.key === key && !fromMenu) S.sort.dir = -S.sort.dir;
    else {
      S.sort.key = key;
      S.sort.dir = key === 'date' || key === 'size' ? -1 : 1;
    }
    store.set('lod.fx.sort', S.sort.key);
    store.set('lod.fx.dir', String(S.sort.dir));
    const name = S.focus >= 0 && S.view[S.focus] ? S.view[S.focus].n : null;
    resort();
    S.focus = name ? S.view.findIndex((e) => e.n === name) : -1;
    renderAll();
  }

  // ── sending: from the pickers, a paste, a drop ──

  function fromPicker(input) {
    const list = [...input.files];
    input.value = '';
    if (!list.length) return;
    const files = list.map((f) => ({ rel: f.webkitRelativePath || f.name, file: f }));
    const tops = [...new Set(files.map((f) => f.rel.split('/')[0]))];
    void transfers.upload(S.path, files, [], tops.length === 1 ? tops[0] : `${files.length} files`);
  }

  /**
   * What a drop holds, folders walked: [{rel, file}] and the folders, empty
   * ones too. The entries are taken in the drop itself; a browser lets them
   * go once it returns.
   */
  function dropEntries(dt) {
    const entries = [];
    const loose = [];
    for (const item of dt.items || []) {
      if (item.kind !== 'file') continue;
      const entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
      if (entry) entries.push(entry);
      else {
        const f = item.getAsFile();
        if (f) loose.push(f);
      }
    }
    if (!entries.length && !loose.length) for (const f of dt.files || []) loose.push(f);
    return { entries, loose };
  }

  async function walk(entries, loose, prep) {
    const files = loose.map((f) => ({ rel: f.name, file: f }));
    const dirs = [];
    const readAll = (reader) => new Promise((resolve, reject) => {
      const all = [];
      const more = () => reader.readEntries((batch) => {
        if (!batch.length) resolve(all);
        else {
          all.push(...batch);
          more();
        }
      }, reject);
      more();
    });
    const fileOf = (entry) => new Promise((resolve, reject) => entry.file(resolve, reject));
    const visit = async (entry, rel, depth) => {
      if (entry.isFile) {
        try {
          files.push({ rel, file: await fileOf(entry) });
        } catch {
          // unreadable (a file the computer would not hand over): left out
        }
        if (prep && files.length % 100 === 0) prep.count(files.length);
      } else if (entry.isDirectory && depth < 64) {
        const kids = await readAll(entry.createReader());
        dirs.push(rel);
        for (const k of kids) await visit(k, `${rel}/${k.name}`, depth + 1);
      }
    };
    for (const e of entries) await visit(e, e.name, 0);
    return { files, dirs };
  }

  async function sendDrop(dt, dest) {
    const { entries, loose } = dropEntries(dt);
    if (!entries.length && !loose.length) return;
    const tops = entries.length ? entries.map((e) => e.name) : loose.map((f) => f.name);
    const label = tops.length === 1 ? tops[0] : `${tops.length} items`;
    const prep = transfers.preparing(label, dest);
    let got;
    try {
      got = await walk(entries, loose, prep);
    } catch {
      prep.drop();
      snack('The browser could not read the dropped files');
      return;
    }
    prep.count(got.files.length);
    // A folder holds nothing but empty folders: those are made, nothing sent.
    await transfers.upload(dest, got.files, got.dirs.filter((d) => !got.files.some((f) => f.rel.startsWith(`${d}/`))), label, prep);
  }

  // ── a drop, onto Files or onto the desktop's picture ──

  const overlay = h('div', { class: 'drop', hidden: true, 'aria-hidden': 'true' },
    h('div', { class: 'drop-frame' },
      h('div', { class: 'drop-art' }, folderIcon(null, 88), h('span', { class: 'drop-arrow' }, ico('upload'))),
      h('div', { class: 'drop-title' }), h('div', { class: 'drop-sub' })));
  document.body.append(overlay);
  let dragDepth = 0;
  let dropTarget = null;
  let hoverRow = null;
  /** Whether the drop zone should be up: a drop can come in the very frame its drag did. */
  let dropWanted = false;

  const external = (e) => e.dataTransfer && [...(e.dataTransfer.types || [])].includes('Files') && !internalDrag;
  let internalDrag = null;

  function targetAt(x, y) {
    if (!S.open || !D) return { path: desktopPath(), label: K.deskTarget, desk: true };
    if (S.trash) return { path: null, label: 'the Trash', bad: 'Files cannot be dropped into the Trash' };
    const el = document.elementFromPoint(x, y);
    const rowEl = el && el.closest && el.closest('.fx-row, .fx-tile');
    if (rowEl) {
      const e = S.view[Number(rowEl.dataset.i)];
      if (e && isDir(e) && e.lt !== 'x') return { path: pathOf(e), label: e.n, row: rowEl };
    }
    const crumb = el && el.closest && el.closest('.crumb[data-path], .place[data-path]');
    if (crumb && crumb.dataset.path !== S.places.find((p) => p.id === 'trash')?.path) {
      return { path: crumb.dataset.path, label: labelOf(crumb.dataset.path), row: crumb };
    }
    // The empty folder's sentence, without its full stop: a title.
    if (!S.writable) return { path: null, label: labelOf(S.path), bad: K.noEntry(labelOf(S.path)).replace(/\.$/, '') };
    return { path: S.path, label: labelOf(S.path) };
  }

  function showDrop(t) {
    overlay.hidden = false;
    overlay.classList.toggle('bad', !!t.bad);
    overlay.classList.toggle('desk', !!t.desk);
    overlay.classList.toggle('on-row', !!t.row);
    overlay.querySelector('.drop-title').textContent = t.bad || (t.desk ? K.dropDesk : `Drop to copy into ${t.label}`);
    overlay.querySelector('.drop-sub').textContent = t.bad ? '' : K.dropSub(app.phoneName());
    dropWanted = true;
    requestAnimationFrame(() => {
      if (dropWanted) overlay.classList.add('on');
    });
    if (hoverRow && hoverRow !== t.row) hoverRow.classList.remove('drop-into');
    hoverRow = t.row || null;
    if (hoverRow) hoverRow.classList.add('drop-into');
  }

  function hideDrop() {
    dropWanted = false;
    overlay.classList.remove('on');
    if (hoverRow) hoverRow.classList.remove('drop-into');
    hoverRow = null;
    setTimeout(() => {
      if (!overlay.classList.contains('on')) overlay.hidden = true;
    }, 220);
  }

  window.addEventListener('dragenter', (e) => {
    if ((!external(e) && !internalDrag) || !app.token()) return;
    e.preventDefault();
    dragDepth++;
    if (!internalDrag && !S.places.length) void loadInfo();
  });
  window.addEventListener('dragover', (e) => {
    if (internalDrag) {
      onInternalOver(e);
      return;
    }
    if (!external(e) || !app.token()) return;
    e.preventDefault();
    dropTarget = targetAt(e.clientX, e.clientY);
    e.dataTransfer.dropEffect = dropTarget.path ? 'copy' : 'none';
    showDrop(dropTarget);
  });
  window.addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) hideDrop();
  });
  window.addEventListener('drop', async (e) => {
    if (internalDrag) return;
    if (!external(e) || !app.token()) return;
    e.preventDefault();
    dragDepth = 0;
    hideDrop();
    const t = targetAt(e.clientX, e.clientY);
    if (!t.path) {
      snack(t.bad);
      return;
    }
    if (!S.places.length) await loadInfo();
    const dest = t.desk ? desktopPath() : t.path;
    await sendDrop(e.dataTransfer, dest);
  });

  // ── dragging what is here: into a folder of Linux's, or out to this computer ──

  let dragTicket = null;
  function prepareDragOut(e) {
    // A browser wants the address at the start of the drag, which leaves no
    // time to ask the phone for a ticket: asked for at the press instead.
    dragTicket = null;
    if (isDir(e) || !('chrome' in window)) return;
    const p = pathOf(e);
    api.json('ticket', { form: { kind: 'get', p } }).then((j) => {
      dragTicket = { p, k: j.k, n: e.n };
    }, () => {});
  }

  function onDragStart(ev) {
    const el = ev.target.closest('.fx-row, .fx-tile');
    if (!el) return;
    const e = S.view[Number(el.dataset.i)];
    if (!e) return;
    if (!S.sel.has(e.n)) {
      S.sel = new Set([e.n]);
      renderList();
      renderSel();
    }
    const list = selected();
    internalDrag = { paths: list.map(pathOf), names: list.map((x) => x.n) };
    ev.dataTransfer.effectAllowed = 'copyMove';
    ev.dataTransfer.setData('text/plain', internalDrag.paths.join('\n'));
    if (list.length === 1 && dragTicket && dragTicket.p === pathOf(e)) {
      // Chrome and Edge: dropped onto the computer's desktop or a folder, it downloads there.
      const url = new URL(api.url('get', { k: dragTicket.k, dl: 1 }), location.href).href;
      ev.dataTransfer.setData('DownloadURL', `application/octet-stream:${e.n}:${url}`);
    }
    const ghost = h('div', { class: 'drag-ghost' }, entryIcon(list[0], 28), h('span', { text: list.length === 1 ? list[0].n : `${list.length} items` }));
    document.body.append(ghost);
    ev.dataTransfer.setDragImage(ghost, 16, 16);
    setTimeout(() => ghost.remove(), 0);
  }

  function onInternalOver(e) {
    const el = document.elementFromPoint(e.clientX, e.clientY);
    const rowEl = el && el.closest && el.closest('.fx-row, .fx-tile, .crumb[data-path], .place[data-path]');
    let dir = null;
    if (rowEl && rowEl.dataset.path) dir = rowEl.dataset.path;
    else if (rowEl) {
      const x = S.view[Number(rowEl.dataset.i)];
      if (x && isDir(x) && !internalDrag.names.includes(x.n)) dir = pathOf(x);
    }
    const trashPath = (S.places.find((p) => p.id === 'trash') || {}).path;
    if (dir && internalDrag.paths.some((p) => dir === p || dir.startsWith(`${p}/`) || parentOf(p) === dir) && dir !== trashPath) dir = null;
    if (hoverRow && hoverRow !== rowEl) hoverRow.classList.remove('drop-into');
    hoverRow = dir ? rowEl : null;
    if (hoverRow) hoverRow.classList.add('drop-into');
    if (dir) {
      e.preventDefault();
      e.dataTransfer.dropEffect = dir === trashPath ? 'move' : e.ctrlKey || e.altKey ? 'copy' : 'move';
    }
  }

  function onInternalDrop(e) {
    if (!internalDrag) return;
    const target = hoverRow;
    if (hoverRow) hoverRow.classList.remove('drop-into');
    hoverRow = null;
    if (!target) return;
    e.preventDefault();
    let dir = target.dataset.path;
    if (!dir) dir = pathOf(S.view[Number(target.dataset.i)]);
    const paths = internalDrag.paths;
    const trashPath = (S.places.find((p) => p.id === 'trash') || {}).path;
    internalDrag = null;
    if (dir === trashPath) void remove(S.view.filter((x) => paths.includes(pathOf(x))), false);
    else void moveTo(paths, dir, e.ctrlKey || e.altKey);
  }

  // ── wiring ──

  function wire() {
    const sc = D.scroll;
    let raf = 0;
    sc.addEventListener('scroll', () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        renderList();
      });
    });
    new ResizeObserver(() => renderList()).observe(sc);
    sc.addEventListener('pointerdown', (ev) => {
      if (ev.button !== 0 && ev.button !== 2) return;
      const el = ev.target.closest('.fx-row, .fx-tile');
      if (!el) {
        if (ev.button === 0 && !ev.ctrlKey && !ev.metaKey && !ev.shiftKey) selectNone();
        return;
      }
      const i = Number(el.dataset.i);
      const e = S.view[i];
      if (ev.button === 2) {
        if (!S.sel.has(e.n)) setFocus(i, { select: true });
        return;
      }
      const onCheck = ev.target.closest('.fx-check');
      if (ev.shiftKey) setFocus(i, { extend: true });
      else if (ev.ctrlKey || ev.metaKey || onCheck) setFocus(i, { toggle: true });
      else if (!S.sel.has(e.n)) setFocus(i, { select: true });
      else {
        S.focus = i;
        // A press on what is already chosen keeps the rest chosen, for a drag;
        // a click without one leaves only it.
        el.addEventListener('pointerup', () => {
          if (!internalDrag && S.sel.size > 1 && !onCheck) setFocus(i, { select: true });
        }, { once: true });
        renderList();
      }
      if (!onCheck) prepareDragOut(e);
    });
    sc.addEventListener('dblclick', (ev) => {
      const el = ev.target.closest('.fx-row, .fx-tile');
      if (!el || ev.target.closest('.fx-check')) return;
      open(S.view[Number(el.dataset.i)]);
    });
    sc.addEventListener('contextmenu', (ev) => {
      ev.preventDefault();
      const el = ev.target.closest('.fx-row, .fx-tile');
      if (el) itemMenu(ev.clientX, ev.clientY);
      else {
        selectNone();
        backgroundMenu(ev.clientX, ev.clientY);
      }
    });
    D.main.addEventListener('contextmenu', (ev) => {
      if (ev.target.closest('.fx-scroll')) return;
      if (ev.target.closest('.fx-empty')) {
        ev.preventDefault();
        backgroundMenu(ev.clientX, ev.clientY);
      }
    });
    D.root.addEventListener('mouseup', (ev) => {
      // A mouse's own back and forward buttons go back and forward here.
      if (ev.button === 3 || ev.button === 4) {
        ev.preventDefault();
        travel(ev.button === 3 ? -1 : 1);
      }
    });
    sc.addEventListener('dragstart', onDragStart);
    document.addEventListener('dragend', () => {
      internalDrag = null;
      dragDepth = 0;
      if (hoverRow) hoverRow.classList.remove('drop-into');
      hoverRow = null;
    });
    D.root.addEventListener('drop', onInternalDrop);
    D.root.addEventListener('keydown', onKey);
    // Focus that fell to the page itself (a dialog closed, a click on nothing) still has the keys.
    document.addEventListener('keydown', (e) => {
      // Never while a dialog is up (Quick look, a question): Escape is the dialog's, to close the dialog.
      if (document.querySelector('dialog[open]')) return;
      if (S.open && (e.target === document.body || e.target === document.documentElement)) onKey(e);
    });
    D.root.addEventListener('paste', onPaste);
    document.addEventListener('paste', (e) => {
      if (S.open && !D.root.contains(e.target) && e.target === document.body) onPaste(e);
    });
  }

  let typed = '';
  let typedAt = 0;

  function onKey(e) {
    if (!S.open || document.querySelector('dialog[open]')) return;
    const inField = e.target.matches && e.target.matches('input, textarea');
    if (inField) return;
    const mod = MAC ? e.metaKey : e.ctrlKey;
    const k = e.key;
    const perRow = S.mode === 'grid' ? cols : 1;
    const page = Math.max(1, Math.floor(D.scroll.clientHeight / (S.mode === 'grid' ? TILE_H : ROW_H)) * perRow);
    const moveTo_ = (i) => setFocus(i, { select: !mod && !e.shiftKey, extend: e.shiftKey });
    let used = true;
    if (k === 'ArrowDown') moveTo_(S.focus < 0 ? 0 : S.focus + perRow);
    else if (k === 'ArrowUp' && e.altKey) goUp();
    else if (k === 'ArrowUp') moveTo_(S.focus < 0 ? 0 : S.focus - perRow);
    else if (k === 'ArrowRight' && e.altKey) travel(1);
    else if (k === 'ArrowLeft' && e.altKey) travel(-1);
    else if (k === 'ArrowRight' && S.mode === 'grid') moveTo_(S.focus + 1);
    else if (k === 'ArrowLeft' && S.mode === 'grid') moveTo_(S.focus - 1);
    else if (k === 'Home') moveTo_(0);
    else if (k === 'End') moveTo_(S.view.length - 1);
    else if (k === 'PageDown') moveTo_(S.focus + page);
    else if (k === 'PageUp') moveTo_(S.focus - page);
    else if (k === 'Enter' && e.altKey) {
      const x = S.view[S.focus];
      if (x) void properties(x);
    } else if (k === 'Enter') open(S.view[S.focus]);
    else if (k === ' ' && !mod) {
      const x = S.view[S.focus];
      if (x && !isDir(x)) open(x);
      else if (x) setFocus(S.focus, { toggle: true });
    } else if (k === ' ' && mod) setFocus(S.focus, { toggle: true });
    else if (k === 'Backspace') goUp();
    else if (k === 'Escape') {
      if (S.search || S.filter) clearSearch();
      else if (S.clip) {
        S.clip = null;
        renderList();
        renderStatus();
      } else selectNone();
    } else if (mod && (k === 'a' || k === 'A')) selectAll();
    else if (mod && (k === 'h' || k === 'H')) toggleHidden();
    else if (mod && e.shiftKey && (k === 'n' || k === 'N')) void newFolder();
    else if (mod && (k === 'u' || k === 'U')) {
      if (S.writable && !S.trash) D.pickFiles.click();
    } else if ((mod && (k === 'f' || k === 'F')) || k === '/') D.q.focus();
    else if (mod && (k === 'c' || k === 'C')) {
      if (selected().length) clip('copy', selected());
    } else if (mod && (k === 'x' || k === 'X')) {
      if (selected().length && !S.trash) clip('cut', selected());
    } else if (mod && (k === 'v' || k === 'V')) {
      // The paste event decides: files from the computer, or what was cut or copied here.
      used = false;
    } else if (k === 'Delete') void remove(selected(), e.shiftKey);
    else if (k === 'F2') renameFocused();
    else if (k === 'F5') void go(S.path, { push: false });
    else if (k.length === 1 && !mod && !e.altKey && /\S/.test(k)) {
      // Typed: to the first name that starts so, as in every file manager.
      const now = performance.now();
      typed = now - typedAt > 900 ? k : typed + k;
      typedAt = now;
      const t = typed.toLowerCase();
      const start = typed.length === 1 ? S.focus + 1 : Math.max(0, S.focus);
      const order = [...S.view.keys()].map((i) => (start + i) % S.view.length);
      const hit = order.find((i) => S.view[i].n.toLowerCase().startsWith(t));
      if (hit !== undefined) setFocus(hit, { select: true });
    } else used = false;
    if (used) {
      e.preventDefault();
      e.stopPropagation();
    }
  }

  function onPaste(e) {
    if (!S.open || (e.target.matches && e.target.matches('input, textarea'))) return;
    const dt = e.clipboardData;
    if (dt && dt.files && dt.files.length && S.writable && !S.trash) {
      e.preventDefault();
      const list = [...dt.files];
      void transfers.upload(S.path, list.map((f) => ({ rel: f.name, file: f })), [], list.length === 1 ? list[0].name : `${list.length} files`);
      return;
    }
    if (S.clip && S.writable && !S.trash) {
      e.preventDefault();
      paste(S.path);
    }
  }

  // ── keeping up with Linux ──

  setInterval(() => {
    if (S.open && document.visibilityState === 'visible' && !S.loading && !renaming && !menuEl && !document.querySelector('dialog[open]')) {
      void refresh();
    }
  }, 4000);
  document.addEventListener('visibilitychange', () => {
    if (S.open && document.visibilityState === 'visible') void refresh();
  });

  // ── to the viewer ──

  return {
    transfers,
    /** Files, at {@code path} (the home folder by default). */
    async show(path, select = null) {
      if (!D) build();
      S.open = true;
      D.root.hidden = false;
      D.root.classList.remove('no-linux');
      S.down = false;
      // Opening until the phone has answered: never an empty folder's words for a folder not yet read.
      S.loading = true;
      D.root.classList.add('loading');
      renderList(true);
      requestAnimationFrame(() => D.root.classList.add('on'));
      const ok = await loadInfo(true);
      if (!ok) {
        renderState();
        return;
      }
      const want = path || (S.path && !S.trash ? S.path : S.home);
      await go(want, { push: false, select });
      focusList();
    },
    hide() {
      S.open = false;
      if (!D) return;
      closeMenu();
      peek.close();
      D.root.classList.remove('on');
      D.root.hidden = true;
    },
    isOpen: () => S.open,
    /** The desktop's picture is up: a drop on it goes to Linux's desktop. */
    loadInfo,
  };
}
