#!/usr/bin/env node
// A desktop window's title bar, the part a computer can check: the launcher's CaptionMath
// — where More's Phone size and Tablet size put the window, which of the two it is now,
// where a bar's menu opens, and when a press on a menu's button is the one that just closed
// it (doc/custom-titlebar-v2.md, "Back, More and Sound"). Plain Java, compiled for this
// JVM as it is.
//
//   node test/caption-bar-test.mjs
//
// The rest — the buttons on a bar, Back reaching the window it was pressed on (the
// daemon's TASKKEY), the menus as windows — needs a phone on Android 14 or newer.
//
// Needs a JDK.

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const LAUNCHER = path.join(
  ROOT,
  "openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher",
);

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-caption-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    tmp,
    path.join(LAUNCHER, "CaptionMath.java"),
    path.join(HERE, "caption-bar/CaptionMathCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL CaptionMath compiles on a JVM");
  process.exit(1);
}

let passed = 0;
let failed = 0;

// UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
const r = spawnSync(
  "java",
  ["-Dstdout.encoding=UTF-8", "-cp", tmp, "com.ccrstech.openandroiddex.launcher.CaptionMathCheck"],
  { encoding: "utf8" },
);
console.log("the launcher's title bar arithmetic (CaptionMath)");
// CRLF from a JVM on Windows: split on either, or nothing matches - 0 checks, a pass.
for (const line of r.stdout.split(/\r?\n/)) {
  const m = /^(ok|FAIL) (.*)$/.exec(line);
  if (!m) continue;
  if (m[1] === "ok") passed++;
  else failed++;
  console.log(`${m[1] === "ok" ? "  ok  " : "  FAIL"} ${m[2]}`);
}
if (r.status !== 0 && !r.stdout.includes("done ")) {
  console.log(r.stderr);
  failed++;
}

console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
