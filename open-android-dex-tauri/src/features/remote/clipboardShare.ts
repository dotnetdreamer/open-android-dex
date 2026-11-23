/**
 * The clipboard between this computer and a phone's Linux desktop, for one
 * desktop window (DesktopRouter.tsx), whichever way the window reaches it.
 *
 * Through the tunnel the guest's end is a frame — the phone's own viewer page
 * — which cannot touch this computer's clipboard, and must not be trusted to
 * decide when it may: anything running as root in the guest can rewrite it.
 * Over adb it is the video's socket (usb/UsbDesktop.tsx). Either way
 * everything that reads or writes the clipboard, and the switch that says
 * whether it may, is here, on the window's side. The guest's end hands the
 * guest's clipboard up (`fromGuest`) and asks for the switch to flip
 * (`toggle`); this hands the computer's down (`paste`) and says which way the
 * switch is (`share: true|false`).
 *
 * The rules, which are TigerVNC's own viewer's:
 *
 * - **Computer → Linux when the window comes to the front**, if the clipboard
 *   changed since the two last agreed. That is the moment someone who copied
 *   something elsewhere comes back to paste it.
 * - **Linux → computer while the window is in front.** What the guest copies
 *   while the window is elsewhere — a program in Linux, not a person — waits
 *   until the window is back, and is dropped if the computer's clipboard
 *   changed in the meantime: the newer copy wins.
 *
 * A postmarketOS phone's desktop window (pmos/PmosDesktop.tsx) shares its
 * phone's own clipboard by the same rules: there the guest is the phone, which
 * takes up to 1 MB at once (GuestClipboard).
 *
 * Pure logic, so the browser check runs this very file against the real page.
 */

/** What goes to the guest's end: the computer's clipboard, or the switch. */
export type ToGuest = { paste: string } | { share: boolean };

export interface ClipboardHost {
  /** The computer's clipboard as text; null when it holds none. */
  read(): Promise<string | null>;
  write(text: string): Promise<void>;
  /** To the guest's end, whichever it is now. */
  post(msg: ToGuest): void;
  /** This window is the one in front. */
  focused(): boolean;
  /** A sentence over the picture: the window's own words, never the frame's. */
  say(text: string): void;
  /** Remember the switch for this phone. */
  save(on: boolean): void;
}

/**
 * The most Xvnc takes in one clipboard update (its MaxCutText), counted the
 * way it counts: UTF-8, CRLF line ends, and a NUL.
 */
export const GUEST_MAX = 262144;
/** What the guest may put on this computer's clipboard at once. */
export const COMPUTER_MAX = 4 * 1024 * 1024;

/** One line ending, whichever side the text came from. */
export const lf = (text: string) => text.replace(/\r\n?/g, "\n");

/** The size Xvnc will see. */
export function guestSize(text: string) {
  return new TextEncoder().encode(lf(text).replace(/\n/g, "\r\n")).length + 1;
}

/** Whose clipboard the other end is, for what the window says, and the most it takes at once. */
export interface GuestClipboard {
  /** "Linux"; a postmarketOS phone's window says "the phone". */
  name: string;
  /** Counted as guestSize counts. */
  max: number;
}

const LINUX: GuestClipboard = { name: "Linux", max: GUEST_MAX };

/** 262144 as "256 KB", 1048576 as "1 MB". */
function sizeWords(bytes: number) {
  return bytes >= 1024 * 1024 ? `${Math.round(bytes / (1024 * 1024))} MB` : `${Math.round(bytes / 1024)} KB`;
}

export class ClipboardShare {
  private on: boolean;
  /** What both sides last agreed the clipboard says, with LF line ends. */
  private synced: string | null = null;
  /**
   * What the two agreed on came from this computer, so the guest holds it
   * only through the connection it came by: Xvnc keeps a client's clipboard
   * for as long as that client stays, and lets it go when it leaves.
   */
  private fromComputer = false;
  /** The guest's, from while the window was elsewhere. */
  private pending: string | null = null;
  /** Too large for the guest, said once. */
  private refused: string | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private latest: string | null = null;

  constructor(
    private readonly host: ClipboardHost,
    on: boolean,
    private readonly guest: GuestClipboard = LINUX,
  ) {
    this.on = on;
  }

  get shared() {
    return this.on;
  }

  /** The guest's end connected: tell it the switch, and give the guest the clipboard if the window is in front. */
  connected() {
    // A new connection — the cable pulled and the tunnel taking over, or the
    // way back — finds the guest without what the old one had given it.
    if (this.fromComputer) {
      this.synced = null;
      this.fromComputer = false;
    }
    this.host.post({ share: this.on });
    if (this.host.focused()) void this.focus();
  }

  /** The window came to the front. */
  async focus() {
    if (!this.on) return;
    const agreed = this.synced;
    const now = await this.host.read().catch(() => null);
    // While the read was out, the switch went off, the window went to the
    // back again (what waits keeps waiting, for the next time it comes), or
    // a copy of the guest's landed here: then what was read is an older
    // clipboard than the one there is now, and is not to be given anywhere.
    if (!this.on || !this.host.focused() || this.synced !== agreed) return;
    const text = now === null ? null : lf(now);
    if (text !== null && text !== this.synced) {
      // Copied on this computer while the window was elsewhere: newer than
      // anything the guest said in the meantime, waiting or still on its way.
      this.pending = null;
      this.dropInFlight();
      if (guestSize(text) > this.guest.max) {
        if (this.refused !== text) {
          this.refused = text;
          this.host.say(`The clipboard is too large to share with ${this.guest.name} (over ${sizeWords(this.guest.max)}).`);
        }
        return;
      }
      this.synced = text;
      this.fromComputer = true;
      this.host.post({ paste: text });
      return;
    }
    if (this.pending !== null) {
      const guest = this.pending;
      this.pending = null;
      await this.commit(guest);
    }
  }

  /** The guest's clipboard changed. */
  fromGuest(raw: string) {
    if (!this.on || raw.length > COMPUTER_MAX) return;
    const text = lf(raw);
    if (!this.host.focused()) {
      // Newer than a copy still on its way from while the window was in
      // front: that one must not come after it and take its place.
      this.dropInFlight();
      this.pending = text;
      return;
    }
    // A selection being dragged out in the guest announces itself many
    // times a second (Xvnc sends PRIMARY too); the clipboard gets the last.
    this.latest = text;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      const last = this.latest;
      this.latest = null;
      if (last === null || !this.on) return;
      // The window went to the back in those 150 ms: what the guest copied
      // waits for it to come back, as anything copied while it was elsewhere
      // does, rather than landing on top of whatever is in front now.
      if (!this.host.focused()) {
        this.pending = last;
        return;
      }
      void this.commit(last);
    }, 150);
  }

  /** The switch, pressed on either end. */
  toggle() {
    this.on = !this.on;
    this.host.save(this.on);
    this.host.post({ share: this.on });
    if (this.on) {
      this.host.say(`Clipboard shared with ${this.guest.name}: what you copy here can be pasted there, and the other way.`);
      // The page took back what it had been given when this went off.
      this.synced = null;
      if (this.host.focused()) void this.focus();
    } else {
      this.host.say(`Clipboard not shared with ${this.guest.name}.`);
      this.pending = null;
      this.dropInFlight();
    }
  }

  dispose() {
    this.dropInFlight();
  }

  /** The guest's copy waiting out its 150 ms, if there is one: let go of, a newer one being there. */
  private dropInFlight() {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.latest = null;
  }

  private async commit(text: string) {
    if (text === this.synced) return;
    this.synced = text;
    this.fromComputer = false;
    try {
      await this.host.write(text);
    } catch {
      // Another program holding the clipboard open (Windows): the next copy
      // in the guest tries again.
      this.synced = null;
    }
  }
}
