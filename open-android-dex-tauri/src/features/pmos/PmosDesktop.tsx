import { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { info as logInfo } from "@tauri-apps/plugin-log";
import { DesktopCover, type DesktopHost } from "../remote/DesktopCards";
import { UsbDesktop } from "../remote/usb/UsbDesktop";

/**
 * A postmarketOS phone's desktop, in a window of its own (pmos.rs).
 *
 * The phone's helper speaks the messages the phone's Linux speaks over adb,
 * so the picture, the pointer and the keys are UsbDesktop's: H.264 decoded on
 * this computer's GPU, the pointer this computer's own. Only the way there is
 * different: a WebSocket on this computer's loopback that pmos.rs puts through
 * to the helper over SSH. The window lives as long as the session does;
 * pmos.rs closes it when the phone goes.
 */

interface PmosLink {
  url: string;
  name: string;
}

export function PmosDesktop({ id }: { id: string }) {
  const [link, setLink] = useState<PmosLink | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  /** Bumped to reach the phone again: a fresh socket, the same screen on the phone. */
  const [attempt, setAttempt] = useState(0);
  // The clipboard and dropped files are not this phone's yet: a host whose
  // clipboard never arrives shows no switch for it.
  const host = useMemo<DesktopHost>(
    () => ({ clipboard: new Promise<never>(() => {}), attach: () => () => {}, showing: () => {} }),
    [],
  );

  useEffect(() => {
    let live = true;
    invoke<PmosLink>("pmos_window_link", { id })
      .then((l) => {
        if (!live) return;
        setLink(l);
        setFailed(null);
      })
      .catch((e) => live && setFailed(String(e)));
    return () => {
      live = false;
    };
  }, [id, attempt]);

  if (failed) {
    return (
      <div className="relative h-screen">
        <DesktopCover
          name={link?.name ?? "the phone"}
          state="failed"
          message={failed}
          via="usb"
          onAgain={() => setAttempt((n) => n + 1)}
          onClose={() => void getCurrentWindow().close()}
        />
      </div>
    );
  }
  if (!link) return <div className="h-screen bg-black" />;
  return (
    <div className="h-screen">
      <UsbDesktop
        key={attempt}
        url={link.url}
        route="USB"
        tier="cable"
        name={link.name}
        start
        smooth
        host={host}
        onLost={() => {
          // The socket went without the phone saying why. While the session
          // lasts the phone's screen is still there: reach it again. When the
          // session has ended, pmos.rs is closing this window anyway.
          logInfo("pmos: the desktop's socket closed; reaching the phone again");
          window.setTimeout(() => setAttempt((n) => n + 1), 800);
        }}
      />
    </div>
  );
}
