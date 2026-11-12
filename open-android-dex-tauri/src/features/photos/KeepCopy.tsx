import { useCallback, useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { Icon } from "../../ui/Icon";
import { Segmented, Switch } from "../../ui/kit";
import type { KeepView } from "./model";

/**
 * Keep a copy on this computer (src-tauri/src/send/keep.rs): one line while
 * off; the folder, Change…, the choice of what to keep and one status line
 * while on. Every sentence comes worded from the computer's half.
 */
export function KeepCopy({
  serial,
  phone,
  say,
}: {
  serial: string;
  /** The phone's ro.serialno: what keep:state names. */
  phone: string;
  say: (text: string, tone?: "ok" | "warn" | "bad" | "info", detail?: string) => void;
}) {
  const [keep, setKeep] = useState<KeepView | null>(null);

  const refresh = useCallback(() => {
    invoke<KeepView>("keep_state", { serial })
      .then(setKeep)
      .catch(() => {});
  }, [serial]);

  useEffect(() => {
    refresh();
    const un = listen<{ phone: string }>("keep:state", ({ payload }) => {
      if (payload.phone === phone) refresh();
    });
    // Its "193 photos (612 MB)" follows the phone's list.
    const unPhotos = listen<{ phone: string }>("photos:changed", ({ payload }) => {
      if (payload.phone === phone) refresh();
    });
    return () => {
      un.then((f) => f());
      unPhotos.then((f) => f());
    };
  }, [refresh, phone]);

  const set = (args: { on?: boolean; scope?: "new" | "all" }) => {
    invoke<KeepView>("keep_set", { serial, ...args })
      .then(setKeep)
      .catch((e) => say("Couldn't change this setting", "bad", String(e)));
  };
  const choose = () => {
    invoke<KeepView>("keep_choose", { serial })
      .then(setKeep)
      .catch((e) => say("Couldn't choose the folder", "bad", String(e)));
  };
  const show = () => {
    invoke("keep_show", { serial }).catch((e) => say("Couldn't open the folder", "bad", String(e)));
  };

  if (!keep) return null;
  const saving = keep.saving;
  return (
    <section className="keep-card card" aria-label="Save photos">
      <div className="flex items-start gap-3">
        <span className={`tile ${keep.on ? "tile-accent" : ""}`}>
          <Icon name="download" size={16} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="heading text-[13px]">Save photos to {keep.thisComputer}</p>
          {keep.on ? (
            <>
              <p className={`caption mt-0.5 ${keep.folderGone ? "!text-[var(--warn)]" : ""}`} role="status">
                {keep.status}
              </p>
              {keep.notice && <p className="caption mt-0.5 !text-[var(--warn)]">{keep.notice}</p>}
            </>
          ) : (
            <p className="caption mt-0.5">Photos stay on your phone too.</p>
          )}
        </div>
        <Switch checked={keep.on} onChange={(on) => set({ on })} label={`Save photos to ${keep.thisComputer}`} />
      </div>
      {keep.on && (
        <div className="keep-detail rise-in">
          {saving && (
            <div className="keep-bar" aria-hidden="true">
              <span style={{ width: `${Math.round(((saving[0] + 1) / Math.max(1, saving[1])) * 100)}%` }} />
            </div>
          )}
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12.5px]">
            <span className="text-[var(--ink-3)]">Saving to</span>
            <button type="button" className="link keep-path" onClick={show} title={keep.folder}>
              <Icon name="folder" size={13} />
              <span className="truncate">{keep.shown}</span>
            </button>
            <button type="button" className="btn btn-quiet btn-sm" onClick={choose}>
              {keep.folderGone ? "Choose folder…" : "Change…"}
            </button>
          </div>
          <Segmented
            label="Which photos to save"
            role="radiogroup"
            value={keep.scope}
            onChange={(scope) => set({ scope })}
            options={[
              { value: "new", label: "New photos only" },
              { value: "all", label: keep.everything ? `All ${keep.everything}` : "All photos" },
            ]}
          />
        </div>
      )}
    </section>
  );
}
