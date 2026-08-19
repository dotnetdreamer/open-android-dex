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
 * What to call the machine the app is running on, in a sentence.
 *
 * The UI copy said "this PC" throughout, which is the ordinary word on Windows
 * and jarring on a Mac.
 */
export const THIS_COMPUTER = IS_MAC ? "this Mac" : "this PC";

/** The same, for the start of a sentence. */
export const THIS_COMPUTER_CAP = IS_MAC ? "This Mac" : "This PC";

/**
 * What to try when adb reports that it cannot discover phones on the network.
 *
 * The two hosts fail this differently and the remedies do not overlap. On
 * Windows it is almost always a firewall rule that was never created; on macOS
 * 15 and newer the first multicast attempt raises a Local Network prompt, and
 * an app that was denied it — or was never in the foreground when it appeared —
 * simply sees an empty network with no error to report.
 */
export const MDNS_HINT = IS_MAC
  ? "Check System Settings → Privacy & Security → Local Network and make sure Open Android DeX is switched on."
  : "";

/**
 * Width to keep clear at the left of the titlebar for the system's own window
 * controls.
 *
 * macOS draws the traffic lights itself (`titleBarStyle: "Overlay"` in
 * tauri.macos.conf.json) and they float over the webview, so the page has to
 * leave room. Windows draws no such thing: the caption buttons there are ours,
 * on the right.
 */
export const TITLEBAR_LEAD = IS_MAC ? "pl-[76px]" : "pl-3";
