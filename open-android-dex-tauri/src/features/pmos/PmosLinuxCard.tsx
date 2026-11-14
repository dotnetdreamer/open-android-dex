import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import type { PmosPhone } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { PmosSignIn } from "./PmosSignIn";

/**
 * A postmarketOS phone on the cable, on the Linux tab (pmos.rs): its whole
 * system is Linux, so its desktop opens from here as it does from the Desktop
 * tab, in the same window. The first time, it is signed in to here as there.
 *
 * Its own small poll while the tab is open; the paired phones' list below is
 * the Android phones' Linux, and is left as it is.
 */
export function PmosLinuxCard() {
  const [phone, setPhone] = useState<PmosPhone | null>(null);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState("");
  const [asking, setAsking] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const [signInError, setSignInError] = useState("");

  useEffect(() => {
    let live = true;
    const poll = () =>
      invoke<PmosPhone | null>("pmos_status")
        .then((p) => live && setPhone(p))
        .catch(() => {});
    void poll();
    const timer = setInterval(poll, 3000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, []);

  if (!phone?.ssh) return null;
  const name = phone.name || "postmarketOS phone";
  const openId = (id: string) => {
    setOpening(true);
    setError("");
    invoke("pmos_open", { id })
      .catch((e) => setError(String(e)))
      .finally(() => setOpening(false));
  };
  // The password goes to pmos.rs once, which leaves this computer's key on
  // the phone; then the desktop opens.
  const signIn = (user: string, password: string) => {
    setSigningIn(true);
    setSignInError("");
    invoke<PmosPhone>("pmos_pair", { user, password })
      .then((p) => {
        setPhone(p);
        setAsking(false);
        openId(p.id);
      })
      .catch((e) => setSignInError(String(e)))
      .finally(() => setSigningIn(false));
  };

  return (
    <article className="card px-4 pb-3.5 pt-3.5">
      <div className="flex items-start gap-3">
        <span className={`tile tile-lg ${phone.paired ? "tile-ok" : ""}`}>
          <Icon name="phone" size={20} />
        </span>
        <div className="min-w-0 flex-1 pt-0.5">
          <div className="flex items-center gap-2">
            <p className="heading truncate text-[14px]">{name}</p>
            <span className="pill pill-ok">
              <span className="dot dot-ok !h-1.5 !w-1.5 !shadow-none" aria-hidden="true" />
              USB
            </span>
          </div>
          <p className="caption mt-0.5 truncate">{phone.os || "postmarketOS"} · its own Linux, on the cable</p>
        </div>
      </div>
      <div className="mt-3.5 flex flex-wrap items-center gap-2">
        {phone.paired ? (
          <button
            className="btn btn-primary btn-sm"
            onClick={() => openId(phone.id)}
            disabled={opening}
            title="Open the phone's desktop in a window: F11 for full screen"
          >
            {opening ? <span className="spinner" aria-hidden="true" /> : <Icon name="monitor" size={14} />}
            Open desktop
          </button>
        ) : (
          !asking && (
            <>
              <button className="btn btn-primary btn-sm" onClick={() => setAsking(true)}>
                <Icon name="monitor" size={14} />
                Sign in to open its desktop
              </button>
              <p className="caption">Once: after that, its desktop just opens.</p>
            </>
          )
        )}
      </div>
      {asking && !phone.paired && (
        <div className="mt-1">
          <PmosSignIn bare busy={signingIn} error={signInError} onSignIn={signIn} />
        </div>
      )}
      {error && <p className="caption mt-2 select-text text-[var(--bad)]">{error}</p>}
    </article>
  );
}
