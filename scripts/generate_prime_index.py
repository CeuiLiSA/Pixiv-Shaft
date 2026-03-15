#!/usr/bin/env python3

import json
import os
import glob

ASSET_DIR = os.path.join(
    os.path.dirname(__file__),
    "..", "app", "src", "main", "assets", "pixiv_prime"
)

def main():
    asset_dir = os.path.normpath(ASSET_DIR)
    index = []

    for f in sorted(glob.glob(os.path.join(asset_dir, "*.txt"))):
        filename = os.path.basename(f)
        path = "pixiv_prime/" + filename
        with open(f, "r") as fp:
            data = json.load(fp)

        tag = data.get("tag", {})
        illusts = data.get("resp", {}).get("illusts", [])
        preview_urls = []
        for illust in illusts[:3]:
            url = (illust.get("image_urls") or {}).get("square_medium")
            if url:
                preview_urls.append(url)

        index.append({
            "tag": {
                "name": tag.get("name"),
                "translated_name": tag.get("translated_name")
            },
            "file_path": path,
            "preview_square_urls": preview_urls
        })

    out_path = os.path.join(asset_dir, "prime_index.json")
    with open(out_path, "w") as fp:
        json.dump(index, fp, ensure_ascii=False, separators=(",", ":"))

    print(f"Generated {out_path}")
    print(f"  entries: {len(index)}")
    print(f"  size: {os.path.getsize(out_path)} bytes")

if __name__ == "__main__":
    main()
