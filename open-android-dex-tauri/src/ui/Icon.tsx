/**
 * The app's icons: one family, drawn here on a 24-unit grid with a 1.6 stroke,
 * so every glyph has the same weight and the same corners on every host. Emoji
 * were the stand-in, and they render as a different artist's drawing on each
 * OS — and as colour pictures in a UI that is otherwise ink.
 */

const PATHS = {
  phone: (
    <>
      <rect x="6.75" y="2.75" width="10.5" height="18.5" rx="2.6" />
      <path d="M10.75 18.25h2.5" />
    </>
  ),
  laptop: (
    <>
      <path d="M4.75 15.25V6.5a1.75 1.75 0 0 1 1.75-1.75h11a1.75 1.75 0 0 1 1.75 1.75v8.75" />
      <path d="M2.75 15.25h18.5l-.6 2.2a1.6 1.6 0 0 1-1.55 1.3H4.9a1.6 1.6 0 0 1-1.55-1.3z" />
    </>
  ),
  monitor: (
    <>
      <rect x="3" y="4" width="18" height="12.5" rx="2" />
      <path d="M9 20.25h6M12 16.5v3.75" />
    </>
  ),
  cable: (
    <>
      <path d="M9.25 3v3.5M14.75 3v3.5" />
      <path d="M7 6.5h10v3.75a5 5 0 0 1-10 0z" />
      <path d="M12 15.25V21" />
    </>
  ),
  wifi: (
    <>
      <path d="M2.75 8.9a14 14 0 0 1 18.5 0" />
      <path d="M5.75 12.35a9.4 9.4 0 0 1 12.5 0" />
      <path d="M8.9 15.75a4.8 4.8 0 0 1 6.2 0" />
      <circle cx="12" cy="19.1" r="1.05" fill="currentColor" stroke="none" />
    </>
  ),
  qr: (
    <>
      <rect x="3.75" y="3.75" width="6.5" height="6.5" rx="1.2" />
      <rect x="13.75" y="3.75" width="6.5" height="6.5" rx="1.2" />
      <rect x="3.75" y="13.75" width="6.5" height="6.5" rx="1.2" />
      <path d="M14 14h2.5v2.5H14zM17.5 17.5H20V20h-2.5zM14 19.75h1M19.75 14v1" />
    </>
  ),
  keypad: (
    <>
      {[6, 12, 18].flatMap((x) =>
        [5, 10.5, 16].map((y) => <circle key={`${x}-${y}`} cx={x} cy={y} r="1.35" fill="currentColor" stroke="none" />),
      )}
      <circle cx="12" cy="21" r="1.35" fill="currentColor" stroke="none" />
    </>
  ),
  cast: (
    <>
      <path d="M3 8.25V6.5a1.75 1.75 0 0 1 1.75-1.75h14.5A1.75 1.75 0 0 1 21 6.5v11a1.75 1.75 0 0 1-1.75 1.75H14" />
      <path d="M3 11.75a7.25 7.25 0 0 1 7.25 7.5" />
      <path d="M3 15.25a3.75 3.75 0 0 1 3.75 4" />
      <circle cx="3.6" cy="19" r="0.9" fill="currentColor" stroke="none" />
    </>
  ),
  check: <path d="M5 12.75l4.4 4.25L19 7.25" />,
  x: <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />,
  chevronRight: <path d="M9.5 5.75L15.75 12 9.5 18.25" />,
  chevronLeft: <path d="M14.5 5.75L8.25 12l6.25 6.25" />,
  chevronDown: <path d="M5.75 9.25L12 15.5l6.25-6.25" />,
  arrowRight: <path d="M4.75 12h14.5M13.5 6.25L19.25 12l-5.75 5.75" />,
  arrowUpRight: <path d="M7.25 16.75l9.5-9.5M8.5 7.25h8.25v8.25" />,
  refresh: (
    <>
      <path d="M19.25 12a7.25 7.25 0 1 1-2.12-5.13" />
      <path d="M19.4 4.6v4.15h-4.15" />
    </>
  ),
  doc: (
    <>
      <path d="M6.25 3.75h7.5l4 4v12.5a1 1 0 0 1-1 1H6.25a1 1 0 0 1-1-1V4.75a1 1 0 0 1 1-1z" />
      <path d="M13.5 3.75v4.25h4.25M8.75 12.25h6.5M8.75 15.75h6.5" />
    </>
  ),
  activity: <path d="M2.75 12.25h3.6l2.4-6.5 5 13 2.6-6.5h4.9" />,
  help: (
    <>
      <circle cx="12" cy="12" r="9.25" />
      <path d="M9.35 9.4a2.75 2.75 0 0 1 5.3 1c0 1.85-2.65 2.4-2.65 4" />
      <circle cx="12" cy="17.35" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9.25" />
      <path d="M12 10.75v6" />
      <circle cx="12" cy="7.6" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  alert: (
    <>
      <path d="M10.4 4.4a1.85 1.85 0 0 1 3.2 0l7.3 12.85a1.85 1.85 0 0 1-1.6 2.75H4.7a1.85 1.85 0 0 1-1.6-2.75z" />
      <path d="M12 9.5v4.25" />
      <circle cx="12" cy="16.6" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  home: (
    <>
      <path d="M3.75 11.25L12 4.25l8.25 7" />
      <path d="M6 9.5v9.25a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V9.5" />
      <path d="M10 19.75v-4.5a2 2 0 0 1 4 0v4.5" />
    </>
  ),
  windowClose: (
    <>
      <rect x="3" y="4.25" width="18" height="15.5" rx="2.25" />
      <path d="M3 8.5h18M10 12.25l4 4M14 12.25l-4 4" />
    </>
  ),
  power: (
    <>
      <path d="M12 3.25v8" />
      <path d="M7.05 6.4a7.5 7.5 0 1 0 9.9 0" />
    </>
  ),
  play: <path d="M8 5.6v12.8a.75.75 0 0 0 1.14.64l10.2-6.4a.75.75 0 0 0 0-1.28L9.14 4.96A.75.75 0 0 0 8 5.6z" />,
  terminal: (
    <>
      <rect x="2.75" y="4.25" width="18.5" height="15.5" rx="2.5" />
      <path d="M7 9.5l3 2.5-3 2.5M12.75 15h4.25" />
    </>
  ),
  code: <path d="M8.5 7.25L3.75 12l4.75 4.75M15.5 7.25L20.25 12l-4.75 4.75M13.6 4.75l-3.2 14.5" />,
  copy: (
    <>
      <rect x="8.75" y="8.75" width="11.5" height="11.5" rx="2" />
      <path d="M15.25 8.75v-3a2 2 0 0 0-2-2H5.75a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h3" />
    </>
  ),
  more: (
    <>
      <circle cx="5.5" cy="12" r="1.5" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
      <circle cx="18.5" cy="12" r="1.5" fill="currentColor" stroke="none" />
    </>
  ),
  lock: (
    <>
      <rect x="4.75" y="10.25" width="14.5" height="10" rx="2.25" />
      <path d="M8.25 10.25V7.5a3.75 3.75 0 0 1 7.5 0v2.75" />
    </>
  ),
  globe: (
    <>
      <circle cx="12" cy="12" r="9.25" />
      <path d="M2.75 12h18.5M12 2.75c2.5 2.5 3.75 5.6 3.75 9.25S14.5 18.75 12 21.25c-2.5-2.5-3.75-5.6-3.75-9.25S9.5 5.25 12 2.75z" />
    </>
  ),
  trash: (
    <>
      <path d="M4.25 6.75h15.5M9.75 6.75V4.5a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 .75.75v2.25" />
      <path d="M6.25 6.75l.8 12.6a1.5 1.5 0 0 0 1.5 1.4h6.9a1.5 1.5 0 0 0 1.5-1.4l.8-12.6" />
    </>
  ),
  fullscreen: <path d="M4 9V4.75A.75.75 0 0 1 4.75 4H9M15 4h4.25a.75.75 0 0 1 .75.75V9M20 15v4.25a.75.75 0 0 1-.75.75H15M9 20H4.75A.75.75 0 0 1 4 19.25V15" />,
  clipboard: (
    <>
      <path d="M8.75 4.75H6.5a1.5 1.5 0 0 0-1.5 1.5v13.25a1.5 1.5 0 0 0 1.5 1.5h11a1.5 1.5 0 0 0 1.5-1.5V6.25a1.5 1.5 0 0 0-1.5-1.5h-2.25" />
      <rect x="8.75" y="3" width="6.5" height="3.5" rx="1" />
    </>
  ),
  download: (
    <>
      <path d="M12 3.75v11.5M7.5 10.75L12 15.25l4.5-4.5" />
      <path d="M4.25 15v3.25a2 2 0 0 0 2 2h11.5a2 2 0 0 0 2-2V15" />
    </>
  ),
  upload: (
    <>
      <path d="M12 15.25V3.75M7.5 8.25L12 3.75l4.5 4.5" />
      <path d="M4.25 15v3.25a2 2 0 0 0 2 2h11.5a2 2 0 0 0 2-2V15" />
    </>
  ),
  minus: <path d="M5 12h14" />,
  link: (
    <>
      <path d="M10.25 13.75a4 4 0 0 0 5.65 0l3-3a4 4 0 0 0-5.65-5.65l-1 1" />
      <path d="M13.75 10.25a4 4 0 0 0-5.65 0l-3 3a4 4 0 0 0 5.65 5.65l1-1" />
    </>
  ),
  sparkle: <path d="M12 3.5c.55 3.9 2.6 6 6.5 6.5-3.9.55-5.95 2.6-6.5 6.5-.55-3.9-2.6-5.95-6.5-6.5 3.9-.5 5.95-2.6 6.5-6.5zM18.5 15.5c.25 1.6 1 2.35 2.6 2.6-1.6.25-2.35 1-2.6 2.6-.25-1.6-1-2.35-2.6-2.6 1.6-.25 2.35-1 2.6-2.6z" />,
  apps: (
    <>
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.8" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.8" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.8" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.8" />
    </>
  ),
  search: (
    <>
      <circle cx="10.75" cy="10.75" r="6" />
      <path d="M15.25 15.25l4.5 4.5" />
    </>
  ),
  bell: (
    <>
      <path d="M5.5 16.75h13l-1.05-1.5a3.3 3.3 0 0 1-.6-1.9V10.6a4.85 4.85 0 0 0-9.7 0v2.75a3.3 3.3 0 0 1-.6 1.9z" />
      <path d="M12 5.75V4.5" />
      <path d="M10.15 19.35a2 2 0 0 0 3.7 0" />
    </>
  ),
  bellOff: (
    <>
      <path d="M16.85 12.2V10.6a4.85 4.85 0 0 0-7.6-4M7.15 9.4v3.95a3.3 3.3 0 0 1-.6 1.9l-1.05 1.5h11.25" />
      <path d="M12 5.75V4.5" />
      <path d="M10.15 19.35a2 2 0 0 0 3.7 0" />
      <path d="M4.25 4.25l15.5 15.5" />
    </>
  ),
  eye: (
    <>
      <path d="M2.75 12s3.35-6.25 9.25-6.25S21.25 12 21.25 12 17.9 18.25 12 18.25 2.75 12 2.75 12z" />
      <circle cx="12" cy="12" r="2.75" />
    </>
  ),
  call: (
    <path d="M6.6 3.75h2.2l1.4 4.1-1.85 1.25a11 11 0 0 0 6.55 6.55l1.25-1.85 4.1 1.4v2.2a2 2 0 0 1-2.1 2A16.1 16.1 0 0 1 4.6 5.85a2 2 0 0 1 2-2.1z" />
  ),
  speaker: (
    <>
      <path d="M4.25 9.5v5h3.5l4.5 3.75V5.75L7.75 9.5z" />
      <path d="M15.6 9.15a4 4 0 0 1 0 5.7M18.2 6.7a7.6 7.6 0 0 1 0 10.6" />
    </>
  ),
  sync: (
    <>
      <path d="M19.25 11a7.25 7.25 0 0 0-13.1-3.5M4.75 13a7.25 7.25 0 0 0 13.1 3.5" />
      <path d="M6.15 3.75v3.75H9.9M17.85 20.25V16.5H14.1" />
    </>
  ),
  send: (
    <>
      <path d="M4.5 11.4 19.75 4.25 13.6 19.5l-2.3-5.85z" />
      <path d="M11.3 13.65 19.75 4.25" />
    </>
  ),
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({
  name,
  size = 16,
  className = "",
  strokeWidth = 1.6,
}: {
  name: IconName;
  size?: number;
  className?: string;
  strokeWidth?: number;
}) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`icon ${className}`}
      aria-hidden="true"
      focusable="false"
    >
      {PATHS[name]}
    </svg>
  );
}
