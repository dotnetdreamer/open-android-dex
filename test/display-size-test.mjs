#!/usr/bin/env node
// The desktop's Display size (doc/sharp-desktop.md, "Display size"), checked
// as far as a computer can without a phone: the launcher's own DisplaySize,
// compiled for this JVM as it is, and run by test/display-size/DisplaySizeCheck.java.
//
//   node test/display-size-test.mjs
//
// What it covers: what a size is kept as and that every dpi comes back as
// itself, how far the slider goes on a display, where a density sits on it,
// what − and + land on, the window a new app gets — the same rect the
// launcher's nextWindowBounds gave before that arithmetic moved — and which
// layout apps choose in it. The card itself, the preview and `wm density` need
// a phone; the design record says what was run on one.
//
// Needs a JDK.

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const LAUNCHER = path.join(ROOT, "openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher");

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-dsize-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

const classes = path.join(tmp, "classes");
fs.mkdirSync(classes);
const javac = spawnSync(
  "javac",
  ["-nowarn", "-d", classes, path.join(LAUNCHER, "DisplaySize.java"), path.join(HERE, "display-size/DisplaySizeCheck.java")],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL the launcher's DisplaySize compiles on a JVM");
  process.exit(1);
}

// UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
const run = spawnSync(
  "java",
  ["-Dstdout.encoding=UTF-8", "-cp", classes, "com.ccrstech.openandroiddex.launcher.DisplaySizeCheck"],
  { encoding: "utf8" },
);
let passed = 0;
let failed = 0;
let section = "";
const sections = [
  ["kept:", "what a size is kept as"],
  ["round trip:", "a dpi comes back as itself"],
  ["bounds:", "how far the control goes"],
  ["track:", "the slider"],
  ["steps:", "− and +"],
  ["windows:", "the window a new app gets"],
  ["layouts:", "the layout apps choose in it"],
];
// CRLF from a JVM on Windows: split on either, or nothing matches and 0 checks pass.
for (const line of run.stdout.split(/\r?\n/)) {
  if (!line) continue;
  const m = /^(ok|FAIL) (.*)$/.exec(line);
  if (!m) continue;
  const head = sections.find(([prefix]) => m[2].startsWith(prefix));
  if (head && head[1] !== section) {
    section = head[1];
    console.log(section);
  }
  if (m[1] === "ok") passed++;
  else failed++;
  console.log(`${m[1] === "ok" ? "  ok  " : "  FAIL"} ${m[2]}`);
}
if (run.status !== 0 && !run.stdout.includes("done ")) {
  console.log(run.stderr);
  failed++;
}
console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
