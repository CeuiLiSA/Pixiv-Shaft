#!/usr/bin/env python3
"""
Generate the SVG assets used by README.md / README/README.zh-CN.md.

The README borrows the look of https://pixshaft.com (dark "ink" background,
brand purple → cyan aurora, glass pills) and its real-device screenshots.
Everything is rendered as self-contained SVG so GitHub can show it as a plain
<img> — no CSS, no fonts, no external requests (screenshots are inlined as
data URIs).

Inputs : snap/pixshaft/screens/*.webp   (512×1138 real-device screenshots)
         snap/pixshaft/shaft-logo.png     (the app mark, from pixshaft.com)
         snap/pixshaft/device/pixel_8/    (Google's Pixel 8 device-art frame, copied
                                           from Android Studio's device-art-resources;
                                           back.webp = bezel, mask.webp = screen corners
                                           + punch-hole, layout = display offset)
Outputs: snap/pixshaft/frames/<name>.webp (each screenshot composited into the Pixel 8)
         snap/pixshaft/hero-{en,zh}.svg
         snap/pixshaft/capabilities-{en,zh}.svg
         snap/pixshaft/tech-{en,zh}.svg
         snap/pixshaft/cta-{en,zh}.svg

Usage: python3 scripts/build_readme_assets.py
"""
from __future__ import annotations

import base64
import html
import io
import re
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "snap" / "pixshaft"
SCREENS = ASSETS / "screens"
FRAMES = ASSETS / "frames"
DEVICE = ASSETS / "device" / "pixel_8"

# ---- theme (tokens lifted from pixshaft.com's stylesheet) -------------------
INK = "#07060f"
INK2 = "#0c0a18"
GLASS = "#14122a"
BRAND = "#7c6cff"
BRAND2 = "#5b6ee1"
GLOW = "#a78bfa"
ACCENT = "#22d3ee"
PINK = "#f6339a"
FONT = ("Inter, Sora, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, "
        "'PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', "
        "system-ui, sans-serif")

def esc(s: str) -> str:
    return html.escape(s, quote=True)


def data_uri(path: Path) -> str:
    return "data:image/webp;base64," + base64.b64encode(path.read_bytes()).decode()


def _device_layout() -> tuple[int, int, int, int]:
    """(display_w, display_h, offset_x, offset_y) from the device-art `layout` file."""
    txt = (DEVICE / "layout").read_text()
    dw, dh = (int(v) for v in re.search(r"display \{\s*width (\d+)\s*height (\d+)", txt).groups())
    ox, oy = (int(v) for v in re.search(r"name device\s*x (\d+)\s*y (\d+)", txt).groups())
    return dw, dh, ox, oy


def frame_in_device(shot: Path) -> Image.Image:
    """Composite a screenshot into the Pixel 8 device art, at screenshot resolution
    (the frame is scaled down to the shot, never the other way round)."""
    dw, dh, ox, oy = _device_layout()
    back = Image.open(DEVICE / "back.webp").convert("RGBA")
    mask = Image.open(DEVICE / "mask.webp").convert("RGBA")
    img = Image.open(shot).convert("RGBA")
    k = img.width / dw                      # frame → shot scale
    fw, fh = round(back.width * k), round(back.height * k)
    sx, sy = round(ox * k), round(oy * k)
    sh = round(dh * k)
    screen = img.resize((img.width, sh), Image.LANCZOS) if img.height != sh else img
    canvas = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    canvas.alpha_composite(screen, (sx, sy))
    canvas.alpha_composite(mask.resize((screen.width, sh), Image.LANCZOS), (sx, sy))
    canvas.alpha_composite(back.resize((fw, fh), Image.LANCZOS))
    return canvas


def image_data_uri(im: Image.Image) -> str:
    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=88, method=6)
    return "data:image/webp;base64," + base64.b64encode(buf.getvalue()).decode()


def text_width(s: str, size: float, weight: str = "normal") -> float:
    """Rough text measurement good enough for pill layout (CJK ≈ 1em, latin ≈ .56em)."""
    w = 0.0
    for ch in s:
        if ord(ch) > 0x2E7F:
            w += 0.98
        elif ch in " ·":
            w += 0.28
        elif ch.isupper() or ch.isdigit():
            w += 0.62
        else:
            w += 0.53
    if weight in ("600", "700", "800", "bold"):
        w *= 1.04
    return w * size


# ---- shared defs -------------------------------------------------------------
def defs(uid: str) -> str:
    return f"""
  <defs>
    <linearGradient id="{uid}-brand" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{BRAND}"/><stop offset="1" stop-color="{BRAND2}"/>
    </linearGradient>
    <linearGradient id="{uid}-text" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#ffffff"/><stop offset=".55" stop-color="#d9d4ff"/><stop offset="1" stop-color="{ACCENT}"/>
    </linearGradient>
    <radialGradient id="{uid}-a1" cx=".5" cy=".5" r=".5">
      <stop offset="0" stop-color="{BRAND}" stop-opacity=".75"/><stop offset="1" stop-color="{BRAND}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="{uid}-a2" cx=".5" cy=".5" r=".5">
      <stop offset="0" stop-color="{ACCENT}" stop-opacity=".45"/><stop offset="1" stop-color="{ACCENT}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="{uid}-a3" cx=".5" cy=".5" r=".5">
      <stop offset="0" stop-color="{PINK}" stop-opacity=".35"/><stop offset="1" stop-color="{PINK}" stop-opacity="0"/>
    </radialGradient>
    <filter id="{uid}-blur" x="-50%" y="-50%" width="200%" height="200%">
      <feGaussianBlur stdDeviation="60"/>
    </filter>
    <filter id="{uid}-soft" x="-50%" y="-50%" width="200%" height="200%">
      <feGaussianBlur stdDeviation="22"/>
    </filter>
    <filter id="{uid}-grain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" stitchTiles="stitch"/>
      <feColorMatrix type="saturate" values="0"/>
      <feComponentTransfer><feFuncA type="linear" slope=".07"/></feComponentTransfer>
    </filter>
  </defs>"""


def ink_background(uid: str, w: int, h: int, rx: int = 28, aurora=True) -> str:
    out = [f'<clipPath id="{uid}-clip"><rect width="{w}" height="{h}" rx="{rx}"/></clipPath>',
           f'<rect width="{w}" height="{h}" rx="{rx}" fill="{INK}"/>']
    if aurora:
        out.append(f'<g clip-path="url(#{uid}-clip)" filter="url(#{uid}-blur)">'
                   f'<circle cx="{int(w*0.18)}" cy="{int(h*0.15)}" r="{int(min(w,h)*0.55)}" fill="url(#{uid}-a1)"/>'
                   f'<circle cx="{int(w*0.82)}" cy="{int(h*0.9)}" r="{int(min(w,h)*0.6)}" fill="url(#{uid}-a2)"/>'
                   f'<circle cx="{int(w*0.62)}" cy="{int(h*0.1)}" r="{int(min(w,h)*0.45)}" fill="url(#{uid}-a3)"/>'
                   f'</g>')
    out.append(f'<rect width="{w}" height="{h}" rx="{rx}" filter="url(#{uid}-grain)" opacity=".6"/>')
    out.append(f'<rect x=".5" y=".5" width="{w-1}" height="{h-1}" rx="{rx}" fill="none" stroke="#ffffff" stroke-opacity=".10"/>')
    return "\n".join(out)


def logo(x: float, y: float, size: float) -> str:
    """The real Shaft mark (snap/pixshaft/shaft-logo.png, from pixshaft.com), inlined and rounded."""
    png = base64.b64encode((ASSETS / "shaft-logo.png").read_bytes()).decode()
    r = size * 0.22
    return (f'<clipPath id="logo-clip"><rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{r:.1f}"/></clipPath>'
            f'<image x="{x}" y="{y}" width="{size}" height="{size}" clip-path="url(#logo-clip)" '
            f'href="data:image/png;base64,{png}"/>')


def pill(x: float, y: float, label: str, *, size=14, h=36, fill="#ffffff", fill_op=".06",
         stroke_op=".14", color="#ffffff", color_op=".88", weight="600", dot: str | None = None,
         gradient: str | None = None, pad=18) -> tuple[str, float]:
    tw = text_width(label, size, weight)
    dot_w = 16 if dot else 0
    w = pad * 2 + tw + dot_w
    r = h / 2
    if gradient:
        rect = f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="{r}" fill="url(#{gradient})"/>'
    else:
        rect = (f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="{r}" fill="{fill}" fill-opacity="{fill_op}" '
                f'stroke="#ffffff" stroke-opacity="{stroke_op}"/>')
    parts = [rect]
    tx = x + pad
    if dot:
        parts.append(f'<circle cx="{x+pad+4:.1f}" cy="{y+h/2:.1f}" r="4" fill="{dot}"/>')
        tx += dot_w
    parts.append(f'<text x="{tx:.1f}" y="{y + h/2 + size*0.36:.1f}" font-family="{FONT}" font-size="{size}" '
                 f'font-weight="{weight}" fill="{color}" fill-opacity="{color_op}">{esc(label)}</text>')
    return "\n".join(parts), w


def svg(w: int, h: int, body: str, title: str) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{esc(title)}">\n'
            f'<title>{esc(title)}</title>\n{body}\n</svg>\n')


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    print(f"  wrote {path.relative_to(ROOT)}  ({len(content.encode())//1024} KB)")


# ---- 1. framed screenshots ---------------------------------------------------
def build_frames() -> None:
    FRAMES.mkdir(parents=True, exist_ok=True)
    for src in sorted(SCREENS.glob("*.webp")):
        out = FRAMES / f"{src.stem}.webp"
        frame_in_device(src).save(out, "WEBP", quality=88, method=6)
        print(f"  wrote {out.relative_to(ROOT)}  ({out.stat().st_size//1024} KB)")


# ---- 2. hero ------------------------------------------------------------------
HERO = {
    "en": dict(
        eyebrow="Open source · Material You · No ads",
        h1=("The whole of Pixiv,", "in your pocket."),
        sub=("An open-source third-party Pixiv client for Android —",
             "illustrations · manga · novels · rankings · FANBOX · pixiv COMIC.",
             "Fluid, Material You, and it doesn't feel third-party at all."),
        btn1="Get it on Google Play", btn2="View on GitHub",
        foot="Android 7.0+  ·  direct connection in mainland China  ·  free & open source",
    ),
    "zh": dict(
        eyebrow="开源 · Material You · 无广告",
        h1=("把整个 Pixiv", "装进口袋"),
        sub=("PixShaft 是一个开源的 Pixiv 第三方安卓客户端：",
             "插画 · 漫画 · 小说 · 排行榜 · FANBOX · pixiv COMIC，",
             "流畅、Material You —— 能想到的体验都在这里。"),
        btn1="Google Play 下载", btn2="在 GitHub 查看",
        foot="支持 Android 7.0+  ·  中国大陆可直连  ·  开源无广告",
    ),
}


def build_hero(lang: str) -> None:
    t = HERO[lang]
    uid = "h"
    W, H = 1200, 600
    b = [defs(uid), ink_background(uid, W, H)]
    # left column
    b.append(logo(64, 64, 56))
    b.append(f'<text x="136" y="104" font-family="{FONT}" font-size="30" font-weight="800" fill="#ffffff">Shaft</text>')
    p, _ = pill(64, 150, t["eyebrow"], size=13, h=32, dot=ACCENT, pad=16)
    b.append(p)
    h1_size = 58 if lang == "en" else 62
    b.append(f'<text font-family="{FONT}" font-size="{h1_size}" font-weight="800" fill="url(#{uid}-text)" letter-spacing="-1">'
             f'<tspan x="64" y="268">{esc(t["h1"][0])}</tspan><tspan x="64" y="{268 + h1_size*1.12:.0f}">{esc(t["h1"][1])}</tspan></text>')
    sub_lines = "".join(f'<tspan x="64" y="{392 + i*27}">{esc(line)}</tspan>' for i, line in enumerate(t["sub"]))
    b.append(f'<text font-family="{FONT}" font-size="17" fill="#ffffff" fill-opacity=".72">{sub_lines}</text>')
    p1, w1 = pill(64, 478, t["btn1"], size=15, h=46, gradient=f"{uid}-brand", color="#ffffff", color_op="1", pad=24)
    p2, _ = pill(64 + w1 + 14, 478, t["btn2"], size=15, h=46, fill_op=".08", stroke_op=".22", pad=24)
    b.append(p1); b.append(p2)
    b.append(f'<text x="64" y="558" font-family="{FONT}" font-size="13" fill="#ffffff" fill-opacity=".5">{esc(t["foot"])}</text>')
    # right: three Pixel 8 frames, clipped to the card; glow behind the middle one
    b.append(f'<g clip-path="url(#{uid}-clip)">')
    shots = [("gallery", 716, 128, -7), ("home", 964, 118, 7), ("detail", 840, 58, 0)]
    for name, x, y, rot in shots:
        framed = frame_in_device(SCREENS / f"{name}.webp")
        w = 224
        h = w * framed.height / framed.width      # aspect comes from the device art, not a constant
        cx, cy = x + w / 2, y + h / 2
        if rot == 0:
            b.append(f'<ellipse cx="{cx:.1f}" cy="{cy:.1f}" rx="{w*0.7:.1f}" ry="{h*0.5:.1f}" '
                     f'fill="url(#{uid}-brand)" opacity=".55" filter="url(#{uid}-soft)"/>')
        b.append(f'<image x="{x}" y="{y}" width="{w}" height="{h:.1f}" transform="rotate({rot} {cx:.1f} {cy:.1f})" '
                 f'href="{image_data_uri(framed)}"/>')
    b.append('</g>')
    write(ASSETS / f"hero-{lang}.svg", svg(W, H, "\n".join(b), "Shaft — " + " ".join(t["h1"])))


# ---- 3. capabilities chips ----------------------------------------------------
CAPS = {
    "zh": ["插画", "漫画", "小说", "排行榜", "PixiVision", "FANBOX", "pixiv COMIC", "热门标签", "关注动态",
           "动图补帧", "GIF / MP4", "批量下载", "断点续传", "以图搜图", "稍后再看", "本地书库", "网络自检",
           "图片加速", "平板双栏", "多账号", "自定义主题色", "深色模式", "Material You"],
    "en": ["Illustrations", "Manga", "Novels", "Rankings", "PixiVision", "FANBOX", "pixiv COMIC", "Trending tags",
           "Following feed", "Ugoira interpolation", "GIF / MP4", "Batch download", "Resumable downloads",
           "Reverse image search", "Watch later", "Local library", "Network self-check", "Image mirrors",
           "Tablet two-pane", "Multi-account", "Custom accent color", "Dark mode", "Material You"],
}
CAP_TITLE = {"zh": "为「逛 Pixiv」而生", "en": "Built for browsing Pixiv"}
DOTS = [BRAND, ACCENT, PINK, GLOW, "#00bb7f", "#fcbb00"]


def build_caps(lang: str) -> None:
    uid = "c"
    W = 1200
    pad = 40
    x, y = pad, 92
    rows = []
    row_h, gap = 40, 12
    for i, label in enumerate(CAPS[lang]):
        _, w = pill(0, 0, label, size=15, h=row_h, dot=DOTS[i % len(DOTS)])
        if x + w > W - pad:
            x = pad
            y += row_h + gap
        p, w = pill(x, y, label, size=15, h=row_h, dot=DOTS[i % len(DOTS)])
        rows.append(p)
        x += w + gap
    H = y + row_h + pad
    b = [defs(uid), ink_background(uid, W, H),
         f'<text x="{pad}" y="58" font-family="{FONT}" font-size="26" font-weight="800" fill="url(#{uid}-text)">{esc(CAP_TITLE[lang])}</text>']
    b += rows
    write(ASSETS / f"capabilities-{lang}.svg", svg(W, H, "\n".join(b), CAP_TITLE[lang]))


# ---- 4. tech stack -------------------------------------------------------------
TECH = {
    "zh": [("Kotlin", "Coroutines · Flow"), ("Material Design 3", "Material You · MD3-E"),
           ("MVVM", "Repository Pattern"), ("Retrofit 2", "OkHttp · SSE 流式"), ("Room", "MMKV"),
           ("Glide", "Lottie · ZoomImage"), ("自研 Feeds 框架", "本地优先 · 统一分页"),
           ("自研 witstudio", "自有 UI 基建 · 零 QMUI"), ("actionqueue", "持久化限流队列"),
           ("Cronet", "自定义 DNS · IPv4-only 兜底"), ("Activity Embedding", "平板横屏双栏"),
           ("Target SDK 36", "Android 16 · Min 24")],
    "en": [("Kotlin", "Coroutines · Flow"), ("Material Design 3", "Material You · MD3-E"),
           ("MVVM", "Repository pattern"), ("Retrofit 2", "OkHttp · SSE streaming"), ("Room", "MMKV"),
           ("Glide", "Lottie · ZoomImage"), ("In-house Feeds", "local-first · unified paging"),
           ("In-house witstudio", "own UI kit · zero QMUI"), ("actionqueue", "persistent rate-limited queue"),
           ("Cronet", "custom DNS · IPv4-only fallback"), ("Activity Embedding", "tablet two-pane"),
           ("Target SDK 36", "Android 16 · Min 24")],
}
TECH_TITLE = {"zh": "工程上同样讲究", "en": "Under the hood"}


def build_tech(lang: str) -> None:
    uid = "t"
    W = 1200
    pad, gap = 40, 14
    cols = 4
    cw = (W - pad * 2 - gap * (cols - 1)) / cols
    ch = 84
    items = TECH[lang]
    rows_n = (len(items) + cols - 1) // cols
    top = 96
    H = int(top + rows_n * ch + (rows_n - 1) * gap + pad)
    b = [defs(uid), ink_background(uid, W, H),
         f'<text x="{pad}" y="58" font-family="{FONT}" font-size="26" font-weight="800" fill="url(#{uid}-text)">{esc(TECH_TITLE[lang])}</text>']
    for i, (title, sub) in enumerate(items):
        r, c = divmod(i, cols)
        x = pad + c * (cw + gap)
        y = top + r * (ch + gap)
        b.append(f'<rect x="{x:.1f}" y="{y}" width="{cw:.1f}" height="{ch}" rx="18" fill="#ffffff" fill-opacity=".05" stroke="#ffffff" stroke-opacity=".12"/>')
        b.append(f'<rect x="{x:.1f}" y="{y+18}" width="3" height="{ch-36}" rx="1.5" fill="{DOTS[i % len(DOTS)]}"/>')
        b.append(f'<text x="{x+22:.1f}" y="{y+36}" font-family="{FONT}" font-size="17" font-weight="700" fill="#ffffff">{esc(title)}</text>')
        b.append(f'<text x="{x+22:.1f}" y="{y+60}" font-family="{FONT}" font-size="13" fill="#ffffff" fill-opacity=".6">{esc(sub)}</text>')
    write(ASSETS / f"tech-{lang}.svg", svg(W, H, "\n".join(b), TECH_TITLE[lang]))


# ---- 5. CTA banner -------------------------------------------------------------
CTA = {
    "zh": dict(eyebrow="免费 · 开源 · 无广告", h="现在就开始逛 Pixiv",
               sub="Google Play 一键安装，或在 GitHub 下载最新 APK。完全开源，欢迎 Star 与贡献。",
               b1="Google Play 下载", b2="GitHub Releases"),
    "en": dict(eyebrow="Free · Open source · No ads", h="Start browsing Pixiv now",
               sub="Install from Google Play, or grab the latest APK from GitHub Releases. Fully open source — stars and PRs welcome.",
               b1="Get it on Google Play", b2="GitHub Releases"),
}


def build_cta(lang: str) -> None:
    t = CTA[lang]
    uid = "k"
    W, H = 1200, 250
    b = [defs(uid), ink_background(uid, W, H)]
    p, w = pill(0, 0, t["eyebrow"], size=13, h=30, dot=ACCENT, pad=16)
    p, _ = pill((W - w) / 2, 36, t["eyebrow"], size=13, h=30, dot=ACCENT, pad=16)
    b.append(p)
    b.append(f'<text x="{W/2}" y="122" text-anchor="middle" font-family="{FONT}" font-size="40" font-weight="800" fill="url(#{uid}-text)">{esc(t["h"])}</text>')
    b.append(f'<text x="{W/2}" y="156" text-anchor="middle" font-family="{FONT}" font-size="15" fill="#ffffff" fill-opacity=".7">{esc(t["sub"])}</text>')
    _, w1 = pill(0, 0, t["b1"], size=15, h=44, pad=24)
    _, w2 = pill(0, 0, t["b2"], size=15, h=44, pad=24)
    x0 = (W - (w1 + 14 + w2)) / 2
    p1, _ = pill(x0, 180, t["b1"], size=15, h=44, gradient=f"{uid}-brand", color_op="1", pad=24)
    p2, _ = pill(x0 + w1 + 14, 180, t["b2"], size=15, h=44, fill_op=".08", stroke_op=".22", pad=24)
    b.append(p1); b.append(p2)
    write(ASSETS / f"cta-{lang}.svg", svg(W, H, "\n".join(b), t["h"]))


if __name__ == "__main__":
    print("building README assets…")
    build_frames()
    for lang in ("en", "zh"):
        build_hero(lang)
        build_caps(lang)
        build_tech(lang)
        build_cta(lang)
    print("done.")
