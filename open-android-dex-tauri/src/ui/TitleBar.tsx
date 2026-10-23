import type { ReactNode } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { IS_MAC, TITLEBAR_LEAD } from "../lib/host";
import { Icon } from "./Icon";
import { Segmented } from "./kit";

export type Tab = "desktop" | "apps" | "linux";

/**
 * The window's top strip: the app's name, the switch between the things it
 * does, and the help menu.
 *
 * The caption buttons are ours on Windows and Linux and the system's on macOS,
 * where `titleBarStyle: "Overlay"` floats the real traffic lights over this
 * strip — so the name is padded clear of them and no second set of controls is
 * drawn. `deep` walks into the children, which keeps the whole band draggable;
 * buttons still short-circuit the walk, so everything in it stays clickable.
 */
export function TitleBar({
  tab,
  onTab,
  menu,
  desktopBusy,
}: {
  tab: Tab;
  onTab: (t: Tab) => void;
  menu: ReactNode;
  /** A desktop is being set up: said on its tab, for someone looking at the other one. */
  desktopBusy?: boolean;
}) {
  const win = getCurrentWindow();
  return (
    <header data-tauri-drag-region="deep" className="titlebar">
      <div className={`pointer-events-none flex min-w-0 flex-1 items-center gap-2 ${TITLEBAR_LEAD}`}>
        <img src="/logo.svg" alt="" width={16} height={16} className="shrink-0" draggable={false} />
        <span className="titlebar-name truncate">Open Android DeX</span>
      </div>
      <Segmented
        label="Sections"
        className="segmented-nav"
        value={tab}
        onChange={onTab}
        options={[
          { value: "desktop", label: "Desktop", icon: "laptop", busy: desktopBusy && tab !== "desktop" },
          { value: "apps", label: "Apps", icon: "apps" },
          { value: "linux", label: "Linux", icon: "terminal" },
        ]}
      />
      <div className={`flex flex-1 items-center justify-end gap-1 ${IS_MAC ? "pr-2.5" : ""}`}>
        {menu}
        {!IS_MAC && (
          <div className="ml-1 flex self-stretch">
            <button className="caption-btn" onClick={() => win.minimize()} title="Minimize" aria-label="Minimize">
              <Icon name="minus" size={14} strokeWidth={1.3} />
            </button>
            <button className="caption-btn close" onClick={() => win.close()} title="Close" aria-label="Close">
              <Icon name="x" size={14} strokeWidth={1.3} />
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
