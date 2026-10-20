import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { DexWindow } from "./features/dex/DexWindow";
import { DesktopRouter } from "./features/remote/DesktopRouter";
import "./index.css";

// A phone's Linux desktop window loads this same page with the phone in its
// hash (remote_desktop.rs) — over adb or through the tunnel, as DesktopRouter
// decides; the Android desktop's own window with its session (dex.rs); every
// other window is the app.
const hash = new URLSearchParams(window.location.hash.slice(1));
const desktop = hash.get("desktop");
const dex = hash.get("dex");

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    {dex ? <DexWindow sessionKey={dex} /> : desktop ? <DesktopRouter phoneId={desktop} /> : <App />}
  </React.StrictMode>,
);
