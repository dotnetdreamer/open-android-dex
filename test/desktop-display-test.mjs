#!/usr/bin/env node
// The window daemon's answer to DESKTOP - which display the desktop is on - as far
// as a computer can take it: DesktopDisplay.java compiled for this JVM as it is, and
// run by test/desktop-display/DesktopDisplayCheck.java over task trees read off a
// phone (issue #11).
//
//   node test/desktop-display-test.mjs
//
// On a phone, the same check is the daemon's own answer over the PC's forward of
// 7191 while the launcher is force-stopped - the design record says how that was run.
//
// Needs a JDK.

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const WMD = path.join(ROOT, "openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd");

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-desktop-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    tmp,
    path.join(WMD, "DesktopDisplay.java"),
    path.join(HERE, "desktop-display/DesktopDisplayCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL the daemon's DesktopDisplay compiles on a JVM");
  process.exit(1);
}

let passed = 0;
let failed = 0;
// UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
const run = spawnSync(
  "java",
  ["-Dstdout.encoding=UTF-8", "-cp", tmp, "com.ccrstech.openandroiddex.wmd.DesktopDisplayCheck"],
  { encoding: "utf8" },
);
console.log("the window daemon's desktop display, by the task tree");
// CRLF from a JVM on Windows: split on either, or every line ends in "\r" and
// nothing matches - 0 checks, and a pass.
for (const line of run.stdout.split(/\r?\n/)) {
  const m = /^(ok|FAIL) (.*)$/.exec(line);
  if (!m) continue;
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
