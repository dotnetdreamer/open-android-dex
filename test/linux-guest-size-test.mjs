#!/usr/bin/env node
// The Linux desktop's size for its window (doc/linux-viewer.md, "The desktop
// follows the window"), checked as far as a computer can without a phone: the
// viewer's rule (assets/linux/novnc/dex-size.js) and the launcher's copy
// (LinuxGuestSize.java, compiled for this JVM as it is), over a sweep of
// windows.
//
//   node test/linux-guest-size-test.mjs
//
// What it covers: the two copies agree to the pixel, for a box and for a whole
// window with the bar docked or folded and the keys row pinned or not; the bar
// and keys row heights the launcher assumes are dex.css's, and LinuxService's
// floor and ceiling move no size a window asks for; the phone's desktop is the
// box's shape (under half a CSS pixel of letterbox), even, with its short side
// 720 to 1200 and its long side at most 2400, the same desktop whichever way
// round, at one scale in both orientations where no limit applies; the desktop
// display's is whole device pixels per guest pixel and fits its window at
// that. A phone turning, XFCE laying itself out again and the dock keeping up
// need a phone; the design record says what was run on one.
//
// Needs a JDK (JAVA_HOME, or javac and java on PATH).

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const LAUNCHER = path.join(ROOT, "openandroiddex-launcher/app/src/main");
const JAVA_SRC = path.join(LAUNCHER, "java/com/ccrstech/openandroiddex/launcher");
const NOVNC = path.join(LAUNCHER, "assets/linux/novnc");

const bin = (tool) => (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", tool) : tool);

let passed = 0;
let failed = 0;
function check(name, ok, detail) {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${ok || detail === undefined ? "" : " — " + detail}`);
}
function section(name) {
  console.log(name);
}

// ── the viewer's copy ──

const { guestSize, sharpScale } = await import(pathToFileURL(path.join(NOVNC, "dex-size.js")).href);

// ── the launcher's copy, on this JVM ──

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-gsize-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));
const javac = spawnSync(
  bin("javac"),
  ["-nowarn", "-d", tmp, path.join(JAVA_SRC, "LinuxGuestSize.java"), path.join(HERE, "linux-guest-size/GuestSizeOracle.java")],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log((javac.stdout || "") + (javac.stderr || "") + (javac.error ? String(javac.error) : ""));
  console.log("  FAIL the launcher's LinuxGuestSize compiles on a JVM");
  process.exit(1);
}

/** Every question in one JVM run: [["box", "phone", w, h, dpr], ...] -> [[w, h], ...]. */
function askJava(questions) {
  const run = spawnSync(bin("java"), ["-cp", tmp, "com.ccrstech.openandroiddex.launcher.GuestSizeOracle"], {
    input: questions.map((q) => q.join(" ")).join("\n") + "\n",
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (run.status !== 0) throw new Error("the oracle failed: " + run.stderr);
  // CRLF from a JVM on Windows: split on either.
  return run.stdout.split(/\r?\n/).filter(Boolean).map((l) => l.split(" ").map(Number));
}

// Densities as Android has them: densityDpi / 160 in a float, which is what
// both the WebView's devicePixelRatio and DisplayMetrics.density carry.
const density = (dpi) => Math.fround(dpi / 160);
const PHONE_DPI = [320, 400, 420, 440, 450, 480, 560, 640];
const DESKTOP_DPI = [120, 160, 200, 213, 240, 280, 320, 360, 480];

const range = (from, to, step) => {
  const out = [];
  for (let v = from; v <= to; v += step) out.push(v);
  return out;
};

// ── the bar, as dex.css draws it ──

section("the bar");
const css = fs.readFileSync(path.join(NOVNC, "dex.css"), "utf8");
const cssBar = {
  wide: Number((/:root\s*\{[^}]*?--bar:\s*(\d+)px/.exec(css) || [])[1]),
  narrow: Number((/@media \(max-width: 560px\)\s*\{\s*:root\s*\{\s*--bar:\s*(\d+)px/.exec(css) || [])[1]),
  desktop: Number((/body\.is-desktop\s*\{[^}]*?--bar:\s*(\d+)px/.exec(css) || [])[1]),
  keyrow: Number((/:root\s*\{[^}]*?--keyrow:\s*(\d+)px/.exec(css) || [])[1]),
  keyrowShort: Number((/@media \(max-height: 420px\)\s*\{\s*:root\s*\{[^}]*?--keyrow:\s*(\d+)px/.exec(css) || [])[1]),
};
const [javaNarrow, javaWide, javaDesktop, javaRow, javaRowShort] = askJava([["bars"]])[0];
check(
  "dex.css still has the bar and keys row heights this reads",
  Object.values(cssBar).every((v) => v > 0),
  JSON.stringify(cssBar),
);
check(
  "the launcher's bar heights are dex.css's (phone up to 560 wide, wider, desktop display)",
  javaNarrow === cssBar.narrow && javaWide === cssBar.wide && javaDesktop === cssBar.desktop,
  `launcher ${javaNarrow}/${javaWide}/${javaDesktop}, dex.css ${cssBar.narrow}/${cssBar.wide}/${cssBar.desktop}`,
);
check(
  "and its keys row is dex.css's (a window up to 420 tall, a taller one)",
  javaRowShort === cssBar.keyrowShort && javaRow === cssBar.keyrow,
  `launcher ${javaRowShort}/${javaRow}, dex.css ${cssBar.keyrowShort}/${cssBar.keyrow}`,
);

// ── the two copies agree ──

section("the two copies");
const phoneBoxes = [];
for (const w of range(240, 1600, 8)) for (const h of range(120, 1600, 8)) phoneBoxes.push([w, h]);
// Fractional CSS px too, as a WebView measures them (1080 px / 2.625 is 411.43).
for (const w of range(300.25, 1000, 13.37)) for (const h of range(150.5, 1000, 11.11)) phoneBoxes.push([w, h]);
const desktopBoxes = [];
for (const dpi of DESKTOP_DPI) {
  for (const w of range(320, 2600, 16)) for (const h of range(240, 1500, 16)) desktopBoxes.push([w, h, density(dpi)]);
}

function agree(label, questions, mine) {
  const theirs = askJava(questions);
  const off = [];
  questions.forEach((q, i) => {
    const a = mine[i];
    const b = theirs[i] || [];
    if (a.w !== b[0] || a.h !== b[1]) off.push(`${q.slice(1).join(" ")}: viewer ${a.w}x${a.h}, launcher ${b[0]}x${b[1]}`);
  });
  check(`${label} (${questions.length.toLocaleString("en")})`, off.length === 0, off.slice(0, 3).join("; "));
}

agree(
  "the phone, for a box",
  phoneBoxes.map(([w, h]) => ["box", "phone", w, h, 2.625]),
  phoneBoxes.map(([w, h]) => guestSize("phone", w, h, 2.625)),
);
agree(
  "the desktop display, for a box",
  desktopBoxes.map(([w, h, d]) => ["box", "desktop", w, h, d]),
  desktopBoxes.map(([w, h, d]) => guestSize("desktop", w, h, d)),
);

// A whole window: the viewer's box is the window less its docked bar and a
// pinned keys row, which the page measures and the launcher works out from
// what the page last had.
const viewerBox = (phone, wpx, hpx, dpr, docked, pinned) => {
  const w = wpx / dpr;
  const h = hpx / dpr;
  const bar = !docked ? 0 : !phone ? cssBar.desktop : w <= 560 ? cssBar.narrow : cssBar.wide;
  const row = !pinned ? 0 : h <= 420 ? cssBar.keyrowShort : cssBar.keyrow;
  return [w, h - bar - row];
};
const CHROME = [[1, 0], [0, 0], [1, 1], [0, 1]];   // [docked, pinned]: as it opens first, then the rest
const phoneWindows = [];
for (const dpi of PHONE_DPI) {
  for (const a of range(720, 1440, 24)) {
    for (const b of range(1200, 3200, 40)) {
      phoneWindows.push([a, b, density(dpi)]);   // portrait
      phoneWindows.push([b, a, density(dpi)]);   // on its side
    }
  }
}
const desktopWindows = [];
for (const dpi of DESKTOP_DPI) {
  for (const w of range(800, 3840, 64)) for (const h of range(600, 2400, 48)) desktopWindows.push([w, h, density(dpi)]);
}
for (const [docked, pinned] of CHROME) {
  const how = `${docked ? "bar docked" : "bar folded"}${pinned ? ", keys row pinned" : ""}`;
  agree(
    `the phone, for a whole window, ${how}`,
    phoneWindows.map(([w, h, d]) => ["window", "phone", w, h, d, docked, pinned]),
    phoneWindows.map(([w, h, d]) => guestSize("phone", ...viewerBox(true, w, h, d, docked, pinned), d)),
  );
  agree(
    `the desktop display, for a whole window, ${how}`,
    desktopWindows.map(([w, h, d]) => ["window", "desktop", w, h, d, docked, pinned]),
    desktopWindows.map(([w, h, d]) => guestSize("desktop", ...viewerBox(false, w, h, d, docked, pinned), d)),
  );
}

// What the launcher starts a phone's desktop at is what the service writes:
// its floor and ceiling (LinuxService.startRuntime) must never move a size
// either rule makes for a window a phone or a desktop display has.
const svc = fs.readFileSync(path.join(JAVA_SRC, "LinuxService.java"), "utf8");
const clamp = /Math\.min\(Math\.max\(w, (\d+)\), (\d+)\) \+ "x" \+ Math\.min\(Math\.max\(h, (\d+)\), (\d+)\)/.exec(svc);
check("LinuxService still clamps the geometry the way this reads", !!clamp);
if (clamp) {
  const [minW, maxW, minH, maxH] = clamp.slice(1).map(Number);
  const moved = [];
  for (const [w, h, d] of phoneWindows) {
    for (const [docked, pinned] of CHROME) {
      const g = guestSize("phone", ...viewerBox(true, w, h, d, docked, pinned), d);
      if (g.w < minW || g.w > maxW || g.h < minH || g.h > maxH) moved.push(`${w}x${h}@${d} -> ${g.w}x${g.h}`);
    }
  }
  for (const [w, h, d] of desktopWindows) {
    const g = guestSize("desktop", ...viewerBox(false, w, h, d, 1, 0), d);
    if (g.w < minW || g.w > maxW || g.h < minH || g.h > maxH) moved.push(`${w}x${h}@${d} -> ${g.w}x${g.h}`);
  }
  check(
    `the service's ${minW}x${minH} to ${maxW}x${maxH} moves no size a window asks for`,
    moved.length === 0,
    moved.slice(0, 3).join("; "),
  );
}

// ── the phone ──

section("the phone");
let worstGap = 0;      // in guest px
let worstAt = "";
let worstPhoneGap = 0; // in CSS px, on windows a phone has
let odd = 0;
let outOfRange = [];
let notSymmetric = [];
let offScale = [];
for (const [w, h] of phoneBoxes) {
  const g = guestSize("phone", w, h, 2.625);
  const s = Math.min(w / g.w, h / g.h);
  const gap = Math.max(w - g.w * s, h - g.h * s) / s;
  // At the long side's limit the short side is the rounded one, and a box
  // that thin magnifies its pixel: a strip, not a window anyone uses.
  if (Math.max(g.w, g.h) < 2400) {
    if (gap > worstGap) {
      worstGap = gap;
      worstAt = `${w}x${h} -> ${g.w}x${g.h}`;
    }
    // A phone's own windows: 533 across at the most (the 1200 limit at 2.25),
    // 1000 along (no phone is longer), so no limit but the 720 one applies.
    if (Math.min(w, h) <= 533 && Math.max(w, h) <= 1000) worstPhoneGap = Math.max(worstPhoneGap, gap * s);
  }
  if (g.w % 2 || g.h % 2) odd++;
  const short = Math.min(g.w, g.h);
  const long = Math.max(g.w, g.h);
  const thin = Math.max(w, h) / Math.min(w, h) > 2400 / 720;
  if (long > 2400 || (!thin && (short < 720 || short > 1200))) outOfRange.push(`${w}x${h} -> ${g.w}x${g.h}`);
  const r = guestSize("phone", h, w, 2.625);
  if (r.w !== g.h || r.h !== g.w) notSymmetric.push(`${w}x${h}`);
  const free = Math.min(w, h) * 2.25 >= 720 && Math.min(w, h) * 2.25 <= 1200 && Math.max(w, h) * 2.25 <= 2400;
  if (free && (Math.abs(g.w / w - 2.25) > 0.01 || Math.abs(g.h / h - 2.25) > 0.01)) offScale.push(`${w}x${h}`);
}
// The long side's rounding to even is at most one pixel, and a tie reaches it.
check(
  `the desktop is the box's shape: at most one of its own pixels of letterbox (worst ${worstGap.toFixed(3)})`,
  worstGap <= 1 + 1e-9,
  `${worstGap.toFixed(3)} px at ${worstAt}`,
);
check(
  `which in a window a phone has is under half a CSS pixel (worst ${worstPhoneGap.toFixed(2)})`,
  worstPhoneGap < 0.5,
  `${worstPhoneGap.toFixed(3)} CSS px`,
);
check("its sizes are even", odd === 0, `${odd} odd`);
check(
  "its short side is 720 to 1200 and its long side at most 2400 (a box thinner than 2400:720 keeps the long side)",
  outOfRange.length === 0,
  outOfRange.slice(0, 3).join("; "),
);
check("the same desktop whichever way round", notSymmetric.length === 0, notSymmetric.slice(0, 3).join("; "));
check("2.25 guest px to the CSS px wherever no limit applies, in either orientation", offScale.length === 0, offScale.slice(0, 3).join("; "));

// The design record's numbers: a 6.8-inch phone, 411 CSS px across, its bars
// and the viewer's bar taken off.
const worked = [
  ["upright", 411, 783, 924, 1760],
  ["on its side, the bar docked", 859, 303, 2042, 720],
  ["on its side, the bar folded away", 859, 355, 1930, 798],
  ["a tablet on its side", 1280, 740, 2076, 1200],
];
for (const [name, w, h, ew, eh] of worked) {
  const g = guestSize("phone", w, h, 2.625);
  check(`${name}: ${w}x${h} CSS px is ${ew}x${eh}`, g.w === ew && g.h === eh, `${g.w}x${g.h}`);
}

// ── the desktop display ──

section("the desktop display");
let blurred = [];
let wasted = [];
let outside = [];
for (const [w, h, d] of desktopBoxes) {
  const g = guestSize("desktop", w, h, d);
  if (g.w < 800 || g.h < 600 || g.w > 3840 || g.h > 2400 || g.w % 2 || g.h % 2) outside.push(`${w}x${h}@${d}`);
  const per = Math.max(1, Math.floor(d + 0.25));
  const sharp = sharpScale(d);
  // Where no limit applies, the desktop fits the box at whole device pixels
  // per guest pixel, and wastes less than one even step of them.
  const limited = (v, lo, hi) => v <= lo || v >= hi;
  if (limited(g.w, 800, 3840) || limited(g.h, 600, 2400)) continue;
  if (g.w * sharp > w + 1e-9 || g.h * sharp > h + 1e-9) blurred.push(`${w}x${h}@${d}`);
  if (w * d - g.w * per >= 2 * per || h * d - g.h * per >= 2 * per) wasted.push(`${w}x${h}@${d}`);
}
check("within 800x600 and 3840x2400, and even", outside.length === 0, outside.slice(0, 3).join("; "));
check("fits its box at whole device pixels per guest pixel", blurred.length === 0, blurred.slice(0, 3).join("; "));
check("and leaves less than one even step of device pixels over", wasted.length === 0, wasted.slice(0, 3).join("; "));
check(
  "1080p at 1x less the bar is 1920x1036, sharp at 1",
  JSON.stringify(guestSize("desktop", 1920, 1036, 1)) === '{"w":1920,"h":1036}' && sharpScale(1) === 1,
);
check(
  "a desktop fitted to a 2x screen draws two device pixels per guest pixel",
  JSON.stringify(guestSize("desktop", 1280, 676, 2)) === '{"w":1280,"h":676}' && sharpScale(2) === 1,
);

console.log(`\n${passed} passed, ${failed} failed`);
// Nothing checked is not a pass.
process.exit(failed || !passed ? 1 : 0);
