import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { readText } from "@tauri-apps/plugin-clipboard-manager";
import { Icon } from "../../../ui/Icon";
import { IS_MAC } from "../../../lib/host";
import { avcC, codecString, sameBytes, splitAccessUnit } from "../usb/h264";
import { keyDown } from "../usb/keysym";
import { ackOf, clock, PLAIN_ACK, readsTimes } from "../usb/wire";
import { createSoundPlayer } from "./sound";

/**
 * Remote desktop's picture and input: the phone's own screen (PhoneStream.java
 * on the phone) decoded on this computer's GPU (WebCodecs) and drawn at the
 * phone's shape in whatever size the window is, and this computer's mouse and
 * keyboard sent back.
 *
 * The phone turns them into touches and typing (PhoneInput.java): the left
 * button is a finger, the right one Back and the middle one Home, the wheel and
 * a touchpad scroll the phone's page as far as a page here would, and keys go
 * into the focused field. The bar along the top has
 * the phone's own keys, the picture's quality, and how the picture comes:
 * screen sharing (full speed) or the slow view (a few pictures a second, when
 * nothing is shared on the phone: nobody said yes there, or the phone locked).
 */

const MSG_VIDEO = 0x01;
const MSG_AUDIO = 0x03;
const IN_POINTER = 0x10;
const IN_KEY = 0x11;
/** The wheel or a touchpad: x, y, and how far the page there scrolls, in the phone's pixels (PhoneInput.scroll). */
const IN_SCROLL = 0x13;
/** Ignored by the phone: a byte every 50 ms keeps a phone's Wi-Fi out of power save while this is open. */
const KEEP_AWAKE = new Uint8Array([0x00]);
const KEEP_AWAKE_MS = 50;

type Quality = "low" | "medium" | "high";
type Phase =
  | { kind: "connecting" }
  | { kind: "up" }
  | { kind: "waiting"; message: string }
  | { kind: "ended"; message: string };

/** The ceiling the route can carry; the phone holds it to the quality's and lower when the link says so. */
function bitrateFor(route: string) {
  if (route === "Wi-Fi") return 30e6;
  if (route === "Remote (relay)") return 4e6;
  return 12e6;
}

function tierOf(route: string) {
  return route === "Wi-Fi" ? "wifi" : route === "Remote (relay)" ? "relay" : "remote";
}

/**
 * Picture size: the part of the quality's picture the phone sends (half its
 * width and height for Half size). A smaller one comes sooner when much of
 * the screen changes (an app opening, a page turned) and uses less data; a
 * small change is as quick at every size (doc/phone-remote.md, "Picture
 * size").
 */
const SIZES: { scale: number; label: string }[] = [
  { scale: 1, label: "Full size" },
  { scale: 0.75, label: "Three quarters" },
  { scale: 0.5, label: "Half size" },
  { scale: 0.333, label: "A third" },
];
const isSize = (v: unknown) => SIZES.some((s) => s.scale === v);

/**
 * Sound: where the phone's sound plays (PhoneSound.java). On the phone, as it
 * always did, and off send nothing at all, so the whole link is the
 * picture's; on this computer or on both, the phone sends what apps play.
 * Off and on this computer keep the phone itself quiet.
 */
type Sound = "phone" | "here" | "both" | "off";
const SOUNDS: { value: Sound; label: string }[] = [
  { value: "phone", label: "Sound on phone" },
  { value: "here", label: "Sound on computer" },
  { value: "both", label: "Sound on both" },
  { value: "off", label: "Sound off" },
];
const isSound = (v: unknown): v is Sound => SOUNDS.some((s) => s.value === v);
const soundHere = (s: Sound) => s === "here" || s === "both";
const soundOnPhone = (s: Sound) => s === "phone" || s === "both";

/** The lock screen's pad as the phone describes it (PhoneLock.java): where its keys are, in the phone's pixels. */
type Box = { x: number; y: number; w: number; h: number };
type LockPad = {
  pad: string;
  w?: number;
  h?: number;
  keys?: (Box & { k: string; on?: boolean })[];
  field?: Box & { n: number };
  pattern?: Box;
  words?: (Box & { s: string })[];
};

/**
 * The window's choices from last time: the app keeps them
 * (remote_phone_view_set) and puts them in the window's address, since the
 * window is private and its own storage goes when it closes.
 */
const fromAddress = () => new URLSearchParams(window.location.hash.slice(1));

const savedScale = (): number | undefined => {
  const s = Number(fromAddress().get("scale"));
  return isSize(s) ? s : undefined;
};

const savedQuality = (): Quality | undefined => {
  const q = fromAddress().get("quality");
  return q === "low" || q === "medium" || q === "high" ? q : undefined;
};

const savedSound = (): Sound | undefined => {
  const s = fromAddress().get("sound");
  return isSound(s) ? s : undefined;
};

/**
 * Screen off (PhoneHelperLink.java): the phone's own screen dark while the
 * phone runs on for this window, as scrcpy's screen off, for the phone's
 * battery. Kept in the window's address only, so the window that comes back
 * after the link went darkens the phone again; a new window starts lit.
 */
const savedPanelOff = () => fromAddress().get("panel") === "off";
const keepPanel = (off: boolean) => {
  const at = fromAddress();
  if (off) at.set("panel", "off");
  else at.delete("panel");
  history.replaceState(history.state, "", `#${at.toString()}`);
};
const HELPER_WORDS = "Two things need a small helper on the phone: turning off the phone's screen, and pressing the buttons Android keeps for someone at the phone, such as permission questions. A computer with adb (Android's USB debugging) starts the helper: run this on that computer, with the phone connected, once after every restart of the phone.";
/** What the phone says of its helper while none runs (PhoneHelperStart on the phone). */
type Panel = { state: string; start: string; how: string; why: string; app: string };
/** The helper states in which the phone starts its helper itself when asked. */
const HELPER_STARTS = ["waiting", "failed", "ask", "starting"];
/**
 * Where the phone stands on its helper, in words: what the phone needs to
 * start it itself, and whether a computer's command helps. The phone's own
 * page (phone.js) says the same.
 */
const helperWords = (p: Panel): { words: string; cmd: boolean } => {
  const app = p.app || "the app";
  const computer = "A computer with adb (Android's USB debugging) can start the helper instead: run this on that computer, with the phone connected, once after every restart of the phone.";
  switch (p.how) {
    case "starting":
    case "waiting":
      return { words: "The phone is starting its helper…", cmd: false };
    case "setup":
      return { words: `Two things need a small helper on the phone: turning off the phone's screen, and pressing the buttons Android keeps for someone at the phone, such as permission questions. Set the helper up once on the phone: open ${app} there, then Settings, Remote desktop, Set up Screen off. After that the phone starts the helper by itself. ${computer}`, cmd: true };
    case "unpaired":
      return { words: `The phone's Wireless debugging no longer knows ${app}. Set up Screen off again on the phone: ${app}, Settings, Remote desktop. ${computer}`, cmd: true };
    case "devoff":
      return { words: `Developer options are off on the phone, so the phone can't start its helper. Turn Developer options back on in the phone's Settings. ${computer}`, cmd: true };
    case "wireless":
      return { words: `Wireless debugging is off on the phone, so the phone can't start its helper. Turn on Wireless debugging in the phone's Developer options. ${computer}`, cmd: true };
    case "wifi":
      return { words: `The phone needs Wi-Fi to start its helper by itself, because Wireless debugging works only on Wi-Fi. ${computer}`, cmd: true };
    case "ask":
      return { words: "The phone asks whether to allow Wireless debugging on its Wi-Fi. Someone at the phone needs to tap Allow there, then press Screen off here again.", cmd: false };
    case "failed":
      return { words: `The phone's helper did not start${p.why ? ` (${p.why})` : ""}. Press Screen off to try again. ${computer}`, cmd: true };
    default:
      return { words: HELPER_WORDS, cmd: true };
  }
};
const HELPER_THEN = "Then Screen off turns the phone's screen off while the phone keeps working here, and the phone's battery lasts longer. The phone's power button, closing this window, or Screen off again turns the screen back on.";
const protectedWords = (what: string) =>
  `Android lets only someone at the phone press the buttons on ${what === "permission" ? "a permission question" : what === "install" ? "an install" : "this question from Android"}. The phone's helper lets you press them from here.`;

/**
 * Kept for the next time this phone's window opens, and in this window's
 * address: a picture that comes back after the link went (PhoneWindow) or a
 * reload starts from the address, and until 2026-11-22 that still had the
 * choices the window was opened with.
 */
const keepChoice = (choice: { quality?: Quality; scale?: number; sound?: Sound }) => {
  const at = fromAddress();
  const id = at.get("phone");
  if (!id) return;
  if (choice.quality) at.set("quality", choice.quality);
  if (choice.scale !== undefined) at.set("scale", String(choice.scale));
  if (choice.sound) at.set("sound", choice.sound);
  history.replaceState(history.state, "", `#${at.toString()}`);
  invoke("remote_phone_view_set", { id, ...choice }).catch((e) => logError(`remote: phone window choice not kept: ${e}`));
};

export function PhoneView({
  url,
  route,
  name,
  onUp,
  onLost,
}: {
  url: string;
  route: string;
  name: string;
  onUp: () => void;
  onLost: () => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<Phase>({ kind: "connecting" });
  const [source, setSource] = useState<"screen" | "shots" | "">("");
  const [quality, setQuality] = useState<Quality>(savedQuality() ?? "medium");
  const [scale, setScale] = useState<number>(savedScale() ?? 1);
  const [picture, setPicture] = useState("");
  const [control, setControl] = useState(true);
  const [locked, setLocked] = useState(false);
  const [stats, setStats] = useState({ fps: 0, mbps: 0, rttMs: 0 });
  const [sound, setSound] = useState<Sound>(savedSound() ?? "phone");
  /** What the phone says of the sound: on, or why not; and whether it keeps itself quiet. */
  const [soundState, setSoundState] = useState({ state: "off", quiet: false });
  const [soundKbps, setSoundKbps] = useState(0);
  const [soundPlaying, setSoundPlaying] = useState(false);
  const [lock, setLock] = useState<LockPad>({ pad: "" });
  const [frame, setFrame] = useState({ cw: 0, ch: 0 });
  /** What the phone says of its own screen: "none" (no helper), "on" or "off", the helper's start command, and with none what the phone needs for one. */
  const [panel, setPanel] = useState<Panel>({ state: "none", start: "", how: "", why: "", app: "" });
  const [panelOff, setPanelOff] = useState(savedPanelOff());
  const panelOffRef = useRef(panelOff);
  const [helperNote, setHelperNote] = useState(false);
  const helperNoteRef = useRef(false);
  const [protectedWhat, setProtectedWhat] = useState("");
  const [copied, setCopied] = useState(false);
  const stageRef = useRef<HTMLDivElement>(null);
  /** The sound chosen now, for the wire's handlers. */
  const soundRef = useRef<Sound>(sound);
  const actions = useRef({
    nav: (_k: string) => {},
    quality: (_q: Quality) => {},
    size: (_s: number) => {},
    sound: (_s: Sound) => {},
    share: () => {},
    panel: (_off: boolean) => {},
  });
  const showHelperNote = (on: boolean) => {
    helperNoteRef.current = on;
    setHelperNote(on);
    if (on) setProtectedWhat("");
  };

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true })!;
    let live = true;
    let up = false;
    let told = false;
    const timed = readsTimes(tierOf(route), false);
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";

    // ── the picture ──
    let dw = 0, dh = 0, cw = 0, ch = 0;
    let decoder: VideoDecoder | null = null;
    let description: Uint8Array | null = null;
    let sps: Uint8Array | null = null;
    let pps: Uint8Array | null = null;
    let needKey = true;
    let askedKeyAt = 0;

    const send = (m: string | Uint8Array) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(m);
    };

    // ── the sound ──
    let soundAsked = "";
    /** The sound's two switches to the phone, when they change: heard here (only while this window can play it), and on the phone. */
    const syncSound = () => {
      const s = soundRef.current;
      const on = soundHere(s) && player.playing();
      const codecs = player.codecs();
      const said = `${on}/${soundOnPhone(s)}/${codecs.join(",")}`;
      if (said === soundAsked || ws.readyState !== WebSocket.OPEN) return;
      soundAsked = said;
      send(JSON.stringify({ t: "audio", on, phone: soundOnPhone(s), codecs }));
    };
    const player = createSoundPlayer((playing) => {
      if (!live) return;
      setSoundPlaying(playing);
      syncSound();
    });
    // Which sound this window decodes, said as soon as it is known.
    void player.init().then(() => live && syncSound());
    // For the tests' eyes (test/phone-remote/phone-window-test.mjs): what the window's sound has played.
    (window as unknown as { __phoneSound?: () => unknown }).__phoneSound = () => player.stats();
    /** In a click or a key, or once the picture is up: the window may start sound then. */
    const startSound = () => {
      if (soundHere(soundRef.current) && !player.playing()) player.start();
    };
    const askKey = () => {
      const now = performance.now();
      if (now - askedKeyAt < 500) return;
      askedKeyAt = now;
      send(JSON.stringify({ t: "keyframe" }));
    };
    const draw = (frame: VideoFrame) => {
      if (live && cw > 0) {
        if (canvas.width !== cw || canvas.height !== ch) {
          canvas.width = cw;
          canvas.height = ch;
        }
        ctx.drawImage(frame, 0, 0, cw, ch, 0, 0, cw, ch);
        if (!up) {
          up = true;
          setPhase({ kind: "up" });
          onUp();
          canvas.focus({ preventScroll: true });
          startSound();
        }
      }
      frame.close();
    };
    const newDecoder = () => {
      if (decoder && decoder.state !== "closed") decoder.close();
      decoder = new VideoDecoder({
        output: draw,
        error: (e) => {
          logError(`remote: phone screen decoder: ${e.message}`);
          description = null;
          needKey = true;
          askKey();
        },
      });
    };
    const onVideo = (flags: number, pts: number, payload: Uint8Array) => {
      const unit = splitAccessUnit(payload);
      if (unit.sps) sps = unit.sps;
      if (unit.pps) pps = unit.pps;
      if (!unit.data.length) return;
      const isKey = unit.key || (flags & 1) !== 0;
      if (isKey && sps && pps) {
        const d = avcC(sps, pps);
        if (!decoder || decoder.state === "closed" || !sameBytes(d, description)) {
          if (!decoder || decoder.state === "closed") newDecoder();
          decoder!.configure({
            codec: codecString(sps),
            description: d,
            optimizeForLatency: true,
            hardwareAcceleration: "prefer-hardware",
          });
          description = d;
        }
        needKey = false;
      }
      if (needKey || !decoder || decoder.state !== "configured") {
        askKey();
        return;
      }
      if (decoder.decodeQueueSize > 3 && !isKey) {
        needKey = true;
        askKey();
        return;
      }
      decoder.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: unit.data }));
    };

    // ── the wire ──
    /** When this window last asked for the phone's screen off or on: the phone's word just before is older. */
    let panelAskedAt = -1e9;
    let protectTimer = 0;
    ws.onopen = () => {
      const s = soundRef.current;
      const on = soundHere(s) && player.playing();
      const codecs = player.codecs();
      soundAsked = `${on}/${soundOnPhone(s)}/${codecs.join(",")}`;
      send(JSON.stringify({
        t: "hello", v: 1, w: window.innerWidth, h: window.innerHeight,
        bitrate: bitrateFor(route), fps: 60, acks: true, ackAt: timed,
        quality, scale, wake: true,
        audio: on, phone: soundOnPhone(s), codecs, panel: !panelOffRef.current,
      }));
      logInfo(`remote: phone screen open (${route})`);
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data !== "string") {
        const b = new Uint8Array(ev.data as ArrayBuffer);
        if (b[0] === MSG_AUDIO && b.length > 10) {
          player.run(b[1], Number(new DataView(b.buffer, b.byteOffset + 2, 8).getBigUint64(0)), b.subarray(10));
          return;
        }
        if (b[0] !== MSG_VIDEO || b.length < 10) return;
        const flags = b[1];
        const pts = Number(new DataView(b.buffer, b.byteOffset + 2, 8).getBigUint64(0));
        onVideo(flags, pts, b.subarray(10));
        if (!(flags & 2)) send(timed ? ackOf(clock()) : PLAIN_ACK);
        return;
      }
      let m: Record<string, unknown>;
      try {
        m = JSON.parse(ev.data);
      } catch {
        return;
      }
      switch (m.t) {
        case "video":
          dw = Number(m.dw) || 0;
          dh = Number(m.dh) || 0;
          cw = Number(m.cw) || 0;
          ch = Number(m.ch) || 0;
          setSource(m.source === "shots" ? "shots" : "screen");
          if (m.quality === "low" || m.quality === "medium" || m.quality === "high") setQuality(m.quality);
          if (isSize(m.scale)) setScale(Number(m.scale));
          setPicture(cw && ch ? `${cw} × ${ch}` : "");
          setFrame({ cw, ch });
          needKey = true;
          break;
        case "sound":
          player.format(m);
          break;
        case "audio":
          setSoundState({ state: String(m.state || "off"), quiet: Boolean(m.quiet) });
          break;
        case "lock":
          setLock(m as unknown as LockPad);
          break;
        case "phone":
          setControl(Boolean(m.control));
          setLocked(Boolean(m.locked));
          if (typeof m.panel === "string") {
            setPanel({
              state: m.panel,
              start: typeof m.panelStart === "string" ? m.panelStart : "",
              how: typeof m.panelHow === "string" ? m.panelHow : "",
              why: typeof m.panelWhy === "string" ? m.panelWhy : "",
              app: typeof m.app === "string" ? m.app : "",
            });
            // The phone's power button took its screen back: this window's choice goes with it.
            if (panelOffRef.current && m.panelWanted === false && m.panel !== "none" && performance.now() - panelAskedAt > 3000) {
              panelOffRef.current = false;
              setPanelOff(false);
              keepPanel(false);
            }
            if (m.panel !== "none") showHelperNote(false);
          }
          break;
        case "protected":
          setProtectedWhat(String(m.what || "system"));
          window.clearTimeout(protectTimer);
          protectTimer = window.setTimeout(() => live && setProtectedWhat(""), 12000);
          break;
        case "stats":
          setStats({
            fps: Number(m.fps) || 0,
            mbps: Math.round((Number(m.kbps) || 0) / 100) / 10,
            rttMs: Math.round(Number(m.rttMs) || 0),
          });
          setSoundKbps(Number(m.soundKbps) || 0);
          break;
        case "state": {
          const state = String(m.state);
          const message = typeof m.message === "string" ? m.message : "";
          if (state === "running") break;
          if (state === "waiting") {
            setPhase({ kind: "waiting", message: message || "Waiting for the phone." });
            break;
          }
          told = true;
          setPhase({
            kind: "ended",
            message: message || (state === "replaced"
              ? "Another computer opened this phone."
              : "The phone closed the screen."),
          });
          break;
        }
        default:
      }
    };
    ws.onclose = () => {
      if (!live) return;
      setLock({ pad: "" });
      // Told why: the cover says so and stays. Not told: the link went, and
      // the window comes back by itself.
      if (!told) onLost();
    };

    // ── the mouse and touch ──
    let buttons = 0;
    const toScreen = (e: { clientX: number; clientY: number }) => {
      const r = canvas.getBoundingClientRect();
      if (!cw || !ch || !dw || !dh) return null;
      const s = Math.min(r.width / cw, r.height / ch);
      const ox = r.left + (r.width - cw * s) / 2;
      const oy = r.top + (r.height - ch * s) / 2;
      const px = (e.clientX - ox) / s;
      const py = (e.clientY - oy) / s;
      const x = Math.round(Math.min(cw - 1, Math.max(0, px)) * dw / cw);
      const y = Math.round(Math.min(ch - 1, Math.max(0, py)) * dh / ch);
      return [x, y] as const;
    };
    const pointer = (mask: number, at: readonly [number, number]) => {
      send(new Uint8Array([IN_POINTER, mask & 0xff, at[0] >> 8, at[0] & 0xff, at[1] >> 8, at[1] & 0xff]));
    };
    const maskOf = (e: PointerEvent) =>
      e.pointerType === "mouse" ? (e.buttons & 1) | (e.buttons & 4 ? 2 : 0) | (e.buttons & 2 ? 4 : 0) : e.buttons ? 1 : 0;
    const onPointer = (e: PointerEvent) => {
      if (!up) return;
      const at = toScreen(e);
      if (!at) return;
      e.preventDefault();
      if (e.type === "pointerdown") {
        canvas.setPointerCapture(e.pointerId);
        startSound();
      }
      const mask = e.type === "pointerup" || e.type === "pointercancel" ? 0 : maskOf(e);
      if (mask === buttons && e.type === "pointermove" && !mask) return; // hover: a phone has none
      buttons = mask;
      pointer(mask, at);
    };
    // The wheel and a touchpad: the phone's page scrolls as far as a page here
    // would, on this screen. The distance goes in the phone's pixels, one
    // message for each wheel event, and what is left of a pixel waits for the
    // next; a touchpad's momentum comes as wheel events too, so the phone adds
    // none of its own.
    let wheelY = 0;
    let wheelX = 0;
    const onWheel = (e: WheelEvent) => {
      if (!up) return;
      // A pinch on a touchpad, or Ctrl and the wheel: the phone gets one finger, which cannot pinch.
      if (e.ctrlKey) return;
      e.preventDefault();
      const at = toScreen(e);
      if (!at) return;
      const r = canvas.getBoundingClientRect();
      const s = Math.min(r.width / cw, r.height / ch);
      // Lines and pages as a page here takes them; a page is the picture's height.
      const unit = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? ch * s : 1;
      let dx = e.deltaX * unit;
      let dy = e.deltaY * unit;
      // Shift turns a mouse's wheel sideways, where the browser has not already.
      if (e.shiftKey && !dx) {
        dx = dy;
        dy = 0;
      }
      const k = dw / (cw * s); // the phone's pixels in one of this window's
      wheelX += dx * k;
      wheelY += dy * k;
      const sx = Math.max(-32767, Math.min(32767, Math.trunc(wheelX)));
      const sy = Math.max(-32767, Math.min(32767, Math.trunc(wheelY)));
      if (!sx && !sy) return;
      wheelX -= sx;
      wheelY -= sy;
      send(new Uint8Array([IN_SCROLL, at[0] >> 8, at[0] & 0xff, at[1] >> 8, at[1] & 0xff, (sx >> 8) & 0xff, sx & 0xff, (sy >> 8) & 0xff, sy & 0xff]));
    };
    const noMenu = (e: Event) => e.preventDefault();

    // ── the keyboard ──
    const held = new Map<string, number>();
    const key = (keysym: number, down: boolean) => {
      send(new Uint8Array([IN_KEY, down ? 1 : 0, (keysym >>> 24) & 0xff, (keysym >>> 16) & 0xff, (keysym >>> 8) & 0xff, keysym & 0xff]));
    };
    const onKeyDown = (e: KeyboardEvent) => {
      // The helper's note up: its command may be copied with the keyboard.
      if (!up || helperNoteRef.current || e.isComposing || e.keyCode === 229) return;
      startSound();
      // Paste: this computer's clipboard, typed into the phone's field.
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === "KeyV") {
        e.preventDefault();
        readText()
          .then((text) => {
            if (text) send(JSON.stringify({ t: "text", text }));
          })
          .catch(() => {});
        return;
      }
      e.preventDefault();
      const a = keyDown(e, IS_MAC);
      if (a.kind === "none") return;
      if (a.kind === "type") {
        key(a.keysym, true);
        key(a.keysym, false);
        return;
      }
      held.set(e.code, a.keysym);
      key(a.keysym, true);
    };
    const onKeyUp = (e: KeyboardEvent) => {
      const k = held.get(e.code);
      if (k === undefined) return;
      held.delete(e.code);
      e.preventDefault();
      key(k, false);
    };
    const onBlur = () => {
      for (const k of held.values()) key(k, false);
      held.clear();
      if (buttons) {
        buttons = 0;
      }
    };

    // ── out of sight: no picture, and the phone's Wi-Fi may sleep ──
    const onVisible = () => {
      send(JSON.stringify({ t: "picture", on: !document.hidden }));
      if (!document.hidden) {
        needKey = true;
        player.settle();
      }
    };
    /** The window is going: the phone ends this session at once, and Remote desktop stays on there. */
    const bye = () => {
      if (ws.readyState === WebSocket.OPEN) send(JSON.stringify({ t: "bye" }));
    };
    const awake = window.setInterval(() => {
      if (!document.hidden) send(KEEP_AWAKE);
    }, KEEP_AWAKE_MS);
    // Hidden (minimized) too, every two seconds: the phone lets go of a computer it has heard
    // nothing from for 90 s, and this window is still here.
    const here = window.setInterval(() => {
      if (document.hidden) send(KEEP_AWAKE);
    }, 2000);

    actions.current = {
      nav: (k) => send(JSON.stringify({ t: "key", key: k })),
      quality: (q) => {
        keepChoice({ quality: q });
        send(JSON.stringify({ t: "quality", mode: q }));
      },
      size: (s) => {
        keepChoice({ scale: s });
        send(JSON.stringify({ t: "size", scale: s }));
      },
      sound: (s) => {
        soundRef.current = s;
        keepChoice({ sound: s });
        // A choice is a click: the moment the window may start sound.
        if (soundHere(s)) player.start();
        else player.stop();
        syncSound();
      },
      share: () => send(JSON.stringify({ t: "share" })),
      panel: (off) => {
        panelOffRef.current = off;
        setPanelOff(off);
        keepPanel(off);
        panelAskedAt = performance.now();
        send(JSON.stringify({ t: "panel", on: !off }));
      },
    };

    canvas.addEventListener("pointerdown", onPointer);
    canvas.addEventListener("pointermove", onPointer);
    canvas.addEventListener("pointerup", onPointer);
    canvas.addEventListener("pointercancel", onPointer);
    canvas.addEventListener("wheel", onWheel, { passive: false });
    canvas.addEventListener("contextmenu", noMenu);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("keyup", onKeyUp);
    window.addEventListener("blur", onBlur);
    window.addEventListener("pagehide", bye);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      live = false;
      bye();
      player.stop();
      window.removeEventListener("pagehide", bye);
      window.clearInterval(awake);
      window.clearInterval(here);
      canvas.removeEventListener("pointerdown", onPointer);
      canvas.removeEventListener("pointermove", onPointer);
      canvas.removeEventListener("pointerup", onPointer);
      canvas.removeEventListener("pointercancel", onPointer);
      canvas.removeEventListener("wheel", onWheel);
      canvas.removeEventListener("contextmenu", noMenu);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("keyup", onKeyUp);
      window.removeEventListener("blur", onBlur);
      document.removeEventListener("visibilitychange", onVisible);
      window.clearTimeout(protectTimer);
      try {
        ws.close();
      } catch {
        // already gone
      }
      if (decoder && decoder.state !== "closed") decoder.close();
    };
    // The hello's quality and picture size are this view's first ones (the address's, which keepChoice keeps up to date); changes go as messages.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, route]);

  const btn = (label: React.ReactNode, title: string, onClick: () => void, on = false, key?: string) => (
    <button
      key={key}
      type="button"
      title={title}
      aria-label={title}
      onClick={onClick}
      style={{ ...styles.button, ...(on ? styles.buttonOn : null) }}
    >
      {label}
    </button>
  );

  return (
    <div style={styles.window}>
      <div style={styles.bar} role="toolbar" aria-label="The phone's keys">
        {btn(<Icon name="chevronLeft" size={16} />, "Back", () => actions.current.nav("back"))}
        {btn(<Icon name="home" size={16} />, "Home", () => actions.current.nav("home"))}
        {btn(<Icon name="recents" size={16} />, "Recent apps", () => actions.current.nav("recents"))}
        {btn(<Icon name="bell" size={16} />, "Notifications", () => actions.current.nav("notifications"))}
        <span style={styles.gap} />
        {btn(
          <span style={panel.state === "none" && !HELPER_STARTS.includes(panel.how) ? styles.faint : undefined}>Screen off</span>,
          panel.state === "none"
            ? HELPER_STARTS.includes(panel.how)
              ? "Turn the phone's own screen off: the phone starts its helper first"
              : "Turning off the phone's screen needs a helper on the phone: click to see how"
            : panel.state === "off"
              ? "The phone's own screen is off, and the phone keeps working here. Click to turn the phone's screen back on"
              : "Turn the phone's own screen off: the phone keeps working here, and the phone's battery lasts longer. The phone's power button turns the screen back on",
          () => {
            if (panel.state === "none") {
              // A phone set up for it starts its helper now, and its screen goes off once the helper is up.
              if (HELPER_STARTS.includes(panel.how)) actions.current.panel(true);
              showHelperNote(true);
              return;
            }
            actions.current.panel(!(panelOff || panel.state === "off"));
            canvasRef.current?.focus();
          },
          panel.state === "off",
          "panel",
        )}
        {(["low", "medium", "high"] as const).map((q) =>
          btn(q === "low" ? "Low" : q === "medium" ? "Medium" : "High",
            q === "low" ? "Low: fastest" : q === "high" ? "High: sharpest" : "Medium",
            () => {
              setQuality(q);
              actions.current.quality(q);
            }, quality === q, q))}
        <select
          value={String(scale)}
          title="Picture size: a smaller picture comes sooner when much of the screen changes, and uses less data"
          aria-label="Picture size"
          onChange={(e) => {
            const s = Number(e.target.value);
            setScale(s);
            actions.current.size(s);
            canvasRef.current?.focus();
          }}
          style={styles.select}
        >
          {SIZES.map((s) => (
            <option key={s.scale} value={String(s.scale)}>{s.label}</option>
          ))}
        </select>
        <select
          value={sound}
          title="Where the phone's sound plays. Sound off, or sound on the phone only, sends nothing more, so the picture has the whole connection."
          aria-label="Sound"
          onChange={(e) => {
            const s = isSound(e.target.value) ? e.target.value : "phone";
            setSound(s);
            actions.current.sound(s);
            canvasRef.current?.focus();
          }}
          style={styles.select}
        >
          {SOUNDS.map((s) => (
            <option key={s.value} value={s.value}>{s.label}</option>
          ))}
        </select>
      </div>
      <div ref={stageRef} style={styles.stage}>
        <canvas ref={canvasRef} tabIndex={0} style={styles.canvas} aria-label={`${name}'s screen`} />
        {phase.kind === "up" && <LockLayer lock={lock} canvas={canvasRef} stage={stageRef} cw={frame.cw} ch={frame.ch} />}
        {phase.kind === "up" && protectedWhat && !helperNote && (
          <div style={styles.protectNote} role="status">
            <span>{protectedWords(protectedWhat)}</span>
            {btn("How", "How to start the phone's helper", () => showHelperNote(true))}
          </div>
        )}
        {helperNote && (
          <div style={styles.helperNote} role="dialog" aria-label="The phone's helper">
            <div>{helperWords(panel).words}</div>
            {helperWords(panel).cmd && <div style={styles.helperCmdRow}>
              <code style={styles.helperCmd}>{panel.start || "The phone has not said the command yet. Wait a moment, then try again."}</code>
              {btn(copied ? "Copied" : "Copy", "Copy the command", () => {
                if (!panel.start) return;
                navigator.clipboard.writeText(panel.start).then(() => {
                  setCopied(true);
                  window.setTimeout(() => setCopied(false), 2000);
                }, () => {});
              })}
            </div>}
            <div>{HELPER_THEN}</div>
            <div style={styles.helperClose}>{btn("Close", "Close", () => {
              showHelperNote(false);
              canvasRef.current?.focus();
            })}</div>
          </div>
        )}
        {phase.kind !== "up" && (
          <div style={styles.cover}>
            <div style={styles.coverTitle}>{name}</div>
            <div style={styles.coverWords}>
              {phase.kind === "connecting" ? "Opening the phone's screen…" : phase.message}
            </div>
            {phase.kind === "waiting" &&
              btn("Ask the phone again", "Ask the phone for screen sharing again", () => actions.current.share())}
          </div>
        )}
      </div>
      <div style={styles.status}>
        <span>
          {source === "shots" ? "Slow view" : source === "screen" ? "Full speed" : ""}
          {source && picture ? ` · ${picture}` : ""}
          {source ? ` · ${stats.fps} fps · ${stats.mbps} Mb/s · ${stats.rttMs} ms · ${route}` : route}
          {locked ? " · locked" : ""}
          {!control ? " · watching only: control is off on the phone" : ""}
          {panel.state === "off" ? " · the phone's screen is off" : panelOff && panel.state === "on" ? " · turning the phone's screen off" : ""}
          {soundWords(sound, soundState, soundPlaying, soundKbps)}
        </span>
        {source === "shots" &&
          btn("Full speed", "Ask the phone for screen sharing: someone at the phone taps Start", () => actions.current.share())}
      </div>
    </div>
  );
}

/** The sound's part of the status line: what is playing here, or why nothing is. */
function soundWords(sound: Sound, st: { state: string; quiet: boolean }, playing: boolean, kbps: number) {
  if (!soundHere(sound)) return st.quiet ? " · the phone is quiet" : "";
  switch (st.state) {
    case "on":
      return playing ? ` · sound ${kbps} kb/s` : " · click the screen to hear the phone";
    case "permission":
      return " · sound: allow it on the phone first";
    case "sharing":
      return " · sound comes with full speed";
    case "android":
      return " · sound needs Android 10 or newer on the phone";
    case "failed":
      return " · the phone's sound stopped: choose the sound again to retry";
    default:
      return playing ? "" : " · click the screen to hear the phone";
  }
}

/** The picture where the pad's keys are is black: Android hides the pad from screen sharing. */
function padHidden(lock: LockPad, canvas: HTMLCanvasElement | null, cw: number, ch: number) {
  const keys = lock.keys ?? [];
  if (!keys.length || !canvas || !cw || !ch || !lock.w || !lock.h) return true;
  try {
    const ctx = canvas.getContext("2d");
    if (!ctx) return true;
    let dark = 0;
    let n = 0;
    for (const k of keys) {
      const x = Math.round(((k.x + k.w / 2) * cw) / lock.w);
      const y = Math.round(((k.y + k.h / 2) * ch) / lock.h);
      if (x < 0 || y < 0 || x >= cw || y >= ch) continue;
      const d = ctx.getImageData(x, y, 1, 1).data;
      n++;
      if (d[0] + d[1] + d[2] < 24) dark++;
    }
    return n === 0 || dark / n > 0.8;
  } catch {
    return true;
  }
}

/**
 * The lock screen's pad, which screen sharing shows black: the keys, the
 * field and the words drawn where they are on the phone (PhoneLock.java),
 * over the picture, while the picture there is black. Nothing here takes a
 * click: a click is a tap on the real key below, and the number keys,
 * Backspace and Enter press the keys too.
 */
function LockLayer({
  lock,
  canvas,
  stage,
  cw,
  ch,
}: {
  lock: LockPad;
  canvas: React.RefObject<HTMLCanvasElement | null>;
  stage: React.RefObject<HTMLDivElement | null>;
  cw: number;
  ch: number;
}) {
  const [shown, setShown] = useState(false);
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!lock.pad) {
      setShown(false);
      return;
    }
    // The picture under the pad turns black a frame or two after the pad is up, and the window changes size.
    const look = () => {
      setShown(padHidden(lock, canvas.current, cw, ch));
      setTick((t) => t + 1);
    };
    look();
    const id = window.setInterval(look, 500);
    window.addEventListener("resize", look);
    return () => {
      window.clearInterval(id);
      window.removeEventListener("resize", look);
    };
  }, [lock, canvas, cw, ch]);
  const c = canvas.current;
  const s0 = stage.current;
  if (!shown || !lock.pad || !lock.w || !c || !s0 || !cw || !ch) return null;
  const r = c.getBoundingClientRect();
  const st = s0.getBoundingClientRect();
  const s = Math.min(r.width / cw, r.height / ch);
  const w = cw * s;
  const h = ch * s;
  const k = w / lock.w;
  const at = (b: Box, size: number): React.CSSProperties => ({
    position: "absolute", left: b.x * k, top: b.y * k, width: b.w * k, height: b.h * k,
    fontSize: Math.max(9, size * k), display: "flex", alignItems: "center", justifyContent: "center",
    textAlign: "center", lineHeight: 1,
  });
  const p = lock.pattern;
  const note = lock.pad === "pin"
    ? "The phone hides its PIN pad from screen sharing, so it is drawn here. Type the PIN on your keyboard, or click the keys."
    : lock.pad === "password"
      ? "The phone hides its password box from screen sharing. Type the password on your keyboard, then press Enter."
      : "The phone hides its pattern from screen sharing. Draw the pattern over the dots.";
  return (
    <>
      <div style={{ position: "absolute", left: r.left - st.left + (r.width - w) / 2, top: r.top - st.top + (r.height - h) / 2, width: w, height: h, pointerEvents: "none", color: "#e8ecf3", fontWeight: 600 }}>
        {(lock.words ?? []).map((wd, i) => (
          <div key={`w${i}`} style={{ ...at(wd, Math.min(wd.h * 0.6, 34)), color: "#cfd6e0", fontWeight: 500, lineHeight: 1.2 }}>{wd.s}</div>
        ))}
        {lock.field && (
          <div style={{ ...at(lock.field, lock.field.h * 0.4), borderBottom: "2px solid rgba(255,255,255,0.45)", letterSpacing: "0.3em" }}>
            {"●".repeat(Math.min(lock.field.n | 0, 24))}
          </div>
        )}
        {(lock.keys ?? []).map((key) => (
          <div
            key={key.k}
            style={{ ...at(key, key.h * (key.k.length > 1 ? 0.3 : 0.4)), borderRadius: "50%", background: "rgba(255,255,255,0.07)", borderWidth: 1, borderStyle: "solid", borderColor: "rgba(255,255,255,0.24)", opacity: key.k === "ok" && !key.on ? 0.35 : 1 }}
          >
            {key.k === "del" ? "⌫" : key.k === "ok" ? "OK" : key.k}
          </div>
        ))}
        {p && [...Array(9)].map((_, i) => {
          const r0 = Math.min(p.w, p.h) * 0.04;
          const cx = p.x + (p.w * (1 + 2 * (i % 3))) / 6;
          const cy = p.y + (p.h * (1 + 2 * Math.floor(i / 3))) / 6;
          return <div key={`d${i}`} style={{ ...at({ x: cx - r0, y: cy - r0, w: r0 * 2, h: r0 * 2 }, 0), borderRadius: "50%", background: "rgba(255,255,255,0.55)" }} />;
        })}
      </div>
      <div style={styles.lockNote}>{note}</div>
    </>
  );
}

const styles: Record<string, React.CSSProperties> = {
  window: { position: "fixed", inset: 0, display: "flex", flexDirection: "column", background: "#090b10", color: "#e8ecf3", font: "13px system-ui, sans-serif", userSelect: "none" },
  bar: { display: "flex", alignItems: "center", gap: 6, padding: "6px 8px", background: "#11151d", borderBottom: "1px solid #1d2330" },
  gap: { flex: 1 },
  // Longhands only: buttonOn changes the colour and weight, and React warns
  // when a shorthand (border, font) and its own longhand meet on a rerender.
  button: { minWidth: 34, height: 30, padding: "0 10px", borderRadius: 8, borderWidth: 1, borderStyle: "solid", borderColor: "#283041", background: "#161b25", color: "#e8ecf3", cursor: "pointer", fontFamily: "inherit", fontSize: "inherit", fontWeight: 400 },
  buttonOn: { background: "#4ade80", color: "#0b0f17", borderColor: "#4ade80", fontWeight: 600 },
  select: { height: 30, padding: "0 6px", borderRadius: 8, borderWidth: 1, borderStyle: "solid", borderColor: "#283041", background: "#161b25", color: "#e8ecf3", cursor: "pointer", fontFamily: "inherit", fontSize: "inherit" },
  stage: { position: "relative", flex: 1, minHeight: 0, overflow: "hidden" },
  canvas: { position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "contain", display: "block", outline: "none", touchAction: "none", cursor: "default" },
  cover: { position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 12, padding: 24, textAlign: "center", background: "rgba(9,11,16,0.92)" },
  coverTitle: { fontSize: 18, fontWeight: 700 },
  coverWords: { maxWidth: 360, lineHeight: 1.45, color: "#b8c0cc" },
  status: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8, padding: "5px 10px", background: "#11151d", borderTop: "1px solid #1d2330", color: "#9aa3af", fontSize: 12 },
  faint: { color: "#9aa3af" },
  protectNote: { position: "absolute", left: "50%", bottom: 12, transform: "translateX(-50%)", width: "max-content", maxWidth: "min(92%, 480px)", padding: "8px 8px 8px 12px", borderRadius: 10, background: "rgba(17,21,29,0.96)", borderWidth: 1, borderStyle: "solid", borderColor: "#ffb547", color: "#e8ecf3", fontSize: 13, lineHeight: 1.4, display: "flex", alignItems: "center", gap: 10, zIndex: 1 },
  helperNote: { position: "absolute", left: "50%", top: 10, transform: "translateX(-50%)", width: "min(92%, 540px)", padding: "12px 14px", borderRadius: 12, background: "rgba(17,21,29,0.97)", borderWidth: 1, borderStyle: "solid", borderColor: "#283041", color: "#e8ecf3", fontSize: 13, lineHeight: 1.45, display: "flex", flexDirection: "column", gap: 10, zIndex: 1 },
  helperCmdRow: { display: "flex", gap: 8, alignItems: "flex-start" },
  helperCmd: { flex: 1, minWidth: 0, font: "12px ui-monospace, Menlo, Consolas, monospace", background: "#0c1017", borderWidth: 1, borderStyle: "solid", borderColor: "#222a38", borderRadius: 8, padding: 8, wordBreak: "break-all", userSelect: "text", color: "#d6dde8" },
  helperClose: { alignSelf: "flex-end" },
  lockNote: { position: "absolute", left: "50%", top: 10, transform: "translateX(-50%)", width: "max-content", maxWidth: "min(92%, 440px)", padding: "8px 12px", borderRadius: 10, background: "rgba(17,21,29,0.94)", borderWidth: 1, borderStyle: "solid", borderColor: "#283041", color: "#e8ecf3", fontSize: 13, lineHeight: 1.4, textAlign: "center", pointerEvents: "none" },
};
