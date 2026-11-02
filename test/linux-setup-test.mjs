#!/usr/bin/env node
// linux-setup.sh's helpers (openandroiddex-linux/README.md, "Skip, Restart and
// a line that moves"), checked on a phone under its own /system/bin/sh — mksh,
// the shell LinuxService runs the script with, whose arithmetic is 32-bit —
// rather than under a computer's:
//
//   node test/linux-setup-test.mjs [serial]
//
// Pushes the script and test/linux-setup/checks.sh to a directory of its own
// in /data/local/tmp and runs the checks there as the shell user. They cut the
// functions out of the real script by name; guest commands go to stand-ins, so
// no rootfs is needed and nothing of the app is touched. Removes what it pushed.
// About fifteen seconds, most of it the ticker's five.
//
// Uses the app's own adb (resources/bin) when there is one; ADB=… overrides it.

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const exe = process.platform === "win32" ? "adb.exe" : "adb";
const bundled = path.join(here, "..", "open-android-dex-tauri", "src-tauri", "resources", "bin", exe);
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), "Library", "Android", "sdk");
const ADB = process.env.ADB || (fs.existsSync(bundled) ? bundled : path.join(sdk, "platform-tools", exe));
const DIR = "/data/local/tmp/oadx-setup-test";
const serialArg = process.argv[2];

const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" });

const model = (adb("shell", "getprop", "ro.product.model").stdout || "").trim();
if (!model) {
  console.log(`FAIL a phone answers on adb (${ADB})`);
  process.exit(1);
}
console.log(`phone: ${model}, ${(adb("shell", "echo $KSH_VERSION").stdout || "").trim() || "/system/bin/sh"}`);

// LF on the way out whatever the checkout did to them: the phone's shell reads
// a carriage return as part of every line.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-setup-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));
for (const [from, name] of [
  [path.join(here, "..", "openandroiddex-linux", "linux-setup.sh"), "linux-setup.sh"],
  [path.join(here, "linux-setup", "checks.sh"), "checks.sh"],
]) {
  fs.writeFileSync(path.join(tmp, name), fs.readFileSync(from, "utf8").replace(/\r\n/g, "\n"));
}

adb("shell", `rm -rf ${DIR} && mkdir -p ${DIR}`);
adb("push", path.join(tmp, "linux-setup.sh"), `${DIR}/linux-setup.sh`);
adb("push", path.join(tmp, "checks.sh"), `${DIR}/checks.sh`);
// Into a file and read back afterwards, rather than streamed: the checks start
// background processes of their own, and a stream through adb was seen to end
// before the checks did.
adb("shell", `/system/bin/sh ${DIR}/checks.sh ${DIR} > ${DIR}/out.txt 2>&1`);
const out = adb("shell", `cat ${DIR}/out.txt`).stdout || "";
adb("shell", `rm -rf ${DIR}`);

let passed = 0;
let failed = 0;
let finished = false;
for (const line of out.split(/\r?\n/)) {
  if (line.startsWith("PASS ")) passed++;
  else if (line.startsWith("FAIL ")) failed++;
  else if (line.startsWith("checks: ")) finished = true;
  if (line) console.log(line);
}
if (!finished) {
  failed++;
  console.log("FAIL the checks ran to their end");
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
