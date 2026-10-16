#!/usr/bin/env python3
"""Writes port/res/dbt3.ico, the desktop program's icon, from the Android one.

The phone's icon is an adaptive icon: a background (res/drawable/ic_launcher_background.xml, a gradient) with the
artwork on top (res/mipmap-*/ic_dbt3_fg.png), sized so the artwork sits in the middle 72 of the 108 the launcher's
mask is cut from. Windows has no such mask and no vector backgrounds, so the same picture is painted here at a
square size: the gradient, then the artwork at the size and place the phone shows.

Kept as a tool rather than a file edited by hand because the two icons must not drift apart: run this after the
phone's icon changes, and commit the result.

    python3 port/tools/make_icon.py     -> port/res/dbt3.ico
"""
import pathlib
import sys

from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from toolchain import ROOT

RES = ROOT / "port/res"
ICO = RES / "dbt3.ico"
SIZES = (16, 24, 32, 48, 64, 128, 256)
GRADIENT_FROM, GRADIENT_TO = (0x1E, 0x3A, 0x8A), (0x0B, 0x0E, 0x1E)  # ic_launcher_background.xml
ART = 72  # the artwork is 72 of the 108 the mask is cut from, centred


def background(size):
    """The gradient, drawn corner to corner as the vector drawable has it."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    last = size - 1
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * last) if last else 0.0
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(GRADIENT_FROM, GRADIENT_TO))
    return img


def main():
    fg = ROOT / "android/app/src/main/res/mipmap-xxxhdpi/ic_dbt3_fg.png"
    if not fg.exists():
        print(f"{fg} is not there: nothing to copy the icon from")
        return 1
    art = Image.open(fg).convert("RGBA")
    frames = []
    for size in SIZES:
        # the phone's 108 canvas at this size, so the artwork lands where the launcher's mask would show it
        img = background(size)
        side = round(size * ART / 108)
        img.paste(art.resize((side, side), Image.LANCZOS), ((size - side) // 2, (size - side) // 2), art.resize((side, side), Image.LANCZOS))
        frames.append(img)
    RES.mkdir(parents=True, exist_ok=True)
    frames[-1].save(ICO, sizes=[(s, s) for s in SIZES])
    print(f"{ICO.relative_to(ROOT)}: {ICO.stat().st_size} bytes, {len(SIZES)} sizes")
    return 0


if __name__ == "__main__":
    sys.exit(main())