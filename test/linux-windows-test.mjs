#!/usr/bin/env node
// Linux apps as windows of their own (doc/linux-windows.md), checked as far as
// a computer can without a phone: the launcher's own X11, X11Keys, LinuxWm,
// LinuxApps and LinuxAudioLag, compiled for this JVM as they are, and run by
// test/linux-windows/UnitCheck.java.
//
//   node test/linux-windows-test.mjs
//
// The window manager against the guest's real Xvnc is not in here: it needs
// a guest, and the phone's lives in the app's private storage. The design
// record says what was run against a real one, and how.
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

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-linuxwin-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

const classes = path.join(tmp, "classes");
fs.mkdirSync(classes);
const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    classes,
    ...["X11.java", "X11Keys.java", "LinuxWm.java", "LinuxApps.java", "LinuxAudioLag.java"].map((f) =>
      path.join(LAUNCHER, f),
    ),
    path.join(HERE, "linux-windows/UnitCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL the launcher's window manager classes compile on a JVM");
  process.exit(1);
}

// UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
const run = spawnSync("java", ["-Dstdout.encoding=UTF-8", "-cp", classes, "com.ccrstech.openandroiddex.launcher.UnitCheck"], {
  encoding: "utf8",
});
let passed = 0;
let failed = 0;
let section = "";
const sections = [
  ["a letter", "X11Keys — keysyms to keycodes"],
  ["Xauthority", "the session's cookie"],
  ["strings by", "xfconf → XSETTINGS"],
  ["XSETTINGS:", "the XSETTINGS wire format"],
  ["rectangles", "damage, coalesced"],
  ["the apps the menu", "LinuxApps — the guest's .desktop files"],
  ["a 300 ms burst", "LinuxAudioLag — the sound tap's drift cap"],
];
// CRLF from a JVM on Windows: a split on "\n" alone left every line ending in
// "\r", which the pattern below never matched — 0 checks, and a pass.
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
