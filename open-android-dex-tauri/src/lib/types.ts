export interface DeviceInfo {
  serial: string;
  state: string; // "device" | "unauthorized" | "offline" | ...
  model: string;
  product: string;
  connection: "usb" | "wifi";
  brand?: string | null;
  androidVersion?: string | null;
  /**
   * ro.serialno — the same string whichever way the phone is attached, unlike
   * `serial`. It is how the same phone on the cable and on Wi-Fi is recognised
   * as one device.
   */
  hardwareSerial?: string | null;
}

// ── Wireless ────────────────────────────────────────────────────────────

/** A phone that has been reached over Wi-Fi. */
export interface WirelessResult {
  /** The serial to use from here on — `adb -s <this>`. */
  serial: string;
  address: string;
  label: string;
}

/** A phone we have connected to wirelessly before. */
export interface KnownDevice {
  label: string;
  address: string;
  guid?: string | null;
  hardwareSerial?: string | null;
}

/** One row of `adb mdns services`. */
export interface MdnsService {
  instance: string;
  service: string;
  address: string;
  port: number;
}

/**
 * Whether adb's mDNS daemon is up. Both pairing flows need it to find the
 * random port the phone chose, so this decides whether they are offered.
 */
export interface WirelessSupport {
  mdns: boolean;
  detail: string;
}

/**
 * Whether this PC's Wi-Fi stack can act as a Miracast receiver at all.
 * `null` = could not be read (a localised Windows names the line differently),
 * which is not the same as "no".
 */
export interface ProjectionSupport {
  miracast: boolean | null;
  detail: string;
}

/** A pairing QR, as a matrix for the connect screen to draw itself. */
export interface QrChallenge {
  /** Square side, in modules. */
  width: number;
  /** Row-major, `true` = dark. */
  modules: boolean[];
}

/** Options passed to the Rust start_mirror command. */
export interface MirrorOptions {
  serial: string;
  windowTitle: string;
  maxSize: number; // 0 = native resolution
  videoBitRateMbps: number; // 0 = scrcpy default
  maxFps: number; // 0 = scrcpy default
  audio: boolean;
  stayAwake: boolean;
  /**
   * scrcpy --keep-active: keep the session's display from timing out, on
   * battery as well as on a charger (stayAwake only holds while charging).
   */
  keepActive?: boolean;
  turnScreenOff: boolean;
  alwaysOnTop: boolean;
  fullscreen: boolean;
  autoReconnect: boolean;
  appPackage?: string | null;
  newDisplay?: string | null;
  vdNoDecorations?: boolean;
  windowBorderless?: boolean;
  audioPlayback?: boolean;
  /** Desktop sessions: auto-convert fullscreen launches into freeform windows. */
  freeform?: boolean;
  /** scrcpy --mouse-bind value (forward right-click for launcher context menus). */
  mouseBind?: string | null;
  /**
   * scrcpy --mouse mode: "sdk" (default) or "uhid".
   *
   * Which side draws the pointer. Under "sdk" Android renders no cursor at all
   * and the PC's own floats over the video; under "uhid" Android draws it into
   * the stream, which is what makes Settings → Mouse & cursor visible. Owned by
   * the in-desktop Settings window — Rust overrides this from its own store
   * before every spawn, so the value here is only the default for a device that
   * has never configured it.
   */
  mouseMode?: string | null;
  /**
   * Stream settings the in-desktop Settings window owns. Rust overrides these
   * from its own store before every spawn, so the values sent from here are
   * only the defaults for a device that has never configured them.
   */
  videoCodec?: string | null;
  videoEncoder?: string | null;
  clipboardAutosync?: boolean;
}

export interface MirrorEvent {
  sessionKey: string;
  serial: string;
  appPackage?: string | null;
  status: "running" | "reconnecting" | "stopped";
  pid?: number | null;
  exitCode?: number | null;
  intentional: boolean;
}

export interface SessionInfo {
  sessionKey: string;
  serial: string;
  appPackage?: string | null;
  pid: number;
  displayId?: number | null;
}

export interface DisplayEvent {
  sessionKey: string;
  serial: string;
  displayId: number;
}

/**
 * A session that could not start at all — emitted instead of retrying
 * forever, which is what a phone that cannot create the virtual display used
 * to get.
 */
export interface MirrorFailure {
  sessionKey: string;
  serial: string;
  /** Plain-language cause, derived from scrcpy's own output. */
  reason: string;
  /** The last lines scrcpy printed, for the log/report. */
  detail: string;
}

/** Something worth saying that did not stop the session. */
export interface MirrorNotice {
  sessionKey: string;
  text: string;
}

// ── Phone Linux over SSH (remote.rs) ────────────────────────────────────

/** A phone this computer is paired with, for SSH into its Linux. */
export interface RemotePhone {
  /** Its endpoint id: how it is dialled, from any network. */
  id: string;
  name: string;
  /** The `Host` in ~/.ssh/config: what goes after `ssh`. */
  alias: string;
  user: string;
  pairedAt: number;
  via: "code" | "usb" | string;
  sshCommand: string;
  /** Straight after a USB pairing: what still stands between it and a login. */
  note?: string;
}

/** One probe of a paired phone. */
export interface RemoteProbe {
  reachable: boolean;
  /** The path in use goes straight to the phone, not through a relay. */
  direct: boolean;
  rttMs: number;
  /** The guest sshd: up, starting, down, failed, missing, installing. */
  ssh: string;
  sessions: number;
  /** Why it could not be reached, as a sentence. */
  error?: string | null;
}

/** What the launcher on a plugged-in phone says about remote access. */
export interface RemoteUsbStatus {
  ok: boolean;
  /** The phone can run Linux, so it can do remote access. */
  available: boolean;
  enabled: boolean;
  name: string;
  /** ready | installing | missing | failed | ssh-failed */
  linux: string;
  id: string;
  /** This computer is already paired with it. */
  pairedHere: boolean;
}
