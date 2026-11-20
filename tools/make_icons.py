#!/usr/bin/env python3
"""Generate launcher icons (PNG) for MusicClip Visualizer.

Design: rounded-square gradient (deep purple -> electric cyan) with white
spectrum bars.  Purely procedural -> no licensing issues.

Usage:
    python3 tools/make_icons.py
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

OUT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
)
SIZES = {  # mipmap density -> px
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
TOP = (124, 77, 255)     # #7C4DFF deep purple
BOTTOM = (0, 229, 255)   # #00E5FF electric cyan


def make_icon(size: int) -> Image.Image:
    s = 4
    big = size * s
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # vertical gradient inside rounded rect
    grad = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(grad)
    for y in range(big):
        t = y / (big - 1)
        r = int(TOP[0] + (BOTTOM[0] - TOP[0]) * t)
        g = int(TOP[1] + (BOTTOM[1] - TOP[1]) * t)
        b = int(TOP[2] + (BOTTOM[2] - TOP[2]) * t)
        gdraw.line([(0, y), (big, y)], fill=(r, g, b, 255))
    mask = Image.new("L", (big, big), 0)
    mdraw = ImageDraw.Draw(mask)
    radius = int(big * 0.20)
    mdraw.rounded_rectangle([0, 0, big - 1, big - 1], radius=radius, fill=255)
    img.paste(grad, (0, 0), mask)

    # soft glow behind bars
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gld = ImageDraw.Draw(glow)
    heights = [0.34, 0.58, 0.92, 0.70, 0.46, 0.80]
    bar_w = int(big * 0.075)
    gap = int(big * 0.045)
    total_w = len(heights) * bar_w + (len(heights) - 1) * gap
    x0 = (big - total_w) // 2
    base_y = int(big * 0.74)
    max_h = int(big * 0.44)
    for i, h in enumerate(heights):
        bh = int(max_h * h)
        x = x0 + i * (bar_w + gap)
        gld.rounded_rectangle(
            [x, base_y - bh, x + bar_w, base_y], radius=bar_w // 2, fill=(255, 255, 255, 120)
        )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=big * 0.03))
    img = Image.alpha_composite(img, glow)

    # crisp bars
    draw = ImageDraw.Draw(img)
    for i, h in enumerate(heights):
        bh = int(max_h * h)
        x = x0 + i * (bar_w + gap)
        draw.rounded_rectangle(
            [x, base_y - bh, x + bar_w, base_y], radius=bar_w // 2, fill=(255, 255, 255, 255)
        )

    # small "play" triangle accent top-left
    tx, ty = int(big * 0.18), int(big * 0.20)
    ts = int(big * 0.13)
    draw.polygon([(tx, ty), (tx, ty + ts), (tx + int(ts * 0.86), ty + ts // 2)], fill=(255, 255, 255, 235))

    return img.resize((size, size), Image.LANCZOS)


def main():
    print("Generating launcher icons ...")
    for folder, size in SIZES.items():
        d = os.path.join(OUT_DIR, folder)
        os.makedirs(d, exist_ok=True)
        icon = make_icon(size)
        path = os.path.join(d, "ic_launcher.png")
        icon.save(path, "PNG")
        print(f"  wrote {path} ({size}x{size})")
    print("Done.")


if __name__ == "__main__":
    sys.exit(main())
