import { useId } from "react";

/**
 * The phone and the computer, drawn as what is actually happening between
 * them — the one picture every screen of the desktop flow shares.
 *
 * It is a status display before it is decoration. A person who does not know
 * what "adb" or "unauthorized" means can still see that the cable is not in,
 * that the phone is showing a question, or that the desktop is building on the
 * computer's screen — and because it is the same drawing from boot to "ready",
 * moving between screens changes what is on the two screens and the cable
 * between them, never the whole window.
 *
 * Geometry is on a 440×212 canvas: the computer's lid at x 146–398, its base
 * 126–418, the phone at 40–104 standing on the same line (y 176), and the cable
 * from the base's left side, under the desk line, up into the phone's port.
 */

export type Scene =
  /** Nothing yet: the cable's free end hangs under the phone, asking to be plugged in. */
  | "waiting"
  /** Looking for a phone over Wi-Fi. */
  | "searching"
  /** On the cable, and the phone is asking whether to trust this computer. */
  | "allow"
  /** On the cable, and the phone needs something done on it. */
  | "alert"
  /** The desktop is building on the computer's screen; `progress` says how far. */
  | "launching"
  | "live"
  /** Linked, but the desktop could not be put up. */
  | "error"
  /** This app could not start its side: nothing linked, and the computer's screen says so. */
  | "broken"
  /** The session is over: still linked, both screens dark. */
  | "ended"
  /** Linux on the phone, reached from anywhere. */
  | "remote";

export function Stage({
  scene,
  via = "usb",
  progress = 0,
  stalled = false,
  className = "",
}: {
  scene: Scene;
  via?: "usb" | "wifi";
  /** 0–3 while launching: linked, display made, launcher up, done. */
  progress?: number;
  /** The link dropped and is coming back: nothing flows, and the computer's screen dims. */
  stalled?: boolean;
  className?: string;
}) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  const id = (name: string) => `${uid}-${name}`;
  const url = (name: string) => `url(#${id(name)})`;

  const wireless = via === "wifi" || scene === "searching" || scene === "remote";
  const plugged = !wireless && scene !== "waiting" && scene !== "broken";
  const flowing = (scene === "launching" || scene === "live") && !stalled;
  const step = scene === "live" ? 3 : scene === "launching" ? progress : -1;

  const phone =
    scene === "allow"
      ? "allow"
      : scene === "alert"
        ? "alert"
        : scene === "remote"
          ? "terminal"
          : scene === "launching" || scene === "live"
            ? "linked"
            : "off";

  return (
    <svg
      viewBox="0 0 440 212"
      className={`stage ${className}`}
      data-scene={scene}
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={id("body")} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1b1f28" />
          <stop offset="1" stopColor="#101319" />
        </linearGradient>
        <linearGradient id={id("edge")} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.30" />
          <stop offset="0.5" stopColor="#ffffff" stopOpacity="0.10" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0.06" />
        </linearGradient>
        <linearGradient id={id("base")} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#2a2f3a" />
          <stop offset="1" stopColor="#14171d" />
        </linearGradient>
        <linearGradient id={id("glass")} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.07" />
          <stop offset="0.45" stopColor="#ffffff" stopOpacity="0.015" />
          <stop offset="0.46" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <linearGradient id={id("wall")} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" className="st-wall-a" />
          <stop offset="1" stopColor="#05070c" />
        </linearGradient>
        <radialGradient id={id("blob-a")}>
          <stop offset="0" className="st-accent-stop" stopOpacity="0.55" />
          <stop offset="1" className="st-accent-stop" stopOpacity="0" />
        </radialGradient>
        <radialGradient id={id("blob-b")}>
          <stop offset="0" stopColor="#2dd4bf" stopOpacity="0.28" />
          <stop offset="1" stopColor="#2dd4bf" stopOpacity="0" />
        </radialGradient>
        <radialGradient id={id("glow")}>
          <stop offset="0" className="st-accent-stop" stopOpacity="0.30" />
          <stop offset="0.55" className="st-accent-stop" stopOpacity="0.08" />
          <stop offset="1" className="st-accent-stop" stopOpacity="0" />
        </radialGradient>
        <radialGradient id={id("ground")}>
          <stop offset="0" stopColor="#000000" stopOpacity="0.55" />
          <stop offset="1" stopColor="#000000" stopOpacity="0" />
        </radialGradient>
        <clipPath id={id("screen")}>
          <rect x="154" y="20" width="236" height="138" rx="5" />
        </clipPath>
        <clipPath id={id("phone-screen")}>
          <rect x="43.5" y="57.5" width="57" height="115" rx="9" />
        </clipPath>
      </defs>

      {/* light from the computer's screen, once there is something on it */}
      <ellipse
        cx="272"
        cy="96"
        rx="200"
        ry="118"
        fill={url("glow")}
        className="st-fade"
        style={{ opacity: step >= 3 ? 1 : step >= 1 ? 0.55 : step === 0 ? 0.25 : 0 }}
      />
      <ellipse cx="228" cy="180" rx="206" ry="11" fill={url("ground")} />

      {/* ── the link ── */}
      {wireless ? (
        <g className={`st-air ${flowing ? "is-flowing" : ""} ${scene === "ended" || scene === "error" ? "is-idle" : ""}`}>
          <path d="M84 44C96 18 126 10 146 30" className="st-air-track" />
          <path d="M84 44C96 18 126 10 146 30" className="st-air-dots" />
          <g transform="translate(72 41)" className="st-air-mark">
            <path d="M-8.5 -2.5a12 12 0 0 1 17 0" />
            <path d="M-5 1a7 7 0 0 1 10 0" />
            <circle cx="0" cy="4.6" r="1.5" />
          </g>
        </g>
      ) : (
        <g className={`st-cable ${plugged ? "is-plugged" : "is-waiting"} ${flowing ? "is-flowing" : ""}`}>
          <path d="M126 171C114 172 112 204 94 207C82 209 72 209 72 203L72 193" className="st-cable-line" />
          <path d="M126 171C114 172 112 204 94 207C82 209 72 209 72 203L72 193" className="st-cable-pulse" />
          <g className="st-plug">
            <path d="M72 194V203" className="st-cable-line" />
            <rect x="69.25" y="179.6" width="5.5" height="3.6" rx="1" className="st-plug-tip" />
            <rect x="67" y="182.6" width="10" height="9.6" rx="2.4" fill={url("base")} stroke={url("edge")} strokeWidth="1" />
            <rect x="69.4" y="191.8" width="5.2" height="3.4" rx="1.4" fill="#1a1d24" />
          </g>
        </g>
      )}

      {/* ── the computer ── */}
      <rect x="146" y="12" width="252" height="154" rx="11" fill={url("body")} stroke={url("edge")} strokeWidth="1.25" />
      <rect x="154" y="20" width="236" height="138" rx="5" fill="#05070a" />
      <g clipPath={url("screen")} className="st-fade" style={{ opacity: stalled ? 0.4 : 1 }}>
        {/* the wallpaper comes up first … */}
        <g className="st-fade" style={{ opacity: step >= 1 ? 1 : step === 0 ? 0.18 : 0 }}>
          <rect x="154" y="20" width="236" height="138" fill={url("wall")} />
          <circle cx="206" cy="46" r="96" fill={url("blob-a")} />
          <circle cx="372" cy="150" r="86" fill={url("blob-b")} />
        </g>
        {/* … then the taskbar … */}
        <g className={`st-rise ${step >= 2 ? "is-in" : ""}`}>
          <rect x="154" y="146" width="236" height="12" fill="#06080c" fillOpacity="0.82" />
          <rect x="159.5" y="149.5" width="15" height="5" rx="2.5" fill="#ffffff" fillOpacity="0.16" />
          {[
            { x: 254, c: "var(--accent)" },
            { x: 263, c: "#ffffff", o: 0.55 },
            { x: 272, c: "#3ddc97" },
            { x: 281, c: "#f5b74f" },
            { x: 290, c: "#ffffff", o: 0.3 },
          ].map((d) => (
            <rect key={d.x} x={d.x - 3} y="149" width="6" height="6" rx="1.6" fill={d.c} fillOpacity={d.o ?? 0.9} />
          ))}
          <rect x="366" y="150.5" width="18" height="3.2" rx="1.6" fill="#ffffff" fillOpacity="0.3" />
        </g>
        {/* … then the windows the person left open */}
        <g className={`st-pop ${step >= 3 ? "is-in" : ""}`}>
          <g>
            <rect x="172.5" y="34.5" width="118" height="80" rx="4" fill="#111723" fillOpacity="0.92" stroke="#ffffff" strokeOpacity="0.1" />
            <path d="M172.5 38.5a4 4 0 0 1 4-4h110a4 4 0 0 1 4 4v5h-118z" fill="#ffffff" fillOpacity="0.06" />
            <circle cx="282" cy="39.5" r="1.4" fill="#ffffff" fillOpacity="0.35" />
            <circle cx="276.5" cy="39.5" r="1.4" fill="#ffffff" fillOpacity="0.22" />
            <rect x="180" y="52" width="46" height="4" rx="2" fill="#ffffff" fillOpacity="0.42" />
            <rect x="180" y="61" width="94" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
            <rect x="180" y="68" width="82" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
            <rect x="180" y="75" width="88" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
            <rect x="180" y="86" width="36" height="9" rx="3" className="st-accent-fill" fillOpacity="0.85" />
          </g>
          <g>
            <rect x="244.5" y="60.5" width="128" height="80" rx="4" fill="#0d1119" stroke="#ffffff" strokeOpacity="0.14" />
            <path d="M244.5 64.5a4 4 0 0 1 4-4h120a4 4 0 0 1 4 4v5h-128z" fill="#ffffff" fillOpacity="0.07" />
            <circle cx="364" cy="65.5" r="1.4" fill="#ffffff" fillOpacity="0.4" />
            <circle cx="358.5" cy="65.5" r="1.4" fill="#ffffff" fillOpacity="0.25" />
            <rect x="252" y="76" width="52" height="56" rx="3" className="st-accent-fill" fillOpacity="0.28" />
            <circle cx="268" cy="94" r="7" className="st-accent-fill" fillOpacity="0.55" />
            <path d="M252 124l14-14 10 9 8-7 20 20v1a3 3 0 0 1-3 3h-46a3 3 0 0 1-3-3z" fill="#ffffff" fillOpacity="0.14" />
            <rect x="312" y="78" width="50" height="4" rx="2" fill="#ffffff" fillOpacity="0.42" />
            <rect x="312" y="87" width="44" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
            <rect x="312" y="94" width="48" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
            <rect x="312" y="101" width="34" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.16" />
          </g>
          <path
            d="M330 112.5v13.2l3.3-3.1 2.4 5.2 2.3-1.1-2.4-5.1 4.6-.3z"
            fill="#ffffff"
            stroke="#05070a"
            strokeWidth="0.9"
            strokeLinejoin="round"
          />
        </g>
        {/* the desktop could not be put up */}
        <g className={`st-pop ${scene === "error" || scene === "broken" ? "is-in" : ""}`}>
          <path
            d="M268.6 69.6a4 4 0 0 1 6.8 0l15.2 26.4a4 4 0 0 1-3.4 6H256.8a4 4 0 0 1-3.4-6z"
            fill="#f5b74f"
            fillOpacity="0.12"
            stroke="#f5b74f"
            strokeWidth="1.6"
            strokeLinejoin="round"
          />
          <path d="M272 80.5v9" stroke="#f5b74f" strokeWidth="2" strokeLinecap="round" />
          <circle cx="272" cy="95" r="1.4" fill="#f5b74f" />
          <rect x="242" y="112" width="60" height="3.5" rx="1.75" fill="#ffffff" fillOpacity="0.22" />
          <rect x="252" y="120" width="40" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.12" />
        </g>
        {/* Linux, reached from here */}
        <g className={`st-pop ${scene === "remote" ? "is-in" : ""}`}>
          <rect x="186.5" y="40.5" width="172" height="100" rx="5" fill="#0a0d12" stroke="#ffffff" strokeOpacity="0.14" />
          <path d="M186.5 45.5a5 5 0 0 1 5-5h162a5 5 0 0 1 5 5v4h-172z" fill="#ffffff" fillOpacity="0.06" />
          <path d="M198 64l6 4.5-6 4.5" fill="none" stroke="#3ddc97" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="209" y="66.5" width="52" height="4" rx="2" fill="#ffffff" fillOpacity="0.5" />
          <rect x="198" y="80" width="118" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.18" />
          <rect x="198" y="87" width="96" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.18" />
          <rect x="198" y="94" width="108" height="3" rx="1.5" fill="#ffffff" fillOpacity="0.18" />
          <path d="M198 108l6 4.5-6 4.5" fill="none" stroke="#3ddc97" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="209" y="110.5" width="7" height="4" rx="1" className="st-caret" fill="#ffffff" fillOpacity="0.85" />
        </g>
      </g>
      <rect x="154" y="20" width="236" height="138" rx="5" fill={url("glass")} pointerEvents="none" />
      <circle cx="272" cy="16" r="1.25" fill="#ffffff" fillOpacity="0.16" />
      <rect x="126" y="166" width="292" height="10" rx="5" fill={url("base")} stroke={url("edge")} strokeWidth="1" />
      <rect x="250" y="166.5" width="44" height="2.6" rx="1.3" fill="#000000" fillOpacity="0.35" />

      {/* ── the phone ── */}
      <rect x="40" y="54" width="64" height="122" rx="12" fill={url("body")} stroke={url("edge")} strokeWidth="1.25" />
      <rect x="103.4" y="80" width="1.8" height="13" rx="0.9" fill="#ffffff" fillOpacity="0.14" />
      <rect x="43.5" y="57.5" width="57" height="115" rx="9" fill="#05070a" />
      <g clipPath={url("phone-screen")}>
        {/* asking to trust this computer */}
        <g className={`st-fade ${phone === "allow" ? "" : "is-out"}`}>
          <rect x="43.5" y="57.5" width="57" height="115" fill={url("wall")} opacity="0.55" />
          <rect x="43.5" y="57.5" width="57" height="115" fill="#000000" fillOpacity="0.45" />
          <rect x="47" y="112" width="50" height="54" rx="7" fill="#1c212b" stroke="#ffffff" strokeOpacity="0.08" />
          <rect x="52" y="119" width="30" height="3.4" rx="1.7" fill="#ffffff" fillOpacity="0.6" />
          <rect x="52" y="126" width="40" height="2.4" rx="1.2" fill="#ffffff" fillOpacity="0.22" />
          <rect x="52" y="131" width="28" height="2.4" rx="1.2" fill="#ffffff" fillOpacity="0.22" />
          <rect x="52.2" y="138.2" width="4.6" height="4.6" rx="1.1" fill="none" className="st-accent-stroke" strokeWidth="1.1" />
          <path d="M53.3 140.5l1.1 1.1 1.9-2" fill="none" className="st-accent-stroke" strokeWidth="0.9" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="59" y="139.3" width="24" height="2.4" rx="1.2" fill="#ffffff" fillOpacity="0.22" />
          <rect x="52" y="150" width="19" height="8.5" rx="4.25" fill="#ffffff" fillOpacity="0.1" />
          <rect x="74" y="150" width="19" height="8.5" rx="4.25" className="st-accent-fill" />
          <rect x="74" y="150" width="19" height="8.5" rx="4.25" fill="none" className="st-accent-stroke st-ping" strokeWidth="1.5" />
        </g>
        {/* the phone is the desktop now */}
        <g className={`st-fade ${phone === "linked" ? "" : "is-out"}`}>
          <circle cx="72" cy="112" r="40" fill={url("blob-a")} opacity="0.7" />
          <rect x="60.5" y="104.5" width="23" height="15" rx="2.5" fill="none" stroke="#ffffff" strokeOpacity="0.75" strokeWidth="1.3" />
          <path d="M67 123.5h10" stroke="#ffffff" strokeOpacity="0.75" strokeWidth="1.3" strokeLinecap="round" />
          <circle cx="72" cy="112" r="2" className="st-accent-fill" />
        </g>
        {/* something to do on the phone */}
        <g className={`st-fade ${phone === "alert" ? "" : "is-out"}`}>
          <path
            d="M69 103.2a3.5 3.5 0 0 1 6 0l11 19a3.5 3.5 0 0 1-3 5.3H61a3.5 3.5 0 0 1-3-5.3z"
            fill="#f5b74f"
            fillOpacity="0.12"
            stroke="#f5b74f"
            strokeWidth="1.5"
            strokeLinejoin="round"
          />
          <path d="M72 111v7" stroke="#f5b74f" strokeWidth="1.8" strokeLinecap="round" />
          <circle cx="72" cy="122.4" r="1.2" fill="#f5b74f" />
        </g>
        {/* Linux, running on it */}
        <g className={`st-fade ${phone === "terminal" ? "" : "is-out"}`}>
          <path d="M51 82l5 3.6-5 3.6" fill="none" stroke="#3ddc97" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="59" y="84" width="22" height="3.2" rx="1.6" fill="#ffffff" fillOpacity="0.5" />
          <rect x="51" y="96" width="40" height="2.6" rx="1.3" fill="#ffffff" fillOpacity="0.18" />
          <rect x="51" y="102" width="32" height="2.6" rx="1.3" fill="#ffffff" fillOpacity="0.18" />
          <rect x="51" y="108" width="36" height="2.6" rx="1.3" fill="#ffffff" fillOpacity="0.18" />
          <path d="M51 120l5 3.6-5 3.6" fill="none" stroke="#3ddc97" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="59" y="122" width="5" height="3.4" rx="0.8" className="st-caret" fill="#ffffff" fillOpacity="0.85" />
        </g>
      </g>
      <rect x="43.5" y="57.5" width="57" height="115" rx="9" fill={url("glass")} pointerEvents="none" />
      <circle cx="72" cy="63.5" r="1.7" fill="#1d2129" />
    </svg>
  );
}
