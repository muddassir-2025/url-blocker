#!/usr/bin/env python3
"""Generate the Google Play store-listing graphics for ClearView:

  - play-assets/icon_512.png        — 512x512 high-res app icon (pure white
                                      background + the split-heart mark at the
                                      same 44/108 size as the launcher icon)
  - play-assets/feature_graphic.png — 1024x500 feature graphic (pure white,
                                      the split heart centered and scaled up)

Reuses the EXACT heart geometry from gen_launcher_icons.py (same 24-unit
Material heart path, same black-left / deep-red-right split), so the store
assets match the app icon pixel-for-pixel.

Run from the repo root:  python scripts/gen_play_assets.py
Requires: Pillow
"""

import os

from PIL import Image, ImageDraw

from gen_launcher_icons import BLACK, HEART, RED, RIGHT, WHITE, flatten

# Supersampling factor for anti-aliased edges.
SS = 4


def mark_points(scale, ox, oy, ss):
    """Flattened, scaled, translated points for the full heart + right half."""
    def to_px(pts):
        return [((x * scale + ox) * ss, (y * scale + oy) * ss) for x, y in pts]
    return to_px(flatten(HEART)), to_px(flatten(RIGHT))


def render_icon(size):
    """512x512 store icon — heart at 44/108 of the canvas, dead center."""
    scale = (44.0 / 108.0) * size / 20.0
    ox = size / 2.0 - scale * 12.0
    oy = size / 2.0 - scale * 12.175
    big = size * SS
    img = Image.new("RGBA", (big, big), WHITE)
    draw = ImageDraw.Draw(img)
    heart, right = mark_points(scale, ox, oy, SS)
    draw.polygon(heart, fill=BLACK)
    draw.polygon(right, fill=RED)
    return img.resize((size, size), Image.LANCZOS)


def render_feature_graphic(w, h):
    """1024x500 feature graphic — heart ~240px wide, vertically centered."""
    heart_px = 240.0
    scale = heart_px / 20.0
    ox = w / 2.0 - scale * 12.0
    oy = h / 2.0 - scale * 12.175
    big = (w * SS, h * SS)
    img = Image.new("RGBA", big, WHITE)
    draw = ImageDraw.Draw(img)
    heart, right = mark_points(scale, ox, oy, SS)
    draw.polygon(heart, fill=BLACK)
    draw.polygon(right, fill=RED)
    return img.resize((w, h), Image.LANCZOS)


def main():
    out = os.path.join("play-assets")
    os.makedirs(out, exist_ok=True)
    icon = os.path.join(out, "icon_512.png")
    render_icon(512).save(icon, "PNG")
    print("wrote", icon)
    feature = os.path.join(out, "feature_graphic.png")
    render_feature_graphic(1024, 500).save(feature, "PNG")
    print("wrote", feature)


if __name__ == "__main__":
    main()
