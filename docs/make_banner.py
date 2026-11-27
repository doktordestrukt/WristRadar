"""Generates docs/images/banner.png (original artwork: gradient, glass radar, title)."""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 420
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "images", "banner.png")
FONTS = r"C:\Windows\Fonts"


def font(name, size):
    for n in name:
        p = os.path.join(FONTS, n)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


img = Image.new("RGB", (W, H))
px = img.load()
stops = [(0.0, (20, 6, 40)), (0.45, (96, 22, 140)), (0.75, (190, 40, 150)), (1.0, (255, 110, 170))]
for x in range(W):
    for y in range(H):
        t = (x / W) * 0.8 + (y / H) * 0.2
        for (a, ca), (b, cb) in zip(stops, stops[1:]):
            if a <= t <= b:
                f = (t - a) / (b - a)
                px[x, y] = tuple(int(ca[i] + (cb[i] - ca[i]) * f) for i in range(3))
                break

layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(layer)
# flowing glass ribbons
for k, alpha in ((0, 46), (1, 30)):
    pts = [(x, H * (0.62 - 0.18 * k) + 60 * math.sin(x / 170 + k * 1.7)) for x in range(-20, W + 40, 20)]
    poly = pts + [(W + 40, H + 40), (-20, H + 40)]
    d.polygon(poly, fill=(255, 255, 255, alpha))
# radar
cx, cy, r = 250, 210, 140
d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 40), outline=(255, 255, 255, 210), width=4)
for rr in (95, 50):
    d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), outline=(255, 255, 255, 170), width=3)
d.pieslice((cx - 95, cy - 95, cx + 95, cy + 95), -90, -30, fill=(255, 255, 255, 90))
d.line((cx, cy, cx + 95 * math.cos(math.radians(-30)), cy + 95 * math.sin(math.radians(-30))), fill=(255, 255, 255, 230), width=4)
for bx, by in ((cx + 55, cy - 40), (cx - 60, cy + 30)):
    d.ellipse((bx - 7, by - 7, bx + 7, by + 7), fill=(255, 255, 255, 255))
glow = layer.filter(ImageFilter.GaussianBlur(6))
img = Image.alpha_composite(Image.alpha_composite(img.convert("RGBA"), glow), layer)

t = ImageDraw.Draw(img)
title = font(["segoeuib.ttf", "arialbd.ttf"], 96)
sub = font(["segoeui.ttf", "arial.ttf"], 30)
small = font(["segoeuisl.ttf", "segoeui.ttf", "arial.ttf"], 24)
t.text((450, 112), "WRISTRADAR", font=title, fill=(255, 255, 255))
t.text((454, 228), "Private watch-to-watch messaging. No internet, no cloud.", font=sub, fill=(255, 235, 248))
t.text((456, 286), "Bluetooth LE  ·  post-quantum encryption  ·  liquid glass  ·  Wear OS", font=small, fill=(255, 215, 240))
img.convert("RGB").save(OUT, optimize=True)
print(OUT)
