#!/usr/bin/env python3
"""Renders the OpenKnights app icon from its source portrait into patches/branding/res/ (needs Pillow).

    python tools/render_icon.py

The patcher builds the committed PNGs into the app, so this only runs when the source or the layout changes.

- openknights_icon.png: the whole portrait, for launchers without adaptive icons (48 dp).
- openknights_icon_foreground.png: the adaptive icon's 108 dp layer. The portrait sits at 90 dp in the middle; the
  rest of the layer is a blurred copy of the portrait. Launchers mask the middle 72 dp (circle, squircle, ...).
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "patches/branding/source/openknights-icon-source.jpg"
OUTPUT = ROOT / "patches/branding/res"

# Android density buckets: folder qualifier and pixels per dp.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48
LAYER_DP = 108
PORTRAIT_DP = 90
BLUR_DP = 6


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=False)


def main() -> None:
    source = Image.open(SOURCE).convert("RGB")
    for name, scale in DENSITIES.items():
        folder = OUTPUT / f"drawable-{name}-v4"
        legacy = round(LEGACY_DP * scale)
        save(source.resize((legacy, legacy), Image.LANCZOS), folder / "openknights_icon.png")
        layer = round(LAYER_DP * scale)
        portrait = round(PORTRAIT_DP * scale)
        canvas = source.resize((layer, layer), Image.LANCZOS).filter(ImageFilter.GaussianBlur(BLUR_DP * scale))
        offset = (layer - portrait) // 2
        canvas.paste(source.resize((portrait, portrait), Image.LANCZOS), (offset, offset))
        save(canvas, folder / "openknights_icon_foreground.png")
        print(f"{name}: icon {legacy} px, foreground {layer} px")


if __name__ == "__main__":
    main()
