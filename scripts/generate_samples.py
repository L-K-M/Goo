#!/usr/bin/env python3
# Regenerate the bundled sample images (app/src/main/assets/samples/).
#
# The samples are PROCEDURAL on purpose: drawn by this script from the
# app's own candy/table palette, so their provenance is this repo and
# their license is the project's (Unlicense) — no third-party photo
# rights to track (AGENTS.md's sample rule). Pure stdlib (zlib/struct),
# no Pillow; 2x supersampling for smooth edges.
#
#   scripts/generate_samples.py
#
# Requirements: python 3.8+. Runtime ~30s.
import math
import os
import struct
import zlib
from array import array

W, H = 1200, 900
SS = 2  # supersample factor
SW, SH = W * SS, H * SS

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "samples")


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def new_buffer():
    return array("f", bytes(4)) * (SW * SH * 3)


def fill_radial(buf, inner, outer, cx=0.5, cy=0.45, rmax=0.75):
    """Radial gradient over the whole canvas (fractional coords)."""
    for y in range(SH):
        v = y / SH
        row = y * SW * 3
        for x in range(SW):
            u = x / SW
            dx = (u - cx) * (W / H)
            dy = v - cy
            t = min(1.0, math.sqrt(dx * dx + dy * dy) / rmax)
            i = row + x * 3
            buf[i] = inner[0] + (outer[0] - inner[0]) * t
            buf[i + 1] = inner[1] + (outer[1] - inner[1]) * t
            buf[i + 2] = inner[2] + (outer[2] - inner[2]) * t


def blend_px(buf, x, y, color, alpha):
    if x < 0 or y < 0 or x >= SW or y >= SH or alpha <= 0.0:
        return
    a = min(1.0, alpha)
    i = (y * SW + x) * 3
    buf[i] += (color[0] - buf[i]) * a
    buf[i + 1] += (color[1] - buf[i + 1]) * a
    buf[i + 2] += (color[2] - buf[i + 2]) * a


def circle(buf, cx, cy, r, color, alpha=1.0, soft=2.0):
    """Alpha-blend a soft-edged circle (pixel coords)."""
    x0, x1 = int(cx - r - soft - 1), int(cx + r + soft + 1)
    y0, y1 = int(cy - r - soft - 1), int(cy + r + soft + 1)
    for y in range(max(0, y0), min(SH, y1)):
        dy = y - cy
        for x in range(max(0, x0), min(SW, x1)):
            d = math.sqrt((x - cx) ** 2 + dy * dy) - r
            a = alpha * max(0.0, min(1.0, 0.5 - d / soft))
            if a > 0.0:
                blend_px(buf, x, y, color, a)


def ellipse(buf, cx, cy, rx, ry, color, alpha=1.0, soft=2.0):
    """Alpha-blend a soft-edged ellipse (pixel coords)."""
    x0, x1 = int(cx - rx - soft - 1), int(cx + rx + soft + 1)
    y0, y1 = int(cy - ry - soft - 1), int(cy + ry + soft + 1)
    for y in range(max(0, y0), min(SH, y1)):
        for x in range(max(0, x0), min(SW, x1)):
            d = math.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2) - 1.0
            a = alpha * max(0.0, min(1.0, 0.5 - d * rx / soft))
            if a > 0.0:
                blend_px(buf, x, y, color, a)


def arc(buf, cx, cy, r, a0, a1, thick, color, alpha=1.0, soft=2.0):
    """Alpha-blend a soft arc (angles in degrees, 0 = +x, CCW-positive)."""
    x0, x1 = int(cx - r - thick - 2), int(cx + r + thick + 2)
    y0, y1 = int(cy - r - thick - 2), int(cy + r + thick + 2)
    for y in range(max(0, y0), min(SH, y1)):
        for x in range(max(0, x0), min(SW, x1)):
            dx, dy = x - cx, y - cy
            dist = math.sqrt(dx * dx + dy * dy)
            ang = math.degrees(math.atan2(dy, dx)) % 360.0
            lo, hi = (a0, a1) if a0 <= a1 else (a0, a1 + 360.0)
            if not (lo <= ang <= hi or lo <= ang + 360.0 <= hi):
                continue
            d = abs(dist - r) - thick
            a = alpha * max(0.0, min(1.0, 0.5 - d / soft))
            if a > 0.0:
                blend_px(buf, x, y, color, a)


def gloss(buf, cx, cy, r):
    """The signature candy highlight: soft white dot, upper left."""
    circle(buf, cx - r * 0.35, cy - r * 0.4, r * 0.28, (1, 1, 1), alpha=0.45, soft=r * 0.28)
    circle(buf, cx - r * 0.42, cy - r * 0.47, r * 0.12, (1, 1, 1), alpha=0.55, soft=r * 0.12)


def write_png(path, buf):
    """Downsample SS-fold and write an 8-bit RGB PNG."""
    raw = bytearray()
    for y in range(H):
        raw.append(0)  # filter: none
        for x in range(W):
            acc = [0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    i = ((y * SS + sy) * SW + (x * SS + sx)) * 3
                    acc[0] += buf[i]
                    acc[1] += buf[i + 1]
                    acc[2] += buf[i + 2]
            n = SS * SS
            raw += bytes(max(0, min(255, round(c / n * 255))) for c in acc)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def goo_guy():
    """A friendly blob face — the app's mascot-to-be, made to be gooed."""
    buf = new_buffer()
    fill_radial(buf, hexrgb("233156"), hexrgb("080A16"))

    cx, cy = SW * 0.5, SH * 0.52
    rx, ry = SW * 0.27, SH * 0.33

    # Shadow on the table, then the head.
    ellipse(buf, cx, cy + ry * 1.02, rx * 0.9, ry * 0.16, hexrgb("04050C"), alpha=0.5, soft=30)
    ellipse(buf, cx, cy, rx, ry, hexrgb("F5C9A0"))
    # Cheeks.
    circle(buf, cx - rx * 0.52, cy + ry * 0.18, rx * 0.16, hexrgb("FF7FB5"), alpha=0.55, soft=18)
    circle(buf, cx + rx * 0.52, cy + ry * 0.18, rx * 0.16, hexrgb("FF7FB5"), alpha=0.55, soft=18)
    # Eyes with glints.
    for sx in (-1, 1):
        ex = cx + sx * rx * 0.38
        ey = cy - ry * 0.12
        circle(buf, ex, ey, rx * 0.11, hexrgb("2B1B14"))
        circle(buf, ex - rx * 0.035, ey - rx * 0.04, rx * 0.035, (1, 1, 1), alpha=0.9)
    # Smile.
    arc(buf, cx, cy + ry * 0.05, rx * 0.42, 25, 155, rx * 0.045, hexrgb("7A3B2E"))
    # Forehead gloss.
    gloss(buf, cx - rx * 0.1, cy - ry * 0.35, rx * 0.8)
    return buf


def candy_blobs():
    """The candy palette as glossy marbles on the goo table."""
    buf = new_buffer()
    fill_radial(buf, hexrgb("1B2447"), hexrgb("06080F"))
    candies = ["FF3D9E", "35E3F2", "A6F03F", "FFDA3D", "B86BFF", "FF9A2E"]
    spots = [(0.22, 0.30, 0.16), (0.52, 0.22, 0.13), (0.78, 0.34, 0.15),
             (0.30, 0.68, 0.14), (0.58, 0.60, 0.17), (0.82, 0.72, 0.12)]
    for (fx, fy, fr), color in zip(spots, candies):
        r = fr * SW
        cx, cy = fx * SW, fy * SH
        circle(buf, cx + r * 0.08, cy + r * 0.55, r * 0.9, hexrgb("04050C"), alpha=0.45, soft=r * 0.3)
        circle(buf, cx, cy, r, hexrgb(color))
        gloss(buf, cx, cy, r)
    return buf


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, painter in (("goo-guy.png", goo_guy), ("candy-blobs.png", candy_blobs)):
        path = os.path.join(OUT_DIR, name)
        write_png(path, painter())
        print(f"wrote {path} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
