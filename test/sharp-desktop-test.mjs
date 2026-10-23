#!/usr/bin/env node
// The window daemon's caller check and the scrcpy server's display scale
// (doc/sharp-desktop.md), as far as a computer can take them: Peers.java and
// the patch's DisplayScale.java compiled for this JVM as they are, and run by
// test/sharp-desktop/PeersCheck.java (against /proc/net lines read off a
// phone) and DisplayScaleCheck.java.
//
//   node test/sharp-desktop-test.mjs
//
// On a phone, the same check is the daemon refusing a stranger and answering
// the launcher and adb — the design record says how that was run.
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

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-peers-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

// The patched scrcpy server's new file, exactly as the patch adds it: its
// lines are lifted out of display-scale.patch rather than kept twice. A
// Windows checkout (core.autocrlf) has the patch in CRLF, read here as LF.
const patch = fs
  .readFileSync(path.join(ROOT, "openandroiddex-scrcpy/display-scale.patch"), "utf8")
  .replace(/\r\n/g, "\n");
const added = /\+\+\+ b\/server\/src\/main\/java\/com\/genymobile\/scrcpy\/video\/DisplayScale\.java\n@@[^\n]*\n((?:\+[^\n]*\n)+)/.exec(patch);
if (!added) {
  console.log("  FAIL display-scale.patch adds DisplayScale.java");
  process.exit(1);
}
const scaleSrc = path.join(tmp, "DisplayScale.java");
fs.writeFileSync(scaleSrc, added[1].split("\n").map((l) => l.slice(1)).join("\n"));

const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    tmp,
    path.join(WMD, "Peers.java"),
    path.join(HERE, "sharp-desktop/PeersCheck.java"),
    scaleSrc,
    path.join(HERE, "sharp-desktop/DisplayScaleCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL the daemon's Peers and the server's DisplayScale compile on a JVM");
  process.exit(1);
}

let passed = 0;
let failed = 0;
for (const [title, main] of [
  ["the window daemon's callers, by the kernel's account", "com.ccrstech.openandroiddex.wmd.PeersCheck"],
  ["the scrcpy server's scale for a display that follows its window", "com.genymobile.scrcpy.video.DisplayScaleCheck"],
]) {
  // UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
  const run = spawnSync("java", ["-Dstdout.encoding=UTF-8", "-cp", tmp, main], { encoding: "utf8" });
  console.log(title);
  // CRLF from a JVM on Windows: a split on "\n" alone left every line ending
  // in "\r", which the pattern below never matched — 0 checks, and a pass.
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
}
console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
