// Files going to Linux and coming back (LinuxFiles.java on the phone). The
// phone's own files too, for Remote desktop's page (phone-files.js): the same
// code through the door's /pfs/, with the phone's words.
//
// Up: a big file is one PUT, resumable: a cut connection, a pause or a
// sleeping laptop carries on from what the phone says arrived. Small files
// go many to a request, in one Blob that only refers to them (nothing is
// read into memory): a folder of a thousand photos is a handful of requests,
// not a thousand round trips. Three requests at a time keep the Wi-Fi full.
//
// Down: one file, or several as a ZIP, is the browser's own download (fast,
// and where downloads go). Chrome and Edge can also save into a folder of
// this computer's, folders and all, with progress here.
//
// The panel in the corner says what is moving, how fast, and how long is left.

import { fileIcon, folderIcon, fmtEta, fmtSize, fmtSpeed, freeName, kindOf } from './fileicons.js';

const te = new TextEncoder();

/** One record's head in a batch (LinuxFiles.batch): kind, the path in UTF-8, and for a file its size and time. */
export function recordHead(kind, rel, size = 0, mtime = 0) {
  const name = te.encode(rel);
  const head = new Uint8Array(3 + name.length + (kind === 1 ? 16 : 0));
  const dv = new DataView(head.buffer);
  head[0] = kind;
  dv.setUint16(1, name.length);
  head.set(name, 3);
  if (kind === 1) {
    dv.setBigUint64(3 + name.length, BigInt(size));
    dv.setBigUint64(11 + name.length, BigInt(Math.max(0, Math.round(mtime || 0))));
  }
  return head;
}

/** A file at least this big goes on its own, resumable; smaller ones share a request. */
const BIG = 8 * 1024 * 1024;
const BATCH_BYTES = 32 * 1024 * 1024;
const BATCH_FILES = 400;
const PARALLEL = 3;
const TRIES = 8;

export class ApiError extends Error {
  constructor(status, code, extra = {}) {
    super(code || `HTTP ${status}`);
    this.status = status;
    this.code = code || '';
    Object.assign(this, extra);
  }
}

/** The phone's /fs/ (Linux's files), or {@code base} ('pfs/': the phone's own), with this browser's token. */
export class Api {
  constructor(token, onUnauthorized, base = 'fs/') {
    this.token = token;
    this.onUnauthorized = onUnauthorized;
    this.base = base;
  }

  url(op, query = {}) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v == null) continue;
      for (const x of [].concat(v)) p.append(k, String(x));
    }
    const s = p.toString();
    return `${this.base}${op}${s ? `?${s}` : ''}`;
  }

  auth() {
    return { Authorization: `Bearer ${this.token() || ''}` };
  }

  /** A request whose answer is JSON; a refusal is thrown as an ApiError with the phone's word for it. */
  async json(op, { query = {}, form = null, signal } = {}) {
    let r;
    try {
      r = await fetch(this.url(op, query), {
        method: form ? 'POST' : 'GET',
        headers: form ? { ...this.auth(), 'Content-Type': 'application/x-www-form-urlencoded' } : this.auth(),
        body: form ? this.formBody(form) : undefined,
        cache: 'no-store',
        signal,
      });
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      throw new ApiError(0, 'offline');
    }
    let j = {};
    try {
      j = await r.json();
    } catch {
      j = {};
    }
    if (r.status === 401) {
      this.onUnauthorized();
      throw new ApiError(401, 'unauthorized');
    }
    if (!r.ok) throw new ApiError(r.status, j.error, j);
    return j;
  }

  formBody(form) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(form)) {
      if (v == null) continue;
      for (const x of [].concat(v)) p.append(k, String(x));
    }
    return p;
  }

  /**
   * A body to the phone with its progress (fetch has none for uploads; XHR
   * does). Resolves with the JSON answer; rejects with an ApiError (status 0
   * for a network that went). {@code handle.xhr} is there to abort it.
   */
  send(method, op, query, body, onProgress, handle = {}) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      handle.xhr = x;
      x.open(method, this.url(op, query));
      x.setRequestHeader('Authorization', `Bearer ${this.token() || ''}`);
      x.setRequestHeader('Content-Type', 'application/octet-stream');
      x.responseType = 'json';
      x.upload.onprogress = (e) => onProgress && onProgress(e.loaded, e.total || body.size);
      x.onload = () => {
        const j = x.response || {};
        if (x.status === 401) {
          this.onUnauthorized();
          reject(new ApiError(401, 'unauthorized'));
        } else if (x.status >= 200 && x.status < 300) {
          resolve(j);
        } else {
          reject(new ApiError(x.status, j.error, j));
        }
      };
      x.onerror = () => reject(new ApiError(0, 'offline'));
      x.ontimeout = () => reject(new ApiError(0, 'offline'));
      x.onabort = () => reject(new ApiError(0, 'aborted'));
      x.send(body);
    });
  }
}

/** The words for what went wrong, from the phone's own; {@code words}: another page's for some of them (the phone's files'). */
export function why(e, words = null) {
  const code = e && e.code;
  if (words && code && Object.prototype.hasOwnProperty.call(words, code)) return words[code];
  switch (code) {
    case 'full': return 'The phone is out of space.';
    case 'offline': return 'The phone stopped answering.';
    case 'denied': return 'Linux does not give permission for the change.';
    case 'protected': return 'Linux’s own system folders stay unchanged from the browser.';
    case 'missing': return 'The file or folder is not there any more.';
    case 'installing': return 'Linux is still being set up on the phone.';
    case 'exists': return 'A file or folder with the same name is already there.';
    case 'notdir': return 'The path leads to a file, not a folder.';
    case 'name': return 'Linux does not allow the name.';
    case 'link': return 'A link in Linux is in the way.';
    case 'inside': return 'A folder cannot move into one of the folder’s own subfolders.';
    default: return 'Something went wrong on the phone.';
  }
}

let nextId = 1;

/**
 * Every transfer of this page: the queue, the requests, the retries, and the
 * panel that shows them. {@code ask} is the page's own question box (files.js);
 * {@code words} where the files are, as the panel says it ({@code on}: "Linux",
 * or "the phone" for the phone's own), the folder picker's id, and {@link why}'s
 * words of that page's. {@code putMax}: the most one PUT carries of a big file
 * (0: the whole rest at once), for a page a proxy may stand in front of.
 */
export class Transfers {
  constructor({ api, ask, snack, onDone, nameOf, words = {}, putMax = 0 }) {
    this.api = api;
    this.ask = ask;
    this.snack = snack;
    this.onDone = onDone;
    this.nameOf = nameOf;
    this.words = { on: 'Linux', picker: 'linux-files', why: null, ...words };
    this.putMax = putMax;
    this.jobs = [];
    this.running = 0;
    this.speed = 0;
    this.spark = [];
    this.lastTotal = 0;
    this.lastAt = performance.now();
    this.tray = new Tray(this);
    setInterval(() => this.tick(), 500);
  }

  /** Something is still moving: closing the tab asks first. */
  active() {
    return this.jobs.some((j) => j.state === 'running' || j.state === 'queued' || j.state === 'preparing');
  }

  /** A folder being read before it is sent: shown at once, however big. */
  preparing(label, dest) {
    const job = this.newJob('up', label, dest);
    job.state = 'preparing';
    this.jobs.push(job);
    this.tray.show();
    this.changed(job);
    return {
      count: (n) => {
        job.files = n;
        this.changed(job);
      },
      drop: () => {
        this.jobs = this.jobs.filter((j) => j !== job);
        this.changed(job);
      },
      job,
    };
  }

  newJob(dir, label, dest) {
    return {
      id: nextId++, dir, label, dest, files: 0, bytes: 0, tasks: [], state: 'queued', error: '', startedAt: performance.now(),
      finishedAt: 0, results: { renamed: [], failed: [], same: 0, skipped: 0, unsent: 0, paths: [] }, speed: 0, lastBytes: 0, policy: 'replace',
      tops: [],
    };
  }

  /**
   * Send {@code files} ([{rel, file}]) and {@code dirs} (rel paths of folders,
   * empty ones too) into {@code dest}, a folder of Linux's. Names already
   * there are asked about first, once for them all.
   */
  async upload(dest, files, dirs = [], label = '', prep = null) {
    const job = prep ? prep.job : this.newJob('up', label, dest);
    // Cancelled while its folder was read (files.js): nothing of it goes.
    if (job.state === 'cancelled') return null;
    if (!prep) this.jobs.push(job);
    job.state = 'preparing';
    this.tray.show();
    this.changed(job);
    // Cancel reaches the questions asked while the job is made ready (cancel),
    // and the job is not made ready again over it: until 2026-11-22 a drop
    // cancelled then went anyway once the phone answered.
    job.asking = new AbortController();
    const tooLong = files.filter((f) => te.encode(f.rel).length > 4000);
    files = files.filter((f) => !tooLong.includes(f));
    const tops = [...new Set([...files.map((f) => f.rel.split('/')[0]), ...dirs.map((d) => d.split('/')[0])])];
    job.tops = tops;
    job.topIsDir = tops.length === 1 && (dirs.includes(tops[0]) || files.some((f) => f.rel.startsWith(`${tops[0]}/`)));
    let taken = {};
    try {
      taken = tops.length ? (await this.api.json('exists', { form: { dir: dest, n: tops }, signal: job.asking.signal })).exists || {} : {};
    } catch (e) {
      if (job.state === 'cancelled') return null;
      return this.fail(job, e);
    }
    if (job.state === 'cancelled') return null;
    const clash = tops.filter((t) => taken[t]);
    if (clash.length) {
      // A second drop of the same things asks too. Replace then sends and
      // writes every file (force): the same size and time is only a quick
      // check, and two different files can share both. Without Replace
      // chosen (a retry after a cut, a drop where nothing clashed), a file
      // already there the same is left as it is, and a big one not sent.
      const choice = await this.ask({
        title: clash.length === 1 ? `“${clash[0]}” is already in ${this.nameOf(dest)}` : `${clash.length} items are already in ${this.nameOf(dest)}`,
        text: clash.length === 1
          ? `Replace “${clash[0]}” in ${this.nameOf(dest)} with the copy from this computer, keep both copies, or skip “${clash[0]}”?`
          : `Replace the ${clash.length} items in ${this.nameOf(dest)} with the copies from this computer, keep both copies of each, or skip the ${clash.length} items?`,
        list: clash.slice(0, 6).map((n) => ({ name: n, dir: taken[n] === 'd' })),
        more: clash.length > 6 ? clash.length - 6 : 0,
        buttons: [
          { label: 'Skip', value: 'skip' },
          { label: 'Keep both', value: 'rename' },
          { label: 'Replace', value: 'replace', primary: true },
        ],
      });
      if (job.state === 'cancelled') return null;
      if (!choice) {
        this.jobs = this.jobs.filter((j) => j !== job);
        this.changed(job);
        return null;
      }
      job.policy = choice;
      job.force = choice === 'replace';
      if (choice === 'skip') {
        const keep = (rel) => !taken[rel.split('/')[0]];
        job.results.skipped = files.filter((f) => !keep(f.rel)).length;
        files = files.filter((f) => keep(f.rel));
        dirs = dirs.filter(keep);
      } else if (choice === 'rename') {
        // New names for what clashes at the top, as the phone would pick
        // them; nothing under them can clash after that.
        let names = new Set();
        try {
          const l = await this.api.json('list', { query: { path: dest }, signal: job.asking.signal });
          names = new Set((l.entries || []).map((e) => e.n));
        } catch (e) {
          if (job.state === 'cancelled') return null;
          return this.fail(job, e);
        }
        if (job.state === 'cancelled') return null;
        const map = {};
        for (const t of clash) {
          map[t] = freeName(names, t, taken[t] === 'd' || dirs.includes(t) || files.some((f) => f.rel.startsWith(`${t}/`)));
          names.add(map[t]);
        }
        const re = (rel) => {
          const i = rel.indexOf('/');
          const top = i < 0 ? rel : rel.slice(0, i);
          return map[top] ? map[top] + (i < 0 ? '' : rel.slice(i)) : rel;
        };
        files = files.map((f) => ({ ...f, rel: re(f.rel) }));
        dirs = dirs.map(re);
        job.tops = job.tops.map((t) => map[t] || t);
      }
      // Folders merge whichever way it went; a file that clashes deeper is replaced.
      if (choice === 'rename') job.policy = 'rename';
    }
    this.plan(job, files, dirs);
    if (tooLong.length) job.results.failed.push(...tooLong.map((f) => [f.rel, 'name']));
    if (!job.tasks.length) {
      this.finish(job);
      return job;
    }
    job.state = 'queued';
    this.changed(job);
    this.pump();
    return job;
  }

  /** Big files alone, small ones together, the folders with the first of them. */
  plan(job, files, dirs) {
    job.files = files.length;
    job.bytes = files.reduce((n, f) => n + f.file.size, 0);
    const small = [];
    for (const f of files) {
      if (f.file.size >= BIG) job.tasks.push({ job, kind: 'put', items: [f], dirs: [], bytes: f.file.size, sent: 0, base: 0, state: 'queued', tries: 0 });
      else small.push(f);
    }
    let cur = null;
    const flush = () => {
      if (cur && (cur.items.length || cur.dirs.length)) job.tasks.push(cur);
      cur = null;
    };
    const fresh = () => ({ job, kind: 'batch', items: [], dirs: [], bytes: 0, sent: 0, base: 0, state: 'queued', tries: 0 });
    cur = fresh();
    cur.dirs = dirs.slice();
    for (const f of small) {
      if (cur.items.length >= BATCH_FILES || (cur.bytes + f.file.size > BATCH_BYTES && cur.items.length)) {
        flush();
        cur = fresh();
      }
      cur.items.push(f);
      cur.bytes += f.file.size;
    }
    flush();
    // Folders first: their batch makes the tree the big files land in.
    job.tasks.sort((a, b) => (b.dirs.length ? 1 : 0) - (a.dirs.length ? 1 : 0));
  }

  pump() {
    while (this.running < PARALLEL) {
      const t = this.next();
      if (!t) break;
      void this.run(t);
    }
  }

  next() {
    const now = performance.now();
    let soonest = Infinity;
    for (const job of this.jobs) {
      if (job.dir !== 'up' || (job.state !== 'queued' && job.state !== 'running')) continue;
      // A file whose folder is still being made waits for that batch.
      const treeFirst = job.tasks.find((t) => t.dirs.length && t.state !== 'done');
      for (const t of job.tasks) {
        if (t.state !== 'queued') continue;
        if (treeFirst && t !== treeFirst && t.kind === 'put') continue;
        if (t.waitUntil && t.waitUntil > now) {
          soonest = Math.min(soonest, t.waitUntil);
          continue;
        }
        return t;
      }
    }
    if (soonest < Infinity) {
      clearTimeout(this.wake);
      this.wake = setTimeout(() => this.pump(), soonest - now + 10);
    }
    return null;
  }

  async run(t) {
    const job = t.job;
    this.running++;
    t.state = 'running';
    if (job.state === 'queued') job.state = 'running';
    this.changed(job);
    try {
      if (t.kind === 'put') await this.runPut(t);
      else await this.runBatch(t);
      t.state = 'done';
      t.sent = 0;
      t.base = t.bytes;
    } catch (e) {
      if (t.state === 'stopped') {
        // Paused: from what the phone has, when resumed. Cancelled: nothing.
        if (job.state === 'paused') {
          t.state = 'queued';
          t.resume = true;
        }
      } else if (e.status === 401) {
        t.state = 'queued';
        job.state = 'paused';
        job.error = 'This browser was signed out on the phone.';
      } else if (e.status === 0 && t.tries < TRIES) {
        // The Wi-Fi, a sleeping phone: again in a moment, from what arrived.
        t.tries++;
        t.state = 'queued';
        t.resume = true;
        t.waitUntil = performance.now() + Math.min(30000, 1000 * 2 ** (t.tries - 1));
      } else {
        t.state = 'failed';
        this.fail(job, e);
      }
    } finally {
      this.running--;
      t.handle = null;
      if (job.state === 'running' && job.tasks.every((x) => x.state === 'done')) this.finish(job);
      this.changed(job);
      this.pump();
    }
  }

  target(job, rel) {
    return `${job.dest === '/' ? '' : job.dest}/${rel}`;
  }

  async runPut(t) {
    const job = t.job;
    const f = t.items[0];
    const q = { path: this.target(job, f.rel), size: f.file.size, mtime: f.file.lastModified || 0, on: job.policy === 'rename' ? 'rename' : 'replace',
      ...(job.force ? { force: 1 } : {}) };
    // Asked first, every time: a file already there (the same size and time)
    // is not sent again, unless Replace was chosen (force), and what the
    // phone would refuse at once (no room, apps' folders) is said now. Once the body is on its way that answer is
    // lost: the phone hangs up on a body it will not take, and a browser,
    // still sending, gets the reset and not the answer.
    // Pause and Cancel reach the question too, and nothing is sent after
    // either, even when the answer comes anyway: until 2026-11-22 the PUT
    // went once the answer came, and a cancelled Replace still replaced.
    t.handle = { abort: new AbortController() };
    const ask = await this.api.json('part', { query: t.resume ? q : { ...q, fresh: 1 }, signal: t.handle.abort.signal });
    if (t.state === 'stopped') throw new ApiError(0, 'aborted');
    if (ask.same) {
      job.results.paths.push(q.path);
      job.results.same++;
      job.results.unsent += f.file.size;
      return;
    }
    let off = t.resume ? ask.have || 0 : 0;
    // Four tries that bring nothing new, as ever. With putMax, the rest goes a
    // piece at a time (the phone adds each to the part file and says what it
    // has), and a piece that arrived is progress, never a try used up.
    for (let i = 0; i < 4;) {
      if (t.state === 'stopped') throw new ApiError(0, 'aborted');
      if (off > f.file.size) off = 0;
      const from = off;
      const end = this.putMax > 0 ? Math.min(f.file.size, off + this.putMax) : f.file.size;
      t.base = off;
      t.sent = 0;
      t.handle = {};
      let r;
      try {
        r = await this.api.send('PUT', 'put', { ...q, off }, f.file.slice(off, end), (loaded) => {
          t.sent = loaded;
        }, t.handle);
      } catch (e) {
        if (e.status === 409 && e.code === 'offset') {
          off = e.have || 0;
          i++;
          continue;
        }
        throw e;
      }
      if (r.done) {
        if (r.path) job.results.paths.push(r.path);
        if (r.same) job.results.same++;
        if (r.path && r.path !== q.path) job.results.renamed.push([f.rel, r.path.slice(r.path.lastIndexOf('/') + 1)]);
        return;
      }
      off = r.have || 0;
      if (off <= from) i++;
    }
    throw new ApiError(0, 'offline');
  }

  async runBatch(t) {
    const job = t.job;
    const parts = [];
    for (const d of t.dirs) parts.push(recordHead(2, d));
    for (const it of t.items) {
      parts.push(recordHead(1, it.rel, it.file.size, it.file.lastModified || 0));
      parts.push(it.file);
    }
    parts.push(new Uint8Array([0]));
    const blob = new Blob(parts);
    t.handle = {};
    t.sent = 0;
    const r = await this.api.send('POST', 'batch', { dir: job.dest, on: job.policy === 'rename' ? 'rename' : 'replace', total: t.bytes, ...(job.force ? { force: 1 } : {}) },
      blob, (loaded, total) => {
        t.sent = Math.min(t.bytes, (loaded / (total || blob.size)) * t.bytes);
      }, t.handle);
    job.results.same += r.same || 0;
    for (const x of r.renamed || []) job.results.renamed.push(x);
    for (const x of r.failed || []) job.results.failed.push(x);
  }

  fail(job, e) {
    // The job's other uploads stop with it, a body on its way or a question
    // to the phone before one; Try again sends them (resume).
    for (const t of job.tasks) {
      if (t.state === 'running' && t.handle && (t.handle.xhr || (t.kind === 'put' && t.handle.abort))) {
        t.state = 'stopped';
        if (t.handle.xhr) t.handle.xhr.abort();
        if (t.handle.abort) t.handle.abort.abort();
      }
    }
    job.state = 'failed';
    job.error = e && e.status === 0 && e.code === 'offline' ? 'The phone stopped answering. Check that the phone is still on the same Wi-Fi.' : why(e, this.words.why);
    job.finishedAt = performance.now();
    this.changed(job);
    return null;
  }

  finish(job) {
    job.state = 'done';
    job.finishedAt = performance.now();
    this.changed(job);
    if (this.onDone) this.onDone(job);
    // A panel with nothing left moving folds itself away after a while.
    clearTimeout(this.fold);
    this.fold = setTimeout(() => {
      if (!this.active()) this.tray.settle();
    }, 6000);
  }

  pause(job) {
    if (job.state !== 'running' && job.state !== 'queued') return;
    job.state = 'paused';
    for (const t of job.tasks) {
      if (t.state === 'running') {
        t.state = 'stopped';
        if (t.handle && t.handle.xhr) t.handle.xhr.abort();
        if (t.handle && t.handle.abort) t.handle.abort.abort();
      }
    }
    this.changed(job);
  }

  resume(job) {
    if (job.state !== 'paused' && job.state !== 'failed') return;
    job.error = '';
    for (const t of job.tasks) {
      // Failed, or stopped when another of the job's failed (fail): until
      // 2026-11-22 those were left stopped, and the job never finished.
      if (t.state === 'failed' || t.state === 'stopped') {
        t.state = 'queued';
        t.resume = true;
        t.tries = 0;
      }
    }
    job.state = 'queued';
    this.changed(job);
    this.pump();
  }

  cancel(job) {
    const wasPut = [];
    for (const t of job.tasks) {
      if (t.state === 'running') {
        t.state = 'stopped';
        if (t.handle && t.handle.xhr) t.handle.xhr.abort();
        if (t.handle && t.handle.abort) t.handle.abort.abort();
      }
      if (t.kind === 'put' && t.state !== 'done' && (t.base || t.sent || t.resume)) wasPut.push(t);
    }
    job.state = 'cancelled';
    job.finishedAt = performance.now();
    if (job.asking) job.asking.abort();
    // What was half sent does not stay on the phone.
    for (const t of wasPut) {
      const f = t.items[0];
      void this.api.json('unpart', { form: {}, query: { path: this.target(job, f.rel), size: f.file.size, mtime: f.file.lastModified || 0 } }).catch(() => {});
    }
    this.changed(job);
    setTimeout(() => {
      this.jobs = this.jobs.filter((j) => j !== job);
      this.changed(job);
    }, 1800);
  }

  dismiss(job) {
    this.jobs = this.jobs.filter((j) => j !== job);
    this.changed(job);
  }

  /** How far a job is, in bytes. */
  moved(job) {
    let n = 0;
    for (const t of job.tasks) n += t.state === 'done' ? t.bytes : Math.min(t.bytes, t.base + t.sent);
    return n;
  }

  tick() {
    const now = performance.now();
    const dt = (now - this.lastAt) / 1000;
    this.lastAt = now;
    let total = 0;
    let moving = false;
    for (const j of this.jobs) {
      const m = this.moved(j);
      total += m;
      if (j.state === 'running') {
        moving = true;
        const inst = Math.max(0, m - j.lastBytes) / dt;
        j.speed = j.speed ? j.speed * 0.6 + inst * 0.4 : inst;
      } else {
        j.speed = 0;
      }
      j.lastBytes = m;
    }
    const inst = Math.max(0, total - this.lastTotal) / dt;
    this.lastTotal = total;
    if (moving) {
      this.speed = this.speed ? this.speed * 0.6 + inst * 0.4 : inst;
      this.spark.push(inst);
      if (this.spark.length > 90) this.spark.shift();
    } else {
      this.speed = 0;
      if (this.spark.length) {
        this.spark.push(0);
        if (this.spark.length > 90) this.spark.shift();
        if (this.spark.every((v) => v === 0)) this.spark = [];
      }
    }
    if (this.jobs.length) this.tray.render();
  }

  changed() {
    this.tray.render();
  }

  // ── down, into a folder of this computer's (Chrome, Edge) ──

  static canSaveToFolder() {
    return typeof window !== 'undefined' && 'showDirectoryPicker' in window && window.isSecureContext;
  }

  /**
   * {@code items} ([{path, name, dir}] of Linux's) into a folder the person
   * picks: folders made as they are in Linux, every file streamed straight to
   * the disk. Asked about first when names there are taken.
   */
  async saveToFolder(items, label) {
    let root;
    try {
      root = await window.showDirectoryPicker({ id: this.words.picker, mode: 'readwrite', startIn: 'downloads' });
    } catch {
      return null; // they chose nothing
    }
    const job = this.newJob('down', label, root.name);
    job.tops = items.map((i) => i.name);
    job.topIsDir = items.length === 1 && !!items[0].dir;
    job.state = 'preparing';
    this.jobs.push(job);
    this.tray.show();
    this.changed(job);
    const files = [];
    const dirs = [];
    job.asking = new AbortController();
    try {
      const walk = async (path, rel, depth) => {
        if (job.state === 'cancelled') return;
        const l = await this.api.json('list', { query: { path }, signal: job.asking.signal });
        dirs.push(rel);
        for (const e of l.entries || []) {
          const p = `${path === '/' ? '' : path}/${e.n}`;
          if (e.t === 'd' || e.lt === 'd') {
            if (e.t === 'd' && depth < 64) await walk(p, `${rel}/${e.n}`, depth + 1);
          } else if (e.t === 'f' || e.lt === 'f') {
            files.push({ path: p, rel: `${rel}/${e.n}`, size: e.s || 0, mtime: e.m });
          }
          job.files = files.length;
          if (files.length % 50 === 0) this.changed(job);
        }
      };
      for (const it of items) {
        if (it.dir) await walk(it.path, it.name, 0);
        else files.push({ path: it.path, rel: it.name, size: it.size || 0, mtime: it.mtime });
      }
    } catch (e) {
      if (job.state === 'cancelled') return null;
      return this.fail(job, e);
    }
    if (job.state === 'cancelled') return null;
    const clash = [];
    for (const it of items) {
      try {
        if (it.dir) await root.getDirectoryHandle(it.name);
        else await root.getFileHandle(it.name);
        clash.push(it.name);
      } catch {
        // not there: no question
      }
    }
    if (job.state === 'cancelled') return null;
    let replace = true;
    if (clash.length) {
      const choice = await this.ask({
        title: clash.length === 1 ? `“${clash[0]}” is already in ${root.name}` : `${clash.length} items are already in ${root.name}`,
        text: clash.length === 1
          ? `Replace “${clash[0]}” in ${root.name} with the copy from ${this.words.on}, or skip “${clash[0]}”?`
          : `Replace the ${clash.length} items in ${root.name} with the copies from ${this.words.on}, or skip the ${clash.length} items?`,
        list: clash.slice(0, 6).map((n) => ({ name: n, dir: items.find((i) => i.name === n).dir })),
        more: Math.max(0, clash.length - 6),
        buttons: [{ label: 'Skip', value: 'skip' }, { label: 'Replace', value: 'replace', primary: true }],
      });
      if (job.state === 'cancelled') return null;
      if (!choice) {
        this.dismiss(job);
        return null;
      }
      replace = choice === 'replace';
    }
    const skip = new Set(replace ? [] : clash);
    const keep = (rel) => !skip.has(rel.split('/')[0]);
    job.policy = 'replace';
    job.dirHandle = root;
    job.dirsToMake = dirs.filter(keep);
    job.files = 0;
    for (const f of files.filter((x) => keep(x.rel))) {
      job.tasks.push({ job, kind: 'get', items: [f], dirs: [], bytes: f.size, sent: 0, base: 0, state: 'queued', tries: 0 });
      job.files++;
      job.bytes += f.size;
    }
    job.results.skipped = files.length - job.files;
    if (!job.tasks.length) {
      this.finish(job);
      return job;
    }
    job.state = 'queued';
    this.changed(job);
    this.pumpDown();
    return job;
  }

  pumpDown() {
    // Downloads share the three slots with uploads.
    while (this.running < PARALLEL) {
      const t = this.jobs.flatMap((j) => (j.dir === 'down' && (j.state === 'queued' || j.state === 'running') ? j.tasks : []))
        .find((x) => x.state === 'queued' && !(x.waitUntil > performance.now()));
      if (!t) break;
      void this.runGet(t);
    }
  }

  async dirOf(job, rel) {
    const names = rel.split('/').slice(0, -1);
    let d = job.dirHandle;
    for (const n of names) d = await d.getDirectoryHandle(n, { create: true });
    return d;
  }

  async runGet(t) {
    const job = t.job;
    const f = t.items[0];
    this.running++;
    t.state = 'running';
    if (job.state === 'queued') job.state = 'running';
    this.changed(job);
    let writable = null;
    // The file this download made, while it is not whole: the bytes go to a
    // side file until the end, and a cancel throws that away, so a file made
    // here would stay empty (until 2026-11-22 it did). One already there
    // (Replace) is kept as it was.
    let made = null;
    try {
      t.handle = { abort: new AbortController() };
      t.sent = 0;
      const r = await fetch(this.api.url('get', { path: f.path }), { headers: this.api.auth(), cache: 'no-store', signal: t.handle.abort.signal });
      if (r.status === 401) {
        this.api.onUnauthorized();
        throw new ApiError(401, 'unauthorized');
      }
      if (!r.ok) throw new ApiError(r.status, ((await r.json().catch(() => ({}))) || {}).error);
      const dir = await this.dirOf(job, f.rel);
      const name = f.rel.slice(f.rel.lastIndexOf('/') + 1);
      let had = true;
      try {
        await dir.getFileHandle(name);
      } catch {
        had = false;
      }
      const fh = await dir.getFileHandle(name, { create: true });
      if (!had) made = { dir, name };
      writable = await fh.createWritable();
      const reader = r.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await writable.write(value);
        t.sent += value.length;
      }
      await writable.close();
      writable = null;
      made = null;
      t.state = 'done';
      t.base = t.bytes;
      t.sent = 0;
    } catch (e) {
      if (writable) await writable.abort().catch(() => {});
      if (made) await made.dir.removeEntry(made.name).catch(() => {});
      if (t.state === 'stopped') {
        if (job.state === 'paused') t.state = 'queued';
      } else if ((e.status === 0 || e.name === 'TypeError') && t.tries < TRIES) {
        t.tries++;
        t.state = 'queued';
        t.waitUntil = performance.now() + Math.min(30000, 1000 * 2 ** (t.tries - 1));
        setTimeout(() => this.pumpDown(), Math.min(30000, 1000 * 2 ** (t.tries - 1)) + 20);
      } else {
        t.state = 'failed';
        this.fail(job, e.status !== undefined ? e : new ApiError(0, 'denied'));
      }
    } finally {
      this.running--;
      if (job.state === 'running' && job.tasks.every((x) => x.state === 'done')) {
        for (const d of job.dirsToMake || []) {
          try {
            await this.dirOf(job, `${d}/x`);
          } catch {
            // an empty folder that could not be made: its files are all there
          }
        }
        this.finish(job);
      }
      this.changed(job);
      this.pumpDown();
      this.pump();
    }
  }
}

// ── the panel ───────────────────────────────────────────────────────────

const h = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

const ICONS = {
  pause: 'M7 5h2.2v10H7zM10.8 5H13v10h-2.2z',
  play: 'M7.2 4.8v10.4L15 10z',
  stop: 'M5.5 5.5l9 9M14.5 5.5l-9 9',
  show: 'M3 10s2.7-5 7-5 7 5 7 5-2.7 5-7 5-7-5-7-5zM10 12.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4z',
  retry: 'M15.5 10a5.5 5.5 0 1 1-1.6-3.9M15.5 4v3.5H12',
  chevron: 'M5.5 8l4.5 4.5L14.5 8',
  close: 'M5.5 5.5l9 9M14.5 5.5l-9 9',
  done: 'M5 10.5l3.2 3.2L15 6.8',
};

function iconBtn(name, label) {
  const b = h('button', 'icon tray-btn');
  b.type = 'button';
  b.title = label;
  b.setAttribute('aria-label', label);
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 20 20');
  s.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', ICONS[name]);
  s.appendChild(p);
  b.appendChild(s);
  return b;
}

class Tray {
  constructor(transfers) {
    this.t = transfers;
    this.el = null;
    this.collapsed = false;
    this.rows = new Map();
    this.queued = false;
  }

  build() {
    const el = h('aside', 'tray');
    el.setAttribute('role', 'region');
    el.setAttribute('aria-label', 'Transfers');
    el.hidden = true;
    const head = h('div', 'tray-head');
    const ring = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    ring.setAttribute('viewBox', '0 0 36 36');
    ring.setAttribute('class', 'tray-ring');
    ring.setAttribute('aria-hidden', 'true');
    for (const c of ['track', 'fill']) {
      const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      circle.setAttribute('cx', '18');
      circle.setAttribute('cy', '18');
      circle.setAttribute('r', '15');
      circle.setAttribute('class', c);
      ring.appendChild(circle);
    }
    const tick = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    tick.setAttribute('d', 'M11.5 18.5l4.2 4.2 8.8-9');
    tick.setAttribute('class', 'tick');
    ring.appendChild(tick);
    const words = h('div', 'tray-words');
    this.title = h('b', 'tray-title');
    this.sub = h('span', 'tray-sub');
    this.sub.setAttribute('aria-live', 'off');
    words.append(this.title, this.sub);
    this.canvas = h('canvas', 'tray-spark');
    this.canvas.width = 120;
    this.canvas.height = 34;
    this.canvas.setAttribute('aria-hidden', 'true');
    this.fold = iconBtn('chevron', 'Fold');
    this.fold.classList.add('tray-fold');
    this.fold.addEventListener('click', () => {
      this.collapsed = !this.collapsed;
      this.el.classList.toggle('collapsed', this.collapsed);
      this.fold.setAttribute('aria-expanded', String(!this.collapsed));
    });
    this.closeBtn = iconBtn('close', 'Close');
    this.closeBtn.addEventListener('click', () => {
      this.t.jobs = this.t.jobs.filter((j) => j.state === 'running' || j.state === 'queued' || j.state === 'preparing' || j.state === 'paused');
      if (!this.t.jobs.length) this.hide();
      this.render();
    });
    head.append(ring, words, this.canvas, this.fold, this.closeBtn);
    this.ringFill = ring.querySelector('.fill');
    this.list = h('ol', 'tray-list');
    el.append(head, this.list);
    document.body.appendChild(el);
    this.el = el;
    this.ring = ring;
  }

  show() {
    if (!this.el) this.build();
    this.el.hidden = false;
    this.wanted = true;
    requestAnimationFrame(() => {
      if (this.wanted) this.el.classList.add('on');
    });
  }

  hide() {
    if (!this.el) return;
    this.wanted = false;
    this.el.classList.remove('on');
    setTimeout(() => {
      if (!this.t.jobs.length) this.el.hidden = true;
    }, 300);
  }

  /** Everything finished a while ago: the panel folds to its header. */
  settle() {
    if (!this.el || this.collapsed) return;
    this.collapsed = true;
    this.el.classList.add('collapsed');
  }

  render() {
    if (this.queued) return;
    this.queued = true;
    requestAnimationFrame(() => {
      this.queued = false;
      this.paint();
    });
  }

  paint() {
    if (!this.el) return;
    const jobs = this.t.jobs;
    if (!jobs.length) {
      this.hide();
      return;
    }
    if (this.el.hidden) this.show();
    const live = jobs.filter((j) => j.state === 'running' || j.state === 'queued' || j.state === 'preparing');
    const paused = jobs.filter((j) => j.state === 'paused');
    const failedJobs = jobs.filter((j) => j.state === 'failed');
    const bytes = jobs.filter((j) => j.state !== 'cancelled').reduce((n, j) => n + j.bytes, 0);
    const moved = jobs.filter((j) => j.state !== 'cancelled').reduce((n, j) => n + this.t.moved(j), 0);
    const frac = bytes ? moved / bytes : live.length ? 0 : 1;
    this.ringFill.style.strokeDashoffset = String(94.25 * (1 - Math.min(1, frac)));
    const files = (n) => (n === 1 ? '1 file' : `${n.toLocaleString()} files`);
    const ups = live.filter((j) => j.dir === 'up');
    const downs = live.filter((j) => j.dir === 'down');
    const allDone = !live.length && !paused.length && !failedJobs.length;
    this.el.classList.toggle('done', allDone);
    this.el.classList.toggle('bad', !live.length && failedJobs.length > 0);
    this.closeBtn.hidden = live.length > 0;
    if (live.length) {
      const n = live.reduce((k, j) => k + j.files, 0);
      this.title.textContent = ups.length && downs.length ? `Moving ${files(n)}` : ups.length ? `Sending ${files(n)} to ${this.t.words.on}` : `Saving ${files(n)} to this computer`;
      const left = live.reduce((k, j) => k + Math.max(0, j.bytes - this.t.moved(j)), 0);
      const sp = this.t.speed;
      const parts = [];
      if (sp > 1) parts.push(fmtSpeed(sp));
      if (sp > 1 && left > 0) parts.push(fmtEta(left / sp));
      if (!parts.length) parts.push(live.some((j) => j.state === 'preparing') ? 'Getting ready' : 'Starting');
      this.sub.textContent = parts.join(' · ');
    } else if (paused.length) {
      this.title.textContent = 'Paused';
      this.sub.textContent = `${fmtSize(moved)} of ${fmtSize(bytes)}`;
    } else if (failedJobs.length) {
      this.title.textContent = failedJobs.length === 1 ? 'A transfer stopped' : `${failedJobs.length} transfers stopped`;
      this.sub.textContent = failedJobs[0].error;
    } else {
      const n = jobs.filter((j) => j.state === 'done').reduce((k, j) => k + j.files, 0);
      this.title.textContent = n ? `${files(n)} done` : 'Done';
      this.sub.textContent = fmtSize(bytes);
    }
    this.drawSpark();
    // Rows, kept and updated rather than rebuilt: a button under the pointer stays under it.
    const seen = new Set();
    for (const j of jobs) {
      seen.add(j.id);
      let row = this.rows.get(j.id);
      if (!row) {
        row = this.row(j);
        this.rows.set(j.id, row);
        this.list.prepend(row.li);
      }
      this.fill(row, j);
    }
    for (const [id, row] of this.rows) {
      if (!seen.has(id)) {
        row.li.remove();
        this.rows.delete(id);
      }
    }
  }

  row(j) {
    const li = h('li', 'job');
    const icon = h('span', 'job-icon');
    const one = j.tops && j.tops.length === 1 ? j.tops[0] : null;
    icon.appendChild(one && !j.topIsDir ? fileIcon(one, kindOf(one), 32) : folderIcon(null, 32));
    const main = h('div', 'job-main');
    const name = h('div', 'job-name');
    const where = h('div', 'job-where');
    const bar = h('div', 'job-bar');
    const fillEl = h('i');
    bar.appendChild(fillEl);
    const status = h('div', 'job-status');
    main.append(name, where, bar, status);
    const acts = h('div', 'job-acts');
    const pause = iconBtn('pause', 'Pause');
    const play = iconBtn('play', 'Resume');
    const retry = iconBtn('retry', 'Try again');
    const stop = iconBtn('stop', 'Cancel');
    const show = iconBtn('show', 'Show in Files');
    pause.addEventListener('click', () => this.t.pause(j));
    play.addEventListener('click', () => (j.dir === 'down' ? (this.t.resume(j), this.t.pumpDown()) : this.t.resume(j)));
    retry.addEventListener('click', () => (j.dir === 'down' ? (this.t.resume(j), this.t.pumpDown()) : this.t.resume(j)));
    stop.addEventListener('click', () => this.t.cancel(j));
    show.addEventListener('click', () => this.t.onShow && this.t.onShow(j));
    acts.append(pause, play, retry, stop, show);
    li.append(icon, main, acts);
    return { li, name, where, fill: fillEl, status, pause, play, retry, stop, show };
  }

  fill(row, j) {
    const moved = this.t.moved(j);
    const frac = j.bytes ? moved / j.bytes : j.state === 'done' ? 1 : 0;
    row.li.dataset.state = j.state;
    row.name.textContent = j.label || (j.files === 1 ? '1 file' : `${j.files.toLocaleString()} files`);
    row.where.textContent = j.dir === 'up' ? `to ${this.t.nameOf(j.dest)} on ${this.t.words.on}` : `to ${j.dest} on this computer`;
    row.fill.style.transform = `scaleX(${Math.min(1, frac)})`;
    let s;
    switch (j.state) {
      case 'preparing':
        s = j.files ? `Reading… ${j.files === 1 ? '1 file' : `${j.files.toLocaleString()} files`}` : 'Getting ready';
        break;
      case 'queued':
        s = 'Waiting';
        break;
      case 'running':
        s = `${fmtSize(moved)} of ${fmtSize(j.bytes)}${j.speed > 1 ? ` · ${fmtSpeed(j.speed)}` : ''}`;
        break;
      case 'paused':
        s = j.error || `Paused · ${fmtSize(moved)} of ${fmtSize(j.bytes)}`;
        break;
      case 'failed':
        s = j.error;
        break;
      case 'cancelled':
        s = 'Cancelled';
        break;
      default: {
        const secs = Math.max(0.1, (j.finishedAt - j.startedAt) / 1000);
        const extra = [];
        if (j.results.same) extra.push(`${j.results.same} already there`);
        if (j.results.skipped) extra.push(`${j.results.skipped} left out`);
        if (j.results.failed.length) extra.push(`${j.results.failed.length} could not be sent`);
        // A speed only for what went: a big file already there was not sent.
        const sent = j.bytes - (j.results.unsent || 0);
        s = sent > 0
          ? `${fmtSize(j.bytes)} in ${secs < 10 ? secs.toFixed(1) : Math.round(secs)} s${sent > 2e6 ? ` · ${fmtSpeed(sent / secs)}` : ''}${extra.length ? ` · ${extra.join(', ')}` : ''}`
          : `${fmtSize(j.bytes)}${extra.length ? ` · ${extra.join(', ')}` : ''}`;
      }
    }
    row.status.textContent = s;
    const going = j.state === 'running' || j.state === 'queued';
    row.pause.hidden = !going;
    row.play.hidden = j.state !== 'paused';
    row.retry.hidden = j.state !== 'failed';
    row.stop.hidden = !(going || j.state === 'paused' || j.state === 'preparing');
    row.show.hidden = !(j.state === 'done' && j.dir === 'up');
  }

  drawSpark() {
    const c = this.canvas;
    const ctx = c.getContext('2d');
    const w = c.width;
    const hgt = c.height;
    ctx.clearRect(0, 0, w, hgt);
    const pts = this.t.spark;
    c.classList.toggle('idle', pts.length < 3 || !this.t.jobs.some((j) => j.state === 'running'));
    if (pts.length < 2) return;
    const max = Math.max(...pts, 1);
    const step = w / 89;
    const x0 = w - (pts.length - 1) * step;
    const y = (v) => hgt - 3 - (v / max) * (hgt - 8);
    const grad = ctx.createLinearGradient(0, 0, 0, hgt);
    grad.addColorStop(0, 'rgba(255, 193, 49, 0.35)');
    grad.addColorStop(1, 'rgba(255, 193, 49, 0)');
    ctx.beginPath();
    ctx.moveTo(x0, hgt);
    pts.forEach((v, i) => ctx.lineTo(x0 + i * step, y(v)));
    ctx.lineTo(w, hgt);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();
    ctx.beginPath();
    pts.forEach((v, i) => (i ? ctx.lineTo(x0 + i * step, y(v)) : ctx.moveTo(x0, y(v))));
    ctx.strokeStyle = '#ffc131';
    ctx.lineWidth = 1.6;
    ctx.lineJoin = 'round';
    ctx.stroke();
  }
}
