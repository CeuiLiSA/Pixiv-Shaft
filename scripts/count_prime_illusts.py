#!/usr/bin/env python3

import json
import os
import glob

ASSET_DIR = os.path.normpath(os.path.join(
    os.path.dirname(__file__),
    "..", "app", "src", "main", "assets", "pixiv_prime"
))

def main():
    all_ids = set()
    file_stats = []

    for f in sorted(glob.glob(os.path.join(ASSET_DIR, "*.txt"))):
        with open(f, "r", encoding="utf-8") as fp:
            data = json.load(fp)

        tag = data.get("tag", {})
        illusts = data.get("resp", {}).get("illusts", [])
        ids = {illust["id"] for illust in illusts if "id" in illust}
        all_ids.update(ids)

        tag_name = tag.get("translated_name") or tag.get("name") or "?"
        file_stats.append((os.path.basename(f), tag_name, len(ids)))

    print(f"{'File':<90} {'Tag':<25} {'Count':>5}")
    print("-" * 125)
    for filename, tag_name, count in file_stats:
        print(f"{filename:<90} {tag_name:<25} {count:>5}")

    print("-" * 125)
    print(f"Total files: {len(file_stats)}")
    print(f"Total illust IDs (with duplicates): {sum(c for _, _, c in file_stats)}")
    print(f"Total unique illust IDs: {len(all_ids)}")


if __name__ == "__main__":
    main()
