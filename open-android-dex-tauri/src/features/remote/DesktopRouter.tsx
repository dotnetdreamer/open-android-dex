import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import type { RemotePhone } from "../../lib/types";
import { DesktopWindow } from "./DesktopWindow";
import { UsbDesktop } from "./usb/UsbDesktop";

/**
 * A phone's desktop window, which reaches its phone one of two ways.
 *
 * - **Over adb** — the cable, or wireless debugging — as video from the
 *   phone's encoder (UsbDesktop, remote_usb.rs): a local desktop's frame
 *   rate and a pointer that never lags.
 * - **Through the tunnel**, anywhere else, with the phone's own viewer page
 *   (DesktopWindow, remote_desktop.rs).
 *
 * Adb wins whenever the phone is on it. A cable pulled mid-session drops the
 * window onto the tunnel — the desktop itself lives on the phone, so nothing
 * in it is lost — and while it is there the window watches for the phone to
 * come back onto adb, and moves to it.
 */

/** remote_usb.rs's answer. */
interface UsbLink {
  url: string;
  wireless: boolean;
}

type Route =
  | { kind: "probing" }
  | { kind: "usb"; url: string; wireless: boolean; start: boolean; key: number }
  | { kind: "tunnel"; key: number };

/** How often the window on the tunnel looks for its phone on adb. Cheap: no process, a few ms per phone. */
const WATCH_MS = 3000;
/**
 * A stream over adb that keeps ending moments after it began is not a cable
 * pulled; it is something the window cannot fix. After this many in a row it
 * stays on the tunnel, and does not look at adb again for a while.
 */
const QUICK_LOSS_MS = 10_000;
const QUICK_LOSSES = 3;
const ADB_REST_MS = 30_000;

/** This webview decodes H.264 with WebCodecs. */
async function canDecode(): Promise<boolean> {
  if (typeof VideoDecoder === "undefined" || typeof EncodedVideoChunk === "undefined") return false;
  try {
    const r = await VideoDecoder.isConfigSupported({ codec: "avc1.640033", optimizeForLatency: true });
    return r.supported === true;
  } catch {
    return false;
  }
}

export function DesktopRouter({ phoneId }: { phoneId: string }) {
  const [name, setName] = useState("the phone");
  const [route, setRoute] = useState<Route>({ kind: "probing" });
  const routeRef = useRef(route);
  routeRef.current = route;
  const lost = useRef<() => void>(() => {});

  useEffect(() => {
    let live = true;
    let n = 0;
    let video = false;
    let busy = false;
    let usbSince = 0;
    let quickLosses = 0;
    let adbRestUntil = 0;
    const toUsb = (link: UsbLink, start: boolean) => {
      usbSince = Date.now();
      setRoute({ kind: "usb", ...link, start, key: ++n });
    };

    invoke<RemotePhone[]>("remote_phones")
      .then((list) => {
        const phone = list.find((p) => p.id === phoneId);
        if (phone && live) setName(phone.name);
      })
      .catch(() => {});

    const overAdb = async (wake: boolean): Promise<UsbLink | null> => {
      if (!video) return null;
      try {
        return await invoke<UsbLink | null>("remote_desktop_usb", { id: phoneId, wake });
      } catch (e) {
        logError(`remote: desktop over adb: ${String(e)}`);
        return null;
      }
    };
    const viaTunnel = () => setRoute((r) => (r.kind === "tunnel" ? r : { kind: "tunnel", key: ++n }));

    const first = async () => {
      video = await canDecode();
      if (!video) logInfo("remote: this webview has no H.264 decoder for the desktop's video; using the tunnel");
      const link = await overAdb(true);
      if (!live) return;
      if (link) toUsb(link, true);
      else viaTunnel();
    };

    // The stream went without the phone saying why. Still on adb — its
    // service restarted, say — it comes straight back the same way; gone from
    // it, the cable pulled, it goes through the tunnel.
    lost.current = async () => {
      quickLosses = Date.now() - usbSince < QUICK_LOSS_MS ? quickLosses + 1 : 0;
      if (quickLosses >= QUICK_LOSSES) {
        logError("remote: the desktop over adb keeps dropping; staying on the tunnel for now");
        quickLosses = 0;
        adbRestUntil = Date.now() + ADB_REST_MS;
        viaTunnel();
        return;
      }
      // Quick losses wait a little longer each time before looking again.
      if (quickLosses) await new Promise((r) => window.setTimeout(r, 500 * 2 ** (quickLosses - 1)));
      const link = await overAdb(false);
      if (!live) return;
      if (link) toUsb(link, false);
      else viaTunnel();
    };

    const watch = window.setInterval(async () => {
      if (!video || busy || routeRef.current.kind !== "tunnel" || Date.now() < adbRestUntil) return;
      busy = true;
      try {
        const link = await overAdb(false);
        if (live && link && routeRef.current.kind === "tunnel") {
          logInfo("remote: the phone is on adb; moving the desktop to it");
          toUsb(link, false);
        }
      } finally {
        busy = false;
      }
    }, WATCH_MS);

    void first();
    return () => {
      live = false;
      window.clearInterval(watch);
    };
  }, [phoneId]);

  if (route.kind === "usb") {
    return (
      <UsbDesktop
        key={route.key}
        url={route.url}
        wireless={route.wireless}
        name={name}
        start={route.start}
        onLost={() => lost.current()}
      />
    );
  }
  if (route.kind === "tunnel") return <DesktopWindow key={route.key} phoneId={phoneId} />;
  return (
    <div className="relative flex h-full w-full items-center justify-center overflow-hidden bg-[var(--bg)] p-6">
      <div className="glass flex w-[440px] max-w-full flex-col items-center gap-2 px-7 py-6 text-center">
        <p className="text-[15px] font-semibold text-slate-100">Reaching {name}…</p>
        <span
          className="mt-2 h-5 w-5 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
          aria-hidden="true"
        />
      </div>
    </div>
  );
}
