import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { DesktopWindow } from "./features/remote/DesktopWindow";
import "./index.css";

// A phone's desktop window loads this same page with the phone in its hash
// (remote_desktop.rs); every other window is the app.
const desktop = new URLSearchParams(window.location.hash.slice(1)).get("desktop");

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>{desktop ? <DesktopWindow phoneId={desktop} /> : <App />}</React.StrictMode>,
);
