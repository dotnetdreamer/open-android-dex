import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { DesktopRouter } from "./features/remote/DesktopRouter";
import { PhoneWindow } from "./features/remote/phone/PhoneWindow";
import { GameEditor } from "./features/game/GameEditor";
import { PmosDesktop } from "./features/pmos/PmosDesktop";
import "./index.css";

// A phone's desktop window loads this same page with the phone in its hash
// (remote_desktop.rs) — over adb or through the tunnel, as DesktopRouter
// decides; every other window is the app.
const desktop = new URLSearchParams(window.location.hash.slice(1)).get("desktop");
// A game's controls editor, with its window's id (apps/game/editor.rs).
const game = new URLSearchParams(window.location.hash.slice(1)).get("game");
// A postmarketOS phone's desktop (pmos.rs).
const pmos = new URLSearchParams(window.location.hash.slice(1)).get("pmos");
// A phone's own screen, Remote desktop (remote_desktop.rs, remote_open_phone).
const phone = new URLSearchParams(window.location.hash.slice(1)).get("phone");

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    {desktop ? (
      <DesktopRouter phoneId={desktop} />
    ) : game ? (
      <GameEditor id={game} />
    ) : pmos ? (
      <PmosDesktop id={pmos} />
    ) : phone ? (
      <PhoneWindow phoneId={phone} />
    ) : (
      <App />
    )}
  </React.StrictMode>,
);
