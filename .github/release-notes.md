### Which download

| Your machine | File |
| --- | --- |
| Windows | `..._x64-setup.exe` (installer) or `..._x64_portable.zip` |
| Mac, Apple Silicon (M1–M4) | `..._aarch64.dmg` |
| Mac, Intel | `..._x64.dmg` |

Not sure which Mac you have?  → About This Mac, and read the "Chip" line.

### macOS: opening it the first time

Open Android DeX is not notarized — that needs a paid Apple Developer account
— so macOS will not open it until you say so once.

1. Open the `.dmg` and drag the app into **Applications**.
2. Open Terminal and run:

   ```
   xattr -dr com.apple.quarantine "/Applications/Open Android DeX.app"
   ```

   The `-r` matters: the app ships `adb` and `scrcpy` inside it, and those are
   quarantined separately. Without it the app opens and then fails to talk to
   your phone.
3. Open it normally.

If you would rather not use Terminal: double-click the app, dismiss the
warning, then go to **System Settings → Privacy & Security**, scroll down to
**Security**, and press **Open Anyway**. (The old right-click → Open trick
stopped working in macOS 15.)

### macOS: two permissions it will ask for

- **Local Network** — needed to find phones over Wi-Fi. macOS asks the first
  time you use a wireless connect option. Without it the Wi-Fi routes find
  nothing; the USB cable is unaffected.
- **Accessibility** — only for the fullscreen (⛶) button on the phone-side
  taskbar, which has to resize a window belonging to `scrcpy`. Everything else
  works without it. Grant it under **System Settings → Privacy & Security →
  Accessibility**.

  Because the app is signed ad-hoc rather than with a developer certificate,
  macOS ties this permission to that exact build — after installing a new
  version you may have to switch Open Android DeX off and on again in that
  list.
