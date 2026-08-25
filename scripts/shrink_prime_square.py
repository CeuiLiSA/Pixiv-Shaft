#!/usr/bin/env python3
"""把 app/src/main/assets/prime_square/ 里登录页回廊用的方图压到实际显示规格。

TracedTunnelView 把每张图画进 28x16 的图集，每格 IMAGE_TILE_SIZE=200 px，RGB_565。
源图 540x540 JPG 里超出 200 px 的部分解码后立刻被丢掉，所以离线缩到 200 px 存 WebP
在屏幕上一个像素都不少；图集只有 448 格，多出来的图永远轮不到，一并删掉。

用法：python3 scripts/shrink_prime_square.py [--keep 448] [--size 200] [--quality 85]
幂等：已经是 <size>x<size> WebP 的文件原样保留。
"""
import argparse
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = ROOT / "app/src/main/assets/prime_square"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", type=int, default=28 * 16, help="保留张数 = ATLAS_COLS*ATLAS_ROWS")
    ap.add_argument("--size", type=int, default=200, help="边长 = IMAGE_TILE_SIZE")
    ap.add_argument("--quality", type=int, default=85)
    args = ap.parse_args()

    files = sorted(
        p for p in ASSET_DIR.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES  # 跳过 .DS_Store 之类
    )
    if not files:
        print(f"no files in {ASSET_DIR}", file=sys.stderr)
        return 1

    keep, drop = files[: args.keep], files[args.keep :]
    before = sum(p.stat().st_size for p in files)

    for p in drop:
        p.unlink()

    converted = 0
    for p in keep:
        with Image.open(p) as im:
            if p.suffix == ".webp" and im.size == (args.size, args.size):
                continue
            im = im.convert("RGB")
            if im.width != im.height:  # 保险：非正方形先居中裁方
                side = min(im.size)
                left, top = (im.width - side) // 2, (im.height - side) // 2
                im = im.crop((left, top, left + side, top + side))
            im = im.resize((args.size, args.size), Image.LANCZOS)
            im.save(p.with_suffix(".webp"), "WEBP", quality=args.quality, method=6)
        if p.suffix != ".webp":
            p.unlink()
        converted += 1

    after = sum(p.stat().st_size for p in ASSET_DIR.iterdir() if p.is_file())
    print(
        f"kept {len(keep)}, dropped {len(drop)}, converted {converted}; "
        f"{before / 1024 / 1024:.1f} MB -> {after / 1024 / 1024:.2f} MB"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
