#!/usr/bin/env node
// The window daemon's caller check (doc/sharp-desktop.md), as far as a
// computer can take it: Peers.java compiled for this JVM as it is, and run
// against /proc/net lines read off a phone by test/sharp-desktop/PeersCheck.java.
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

const javac = spawnSync(
  "javac",
  ["-nowarn", "-d", tmp, path.join(WMD, "Peers.java"), path.join(HERE, "sharp-desktop/PeersCheck.java")],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL the daemon's Peers compiles on a JVM");
  process.exit(1);
}

const run = spawnSync("java", ["-cp", tmp, "com.ccrstech.openandroiddex.wmd.PeersCheck"], { encoding: "utf8" });
let passed = 0;
let failed = 0;
console.log("the window daemon's callers, by the kernel's account");
for (const line of run.stdout.split("\n")) {
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
process.exit(failed ? 1 : 0);
