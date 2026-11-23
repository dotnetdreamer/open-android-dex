// Quick look: a file of Linux's, seen without downloading it. A photo, a
// video or a song plays from the phone as it streams (Range requests, so a
// video seeks at once); a PDF opens in the browser's own viewer; text and
// code show with their line numbers. The arrow keys go through the folder.
//
// What the phone serves for this is never this page: everything but a PDF
// comes with a sandbox (LinuxFiles.get), so an HTML file of Linux's is shown
// as its text, and cannot run as the page and read its token.

import { entryIcon, fmtDate, fmtSize, kindLabel, kindOf } from './fileicons.js';

const h = (tag, props = {}, ...kids) => {
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
};

function line(d) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 20 20');
  s.setAttribute('aria-hidden', 'true');
  s.setAttribute('class', 'li');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', d);
  s.appendChild(p);
  return s;
}

const D_PREV = 'M12.5 4.5 7 10l5.5 5.5';
const D_NEXT = 'M7.5 4.5 13 10l-5.5 5.5';
const D_CLOSE = 'M5.5 5.5l9 9M14.5 5.5l-9 9';
const D_DOWN = 'M10 4v9.5M6 9.5l4 4 4-4M4 15.5h12';
const D_TAB = 'M11 4h5v5M16 4l-7 7M14 12v4H4V6h4';
const D_WRAP = 'M3.5 5.5h13M3.5 10h10a2.5 2.5 0 0 1 0 5H10M11.5 13l-2 2 2 2M3.5 14.5h3';

/** The first this many bytes of a text file are shown: plenty to read, little to wait for. */
const TEXT_BYTES = 512 * 1024;

export class Peek {
  constructor({ api, download, onClose }) {
    this.api = api;
    this.download = download;
    this.onClose = onClose;
    this.list = [];
    this.i = 0;
    this.seq = 0;
    this.dlg = null;
    this.wrap = true;
  }

  build() {
    const dlg = h('dialog', { class: 'peek', 'aria-label': 'Quick look' });
    this.icon = h('span', { class: 'peek-icon' });
    this.name = h('b', { class: 'peek-name' });
    this.meta = h('span', { class: 'peek-meta' });
    this.count = h('span', { class: 'peek-count' });
    const btn = (d, label, run, cls = '') => {
      const b = h('button', { type: 'button', class: `peek-btn ${cls}`, title: label, 'aria-label': label, onclick: run });
      b.append(line(d));
      return b;
    };
    this.wrapBtn = btn(D_WRAP, 'Wrap long lines', () => {
      this.wrap = !this.wrap;
      this.stage.classList.toggle('nowrap', !this.wrap);
      this.wrapBtn.setAttribute('aria-pressed', String(this.wrap));
    }, 'wrap');
    this.tabBtn = btn(D_TAB, 'Open in a new tab', () => void this.newTab());
    const bar = h('div', { class: 'peek-bar' }, this.icon, h('div', { class: 'peek-title' }, this.name, this.meta), this.count,
      this.wrapBtn, this.tabBtn,
      btn(D_DOWN, 'Download', () => this.download(this.list[this.i]), 'dl'),
      btn(D_CLOSE, 'Close (Esc)', () => this.close(), 'x'));
    this.stage = h('div', { class: 'peek-stage' });
    this.prev = btn(D_PREV, 'Previous (←)', () => this.step(-1), 'nav prev');
    this.next = btn(D_NEXT, 'Next (→)', () => this.step(1), 'nav next');
    dlg.append(bar, h('div', { class: 'peek-body' }, this.prev, this.stage, this.next));
    dlg.addEventListener('cancel', (e) => {
      e.preventDefault();
      this.close();
    });
    dlg.addEventListener('keydown', (e) => {
      const inMedia = e.target.matches && e.target.matches('video, audio');
      if (e.key === 'ArrowRight' && !inMedia) this.step(1);
      else if (e.key === 'ArrowLeft' && !inMedia) this.step(-1);
      else if (e.key === ' ' && !inMedia && !(e.target.matches && e.target.matches('button'))) this.close();
      else if (e.key === 'Escape') this.close();
      else {
        e.stopPropagation();
        return;
      }
      e.preventDefault();
      e.stopPropagation();
    });
    dlg.addEventListener('click', (e) => {
      // A click on the dark around it closes it, as on a phone.
      if (e.target === dlg || e.target.classList.contains('peek-body')) this.close();
    });
    document.body.append(dlg);
    this.dlg = dlg;
  }

  /** {@code list}: the folder's files as shown; {@code current}: the one to see first. */
  show(list, current, pathOf) {
    if (!this.dlg) this.build();
    this.list = list;
    this.pathOf = pathOf;
    this.i = Math.max(0, list.indexOf(current));
    if (!this.dlg.open) this.dlg.showModal();
    this.dlg.classList.remove('leaving');
    // The keys are Quick look's from the start: Escape closes, the arrows go through the folder.
    this.dlg.querySelector('.peek-btn.x').focus({ preventScroll: true });
    void this.render();
  }

  close() {
    if (!this.dlg || !this.dlg.open) return;
    this.seq++;
    this.clearStage();
    this.dlg.close();
    if (this.onClose) this.onClose();
  }

  step(d) {
    if (this.list.length < 2) return;
    this.i = (this.i + d + this.list.length) % this.list.length;
    void this.render();
  }

  clearStage() {
    // A video or a song stops when it is no longer shown, and lets go of its connection.
    for (const m of this.stage.querySelectorAll('video, audio')) {
      m.pause();
      m.removeAttribute('src');
      m.load();
    }
    for (const u of this.urls || []) URL.revokeObjectURL(u);
    this.urls = [];
    this.stage.textContent = '';
  }

  async url(e) {
    const { k } = await this.api.json('ticket', { form: { kind: 'get', p: this.pathOf(e) } });
    return this.api.url('get', { k });
  }

  async newTab() {
    const e = this.list[this.i];
    try {
      window.open(await this.url(e), '_blank', 'noopener');
    } catch {
      // the phone went: nothing to open
    }
  }

  async render() {
    const seq = ++this.seq;
    const e = this.list[this.i];
    this.clearStage();
    const kind = kindOf(e.n);
    this.icon.textContent = '';
    this.icon.append(entryIcon(e, 28));
    this.name.textContent = e.n;
    this.meta.textContent = [kindLabel(e.n, kind), fmtSize(e.s), fmtDate(e.m)].filter(Boolean).join(' · ');
    this.count.textContent = this.list.length > 1 ? `${this.i + 1} of ${this.list.length}` : '';
    this.prev.hidden = this.next.hidden = this.list.length < 2;
    this.stage.className = `peek-stage k-${kind}${this.wrap ? '' : ' nowrap'}`;
    this.wrapBtn.hidden = true;
    this.stage.append(h('div', { class: 'peek-wait' }, h('i')));
    let url;
    try {
      url = await this.url(e);
    } catch {
      if (seq === this.seq) this.nothing(e, 'The phone did not answer.');
      return;
    }
    if (seq !== this.seq) return;
    if (kind === 'image') this.image(e, url, seq);
    else if (kind === 'video') this.media('video', e, url, seq);
    else if (kind === 'audio') this.media('audio', e, url, seq);
    else if (kind === 'pdf') {
      this.stage.textContent = '';
      this.stage.append(h('iframe', { src: url, title: e.n, class: 'peek-pdf' }));
    } else if (kind === 'text' || kind === 'code' || (kind === 'file' && e.s < 4 * 1024 * 1024)) {
      await this.text(e, seq);
    } else {
      this.nothing(e);
    }
  }

  image(e, url, seq) {
    const img = h('img', { alt: e.n, src: url, class: 'peek-img', draggable: 'false' });
    img.addEventListener('load', () => {
      if (seq !== this.seq) return;
      this.stage.querySelector('.peek-wait')?.remove();
      img.classList.add('in');
      // Bigger than the window: a click shows it pixel for pixel, another fits it again.
      const big = img.naturalWidth > this.stage.clientWidth || img.naturalHeight > this.stage.clientHeight;
      img.classList.toggle('can-zoom', big);
    });
    img.addEventListener('error', async () => {
      // A format this browser does not draw (a HEIC from a phone): the phone's
      // own decoder makes a picture of it instead.
      if (seq !== this.seq) return;
      try {
        const r = await fetch(this.api.url('thumb', { path: this.pathOf(e), s: 512 }), { headers: this.api.auth() });
        if (!r.ok) throw new Error();
        const u = URL.createObjectURL(await r.blob());
        this.urls.push(u);
        if (seq !== this.seq) return;
        img.src = u;
      } catch {
        if (seq === this.seq) this.nothing(e, `The browser cannot show “${e.n}”.`);
      }
    }, { once: true });
    img.addEventListener('click', () => {
      if (!img.classList.contains('can-zoom')) return;
      this.stage.classList.toggle('zoomed');
    });
    this.stage.append(img);
  }

  media(tag, e, url, seq) {
    const m = h(tag, { src: url, controls: true, autoplay: true, playsinline: true, preload: 'metadata', class: `peek-${tag}` });
    m.addEventListener('loadeddata', () => {
      if (seq === this.seq) this.stage.querySelector('.peek-wait')?.remove();
    });
    m.addEventListener('error', () => {
      if (seq === this.seq) this.nothing(e, `The browser cannot play “${e.n}”.`);
    });
    if (tag === 'audio') {
      const art = h('div', { class: 'peek-art' }, entryIcon(e, 120));
      this.stage.append(art);
      fetch(this.api.url('thumb', { path: this.pathOf(e), s: 512 }), { headers: this.api.auth() }).then(async (r) => {
        if (!r.ok || seq !== this.seq) return;
        const u = URL.createObjectURL(await r.blob());
        this.urls.push(u);
        art.textContent = '';
        art.append(h('img', { src: u, alt: '' }));
      }).catch(() => {});
      this.stage.querySelector('.peek-wait')?.remove();
    }
    this.stage.append(m);
    m.focus({ preventScroll: true });
  }

  async text(e, seq) {
    let buf;
    try {
      const r = await fetch(this.api.url('get', { path: this.pathOf(e) }), {
        headers: { ...this.api.auth(), Range: `bytes=0-${TEXT_BYTES - 1}` }, cache: 'no-store',
      });
      if (!r.ok) throw new Error(String(r.status));
      buf = new Uint8Array(await r.arrayBuffer());
    } catch {
      if (seq === this.seq) this.nothing(e, 'The phone did not answer.');
      return;
    }
    if (seq !== this.seq) return;
    // A NUL near the start: not text, whatever its name says.
    if (buf.subarray(0, 8192).includes(0)) {
      this.nothing(e);
      return;
    }
    let text = new TextDecoder('utf-8', { fatal: false }).decode(buf);
    const cut = e.s > TEXT_BYTES;
    if (/\.json$/i.test(e.n) && !cut) {
      try {
        text = JSON.stringify(JSON.parse(text), null, 2);
      } catch {
        // not quite JSON: as it is
      }
    }
    const lines = text.split(/\r\n|\r|\n/);
    if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop();
    const shown = lines.slice(0, 20000);
    this.stage.textContent = '';
    this.wrapBtn.hidden = false;
    this.wrapBtn.setAttribute('aria-pressed', String(this.wrap));
    const gutter = h('pre', { class: 'gutter', 'aria-hidden': 'true', text: shown.map((_, i) => i + 1).join('\n') });
    const code = h('pre', { class: 'code', tabindex: '0', text: shown.join('\n') });
    const sheet = h('div', { class: 'peek-text' }, gutter, code);
    this.stage.append(sheet);
    if (cut || lines.length > shown.length) {
      this.stage.append(h('div', { class: 'peek-note', text: `Showing the first ${fmtSize(TEXT_BYTES)} of ${fmtSize(e.s)}. Download “${e.n}” to see the whole file.` }));
    }
    code.focus({ preventScroll: true });
  }

  nothing(e, why = '') {
    this.stage.textContent = '';
    this.stage.className = 'peek-stage none';
    const dl = h('button', { type: 'button', class: 'fx-primary', onclick: () => this.download(e) });
    dl.append(line(D_DOWN), h('span', { text: 'Download' }));
    this.stage.append(h('div', { class: 'peek-none' }, entryIcon(e, 120), h('h3', { text: e.n }),
      h('p', { text: why || `“${e.n}” has no preview. Download “${e.n}” to open the file on this computer.` }),
      h('p', { class: 'sub', text: fmtSize(e.s) }), dl));
  }
}
