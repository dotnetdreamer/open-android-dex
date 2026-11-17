import type { DeviceInfo, PmosPhone } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Menu, MenuItem } from "../../ui/kit";
import { deviceName, readyPhones, routeName, samePhone } from "./model";

/**
 * The phone in use, in the titlebar: the one the Desktop page offers to open
 * and the Apps and Photos pages show (App's readyDevice), or the postmarketOS
 * phone on the cable, whose apps the Apps page then shows. There only when
 * there is a choice, two phones or more, and then on every page, so the one
 * in front always says which phone it is about.
 */
export function PhoneSwitcher({
  devices,
  current,
  onChoose,
  pmos,
  pmosChosen,
  onChoosePmos,
}: {
  devices: DeviceInfo[];
  /** The Android phone in use; none while the postmarketOS phone is. */
  current?: DeviceInfo;
  onChoose: (d: DeviceInfo) => void;
  /** The postmarketOS phone on the cable, when it can be used: signed in to, its SSH answering. */
  pmos?: PmosPhone;
  pmosChosen: boolean;
  onChoosePmos: () => void;
}) {
  const phones = readyPhones(devices);
  if (phones.length + (pmos ? 1 : 0) < 2) return null;
  const pmosName = pmos?.name || "postmarketOS phone";
  const name = pmosChosen ? pmosName : current ? deviceName(current) : "";
  if (!name) return null;
  return (
    <Menu
      label="Phones"
      align="left"
      trigger={({ open, toggle, ref }) => (
        // min-w-0: in a narrow window the name shortens with "…" short of the tabs; the whole of it on hover.
        <button
          ref={ref}
          className={`btn btn-quiet btn-sm min-w-0 max-w-[220px] ${open ? "!bg-[var(--surface-3)] !text-[var(--ink-1)]" : ""}`}
          onClick={toggle}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={`Phone in use: ${name}`}
          title={name}
        >
          <Icon name="phone" size={14} className="shrink-0" />
          <span className="truncate">{name}</span>
          <Icon name="chevronDown" size={13} className="shrink-0" />
        </button>
      )}
    >
      {(close) => (
        <>
          <p className="menu-label">Use this phone</p>
          {phones.map((d) => (
            <MenuItem
              key={d.serial}
              icon="phone"
              hint={routeName(d)}
              checked={!pmosChosen && !!current && samePhone(d, current)}
              onSelect={() => {
                close();
                onChoose(d);
              }}
            >
              {deviceName(d)}
            </MenuItem>
          ))}
          {pmos && (
            <MenuItem
              icon="phone"
              hint="postmarketOS"
              checked={pmosChosen}
              onSelect={() => {
                close();
                onChoosePmos();
              }}
            >
              {pmosName}
            </MenuItem>
          )}
        </>
      )}
    </Menu>
  );
}
