// Build the Open Android DeX launcher APK and drop it where Tauri bundles it.
//
// The desktop ships the launcher as a resource and reinstalls it on every
// connect (see adb.rs, adb_start_launcher). That APK used to be a hand-built
// file checked into resources/bin, so a launcher change only reached the phone
// if someone remembered to rebuild and copy it — and because the install is an
// unconditional `install -r`, a stale bundle silently *reverts* whatever was
// sideloaded onto the device. Wiring this into beforeDevCommand /
// beforeBuildCommand makes that impossible: the bundled APK is always built
// from the launcher sources in this repo.
//
// Release builds of the launcher are unsigned (no signingConfig), so they
// cannot be installed — assembleDebug is what ships, matching what was
// bundled before.
//
// Set SKIP_LAUNCHER_APK=1 to bypass the APK build and SKIP_WMD_DEX=1 to bypass
// the wmd dex build (set both for frontend-only work on a machine without a
// JDK or the Android SDK). A skipped artifact is then whatever was there
// already. The flags are separate because CI learned the hard way that one
// flag covering both lets a release ship without the dex: the `launcher` CI
// job rebuilds both payloads, but the old whole-script skip silently dropped
// the dex from every installer (broken window chrome / no custom titlebars).
// SKIP_LAUNCHER_APK also skips syncing the Linux provisioning scripts into the
// launcher's assets (the third leg below) — they ride inside the APK now.

import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

// Copying into resources/bin is not enough. At runtime the app resolves its
// resources from the *staged* copy Tauri makes under target/<profile>/resources,
// and tauri-build only refreshes that when its build script re-runs — which it
// does not for these files, because they are not in the rerun-if-changed set it
// emits. A dev session therefore keeps installing whatever APK was staged the
// first time, silently reverting every launcher change, which looks exactly
// like the fix not working. Refresh any staged copy that already exists.
function stage(built, bundled) {
  mkdirSync(dirname(bundled), { recursive: true })
  copyFileSync(built, bundled)
  const name = basename(bundled)
  for (const profile of ['debug', 'release']) {
    const staged = resolve(here, '..', 'src-tauri', 'target', profile, 'resources', 'bin', name)
    if (!existsSync(dirname(staged))) continue
    try {
      copyFileSync(built, staged)
      console.log(`[stage] refreshed ${staged}`)
    } catch (e) {
      // Locked by a running dev instance. Worth saying out loud: the app will
      // keep deploying the stale artifact until it is restarted.
      console.warn(`[stage] could not refresh ${staged} (${e.code}) — restart the dev app`)
    }
  }
}
const launcherDir = resolve(here, '..', '..', 'openandroiddex-launcher')
const builtApk = join(launcherDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
const bundledApk = resolve(here, '..', 'src-tauri', 'resources', 'bin', 'openandroiddex-launcher.apk')
const onWindows = process.platform === 'win32'
const gradlew = join(launcherDir, onWindows ? 'gradlew.bat' : 'gradlew')

// Both halves below need the Android SDK, and neither has a way to ask for it
// interactively. ANDROID_HOME is the contract (CI sets it); this fills it in
// from the place each platform's Android Studio installs to, so a developer who
// has Studio and has never exported anything still gets a build rather than
// "SDK location not found". Never overrides an ANDROID_HOME that is already set.
//
// The same order as openandroiddex-wmd/build.sh — keep them in step.
function androidHome() {
  const home = process.env.HOME || process.env.USERPROFILE || ''
  // Every candidate is tested, not just the first one that is set: a stale
  // ANDROID_HOME left over from an old install would otherwise mask a perfectly
  // good ANDROID_SDK_ROOT. Same order, and same "does it have platforms/" test,
  // as openandroiddex-wmd/build.sh.
  return [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    ...(onWindows
      ? [join(process.env.LOCALAPPDATA || join(home, 'AppData', 'Local'), 'Android', 'Sdk')]
      : [join(home, 'Library', 'Android', 'sdk'), join(home, 'Android', 'Sdk')]),
  ].find((dir) => dir && existsSync(join(dir, 'platforms')))
}

const sdk = androidHome()
const buildEnv = { ...process.env }
if (sdk) {
  buildEnv.ANDROID_HOME = sdk
  buildEnv.ANDROID_SDK_ROOT = sdk
}

if (process.env.SKIP_LAUNCHER_APK === '1') {
  console.log('[launcher-apk] SKIP_LAUNCHER_APK=1 — keeping the bundled APK as is')
} else {
  if (!existsSync(gradlew)) {
    console.error(`[launcher-apk] no Gradle wrapper at ${gradlew}`)
    process.exit(1)
  }

  console.log('[launcher-apk] building the launcher APK…')
  // A .bat is not directly executable via CreateProcess, so it goes through the
  // command interpreter. Naming cmd.exe explicitly rather than passing
  // shell: true keeps the arguments out of shell parsing.
  const [command, args] = onWindows
    ? ['cmd.exe', ['/d', '/s', '/c', gradlew, 'assembleDebug', '--console=plain']]
    : [gradlew, ['assembleDebug', '--console=plain']]
  try {
    execFileSync(command, args, { cwd: launcherDir, stdio: 'inherit', env: buildEnv })
  } catch {
    console.error(
      '[launcher-apk] Gradle build failed. A JDK (JAVA_HOME) and the Android SDK\n' +
        '               (ANDROID_HOME) are required. Set SKIP_LAUNCHER_APK=1 to skip\n' +
        '               this step and keep the APK that is already bundled.'
    )
    process.exit(1)
  }

  if (!existsSync(builtApk)) {
    console.error(`[launcher-apk] Gradle reported success but ${builtApk} is missing`)
    process.exit(1)
  }

  stage(builtApk, bundledApk)
  console.log(`[launcher-apk] bundled ${statSync(bundledApk).size} bytes → ${bundledApk}`)
}

// ── wmd: the shell-uid daemon ─────────────────────────────────────────────
// Bundled for the same reason as the APK: adb.rs pushes it on every connect, so
// a stale dex would silently downgrade whatever is on the device. It is not an
// Android project — javac + d8, no Gradle — hence its own build script.
const wmdDir = resolve(here, '..', '..', 'openandroiddex-wmd')
const builtDex = join(wmdDir, 'openandroiddex-wmd.dex')
const bundledDex = resolve(here, '..', 'src-tauri', 'resources', 'bin', 'openandroiddex-wmd.dex')

if (process.env.SKIP_WMD_DEX === '1') {
  console.log('[wmd] SKIP_WMD_DEX=1 — keeping the bundled dex as is')
} else {
  console.log('[wmd] building the daemon dex…')
  // build.cmd and build.sh are the same three commands (javac, d8, copy) in the
  // two shells. Neither is Gradle, so there is no wrapper to hide the split.
  const [wmdCommand, wmdArgs] = onWindows
    ? ['cmd.exe', ['/d', '/s', '/c', join(wmdDir, 'build.cmd')]]
    : [join(wmdDir, 'build.sh'), []]
  try {
    execFileSync(wmdCommand, wmdArgs, { cwd: wmdDir, stdio: 'inherit', env: buildEnv })
  } catch {
    console.error(
      '[wmd] build failed. Needs the Android SDK (platform android-36 + build-tools d8)\n' +
        '      and a JDK 17+ (JAVA_HOME, or the Android Studio JBR). Set SKIP_WMD_DEX=1 to\n' +
        '      skip this step and keep the dex that is already bundled.'
    )
    process.exit(1)
  }
  if (!existsSync(builtDex)) {
    console.error(`[wmd] build reported success but ${builtDex} is missing`)
    process.exit(1)
  }
  stage(builtDex, bundledDex)
  console.log(`[wmd] bundled ${statSync(bundledDex).size} bytes → ${bundledDex}`)
}

// ── linux: keep the app's provisioning scripts in sync ─────────────────────
// The Linux feature is now owned entirely by the launcher APK (no PC push, no
// daemon): proot ships as committed jniLibs, the Ubuntu rootfs is downloaded
// by the app on first run, and the two provisioning scripts are APK assets.
// The scripts' source of truth is openandroiddex-linux/; copy them into the
// launcher's assets so a build always carries the current version. Nothing is
// pushed to resources/bin any more — the whole stack rides inside the APK.
if (process.env.SKIP_LAUNCHER_APK === '1') {
  console.log('[linux] SKIP_LAUNCHER_APK=1 — leaving launcher assets as is')
} else {
  const linuxDir = resolve(here, '..', '..', 'openandroiddex-linux')
  const assetsLinux = resolve(
    here, '..', '..', 'openandroiddex-launcher', 'app', 'src', 'main', 'assets', 'linux'
  )
  mkdirSync(assetsLinux, { recursive: true })
  for (const name of ['linux-setup.sh', 'linux-rt.sh']) {
    const src = join(linuxDir, name)
    if (!existsSync(src)) {
      console.error(`[linux] ${src} is missing`)
      process.exit(1)
    }
    copyFileSync(src, join(assetsLinux, name))
  }
  console.log('[linux] synced linux-setup.sh + linux-rt.sh → launcher assets')
}

// ── docker: same deal, one directory over ──────────────────────────────────
// The Docker feature is a sibling of Linux, not a part of it: it is a QEMU
// virtual machine (this kernel has no PID/user namespaces, so a container is
// not makeable on Android itself — see Docker.java). QEMU ships as committed
// jniLibs, Alpine's kernel/initramfs/modloop/minirootfs are downloaded by the
// app on first run, and the two scripts here are APK assets.
//
// guest-init.sh is the one that is NOT run on Android: the app appends it to
// Alpine's initramfs and the guest kernel executes it as PID 1. It is still an
// asset like any other — the app only has to copy bytes.
if (process.env.SKIP_LAUNCHER_APK === '1') {
  console.log('[docker] SKIP_LAUNCHER_APK=1 — leaving launcher assets as is')
} else {
  const dockerDir = resolve(here, '..', '..', 'openandroiddex-docker')
  const assetsDocker = resolve(
    here, '..', '..', 'openandroiddex-launcher', 'app', 'src', 'main', 'assets', 'docker'
  )
  mkdirSync(assetsDocker, { recursive: true })
  for (const name of ['docker-rt.sh', 'guest-init.sh']) {
    const src = join(dockerDir, name)
    if (!existsSync(src)) {
      console.error(`[docker] ${src} is missing`)
      process.exit(1)
    }
    copyFileSync(src, join(assetsDocker, name))
  }
  console.log('[docker] synced docker-rt.sh + guest-init.sh → launcher assets')
}
