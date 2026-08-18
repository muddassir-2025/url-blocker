#!/usr/bin/env python3
"""Regenerate ClearView legacy launcher icons (Android < 8 / API < 26) from
the split-heart identity. The adaptive icon (API 26+) is pure vector; these
PNGs mirror it exactly for older devices:

  - pure white background (square for ic_launcher, circle for ic_launcher_round)
  - one heart silhouette at the same 60/108 (55.6%) canvas size as the
    adaptive foreground, so the icon looks identical on every launcher
  - left half matte black (#000000)  = old / dead / impure side
  - right half deep red (#C62828)    = life, renewal, a living heart
  - clean vertical seam through the heart's center

Run from the repo root:  python scripts/gen_launcher_icons.py
Requires: Pillow
"""

import os
import re

from PIL import Image, ImageDraw

RED = (198, 40, 40, 255)      # #C62828
BLACK = (0, 0, 0, 255)        # #000000
WHITE = (255, 255, 255, 255)  # #FFFFFF

# The heart silhouette (Material "favorite" glyph) in a 24x24 design space.
# Same geometry as app/src/main/res/drawable/ic_launcher_foreground.xml.
HEART = (
    "M12,21.35 l-1.45,-1.32 C5.4,15.36 2,12.28 2,8.5 C2,5.42 4.42,3 7.5,3 "
    "c1.74,0 3.41,0.81 4.5,2.09 C13.09,3.81 14.76,3 16.5,3 C19.58,3 22,5.42 "
    "22,8.5 c0,3.78 -3.4,6.86 -8.55,11.54 L12,21.35z"
)
# Right half of the heart (from the top dip, over the right lobe, down to the
# tip), closed along the vertical center line x=12.
RIGHT = (
    "M12,5.09 C13.09,3.81 14.76,3 16.5,3 C19.58,3 22,5.42 22,8.5 "
    "c0,3.78 -3.4,6.86 -8.55,11.54 L12,21.35z"
)

# Heart width as a fraction of the icon canvas — matches the adaptive icon
# (heart spans x=32..76 in the 108-unit foreground => 44/108). Deliberately
# small and dead center so the mark reads as a small, elegant symbol.
HEART_FRACTION = 44.0 / 108.0

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

BASE = os.path.join("app", "src", "main", "res")

# Supersampling factor for anti-aliased edges.
SS = 4
CURVE_STEPS = 64


def parse(path_data):
    """Split an SVG-ish path (M/l/C/c/L/z) into (command, [values]) groups."""
    tokens = re.findall(r"[MlCcLz]|-?\d+\.?\d*(?:[eE][+-]?\d+)?", path_data)
    ops = []
    i = 0
    while i < len(tokens):
        cmd = tokens[i]
        i += 1
        vals = []
        while i < len(tokens) and tokens[i] not in "MlCcLz":
            vals.append(float(tokens[i]))
            i += 1
        ops.append((cmd, vals))
    return ops


def flatten(path_data):
    """Flatten a path to a list of (x, y) points in the 24-unit design space."""
    pts = []
    cur = [0.0, 0.0]
    start = [0.0, 0.0]
    for cmd, v in parse(path_data):
        if cmd == "M":
            cur = [v[0], v[1]]
            start = list(cur)
            pts.append(tuple(cur))
        elif cmd == "l":
            cur = [cur[0] + v[0], cur[1] + v[1]]
            pts.append(tuple(cur))
        elif cmd == "L":
            cur = [v[0], v[1]]
            pts.append(tuple(cur))
        elif cmd == "C":
            p0 = cur
            x1, y1, x2, y2, x3, y3 = v
            for k in range(1, CURVE_STEPS + 1):
                u = k / CURVE_STEPS
                w = 1.0 - u
                pts.append((
                    w ** 3 * p0[0] + 3 * w * w * u * x1 + 3 * w * u * u * x2 + u ** 3 * x3,
                    w ** 3 * p0[1] + 3 * w * w * u * y1 + 3 * w * u * u * y2 + u ** 3 * y3,
                ))
            cur = [x3, y3]
        elif cmd == "c":
            p0 = cur
            dx1, dy1, dx2, dy2, dx3, dy3 = v
            x1, y1 = p0[0] + dx1, p0[1] + dy1
            x2, y2 = p0[0] + dx2, p0[1] + dy2
            x3, y3 = p0[0] + dx3, p0[1] + dy3
            for k in range(1, CURVE_STEPS + 1):
                u = k / CURVE_STEPS
                w = 1.0 - u
                pts.append((
                    w ** 3 * p0[0] + 3 * w * w * u * x1 + 3 * w * u * u * x2 + u ** 3 * x3,
                    w ** 3 * p0[1] + 3 * w * w * u * y1 + 3 * w * u * u * y2 + u ** 3 * y3,
                ))
            cur = [x3, y3]
        elif cmd == "z":
            pts.append(tuple(start))
            cur = list(start)
    return pts


def render(size, round_icon):
    """Render one icon at `size` px (4x supersampled, then downscaled)."""
    scale = HEART_FRACTION * size / 20.0  # heart is 20 units wide in 24-space
    ox = size / 2.0 - scale * 12.0
    oy = size / 2.0 - scale * 12.175

    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if round_icon:
        draw.ellipse([0, 0, big - 1, big - 1], fill=WHITE)
    else:
        draw.rectangle([0, 0, big - 1, big - 1], fill=WHITE)

    def to_px(pts):
        return [
            ((x * scale + ox) * SS, (y * scale + oy) * SS)
            for x, y in pts
        ]

    draw.polygon(to_px(flatten(HEART)), fill=BLACK)
    draw.polygon(to_px(flatten(RIGHT)), fill=RED)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for dpi, size in SIZES.items():
        folder = os.path.join(BASE, "mipmap-" + dpi)
        os.makedirs(folder, exist_ok=True)
        for name, round_icon in (("ic_launcher", False), ("ic_launcher_round", True)):
            path = os.path.join(folder, name + ".png")
            render(size, round_icon).save(path, "PNG")
            print("wrote %s (%dpx)" % (path, size))


if __name__ == "__main__":
    main()
