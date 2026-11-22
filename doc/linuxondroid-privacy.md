# LinuxOnDroid privacy policy

Effective 22 November 2026

LinuxOnDroid puts an Ubuntu Linux desktop on your Android phone. This page explains what the app does with your data. The short version: it collects nothing about you, and everything you do in Linux stays on your phone unless you send it somewhere yourself.

## What we collect

Nothing. LinuxOnDroid has:

- No account and no sign-in
- No ads
- No analytics, tracking or crash reporting
- No server of our own that it talks to

We never see your files, what you type, what you install or how you use the app.

## What stays on your phone

Ubuntu, the apps you add and everything you create in Linux are stored in the app's own private storage on your phone. Other apps can't read it.

- Uninstalling LinuxOnDroid deletes all of it
- Uninstall Linux in the app's menu deletes it too, without removing the app

## Connections the app makes for you

LinuxOnDroid only goes online to do what you asked for. We don't add anything about you to these connections. Like any download, the servers involved see your phone's internet (IP) address, and their own privacy policies apply.

- **Installing Linux.** Ubuntu comes from Canonical's servers (cdimage.ubuntu.com, ports.ubuntu.com and archive.ubuntu.com)
- **Apps you choose to add.** Each comes from where its makers publish it: Firefox from Mozilla (packages.mozilla.org), Chromium from the xtradeb package archive on Launchpad (ppa.launchpadcontent.net, with its key from keyserver.ubuntu.com), Visual Studio Code from Microsoft (update.code.visualstudio.com), Node.js from NodeSource (deb.nodesource.com), IntelliJ IDEA from JetBrains (data.services.jetbrains.com), and GIMP and Git from Ubuntu
- **Inside Linux.** Programs you run in Linux, such as a web browser or a package update, connect wherever you point them. LinuxOnDroid does not watch, log or collect that traffic

## Remote access (off unless you turn it on)

Remote access lets computers you pair log in to your phone's Linux, for example with SSH. It stays off until you turn it on in the app, and turning it off stops all of the below.

- Only computers you pair with a code shown on your phone can connect
- Connections are end-to-end encrypted. Nobody in between can read them, including us
- To let your computers find your phone, the app publishes your phone's connection key and relay address to a lookup service run by n0, the makers of the iroh networking software it uses
- When your phone and computer can't connect directly, the encrypted traffic passes through n0's public relay servers, which can't read it
- n0's services see your phone's IP address, as any internet service does

## Open in a browser (off unless you turn it on)

Open in a browser shows your phone's Linux desktop in a browser on a computer on the same Wi-Fi.

- Only a browser that types the code shown on your phone gets in
- The connection goes straight from your phone to that browser over your Wi-Fi, encrypted, unless you choose to continue without encryption when your browser refuses the phone's certificate. Nothing passes through us or anyone else

## Remote desktop (off unless you turn it on)

Remote desktop lets computers you allow see and use this phone: its screen, its sound if you choose, and taps and typing if you turn on control.

- Only computers you allow can connect: a browser that types the code shown on your phone, or a computer you pair in the LinuxOnDroid desktop app
- The screen, the sound and what you type go only between your phone and that computer, end-to-end encrypted: directly between them on the same Wi-Fi (unless you choose to continue without encryption there, as above), and from elsewhere through the desktop app's encrypted connection, described under Remote access. Nobody in between can read them, including us
- Nothing is recorded or stored, and nothing is sent to us
- **Screen sharing.** Android asks you before the screen is shared, and a notification shows for as long as it is. It stops when you tap Stop sharing, when you turn Remote desktop off, or, for a computer given unattended access, shortly after the last computer leaves
- **Control.** Only if you turn it on in Android's accessibility settings, after the app explains what it does. A computer you allow can then tap, scroll and type on the phone. While a computer is connected, the app also reads where the lock screen's keys are, so you can type your PIN from the computer (it never reads or keeps your PIN), and can send screenshots when live video is off
- **Sound.** Only when you choose it on the computer, the app captures what other apps play, with Android's playback capture, and sends it to that computer. The microphone is never used
- **Files.** The version of LinuxOnDroid from GitHub also lets a computer's browser download files from the phone and upload files to it, after you allow access to the phone's files. The version on Google Play has no access to your files
- **Cloudflare link.** The version from GitHub can also give you a link through Cloudflare's tunnel service. Through that link, the screen and what you type pass through Cloudflare, and Cloudflare's privacy policy applies. The version on Google Play has no such link

## Email me when an address changes (off unless you turn it on)

- If you turn it on, the phone emails you the addresses you use to reach it, and a way to sign in, when they change
- The email goes through the email account you enter, to the address you enter. We never receive it
- Your email password stays on the phone, encrypted with a key that never leaves the phone

## Permissions

- **Internet and network state.** To download Linux and your apps, and for remote access
- **Foreground service and notifications.** To keep Linux running while you use other apps, with a notification so you always know it's running and can get back to it
- **Run at startup.** Only for things you switch on: to start Linux when your phone starts, if you turn on Start Linux with the phone in Settings, and to turn remote access back on after a restart if you had it on
- **Stay awake.** While Linux is being installed, so the download and setup can finish with the screen off, and while a computer is connected, to keep its session alive
- **Battery optimization.** When you turn on Remote desktop or Remote access, the app can ask Android not to pause it, so your computers can still reach the phone when its screen is off. You decide
- **Screen capture.** For Remote desktop's screen sharing, only after Android asks you, with a notification while it lasts
- **Accessibility.** For Remote desktop's control, only if you turn it on in Android's settings
- **Record audio.** Android requires it to capture the sound other apps play, for Remote desktop's sound. The microphone is never used
- **Change audio settings.** To keep the phone quiet while its sound plays on the computer, if you choose that

## Children

LinuxOnDroid is a tool for adults and isn't directed at children.

## Changes to this policy

If this policy changes, the new version will be posted on this page with a new date.

## Contact

Write to the email address on LinuxOnDroid's Google Play page, or open an issue at https://github.com/dotnetdreamer/open-android-dex/issues

LinuxOnDroid is free software under the GNU General Public License version 3. Its source code is at https://github.com/dotnetdreamer/open-android-dex
