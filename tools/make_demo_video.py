"""Renders the demo clip the app plays while it is open (app/src/main/res/raw/demo.mp4).

Each part shows one thing ambilight has to get right:
   0-5 s  sunset, colours drifting slowly         smooth following
   5-8 s  fade to black, black                    soft fade, true off
   8 s    hard cut to a bright sea                cut prediction
   8-12 s colours rotating round the edges        zones follow their own edge
  12-15 s small bright balls along the edges      multi-point sampling keeps the zone steady
  15-18 s hard cuts red / green / blue            fast cuts, no stair steps
  18-24 s fade up to warm white, to violet, back  slow fades, colour change through bright light

Usage: python3 -I tools/make_demo_video.py <empty frame dir>, then the gst-launch line printed at the end
(needs Pillow and GStreamer with x264enc and mp4mux).
"""
import colorsys
import math
import os
import sys

from PIL import Image, ImageDraw

W, H, FPS, SECONDS = 480, 270, 30, 24


def lerp(a, b, f):
    return tuple(round(x + (y - x) * f) for x, y in zip(a, b))


def smooth(f):
    f = min(1.0, max(0.0, f))
    return f * f * (3 - 2 * f)


def grid(fn, gw=16, gh=9):
    """A small colour grid fn(x, y) in 0..1, scaled up smoothly to the frame."""
    small = Image.new("RGB", (gw, gh))
    small.putdata([fn((i + 0.5) / gw, (j + 0.5) / gh) for j in range(gh) for i in range(gw)])
    return small.resize((W, H), Image.BICUBIC)


def sunset(t):
    drift = 0.5 + 0.5 * math.sin(t * 0.9)

    def fn(x, y):
        if y < 0.55:
            top = lerp((43, 30, 79), (70, 40, 110), drift)
            bottom = lerp((243, 154, 76), (232, 96, 120), x * 0.6 + drift * 0.4)
            return lerp(top, bottom, smooth(y / 0.55))
        return lerp((20, 90, 112), (11, 46, 68), (y - 0.55) / 0.45)

    img = grid(fn)
    d = ImageDraw.Draw(img)
    cx = W * (0.35 + 0.1 * math.sin(t * 0.5))
    d.ellipse([cx - 34, H * 0.30, cx + 34, H * 0.30 + 68], fill=(255, 208, 138))
    return img


def hue_wheel(t):
    def fn(x, y):
        a = math.atan2(y - 0.5, (x - 0.5) * 16 / 9) / (2 * math.pi) + t * 0.25
        r, g, b = colorsys.hsv_to_rgb(a % 1.0, 0.85, 0.95)
        return round(r * 255), round(g * 255), round(b * 255)

    return grid(fn)


def balls(t):
    img = Image.new("RGB", (W, H), (32, 36, 40))
    d = ImageDraw.Draw(img)
    x = (t / 3.0) * (W + 40) - 20
    d.ellipse([x - 12, 8, x + 12, 32], fill=(255, 255, 255))
    d.ellipse([W - x - 12, H - 32, W - x + 12, H - 8], fill=(255, 214, 90))
    return img


def flat(c):
    return Image.new("RGB", (W, H), c)


def frame(t):
    if t < 5:
        return sunset(t)
    if t < 7:
        return Image.blend(sunset(t), flat((0, 0, 0)), smooth((t - 5) / 2))
    if t < 8:
        return flat((0, 0, 0))
    if t < 12:
        sea = grid(lambda x, y: lerp((40, 200, 230), (10, 90, 170), y))
        return Image.blend(sea, hue_wheel(t - 9), smooth(t - 9)) if t >= 9 else sea
    if t < 15:
        return balls(t - 12)
    if t < 18:
        return flat([(220, 30, 30), (30, 200, 60), (30, 60, 230)][int(t - 15)])
    if t < 20:
        return flat(lerp((0, 0, 0), (255, 226, 184), smooth((t - 18) / 2)))
    if t < 22:
        return flat(lerp((255, 226, 184), (122, 58, 255), smooth((t - 20) / 2)))
    return Image.blend(flat((122, 58, 255)), sunset(0), smooth((t - 22) / 2))


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for i in range(FPS * SECONDS):
        frame(i / FPS).save(os.path.join(out, f"f_{i:04d}.png"))
    print(
        f"gst-launch-1.0 -q multifilesrc location={out}/f_%04d.png index=0 caps=image/png,framerate={FPS}/1 "
        "! pngdec ! videoconvert ! video/x-raw,format=I420 ! x264enc bitrate=600 speed-preset=slow "
        "key-int-max=60 ! video/x-h264,profile=main ! mp4mux ! filesink location=app/src/main/res/raw/demo.mp4"
    )


if __name__ == "__main__":
    main()
