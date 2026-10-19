import { useCallback, useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { THIS_COMPUTER } from "../../lib/host";
import type { DeviceInfo, RemotePhone, RemoteProbe, RemoteUsbStatus } from "../../lib/types";

/**
 * Phone Linux over SSH: the phones this computer can log in to, and the two
 * ways to add one.
 *
 * The whole point of the panel is to make itself unnecessary. Pairing writes a
 * `Host` block for the phone into ~/.ssh, so from then on `ssh <name>` works in
 * any terminal and VS Code's Remote-SSH lists it — this window is for adding a
 * phone, seeing whether it is reachable right now, and the buttons that save
 * typing. Nothing here has to stay open for a session to work. The one thing
 * ssh cannot give is the phone's XFCE desktop, so Desktop opens that in a
 * window of its own (DesktopWindow.tsx), over the same tunnel.
 *
 * Pairing over the cable comes first when a phone is plugged in, for the same
 * reason the Wi-Fi panel leads with it: adb has already proved which computer
 * is on the other end, so there is nothing to type. The code is for a phone
 * that is somewhere else.
 */

/** How often the list re-asks each phone how it is while the panel is open. */
const PROBE_MS = 20_000;

type ProbeState = RemoteProbe | "probing";

/** "k7qm29x" → "K7QM-29X": grouped as the phone shows it, as it is typed. */
function formatCode(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 8);
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
}

function StatusLine({ probe }: { probe: ProbeState | undefined }) {
  if (!probe || probe === "probing") {
    return <span className="text-slate-500">Checking…</span>;
  }
  if (!probe.reachable) {
    return <span className="text-amber-300/90">{probe.error ?? "Not reachable right now"}</span>;
  }
  const path = `${probe.direct ? "direct" : "through a relay"} · ${probe.rttMs} ms`;
  switch (probe.ssh) {
    case "up":
      return (
        <span className="text-teal-300">
          ● Online · {path}
          {probe.desktop === "running" ? " · desktop running" : ""}
          {probe.sessions > 0 ? ` · ${probe.sessions} open` : ""}
        </span>
      );
    case "installing":
      return <span className="text-amber-300/90">Online · Linux is still being set up on the phone</span>;
    case "missing":
      return <span className="text-amber-300/90">Online · Linux is not installed on the phone</span>;
    case "failed":
      return <span className="text-amber-300/90">Online · the SSH server on the phone would not start</span>;
    default:
      return <span className="text-teal-300">● Online · {path} · SSH starting</span>;
  }
}

/**
 * The phone's desktop, in a window of its own. Offered until a probe says the
 * phone cannot: a phone that is merely unreachable may be back by the time the
 * window has dialled it, and the window says so in words if it is not.
 */
function DesktopButton({ probe, onOpen }: { probe: ProbeState | undefined; onOpen: () => void }) {
  const tooOld = !!probe && probe !== "probing" && probe.reachable && !probe.desktopOk;
  return (
    <button
      className="btn-ghost !px-3 !py-1.5 text-[12px] disabled:opacity-40"
      onClick={onOpen}
      disabled={tooOld}
      title={
        tooOld
          ? "Update Open Android DeX on the phone to open its desktop from here"
          : "Open the phone's Linux desktop in a window — F11 for fullscreen"
      }
    >
      <span aria-hidden="true">🖥</span> Desktop
    </button>
  );
}

export function RemotePanel({ devices, onClose }: { devices: DeviceInfo[]; onClose: () => void }) {
  const [phones, setPhones] = useState<RemotePhone[]>([]);
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
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Escape closes, like every other sheet a desktop app has.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // ── how each phone is, re-asked while the panel is open ──
  const ids = useMemo(() => phones.map((p) => p.id).join(","), [phones]);
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
  }, [adbSerial, phones.length]);

  const succeed = useCallback(
    (phone: RemotePhone) => {
      setBusy(null);
      setError("");
      setCode("");
      setNotice(
        `Paired with ${phone.name}. Connect from any terminal with: ${phone.sshCommand}` +
          (phone.note ? ` ${phone.note}` : ""),
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

  const codeReady = code.replace("-", "").length === 8;
  const usbOffer = usb && usb.status.ok && usb.status.available && !usb.status.pairedHere;

  return (
    <div
      className="fade-in fixed inset-0 z-20 flex flex-col overflow-y-auto bg-black/55 p-6 pt-[46px] backdrop-blur-sm"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="glass m-auto flex w-[640px] max-w-full shrink-0 flex-col overflow-hidden">
        <div className="flex items-start justify-between gap-4 border-b border-white/10 px-5 py-4">
          <div>
            <p className="text-[15px] font-semibold text-slate-100">Linux on your phones</p>
            <p className="mt-1 text-[12px] leading-relaxed text-slate-400">
              Log in to the Ubuntu on a paired phone with ssh, or open its desktop, from{" "}
              {THIS_COMPUTER}, wherever the phone is — the same Wi-Fi, a different one, or mobile data.
            </p>
          </div>
          <button className="link-btn muted shrink-0 text-[13px]" onClick={onClose} title="Close (Esc)">
            ✕
          </button>
        </div>

        <div className="flex flex-col gap-4 px-5 py-4">
          {phones.length > 0 && (
            <div className="flex flex-col gap-2">
              {phones.map((p) => (
                <div key={p.id} className="rounded-xl bg-white/[0.04] px-4 py-3">
                  <div className="flex items-center gap-3">
                    <span className="text-xl" aria-hidden="true">
                      📱
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[13px] font-medium text-slate-200">{p.name}</p>
                      <p className="truncate text-[11.5px]">
                        <StatusLine probe={probes[p.id]} />
                      </p>
                    </div>
                  </div>
                  <div className="mt-2.5 flex flex-wrap items-center gap-2">
                    <DesktopButton probe={probes[p.id]} onOpen={() => invoke("remote_open_desktop", { id: p.id }).catch(fail)} />
                    <button
                      className="btn-ghost !px-3 !py-1.5 text-[12px]"
                      onClick={() => invoke("remote_open_terminal", { id: p.id }).catch(fail)}
                      title={`Open a terminal running ${p.sshCommand}`}
                    >
                      <span aria-hidden="true">⌨</span> Terminal
                    </button>
                    <button
                      className="btn-ghost !px-3 !py-1.5 text-[12px]"
                      onClick={() => invoke("remote_open_vscode", { id: p.id }).catch(fail)}
                      title="Open VS Code on the phone through Remote-SSH"
                    >
                      <span aria-hidden="true">⧉</span> VS Code
                    </button>
                    <button
                      className="rounded-lg bg-white/10 px-2 py-1 font-mono text-[11.5px] text-slate-200 hover:bg-white/15"
                      onClick={() => copy(p.sshCommand)}
                      title="Copy"
                    >
                      {copied === p.sshCommand ? "Copied" : p.sshCommand}
                    </button>
                    <span className="flex-1" />
                    {forgetting === p.id ? (
                      <>
                        <button className="link-btn" onClick={() => forget(p)}>
                          Forget it
                        </button>
                        <button className="link-btn muted" onClick={() => setForgetting(null)}>
                          Keep
                        </button>
                      </>
                    ) : (
                      <button className="link-btn muted" onClick={() => setForgetting(p.id)}>
                        Forget
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {usbOffer && usb && (
            <div className="rounded-xl border border-white/10 bg-white/[0.03] px-4 py-3">
              <p className="text-[12.5px] leading-relaxed text-slate-300">
                <strong className="font-semibold text-slate-100">{usb.status.name}</strong> is plugged
                in. Pair it now and there is no code to type.
                {usb.status.linux === "missing" &&
                  " Linux is not installed on it yet — that is fine; SSH starts working once it is."}
                {usb.status.linux === "failed" &&
                  " Linux on it did not finish setting up. Pairing works now; open Linux on the phone to try the setup again."}
                {usb.status.linux === "ssh-failed" &&
                  " The SSH server would not install in its Linux. Setting up SSH from here tries again."}
              </p>
              <button className="btn-accent mt-3" onClick={pairUsb} disabled={!!busy}>
                <span aria-hidden="true">🔌</span>
                {busy?.kind === "usb" ? busy.text : `Set up SSH to ${usb.status.name}`}
              </button>
            </div>
          )}

          <div>
            <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
              {phones.length ? "Pair another phone" : "Pair a phone"}
            </p>
            <ol className="mb-3 flex list-decimal flex-col gap-1 pl-4 text-[12px] leading-relaxed text-slate-400 marker:text-slate-600">
              <li>
                On the phone, open Linux, then <em>Remote access</em> — from the Linux tile's menu,
                or the button in the Linux window's top bar.
              </li>
              <li>
                Tap <em>Add a computer</em> and type the code it shows here.
              </li>
            </ol>
            <div className="flex items-center gap-2">
              <input
                value={code}
                onChange={(e) => setCode(formatCode(e.target.value))}
                onKeyDown={(e) => e.key === "Enter" && codeReady && !busy && pairCode()}
                placeholder="K7QM-29XV"
                aria-label="Pairing code"
                spellCheck={false}
                autoCapitalize="characters"
                className="field code-input w-[190px]"
              />
              <button className="btn-accent shrink-0" onClick={pairCode} disabled={!!busy || !codeReady}>
                {busy?.kind === "code" ? busy.text : "Pair"}
              </button>
            </div>
          </div>

          {notice && <p className="text-[12px] leading-relaxed text-teal-300">✓ {notice}</p>}
          {error && <pre className="console rounded-lg bg-amber-950/40 p-3 text-amber-300">{error}</pre>}

          <p className="text-[11px] leading-relaxed text-slate-500">
            Pairing adds the phone to ~/.ssh/config, so <span className="font-mono">ssh</span>,{" "}
            <span className="font-mono">scp</span>, <span className="font-mono">rsync</span>, git and
            VS Code's Remote-SSH all reach it by name, with no app open. The connection is
            end-to-end encrypted; it goes straight between the two devices when the networks allow
            it and through a public relay, which cannot read it, when they do not.
          </p>
        </div>
      </div>
    </div>
  );
}
