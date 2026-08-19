---
name: dex-window-debug
description: Inspect and drive Android window state on the Open Android DeX desktop display — build/push/run the uid-2000 wmd daemon, find the live scrcpy display, list and move tasks, reserve caption strips, and verify chrome in SurfaceFlinger. Use when debugging titlebars, window chrome, freeform task geometry, z-order, or the CaptionService accessibility overlay.
---

# Debugging windows on the DeX desktop display

Everything here talks to `openandroiddex-wmd`, the shell-uid daemon. It exists because
`MANAGE_ACTIVITY_TASKS` is what lets anything enumerate, move, re-order or inset another
app's task, and the launcher (a normal app) can never hold it.

## Run commands from PowerShell, not Git Bash

Git Bash rewrites `/data/local/tmp/...` into a Windows path and the command fails with
`inaccessible or not found`. Every snippet below assumes PowerShell.

## Build, push, start the daemon

```powershell
cmd /c "openandroiddex-wmd\build.cmd push"     # javac + d8 + adb push, no Gradle
adb shell "pkill -f WmDaemon"
adb shell "setsid sh -c 'CLASSPATH=/data/local/tmp/wmd.dex exec app_process /system/bin com.ccrstech.openandroiddex.wmd.WmDaemon > /data/local/tmp/wmd.log 2>&1' &"
adb shell "cat /data/local/tmp/wmd.log"        # expect: wmd listening on 127.0.0.1:7191 uid=2000
```

`setsid` is required: a plain background job dies with the adb shell that launched it.

## Talk to it

```powershell
adb shell "printf 'PING`nBYE`n' | toybox nc 127.0.0.1 7191"
```

**Never hard-code the display id — it changes every scrcpy session, and dead sessions
leave virtual displays behind that look identical to the live one.** Ask the daemon:

```powershell
adb shell "printf 'DESKTOP com.ccrstech.openandroiddex.launcher`nBYE`n' | toybox nc 127.0.0.1 7191"
```

Protocol: `PING` · `DESKTOP <pkg>` · `LIST <display>` · `STRIP <display> <task> <px> [inset]` ·
`UNSTRIP` · `MOVE <display> <task> <x> <y>` · `BOUNDS <display> <task> <l> <t> <r> <b>` ·
`FRONT` · `BACK` · `FOCUSABLE <display> <task> <0|1>` · `CLOSE <task>` · `BYE`.

`LIST` returns **topmost first**, 17 whitespace fields:

```
TASK ix id display mode actType vis  l t r b   al at ar ab   pkg activity
                                     └ bounds ┘ └ appBounds ┘
```

`appBounds.top > bounds.top` means a caption strip is reserved. Field 15 is the package —
reading 14 instead renders window titles as a y coordinate.

## From the PC instead of the device

```powershell
adb forward tcp:7191 tcp:7191     # then talk to 127.0.0.1:7191 from Windows
adb forward --remove tcp:7191
```

Measured RTT over USB: median 2.57 ms, p95 5.30 ms.

## One-shot probes without the daemon

```powershell
adb shell "CLASSPATH=/data/local/tmp/wmd.dex app_process /system/bin com.ccrstech.openandroiddex.wmd.Probe <cmd>"
```

`caps` (what is reachable at this uid) · `list <display>` · `move <display> <task> <dx> <dy> <legacy|transition|resize>` ·
`strip <display> <task> <px>` · `stack <display> <low> <high>` · `focusable` ·
`sig <wct|wms|atm|woc|CLASS> [filter]` — dump real method signatures, because hidden-API
shapes vary by build and OEM. Guessing one throws inside `system_server`.

## Verify chrome actually renders

`dumpsys` bounds only prove the Configuration changed. To prove **pixels**, read the layer:

```powershell
adb shell "dumpsys SurfaceFlinger --list" | Select-String "SurfaceControlViewHost#\d+ parentId"
adb shell "dumpsys SurfaceFlinger" | Select-String "Layer \[<id>\]" -Context 0,3
```

Read carefully:

- `visible reason= buffer=…` — real pixels. `invisible reason=hidden by parent or layer flag`
  means the surface exists but nothing shows it.
- SF prints `bounds={left,top,bottom,right}` — **not** l,t,r,b.
- `toDisplayTransform={ tx= ty= }` on `Task=N` is where the window actually is. Use this,
  not task bounds, to prove a move landed.
- Per task the platform maintains `Decor container of Task=N` (z=30000, child of the Task)
  and sometimes `Caption of Task=N`. Anything parented inside the **app window** is below
  those regardless of its own z.

To check our caption covers One UI's exactly, compare buffer size against composited rect —
the two columns disagree when a parent surface crops us:

```powershell
adb shell "dumpsys SurfaceFlinger" | Select-String "705.0    40.0|648.0    40.0"
```

```
   src w/h    |  composited l t r b  | layer
  705.0  40.0 |  397  27 1102  67    | Caption of Task=1005   <- One UI's
  705.0  40.0 |  397  27 1102  67    | #5162                  <- ours, exact cover
  705.0  40.0 |  397  27  693  67    | #13970                 <- ours, CROPPED to a pane
```

A short `coveredRegion` on the platform's caption layer is the sliver of old title bar
still showing.

**A layer with no `parentId` is an attach that silently failed** — the surface exists, is
never composited, and nothing reports an error:

```powershell
adb shell "dumpsys SurfaceFlinger --list" | Select-String "SurfaceControlViewHost#"
# good: SurfaceControlViewHost#22611 parentId=22489 z=2147483647
# bad:  SurfaceControlViewHost#22558 z=2147483647          <- orphaned
```

Usual cause is the wrong `InputTransferToken`: a *display* overlay needs a NEW
`InputTransferToken()`, a *window* overlay needs one belonging to a live window. Caption churn is invisible from the UI — check `files/caption.log` grows
(`wc -l`) rather than trusting how it looks; a caption being destroyed and rebuilt 30×/sec
looks completely normal on screen.

## Caption service (Route A)

```powershell
adb shell "appops set com.ccrstech.openandroiddex.launcher ACCESS_RESTRICTED_SETTINGS allow"
adb shell "settings put secure enabled_accessibility_services com.ccrstech.openandroiddex.launcher/com.ccrstech.openandroiddex.launcher.CaptionService"
adb shell "settings put secure accessibility_enabled 1"
adb shell "run-as com.ccrstech.openandroiddex.launcher cat files/caption.log"
```

`run-as` only works if the launcher was built with `-PdebuggableLauncher` — the
shipped APK is non-debuggable so Android's 16 KB page-size dialog stays away.

**Installing the APK clears both the appop and the enabled-services list**, and so does
`am force-stop` — so apply the grant *after* the launcher is (re)started, never before.
Force-stop does NOT clear the appop, so the half-wiped state reads as "permission fine,
service just isn't running". This is the single most common reason captions "stop working".
`adb::enable_caption_service` does this automatically on every `npm run tauri dev`; the
commands above are for a hand-installed APK, or to check what the automatic path did.

Confirm the service is actually alive before debugging anything else — `settings get` /
`appops get` returning `null` / `No operations` means it never started, and there will be
no error anywhere to find:

```powershell
adb shell "settings get secure enabled_accessibility_services"
adb shell "appops get com.ccrstech.openandroiddex.launcher ACCESS_RESTRICTED_SETTINGS"
```

If the setting reads back `null` immediately, it is being filtered, not rejected. Check for
an MDM allowlist:

```powershell
adb shell "dumpsys device_policy | grep -B3 'accessibility services'"
adb shell "pm list users"
```

`accessibility services: empty` under a Profile Owner is an empty allowlist: no third-party
a11y service can run. Confirm by writing a *system* service — if that sticks and yours does
not, it is policy, not code.

Prefer the breadcrumb file over logcat: app logs from this uid are unreliable on One UI.
`no host task=… candidates=…` in that file lists every window `pickHost` rejected, with
rects — that is the tool for "the service runs but `captions=0`".

## Verify the device is running the APK you just built

Before debugging any behaviour that "did not take". The dev app deploys the copy staged
under `target/<profile>/resources/bin`, not `resources/bin`, and Tauri does not always
refresh it — so a stale APK gets reinstalled over a sideloaded one on every run:

```powershell
(Get-FileHash open-android-dex-tauri\src-tauri\resources\bin\openandroiddex-launcher.apk -Algorithm MD5).Hash
(Get-FileHash open-android-dex-tauri\src-tauri\target\debug\resources\bin\openandroiddex-launcher.apk -Algorithm MD5).Hash
adb shell "pm path com.ccrstech.openandroiddex.launcher"     # then md5sum that path
```

All three must match. `node open-android-dex-tauri\scripts\build-launcher-apk.mjs` rebuilds
and refreshes both; it warns if a running dev instance has the staged file locked.

## Leave the device clean

```powershell
adb shell "settings put secure accessibility_enabled 0"    # strips auto-release on service destroy
adb shell "printf 'UNSTRIP <display> <task>`nBYE`n' | toybox nc 127.0.0.1 7191"
adb shell "pkill -f WmDaemon"
```

A `setAppBounds` override **outlives** its inset source and its owner binder — clear it
explicitly or apps stay short by the strip height.

## Traps that cost hours

- **Never `BOUNDS` a task to an empty rect** and never clear its bounds: `matchParentBounds()`
  flips true and every task below it is paused or stopped.
- Do not prototype nesting a Task under a Task that holds an ActivityRecord — unchecked cast
  in `resumeTopActivityUncheckedLocked` kills `system_server`.
- `setprop persist.wm.debug.*` is SELinux-denied to shell, and SystemUI cannot be killed
  (Knox). Flags latched at SystemUI start need a reboot.
- `settings put global override_desktop_mode_features ALL_DISABLED` is accepted but may
  disable freeform along with desktop mode. Do not leave it set.
