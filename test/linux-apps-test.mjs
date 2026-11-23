#!/usr/bin/env node
// Linux apps in an Android process of their own (doc/linux-apps-process.md),
// checked as far as a computer can without a phone:
//
//   node test/linux-apps-test.mjs
//
// The scripts themselves first: the new files the same in the source and in
// the launcher's assets, LF only, POSIX sh that dash and mksh both parse, and
// no apostrophe inside linux-rt.sh's or linux-win.sh's session strings (a lone
// one ends the single-quoted string early, silently).
//
// Then, in an ubuntu:24.04 container -- the guest's own dash, mawk and
// coreutils -- guest-side oadx-run and oadx-apps-menu exactly as the app
// installs them, against a stand-in for the app's side of the hand-over
// (test/linux-apps/fake_service.sh), XFCE 4.18's own helper lookup on a
// throwaway X, linux-app.sh under mksh with a stand-in proot, and the
// sessions' D-Bus policy against a real dbus-daemon: once plainly, and once in
// the phone's shape, bus and clients each in a proot of their own (the app's
// proot, built for this machine by build_proot.sh). See guest_check.sh,
// menu_check.sh, linux_app_check.sh and dbus_check.sh.
//
// Then, in eclipse-temurin:17-jdk, LinuxAppRunner itself against real
// processes, linux-app.sh and that proot (runner_check.sh, RealRunner.java):
// a session's stop, Android killing a proot, the service going while an app
// starts, a request from a closed session.
//
// The Java half also has unit tests that run with the launcher's:
// ./gradlew :app:testDebugUnitTest. What none of this can show -- Android
// ranking the two processes, its limit picking the apps, a real desktop -- is
// the phone test the design record describes.
//
// Needs Docker and the network (apt, and proot's source from GitHub). Uses
// the ubuntu:24.04 and eclipse-temurin:17-jdk images in throwaway containers
// (--rm, labelled oadx-test=linux-apps), and touches nothing else.

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");

let passed = 0;
let failed = 0;
function check(name, ok, detail) {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${ok || detail === undefined ? "" : " — " + detail}`);
}

console.log("the scripts");
const pairs = [
  ["openandroiddex-linux/linux-app.sh", "openandroiddex-launcher/app/src/main/assets/linux/linux-app.sh"],
  ["openandroiddex-linux/guest/oadx-run", "openandroiddex-launcher/app/src/main/assets/linux/guest/oadx-run"],
  ["openandroiddex-linux/guest/oadx-apps-menu", "openandroiddex-launcher/app/src/main/assets/linux/guest/oadx-apps-menu"],
  ["openandroiddex-linux/guest/oadx-apps-dbus.conf", "openandroiddex-launcher/app/src/main/assets/linux/guest/oadx-apps-dbus.conf"],
];
for (const [src, asset] of pairs) {
  const a = fs.readFileSync(path.join(ROOT, src));
  const b = fs.readFileSync(path.join(ROOT, asset));
  check(`${path.basename(src)}: the APK's copy is the source`, a.equals(b));
  check(`${path.basename(src)}: LF only`, !a.includes(13));
}
for (const f of [
  "openandroiddex-linux/linux-rt.sh",
  "openandroiddex-launcher/app/src/main/assets/linux/linux-rt.sh",
  "openandroiddex-linux/linux-win.sh",
  "openandroiddex-launcher/app/src/main/assets/linux/linux-win.sh",
]) {
  const s = fs.readFileSync(path.join(ROOT, f), "utf8");
  const start = s.indexOf("SESSION='");
  const end = s.indexOf("exit $RC'", start) > 0 ? s.indexOf("exit $RC'", start) : s.indexOf("exit 0'", start);
  const inside = start > 0 && end > start ? s.slice(start + "SESSION='".length, end) : null;
  check(`${f}: no apostrophe inside the session string`, inside !== null && !inside.includes("'"));
  check(`${f}: the apps menu is pointed at oadx-run`, s.includes("XDG_DATA_DIRS=/usr/local/share/oadx-apps:/usr/local/share:/usr/share"));
}
{
  const rt = fs.readFileSync(path.join(ROOT, "openandroiddex-linux/linux-rt.sh"), "utf8");
  const sess = rt.slice(rt.indexOf("SESSION='"), rt.indexOf("exit $RC'"));
  const menu = sess.indexOf("oadx-apps-menu --launchers-only");
  const theme = sess.indexOf('[ -n "$THEME" ] && wait $THEME');
  const xfce = sess.indexOf("startxfce4");
  check("linux-rt.sh: the dock is routed after dock.py and theme.py, before XFCE starts",
    theme > 0 && menu > theme && xfce > menu, `wait ${theme}, menu ${menu}, xfce ${xfce}`);
  const win = fs.readFileSync(path.join(ROOT, "openandroiddex-linux/linux-win.sh"), "utf8");
  check("linux-win.sh: a window session app goes through oadx-run",
    win.includes('/usr/local/bin/oadx-run /bin/sh -c "$cmd"'));
}

console.log("\nin ubuntu:24.04");
const docker = spawnSync("docker", ["version", "--format", "{{.Server.Version}}"], { encoding: "utf8" });
if (docker.status !== 0) {
  check("Docker is running", false, (docker.stderr || "").trim().split("\n")[0]);
} else {
  const r = spawnSync(
    "docker",
    [
      "run", "--rm", "--label", "oadx-test=linux-apps",
      "-v", `${ROOT}:/work:ro`,
      "ubuntu:24.04",
      "sh", "-c",
      // mksh for linux-app.sh (Android's own /system/bin/sh); the rest is the
      // image as it comes, like the guest's.
      "export DEBIAN_FRONTEND=noninteractive; " +
        "(apt-get update -qq && apt-get install -y -qq mksh procps dbus-daemon dbus-bin libglib2.0-bin gcc libc6-dev make libtalloc-dev patch curl >/dev/null && " +
        // XFCE 4.18 own helper lookup (xfce4-mime-helper) for menu_check.sh, on a throwaway X.
        "apt-get install -y -qq --no-install-recommends xfce4-settings xvfb xauth >/dev/null) >/tmp/apt.log 2>&1 || { tail -5 /tmp/apt.log; exit 3; }; " +
        "for f in /work/openandroiddex-linux/linux-app.sh /work/openandroiddex-linux/guest/oadx-run /work/openandroiddex-linux/guest/oadx-apps-menu; do " +
        "dash -n \"$f\" || { echo \"  FAIL dash -n $f\"; exit 4; }; mksh -n \"$f\" || { echo \"  FAIL mksh -n $f\"; exit 4; }; done; echo '  ok   dash and mksh both parse the three scripts'; " +
        "sh /work/test/linux-apps/guest_check.sh; g=$?; sh /work/test/linux-apps/linux_app_check.sh; a=$?; echo 'the D-Bus policy of the sessions'; sh /work/test/linux-apps/build_proot.sh && sh /work/test/linux-apps/dbus_check.sh; d=$?; [ $g -eq 0 ] && [ $a -eq 0 ] && [ $d -eq 0 ]",
    ],
    { encoding: "utf8", maxBuffer: 1 << 24 },
  );
  process.stdout.write(r.stdout);
  if (r.stderr.trim()) process.stdout.write(r.stderr.split("\n").map((l) => "  | " + l).join("\n") + "\n");
  for (const line of r.stdout.split("\n")) {
    if (/^\s+ok\s/.test(line)) passed++;
    else if (/^\s+FAIL\s/.test(line)) failed++;
  }
  check("the container run ended cleanly", r.status === 0, `exit ${r.status}`);

  // LinuxAppRunner itself against real processes: a JDK, gcc to build the
  // app's own proot for this machine, and the apps as an ordinary user. See
  // runner_check.sh. --init, so that what is orphaned is reaped as on a phone.
  console.log("\nin eclipse-temurin:17-jdk");
  const j = spawnSync(
    "docker",
    [
      "run", "--rm", "--init", "--label", "oadx-test=linux-apps",
      "-v", `${ROOT}:/work:ro`,
      "eclipse-temurin:17-jdk",
      "sh", "-c",
      "export DEBIAN_FRONTEND=noninteractive; " +
        "(apt-get update -qq && apt-get install -y -qq gcc libc6-dev make libtalloc-dev patch curl mksh procps util-linux >/dev/null) >/tmp/apt.log 2>&1 || { tail -5 /tmp/apt.log; exit 3; }; " +
        "sh /work/test/linux-apps/runner_check.sh",
    ],
    { encoding: "utf8", maxBuffer: 1 << 24 },
  );
  process.stdout.write(j.stdout);
  if (j.stderr.trim()) process.stdout.write(j.stderr.split("\n").map((l) => "  | " + l).join("\n") + "\n");
  for (const line of j.stdout.split("\n")) {
    if (/^\s+ok\s/.test(line)) passed++;
    else if (/^\s+FAIL\s/.test(line)) failed++;
  }
  check("the runner's container run ended cleanly", j.status === 0, `exit ${j.status}`);
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
