#!/usr/bin/env python3
"""生成登录页回廊使用的单张纹理图集 (Texture Atlas)。

将方形插画切片合批烘焙为 28x16 网格（共 448 格，单格 200x200 px，总分辨率 5600x3200）的单张 WebP 图集，
供 TracedTunnelView 的 AGSL 着色器直接采样。同时生成单张样例 preview_sample.webp。

针对隧道远景的透视压缩与着色器雾化特性，采用分级频带滤波降阶策略：
- 前 112 格（25%）：保持 200x200 原始全频段高精细节；
- 中 112 格（25%）：平滑超高频细节（140px 重采样）；
- 后 224 格（50%）：过滤深处高频噪点（100px 重采样），削减 WebP 熵编码冗余。

用法：python3 scripts/shrink_prime_square.py [--source-dir <dir>] [--quality 68]
"""
import argparse
import io
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
TARGET_DIR = ROOT / "app/src/main/assets/prime_square"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}

ATLAS_COLS = 28
ATLAS_ROWS = 16
TILE_SIZE = 200
TOTAL_TILES = ATLAS_COLS * ATLAS_ROWS  # 448


def load_image(item) -> Image.Image:
    if isinstance(item, Path):
        im = Image.open(item)
    elif isinstance(item, bytes):
        im = Image.open(io.BytesIO(item))
    else:
        raise TypeError(f"Unsupported image source: {type(item)}")
    im = im.convert("RGB")
    if im.width != im.height:
        side = min(im.size)
        left = (im.width - side) // 2
        top = (im.height - side) // 2
        im = im.crop((left, top, left + side, top + side))
    return im


def process_tile(im: Image.Image, tile_index: int) -> Image.Image:
    if im.size != (TILE_SIZE, TILE_SIZE):
        im = im.resize((TILE_SIZE, TILE_SIZE), Image.Resampling.LANCZOS)

    # 深度视觉权重阶梯滤波
    if 112 <= tile_index < 224:
        im = im.resize((140, 140), Image.Resampling.BILINEAR).resize(
            (TILE_SIZE, TILE_SIZE), Image.Resampling.BICUBIC
        )
    elif tile_index >= 224:
        im = im.resize((100, 100), Image.Resampling.BILINEAR).resize(
            (TILE_SIZE, TILE_SIZE), Image.Resampling.BICUBIC
        )
    return im


def build_atlas(images: list, output_atlas: Path, output_sample: Path, quality: int) -> None:
    atlas_w = ATLAS_COLS * TILE_SIZE
    atlas_h = ATLAS_ROWS * TILE_SIZE
    atlas = Image.new("RGB", (atlas_w, atlas_h))

    for i in range(TOTAL_TILES):
        item = images[i % len(images)]
        col = i % ATLAS_COLS
        row = i // ATLAS_COLS
        with load_image(item) as im:
            tile = process_tile(im, i)
            atlas.paste(tile, (col * TILE_SIZE, row * TILE_SIZE))

    output_atlas.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(output_atlas, "WEBP", quality=quality, method=6)

    with load_image(images[0]) as first_im:
        if first_im.size != (TILE_SIZE, TILE_SIZE):
            first_im = first_im.resize((TILE_SIZE, TILE_SIZE), Image.Resampling.LANCZOS)
        first_im.save(output_sample, "WEBP", quality=75, method=6)


def load_from_git() -> list[bytes]:
    try:
        git_ls = subprocess.check_output(
            ["git", "ls-tree", "-r", "--name-only", "HEAD", "app/src/main/assets/prime_square/"],
            text=True,
        )
        files = sorted(
            line.strip()
            for line in git_ls.splitlines()
            if line.strip().endswith(".webp") and "tunnel_atlas" not in line and "preview_sample" not in line
        )
        result = []
        for f in files:
            raw = subprocess.check_output(["git", "show", f"HEAD:{f}"])
            result.append(raw)
        return result
    except Exception as e:
        print(f"Warning: failed to load from git: {e}", file=sys.stderr)
        return []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-dir", type=Path, default=None, help="源切片图片目录")
    ap.add_argument("--quality", type=int, default=68, help="WebP 压缩质量 (默认 68)")
    args = ap.parse_args()

    src_dir = args.source_dir or TARGET_DIR
    files = []
    if src_dir.exists():
        files = sorted(
            p for p in src_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES and p.name not in ("tunnel_atlas.webp", "preview_sample.webp")
        )

    atlas_path = TARGET_DIR / "tunnel_atlas.webp"
    sample_path = TARGET_DIR / "preview_sample.webp"

    if not files:
        # 尝试从 Git 历史回溯源图
        git_images = load_from_git()
        if git_images:
            print(f"Loaded {len(git_images)} source images from git history.")
            build_atlas(git_images, atlas_path, sample_path, args.quality)
        elif atlas_path.exists():
            print(f"tunnel_atlas.webp already exists ({atlas_path.stat().st_size / 1024 / 1024:.2f} MB)")
            return 0
        else:
            print(f"no source images found in {src_dir} or git history", file=sys.stderr)
            return 1
    else:
        build_atlas(files, atlas_path, sample_path, args.quality)

    print(
        f"Built atlas ({atlas_path.stat().st_size / 1024 / 1024:.2f} MB) and "
        f"sample ({sample_path.stat().st_size / 1024:.1f} KB)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
