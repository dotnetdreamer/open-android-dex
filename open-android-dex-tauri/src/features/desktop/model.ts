import type { DeviceInfo } from "../../lib/types";

/** Where the desktop flow is. */
export type Phase =
  | { kind: "boot" }
  | { kind: "connect"; error?: string }
  | { kind: "launching"; device: DeviceInfo }
  | { kind: "ready"; device: DeviceInfo }
  /** `requested` = the desktop asked to be closed, rather than being closed on it. */
  | { kind: "ended"; device: DeviceInfo; requested: boolean };

/** Why a launch is happening, which is what its screen says it is doing. */
export type LaunchMode =
  /** A first launch, or one asked for. */
  | "open"
  /** The phone undid the desktop while it was unplugged; it is being put back. */
  | "again"
  /** The in-desktop Settings changed a stream setting; the session is cycled. */
  | "settings"
  /** The session moved off the cable onto Wi-Fi. */
  | "wireless";

/**
 * How far a launch has got: linked (0), the display is being made (1), the
 * launcher is going onto it (2), done (3). `waiting` is set while the phone has
 * dropped off adb part way through and the launch is waiting for it.
 */
export interface Progress {
  step: 0 | 1 | 2 | 3;
  mode: LaunchMode;
  waiting?: boolean;
}

export type AdbState = { state: "busy" } | { state: "done" } | { state: "error"; error: string };

/**
 * The phone as a person knows it: its own Device name ("Galaxy S25 Ultra"),
 * else its brand and model ("Samsung SM-S938B"). Never the serial.
 */
export function deviceName(d: DeviceInfo): string {
  if (d.name) return d.name;
  const brand = d.brand ? d.brand.replace(/^([a-z])/, (c) => c.toUpperCase()) : "";
  const named = [brand, d.model].filter(Boolean).join(" ");
  return named || "Your phone";
}

/** How the phone is attached, in words. */
export function routeName(d: DeviceInfo): string {
  return d.connection === "wifi" ? "Wi‑Fi" : "USB cable";
}
