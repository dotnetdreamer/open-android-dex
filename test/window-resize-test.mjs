#!/usr/bin/env node
// A desktop window's resize edges (issue #5, doc/window-resize.md) as far as a computer
// can take them: the launcher's ResizeGeometry — where the zone is, what a point on it
// grabs, what of it can be reached past the windows in front and the taskbar, where a
// drag may take an edge — and the window daemon's Shapes, the hand-over of the pointer's
// shape to the computer (SHAPE / SHAPEWAIT). Both are plain Java, compiled for this JVM
// as they are.
//
//   node test/window-resize-test.mjs
//
// The rest — the strips as windows, the drag through the daemon, the computer's cursor —
// needs a phone or the emulator; the design record says what ran where.
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
const WMD = path.join(ROOT, "openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd");

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-resize-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    tmp,
    path.join(LAUNCHER, "ResizeGeometry.java"),
    path.join(WMD, "Shapes.java"),
    path.join(HERE, "window-resize/ResizeGeometryCheck.java"),
    path.join(HERE, "window-resize/ShapesCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stdout + javac.stderr);
  console.log("  FAIL ResizeGeometry and Shapes compile on a JVM");
  process.exit(1);
}

let passed = 0;
let failed = 0;

function run(title, main) {
  // UTF-8 out, read as UTF-8: a JVM writing to a pipe on Windows uses the code page.
  const r = spawnSync("java", ["-Dstdout.encoding=UTF-8", "-cp", tmp, main], {
    encoding: "utf8",
  });
  console.log(title);
  // CRLF from a JVM on Windows: split on either, or nothing matches - 0 checks, a pass.
  for (const line of r.stdout.split(/\r?\n/)) {
    const m = /^(ok|FAIL) (.*)$/.exec(line);
    if (!m) {
      if (line.startsWith("  ")) console.log(line);
      continue;
    }
    if (m[1] === "ok") passed++;
    else failed++;
    console.log(`${m[1] === "ok" ? "  ok  " : "  FAIL"} ${m[2]}`);
  }
  if (r.status !== 0 && !r.stdout.includes("done ")) {
    console.log(r.stderr);
    failed++;
  }
}

run(
  "the launcher's resize edges (ResizeGeometry)",
  "com.ccrstech.openandroiddex.launcher.ResizeGeometryCheck",
);
run(
  "the window daemon's pointer shapes (Shapes)",
  "com.ccrstech.openandroiddex.wmd.ShapesCheck",
);

console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
