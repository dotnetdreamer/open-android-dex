import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "../../ui/Icon";
import { Dialog, Segmented } from "../../ui/kit";
import {
  bind,
  fresh,
  fromButton,
  fromKey,
  kept,
  keyLabel,
  MAX_CONTROLS,
  nearest,
  point,
  refusal,
  sharing,
  SPEED,
  STICK_SIZE,
  LOOK_SIZE,
  TITLES,
  type Control,
  type Loaded,
} from "./model";
import { decodeStill } from "./still";

/**
 * The game controls' editor (doc/game-mode.md, "The editor"): its own window,
 * opened over the game's at its picture's size (apps/game/editor.rs), with a
 * still of the game and the controls on top. A control is dragged onto the
 * game's own button and clicked to choose its key; Done keeps them for this
 * game, turns them on and brings the game forward.
 */

type Binding = { index: number; field: string };
type Refused = Binding & { sentence: string };
type Picture = "loading" | "shown" | "none";

/** An imported file larger than this is no set of controls (layout.rs, MAX_BYTES): it is not read. */
const MAX_FILE = 64 * 1024;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export function GameEditor({ id }: { id: string }) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [controls, setControls] = useState<Control[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [binding, setBinding] = useState<Binding | null>(null);
  const [refused, setRefused] = useState<Refused | null>(null);
  const [warning, setWarning] = useState(false);
  const [picture, setPicture] = useState<Picture>("loading");
  const [saved, setSaved] = useState<{ name: string; path: string } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [foreign, setForeign] = useState<{ name: string; controls: Control[] } | null>(null);
  const [room, setRoom] = useState({ w: 0, h: 0 });
  const stage = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const still = useRef<VideoFrame | null>(null);
  const file = useRef<HTMLInputElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const keepCancel = useRef<HTMLButtonElement>(null);

  // What the game is, then its picture: asked for once the window is up.
  useEffect(() => {
    let gone = false;
    invoke<Loaded>("game_editor_load")
      .then((l) => {
        if (gone) return;
        setLoaded(l);
        setControls(l.controls ?? l.starter);
        setWarning(!l.warned);
        invoke<ArrayBuffer>("game_still")
          .then((buf) => decodeStill(buf))
          .then((frame) => {
            if (gone) {
              frame?.close();
              return;
            }
            still.current = frame;
            setPicture(frame ? "shown" : "none");
          })
          .catch(() => !gone && setPicture("none"));
      })
      .catch((e) => !gone && setFailed(String(e)));
    return () => {
      gone = true;
      still.current?.close();
    };
  }, [id]);

  // The still, drawn once its canvas is there: the app's part, the encoder's padding left off.
  useLayoutEffect(() => {
    const c = canvas.current;
    const frame = still.current;
    if (!c || !frame || !loaded || picture !== "shown") return;
    const { w, h } = loaded.picture;
    c.width = w;
    c.height = h;
    const sw = Math.min(w, frame.displayWidth);
    const sh = Math.min(h, frame.displayHeight);
    c.getContext("2d")?.drawImage(frame, 0, 0, sw, sh, 0, 0, sw, sh);
  }, [picture, loaded]);

  // The room the picture has: the window between its bars.
  useLayoutEffect(() => {
    const el = stage.current;
    if (!el) return;
    const measure = () => setRoom({ w: el.clientWidth, h: el.clientHeight });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [loaded]);

  // A key chip waiting: the next key, mouse button or notch of the wheel is its.
  useEffect(() => {
    if (!binding || !loaded) return;
    const take = (code: string | null) => {
      if (!code) return;
      const c = controls[binding.index];
      const always = c?.type === "look" && c.by === "always" && binding.field === "key";
      const why = c ? refusal(c, binding.field, code, loaded.mac) : null;
      if (always && code === "Backquote") {
        // The key under Esc is the one a look area takes the mouse with unless another is chosen.
        setRefused(null);
        setControls((cs) => bind(cs, binding.index, binding.field, null));
      } else if (why) {
        setRefused({ ...binding, sentence: why });
      } else if (kept(code, loaded.mac)) {
        setRefused({ ...binding, sentence: "Open Android DeX uses this key. Pick another." });
      } else {
        setRefused(null);
        setControls((cs) => bind(cs, binding.index, binding.field, code));
      }
      setBinding(null);
    };
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopImmediatePropagation();
      if (e.repeat) return;
      if (e.code === "Escape") {
        setBinding(null);
        return;
      }
      take(fromKey(e));
    };
    const onDown = (e: MouseEvent) => {
      // A click on another of the editor's buttons is that button's: the wait is over.
      const other = (e.target as Element | null)?.closest("button, input, [role='radio']");
      if (e.button === 0 && other && !other.classList.contains("is-waiting")) {
        setBinding(null);
        return;
      }
      e.preventDefault();
      e.stopImmediatePropagation();
      take(fromButton(e.button));
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (e.deltaY !== 0) take(e.deltaY < 0 ? "WheelUp" : "WheelDown");
    };
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("wheel", onWheel, { capture: true, passive: false });
    return () => {
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("wheel", onWheel, true);
    };
  }, [binding, loaded, controls]);

  // The webview's own menu is never this window's: a right click may be a key being chosen.
  useEffect(() => {
    const no = (e: Event) => e.preventDefault();
    window.addEventListener("contextmenu", no);
    return () => window.removeEventListener("contextmenu", no);
  }, []);

  // Esc, with nothing waiting for a key: nothing chosen.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !binding && !warning && !foreign) setSelected(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [binding, warning, foreign]);

  const pw = loaded?.picture.w ?? 1;
  const ph = loaded?.picture.h ?? 1;
  const s = Math.min(pw, ph);
  const scale = room.w > 0 && room.h > 0 ? Math.min(room.w / pw, room.h / ph) : 0;
  const box = { w: pw * scale, h: ph * scale, x: (room.w - pw * scale) / 2, y: (room.h - ph * scale) / 2 };

  // ── dragging ──

  const drag = useRef<{ index: number; mode: "move" | "size"; x0: number; y0: number; px: number; py: number } | null>(null);

  const startDrag = (e: React.PointerEvent, index: number, mode: "move" | "size") => {
    if (binding || e.button !== 0) return;
    e.stopPropagation();
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    const [px, py] = point(controls[index].at, pw, ph);
    drag.current = { index, mode, x0: e.clientX, y0: e.clientY, px, py };
    setSelected(index);
    setRefused(null);
  };

  const moveDrag = (e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || scale <= 0) return;
    const dx = (e.clientX - d.x0) / scale;
    const dy = (e.clientY - d.y0) / scale;
    setControls((cs) =>
      cs.map((c, i) => {
        if (i !== d.index) return c;
        if (d.mode === "move") return { ...c, at: nearest(clamp(d.px + dx, 0, pw), clamp(d.py + dy, 0, ph), pw, ph) };
        if (c.type !== "look") return c;
        // The corner handle: the area grows from its centre, inside the picture.
        const [cx, cy] = point(c.at, pw, ph);
        const r = (e.currentTarget as Element).closest(".game-picture")?.getBoundingClientRect();
        if (!r) return c;
        const x = (e.clientX - r.left) / scale;
        const y = (e.clientY - r.top) / scale;
        // Never past the picture, and never smaller than the smallest a file may hold — even at its edge.
        const most = (room: number) => Math.max(LOOK_SIZE.min, Math.min(LOOK_SIZE.max, (2 * room) / s));
        const w = clamp((2 * Math.abs(x - cx)) / s, LOOK_SIZE.min, most(Math.min(cx, pw - cx)));
        const h = clamp((2 * Math.abs(y - cy)) / s, LOOK_SIZE.min, most(Math.min(cy, ph - cy)));
        return { ...c, w, h };
      }),
    );
  };

  const endDrag = () => {
    drag.current = null;
  };

  // ── the bar's actions ──

  const add = (type: Control["type"]) => {
    if (controls.length >= MAX_CONTROLS) return;
    setControls((cs) => [...cs, fresh(type, cs, pw, ph)]);
    setSelected(controls.length);
    setRefused(null);
  };

  const remove = (index: number) => {
    setControls((cs) => cs.filter((_, i) => i !== index));
    setSelected(null);
    setBinding(null);
  };

  const update = (index: number, change: Partial<Control>) =>
    setControls((cs) => cs.map((c, i) => (i === index ? ({ ...c, ...change } as Control) : c)));

  const exportFile = async () => {
    setProblem(null);
    try {
      const path = await invoke<string>("game_export", { controls, shape: loaded?.shape });
      setSaved({ name: path.split(/[\\/]/).pop() ?? path, path });
    } catch (e) {
      setProblem(String(e));
    }
  };

  const importFile = async (f: File) => {
    setProblem(null);
    setSaved(null);
    if (f.size > MAX_FILE) {
      setProblem("That file isn't a set of game controls.");
      return;
    }
    try {
      const text = await f.text();
      // For the shape these will be placed on, as Done and Export store them.
      const got = await invoke<{ app: { pkg: string; name: string }; controls: Control[] }>("game_import", { text, shape: loaded?.shape });
      if (got.app.pkg && loaded && got.app.pkg !== loaded.app.pkg) {
        setForeign({ name: got.app.name || got.app.pkg, controls: got.controls });
      } else {
        setControls(got.controls);
        setSelected(null);
      }
    } catch (e) {
      setProblem(String(e));
    }
  };

  const done = async () => {
    setProblem(null);
    try {
      // Under the shape these were placed on, whatever shape the game's window is now.
      await invoke("game_save", { controls, shape: loaded?.shape });
    } catch (e) {
      setProblem(String(e));
    }
  };

  const close = () => void getCurrentWindow().close();

  if (failed) {
    return (
      <div className="game-editor">
        <div className="game-stage game-stage-message">
          <p className="body">{failed}</p>
        </div>
      </div>
    );
  }
  if (!loaded) {
    return (
      <div className="game-editor">
        <div className="game-stage game-stage-message">
          <span className="spinner" aria-label="Loading" />
        </div>
      </div>
    );
  }

  const toggleKey = loaded.mac ? "⌘G" : "F8";
  const sel = selected !== null && selected < controls.length ? selected : null;

  return (
    <div className="game-editor">
      <header className="game-bar">
        <span className="section-label">Add</span>
        <div className="flex items-center gap-1.5">
          {(["stick", "button", "look"] as const).map((t) => (
            <button key={t} type="button" className="btn btn-secondary btn-sm" disabled={controls.length >= MAX_CONTROLS} onClick={() => add(t)}>
              {TITLES[t]}
            </button>
          ))}
        </div>
        <span className="flex-1" />
        <button type="button" className="btn btn-quiet btn-sm" onClick={() => file.current?.click()}>
          <Icon name="upload" size={15} />
          Import
        </button>
        <button type="button" className="btn btn-quiet btn-sm" onClick={() => void exportFile()}>
          <Icon name="download" size={15} />
          Export
        </button>
        <button type="button" className="btn btn-primary btn-sm game-done" onClick={() => void done()}>
          Done
        </button>
        <input
          ref={file}
          type="file"
          accept=".json,application/json"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = "";
            if (f) void importFile(f);
          }}
        />
      </header>
      <p className="caption game-hint">Drag a control onto the game's button, then click it to pick a key.</p>

      <div ref={stage} className="game-stage" onPointerDown={() => !binding && setSelected(null)}>
        {scale > 0 && (
          <div
            className="game-picture"
            style={{ left: box.x, top: box.y, width: box.w, height: box.h }}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
          >
            {picture === "shown" && <canvas ref={canvas} className="game-still" />}
            {picture === "loading" && (
              <div className="game-picture-note">
                <span className="spinner" aria-hidden="true" />
              </div>
            )}
            {picture === "none" && (
              <div className="game-picture-note is-top">
                <p className="game-empty-text">Can't show the game here. Place the controls by eye, then test them in the game.</p>
              </div>
            )}
            {controls.map((c, i) => (
              <ControlView
                key={i}
                control={c}
                selected={i === sel}
                scale={scale}
                pw={pw}
                ph={ph}
                mac={loaded.mac}
                underEsc={loaded.underEsc !== false}
                share={sharing(controls, i)}
                onPointerDown={(e, mode) => startDrag(e, i, mode)}
              />
            ))}
            {controls.length === 0 && (
              <div className="game-empty">
                <p className="game-empty-text">No controls yet. Add one above.</p>
              </div>
            )}
          </div>
        )}
        {sel !== null && scale > 0 && (
          <Card
            control={controls[sel]}
            index={sel}
            near={place(controls[sel], pw, ph, s, scale, box)}
            room={room}
            mac={loaded.mac}
            underEsc={loaded.underEsc !== false}
            share={sharing(controls, sel)}
            binding={binding}
            refused={refused}
            onBind={(field) => {
              setRefused(null);
              setBinding({ index: sel, field });
            }}
            onChange={(change) => update(sel, change)}
            onRemove={() => remove(sel)}
          />
        )}
      </div>

      <p className="caption game-foot">
        Press {toggleKey} to turn controls on or off. Turn them off to type in chat.
      </p>

      {(saved || problem) && (
        <div className="pointer-events-none fixed inset-x-0 bottom-14 z-40 flex justify-center px-4" role="status" aria-live="polite">
          <div className="toast card-raised pointer-events-auto">
            <Icon name={problem ? "alert" : "check"} size={16} className={problem ? "text-[var(--bad)]" : "text-[var(--ok)]"} />
            <p className="min-w-0 flex-1 text-[var(--ink-1)]">{problem ?? `Saved to Downloads as “${saved!.name}”`}</p>
            {saved && !problem && (
              <button type="button" className="btn btn-secondary btn-sm -my-1" onClick={() => void invoke("game_reveal", { path: saved.path })}>
                Show
              </button>
            )}
            <button
              type="button"
              className="btn btn-quiet btn-sm btn-icon -my-1"
              aria-label="Dismiss"
              onClick={() => {
                setSaved(null);
                setProblem(null);
              }}
            >
              <Icon name="x" size={14} />
            </button>
          </div>
        </div>
      )}

      <Dialog
        open={warning}
        title="Check the game's rules first"
        initialFocus={cancel}
        onDismiss={close}
        actions={
          <>
            <button ref={cancel} type="button" className="btn btn-secondary" onClick={close}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                invoke("game_warned")
                  .then(() => setWarning(false))
                  .catch((e) => setProblem(String(e)));
              }}
            >
              Set up controls
            </button>
          </>
        }
      >
        <p>Some online games don't allow a keyboard and mouse. Your account could be banned.</p>
      </Dialog>

      <Dialog
        open={!!foreign}
        title="Use these controls here?"
        initialFocus={keepCancel}
        onDismiss={() => setForeign(null)}
        actions={
          <>
            <button ref={keepCancel} type="button" className="btn btn-secondary" onClick={() => setForeign(null)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                if (foreign) setControls(foreign.controls);
                setForeign(null);
                setSelected(null);
              }}
            >
              Use them
            </button>
          </>
        }
      >
        <p>
          These were made for {foreign?.name}. The buttons in {loaded.app.name} may be in other places.
        </p>
      </Dialog>
    </div>
  );
}

/** Where a control is drawn, in the stage's pixels: its box. */
function place(c: Control, pw: number, ph: number, s: number, scale: number, box: { x: number; y: number }) {
  const [x, y] = point(c.at, pw, ph);
  const [hw, hh] = half(c, s, scale);
  return { x: box.x + x * scale, y: box.y + y * scale, hw, hh };
}

/** A control's half width and height on screen. */
function half(c: Control, s: number, scale: number): [number, number] {
  if (c.type === "stick") {
    const r = Math.max(26, c.size * s * scale);
    return [r, r];
  }
  if (c.type === "look") return [(c.w * s * scale) / 2, (c.h * s * scale) / 2];
  // Never smaller than its key's name, and a target a pointer can take.
  const r = Math.max(24, 0.05 * s * scale);
  return [r, r];
}

function ControlView({
  control: c,
  selected,
  scale,
  pw,
  ph,
  mac,
  underEsc,
  share,
  onPointerDown,
}: {
  control: Control;
  selected: boolean;
  scale: number;
  pw: number;
  ph: number;
  mac: boolean;
  underEsc: boolean;
  share: { place: number; count: number };
  onPointerDown: (e: React.PointerEvent, mode: "move" | "size") => void;
}) {
  const s = Math.min(pw, ph);
  const [x, y] = point(c.at, pw, ph);
  const [hw, hh] = half(c, s, scale);
  const style = { left: x * scale, top: y * scale, width: hw * 2, height: hh * 2 };
  const cls = `game-control game-${c.type} ${selected ? "is-selected" : ""}`;
  const label = (code: string | null) => keyLabel(code, mac);
  if (c.type === "stick") {
    return (
      <div className={cls} style={style} onPointerDown={(e) => onPointerDown(e, "move")} aria-label={`Stick: ${[c.up, c.left, c.down, c.right].map(label).join(", ")}`}>
        <span className="game-stick-knob" aria-hidden="true" />
        <span className="game-stick-key up">{c.up ? label(c.up) : ""}</span>
        <span className="game-stick-key left">{c.left ? label(c.left) : ""}</span>
        <span className="game-stick-key down">{c.down ? label(c.down) : ""}</span>
        <span className="game-stick-key right">{c.right ? label(c.right) : ""}</span>
      </div>
    );
  }
  if (c.type === "look") {
    // The second of two that share a key is reached by pressing it twice.
    const times = share.place === 0 ? "" : share.place === 1 ? " twice" : ` ${share.place + 1} times`;
    const always = c.key ? `Press ${label(c.key)}${times}` : underEsc ? `The key under Esc${times && `,${times}`}` : "Pick a key";
    return (
      <div className={cls} style={style} onPointerDown={(e) => onPointerDown(e, "move")}>
        <div className="game-look-label">
          <span className="heading">{TITLES.look}</span>
          <span className="caption">{c.by === "always" ? always : c.key ? `Hold ${label(c.key)}` : "Pick a key"}</span>
        </div>
        {selected && <span className="game-look-handle" onPointerDown={(e) => onPointerDown(e, "size")} aria-label="Resize" />}
      </div>
    );
  }
  return (
    <div className={cls} style={style} onPointerDown={(e) => onPointerDown(e, "move")}>
      <span className={c.key ? "" : "text-[var(--ink-3)]"}>{c.key ? label(c.key) : "?"}</span>
    </div>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="game-card-row">
      <span className="body">{label}</span>
      {children}
    </div>
  );
}

function Card({
  control: c,
  index,
  near,
  room,
  mac,
  underEsc,
  share,
  binding,
  refused,
  onBind,
  onChange,
  onRemove,
}: {
  control: Control;
  index: number;
  near: { x: number; y: number; hw: number; hh: number };
  room: { w: number; h: number };
  mac: boolean;
  underEsc: boolean;
  share: { place: number; count: number };
  binding: Binding | null;
  refused: Refused | null;
  onBind: (field: string) => void;
  onChange: (change: Partial<Control>) => void;
  onRemove: () => void;
}) {
  const card = useRef<HTMLDivElement>(null);
  const [h, setH] = useState(220);
  useLayoutEffect(() => {
    if (card.current) setH(card.current.offsetHeight);
  });
  const W = 288;
  const gap = 12;
  // Beside the control, on whichever side has room; inside the stage.
  const right = near.x + near.hw + gap + W <= room.w - 8;
  const left = right ? near.x + near.hw + gap : Math.max(8, near.x - near.hw - gap - W);
  const top = clamp(near.y - h / 2, 8, Math.max(8, room.h - h - 8));

  const chip = (field: string, code: string | null, none?: string) => {
    const waiting = binding?.index === index && binding.field === field;
    return (
      <div className="flex flex-col items-end">
        <button
          type="button"
          className={`game-chip ${waiting ? "is-waiting" : ""} ${code || none ? "" : "is-none"}`}
          onClick={() => onBind(field)}
        >
          {waiting ? "Press a key or mouse button…" : code ? keyLabel(code, mac) : (none ?? keyLabel(null, mac))}
        </button>
      </div>
    );
  };
  const refusedHere = refused?.index === index;

  return (
    <div
      ref={card}
      className="game-card card-raised rise-in"
      style={{ left, top, width: W }}
      onPointerDown={(e) => e.stopPropagation()}
      role="group"
      aria-label={TITLES[c.type]}
    >
      <h2 className="heading mb-2.5">{TITLES[c.type]}</h2>
      {c.type === "stick" && (
        <>
          <Row label="Up">{chip("up", c.up)}</Row>
          <Row label="Left">{chip("left", c.left)}</Row>
          <Row label="Down">{chip("down", c.down)}</Row>
          <Row label="Right">{chip("right", c.right)}</Row>
          <Row label="Size">
            <input
              type="range"
              className="game-range"
              min={STICK_SIZE.min}
              max={STICK_SIZE.max}
              step={0.005}
              value={c.size}
              aria-label="Size"
              onChange={(e) => onChange({ size: Number(e.target.value) })}
            />
          </Row>
        </>
      )}
      {c.type === "button" && (
        <>
          <Row label="Key">{chip("key", c.key)}</Row>
          <div className="mt-2.5">
            <span className="body">When pressed</span>
            <Segmented
              className="mt-1.5 w-full"
              role="radiogroup"
              label="When pressed"
              value={c.press}
              options={[
                { value: "hold", label: "Hold" },
                { value: "tap", label: "Tap" },
              ]}
              onChange={(press) => onChange({ press })}
            />
            <p className="caption mt-1.5">{c.press === "hold" ? "Holds the button down while you hold the key." : "Taps the button once for each key press."}</p>
          </div>
        </>
      )}
      {c.type === "look" && (
        <>
          <span className="body">Aim with the mouse</span>
          <Segmented
            className="mt-1.5 w-full"
            role="radiogroup"
            label="Aim with the mouse"
            value={c.by}
            options={[
              { value: "hold", label: "While holding a key" },
              { value: "always", label: "All the time" },
            ]}
            // A key held to look, or one pressed to take the mouse: never the other's.
            onChange={(by) => {
              onChange(by === c.by ? {} : { by, key: null });
              // No key under Esc on this keyboard: the key is asked for at once.
              if (by === "always" && by !== c.by && !underEsc) onBind("key");
            }}
          />
          {c.by === "hold" ? (
            <div className="mt-2">
              <Row label="Key">{chip("key", c.key)}</Row>
            </div>
          ) : (
            <>
              <div className="mt-2">
                <Row label="Key">{chip("key", c.key, underEsc ? "Key under Esc" : "Pick a key")}</Row>
              </div>
              {!c.key && !underEsc ? (
                <p className="caption mt-1.5">This keyboard has no key under Esc. Pick a key to aim with.</p>
              ) : share.count > 1 ? (
                <p className="caption mt-1.5">
                  Shared with {share.count === 2 ? "another Aim" : "other Aims"}. Each press of {c.key ? keyLabel(c.key, mac) : "the key under Esc"}{" "}
                  moves to the next one.
                </p>
              ) : (
                <p className="caption mt-1.5">
                  Press {c.key ? keyLabel(c.key, mac) : "the key under Esc"} to aim with the mouse. Press it again or Esc to get your mouse
                  back.
                </p>
              )}
            </>
          )}
          <Row label="Speed">
            <input
              type="range"
              className="game-range"
              min={SPEED.min}
              max={SPEED.max}
              step={0.05}
              value={c.speed}
              aria-label="Speed"
              onChange={(e) => onChange({ speed: Number(e.target.value) })}
            />
          </Row>
        </>
      )}
      {refusedHere && <p className="caption game-refused">{refused?.sentence}</p>}
      <div className="mt-3 flex justify-end">
        <button type="button" className="btn btn-danger btn-sm" onClick={onRemove}>
          <Icon name="trash" size={14} />
          Remove
        </button>
      </div>
    </div>
  );
}
