import { platform } from "@tauri-apps/plugin-os";

/**
 * Which desktop this build is running on.
 *
 * Synchronous on purpose. The OS plugin injects the answer into the webview
 * before any script runs, so this can be read at module scope and used in the
 * first render — the titlebar's layout depends on it, and resolving it through
 * an `invoke` would mean painting the Windows caption buttons for a frame and
 * then taking them away again on a Mac.
 */
export const IS_MAC = platform() === "macos";

/**
 * Windows, specifically — not merely "not a Mac".
 *
 * There are three hosts now, and the two questions the UI asks are different
 * ones: "does the system draw the window controls?" (only macOS does, so
 * `!IS_MAC` is still the right test there) and "does this host have the
 * feature at all?", which `!IS_MAC` answers wrongly on Linux. Miracast is the
 * live case — a Windows-only receiver whose tab was drawn for anything that
 * was not a Mac, which on Linux is a tab that leads to a dead end.
 */
export const IS_WINDOWS = platform() === "windows";

/** Neither Windows nor macOS: a Linux desktop. */
export const IS_LINUX = platform() === "linux";

/**
 * What to call the machine the app is running on, in a sentence.
 *
 * The UI copy said "this PC" throughout, which is the ordinary word on Windows
 * and jarring on a Mac.
 */
export const THIS_COMPUTER = IS_MAC ? "this Mac" : "this PC";

/** The same, for the start of a sentence. */
export const THIS_COMPUTER_CAP = IS_MAC ? "This Mac" : "This PC";

/**
 * What to do when adb can see the phone on the cable but is not allowed to
 * open it (`adb.rs` reports this as the `no-permissions` state).
 *
 * Almost exclusively a Linux answer. USB devices there are owned by root
 * until a udev rule says otherwise, and a fresh install has no rule for a
 * phone in ADB mode — so the phone appears, is unusable, and says nothing
 * about why. Ubuntu and Debian ship the rules in a package of their own;
 * naming it is the difference between a dead end and one command. The replug
 * is not optional: udev applies rules when a device appears, so a phone that
 * was already plugged in keeps the permissions it was given.
 */
export const NO_PERMISSIONS_HINT = IS_LINUX
  ? "Install android-sdk-platform-tools-common, then unplug the phone and plug it back in."
  : "Unplug the phone and plug it back in.";

/**
 * What to try when adb reports that it cannot discover phones on the network.
 *
 * The three hosts fail this differently and the remedies do not overlap. On
 * Windows it is almost always a firewall rule that was never created; on macOS
 * 15 and newer the first multicast attempt raises a Local Network prompt, and
 * an app that was denied it — or was never in the foreground when it appeared —
 * simply sees an empty network with no error to report. On Linux there is no
 * permission to grant: discovery is a plain multicast that a host firewall
 * either passes or drops, and the default profile of both common firewalls
 * drops it.
 *
 * Deliberately not advice about avahi. adb has carried its own mDNS stack
 * (openscreen) for years and does not use the system daemon, so "start
 * avahi-daemon" is the answer to a different question and sends people to a
 * service that changes nothing here.
 */
export const MDNS_HINT = IS_MAC
  ? "Check System Settings → Privacy & Security → Local Network and make sure Open Android DeX is switched on."
  : IS_LINUX
    ? "Check that your firewall is not blocking mDNS on UDP port 5353."
    : "";

/**
 * Width to keep clear at the left of the titlebar for the system's own window
 * controls.
 *
 * macOS draws the traffic lights itself (`titleBarStyle: "Overlay"` in
 * tauri.macos.conf.json) and they float over the webview, so the page has to
 * leave room. Windows and Linux draw no such thing — both run with
 * `decorations: false`, so the caption buttons there are ours, on the right.
 */
export const TITLEBAR_LEAD = IS_MAC ? "pl-[76px]" : "pl-3";
