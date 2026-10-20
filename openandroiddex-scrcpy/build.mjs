#!/usr/bin/env node
// Build scrcpy's server — the part that runs on the phone — from scrcpy's own
// source at the release the desktop app bundles, with one change of ours:
// display-scale.patch (see doc/sharp-desktop.md, "Following the window").
//
//   node openandroiddex-scrcpy/build.mjs     →  openandroiddex-scrcpy/build/scrcpy-server
//
// Only the server is rebuilt. The client stays the stock release from
// .github/workflows/desktop.yml, which is why the version below MUST match
// SCRCPY_VERSION there: the client refuses a server of any other version.
//
// The recipe is upstream's server/build_without_gradle.sh — aidl, javac, d8 —
// redone here so it runs on Windows too, and with the SDK found the way
// openandroiddex-wmd/build.sh finds it. Needs git, a JDK (JAVA_HOME, Android
// Studio's, or javac on PATH) and the Android SDK (platform android-36,
// build-tools with d8 and aidl). The source is cloned once into build/ and
// checked against the commit below, so a moved tag fails the build rather
// than changing what ships.

import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRCPY_VERSION = '5.0.1'
const SCRCPY_COMMIT = 'a60891aea193d92e7e5c3942700eca63f9d19a5f'
const SCRCPY_REPO = 'https://github.com/Genymobile/scrcpy.git'

const here = dirname(fileURLToPath(import.meta.url))
const buildDir = join(here, 'build')
const src = join(buildDir, `scrcpy-${SCRCPY_VERSION}`)
const out = join(buildDir, 'scrcpy-server')
const onWindows = process.platform === 'win32'

function run(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8', ...opts })
}

function fail(message) {
  console.error(`[scrcpy-server] ${message}`)
  process.exit(1)
}

// ── the source, at exactly the commit the release was made from ──────────────

function head() {
  try {
    return run('git', ['-C', src, 'rev-parse', 'HEAD']).trim()
  } catch {
    return ''
  }
}

mkdirSync(buildDir, { recursive: true })
if (head() !== SCRCPY_COMMIT) {
  rmSync(src, { recursive: true, force: true })
  console.log(`[1/4] git clone scrcpy v${SCRCPY_VERSION}`)
  run('git', ['clone', '--quiet', '--depth', '1', '--branch', `v${SCRCPY_VERSION}`, SCRCPY_REPO, src])
  if (head() !== SCRCPY_COMMIT) {
    fail(`v${SCRCPY_VERSION} is ${head() || 'nothing'}, not ${SCRCPY_COMMIT} — refusing to build it`)
  }
} else {
  console.log(`[1/4] scrcpy v${SCRCPY_VERSION} already in ${relative(process.cwd(), src)}`)
}

console.log('[2/4] apply display-scale.patch')
run('git', ['-C', src, 'checkout', '--quiet', '--', '.'])
run('git', ['-C', src, 'clean', '--quiet', '-fd'])
run('git', ['-C', src, 'apply', join(here, 'display-scale.patch')])

// ── the toolchain ─────────────────────────────────────────────────────────

const home = process.env.HOME || process.env.USERPROFILE || ''
const sdk = [
  process.env.ANDROID_HOME,
  process.env.ANDROID_SDK_ROOT,
  ...(onWindows
    ? [join(process.env.LOCALAPPDATA || join(home, 'AppData', 'Local'), 'Android', 'Sdk')]
    : [join(home, 'Library', 'Android', 'sdk'), join(home, 'Android', 'Sdk')]),
].find((dir) => dir && existsSync(join(dir, 'platforms')))
if (!sdk) fail('Android SDK not found. Set ANDROID_HOME.')

const platform = join(sdk, 'platforms', 'android-36')
if (!existsSync(join(platform, 'android.jar'))) fail(`android-36 platform not found under ${sdk}`)

const exe = (name, ext) => (onWindows ? `${name}.${ext}` : name)
const buildTools = ['36.1.0', '36.0.0', '35.0.0', '34.0.0']
  .map((v) => join(sdk, 'build-tools', v))
  .find((dir) => existsSync(join(dir, exe('d8', 'bat'))) && existsSync(join(dir, exe('aidl', 'exe'))))
if (!buildTools) fail(`no build-tools with d8 and aidl under ${join(sdk, 'build-tools')}`)

const studioJbr = onWindows
  ? join(process.env.ProgramFiles || 'C:\\Program Files', 'Android', 'Android Studio', 'jbr', 'bin', 'javac.exe')
  : '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac'
const javac = [process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', exe('javac', 'exe')), studioJbr]
  .find((p) => p && existsSync(p)) || 'javac'

// ── build: upstream's server/build_without_gradle.sh, step for step ──────────

const server = join(src, 'server')
const work = join(buildDir, 'work')
const classes = join(work, 'classes')
const gen = join(work, 'gen')
rmSync(work, { recursive: true, force: true })
mkdirSync(classes, { recursive: true })
mkdirSync(join(gen, 'com', 'genymobile', 'scrcpy'), { recursive: true })

writeFileSync(
  join(gen, 'com', 'genymobile', 'scrcpy', 'BuildConfig.java'),
  `package com.genymobile.scrcpy;

public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String VERSION_NAME = "${SCRCPY_VERSION}";
}
`,
)

console.log(`[3/4] aidl + javac  (${javac})`)
const aidl = join(buildTools, exe('aidl', 'exe'))
const aidlDir = join(server, 'src', 'main', 'aidl')
run(aidl, [`-o${gen}`, '-I.', join('android', 'content', 'IOnPrimaryClipChangedListener.aidl')], { cwd: aidlDir })
run(aidl, [`-o${gen}`, '-I.', '-p', join(platform, 'framework.aidl'), join('android', 'view', 'IDisplayWindowListener.aidl')], { cwd: aidlDir })

function files(dir, ext) {
  const found = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) found.push(...files(p, ext))
    else if (p.endsWith(ext)) found.push(p)
  }
  return found
}

const javaRoot = join(server, 'src', 'main', 'java')
const sources = [
  // upstream's "fake sources", which expose hidden Android types to the build
  ...files(join(javaRoot, 'android', 'content'), '.java'),
  ...files(join(javaRoot, 'com', 'genymobile', 'scrcpy'), '.java'),
]
const sep = onWindows ? ';' : ':'
writeFileSync(join(work, 'javac.args'), sources.map((s) => `"${s.replaceAll('\\', '/')}"`).join('\n'))
run(javac, [
  '-nowarn', '-Xlint:-options',
  '-encoding', 'UTF-8',
  '-bootclasspath', join(platform, 'android.jar'),
  '-cp', `${join(buildTools, 'core-lambda-stubs.jar')}${sep}${gen}`,
  '-d', classes,
  '-source', '1.8', '-target', '1.8',
  `@${join(work, 'javac.args')}`,
])

console.log(`[4/4] d8  (${buildTools})`)
const dexInputs = [
  ...files(join(classes, 'android', 'view'), '.class'),
  ...files(join(classes, 'android', 'content'), '.class'),
  ...files(join(classes, 'com', 'genymobile', 'scrcpy'), '.class'),
]
// One path per line and no quotes: unlike javac's, d8's argument file takes
// each line whole, spaces included, and would read quotes as part of the name.
writeFileSync(join(work, 'd8.args'), dexInputs.join('\n'))
const d8 = join(buildTools, exe('d8', 'bat'))
const zip = join(work, 'classes.zip')
run(d8, ['--classpath', join(platform, 'android.jar'), '--output', zip, `@${join(work, 'd8.args')}`], {
  shell: onWindows,
})
rmSync(out, { force: true })
renameSync(zip, out)
rmSync(work, { recursive: true, force: true })

// The same file under its shipped name, beside the build, for whoever runs this by hand.
if (process.argv[2]) copyFileSync(out, resolve(process.argv[2]))
console.log(`[scrcpy-server] built ${statSync(out).size} bytes → ${out}`)
