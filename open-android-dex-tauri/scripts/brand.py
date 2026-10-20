"""The Open Android DeX mark, defined once and written out for every place it
appears, so every copy is the same drawing.

    python3 scripts/brand.py <out-dir>

writes:

  logo-macos.svg   the macOS icon: Apple's 824 tile inset in a 1024 canvas,
                   with its shadow
  logo.svg         the same tile, full bleed: Windows, Linux, the DeX window
  logo-flat.svg    full bleed without shadows: the app's own UI
                   (public/logo.svg)
  ic_launcher_{foreground,background,monochrome}.xml
                   the launcher's adaptive icon (openandroiddex-launcher/app/
                   src/main/res/drawable/)
  android-*.svg    previews of the adaptive icon under a round mask, a Pixel
                   mask and as a themed icon, for looking at, not shipping

To refresh the desktop icons, render logo.svg and logo-macos.svg to 1024x1024
PNGs with transparency in anything that draws SVG filters (a browser does),
run `npx tauri icon <png> -o <dir>` on each, and copy into src-tauri/icons:
icon.icns from the macOS one, every PNG and icon.ico from the full-bleed one.
src-tauri/resources/icon/scrcpy.png is logo.svg at 256x256, and
doc/img/logo.png the same.
"""
import os
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)

# ── the scene, in the 1024 canvas of a macOS icon (tile at 100..924) ──────────
# A phone (green: Android) standing in front of a laptop whose screen shows the
# desktop: a taskbar, and a window with the phone's green inside it — the
# phone's apps, in windows, on the computer.

S = 0.94  # breathing room inside the tile
CX = CY = 512


def T(x, y):
    return (CX + (x - CX) * S, CY + (y - CY) * S)


def L(v):
    return v * S


TILE_TOP, TILE_BOTTOM = "#1e2a47", "#0a0f1d"

GRADIENTS = {
    "mint": ((0, 380), (0, 720), [(0, "#86f5c0"), (1, "#22b573")]),
    "blue": ((350, 300), (840, 660), [(0, "#86c8ff"), (0.5, "#4d9fff"), (1, "#2b58e8")]),
    "lid": ((0, 300), (0, 724), [(0, "#1a2338"), (1, "#0b111f")]),
    "base": ((0, 688), (0, 724), [(0, "#e9eef6"), (1, "#aeb9cb")]),
    "win": ((0, 372), (0, 578), [(0, "#ffffff"), (1, "#e2e9f4")]),
    "phonebody": ((0, 360), (0, 724), [(0, "#1a2338"), (1, "#0b111f")]),
    # The tile's own gradient, laid over the tile exactly: the gap around the
    # phone is painted with it, so the gap shows only where it cuts the laptop.
    "tile": (None, None, [(0, TILE_TOP), (1, TILE_BOTTOM)]),
}


def rr(x, y, w, h, r):
    """A rounded rectangle as path data, in scene coordinates (transformed)."""
    (x, y), w, h, r = T(x, y), L(w), L(h), L(r)
    return (
        f"M{x + r:.2f},{y:.2f}h{w - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f}"
        f"v{h - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f}h{-(w - 2 * r):.2f}"
        f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f}v{-(h - 2 * r):.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f}z"
    )


def rr_top(x, y, w, h, r):
    """Rounded at the top only (a titlebar)."""
    (x, y), w, h, r = T(x, y), L(w), L(h), L(r)
    return (
        f"M{x:.2f},{y + h:.2f}v{-(h - r):.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f}"
        f"h{w - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f}v{h - r:.2f}z"
    )


def rr_bottom(x, y, w, h, r):
    """Rounded at the bottom only (a taskbar, the laptop's base)."""
    (x, y), w, h, r = T(x, y), L(w), L(h), L(r)
    return (
        f"M{x:.2f},{y:.2f}h{w:.2f}v{h - r:.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f}"
        f"h{-(w - 2 * r):.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f}z"
    )


def circle(cx, cy, r):
    (cx, cy), r = T(cx, cy), L(r)
    return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1 0 {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1 0 {-2 * r:.2f},0z"


DX = -11  # centres the visible drawing, phone edge to base edge, in the tile


def X(v):
    return v + DX


# (path, fill, alpha, kind) — fill is a colour or "grad:<id>"; kind "shadow"
# marks the window, which casts a soft shadow where the format allows one.
SHAPES = [
    # the laptop
    (rr(X(330), 300, 522, 388, 38), "grad:lid", 1, None),
    (rr(X(350), 320, 482, 348, 20), "grad:blue", 1, None),
    # a window on its desktop, with the phone's green inside it
    (rr(X(470), 372, 300, 206, 22), "grad:win", 1, "shadow"),
    (rr_top(X(470), 372, 300, 38, 22), "#dfe7f3", 1, None),
    (circle(X(744), 391, 8), "#ff6b6b", 1, None),
    (circle(X(718), 391, 8), "#b8c4d6", 1, None),
    (rr(X(494), 432, 96, 120, 14), "grad:mint", 1, None),
    (rr(X(608), 438, 132, 14, 7), "#0b111f", 0.7, None),
    (rr(X(608), 468, 104, 11, 5.5), "#0b111f", 0.22, None),
    (rr(X(608), 492, 118, 11, 5.5), "#0b111f", 0.22, None),
    # its taskbar
    (rr_bottom(X(350), 622, 482, 46, 20), "#07112c", 0.5, None),
    (rr(X(546), 633, 24, 24, 8), "#ffffff", 1, None),
    (rr(X(580), 633, 24, 24, 8), "#ffffff", 0.5, None),
    (rr(X(614), 633, 24, 24, 8), "#86f5c0", 1, None),
    # the base
    (rr_bottom(X(292), 688, 598, 36, 28), "grad:base", 1, None),
    (rr(X(546), 688, 90, 10, 5), "#0b111f", 0.18, None),
    # the phone, in front, with a gap of tile around it
    (rr(X(132), 336, 244, 412, 74), "grad:tile", 1, None),
    (rr(X(156), 360, 196, 364, 50), "grad:phonebody", 1, None),
    (rr(X(170), 374, 168, 336, 37), "grad:mint", 1, None),
    (circle(X(254), 398, 8), "#0b111f", 0.75, None),
]
# the rims that catch the light, drawn as strokes
RIMS = [
    (rr(X(331.5), 301.5, 519, 385, 36.5), 3),
    (rr(X(157.5), 361.5, 193, 361, 48.5), 3),
]


def grad_coords(gid):
    if gid == "tile":
        # in the canvas, not the scene: the tile is not scaled with the drawing
        return (CX, 100), (CX, 924), GRADIENTS[gid][2]
    (x1, y1), (x2, y2), stops = GRADIENTS[gid]
    # x of a vertical gradient is irrelevant; keep it on the scene's centre
    p1 = T(X(x1) if x1 else CX, y1)
    p2 = T(X(x2) if x2 else CX, y2)
    return p1, p2, stops


# ── SVG ───────────────────────────────────────────────────────────────────────


def svg(kind):
    """kind: "macos" (tile inset with a shadow), "tile" (full bleed), "flat" (no shadows, for UI)."""
    defs = []
    for gid in GRADIENTS:
        (x1, y1), (x2, y2), stops = grad_coords(gid)
        st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
        defs.append(
            f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}">{st}</linearGradient>'
        )
    defs.append(
        f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{TILE_TOP}"/><stop offset="1" stop-color="{TILE_BOTTOM}"/></linearGradient>'
    )
    defs.append(
        '<linearGradient id="rim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.2"/><stop offset="0.5" stop-color="#fff" stop-opacity="0"/></linearGradient>'
    )
    if kind != "flat":
        defs.append(
            '<filter id="wsh" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="9" stdDeviation="11" flood-color="#06123a" flood-opacity="0.45"/></filter>'
        )
    if kind == "macos":
        defs.append(
            '<filter id="shadow" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="12" stdDeviation="14" flood-color="#000" flood-opacity="0.3"/></filter>'
        )
    body = []
    if kind == "macos":
        body.append('<rect x="100" y="100" width="824" height="824" rx="185" fill="url(#bg)" filter="url(#shadow)"/>')
        body.append('<rect x="101" y="101" width="822" height="822" rx="184" fill="none" stroke="url(#rim)" stroke-width="2"/>')
    else:
        body.append('<rect x="100" y="100" width="824" height="824" rx="185" fill="url(#bg)"/>')
        body.append('<rect x="101" y="101" width="822" height="822" rx="184" fill="none" stroke="url(#rim)" stroke-width="2"/>')
    for d, fill, alpha, extra in SHAPES:
        f = f"url(#{fill[5:]})" if fill.startswith("grad:") else fill
        a = f' fill-opacity="{alpha}"' if alpha != 1 else ""
        flt = ' filter="url(#wsh)"' if extra == "shadow" and kind != "flat" else ""
        body.append(f'<path d="{d}" fill="{f}"{a}{flt}/>')
    for d, w in RIMS:
        body.append(f'<path d="{d}" fill="none" stroke="#fff" stroke-opacity="0.2" stroke-width="{w * S:.2f}"/>')
    inner = "\n  ".join(body)
    view = "0 0 1024 1024" if kind == "macos" else "100 100 824 824"
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="1024" height="1024">\n  <defs>{"".join(defs)}</defs>\n  {inner}\n</svg>\n'


for kind, name in (("macos", "logo-macos.svg"), ("tile", "logo.svg"), ("flat", "logo-flat.svg")):
    open(os.path.join(OUT, name), "w").write(svg(kind))

# ── Android VectorDrawables (108×108 adaptive icon) ───────────────────────────
# The scene's bounds — the phone's gap on the left, the base on the right,
# the lid's top and the gap's bottom — to fit it into the 66dp safe zone.
xmin, xmax = T(X(132), 0)[0], T(X(890), 0)[0]
ymin, ymax = T(0, 300)[1], T(0, 748)[1]
w, h = xmax - xmin, ymax - ymin
# Fit the bounding box's diagonal inside a 66dp circle: every launcher mask
# (circle, squircle, rounded square) contains that circle.
k = 64.0 / (w * w + h * h) ** 0.5
ox = 54 - (xmin + w / 2) * k
oy = 54 - (ymin + h / 2) * k


def vd_color(c, alpha=1.0):
    c = c.lstrip("#")
    a = int(round(alpha * 255))
    return f"#{a:02X}{c.upper()}"


def vd_grad(gid):
    (x1, y1), (x2, y2), stops = grad_coords(gid)
    if gid == "tile":
        # On Android the tile is the background layer, 0..108 down the canvas:
        # the same span, taken back through the group's scale and offset.
        x1 = x2 = CX
        y1, y2 = (0 - oy) / k, (108 - oy) / k
    items = "\n".join(
        f'                <item android:offset="{o}" android:color="{vd_color(c)}" />' for o, c in stops
    )
    return f"""            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="{x1:.2f}"
                    android:startY="{y1:.2f}"
                    android:endX="{x2:.2f}"
                    android:endY="{y2:.2f}">
{items}
                </gradient>
            </aapt:attr>"""


fg = []
for d, fill, alpha, extra in SHAPES:
    if fill.startswith("grad:"):
        fg.append(
            f'        <path android:pathData="{d}">\n{vd_grad(fill[5:])}\n        </path>'
        )
    else:
        fg.append(f'        <path android:fillColor="{vd_color(fill, alpha)}" android:pathData="{d}" />')
for d, sw in RIMS:
    fg.append(
        f'        <path android:strokeColor="#33FFFFFF" android:strokeWidth="{sw * S:.2f}" android:pathData="{d}" />'
    )

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive-icon foreground: the product mark — a phone (green: Android) in
  front of a laptop whose screen shows the desktop, with the phone's green in
  a window on it. The same drawing as the desktop app's icons
  (open-android-dex-tauri/public/logo.svg and src-tauri/icons/*), generated from
  one definition so the two cannot drift. Kept as paths rather than mipmap PNGs:
  one small file instead of five bitmaps, on an APK that is reinstalled on
  every connect, and crisp at any density including the desktop display's
  160dpi.

  The art is drawn in the 1024 canvas of a macOS icon and scaled into the 108
  canvas so the scene's bounding box fits a 64dp circle: every launcher mask
  (circle, squircle, rounded square) keeps the centre 66dp, and the outer 18dp
  on each side is reserved for the launcher's parallax.

  Generated by open-android-dex-tauri/scripts/brand.py: change the mark there.
-->
"""

open(os.path.join(OUT, "ic_launcher_foreground.xml"), "w").write(
    HEADER
    + f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <group
        android:translateX="{ox:.4f}"
        android:translateY="{oy:.4f}"
        android:scaleX="{k:.6f}"
        android:scaleY="{k:.6f}">

{chr(10).join(fg)}
    </group>
</vector>
"""
)

open(os.path.join(OUT, "ic_launcher_background.xml"), "w").write(
    f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Adaptive-icon background layer: the same deep blue as the desktop app's
     icon tile. Must fill the whole 108x108: a launcher may mask it to a circle,
     a squircle or a rounded square, and any of those can reveal a corner an
     inset shape would have left empty. ic_linux.xml shares it.
     Generated by open-android-dex-tauri/scripts/brand.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient
                android:startX="54"
                android:startY="0"
                android:endX="54"
                android:endY="108"
                android:type="linear">
                <item android:offset="0" android:color="#FF{TILE_TOP[1:].upper()}" />
                <item android:offset="1" android:color="#FF{TILE_BOTTOM[1:].upper()}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""
)

# Themed icon (Android 13+): one colour, alpha for depth. The launcher tints
# it, so only shapes and their alpha count. The phone moves left of the
# laptop here — a single colour cannot draw the gap that sets it in front.
MDX = -40


def MX(v):
    return v + DX + MDX


mono = [
    # the laptop's lid as a frame, its screen a lighter panel, its base solid
    (rr(X(330), 300, 522, 388, 38) + rr(X(350), 320, 482, 348, 20), 1.0, True),
    (rr(X(350), 320, 482, 348, 20), 0.28, False),
    (rr(X(470), 372, 300, 206, 22), 1.0, False),
    (rr_bottom(X(350), 622, 482, 46, 20), 0.75, False),
    (rr_bottom(X(292), 688, 598, 36, 28), 1.0, False),
    (rr(MX(156), 360, 196, 364, 50) + rr(MX(170), 374, 168, 336, 37), 1.0, True),
    (rr(MX(170), 374, 168, 336, 37), 0.55, False),
]
mono_paths = []
for d, a, evenodd in mono:
    ft = ' android:fillType="evenOdd"' if evenodd else ""
    mono_paths.append(f'        <path android:fillColor="{vd_color("FFFFFF", a)}"{ft} android:pathData="{d}" />')
mxmin = T(MX(156), 0)[0]
mw = xmax - mxmin
mk = 64.0 / (mw * mw + h * h) ** 0.5
mox = 54 - (mxmin + mw / 2) * mk
moy = 54 - (ymin + h / 2) * mk
open(os.path.join(OUT, "ic_launcher_monochrome.xml"), "w").write(
    """<?xml version="1.0" encoding="utf-8"?>
<!--
  Themed-icon (Android 13+) layer: the launcher tints it with the wallpaper's
  colour, so only the shapes and their alpha count. The mark of
  ic_launcher_foreground.xml in one colour — the laptop's frame and base solid,
  its screen and the phone's screen as lighter panels. The phone stands clear of
  the laptop here: in one colour there is no gap to set it in front with.

  Generated by open-android-dex-tauri/scripts/brand.py: change the mark there.
-->
"""
    + f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <group
        android:translateX="{mox:.4f}"
        android:translateY="{moy:.4f}"
        android:scaleX="{mk:.6f}"
        android:scaleY="{mk:.6f}">

{chr(10).join(mono_paths)}
    </group>
</vector>
"""
)
print("wrote", sorted(os.listdir(OUT)))

# ── a preview of the adaptive icon, as launchers mask it ──────────────────────
def android_preview(mask, themed=False):
    clip = (
        '<circle cx="54" cy="54" r="54"/>'
        if mask == "circle"
        else '<rect x="0" y="0" width="108" height="108" rx="30"/>'
    )
    defs = []
    for gid in GRADIENTS:
        (x1, y1), (x2, y2), stops = grad_coords(gid)
        if gid == "tile":
            y1, y2 = (0 - oy) / k, (108 - oy) / k
        st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}">{st}</linearGradient>')
    if themed:
        rule = ' fill-rule="evenodd"'
        paths = "".join(f'<path d="{d}" fill="#d7e3ff" fill-opacity="{a}"{rule if e else ""}/>' for d, a, e in mono)
        g = f'<g transform="translate({mox:.3f} {moy:.3f}) scale({mk:.5f})">{paths}</g>'
        bg = '<rect width="108" height="108" fill="#2b3550"/>'
    else:
        paths = "".join(
            f'<path d="{d}" fill="{("url(#" + f[5:] + ")") if f.startswith("grad:") else f}" fill-opacity="{al}"/>'
            for d, f, al, _ in SHAPES
        ) + "".join(f'<path d="{d}" fill="none" stroke="#fff" stroke-opacity=".2" stroke-width="{sw * S}"/>' for d, sw in RIMS)
        g = f'<g transform="translate({ox:.3f} {oy:.3f}) scale({k:.5f})">{paths}</g>'
        bg = f'<rect width="108" height="108" fill="url(#abg)"/>'
        defs.append(f'<linearGradient id="abg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{TILE_TOP}"/><stop offset="1" stop-color="{TILE_BOTTOM}"/></linearGradient>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="216" height="216"><defs>{"".join(defs)}<clipPath id="m">{clip}</clipPath></defs><g clip-path="url(#m)">{bg}{g}</g></svg>'


for mask in ("circle", "squircle"):
    open(os.path.join(OUT, f"android-{mask}.svg"), "w").write(android_preview(mask))
open(os.path.join(OUT, "android-themed.svg"), "w").write(android_preview("circle", themed=True))
