import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { invoke } from "@tauri-apps/api/core";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { IS_WINDOWS, MDNS_HINT, THIS_COMPUTER, THIS_COMPUTER_CAP } from "../../lib/host";
import type {
  DeviceInfo,
  KnownDevice,
  MdnsService,
  ProjectionSupport,
  QrChallenge,
  WirelessResult,
  WirelessSupport,
} from "../../lib/types";
import { Icon, type IconName } from "../../ui/Icon";
import { Disclosure, Menu, MenuItem, phoneLabel } from "../../ui/kit";
import { deviceName } from "../desktop/model";

/**
 * Wi-Fi: the phones that have been on it before, one click each, and the ways
 * to put a new one on it.
 *
 * "With a cable, once" is the first way and stays the default even when no
 * cable is plugged in, because it is the only route that does not depend on
 * mDNS — and mDNS is the part that quietly fails on routers that filter
 * multicast, on guest networks with client isolation, and on corporate
 * machines where the firewall rule was never created. It is also the only one
 * that works on every Android version. The two pairing routes (Android 11+)
 * are one click away and say what they need. Reading an IP address off a
 * Settings screen is the thing this panel exists to avoid, so manual entry is
 * a disclosure at the bottom rather than the main event.
 *
 * "project" is the odd one out: it hands the phone to Windows' own Miracast
 * receiver instead of connecting it here, so it ends in Samsung's DeX rather
 * than ours. It earns its place as the fallback for a PC where ADB cannot be
 * made to work at all — see projection.rs for why we cannot host that stream
 * ourselves. That way is Windows-only, and hidden rather than disabled
 * elsewhere: there is no Miracast receiver for a Mac and there cannot be one,
 * so a greyed-out "Samsung DeX" would only send someone looking for a setting
 * that does not exist. The three ADB routes are unaffected.
 */
type Way = "usb" | "qr" | "code" | "project";

/** How often the QR is checked for a scan. Android Studio polls at the same rate. */
const QR_POLL_MS = 1200;
/** Refresh rate for "a phone is waiting to be paired" while the code tab is open. */
const DISCOVER_POLL_MS = 2000;

const P = ({ children }: { children: ReactNode }) => <span className="ui-path">{children}</span>;

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
      className="fade-in h-full w-full"
      role="img"
      aria-label="Pairing QR code"
    >
      <rect x={-4} y={-4} width={span} height={span} fill="#ffffff" />
      <path d={path} fill="#000000" />
    </svg>
  );
}

function WayCard({
  way,
  current,
  icon,
  title,
  hint,
  onPick,
}: {
  way: Way;
  current: Way;
  icon: IconName;
  title: string;
  hint: string;
  onPick: (w: Way) => void;
}) {
  return (
    <button type="button" role="radio" aria-checked={current === way} className="choice" onClick={() => onPick(way)}>
      <Icon name={icon} size={18} />
      <span>
        <span className="choice-title block">{title}</span>
        <span className="choice-hint block">{hint}</span>
      </span>
    </button>
  );
}

/** A line under a step list that says what the app is waiting for. */
function Waiting({ children, tone = "accent" }: { children: ReactNode; tone?: "accent" | "ok" | "warn" }) {
  return (
    <p className="caption flex items-center gap-2">
      <span className={`dot dot-${tone} ${tone === "accent" ? "dot-live" : ""}`} aria-hidden="true" />
      {children}
    </p>
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
  const [way, setWay] = useState<Way>("usb");
  const [busy, setBusy] = useState("");
  /** Which saved phone the busy state belongs to, so only its button spins. */
  const [busyAddress, setBusyAddress] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [support, setSupport] = useState<WirelessSupport | null>(null);
  const [known, setKnown] = useState<KnownDevice[]>([]);
  const [qr, setQr] = useState<QrChallenge | null>(null);
  const [waiting, setWaiting] = useState<MdnsService[]>([]);
  const [code, setCode] = useState("");
  const [manual, setManual] = useState("");
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
      setBusyAddress(null);
      setError("");
      refreshKnown();
      onConnected(result);
    },
    [onConnected, refreshKnown],
  );

  const fail = useCallback((e: unknown) => {
    logError(`wireless: ${String(e)}`);
    setBusy("");
    setBusyAddress(null);
    setError(String(e));
  }, []);

  // ── The cable, once ──
  const goWireless = useCallback(() => {
    if (!usbDevice) return;
    setError("");
    setBusy("Setting up Wi‑Fi…");
    invoke<WirelessResult>("wireless_go_wireless", { serial: usbDevice.serial })
      .then(succeed)
      .catch(fail);
  }, [usbDevice, succeed, fail]);

  // ── QR pairing ──
  // The code is armed when the way is picked and cancelled when it is left,
  // because a live challenge is a password sitting in memory waiting for
  // whoever scans it — it should not outlive the screen showing it.
  useEffect(() => {
    if (way !== "qr" || !support?.mdns) return;
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
  }, [way, support?.mdns, fail]);

  useEffect(() => {
    if (way !== "qr" || !qr) return;
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
  }, [way, qr, succeed, fail]);

  // ── Pairing code ──
  // Poll for phones sitting on the pairing screen, purely so the user can see
  // that theirs has been found before they type anything.
  useEffect(() => {
    if (way !== "code" || !support?.mdns) return;
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
  }, [way, support?.mdns]);

  // ── Windows projection ──
  // Only probed when the way is picked: it spawns netsh, and nobody who is
  // going to use a cable should pay for that.
  useEffect(() => {
    if (way !== "project" || projection) return;
    invoke<ProjectionSupport>("projection_support").then(setProjection).catch(() => {});
  }, [way, projection]);

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
      setBusy(`Connecting to ${phoneLabel(device.label)}…`);
      setBusyAddress(device.address);
      // Scoped to this row: without it, a reply from a different saved phone
      // would be reported as this one.
      invoke<string[]>("wireless_reconnect_known", { only: device.address })
        .then((addresses) => {
          const address = addresses[0];
          if (!address) {
            throw new Error(
              `${phoneLabel(device.label)} didn't answer. Check that it's awake and on the same Wi‑Fi — ` +
                `and if it has restarted since, connect it with the cable once to set it up again.`,
            );
          }
          succeed({ serial: address, address, label: device.label });
        })
        .catch((e) => fail(e instanceof Error ? e.message : e));
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
  const noDiscovery = (
    <div className="callout callout-warn">
      <Icon name="alert" size={16} />
      <p>
        {THIS_COMPUTER_CAP} can't find phones on the network ({support?.detail}), so pairing isn't available. {MDNS_HINT}{" "}
        Connecting with a cable once doesn't need it.
      </p>
    </div>
  );

  return (
    <div className="flex flex-col gap-6">
      {known.length > 0 && (
        <section>
          <p className="section-label mb-2 px-1">Your phones</p>
          <div className="card rows">
            {known.map((d) => {
              const named = phoneLabel(d.label);
              const mine = busyAddress === d.address;
              return (
                <div key={d.address} className="flex items-center gap-3 px-3.5 py-3">
                  <span className="tile">
                    <Icon name="phone" size={17} />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="heading truncate">{named}</p>
                    <p className="caption truncate">{d.address === d.label ? "Paired over Wi‑Fi" : d.address}</p>
                  </div>
                  <button className="btn btn-secondary btn-sm" onClick={() => reconnect(d)} disabled={!!busy}>
                    {mine ? <span className="spinner !h-3 !w-3 !border-[1.5px]" aria-hidden="true" /> : <Icon name="wifi" size={14} />}
                    {mine ? "Connecting…" : "Connect"}
                  </button>
                  <Menu
                    label={`${named} options`}
                    trigger={({ open, toggle, ref }) => (
                      <button
                        ref={ref}
                        className={`btn btn-quiet btn-sm btn-icon ${open ? "!bg-[var(--surface-2)]" : ""}`}
                        onClick={toggle}
                        aria-haspopup="menu"
                        aria-expanded={open}
                        aria-label={`More for ${named}`}
                      >
                        <Icon name="more" size={16} />
                      </button>
                    )}
                  >
                    {(close) => (
                      <MenuItem
                        icon="trash"
                        danger
                        onSelect={() => {
                          close();
                          forget(d);
                        }}
                      >
                        Forget this phone
                      </MenuItem>
                    )}
                  </Menu>
                </div>
              );
            })}
          </div>
        </section>
      )}

      <section>
        <p className="section-label mb-2 px-1">{known.length > 0 ? "Add another phone" : "Choose a way to connect"}</p>
        <div role="radiogroup" aria-label="Ways to connect over Wi-Fi" className={`grid gap-2 ${IS_WINDOWS ? "grid-cols-4" : "grid-cols-3"}`}>
          <WayCard way="usb" current={way} onPick={setWay} icon="cable" title="Cable, once" hint="Any phone · easiest" />
          <WayCard way="qr" current={way} onPick={setWay} icon="qr" title="Scan a code" hint="Android 11 or newer" />
          <WayCard way="code" current={way} onPick={setWay} icon="keypad" title="Type a code" hint="Android 11 or newer" />
          {/* Windows only, and asked as such. Miracast is a Windows receiver:
              this way used to be drawn for anything that was not a Mac, which
              made it appear on Linux, where every step behind it leads to a
              Settings page that does not exist. */}
          {IS_WINDOWS && (
            <WayCard way="project" current={way} onPick={setWay} icon="cast" title="Samsung DeX" hint="Samsung · no USB debugging" />
          )}
        </div>

        <div key={way} className="card fade-in mt-2.5 px-4 py-4">
          {way === "usb" &&
            (alreadyWireless ? (
              <div className="callout callout-ok">
                <Icon name="check" size={16} />
                <p>This phone is already set up for Wi‑Fi — you can unplug the cable.</p>
              </div>
            ) : usbDevice ? (
              <div className="flex flex-col items-start gap-3">
                <p className="body">
                  <span className="font-semibold text-[var(--ink-1)]">{deviceName(usbDevice)}</span> is plugged in. One
                  click and it's on Wi‑Fi — no address to find, no code to type.
                </p>
                <button className="btn btn-primary" onClick={goWireless} disabled={!!busy}>
                  {busy ? <span className="spinner !h-3.5 !w-3.5 !border-[1.5px] !border-white/30 !border-t-white" aria-hidden="true" /> : <Icon name="wifi" size={15} />}
                  {busy || "Set up Wi‑Fi"}
                </button>
                <p className="caption">You can unplug as soon as it's done. It stays set up until the phone restarts.</p>
              </div>
            ) : (
              <div className="flex flex-col gap-3">
                <p className="body">The simplest way, and it works on every Android phone:</p>
                <ol className="howto">
                  <li>Connect your phone with a USB cable. Its desktop opens.</li>
                  <li>
                    Choose <P>Go wireless</P> on the screen that appears here.
                  </li>
                  <li>Unplug the cable. From then on it connects over Wi‑Fi, until the phone restarts.</li>
                </ol>
              </div>
            ))}

          {way === "qr" &&
            (mdnsDown ? (
              noDiscovery
            ) : (
              <div className="flex items-center gap-5">
                <div className="h-[164px] w-[164px] shrink-0 overflow-hidden rounded-[var(--r-md)] bg-white p-1.5 shadow-[0_8px_24px_-8px_rgba(0,0,0,0.6)]">
                  {qr ? (
                    <QrImage challenge={qr} />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center">
                      <span className="spinner !border-black/10 !border-t-black/50" aria-hidden="true" />
                    </div>
                  )}
                </div>
                <div className="flex min-w-0 flex-col gap-3.5">
                  <ol className="howto">
                    <li>
                      On your phone, open <P>Settings</P> → <P>Developer options</P> → <P>Wireless debugging</P> and turn it on.
                    </li>
                    <li>
                      Tap <P>Pair device with QR code</P>.
                    </li>
                    <li>Point the camera at this code.</li>
                  </ol>
                  <Waiting>{busy || "Waiting for your phone to scan…"}</Waiting>
                </div>
              </div>
            ))}

          {way === "code" && (
            <div className="flex flex-col gap-4">
              {mdnsDown ? (
                noDiscovery
              ) : (
                <ol className="howto">
                  <li>
                    On your phone, open <P>Settings</P> → <P>Developer options</P> → <P>Wireless debugging</P> and turn it on.
                  </li>
                  <li>
                    Tap <P>Pair device with pairing code</P>.
                  </li>
                  <li>Type the six digits it shows — the address is found for you.</li>
                </ol>
              )}
              <div className="flex items-center gap-2.5">
                <input
                  value={code}
                  inputMode="numeric"
                  maxLength={6}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                  onKeyDown={(e) => e.key === "Enter" && code.length === 6 && !busy && pair()}
                  placeholder="000000"
                  aria-label="Pairing code"
                  className="field code-input w-[176px]"
                />
                <button className="btn btn-primary btn-lg" onClick={pair} disabled={!!busy || code.length !== 6}>
                  {busy === "Pairing…" && <span className="spinner !h-3.5 !w-3.5 !border-[1.5px] !border-white/30 !border-t-white" aria-hidden="true" />}
                  {busy === "Pairing…" ? "Pairing…" : "Pair"}
                </button>
              </div>
              {!mdnsDown &&
                (waiting.length === 0 ? (
                  <Waiting>No phone is waiting to pair yet — keep the pairing screen open on it.</Waiting>
                ) : waiting.length === 1 ? (
                  <Waiting tone="ok">Found a phone waiting at {waiting[0].address}.</Waiting>
                ) : (
                  <Waiting tone="warn">
                    {waiting.length} phones are waiting — enter the address below so the right one is used.
                  </Waiting>
                ))}
            </div>
          )}

          {way === "project" && (
            <div className="flex flex-col gap-4">
              {/* Said plainly and first. Someone who follows these steps gets a
                  desktop that looks like this app's but is not — no taskbar from
                  us, no widgets, no file drop — and finding that out afterwards
                  would be worse than being told now. */}
              <p className="body">
                Samsung phones can cast DeX straight to Windows, with no cable and no USB debugging. It hands the phone to
                Windows, so{" "}
                <span className="text-[var(--warn)]">you get Samsung's own DeX, not this app's desktop</span> — the taskbar,
                widgets and file drop here won't apply. Use it when nothing else works.
              </p>

              {projection?.miracast === false && (
                <div className="callout callout-warn">
                  <Icon name="alert" size={16} />
                  <p>
                    This PC reports no Miracast support, so the phone won't find it. It needs a Wi‑Fi adapter with Wi‑Fi
                    Direct — a USB Wi‑Fi dongle is enough. ({projection.detail})
                  </p>
                </div>
              )}
              {projection?.miracast === true && (
                <div className="callout callout-ok">
                  <Icon name="check" size={16} />
                  <p>This PC's Wi‑Fi supports Miracast.</p>
                </div>
              )}

              <div>
                <p className="section-label mb-2.5">On this PC</p>
                <ol className="howto">
                  <li>
                    Install <P>Wireless Display</P>. On Windows 11 24H2 and 25H2 it is usually missing from the Optional
                    features list, so the button below installs it by name. It needs administrator approval and a restart.
                  </li>
                  <li>
                    Open <P>Projecting to this PC</P> and change the top setting from <P>Never</P> to{" "}
                    <P>Available everywhere</P> — it's off by default, and this is the step people miss.
                  </li>
                  <li>
                    Open the <P>Wireless Display</P> app from Start and leave it on screen, ready to accept.
                  </li>
                </ol>
                <div className="mt-3.5 flex flex-wrap gap-2">
                  <button className="btn btn-secondary btn-sm" onClick={installReceiver} disabled={!!busy}>
                    <Icon name="download" size={14} />
                    Install Wireless Display
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={copyInstallCommand}>
                    <Icon name={copied ? "check" : "copy"} size={14} />
                    {copied ? "Copied" : "Copy command"}
                  </button>
                  <button className="btn btn-primary btn-sm" onClick={() => openSettings("project")}>
                    <Icon name="cast" size={14} />
                    Projecting to this PC
                  </button>
                </div>
                {installing && (
                  <p className="caption mt-2.5 text-[#9ff0c9]">
                    It's installing in a separate window. When it finishes, restart Windows — the receiver only appears
                    after a restart.
                  </p>
                )}
                <button
                  className="link muted mt-2 text-[12px]"
                  onClick={() => openSettings("features")}
                  title="Only useful on builds where it is actually listed"
                >
                  Open the Optional features list
                </button>
              </div>

              <div>
                <p className="section-label mb-2.5">On your phone</p>
                <ol className="howto">
                  <li>
                    Swipe down Quick Settings and tap <P>DeX</P> (or <P>Settings</P> → <P>Connected devices</P> →{" "}
                    <P>Samsung DeX</P>).
                  </li>
                  <li>Pick this PC from the list by its name.</li>
                  <li>Accept the connection when this PC asks.</li>
                </ol>
              </div>

              <p className="caption">
                Both need Wi‑Fi on. Miracast links the phone to the PC directly, so it works without a router — but a VPN or
                a second network adapter on this PC often stops the phone finding it.
              </p>
            </div>
          )}
        </div>
      </section>

      {error && (
        <div className="callout callout-warn fade-in">
          <Icon name="alert" size={16} />
          <p className="whitespace-pre-wrap select-text">{error}</p>
        </div>
      )}

      <Disclosure title="Enter an address instead" defaultOpen={false} className="-mt-1 px-1">
        <div className="flex gap-2">
          <input
            value={manual}
            onChange={(e) => setManual(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && connectManual()}
            placeholder="192.168.1.100  or  192.168.1.100:5555"
            aria-label="Phone address"
            spellCheck={false}
            className="field min-w-0 flex-1"
          />
          <button className="btn btn-secondary btn-lg" onClick={connectManual} disabled={!!busy || !manual.trim()}>
            Connect
          </button>
        </div>
        <p className="caption mt-2">
          {way === "code" ? (
            <>
              While you're pairing with a code, this address is used for the pairing. The pairing screen on the phone shows
              its own address — a different port from the one on the Wireless debugging screen behind it.
            </>
          ) : (
            <>For a phone that is already set up for wireless debugging, on the same network as {THIS_COMPUTER}.</>
          )}
        </p>
      </Disclosure>
    </div>
  );
}
