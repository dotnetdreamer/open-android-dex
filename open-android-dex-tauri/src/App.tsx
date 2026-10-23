import { useCallback, useEffect, useRef, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { error as logError, info as logInfo, warn as logWarn } from "@tauri-apps/plugin-log";
import { openUrl } from "@tauri-apps/plugin-opener";
import { AppsPanel, opensAppsOnConnect } from "./features/apps/AppsPanel";
import { DesktopPage, type Method } from "./features/desktop/DesktopPage";
import { deviceName, type AdbState, type LaunchMode, type Phase, type Progress } from "./features/desktop/model";
import { useSessions } from "./features/mirror/useSessions";
import { RemotePanel } from "./features/remote/RemotePanel";
import { WirelessPanel } from "./features/wireless/WirelessPanel";
import { Icon } from "./ui/Icon";
import { Menu, MenuItem, Toast, useToast } from "./ui/kit";
import { TitleBar, type Tab } from "./ui/TitleBar";
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

/**
 * The desktop's size when there is no screen to fit it to. Normally the Rust
 * side replaces it with the computer's own screen, in its own pixels, with the
 * density and bit rate to match (src-tauri/src/display.rs); a resolution picked
 * in the phone's Settings replaces it as well.
 */
const DESKTOP_RES = "1920x1080";

/**
 * How long the UI waits for a display before it stops pretending progress is
 * being made. The backend gives up at 30s and says why; this is the backstop
 * for the case where the backend never answers at all.
 */
const LAUNCH_TIMEOUT_MS = 45_000;
/** How long a launch waits once the phone has asked its owner about the install — adb.rs's INSTALL_TIMEOUT. */
const PHONE_ANSWER_TIMEOUT_MS = 240_000;

const ISSUES_URL = "https://github.com/dotnetdreamer/open-android-dex/issues";

function desktopOptions(d: DeviceInfo): MirrorOptions {
  return {
    serial: d.serial,
    windowTitle: `${deviceName(d)} — Open Android DeX`,
    newDisplay: DESKTOP_RES,
    vdNoDecorations: true, // our launcher is the only desktop surface
    maxSize: 0,
    // scrcpy's own 8 Mbps, for that 1080p — scaled with the display's pixels on
    // the Rust side unless a rate was picked in Settings
    videoBitRateMbps: 8,
    maxFps: 0,
    audio: true,
    audioPlayback: false,
    stayAwake: true,
    // stayAwake only holds while the phone charges; over Wi-Fi on battery an
    // idle desktop would otherwise time out and go dark
    keepActive: true,
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

// ── App ─────────────────────────────────────────────────────────────

export default function App() {
  const sessions = useSessions();
  const [phase, setPhase] = useState<Phase>({ kind: "boot" });
  const [adb, setAdb] = useState<AdbState>({ state: "busy" });
  /** Bumped by "Try again" on the start-up error, to ask adb once more. */
  const [adbAttempt, setAdbAttempt] = useState(0);
  /** A remembered phone is being called back over Wi-Fi, at boot. */
  const [reconnecting, setReconnecting] = useState(false);
  const [progress, setProgress] = useState<Progress>({ step: 0, mode: "open" });
  /** A running desktop is being moved off the cable onto Wi-Fi. */
  const [switching, setSwitching] = useState(false);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  /**
   * What the launch wants said, which stays for as long as the session: a
   * phone that cannot draw window titlebars, a retry with fewer options.
   * One-off feedback to a click goes to the toast instead.
   */
  const [notice, setNotice] = useState("");
  /** The launcher's install is held up by a question on the phone (Play Protect). */
  const [askedOnPhone, setAskedOnPhone] = useState(false);
  const { toast, say, dismiss } = useToast();
  const [tab, setTab] = useState<Tab>("desktop");
  /** Cable or Wi-Fi, on the connect screen. Wi-Fi first for someone who has used it before. */
  const [method, setMethod] = useState<Method>("usb");
  const [version, setVersion] = useState("");
  /**
   * Whether the one-shot attempt to reach a remembered phone has finished.
   * Nothing may conclude "no device" before it has: the whole point is that a
   * phone set up once comes back on its own, and a connect screen that flashes
   * up first would make it look like it hadn't.
   */
  const [reconnectDone, setReconnectDone] = useState(false);
  /**
   * Whether this run was started to open one phone app (a taskbar button
   * pinned from its window). Asked before anything launches by itself: such a
   * start opens that app's window, and no desktop.
   */
  const [startupChecked, setStartupChecked] = useState(false);

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
  /**
   * The in-flight `adb_prepare_desktop` call, awaited by the launcher deploy
   * rather than by the launch itself — see the note where it is fired.
   */
  const prepareProfile = useRef<Promise<unknown> | null>(null);
  const sawDesktop = useRef(false);
  /**
   * Set when the desktop was stopped in order to be started again — the
   * in-desktop Settings window asking for its stream settings to take effect.
   * Without it the session ending would land on the "desktop closed" screen.
   */
  const restarting = useRef(false);
  /** The session ended because the phone undid it while unplugged: launch it again. */
  const relaunching = useRef(false);
  /** This launch sets the desktop up around a launcher that is still running (adb_start_launcher's `resume`). */
  const resumeLauncher = useRef(false);
  /**
   * Set when the phone asked to leave DeX ("Exit DeX" in the taskbar or in the
   * in-desktop Settings window). Only changes the wording of the screen this
   * lands on — the phone is put back either way.
   */
  const exiting = useRef(false);

  // ── ADB server boot ──
  useEffect(() => {
    let live = true;
    setAdb({ state: "busy" });
    logInfo("boot: starting the ADB bridge");
    invoke<string>("adb_version")
      .then((v) => {
        if (!live) return;
        logInfo(`boot: adb online — ${String(v).split("\n")[0]}`);
        setAdb({ state: "done" });
      })
      .catch((e) => {
        if (!live) return;
        logError(`boot: adb unusable — ${String(e)}`);
        setAdb({ state: "error", error: String(e) });
      });
    return () => {
      live = false;
    };
  }, [adbAttempt]);

  useEffect(() => {
    getVersion()
      .then(setVersion)
      .catch(() => {});
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
        setMethod("wifi");
        setReconnecting(true);
        const back = await invoke<string[]>("wireless_reconnect_known", { only: null }).catch(
          () => [],
        );
        if (live && back.length > 0) {
          logInfo(`boot: ${back.join(", ")} answered`);
          preferredSerial.current = back[0];
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!live) return;
        setReconnecting(false);
        setReconnectDone(true);
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    invoke<{ serial: string; pkg: string } | null>("apps_startup")
      .then((startup) => {
        if (startup) {
          logInfo(`boot: started for ${startup.pkg} on ${startup.serial} — its window, no desktop`);
          armedRef.current = false;
          setTab("apps");
        }
      })
      .catch(() => {})
      .finally(() => setStartupChecked(true));
  }, []);

  // ── Device polling (always on — plugging a phone in is the trigger) ──
  useEffect(() => {
    let cancelled = false;
    // One list at a time. A poll can outlast the 2.5s tick — the first one to
    // see a phone reads its name, which starts a process on it — and two in
    // flight can land out of order, an older list over a newer one.
    let inFlight = false;
    const poll = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const list = await invoke<DeviceInfo[]>("adb_list_devices");
        if (!cancelled) {
          scannedOnce.current = true;
          setDevices(list);
        }
      } catch {
        // adb still starting — next tick retries
      } finally {
        inFlight = false;
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
  const launch = useCallback(async (device: DeviceInfo, resume = false, mode: LaunchMode = "open") => {
    resumeLauncher.current = resume;
    armedRef.current = false;
    deployedFor.current = null;
    sawDesktop.current = false;
    exiting.current = false;
    setNotice("");
    setAskedOnPhone(false);
    setSwitching(false);
    setProgress({ step: 0, mode });
    setPhase({ kind: "launching", device });
    logInfo(
      `launch: ${deviceName(device)} (${device.serial}, ${device.connection}, ` +
        `Android ${device.androidVersion ?? "?"})`,
    );
    try {
      // FIRED, NOT AWAITED. The profile is a chain of `settings` round trips —
      // measured at ~1.2s on a Redmi Note 7 over USB, because each `settings`
      // boots its own process on the phone — and awaiting it put every one of
      // those milliseconds in front of the launch. Nothing it writes is needed
      // to CREATE the virtual display; the globals matter to the apps that
      // open on it, so the deadline is the launcher deploy, not the spawn.
      // The deploy effect below awaits this promise before it starts anything,
      // which is what keeps enable_freeform_support/hidden_api_policy/
      // block_untrusted_touches guaranteed to be in place by then.
      prepareProfile.current = invoke("adb_prepare_desktop", {
        serial: device.serial,
        samsungDesktop: false,
      }).catch((e) => logWarn(`launch: prepare_desktop failed (continuing) — ${String(e)}`));
      const info = await invoke<SessionInfo>("start_mirror", { options: desktopOptions(device) });
      logInfo(`launch: scrcpy started (pid ${info.pid}), waiting for the display id`);
      // The phone is linked and making the display. Never backwards: the
      // display id can beat this answer here, and the deploy has moved on.
      setProgress((p) => (p.step < 1 ? { ...p, step: 1 } : p));
      // the display id arrives via the mirror:display event; the effect
      // below continues with the launcher deployment
    } catch (e) {
      logError(`launch: start_mirror failed — ${String(e)}`);
      setPhase({ kind: "connect", error: String(e) });
    }
  }, []);

  /** A launch that cannot continue: show why, and where the trace is. */
  const failLaunch = useCallback((message: string) => {
    setSwitching(false);
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

  // ── The phone stopped the launcher's install to ask its owner something ──
  // Play Protect's "Send app for a security check?" is the usual one. The
  // install waits on the answer without a word, so say where the hold-up is.
  useEffect(() => {
    const un = listen<{ serial: string }>("launch:phone-asks", () => {
      logWarn("launch: the phone is asking about installing the launcher — waiting for an answer there");
      setAskedOnPhone(true);
      setNotice("Check your phone: it's asking about installing Open Android DeX. Answer it there, and the desktop carries on.");
    });
    return () => {
      un.then((f) => f());
    };
  }, []);

  // ── Backstop: a launch that goes quiet must not spin forever ──
  // Longer once the phone has asked its owner something: the answer can take
  // a while, and the install itself gives up on its own after four minutes.
  useEffect(() => {
    if (phase.kind !== "launching") return;
    const wait = askedOnPhone ? PHONE_ANSWER_TIMEOUT_MS : LAUNCH_TIMEOUT_MS;
    const timer = setTimeout(() => {
      if (phaseRef.current.kind !== "launching") return;
      logError(`launch: nothing happened for ${wait / 1000}s — giving up`);
      failLaunch(
        askedOnPhone
          ? "Your phone is still waiting for an answer about installing Open Android DeX.\n" +
              "Answer it on the phone, then open the desktop again."
          : "The desktop took too long to start.\n" +
              "Unplug the phone, plug it back in and try again. If it keeps happening, the log " +
              "has every step of this attempt — attach it to a bug report.",
      );
    }, wait);
    return () => clearTimeout(timer);
  }, [phase, failLaunch, askedOnPhone]);

  // ── Auto-launch as soon as a ready device shows up ──
  useEffect(() => {
    if (!startupChecked) return;
    const current = phaseRef.current;
    if (current.kind !== "boot" && current.kind !== "connect") return;
    // A phone just moved onto Wi-Fi is briefly present twice, once per
    // transport. Prefer the one that was asked for.
    const ready =
      devices.find((d) => d.state === "device" && d.serial === preferredSerial.current) ??
      devices.find((d) => d.state === "device");
    if (ready && armedRef.current && opensAppsOnConnect()) {
      // Its owner asked for the phone's apps here rather than its desktop:
      // the Apps page, and no desktop is set up on the phone at all.
      armedRef.current = false;
      logInfo(`launch: ${deviceName(ready)} opens its apps here, not a desktop`);
      setPhase({ kind: "connect" });
      setTab("apps");
    } else if (ready && armedRef.current) {
      launch(ready);
    } else if (current.kind === "boot" && scannedOnce.current && reconnectDone && !ready) {
      setPhase({ kind: "connect" });
    }
  }, [devices, launch, reconnectDone, startupChecked]);

  // ── A phone on the cable that needs something done ──
  // Plugging one in is choosing the cable: the connect screen turns to it, so
  // "Tap Allow" is in front of the person who just plugged in, whichever way
  // they were looking. Once per phone, so choosing Wi-Fi again sticks.
  const pendingUsb = devices.find((d) => d.connection === "usb" && d.state !== "device")?.serial ?? null;
  const shownPending = useRef<string | null>(null);
  useEffect(() => {
    if (pendingUsb && pendingUsb !== shownPending.current) setMethod("usb");
    shownPending.current = pendingUsb;
  }, [pendingUsb]);

  // ── Once the virtual display exists, deploy the DeX launcher ──
  useEffect(() => {
    if (phase.kind !== "launching" && phase.kind !== "ready") return;
    const device = phase.device;
    const key = `${device.serial}|desktop`;
    const displayId = sessions[key]?.displayId;
    if (displayId == null || deployedFor.current === displayId) return;
    deployedFor.current = displayId;
    if (phase.kind === "ready") {
      // A desktop already up got a new display: scrcpy's reconnect after the
      // phone came back (Samsung's adbd restarts on every USB mode switch)
      // makes one, and a new display is an empty one — the old one's windows
      // went with it. Put the launcher back on it, around the running one.
      logInfo(`launch: the desktop came back on display ${displayId} — putting the launcher back on it`);
      invoke("adb_start_launcher", { serial: device.serial, displayId, resume: true })
        .then(() => logInfo("launch: desktop ready again"))
        .catch((e) => logError(`launch: could not put the launcher back — ${String(e)}`));
      return;
    }
    sawDesktop.current = true;
    logInfo(`launch: display ${displayId} is up — deploying the launcher`);
    setProgress((p) => ({ ...p, step: 2, waiting: false }));
    // The desktop profile has been writing itself while the display came up.
    // By here it is almost always already done, and this await costs nothing —
    // but it is what makes "fired, not awaited" above safe rather than a race.
    Promise.resolve(prepareProfile.current)
      .then(() => invoke("adb_start_launcher", { serial: device.serial, displayId, resume: resumeLauncher.current }))
      .then(() => {
        logInfo("launch: desktop ready");
        setProgress((p) => ({ ...p, step: 3, waiting: false }));
        setPhase({ kind: "ready", device });
        // the scrcpy desktop window is the star — tuck this one away
        setTimeout(() => appWindow.minimize().catch(() => {}), 1400);
      })
      .catch((e) => {
        // The phone dropping off adb part way through is not a failed launch.
        // Samsung's MTP service switches the port to file transfer about five
        // seconds after it is plugged in, which restarts adbd and every adb
        // connection with it — right through a deploy that started on replug.
        // scrcpy reconnects by itself and makes a new display, and this effect
        // deploys onto that one (or the reconnect asks for a relaunch, which
        // the session-ended effect below takes up).
        if (/device '[^']*' not found|device offline|no devices\/emulators found/.test(String(e))) {
          logInfo("launch: the phone dropped off adb part way through — deploying again once it is back");
          setProgress((p) => ({ ...p, waiting: true }));
          return;
        }
        logError(`launch: launcher deployment failed — ${String(e)}`);
        invoke("stop_mirror", { sessionKey: key }).catch(() => {});
        // The guidance errors out of install_launcher are paragraphs whose
        // first line is written to stand on its own: the screen makes that
        // line its sentence and sets the rest out underneath, line breaks kept.
        setPhase({ kind: "connect", error: String(e) });
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

  // ── The phone undid the desktop while the cable was out ──
  // Its watchdog takes the session off ten seconds after the PC goes quiet, so
  // a phone plugged back in after that has nothing of it left: the session ends
  // here, and is set up again the way a fresh connect sets it up.
  useEffect(() => {
    const un = listen<{ sessionKey: string }>("desktop:relaunch", () => {
      logInfo("launch: the phone undid the desktop while it was away — setting it up again");
      relaunching.current = true;
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
    // A relaunch can also be asked for while the launch is still going: the
    // phone dropped off adb part way through it, before the desktop was set up.
    if (phase.kind !== "ready" && !(phase.kind === "launching" && relaunching.current)) return;
    const key = `${phase.device.serial}|desktop`;
    if (sawDesktop.current && !sessions[key]) {
      if (restarting.current || relaunching.current) {
        const again = relaunching.current;
        restarting.current = false;
        relaunching.current = false;
        // After the phone's own clean-up the launcher may still be running its
        // Linux; resuming sets DeX up around it instead of reinstalling it.
        launch(phase.device, again, again ? "again" : "settings");
        return;
      }
      // Moving a running desktop onto Wi-Fi. The session had to end first —
      // `adb tcpip` restarts adbd and takes the stream down with it — so this
      // is the moment to do the switch and start again on the new serial.
      // The phone keeps its desktop profile throughout; the relaunch reapplies
      // it, which is why the restore that the ordinary path runs is skipped.
      if (switchingToWireless.current) {
        switchingToWireless.current = false;
        appWindow.unminimize().catch(() => {});
        invoke<WirelessResult>("wireless_go_wireless", { serial: phase.device.serial })
          .then(async (result) => {
            logInfo(`wireless: ${phase.device.serial} is now reachable at ${result.address}`);
            preferredSerial.current = result.serial;
            const list = await invoke<DeviceInfo[]>("adb_list_devices").catch(() => []);
            say("Wi‑Fi is set up — you can unplug the cable.", "ok");
            launch(
              list.find((d) => d.serial === result.serial) ?? {
                ...phase.device,
                serial: result.serial,
                connection: "wifi",
              },
              false,
              "wireless",
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
      // The desktop's own warnings go with it: a banner about how windows behave
      // on a display that no longer exists is read as a complaint about the app.
      setNotice("");
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
  }, [phase, sessions, launch, failLaunch, say]);

  // ── Help ──
  const [diagBusy, setDiagBusy] = useState(false);

  const openLog = useCallback(() => {
    invoke("diag_reveal", { path: null }).catch((e) => {
      logWarn(`could not open the log: ${e}`);
      say("Couldn't open the log", "bad", String(e));
    });
  }, [say]);

  /** Host + phone state in one file, for attaching to a bug report. */
  const collectDiagnostics = useCallback(async () => {
    setDiagBusy(true);
    say("Creating a diagnostics report…", "info");
    try {
      const serial = devices.find((d) => d.state === "device")?.serial ?? null;
      const path = await invoke<string>("diag_collect", { serial });
      say("Diagnostics report saved", "ok", path);
      invoke("diag_reveal", { path }).catch(() => {});
    } catch (e) {
      logError(`diagnostics failed: ${String(e)}`);
      say("Couldn't create the diagnostics report", "bad", String(e));
    } finally {
      setDiagBusy(false);
    }
  }, [devices, say]);

  const refresh = useCallback(() => {
    // Re-armed only where auto-launch acts. Armed mid-launch, a launch that
    // then failed would be relaunched by the next poll before its error could
    // be read; armed while a desktop runs, "Use another phone" after it would
    // open the same one straight away.
    const now = phaseRef.current.kind;
    if (now === "boot" || now === "connect") armedRef.current = true;
    invoke<DeviceInfo[]>("adb_list_devices")
      .then((list) => {
        scannedOnce.current = true;
        setDevices(list);
      })
      .catch(() => {});
  }, []);

  /** After a launch failed: straight back in if the phone is still there, otherwise wait for it. */
  const retry = useCallback(() => {
    const ready =
      devices.find((d) => d.state === "device" && d.serial === preferredSerial.current) ??
      devices.find((d) => d.state === "device");
    if (ready) {
      launch(ready);
    } else {
      setPhase({ kind: "connect" });
      refresh();
    }
  }, [devices, launch, refresh]);

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
  const switchToWireless = useCallback(
    (device: DeviceInfo) => {
      switchingToWireless.current = true;
      setNotice("");
      setSwitching(true);
      invoke("stop_mirror", { sessionKey: `${device.serial}|desktop` }).catch((e) => {
        switchingToWireless.current = false;
        setSwitching(false);
        logWarn(`wireless: could not stop the session to switch — ${String(e)}`);
        say("Couldn't switch to Wi‑Fi", "bad", String(e));
      });
    },
    [say],
  );

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

  /**
   * The way back out of a fullscreen app, and the way to close it.
   *
   * On a phone that draws titlebars these are the caption's own buttons and the
   * taskbar underneath. On a phone that cannot — an untrusted display gets no
   * system decorations, so no freeform, so every app covers the launcher whole —
   * there is nothing left on screen to press, and these are the only way back.
   * Both go through the uid-2000 daemon, which needs neither a trusted display
   * nor a focused window. See wm.rs.
   *
   * Offered from the help menu on every screen, not only the "running" one:
   * the desktop keeps running when this app restarts or loses track of its
   * session, and the moment a person needs these is exactly the moment the
   * "running" screen may not be the one in front of them. The commands ask the
   * phone what is running rather than trusting this window's idea of it.
   */
  const backToLauncher = useCallback(() => {
    invoke<boolean>("desktop_home")
      .then((done) => {
        if (!done) say("The desktop didn't answer. Is it running?", "warn");
      })
      .catch((e) => say("Couldn't reach the desktop", "bad", String(e)));
  }, [say]);

  const closeTopWindow = useCallback(() => {
    invoke<boolean>("desktop_close_top")
      .then((done) => {
        if (!done) say("No app window to close", "info");
      })
      .catch((e) => say("Couldn't reach the desktop", "bad", String(e)));
  }, [say]);

  /** The phone "Open desktop" means: the one asked for, as auto-launch and retry choose it. */
  const readyDevice =
    devices.find((d) => d.state === "device" && d.serial === preferredSerial.current) ??
    devices.find((d) => d.state === "device");
  /** The stream dropped and scrcpy is bringing it back by itself. */
  const stalled =
    phase.kind === "ready" && sessions[`${phase.device.serial}|desktop`]?.status === "reconnecting";

  const helpMenu = (
    <Menu
      label="Help"
      trigger={({ open, toggle, ref }) => (
        <button
          ref={ref}
          className={`btn btn-quiet btn-sm btn-icon ${open ? "!bg-[var(--surface-3)] !text-[var(--ink-1)]" : ""}`}
          onClick={toggle}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label="Help and troubleshooting"
          title="Help and troubleshooting"
        >
          <Icon name="help" size={17} />
        </button>
      )}
    >
      {(close) => {
        const pick = (f: () => void) => () => {
          close();
          f();
        };
        return (
          <>
            <MenuItem icon="refresh" onSelect={pick(refresh)}>
              Look for phones again
            </MenuItem>
            <MenuItem icon="doc" onSelect={pick(openLog)}>
              Open the log
            </MenuItem>
            <MenuItem icon="activity" disabled={diagBusy} onSelect={pick(collectDiagnostics)}>
              {diagBusy ? "Creating report…" : "Create a diagnostics report"}
            </MenuItem>
            <div className="menu-sep" />
            <p className="menu-label">If the desktop is stuck</p>
            <MenuItem icon="home" onSelect={pick(backToLauncher)}>
              Home screen
            </MenuItem>
            <MenuItem icon="windowClose" onSelect={pick(closeTopWindow)}>
              Close the front app
            </MenuItem>
            <div className="menu-sep" />
            <MenuItem
              icon="arrowUpRight"
              onSelect={pick(() => {
                openUrl(ISSUES_URL).catch((e) => say("Couldn't open the browser", "bad", String(e)));
              })}
            >
              Report a problem
            </MenuItem>
            {version && (
              <p className="flex items-center gap-2 px-2.5 pb-1 pt-1.5 text-[11px] text-[var(--ink-3)]">
                <img src="/logo.svg" alt="" width={14} height={14} draggable={false} />
                Open Android DeX {version}
              </p>
            )}
          </>
        );
      }}
    </Menu>
  );

  return (
    <div className="app-backdrop flex h-screen flex-col">
      <TitleBar tab={tab} onTab={setTab} menu={helpMenu} desktopBusy={phase.kind === "launching" || switching} />
      <main className="relative min-h-0 flex-1 overflow-y-auto">
        <div key={tab} className="fade-in min-h-full">
          {tab === "desktop" ? (
            <DesktopPage
              phase={phase}
              adb={adb}
              reconnecting={reconnecting}
              progress={progress}
              switching={switching}
              devices={devices}
              ready={readyDevice}
              stalled={stalled}
              notice={notice}
              method={method}
              onMethod={setMethod}
              wireless={<WirelessPanel devices={devices} onConnected={onWirelessConnected} />}
              actions={{
                launch: (d) => launch(d),
                retry,
                retryAdb: () => setAdbAttempt((n) => n + 1),
                openLog,
                diagnostics: collectDiagnostics,
                showDesktop,
                goWireless: switchToWireless,
                home: backToLauncher,
                closeTop: closeTopWindow,
                disconnect: stopDesktop,
                chooseAnother: () => setPhase({ kind: "connect" }),
              }}
            />
          ) : tab === "apps" ? (
            <AppsPanel device={readyDevice} say={say} />
          ) : (
            <RemotePanel devices={devices} />
          )}
        </div>
      </main>
      <Toast toast={toast} onDismiss={dismiss} />
    </div>
  );
}
