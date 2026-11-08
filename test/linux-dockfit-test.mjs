#!/usr/bin/env node
// The dock keeping up with the screen (openandroiddex-linux/README.md, "And it
// follows the screen"), checked as far as a computer can without a guest:
// dockfit.py exactly as linux-rt.sh writes it — cut out of the script's own
// heredoc — run against canned xfconf-query output by
// test/linux-dockfit/dockfit_check.py.
//
//   node test/linux-dockfit-test.mjs
//
// What it covers: the panels read out of `xfconf-query -l -v` (arrays and other
// channels left out), the top bar found by dock.py's rule; a fresh guest left
// as dock.py made it and recorded; the phone turned upright and back, each a
// length set through xfconfd and recorded; a length someone chose, a dock they
// moved, a deskbar, a guest with no record and no dock.py marker (or an older
// layout's), and no top bar all left alone; a refused set not recorded, a
// failed listing, a screen no taller than the bar; and, on a 64-bit Linux, the
// X event laid out as Xlib lays it out. And linux-rt.sh itself: the heredoc
// closed and ahead of the session string, and no apostrophe inside that string
// — single-quoted shell, which a lone one would end early, silently. Waiting
// on a real X server and a real xfconfd needs a guest; the design record says
// what was run on one.
//
// Needs Python 3 (PYTHON, or python3 / python on PATH).

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const RT = path.join(ROOT, "openandroiddex-linux/linux-rt.sh");

let passed = 0;
let failed = 0;
function check(name, ok, detail) {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${ok || detail === undefined ? "" : " — " + detail}`);
}

// ── the script ──

console.log("linux-rt.sh");
const src = fs.readFileSync(RT, "utf8");
const session = src.indexOf("SESSION='");
const sessionEnd = src.indexOf("exit $RC'", session);
const open = src.indexOf("<<'PYEOF'\n");
const close = src.indexOf("\nPYEOF\n", open);
check("LF only", !src.includes("\r"));
check(
  "dockfit.py's heredoc is closed, and ahead of the session string",
  open > 0 && close > open && session > close,
  `heredoc ${open}..${close}, session at ${session}`,
);
const inside = session > 0 && sessionEnd > session ? src.slice(session + "SESSION='".length, sessionEnd) : null;
check(
  "no apostrophe inside the session string",
  inside !== null && !inside.includes("'"),
  inside === null ? "session string not found" : `${inside.split("'").length - 1} found`,
);
check("the session starts dockfit.py and kills it with the rest", inside !== null
  && /python3 \/usr\/local\/share\/openandroiddex\/dockfit\.py/.test(inside)
  && /kill -9 \$WS \$DBUS \$XVNC \$PULSE \$FIT/.test(inside));
if (open < 0 || close < 0) {
  console.log(`\n${passed} passed, ${failed + 1} failed`);
  process.exit(1);
}

// ── dockfit.py ──

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-dockfit-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));
const file = path.join(tmp, "dockfit.py");
fs.writeFileSync(file, src.slice(src.indexOf("\n", open) + 1, close + 1));

const env = { ...process.env };
delete env.DISPLAY;   // a Linux with libX11 would otherwise sit waiting on it
let run = null;
for (const py of [process.env.PYTHON, "python3", "python"].filter(Boolean)) {
  const r = spawnSync(py, [path.join(HERE, "linux-dockfit/dockfit_check.py"), file], { encoding: "utf8", env });
  if (r.error || /Microsoft Store/.test(r.stdout + r.stderr)) continue;
  run = r;
  break;
}
if (!run) {
  console.log("  FAIL no Python 3 here (set PYTHON)");
  process.exit(1);
}
// CRLF from a Python on Windows: split on either.
for (const line of run.stdout.split(/\r?\n/)) {
  const m = /^ {2}(ok|FAIL) +(.*)$/.exec(line);
  if (m) {
    if (m[1] === "ok") passed++;
    else failed++;
    console.log(`${m[1] === "ok" ? "  ok  " : "  FAIL"} ${m[2]}`);
  } else if (line && !/^\d+ passed/.test(line)) {
    console.log(line);
  }
}
if (run.status !== 0 && !failed) {
  console.log(run.stderr);
  failed++;
}
console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
