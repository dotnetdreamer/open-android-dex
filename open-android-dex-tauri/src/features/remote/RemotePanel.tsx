import { useCallback, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { THIS_COMPUTER } from "../../lib/host";
import type { DeviceInfo, RemotePhone, RemoteProbe, RemoteUsbStatus } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Menu, MenuItem } from "../../ui/kit";
import { Stage } from "../../ui/Stage";

/**
 * Phone Linux over SSH: the phones this computer can log in to, and the two
 * ways to add one — the window's second tab.
 *
 * The whole point of the page is to make itself unnecessary. Pairing writes a
 * `Host` block for the phone into ~/.ssh, so from then on `ssh <name>` works in
 * any terminal and VS Code's Remote-SSH lists it — this page is for adding a
 * phone, seeing whether it is reachable right now, and the buttons that save
 * typing. Nothing here has to stay open for a session to work. The one thing
 * ssh cannot give is the phone's XFCE desktop, so "Open desktop" opens that in
 * a window of its own (DesktopWindow.tsx), over the same tunnel.
 *
 * Pairing over the cable comes first when a phone is plugged in, for the same
 * reason the Wi-Fi panel leads with it: adb has already proved which computer
 * is on the other end, so there is nothing to type. The code is for a phone
 * that is somewhere else.
 */

/** How often the list re-asks each phone how it is while the page is open. */
const PROBE_MS = 20_000;

type ProbeState = RemoteProbe | "probing";

/** "k7qm29x" → "K7QM-29X": grouped as the phone shows it, as it is typed. */
function formatCode(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 8);
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
}

/** How a phone is right now, as a pill and a line under its name. */
function describe(probe: ProbeState | undefined): { pill: string; tone: "ok" | "warn" | "accent" | ""; line: string; busy?: boolean } {
  if (!probe || probe === "probing") return { pill: "Checking", tone: "", line: "Reaching the phone…", busy: true };
  if (!probe.reachable) return { pill: "Offline", tone: "", line: probe.error ?? "Not reachable right now" };
  const path = `${probe.direct ? "Direct" : "Through a relay"} · ${probe.rttMs} ms`;
  switch (probe.ssh) {
    case "up":
      return {
        pill: "Online",
        tone: "ok",
        line: [path, probe.desktop === "running" ? "Desktop running" : "", probe.sessions > 0 ? `${probe.sessions} open` : ""]
          .filter(Boolean)
          .join(" · "),
      };
    case "installing":
      return { pill: "Setting up", tone: "warn", line: "Linux is still being set up on the phone" };
    case "missing":
      return { pill: "No Linux", tone: "warn", line: "Linux isn't installed on the phone yet" };
    case "failed":
      return { pill: "SSH stopped", tone: "warn", line: "The SSH server on the phone wouldn't start" };
    default:
      return { pill: "Starting", tone: "accent", line: `${path} · SSH starting` };
  }
}

export function RemotePanel({ devices }: { devices: DeviceInfo[] }) {
  const [phones, setPhones] = useState<RemotePhone[] | null>(null);
  const [probes, setProbes] = useState<Record<string, ProbeState>>({});
  const [code, setCode] = useState("");
  /** Which pairing is in flight, so its own button says so. */
  const [busy, setBusy] = useState<{ kind: "code" | "usb"; text: string } | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [usb, setUsb] = useState<{ serial: string; status: RemoteUsbStatus } | null>(null);
  const [forgetting, setForgetting] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const refresh = useCallback(() => {
    invoke<RemotePhone[]>("remote_phones")
      .then(setPhones)
      .catch((e) => {
        setPhones([]);
        setError(String(e));
      });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // ── how each phone is, re-asked while the page is open ──
  const ids = useMemo(() => (phones ?? []).map((p) => p.id).join(","), [phones]);
  useEffect(() => {
    if (!ids) return;
    let live = true;
    const probeAll = () => {
      for (const id of ids.split(",")) {
        setProbes((m) => (m[id] ? m : { ...m, [id]: "probing" }));
        invoke<RemoteProbe>("remote_probe", { id })
          .then((r) => live && setProbes((m) => ({ ...m, [id]: r })))
          .catch(() => {});
      }
    };
    probeAll();
    const timer = setInterval(probeAll, PROBE_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [ids]);

  // ── a phone on the cable (or on wireless adb — just as proven) ──
  const adbPhone = devices.find((d) => d.state === "device");
  const adbSerial = adbPhone?.serial;
  useEffect(() => {
    if (!adbSerial) {
      setUsb(null);
      return;
    }
    let live = true;
    invoke<RemoteUsbStatus>("remote_usb_status", { serial: adbSerial })
      .then((status) => live && setUsb({ serial: adbSerial, status }))
      .catch(() => live && setUsb(null));
    return () => {
      live = false;
    };
  }, [adbSerial, phones?.length]);

  const succeed = useCallback(
    (phone: RemotePhone) => {
      setBusy(null);
      setError("");
      setCode("");
      setNotice(
        `Paired with ${phone.name}. From any terminal: ${phone.sshCommand}` + (phone.note ? `. ${phone.note}` : ""),
      );
      logInfo(`remote: paired with ${phone.name} as ${phone.alias}`);
      refresh();
    },
    [refresh],
  );

  const fail = useCallback((e: unknown) => {
    logError(`remote: ${String(e)}`);
    setBusy(null);
    setNotice("");
    setError(String(e));
  }, []);

  const pairCode = useCallback(() => {
    setError("");
    setNotice("");
    setBusy({ kind: "code", text: "Looking for the phone…" });
    invoke<RemotePhone>("remote_pair_code", { code })
      .then(succeed)
      .catch(fail);
  }, [code, succeed, fail]);

  const pairUsb = useCallback(() => {
    if (!usb) return;
    setError("");
    setNotice("");
    setBusy({ kind: "usb", text: "Setting up…" });
    invoke<RemotePhone>("remote_pair_usb", { serial: usb.serial, enable: true })
      .then(succeed)
      .catch(fail);
  }, [usb, succeed, fail]);

  const copy = useCallback(async (text: string) => {
    try {
      await writeText(text);
      setCopied(text);
      setTimeout(() => setCopied(null), 2000);
    } catch (e) {
      setError(String(e));
    }
  }, []);

  const forget = useCallback(
    (phone: RemotePhone) => {
      setForgetting(null);
      // With the phone on the cable, it forgets this computer as well.
      const serial = usb && usb.status.id === phone.id ? usb.serial : null;
      invoke("remote_forget", { id: phone.id, serial })
        .then(() => {
          setNotice(
            serial
              ? `Forgot ${phone.name}, here and on the phone.`
              : `Forgot ${phone.name}. Remove ${THIS_COMPUTER} on the phone too: Linux → Remote access.`,
          );
          refresh();
        })
        .catch(fail);
    },
    [usb, refresh, fail],
  );

  // Nothing until the list is read — a few milliseconds from a local file —
  // rather than a header that lays itself out twice.
  if (phones === null) return null;

  const codeReady = code.replace("-", "").length === 8;
  const usbOffer = usb && usb.status.ok && usb.status.available && !usb.status.pairedHere ? usb : null;
  const list = phones;
  const empty = list.length === 0;

  return (
    <div className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 pb-10 pt-5">
      <header className={empty ? "flex flex-col items-center text-center" : ""}>
        {empty && (
          <div className="mb-4 w-[min(320px,64vw)]">
            <Stage scene="remote" />
          </div>
        )}
        <h1 className="display">Linux on your phone</h1>
        <p className={`lede mt-2 ${empty ? "max-w-[460px]" : ""}`}>
          Open the Ubuntu desktop or a terminal on your phone from {THIS_COMPUTER} — on the same Wi‑Fi, another network,
          or mobile data.
        </p>
      </header>

      {list.length > 0 && (
        <section className="stagger flex flex-col gap-2.5">
          {list.map((p) => {
            const probe = probes[p.id];
            const s = describe(probe);
            const tooOld = !!probe && probe !== "probing" && probe.reachable && !probe.desktopOk;
            return (
              <article key={p.id} className="card px-4 pb-3.5 pt-3.5">
                <div className="flex items-start gap-3">
                  <span className={`tile tile-lg ${s.tone === "ok" ? "tile-ok" : ""}`}>
                    <Icon name="phone" size={20} />
                  </span>
                  <div className="min-w-0 flex-1 pt-0.5">
                    <div className="flex items-center gap-2">
                      <p className="heading truncate text-[14px]">{p.name}</p>
                      <span className={`pill ${s.tone ? `pill-${s.tone}` : ""}`}>
                        {s.busy ? (
                          <span className="spinner !h-2.5 !w-2.5 !border-[1.5px]" aria-hidden="true" />
                        ) : (
                          <span className={`dot ${s.tone === "ok" ? "dot-ok" : s.tone === "warn" ? "dot-warn" : s.tone === "accent" ? "dot-accent" : ""} !h-1.5 !w-1.5 !shadow-none`} aria-hidden="true" />
                        )}
                        {s.pill}
                      </span>
                    </div>
                    <p className="caption mt-0.5 truncate">{s.line}</p>
                  </div>
                  <Menu
                    label={`${p.name} options`}
                    trigger={({ open, toggle, ref }) => (
                      <button
                        ref={ref}
                        className={`btn btn-quiet btn-sm btn-icon -mr-1.5 -mt-0.5 ${open ? "!bg-[var(--surface-2)]" : ""}`}
                        onClick={toggle}
                        aria-haspopup="menu"
                        aria-expanded={open}
                        aria-label={`More for ${p.name}`}
                      >
                        <Icon name="more" size={16} />
                      </button>
                    )}
                  >
                    {(close) => (
                      <>
                        <MenuItem
                          icon="copy"
                          onSelect={() => {
                            close();
                            copy(p.sshCommand);
                          }}
                        >
                          Copy SSH command
                        </MenuItem>
                        <MenuItem
                          icon="trash"
                          danger
                          onSelect={() => {
                            close();
                            setForgetting(p.id);
                          }}
                        >
                          Forget this phone…
                        </MenuItem>
                      </>
                    )}
                  </Menu>
                </div>

                {forgetting === p.id ? (
                  <div className="fade-in mt-3.5 flex flex-wrap items-center gap-2.5 rounded-[var(--r-md)] bg-[rgba(255,107,107,0.06)] px-3 py-2.5 shadow-[inset_0_0_0_1px_rgba(255,107,107,0.18)]">
                    <p className="body min-w-0 flex-1">Forget {p.name}? You can pair it again later.</p>
                    <button className="btn btn-danger btn-sm" onClick={() => forget(p)}>
                      Forget
                    </button>
                    <button className="btn btn-quiet btn-sm" onClick={() => setForgetting(null)}>
                      Keep
                    </button>
                  </div>
                ) : (
                  <div className="mt-3.5 flex flex-wrap items-center gap-2">
                    <button
                      className={`btn btn-sm ${s.tone === "ok" ? "btn-primary" : "btn-secondary"}`}
                      onClick={() => invoke("remote_open_desktop", { id: p.id }).catch(fail)}
                      disabled={tooOld}
                      title={
                        tooOld
                          ? "Update Open Android DeX on the phone to open its desktop from here"
                          : "Open the phone's Linux desktop in a window — F11 for full screen"
                      }
                    >
                      <Icon name="monitor" size={14} />
                      Open desktop
                    </button>
                    <button
                      className="btn btn-secondary btn-sm"
                      onClick={() => invoke("remote_open_terminal", { id: p.id }).catch(fail)}
                      title={`Open a terminal running ${p.sshCommand}`}
                    >
                      <Icon name="terminal" size={14} />
                      Terminal
                    </button>
                    <button
                      className="btn btn-secondary btn-sm"
                      onClick={() => invoke("remote_open_vscode", { id: p.id }).catch(fail)}
                      title="Open VS Code on the phone through Remote-SSH"
                    >
                      <Icon name="code" size={14} />
                      VS Code
                    </button>
                    <span className="flex-1" />
                    <button
                      className="btn btn-quiet btn-sm mono !font-medium !text-[11.5px]"
                      onClick={() => copy(p.sshCommand)}
                      title="Copy — works in any terminal"
                    >
                      {copied === p.sshCommand ? (
                        <>
                          <Icon name="check" size={13} className="text-[var(--ok)]" />
                          Copied
                        </>
                      ) : (
                        <>
                          <Icon name="copy" size={13} />
                          {p.sshCommand}
                        </>
                      )}
                    </button>
                  </div>
                )}
                {tooOld && (
                  <p className="caption mt-2.5">Update Open Android DeX on the phone to open its desktop from here.</p>
                )}
              </article>
            );
          })}
        </section>
      )}

      {notice && (
        <div className="callout callout-ok fade-in">
          <Icon name="check" size={16} />
          <p className="select-text">{notice}</p>
        </div>
      )}
      {error && (
        <div className="callout callout-warn fade-in">
          <Icon name="alert" size={16} />
          <p className="whitespace-pre-wrap select-text">{error}</p>
        </div>
      )}

      {usbOffer && (
        <section className="card overflow-hidden px-4 py-4 shadow-[inset_0_0_0_1px_var(--accent-line)]">
          <div className="flex items-start gap-3">
            <span className="tile tile-accent tile-lg">
              <Icon name="cable" size={20} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="heading text-[14px]">{usbOffer.status.name} is plugged in</p>
              <p className="body mt-0.5">
                Set it up now — there's no code to type.
                {usbOffer.status.linux === "missing" && " Linux isn't installed on it yet; that's fine, SSH starts working once it is."}
                {usbOffer.status.linux === "failed" &&
                  " Linux on it didn't finish setting up. Pairing works now; open Linux on the phone to try the setup again."}
                {usbOffer.status.linux === "ssh-failed" && " The SSH server wouldn't install in its Linux. Setting up from here tries again."}
              </p>
              <button className="btn btn-primary mt-3.5" onClick={pairUsb} disabled={!!busy}>
                {busy?.kind === "usb" ? (
                  <span className="spinner !h-3.5 !w-3.5 !border-[1.5px] !border-white/30 !border-t-white" aria-hidden="true" />
                ) : (
                  <Icon name="link" size={15} />
                )}
                {busy?.kind === "usb" ? busy.text : `Set up ${usbOffer.status.name}`}
              </button>
            </div>
          </div>
        </section>
      )}

      <section className="card px-4 py-4">
        <div className="flex items-start gap-3">
          <span className="tile tile-lg">
            <Icon name="keypad" size={19} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="heading text-[14px]">{list.length ? "Pair another phone" : "Pair a phone"}</p>
            <p className="body mt-0.5">Wherever it is — it doesn't need to be on the same network.</p>
          </div>
        </div>
        <ol className="howto mt-4">
          <li>
            On your phone, open <span className="ui-path">Linux</span>, then <span className="ui-path">Remote access</span> — from the Linux
            tile's menu, or the button in the Linux window's top bar.
          </li>
          <li>
            Tap <span className="ui-path">Add a computer</span> and type the code it shows here.
          </li>
        </ol>
        <div className="mt-4 flex items-center gap-2.5">
          <input
            value={code}
            onChange={(e) => setCode(formatCode(e.target.value))}
            onKeyDown={(e) => e.key === "Enter" && codeReady && !busy && pairCode()}
            placeholder="K7QM-29XV"
            aria-label="Pairing code"
            spellCheck={false}
            autoCapitalize="characters"
            className="field code-input w-[208px]"
          />
          <button className="btn btn-primary btn-lg" onClick={pairCode} disabled={!!busy || !codeReady}>
            {busy?.kind === "code" && (
              <span className="spinner !h-3.5 !w-3.5 !border-[1.5px] !border-white/30 !border-t-white" aria-hidden="true" />
            )}
            {busy?.kind === "code" ? busy.text : "Pair"}
          </button>
        </div>
      </section>

      <p className="caption flex gap-2.5 px-1">
        <Icon name="lock" size={15} className="mt-px shrink-0 text-[var(--ink-3)]" />
        <span>
          End-to-end encrypted. It goes straight between the two when the networks allow it, and through a public relay —
          which can't read it — when they don't. Pairing also adds the phone to <span className="mono">~/.ssh/config</span>, so{" "}
          <span className="mono">ssh</span>, <span className="mono">scp</span>, git and VS Code reach it by name, with this app
          closed.
        </span>
      </p>
    </div>
  );
}
