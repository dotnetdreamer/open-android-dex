import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { DesktopRouter } from "./features/remote/DesktopRouter";
import "./index.css";

// A phone's desktop window loads this same page with the phone in its hash
// (remote_desktop.rs) — over adb or through the tunnel, as DesktopRouter
// decides; every other window is the app.
const desktop = new URLSearchParams(window.location.hash.slice(1)).get("desktop");

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>{desktop ? <DesktopRouter phoneId={desktop} /> : <App />}</React.StrictMode>,
);
