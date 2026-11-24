# A way out of a full-screen app

A tester on a Galaxy A13 (One UI 4, Android 12) made a video full screen on the
desktop and was stuck: the video covered the taskbar, and with it ◁ ○ ▢. Then the
three buttons did nothing even when they could be reached, and maximising the
video from One UI's own window controls ended in "Failed to play video".
2026-11-17. This is everything done about it, and what is and is not verified.

Code: `src-tauri/src/deskbar.rs` (the desktop window's own buttons),
`LauncherActivity` (`taskbarBack`, `goHome`, `recentApps`), `CaptionService`
(`onShowDesktop`), `WmClient.list`, `WmDaemon.giveKeyboardTo` and
`AppInput.pressTakenBack` (TASKKEY on Android 12), `scrcpy.rs` (`Enforcer::apply`,
`TaskRec::opened_with_data`).

## Why the A13 is different

Its desktop is an untrusted virtual display, and One UI 4 keeps every freeform window
above anything an app draws there: the taskbar is inside the launcher's activity
(`overlaysBuried`), so a window covering the bottom of the desktop covers the taskbar.
Before Android 14 only the topmost display has a focused window at all, and the
phone's own screen is often that display.

## The desktop window's own Back, Home and Recent apps

Three buttons at the left end of scrcpy's title bar, where an app window's Back is
(`apps/win/frame.rs`): Segoe MDL2 Assets' back arrow, house and task view. Nothing on
the phone can cover them. Each runs `am broadcast -a …GESTURE --es action back|home|recents`,
the three-finger gestures' path, and the launcher runs the taskbar's own code for it, so
the PC and the taskbar can never do different things.

- A top-level window of ours (`WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE`, `MA_NOACTIVATE`): a
  window in another process's title bar cannot be its child. A 16 ms tick keeps it on
  the title bar and directly above scrcpy's window in the z-order (never above what is
  above that), hides it while the desktop is minimised or hidden, and drops it when
  scrcpy's window goes.
- The title is pushed right with `TITLE_PAD`, a middle dot (which the bar covers) and 30
  spaces: Windows 11 draws a title without its leading whitespace, and spaces, em
  spaces, a zero-width space, a blank braille cell, U+3164 and U+FFA0 in front were all
  dropped. The count holds at any scale, since the caption font and the bar grow
  together.
- Full screen has no title bar: the bar waits at the top edge, in the middle, and shows
  while the pointer is there (900 ms linger).
- Not on a narrow window: it needs room for itself, scrcpy's icon and the start of the
  title, and the three caption buttons.
- Windows only. On a Mac nothing places a window of ours over another process's title
  bar.

## The taskbar's ◁ ○ ▢

- **◁** (`taskbarBack`) closes the launcher's own drawer or flyout first, as Back does
  on a phone. Otherwise it goes to the topmost visible window that is not the desktop,
  through the daemon's `TASKKEY`, as a title bar's Back does (`doc/custom-titlebar-v2.md`,
  "Back, More and Sound"). It used to send the computer's Back to the display. On the
  A13 a click on the taskbar is a click on the desktop, which takes the keyboard, so that
  Back went to the desktop and did nothing. With no window list (no daemon), the old
  display-wide Back.
- **○** (`goHome`) is Show desktop (`CaptionService`) on every phone: every window and
  every full-screen app minimised, ○ again putting them back, a taskbar icon bringing
  back its own, as DeX's own Home clears the windows. It used to raise the desktop's
  task, and no reorder lifts it over a window: nothing visible happened on the A13
  (Android 12) or the S25 Ultra (Android 17) with an app open.
- **▢** (`recentApps`), where overlays are buried: the window at the back comes to the
  front, each press the next one, because the flyout would be drawn under the windows.
  With nothing open, the flyout. Elsewhere, the flyout as before.

**Show desktop counts full-screen apps too.** It counted only freeform windows, the ones
with captions. An app full screen was not hidden but covered by the desktop that ○ raised
right after, so it was in neither the taskbar nor the minimised map, and ○ again brought
back only the windows: the video was gone, still playing behind the desktop.

`CaptionService` runs below Android 14 too now, without bars ("windows only"): Show
desktop and the minimised windows' bookkeeping need it. It puts back every window it
minimised when it stops.

**The launcher's window list retries once.** `WmClient.list` read EOF from a connection
to a daemon that had since restarted, answered null, and the taskbar's ◁ fell back to the
display-wide Back; `request()` already retried once on a fresh connection, and `list` now
does the same when it had a connection to lose. A daemon restart is routine: Samsung
restarts adbd at every plug-in, and the enforcer starts the daemon again.

## Back on Android 12: a press taken back

`TASKKEY` gives the window the keyboard with `setFocusedTask` before it sends the key.
On the A13 that raises the task within its display and nothing more: with the phone's
own screen holding the keyboard (used last, a call, an app started there), the task
never got it, and the PC's Back answered `ERR not focused` with nothing sent. Only a
press moves the keyboard to another display (`WindowState#handleTapOutsideFocusInsideSelf`).

So when the keyboard is still on another display 100 ms after `setFocusedTask`,
`giveKeyboardTo` presses the middle of the window, a finger down and the gesture
cancelled at once (`AppInput.pressTakenBack`), focuses the task again and waits up to
400 ms more. What the press lands on sees it cancelled and is not clicked. If another
window covers that spot, it is what gets the keyboard, and `setFocusedTask` moves it on
within the display. Not while a mouse press or game controls' fingers are down on that
display. The daemon logs each press: `taskkey: the keyboard was on display 19; a press
taken back on task 3915 at 816,432 to bring it to display 18`.

## The enforcer leaves a full-screen video full screen

A full-screen task goes back into a window only by being started again
(`am start -n … --windowingMode 5`), and that intent names the component and nothing
else. A screen opened with something, a video or a document another app handed it, gets
an empty intent instead: Samsung's video player, maximised from One UI's window
controls, answered "Failed to play video". The enforcer reads each task's first
`intent={…}` line, and a full-screen task whose intent has ` dat=` is left full screen
(`opened_with_data`, logged once: "left as it is: a re-launch would empty it"). The
desktop window's buttons are the way out.

A video started in a window can also come up freeform with empty bounds, filling the
display (`mBounds=Rect(0, 0 - 0, 0)`, Samsung's player on the A13). Pinning those to the
maximised rect was tried the same day and taken out: One UI then drew its window pill
over the video.

## Verified

Galaxy A13 (Android 12), desktop on displays 18 and 20, this PC, 2026-11-17:

- The bar at the left end of the title bar, the title starting past it, gone with the
  desktop's window. Full screen: hidden until the pointer was at the top edge, then at
  the top in the middle, directly above the desktop's window in the z-order, and hidden
  again 1.5 s after the pointer left. (VS Code was in front of the full-screen desktop on
  the screen, so this was read from the z-order and a PrintWindow of the bar, not seen.)
- One UI's own maximise on Samsung's video player, started from a `content://` video:
  the enforcer logged "left as it is", no re-launch, and the player kept its place
  (0:24 of 1:00, paused) through what followed.
- With that video full screen and Clock in a window above it, the bar's ○ hid both and
  ○ again brought both back, the video still at 0:24. Before the Show desktop change the
  same two presses hid Clock, covered the video, and brought back only Clock.
- The bar's ◁ with the keyboard on another display (a throwaway scrcpy display holding
  it after a press there): `a press taken back on task 3915 at 816,432`,
  `back for com.samsung.android.video (task 3915): OK 215`, the player closed, and the
  keyboard was on the desktop after. Before that change: `ERR not focused`, nothing sent.
- ◁ right after a daemon restart: `back for com.samsung.android.video (task 3919): OK 0`.
  Before the `WmClient.list` change the same press logged `[request] key back queued`.
- The bar's ▢ cycled the windows; the taskbar's ▢: `recent apps:
  com.sec.android.app.myfiles (task 3905) to the front`; its ◁: `back for
  com.sec.android.app.myfiles (task 3905): OK 67`; its ○ twice: windows hidden, then back
  (before the full-screen change).

Galaxy S25 Ultra (Android 17), over Wi-Fi, 2026-11-17: the bar's ▢ opened the taskbar's
Open apps flyout; ○ hid Play Store (its taskbar icon stayed) and ○ again brought it back;
◁ closed it (`back for com.android.vending (task 1570): OK 0`). Before the ○ change, ○
only closed the flyout and Play Store stayed.

## MIUI: Redmi 9A, MIUI 12.5, Android 11

The app refuses a MIUI desktop until "USB debugging (Security settings)" is on ("This
phone is blocking clicks and keystrokes sent from a computer"); with it on, 2026-11-17:

- The bar at the left end of the desktop window's title bar, the title past it.
- The daemon cannot list windows below Android 12 (`getAllRootTaskInfosOnDisplay` is
  missing), so the caption service never finds its display: ○ raises the desktop over a
  full-screen app but cannot clear a window, and ◁ falls back to the display-wide Back.
- MIUI's Clock never became a window (six re-launches, then left full screen), and as a
  portrait app full screen it turned the whole desktop portrait. Android 10 and 11 now
  have the desktop's rotation held at its own (`rotation_lock_command`). With that build
  the enforcer logged "rotation held at the desktop's own", and the desktop stayed
  1416 × 804 through Clock's re-launches (eight reads over 16 s).
- Android keeps that hold under the display's identity
  (`virtual:com.android.shell,2000,scrcpy,0`), as it keeps a forced density, and the next
  scrcpy display with that identity got it. The computer took it back only on an ending it
  starts (Disconnect, Exit), and as `disabled`, which Android keeps as well. Closing the
  desktop's window with its own × left the lock behind (found in a review, 2026-11-18).
  Now the computer resets it to `default`, and the phone's scrcpy server takes the hold
  off itself before it lets the display go, whatever ends the session. It also takes it
  off every display it makes, for a server killed before it could
  (desktop-rotation.patch). Run on the Redmi by USB, 2026-11-18. Each result was read on
  the next display, made by the unpatched server, which clears nothing:
  - A bare display had inherited the morning's lock (`USER_ROTATION_LOCKED`,
    `mFixedToUserRotation=true`). The patched server's next display logged "Rotation hold
    left by an earlier display taken off" before "New display", and was free.
  - The desktop closed with `WM_CLOSE` posted to scrcpy's window, which is what its ×
    sends (scrcpy exited by itself, `intentional=false`): "Rotation hold on this display
    taken off (display 68)", and the next display was free.
  - The USB link dropped (`adb reconnect`): the server died without clearing. The app's
    reconnect made the next display, with the same identity, and it logged "left by an
    earlier display taken off". The computer then held it again.
  - Disconnect: free after.
  - The rebuilt app with the final server (17:05 to 17:10): Disconnect and Exit each sent
    `wm set-fix-to-user-rotation -d N default; wm set-user-rotation free -d N` ("freed",
    "rotation free again before it goes"). × sent nothing from the computer, and the
    server logged "Rotation hold on this display taken off (display 130)". All three ended
    at 181 bytes (below), and the next display was free.
  - What Android saved, by the size of `/data/system/display_settings.xml` (the shell can
    read the size, not the file): 181 bytes with nothing kept for the display, 226 with
    the hold, 205 with `disabled` alone. The patched server's × and Disconnect both
    ended at 181. Disconnect from the app still running the old `disabled` line also ended
    at 181: the server sets `default` whatever it finds.
- **Android 10 can't be given `default`.** Its window manager has no call for fixed to the
  user's rotation outside its own process (no `setFixedToUserRotation` in Android 10's
  `IWindowManager`), only the shell command, and that command takes `default` as
  `disabled` (android-10.0.0_r47, `WindowManagerShellCommand.runSetFixToUserRotation`).
  So there the server runs `cmd window set-fix-to-user-rotation -d N disabled` itself,
  and only on a display whose user rotation it finds frozen. Any freeze counts, not only
  the computer's (which always freezes it); run on every display, the command would leave
  each one a setting of its own. An Android 10 desktop therefore leaves `disabled` behind,
  one setting that makes the next display follow its apps' orientation, as a phone's does
  anyway (unless desktop mode is forced on external displays: there a display's default
  is fixed, `mDefaultFixedToUserRotation`). Found from source in a review on 2026-11-18.
  No Android 10 phone was run.
- **The freeze comes off last** (a third review, 2026-11-18: a failed command used to thaw
  anyway). Fixed to the user's rotation alone still holds a display at its rotation
  (`DisplayRotation.rotationForOrientation` returns the user's rotation before anything
  else, on 10 and 11), and on Android 10 the freeze is all the next display can look for.
  So the server thaws only once the fixed half is off, or the window manager refused the
  command: `cmd` exits 255 when its shell command returned -1 or threw, the same at every
  try, and the computer's own `enabled` would have been refused too. When `cmd` can't be
  run, or can't reach the window manager (it exits 20, or with a binder error), the hold
  stays on, with a warning, for the next display to take off. On Android 11 the shell
  command (`default`) stands in for the window manager's call if that call is missing.
- Those branches were run on the Redmi, 17:00 to 17:02, with a test build: the real one
  plus switches, files in `/data/local/tmp` that force the Android 10 branch, a command
  that can't be run, a refused value, a missing service, or a missing Android 11 call:
  - Android 10 branch: × took the hold off, 226 to 205 bytes (the command took about
    65 ms); the next display was free.
  - Command that can't be run: "left on, for the next display made here to take off", 226
    kept. The next display came up held (`USER_ROTATION_LOCKED`, fixed). It took the hold
    off once the command could run: 205, and the display after it was free.
  - `cmd` sent to a missing service (exit 20): 226 kept, and the next display took it off.
  - Refused value (exit 255): "The window manager refused…", thawed, and the fixed half
    stayed (`mFixedToUserRotation=true` on the next display), as designed.
  - Android 11 with the call missing: the shell's `default` instead, 226 to 181. With the
    command also unable to run, 226 kept, and the real build's next display took it off.
  - The real build put the file back at 181 after each.
- The app's phone windows are not affected: their displays have a name of their own,
  "Open Android DeX app", so their identity is another one.
- The taskbar icon's Close app ran in the Redmi's own session only (`request-pump
  [<Redmi over Wi-Fi>|desktop] close request: com.android.vending`) and closed Play Store;
  before the port change it sat in the queue ("4 pending") and the icon came back. The
  bar's ◁ closed Play Store through the same queue.
- Its wireless adb dropped twice in five minutes (adbd: `SSL_read failed`), and the app
  reopened the desktop by itself once; `adb connect` to its TLS port brought it back.

## Two desktops at once

Each phone's daemon now has a computer port of its own (`wm::host_port`: the first phone
7191 as before, the next ones 7190, 7189…), and every per-session client (enforcer,
request pump, pointer shapes, touchpad gestures, exit, daemon start) uses its phone's.
With one port for all, the phone connected last took it: on 2026-11-17 one "close
com.sec.android.app.myfiles" request ran in two sessions at once (`request-pump [both
serials] close request`) and force-stopped My Files on the S25 too, and a Redmi's taskbar
Close did nothing, its pump reading another phone's queue. The help menu's Back to the
desktop and Close the top window still name no phone: they act on the phone connected
last. Run 2026-11-17 23:02–23:08 with the A13 and the Redmi open together: `adb forward
--list` had the A13 (USB) on `tcp:7191` and the Redmi (Wi-Fi) on `tcp:7189`, and the
A13's pump ran nothing of the Redmi's.

## Not verified, and known gaps

- macOS: the bar does not exist there.
- Android 10: first run on 2026-11-24 (Redmi Note 7, MIUI 12.5), and every window
  stayed full screen, under the taskbar. The enforcer saw no windows: Android 10
  prints `* TaskRecord{#id …}` under `Stack #N: type=… mode=…` lines, with the
  task's rect before its header and visibility on its activities. The parser now
  reads that (`parse_section`, test `reads_android_10_tasks_out_of_their_stacks`).
  It could not make a window either: `am start --windowingMode 5` only re-delivers
  the intent to a task that exists, and `am task resize` refuses one that is not
  freeform. The window daemon's `WINMODE <task> 5` (`setTaskWindowingMode`, PC only)
  does it. Android then puts its own rect on the window afterwards, so the resize is
  `freeform_then_resize`'s ladder. Android 10's own ▢ makes a task full screen at
  every press, so there a press on a window already maximized restores it. Run on
  the Redmi: full screen → window, ▢ → maximized above the taskbar, ▢ → restored,
  ▢ → maximized. Not run: a portrait-locked app, which gets a landscape window
  (Android 10 prints no requested orientation). Android 11 and later are unchanged.
- MIUI's freeform windows on that Redmi are MIUI's own floating windows
  (`MiuiFreeFormGestureController`): a grey pill handle and no ✕. Below Android 14
  the caption service draws no bar either, so nothing closed our own windows (the
  file transfer window). Their taskbar tiles now have Close window on right-click
  or long-press (`closeOwnWindowNamed`: the windows that ask first are asked, any
  other has its AppTask removed). Third-party windows already had Close in their
  taskbar menu.
- On the Galaxy A13 (Android 12) every taskbar menu opened UNDER the app windows: on
  its untrusted display overlays draw under apps, so the taskbar is inside the desktop
  activity and a popup of it is a sub-window of that, the bottom of the stack. There a
  tile's menu is now a window of its own (`TaskbarMenuActivity`, through
  `showTileMenu` where `overlaysBuried()`): freeform, as One UI keeps every freeform
  window above every full-screen one; started without naming the display, which the
  launcher may not; see-through but for its card, because One UI grows a freeform
  window to its minimum (440 px square) and draws its handle over the top middle; put
  away when it answers or loses the focus (windows side by side are all resumed, so
  pausing is not enough). Run on the A13 with the window daemon's mouse: the file
  transfer tile's Close window closed it, Gallery's menu came up over Gallery with its
  three items, a click on the desktop put it away, and Maximize / restore maximized
  Gallery to 2828x1414 over the taskbar. One UI still outlines the menu's whole
  window, a faint frame larger than the card.
- The app drawer had the same trouble there, from before (goHome, 2026-11-17: the
  desktop cannot be raised over a window): opened inside the desktop, it was behind the
  Gallery window over it. Now opening it puts the windows on screen away, as Show
  desktop does, and closing it with nothing opened brings exactly those back
  (CaptionService `clearForDrawer` / `backFromDrawer`). An app opened from it leaves the
  others on the taskbar, as after Show desktop on a computer: brought back, they would
  be raised over it. Run on the A13: Apps with Gallery open → drawer, Gallery away;
  Apps again → Gallery back where it was; Clock from the drawer → Clock in front,
  Gallery on the taskbar; its tile → Gallery back.
- An update of the launcher can cost it the overlay permission on MIUI after the
  computer has given it: on the Redmi Note 7 (2026-11-24, 20:49) the grant read back
  "allow", Android un-granted it as the install finished, and the launcher started three
  seconds later without it — its taskbar inside the desktop, under every window, for
  the session. The computer now looks again 2.5 and 6.5 s on and grants it again
  (adb.rs), and the launcher keeps looking for the permission for two minutes, not ten
  seconds, also after the caption service re-seats the bar. The next update on the
  Redmi: the grant's own retry caught it ("took 2 attempts"), and the bar was an
  overlay. Run there the same day: a full-screen app becomes a window above the
  taskbar, ▢ maximizes and restores, the taskbar menu is the popup above windows and
  its Maximize works, the drawer opens over windows without putting them away, Close
  window closes the file transfer window, and a click on a window behind brings it in
  front as drawn. MIUI again removed a Settings window when another app opened as a
  floating window.
- A window clicked into was put on top in the window manager's order, but MIUI
  kept the one before drawn over it: Settings first, file transfer's layer above
  (`dumpsys SurfaceFlinger`: z 5 over 3), and the click going to the window that
  could not be seen. The enforcer now raises a window that was already on the
  desktop when it comes in front (`front_pass`, the daemon's `TOFRONT`, which is
  `moveTaskToFront`). Run on the Redmi with taps on two windows, both ways: the
  two orders agreed each time, and the screen showed the window tapped.
- Galaxy S25 Ultra (Android 17, One UI 9), 2026-11-24, with the Mac's mouse. Its
  overlays draw above windows, so the taskbar's menus are the plain popup and the
  drawer opens over the windows without putting them away. Run: a tile's menu over a
  maximized window, a click on the taskbar putting it away, Close window on the file
  transfer tile. Two things found there and changed:
  - The menu's Maximize / restore resized the window and left it where it was in the
    order: Gallery, restored behind a maximized My Files, could not be seen. The
    enforcer now also raises the window it resized (`window_pass`, the daemon's
    `TOFRONT`). Run: Gallery behind My Files → maximized and in front; again →
    restored, still in front. Not run on Android 10 to 12.
  - Our own windows' first rect was centred on the whole display: File transfer ran
    from y 76 to 1442 on the 1518 px desktop, 28 px under the taskbar (from 1414).
    `desktopWindowRect` now centres it in the room above the taskbar, as an app's
    window is. Run: Web viewer opened at y 71 to 1343. A window put back where it was
    left already stayed above the taskbar (`WindowMemory.recall`).
- Also seen on the Redmi, not changed: MIUI removed a Settings window (task
  gone) when Calculator opened as a floating window, likely its small-window
  blacklist (`MiuiMultiWindowAdapter::getFreeformBlackList`). And while Settings
  is open anywhere, Android hides the taskbar: below Android 14 it is an app
  overlay, and Settings' window has `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`
  (`mForceHideNonSystemOverlayWindow=true` on the taskbar). `wm overscan` on the
  desktop display does keep full-screen apps above the taskbar, but it moved the
  taskbar up with them; tried and reset, not used.
- The rotation hold's failure branches were forced in a test build; none was met for real.
- The enforcer's `wm set-display-windowing-mode -d N 5` leaves nothing for the next
  display. A review (2026-11-18) read in the Android 12 source that a display's windowing
  mode is kept under its identity, but nothing sets it there: AOSP 12, 13, 14 and 15 (r1)
  have no such shell command, the A13 answers "Unknown command", and every A13 and Redmi
  desktop logged `default_freeform=false`. Checked on 2026-11-18 after a day of A13
  desktops: a bare display with their identity came up `mWindowingMode=fullscreen`, and
  the A13's settings file stayed at 63 bytes. Where the command exists (the S25, Android
  17), Android keeps no virtual display's settings (since a late Android 14:
  `mVirtualDisplayIdentifiers`). Not run: an OEM build that added the command to
  Android 13 or an early 14.
- ▢ on the A13 with an app full screen under a window: the full-screen app cannot be
  raised over a freeform window there, so ▢ picks the window that is already in front.
