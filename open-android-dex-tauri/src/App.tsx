import { useCallback, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { error as logError, info as logInfo, warn as logWarn } from "@tauri-apps/plugin-log";
import { useSessions } from "./features/mirror/useSessions";
import { WirelessPanel } from "./features/wireless/WirelessPanel";
import { IS_MAC, TITLEBAR_LEAD } from "./lib/host";
import type {
  DeviceInfo,
  KnownDevice,
  MirrorFailure,
  MirrorNotice,
  MirrorOptions,
  SessionInfo,
  WirelessResult,
} from "./lib/types";

const appWindow = getCurrentWindow();

const DESKTOP_RES = "1920x1080";

/**
 * How long the UI waits for a display before it stops pretending progress is
 * being made. The backend gives up at 30s and says why; this is the backstop
 * for the case where the backend never answers at all.
 */
const LAUNCH_TIMEOUT_MS = 45_000;

function deviceName(d: DeviceInfo): string {
  return [d.brand, d.model].filter(Boolean).join(" ") || d.serial;
}

function desktopOptions(d: DeviceInfo): MirrorOptions {
  return {
    serial: d.serial,
    windowTitle: `${deviceName(d)} — Open Android DeX`,
    newDisplay: DESKTOP_RES,
    vdNoDecorations: true, // our launcher is the only desktop surface
    maxSize: 0,
    videoBitRateMbps: 8,
    maxFps: 0,
    audio: true,
    audioPlayback: false,
    stayAwake: true,
    turnScreenOff: false,
    alwaysOnTop: false,
    fullscreen: false,
    windowBorderless: false,
    autoReconnect: true,
    freeform: true,
    // forward right-click to Android for context menus; Back = Shift+right-click
    mouseBind: "+hsn:bhsn",
    // scrcpy's own default: the PC keeps its cursor and nothing is captured.
    // Settings → Mouse & cursor switches it to "uhid", which is the only mode
    // in which Android draws the pointer into the stream.
    mouseMode: "sdk",
  };
}

interface Step {
  state: "idle" | "busy" | "done" | "error";
  text: string;
}

type Phase =
  | { kind: "boot" }
  | { kind: "connect"; error?: string }
  | { kind: "launching"; device: DeviceInfo }
  | { kind: "ready"; device: DeviceInfo }
  /** `requested` = the desktop asked to be closed, rather than being closed on it. */
  | { kind: "ended"; device: DeviceInfo; requested: boolean };

// ── Boot/stage widgets ──────────────────────────────────────────────

function StageRow({ tag, step }: { tag: string; step: Step }) {
  return (
    <div className={`stage-row ${step.state}`}>
      <span className={`stage-ring ${step.state}`} />
      <span className="stage-tag">{tag}</span>
      <span className="stage-sep">–</span>
      <span className="stage-text">{step.text}</span>
    </div>
  );
}

function Wordmark() {
  return (
    <div className="wordmark">
      <span className="wordmark-light">OPEN ANDROID</span>
      <span className="wordmark-bold">DeX</span>
    </div>
  );
}

// ── App ─────────────────────────────────────────────────────────────

export default function App() {
  const sessions = useSessions();
  const [phase, setPhase] = useState<Phase>({ kind: "boot" });
  const [adbStep, setAdbStep] = useState<Step>({ state: "busy", text: "Starting ADB server…" });
  const [dexStep, setDexStep] = useState<Step>({ state: "idle", text: "Waiting for bridge…" });
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  /** Non-fatal thing worth telling the user about the current launch. */
  const [notice, setNotice] = useState("");
  /**
   * Whether the one-shot attempt to reach a remembered phone has finished.
   * Nothing may conclude "no device" before it has: the whole point is that a
   * phone set up once comes back on its own, and a connect screen that flashes
   * up first would make it look like it hadn't.
   */
  const [reconnectDone, setReconnectDone] = useState(false);

  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  /** Auto-launch is armed at startup and re-armed by explicit user actions. */
  const armedRef = useRef(true);
  const scannedOnce = useRef(false);
  /**
   * The serial to launch on when more than one is ready. A phone that has just
   * been put on Wi-Fi is still on the cable too, and launching on the USB
   * serial would tie the session to the cable the user is about to pull out.
   */
  const preferredSerial = useRef<string | null>(null);
  /**
   * Set when a live session was stopped in order to move it onto Wi-Fi.
   * `adb tcpip` restarts adbd, which drops every transport including the one
   * scrcpy is streaming over, so the switch has to happen between sessions
   * rather than under one.
   */
  const switchingToWireless = useRef(false);
  const deployedFor = useRef<number | null>(null);
  const sawDesktop = useRef(false);
  /**
   * Set when the desktop was stopped in order to be started again — the
   * in-desktop Settings window asking for its stream settings to take effect.
   * Without it the session ending would land on the "desktop closed" screen.
   */
  const restarting = useRef(false);
  /**
   * Set when the phone asked to leave DeX ("Exit DeX" in the taskbar or in the
   * in-desktop Settings window). Only changes the wording of the screen this
   * lands on — the phone is put back either way.
   */
  const exiting = useRef(false);

  // ── ADB server boot ──
  useEffect(() => {
    logInfo("boot: starting the ADB bridge");
    invoke<string>("adb_version")
      .then((v) => {
        logInfo(`boot: adb online — ${String(v).split("\n")[0]}`);
        setAdbStep({ state: "done", text: "ADB bridge online" });
        setDexStep((s) => (s.state === "idle" ? { state: "busy", text: "Scanning for devices…" } : s));
      })
      .catch((e) => {
        logError(`boot: adb unusable — ${String(e)}`);
        setAdbStep({ state: "error", text: String(e) });
      });
  }, []);

  // ── Bring back a phone that has been set up before ──
  // Runs once, at boot, before the connect screen is allowed to appear. The
  // second run on a given phone should not ask the user for anything.
  useEffect(() => {
    let live = true;
    invoke<KnownDevice[]>("wireless_known")
      .then(async (saved) => {
        if (!live || saved.length === 0) return;
        logInfo(`boot: trying ${saved.length} remembered phone(s)`);
        setDexStep({ state: "busy", text: "Reconnecting over Wi-Fi…" });
        const back = await invoke<string[]>("wireless_reconnect_known", { only: null }).catch(
          () => [],
        );
        if (live && back.length > 0) {
          logInfo(`boot: ${back.join(", ")} answered`);
          preferredSerial.current = back[0];
        }
      })
      .catch(() => {})
      .finally(() => live && setReconnectDone(true));
    return () => {
      live = false;
    };
  }, []);

  // ── Device polling (always on — plugging a phone in is the trigger) ──
  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const list = await invoke<DeviceInfo[]>("adb_list_devices");
        if (!cancelled) {
          scannedOnce.current = true;
          setDevices(list);
        }
      } catch {
        // adb still starting — next tick retries
      }
    };
    poll();
    const timer = setInterval(poll, 2500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  // ── Desktop launch pipeline ──
  const launch = useCallback(async (device: DeviceInfo) => {
    armedRef.current = false;
    deployedFor.current = null;
    sawDesktop.current = false;
    exiting.current = false;
    setNotice("");
    setPhase({ kind: "launching", device });
    setDexStep({ state: "busy", text: `Linking ${deviceName(device)}…` });
    logInfo(
      `launch: ${deviceName(device)} (${device.serial}, ${device.connection}, ` +
        `Android ${device.androidVersion ?? "?"})`,
    );
    try {
      setDexStep({ state: "busy", text: "Preparing desktop profile…" });
      await invoke("adb_prepare_desktop", {
        serial: device.serial,
        samsungDesktop: false,
      }).catch((e) => logWarn(`launch: prepare_desktop failed (continuing) — ${String(e)}`));
      setDexStep({ state: "busy", text: "Creating virtual display…" });
      const info = await invoke<SessionInfo>("start_mirror", { options: desktopOptions(device) });
      logInfo(`launch: scrcpy started (pid ${info.pid}), waiting for the display id`);
      // the display id arrives via the mirror:display event; the effect
      // below continues with the launcher deployment
    } catch (e) {
      logError(`launch: start_mirror failed — ${String(e)}`);
      setDexStep({ state: "error", text: String(e) });
      setPhase({ kind: "connect", error: String(e) });
    }
  }, []);

  /** A launch that cannot continue: show why, and where the trace is. */
  const failLaunch = useCallback((message: string) => {
    setDexStep({ state: "error", text: message });
    setPhase((p) =>
      p.kind === "launching" || p.kind === "ready"
        ? { kind: "connect", error: message }
        : p,
    );
  }, []);

  // ── A session that reported it cannot start ──
  useEffect(() => {
    const un = listen<MirrorFailure>("mirror:failed", ({ payload }) => {
      logError(`launch: session ${payload.sessionKey} failed — ${payload.reason}`);
      failLaunch(payload.reason);
    });
    const unNotice = listen<MirrorNotice>("mirror:notice", ({ payload }) => {
      logWarn(`launch: ${payload.text}`);
      setNotice(payload.text);
    });
    return () => {
      un.then((f) => f());
      unNotice.then((f) => f());
    };
  }, [failLaunch]);

  // ── Backstop: a launch that goes quiet must not spin forever ──
  useEffect(() => {
    if (phase.kind !== "launching") return;
    const timer = setTimeout(() => {
      if (phaseRef.current.kind !== "launching") return;
      logError(`launch: nothing happened for ${LAUNCH_TIMEOUT_MS / 1000}s — giving up`);
      failLaunch(
        "The desktop did not start. The log for this run has the whole sequence — " +
          "open it below and send it with a bug report.",
      );
    }, LAUNCH_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [phase, failLaunch]);

  // ── Auto-launch as soon as a ready device shows up ──
  useEffect(() => {
    const current = phaseRef.current;
    if (current.kind !== "boot" && current.kind !== "connect") return;
    // A phone just moved onto Wi-Fi is briefly present twice, once per
    // transport. Prefer the one that was asked for.
    const ready =
      devices.find((d) => d.state === "device" && d.serial === preferredSerial.current) ??
      devices.find((d) => d.state === "device");
    if (ready && armedRef.current) {
      launch(ready);
    } else if (current.kind === "boot" && scannedOnce.current && reconnectDone && !ready) {
      setDexStep({ state: "idle", text: "No device detected" });
      setPhase({ kind: "connect" });
    }
  }, [devices, launch, reconnectDone]);

  // ── Once the virtual display exists, deploy the DeX launcher ──
  useEffect(() => {
    if (phase.kind !== "launching") return;
    const device = phase.device;
    const key = `${device.serial}|desktop`;
    const displayId = sessions[key]?.displayId;
    if (displayId == null || deployedFor.current === displayId) return;
    deployedFor.current = displayId;
    sawDesktop.current = true;
    logInfo(`launch: display ${displayId} is up — deploying the launcher`);
    setDexStep({ state: "busy", text: "Deploying DeX launcher…" });
    invoke("adb_start_launcher", { serial: device.serial, displayId })
      .then(() => {
        logInfo("launch: desktop ready");
        setDexStep({ state: "done", text: "Desktop ready" });
        setPhase({ kind: "ready", device });
        // the scrcpy desktop window is the star — tuck this one away
        setTimeout(() => appWindow.minimize().catch(() => {}), 1400);
      })
      .catch((e) => {
        logError(`launch: launcher deployment failed — ${String(e)}`);
        invoke("stop_mirror", { sessionKey: key }).catch(() => {});
        // The stage row is one line in a fixed-width span; the guidance errors
        // out of install_launcher are paragraphs, and dropping a whole one in
        // there renders as a smear. Their first line is written to stand on its
        // own, so it becomes the status and the full text goes to the block
        // below, which is a <pre> and keeps the line breaks.
        const full = String(e);
        setDexStep({ state: "error", text: full.split("\n")[0] });
        setPhase({ kind: "connect", error: full });
      });
  }, [phase, sessions]);

  // ── Restart requested from the in-desktop Settings window ──
  // Stream settings (resolution, codec, bit rate, clipboard sync) are command
  // line arguments, so they only take on a fresh scrcpy process. Rust has
  // already stored them; all that is left is to cycle the session.
  useEffect(() => {
    const un = listen<{ sessionKey: string }>("desktop:restart", ({ payload }) => {
      restarting.current = true;
      invoke("stop_mirror", { sessionKey: payload.sessionKey }).catch(() => {
        restarting.current = false;
      });
    });
    return () => {
      un.then((f) => f());
    };
  }, []);

  // ── "Exit DeX", pressed on the phone ──
  // The desktop cannot end itself: the display belongs to scrcpy over here,
  // and so does everything the launch switched on over there.
  useEffect(() => {
    const un = listen<{ sessionKey: string }>("desktop:exit", ({ payload }) => {
      logInfo("exit: the desktop asked to be closed");
      exiting.current = true;
      invoke("stop_mirror", { sessionKey: payload.sessionKey }).catch((e) => {
        exiting.current = false;
        logWarn(`exit: could not stop the session — ${String(e)}`);
      });
    });
    return () => {
      un.then((f) => f());
    };
  }, []);

  // ── Detect the desktop session ending (exited, or the window closed) ──
  useEffect(() => {
    if (phase.kind !== "ready") return;
    const key = `${phase.device.serial}|desktop`;
    if (sawDesktop.current && !sessions[key]) {
      if (restarting.current) {
        restarting.current = false;
        setDexStep({ state: "busy", text: "Applying stream settings…" });
        launch(phase.device);
        return;
      }
      // Moving a running desktop onto Wi-Fi. The session had to end first —
      // `adb tcpip` restarts adbd and takes the stream down with it — so this
      // is the moment to do the switch and start again on the new serial.
      // The phone keeps its desktop profile throughout; the relaunch reapplies
      // it, which is why the restore that the ordinary path runs is skipped.
      if (switchingToWireless.current) {
        switchingToWireless.current = false;
        setDexStep({ state: "busy", text: "Switching to Wi-Fi…" });
        appWindow.unminimize().catch(() => {});
        invoke<WirelessResult>("wireless_go_wireless", { serial: phase.device.serial })
          .then(async (result) => {
            logInfo(`wireless: ${phase.device.serial} is now reachable at ${result.address}`);
            preferredSerial.current = result.serial;
            const list = await invoke<DeviceInfo[]>("adb_list_devices").catch(() => []);
            setNotice("Wireless is set up — you can unplug the cable.");
            launch(
              list.find((d) => d.serial === result.serial) ?? {
                ...phase.device,
                serial: result.serial,
                connection: "wifi",
              },
            );
          })
          .catch((e) => {
            logError(`wireless: could not switch ${phase.device.serial} — ${String(e)}`);
            failLaunch(String(e));
          });
        return;
      }
      const requested = exiting.current;
      exiting.current = false;
      setDexStep({ state: "idle", text: requested ? "DeX closed" : "Desktop session ended" });
      setPhase({ kind: "ended", device: phase.device, requested });
      // However the session ended, the phone is left with a desktop profile on
      // it — freeform windowing, a relaxed hidden-API policy, our accessibility
      // service and the window daemon. Take all of it back off.
      invoke("adb_end_desktop", { serial: phase.device.serial })
        .then(() => logInfo("exit: the phone is back to normal"))
        .catch((e) => logWarn(`exit: the phone could not be fully restored — ${String(e)}`));
      appWindow.unminimize().catch(() => {});
      appWindow.setFocus().catch(() => {});
    }
  }, [phase, sessions, launch, failLaunch]);

  // ── Where the trace for this run lives ──
  const [logPath, setLogPath] = useState("");
  const [diagBusy, setDiagBusy] = useState(false);
  useEffect(() => {
    invoke<string>("diag_log_path")
      .then(setLogPath)
      .catch(() => {});
  }, []);

  const openLog = useCallback(() => {
    invoke("diag_reveal", { path: null }).catch((e) => logWarn(`could not open the log: ${e}`));
  }, []);

  /** Host + phone state in one file, for attaching to a bug report. */
  const collectDiagnostics = useCallback(async () => {
    setDiagBusy(true);
    setNotice("Collecting diagnostics…");
    try {
      const serial = devices.find((d) => d.state === "device")?.serial ?? null;
      const path = await invoke<string>("diag_collect", { serial });
      setNotice(`Diagnostics written to ${path}`);
      invoke("diag_reveal", { path }).catch(() => {});
    } catch (e) {
      logError(`diagnostics failed: ${String(e)}`);
      setNotice(`Could not collect diagnostics: ${String(e)}`);
    } finally {
      setDiagBusy(false);
    }
  }, [devices]);

  const refresh = useCallback(() => {
    armedRef.current = true;
    invoke<DeviceInfo[]>("adb_list_devices")
      .then((list) => {
        scannedOnce.current = true;
        setDevices(list);
      })
      .catch(() => {});
  }, []);

  /** A phone became reachable over Wi-Fi — take the desktop there. */
  const onWirelessConnected = useCallback(
    (result: WirelessResult) => {
      preferredSerial.current = result.serial;
      armedRef.current = true;
      setPhase((p) => (p.kind === "connect" ? { kind: "connect" } : p));
      refresh();
    },
    [refresh],
  );

  /** "Lose the cable", from a desktop that is already running on one. */
  const switchToWireless = useCallback((device: DeviceInfo) => {
    switchingToWireless.current = true;
    setNotice("");
    setDexStep({ state: "busy", text: "Switching to Wi-Fi…" });
    invoke("stop_mirror", { sessionKey: `${device.serial}|desktop` }).catch((e) => {
      switchingToWireless.current = false;
      logWarn(`wireless: could not stop the session to switch — ${String(e)}`);
    });
  }, []);

  const stopDesktop = useCallback((device: DeviceInfo) => {
    // Same thing the phone's "Exit DeX" does, asked for from this end.
    exiting.current = true;
    invoke("stop_mirror", { sessionKey: `${device.serial}|desktop` }).catch(() => {
      exiting.current = false;
    });
  }, []);

  const showDesktop = useCallback((device: DeviceInfo) => {
    invoke("focus_session", { sessionKey: `${device.serial}|desktop` }).catch(() => {});
  }, []);

  // ── Screens ──
  const pendingDevices = devices.filter((d) => d.state !== "device");

  return (
    <div className="boot-backdrop flex h-screen flex-col">
      {/*
        The caption buttons are ours on Windows and the system's on macOS,
        where `titleBarStyle: "Overlay"` floats the real traffic lights over
        this strip — so the label is padded clear of them and no second set of
        controls is drawn. `deep` walks into the children, which keeps the whole
        34px band draggable; buttons still short-circuit the walk, so the
        Windows controls stay clickable.
      */}
      <header
        data-tauri-drag-region="deep"
        className="z-10 flex h-[34px] shrink-0 items-center justify-between"
      >
        <div
          className={`pointer-events-none flex items-center gap-2 pr-3 text-[12.5px] text-slate-400 ${TITLEBAR_LEAD}`}
        >
          <span>📱</span>
          <span>Open Android DeX</span>
        </div>
        {!IS_MAC && (
          <div className="flex">
            <button className="titlebar-btn" onClick={() => appWindow.minimize()} title="Minimize">
              <svg width="10" height="10" viewBox="0 0 10 10"><line x1="0" y1="5" x2="10" y2="5" stroke="currentColor" /></svg>
            </button>
            <button className="titlebar-btn close" onClick={() => appWindow.close()} title="Close">
              <svg width="10" height="10" viewBox="0 0 10 10"><path d="M0 0 L10 10 M10 0 L0 10" stroke="currentColor" /></svg>
            </button>
          </div>
        )}
      </header>

      {phase.kind === "connect" ? (
        // Centred by auto margins, not by `justify-center`: the Wi-Fi panel
        // grows with a QR code, a list of saved phones and an error block, and
        // centring a flex child the usual way pushes its top edge off the top
        // of the scroll box once it outgrows the window. Auto margins collapse
        // to zero at that point, so the card sits in the middle while it fits
        // and scrolls from its top once it doesn't.
        <div className="fade-in flex min-h-0 flex-1 flex-col overflow-y-auto p-6">
          <div className="glass m-auto flex w-[620px] max-w-full shrink-0 flex-col overflow-hidden">
            <div className="px-5 py-5">
              {pendingDevices.length > 0 && (
                <div className="flex flex-col gap-2">
                  {pendingDevices.map((d) => (
                    <div key={d.serial} className="flex items-center gap-3 rounded-xl bg-white/[0.04] px-4 py-3">
                      <span className="text-xl">📱</span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[13px] font-medium text-slate-200">
                          {deviceName(d)}
                        </p>
                        <p className="text-[11.5px] text-slate-500">
                          {d.state === "unauthorized"
                            ? "Confirm the USB-debugging prompt on the phone"
                            : d.state === "offline"
                              ? "Device is offline — reconnect the cable"
                              : d.state}
                        </p>
                      </div>
                      <span className={`badge ${d.state === "unauthorized" ? "badge-warn" : ""}`}>
                        {d.connection === "wifi" ? "Wi-Fi" : "USB"}
                      </span>
                    </div>
                  ))}
                </div>
              )}

              {phase.error && (
                <>
                  <pre className="console mt-3 rounded-lg bg-amber-950/40 p-3 text-amber-300">{phase.error}</pre>
                  <p className="mt-2 text-[11.5px] text-slate-500">
                    Every step of this attempt was written to{" "}
                    <button className="underline hover:text-slate-300" onClick={openLog}>
                      {logPath || "the log file"}
                    </button>
                    .
                  </p>
                </>
              )}

              <WirelessPanel devices={devices} onConnected={onWirelessConnected} />
            </div>

            {/* The three buttons already fill the card's width, so the status
                line goes underneath them rather than beside them — sharing the
                row is what pushed both into wrapping over each other. */}
            <div className="flex flex-col gap-2 border-t border-white/10 px-5 py-3">
              <div className="flex flex-wrap gap-2">
                <button className="btn-ghost" onClick={refresh}>
                  <span aria-hidden="true">⟳</span>
                  Refresh devices
                </button>
                <button className="btn-ghost" onClick={openLog} title={logPath}>
                  <span aria-hidden="true">🗒</span>
                  Open log
                </button>
                <button className="btn-ghost" onClick={collectDiagnostics} disabled={diagBusy}>
                  <span aria-hidden="true">🩺</span>
                  {diagBusy ? "Collecting…" : "Diagnostics"}
                </button>
              </div>
              <span className="text-[11.5px] text-slate-500">scanning in the background…</span>
            </div>
            {notice && (
              <div className="border-t border-white/10 px-5 py-2 text-[11.5px] text-amber-300/90">
                {notice}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="fade-in flex min-h-0 flex-1 flex-col items-center justify-center gap-10 pb-10">
          <Wordmark />
          <div className="flex flex-col gap-4">
            <StageRow tag="ADB" step={adbStep} />
            <StageRow tag="DEX" step={dexStep} />
          </div>

          {notice && <p className="text-[11.5px] text-amber-300/90">{notice}</p>}

          {phase.kind === "ready" && (
            <div className="fade-in flex flex-col items-center gap-3">
              <p className="text-[12.5px] text-slate-500">
                Desktop is live on {deviceName(phase.device)} — this window stays out of the way
              </p>
              <div className="flex flex-wrap justify-center gap-2">
                <button className="btn-ghost" onClick={() => showDesktop(phase.device)}>
                  Show desktop
                </button>
                {/* Only offered on a cable: the point is to get rid of it. */}
                {phase.device.connection === "usb" && (
                  <button
                    className="btn-ghost"
                    onClick={() => switchToWireless(phase.device)}
                    title="Move this session onto Wi-Fi so the cable can come out"
                  >
                    <span aria-hidden="true">📶</span>
                    Go wireless
                  </button>
                )}
                <button className="btn-danger" onClick={() => stopDesktop(phase.device)}>
                  Exit DeX
                </button>
              </div>
            </div>
          )}

          {phase.kind === "ended" && (
            <div className="fade-in flex flex-col items-center gap-3">
              <p className="text-[12.5px] text-slate-500">
                {phase.requested
                  ? "DeX closed — the phone is back to normal"
                  : "The desktop window was closed"}
              </p>
              <div className="flex gap-2">
                <button className="btn-accent" onClick={() => launch(phase.device)}>
                  <span aria-hidden="true">▶</span>
                  Relaunch desktop
                </button>
                <button
                  className="btn-ghost"
                  onClick={() => {
                    setDexStep({ state: "idle", text: "No device selected" });
                    setPhase({ kind: "connect" });
                  }}
                >
                  Devices
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
