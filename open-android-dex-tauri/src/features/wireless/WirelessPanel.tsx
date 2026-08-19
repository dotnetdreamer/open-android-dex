import { useCallback, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { IS_MAC, MDNS_HINT, THIS_COMPUTER, THIS_COMPUTER_CAP } from "../../lib/host";
import type {
  DeviceInfo,
  KnownDevice,
  MdnsService,
  ProjectionSupport,
  QrChallenge,
  WirelessResult,
  WirelessSupport,
} from "../../lib/types";

/**
 * Three ways onto Wi-Fi, and the whole point of the ordering is that the first
 * one needs no typing at all. Reading an IP address off a Settings screen is
 * the thing this panel exists to avoid, so manual entry is a disclosure at the
 * bottom rather than the main event.
 *
 * "Use a cable once" is the default and stays the default even when no cable
 * is plugged in, because it is the only route that does not depend on mDNS —
 * and mDNS is the part that quietly fails on routers that filter multicast,
 * on guest networks with client isolation, and on corporate machines where the
 * firewall rule was never created. The other two tabs are one click away and
 * say what they do.
 *
 * "project" is the odd one out: it hands the phone to Windows' own Miracast
 * receiver instead of connecting it here, so it ends in Samsung's DeX rather
 * than ours. It earns its place as the fallback for a PC where ADB cannot be
 * made to work at all — see projection.rs for why we cannot host that stream
 * ourselves.
 *
 * That tab is Windows-only, and hidden rather than disabled on macOS: there is
 * no Miracast receiver for a Mac and there cannot be one, so a greyed-out
 * "Project to PC" would only send someone looking for a setting that does not
 * exist. The three ADB routes are unaffected.
 */
type Tab = "usb" | "qr" | "code" | "project";

/** How often the QR is checked for a scan. Android Studio polls at the same rate. */
const QR_POLL_MS = 1200;
/** Refresh rate for "a phone is waiting to be paired" while the code tab is open. */
const DISCOVER_POLL_MS = 2000;

function Mono({ children }: { children: string }) {
  return (
    <code className="rounded bg-white/10 px-1.5 py-0.5 font-mono text-[11px] text-slate-200">
      {children}
    </code>
  );
}

/**
 * The QR, drawn from the module matrix Rust sent.
 *
 * Deliberately black on white whatever the window's theme is: this is being
 * pointed at a camera, and a dark-mode QR is one more thing that can stop a
 * scan from working. The four-module quiet zone is part of the format, not
 * padding — scanners need it to find the symbol at all.
 */
function QrImage({ challenge }: { challenge: QrChallenge }) {
  const path = useMemo(() => {
    const { width, modules } = challenge;
    let d = "";
    for (let y = 0; y < width; y++) {
      for (let x = 0; x < width; x++) {
        if (modules[y * width + x]) d += `M${x} ${y}h1v1h-1z`;
      }
    }
    return d;
  }, [challenge]);

  const span = challenge.width + 8;
  return (
    <svg
      viewBox={`-4 -4 ${span} ${span}`}
      shapeRendering="crispEdges"
      className="h-[188px] w-[188px] rounded-lg"
      role="img"
      aria-label="Pairing QR code"
    >
      <rect x={-4} y={-4} width={span} height={span} fill="#ffffff" />
      <path d={path} fill="#000000" />
    </svg>
  );
}

function Steps({ items }: { items: React.ReactNode[] }) {
  return (
    <ol className="flex list-decimal flex-col gap-1.5 pl-4 text-[12px] leading-relaxed text-slate-400 marker:text-slate-600">
      {items.map((item, i) => (
        <li key={i}>{item}</li>
      ))}
    </ol>
  );
}

export function WirelessPanel({
  devices,
  onConnected,
}: {
  devices: DeviceInfo[];
  /** A phone is reachable over Wi-Fi — launch the desktop on this serial. */
  onConnected: (result: WirelessResult) => void;
}) {
  const [tab, setTab] = useState<Tab>("usb");
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [support, setSupport] = useState<WirelessSupport | null>(null);
  const [known, setKnown] = useState<KnownDevice[]>([]);
  const [qr, setQr] = useState<QrChallenge | null>(null);
  const [waiting, setWaiting] = useState<MdnsService[]>([]);
  const [code, setCode] = useState("");
  const [manual, setManual] = useState("");
  const [showManual, setShowManual] = useState(false);
  const [projection, setProjection] = useState<ProjectionSupport | null>(null);
  const [installing, setInstalling] = useState(false);
  const [copied, setCopied] = useState(false);

  /** A phone on the cable, ready to be sent wireless. */
  const usbDevice = devices.find((d) => d.connection === "usb" && d.state === "device");
  /** …and whether that same phone is already reachable without the cable. */
  const alreadyWireless = devices.some(
    (d) =>
      d.connection === "wifi" &&
      d.state === "device" &&
      !!d.hardwareSerial &&
      d.hardwareSerial === usbDevice?.hardwareSerial,
  );

  const refreshKnown = useCallback(() => {
    invoke<KnownDevice[]>("wireless_known").then(setKnown).catch(() => {});
  }, []);

  useEffect(() => {
    refreshKnown();
    invoke<WirelessSupport>("wireless_support").then(setSupport).catch(() => {});
  }, [refreshKnown]);

  const succeed = useCallback(
    (result: WirelessResult) => {
      logInfo(`wireless: ${result.label} is on Wi-Fi at ${result.address}`);
      setBusy("");
      setError("");
      refreshKnown();
      onConnected(result);
    },
    [onConnected, refreshKnown],
  );

  const fail = useCallback((e: unknown) => {
    logError(`wireless: ${String(e)}`);
    setBusy("");
    setError(String(e));
  }, []);

  // ── The cable, once ──
  const goWireless = useCallback(() => {
    if (!usbDevice) return;
    setError("");
    setBusy("Switching the phone to Wi-Fi…");
    invoke<WirelessResult>("wireless_go_wireless", { serial: usbDevice.serial })
      .then(succeed)
      .catch(fail);
  }, [usbDevice, succeed, fail]);

  // ── QR pairing ──
  // The code is armed when the tab opens and cancelled when it closes, because
  // a live challenge is a password sitting in memory waiting for whoever scans
  // it — it should not outlive the screen showing it.
  useEffect(() => {
    if (tab !== "qr" || !support?.mdns) return;
    let live = true;
    setError("");
    invoke<QrChallenge>("wireless_qr_begin")
      .then((c) => live && setQr(c))
      .catch((e) => live && fail(e));
    return () => {
      live = false;
      setQr(null);
      invoke("wireless_qr_cancel").catch(() => {});
    };
  }, [tab, support?.mdns, fail]);

  useEffect(() => {
    if (tab !== "qr" || !qr) return;
    let live = true;
    const timer = setInterval(() => {
      invoke<WirelessResult | null>("wireless_qr_poll")
        .then((result) => {
          if (live && result) succeed(result);
        })
        .catch((e) => {
          if (!live) return;
          // The challenge is spent whether it worked or not, so a failure has
          // to hand out a fresh one — otherwise the code on screen is a
          // picture of something the phone will never answer.
          fail(e);
          invoke<QrChallenge>("wireless_qr_begin").then(setQr).catch(() => {});
        });
    }, QR_POLL_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [tab, qr, succeed, fail]);

  // ── Pairing code ──
  // Poll for phones sitting on the pairing screen, purely so the user can see
  // that theirs has been found before they type anything.
  useEffect(() => {
    if (tab !== "code" || !support?.mdns) return;
    let live = true;
    const poll = () =>
      invoke<MdnsService[]>("wireless_discover")
        .then((list) => live && setWaiting(list))
        .catch(() => {});
    poll();
    const timer = setInterval(poll, DISCOVER_POLL_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [tab, support?.mdns]);

  // ── Windows projection ──
  // Only probed when the tab is opened: it spawns netsh, and nobody who is
  // going to use a cable should pay for that.
  useEffect(() => {
    if (tab !== "project" || projection) return;
    invoke<ProjectionSupport>("projection_support").then(setProjection).catch(() => {});
  }, [tab, projection]);

  const openSettings = useCallback((page: "project" | "features") => {
    invoke("projection_open_settings", { page }).catch((e) => setError(String(e)));
  }, []);

  const installReceiver = useCallback(() => {
    setError("");
    setBusy("Waiting for the administrator prompt…");
    invoke("projection_install_receiver")
      .then(() => {
        setBusy("");
        setInstalling(true);
      })
      .catch(fail);
  }, [fail]);

  const copyInstallCommand = useCallback(async () => {
    try {
      const cmd = await invoke<string>("projection_install_command");
      await writeText(cmd);
      setCopied(true);
      setTimeout(() => setCopied(false), 2200);
    } catch (e) {
      setError(String(e));
    }
  }, []);

  const pair = useCallback(() => {
    setError("");
    setBusy("Pairing…");
    invoke<WirelessResult>("wireless_pair", {
      code: code.trim(),
      // Left null unless the user opened the manual field: the address is
      // discovered for them, which is the only reason this flow is easier
      // than typing an IP.
      address: manual.trim() || null,
    })
      .then(succeed)
      .catch(fail);
  }, [code, manual, succeed, fail]);

  // ── Saved phones ──
  const reconnect = useCallback(
    (device: KnownDevice) => {
      setError("");
      setBusy(`Reconnecting to ${device.label}…`);
      // Scoped to this row: without it, a reply from a different saved phone
      // would be reported as this one.
      invoke<string[]>("wireless_reconnect_known", { only: device.address })
        .then((addresses) => {
          const address = addresses[0];
          if (!address) {
            throw new Error(
              `${device.label} did not answer. Check it is awake and on the same Wi-Fi — ` +
                `and if it has been restarted since, plug the cable in once to set it up again.`,
            );
          }
          succeed({ serial: address, address, label: device.label });
        })
        .catch(fail);
    },
    [succeed, fail],
  );

  const forget = useCallback(
    (device: KnownDevice) => {
      invoke("wireless_forget", { address: device.address })
        .then(refreshKnown)
        .catch(() => {});
    },
    [refreshKnown],
  );

  const connectManual = useCallback(() => {
    const addr = manual.trim();
    if (!addr) return;
    setError("");
    setBusy(`Connecting to ${addr}…`);
    invoke<string>("adb_connect", { address: addr.includes(":") ? addr : `${addr}:5555` })
      .then(() => succeed({ serial: addr.includes(":") ? addr : `${addr}:5555`, address: addr, label: addr }))
      .catch(fail);
  }, [manual, succeed, fail]);

  const mdnsDown = support != null && !support.mdns;

  return (
    <div className="mt-5">
      <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
        Connect over Wi-Fi
      </p>

      <div className="tabs" role="tablist">
        <button
          role="tab"
          aria-selected={tab === "usb"}
          className={`tab ${tab === "usb" ? "active" : ""}`}
          onClick={() => setTab("usb")}
        >
          <span aria-hidden="true">🔌</span> Use a cable once
        </button>
        <button
          role="tab"
          aria-selected={tab === "qr"}
          className={`tab ${tab === "qr" ? "active" : ""}`}
          onClick={() => setTab("qr")}
        >
          <span aria-hidden="true">▩</span> Scan a code
        </button>
        <button
          role="tab"
          aria-selected={tab === "code"}
          className={`tab ${tab === "code" ? "active" : ""}`}
          onClick={() => setTab("code")}
        >
          <span aria-hidden="true">🔢</span> Type a code
        </button>
        {!IS_MAC && (
          <button
            role="tab"
            aria-selected={tab === "project"}
            className={`tab ${tab === "project" ? "active" : ""}`}
            onClick={() => setTab("project")}
          >
            <span aria-hidden="true">🖥️</span> Project to PC
          </button>
        )}
      </div>

      <div className="rounded-b-xl border border-t-0 border-white/10 bg-white/[0.03] px-4 py-4">
        {tab === "usb" && (
          <div className="flex flex-col gap-3">
            <p className="text-[12.5px] leading-relaxed text-slate-300">
              Plug the phone in once and this sets up Wi-Fi for you — no address to find,
              no code to type. It works on every Android version.
            </p>
            {alreadyWireless ? (
              <p className="text-[12px] text-teal-300">
                ✓ This phone is already reachable over Wi-Fi — you can unplug the cable.
              </p>
            ) : usbDevice ? (
              <>
                <button className="btn-accent self-start" onClick={goWireless} disabled={!!busy}>
                  <span aria-hidden="true">📶</span>
                  {busy ? busy : `Set up Wi-Fi for ${usbDevice.model || usbDevice.serial}`}
                </button>
                <p className="text-[11.5px] text-slate-500">
                  You can unplug the cable as soon as this finishes. It stays set up until
                  the phone restarts.
                </p>
              </>
            ) : (
              <Steps
                items={[
                  <>Connect the phone to {THIS_COMPUTER} with a USB cable.</>,
                  <>
                    Turn on USB debugging: Settings → About phone → Software information →
                    tap <em>Build number</em> seven times, then Settings → Developer options →{" "}
                    <em>USB debugging</em>.
                  </>,
                  <>Tap <em>Allow</em> on the phone, and this button will appear here.</>,
                ]}
              />
            )}
          </div>
        )}

        {tab === "qr" && (
          <div className="flex gap-4">
            {mdnsDown ? (
              <p className="text-[12.5px] leading-relaxed text-amber-300/90">
                {THIS_COMPUTER_CAP} cannot discover phones on the network, so code pairing
                is unavailable ({support?.detail}). {MDNS_HINT} Use a cable once instead —
                that path does not need network discovery.
              </p>
            ) : (
              <>
                <div className="shrink-0">
                  {qr ? (
                    <QrImage challenge={qr} />
                  ) : (
                    <div className="flex h-[188px] w-[188px] items-center justify-center rounded-lg bg-white/5 text-[12px] text-slate-500">
                      Preparing…
                    </div>
                  )}
                </div>
                <div className="flex min-w-0 flex-col gap-2">
                  <p className="text-[12.5px] text-slate-300">On the phone:</p>
                  <Steps
                    items={[
                      <>Settings → Developer options → <em>Wireless debugging</em>, turn it on.</>,
                      <>Tap <em>Pair device with QR code</em>.</>,
                      <>Point the camera at this code.</>,
                    ]}
                  />
                  <p className="mt-auto text-[11.5px] text-slate-500">Waiting for a scan…</p>
                </div>
              </>
            )}
          </div>
        )}

        {tab === "code" && (
          <div className="flex flex-col gap-3">
            {mdnsDown ? (
              <p className="text-[12.5px] leading-relaxed text-amber-300/90">
                {THIS_COMPUTER_CAP} cannot discover phones on the network
                ({support?.detail}), so the address has to be entered by hand below — or use
                a cable once, which does not need discovery at all. {MDNS_HINT}
              </p>
            ) : (
              <Steps
                items={[
                  <>Settings → Developer options → <em>Wireless debugging</em>, turn it on.</>,
                  <>Tap <em>Pair device with pairing code</em>.</>,
                  <>Type the six digits it shows — the address is found for you.</>,
                ]}
              />
            )}
            <div className="flex items-center gap-2">
              <input
                value={code}
                inputMode="numeric"
                maxLength={6}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                onKeyDown={(e) => e.key === "Enter" && code.length === 6 && pair()}
                placeholder="000000"
                aria-label="Pairing code"
                className="field code-input w-[150px]"
              />
              <button
                className="btn-accent shrink-0"
                onClick={pair}
                disabled={!!busy || code.length !== 6}
              >
                {busy ? busy : "Pair"}
              </button>
            </div>
            {!mdnsDown && (
              <p className="text-[11.5px] text-slate-500">
                {waiting.length === 0
                  ? "No phone is waiting to be paired yet — leave the pairing screen open."
                  : waiting.length === 1
                    ? `Found a phone waiting at ${waiting[0].address}.`
                    : `${waiting.length} phones are waiting — add the address below so the right one is picked.`}
              </p>
            )}
          </div>
        )}

        {tab === "project" && (
          <div className="flex flex-col gap-3">
            {/* Said plainly and first. Someone who follows these steps gets a
                desktop that looks like this app's but is not — no taskbar from
                us, no widgets, no file drop — and finding that out afterwards
                would be worse than being told now. */}
            <p className="text-[12.5px] leading-relaxed text-slate-300">
              Samsung phones can cast DeX straight to Windows over Miracast, with no cable
              and no ADB. This hands the phone to Windows —{" "}
              <span className="text-amber-300/90">
                you get Samsung's own DeX, not this app's desktop
              </span>
              , so the taskbar, widgets and file drop here won't apply. Use it when ADB
              can't be made to work.
            </p>

            {projection?.miracast === false && (
              <p className="text-[12px] text-amber-300/90">
                This PC reports no Miracast support, so the phone won't find it. A Wi-Fi
                adapter with Wi-Fi Direct support is required — a USB Wi-Fi dongle is
                enough. ({projection.detail})
              </p>
            )}
            {projection?.miracast === true && (
              <p className="text-[12px] text-teal-300">✓ This PC's Wi-Fi supports Miracast.</p>
            )}

            <div>
              <p className="mb-1 text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
                On this PC
              </p>
              <Steps
                items={[
                  <>
                    Install the <em>Wireless Display</em> component. On Windows 11 24H2 and
                    25H2 it is usually <strong>missing from the Optional features list</strong>,
                    so searching for it there finds nothing — the button below installs it by
                    name instead. Needs administrator approval and a restart.
                  </>,
                  <>
                    Open <em>Projecting to this PC</em> and change the top dropdown from{" "}
                    <em>Never</em> to <em>Available everywhere</em> — it is off by default,
                    and this is the step people miss.
                  </>,
                  <>
                    Open the <em>Wireless Display</em> app from Start and leave it on screen,
                    ready to accept the connection.
                  </>,
                ]}
              />
              <div className="mt-2 flex flex-wrap gap-2">
                <button className="btn-ghost" onClick={installReceiver} disabled={!!busy}>
                  <span aria-hidden="true">➕</span>
                  Install Wireless Display
                </button>
                <button className="btn-ghost" onClick={copyInstallCommand}>
                  <span aria-hidden="true">📋</span>
                  {copied ? "Copied" : "Copy command"}
                </button>
                <button className="btn-accent" onClick={() => openSettings("project")}>
                  <span aria-hidden="true">🖥️</span>
                  Projecting to this PC
                </button>
              </div>
              {installing && (
                <p className="mt-2 text-[12px] text-teal-300">
                  DISM is installing it in a separate window. When it finishes, restart
                  Windows — the receiver only appears after a reboot.
                </p>
              )}
              <button
                className="link-btn muted mt-1"
                onClick={() => openSettings("features")}
                title="Only useful on builds where it is actually listed"
              >
                Optional features list
              </button>
            </div>

            <div>
              <p className="mb-1 text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
                On the phone
              </p>
              <Steps
                items={[
                  <>
                    Swipe down the Quick Settings panel and tap <em>DeX</em> (or Settings →
                    Connected devices → <em>Samsung DeX</em>).
                  </>,
                  <>Pick this PC from the list of nearby devices by its name.</>,
                  <>Accept the connection prompt when it appears on this PC.</>,
                ]}
              />
            </div>

            <p className="text-[11px] text-slate-500">
              Both devices need Wi-Fi on. Miracast uses a direct phone-to-PC link, so it
              works without a router — but a VPN or a second active network adapter on this
              PC often stops the phone finding it.
            </p>
          </div>
        )}
      </div>

      {known.length > 0 && (
        <div className="mt-3 flex flex-col gap-1.5">
          <p className="text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            Saved phones
          </p>
          {known.map((d) => (
            <div
              key={d.address}
              className="flex items-center gap-3 rounded-xl bg-white/[0.04] px-3 py-2"
            >
              <span aria-hidden="true">📶</span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-[12.5px] text-slate-200">{d.label}</p>
                <p className="truncate text-[11px] text-slate-500">{d.address}</p>
              </div>
              <button className="link-btn" onClick={() => reconnect(d)} disabled={!!busy}>
                Connect
              </button>
              <button className="link-btn muted" onClick={() => forget(d)}>
                Forget
              </button>
            </div>
          ))}
        </div>
      )}

      {error && (
        <pre className="console mt-3 rounded-lg bg-amber-950/40 p-3 text-amber-300">{error}</pre>
      )}

      <div className="mt-3">
        <button className="link-btn muted" onClick={() => setShowManual((v) => !v)}>
          {showManual ? "▾" : "▸"} Enter an address by hand
        </button>
        {showManual && (
          <div className="mt-2 flex gap-2">
            <input
              value={manual}
              onChange={(e) => setManual(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && connectManual()}
              placeholder="192.168.1.100  or  192.168.1.100:5555"
              aria-label="Phone address"
              className="field min-w-0 flex-1"
            />
            <button className="btn-ghost shrink-0" onClick={connectManual} disabled={!!busy || !manual.trim()}>
              Connect
            </button>
          </div>
        )}
        {showManual && tab === "code" && (
          <p className="mt-1.5 text-[11px] text-slate-500">
            On the pairing screen the phone shows its own address — note that it is a{" "}
            <Mono>different port</Mono> from the one on the Wireless debugging screen behind it.
            While the code tab is open, the address above is used for pairing.
          </p>
        )}
      </div>
    </div>
  );
}
