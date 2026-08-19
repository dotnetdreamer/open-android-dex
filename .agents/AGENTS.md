# Agent Guide

Open Android DeX is an original project: the PC app, the launcher shell, the
window daemon and both guest environments are all written here. Nothing in this
repository is derived from another product's source.

## Architecture rules

These are settled and expensive to relitigate — see `doc/custom-titlebar-v2.md`
for the measurements behind them.

- **Privilege separation is fixed.** `openandroiddex-wmd` (uid 2000) is the sole
  holder of `MANAGE_ACTIVITY_TASKS`. It does task enumeration, bounds, z-order,
  focusability and caption-strip reservation — no UI, no policy. All policy and
  rendering live in `CaptionService` in the launcher APK.
- **Window chrome is a child of the task's own caption window**
  (`attachAccessibilityOverlayToWindow`). The two alternatives were built and
  measured worse: inside the app window it is invisible below One UI's caption
  at z=30000; as a display-level overlay it needs manual repositioning with
  visible drag lag.
- **`applyTransaction(setBounds)` is the per-frame drag mover** (~2.5 ms, skips
  the transition queue). `startNewTransition` / the `RESIZE` verb is for one-shot
  user resizes, so bounds and caption inset land in one relayout.
- **Launcher → PC commands must not ride the `content query` queue** (~990 ms per
  round; it boots a JVM per invocation). Use the resident daemon on loopback TCP
  7191 (~0.35 ms).
- **One shared desktop display, real Android freeform windows.** Per-app virtual
  displays are out of scope: they hit the encoder-instance ceiling, break
  cross-app drag and drop, and would delete the launcher.

## UI rules

- The UI reads CSS variables from `open-android-dex-tauri/src/index.css` —
  `--glass-blur`, `--surface-alpha`, `--item-radius`, `--accent` — driven live by
  the settings surface. Change those, not hard-coded values.
- The taskbar is fixed at 52dp, and "maximized" must not fill the display exactly.
- **A titlebar and its window must never visibly separate.** A trailing window
  reads as a slow machine; detached chrome reads as broken.

## New / existing logic changes

- Read the relevant design record in `doc/` before writing new window-management
  logic. `doc/custom-titlebar-v2.md` is the current one; `custom-titlebar.md` and
  `custom-titlebar-proposal.md` are superseded, kept for the reasoning.
- Competitive analysis of a third-party closed-source product, and the decompiled
  sources behind it, are kept **locally only** and are deliberately untracked (see
  `.gitignore`). They are reference material about someone else's app, not prior
  art for ours — never copy from them, never let them set our design, and never
  commit them. If you do not have them locally, you are not missing anything you
  need.

## Testing

- Run the app with `npm run tauri dev` from `open-android-dex-tauri/`. That is the
  only flow that stages the payloads and applies permissions in the order that
  matters — never hand-launch scrcpy or sideload the APK to test.
- Always kill any server or port you launch when you are done.
