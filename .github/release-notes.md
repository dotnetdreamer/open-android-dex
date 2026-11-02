<!-- The release job in .github/workflows/desktop.yml fills in the version and repository placeholders, so every file name and link below is exact. -->

### Download for your computer

The computer app sets up your phone for you, so this is the only file you need.

| Your computer | Download |
| --- | --- |
| Windows | [`Open.Android.DeX_<version>_windows_x64-setup.exe`][windows] |
| Windows, without installing | [`Open.Android.DeX_<version>_windows_x64_portable.zip`][windows-portable] |
| Mac with an Apple chip (M1 or newer) | [`Open.Android.DeX_<version>_macos_apple-silicon.dmg`][macos-apple-silicon] |
| Mac with an Intel chip | [`Open.Android.DeX_<version>_macos_intel.dmg`][macos-intel] |
| Linux: Ubuntu, Debian, Mint | [`Open.Android.DeX_<version>_linux_amd64.deb`][linux-deb] |
| Linux: Fedora, openSUSE | [`Open.Android.DeX_<version>_linux_x86_64.rpm`][linux-rpm] |
| Linux: any other | [`Open.Android.DeX_<version>_linux_amd64.AppImage`][linux-appimage] |

- **Not sure which Mac?** Apple menu → **About This Mac**. "Apple M1", "M2" and so on mean an Apple chip.
- **Linux** runs on 64-bit Intel and AMD computers only, for now.

### No computer? Install it on your phone

Download one of these on the phone and open it. If Android asks, allow **Install unknown apps**.

| You want | Download |
| --- | --- |
| The desktop, on the phone's screen, a monitor or Samsung DeX | [`OpenAndroidDeX-Launcher-v<version>.apk`][android-launcher] |
| Just Ubuntu Linux on the phone | [`LinuxOnDroid-v<version>.apk`][android-linuxondroid] |

### Get started with a computer

1. Install (or unzip) the app and open it.
2. On your phone, turn on **USB debugging**: Settings → About phone → tap **Build number** 7 times, go back, open **Developer options** and switch on **USB debugging**.
3. Plug the phone in and tap **Allow** on it. The desktop opens by itself.

### Mac: if it won't open the first time

macOS blocks apps that Apple has not checked (that needs a paid developer account). Allow this one once:

1. Open the `.dmg` and drag the app into **Applications**.
2. Open **Terminal**, paste this and press Return:

   ```
   xattr -dr com.apple.quarantine "/Applications/Open Android DeX.app"
   ```

3. Open the app as usual.

If macOS asks for **Local Network** (finds your phone over Wi-Fi) or **Accessibility** (full screen only), allow it. If full screen stops working after an update, switch Open Android DeX off and on again in **System Settings → Privacy & Security → Accessibility**.

### Linux

Open a terminal in the folder you saved the file to, and run the commands for your system.

**Ubuntu, Debian, Mint**

```
sudo apt install ./Open.Android.DeX_<version>_linux_amd64.deb
```

**Fedora, openSUSE** (on openSUSE, type `zypper` instead of `dnf`)

```
sudo dnf install ./Open.Android.DeX_<version>_linux_x86_64.rpm
```

**Any other Linux**

```
chmod +x Open.Android.DeX_<version>_linux_amd64.AppImage
./Open.Android.DeX_<version>_linux_amd64.AppImage
```

**Phone shows up but is not allowed to connect?** On Ubuntu, Debian or Mint, run this, then unplug the phone and plug it back in:

```
sudo apt install android-sdk-platform-tools-common
```

[windows]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_windows_x64-setup.exe
[windows-portable]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_windows_x64_portable.zip
[macos-apple-silicon]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_macos_apple-silicon.dmg
[macos-intel]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_macos_intel.dmg
[linux-deb]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_linux_amd64.deb
[linux-rpm]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_linux_x86_64.rpm
[linux-appimage]: https://github.com/<repo>/releases/download/v<version>/Open.Android.DeX_<version>_linux_amd64.AppImage
[android-launcher]: https://github.com/<repo>/releases/download/v<version>/OpenAndroidDeX-Launcher-v<version>.apk
[android-linuxondroid]: https://github.com/<repo>/releases/download/v<version>/LinuxOnDroid-v<version>.apk
