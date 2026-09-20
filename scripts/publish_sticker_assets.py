#!/usr/bin/env python3
"""Verify local sticker ZIPs, then publish immutable-by-name GitHub assets.

Requires Python 3.11+ and an authenticated gh CLI. Without --publish, only
prepares files locally. Never downloads from or writes to COS.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

REPO = "CeuiLiSA/Pixiv-Shaft"
TAG = "sticker-assets"
BASE = f"https://github.com/{REPO}/releases/download/{TAG}/"
TYPES = ("customized", "static", "animation")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def find_release():
    pages = json.loads(gh("api", f"repos/{REPO}/releases", "--paginate", "--slurp"))
    return next((r for page in pages for r in page if r["tag_name"] == TAG), None)


def prepare(source, catalogs, output):
    manifest = json.loads((source / "download-manifest.json").read_text())
    if manifest.get("errors") or not manifest.get("files"):
        raise ValueError("Incomplete download manifest")
    files = {}
    for item in manifest["files"]:
        checksum = item["sha256"]
        path = (source / item["file"]).resolve()
        if not path.is_relative_to(source) or not re.fullmatch(r"[a-f0-9]{64}", checksum):
            raise ValueError("Invalid local archive path/checksum")
        if path.stat().st_size != item["bytes"] or sha256(path) != checksum:
            raise ValueError(f"Archive checksum mismatch: {path}")
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Archive CRC mismatch: {path}")
        files[checksum] = (path, item)

    referenced = set()
    snapshots = {}
    for kind in TYPES:
        raw = (catalogs / f"{kind}.json").read_bytes()
        snapshots[f"{kind}.json"] = raw  # Keep uint64 sticker IDs byte-for-byte.
        pack = json.loads(raw)
        if {pkg["width"] for pkg in pack["pkgList"]} != {64, 128}:
            raise ValueError(f"Missing resolution: {kind}")
        for pkg in pack["pkgList"]:
            path, item = files[pkg["sha256"]]
            if item["bytes"] != pkg["size"] or item["path"] != pkg["path"]:
                raise ValueError(f"Catalog mismatch: {kind}: {path}")
            referenced.add(pkg["sha256"])
    snapshots["versions.json"] = (catalogs / "versions.json").read_bytes()
    if {item["name"] for item in json.loads(snapshots["versions.json"])["list"]} != set(TYPES):
        raise ValueError("Incomplete catalog versions")

    output.mkdir(parents=True, exist_ok=True)
    assets = []
    entries = []
    for checksum in sorted(referenced):
        path, item = files[checksum]
        target = output / f"{checksum}.zip"
        shutil.copyfile(path, target)
        assets.append((target, item["file"]))
        entries.append({"file": item["file"], "size": item["bytes"], "sha256": checksum,
                        "url": BASE + target.name})
    snapshot_id = hashlib.sha256(b"".join(snapshots.values())).hexdigest()[:16]
    for name, raw in snapshots.items():
        target = output / f"{snapshot_id}-{name}"
        target.write_bytes(raw)
        assets.append((target, name))
    target = output / f"{snapshot_id}-manifest.json"
    target.write_text(json.dumps({"files": entries}, indent=2) + "\n")
    assets.append((target, "Download manifest"))
    return assets


def publish(assets, target):
    # Listing first distinguishes a missing release from an authentication/network failure.
    release = find_release()
    if release is None:
        with tempfile.TemporaryDirectory(prefix="sticker-notes-") as tmp:
            notes = Path(tmp) / "notes.md"
            notes.write_text(
                "Shaft 贴纸资源（精选、表情、动态，64 / 128 两档）。\n\n"
                "ZIP 按 SHA-256 命名，客户端下载后校验长度与 SHA-256，再解压到本地。"
                "附件标签标明原始文件名；附带目录快照和下载清单。\n\n"
                "资源更新只追加新文件，已有 ZIP 不覆盖、不删除。\n"
            )
            gh("release", "create", TAG, "--repo", REPO, "--target", target,
               "--title", "Sticker assets / 贴纸资源", "--notes-file", str(notes),
               "--draft", "--prerelease", "--latest=false")
        release = find_release()  # The by-tag REST endpoint excludes draft releases.

    existing = {asset["name"]: asset for asset in release["assets"]}
    for path, label in assets:
        asset = existing.get(path.name)
        if asset:
            if asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + sha256(path):
                raise ValueError(f"Existing release asset differs; refusing overwrite: {path.name}")
            continue
        print(f"Uploading {label} ({path.stat().st_size:,} bytes)", flush=True)
        gh("release", "upload", TAG, f"{path}#{label}", "--repo", REPO)

    # Check GitHub's server-side checksums before making a new release visible.
    release = json.loads(gh("api", f"repos/{REPO}/releases/{release['id']}"))
    uploaded = {asset["name"]: asset for asset in release["assets"]}
    for path, _ in assets:
        asset = uploaded[path.name]
        if asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + sha256(path):
            raise ValueError(f"Uploaded asset verification failed: {path.name}")
    if release["draft"]:
        gh("release", "edit", TAG, "--repo", REPO, "--draft=false", "--latest=false")

    for path, label in assets:
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(BASE + path.name, timeout=120) as response:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                size += len(chunk)
                digest.update(chunk)
        if size != path.stat().st_size or digest.hexdigest() != sha256(path):
            raise ValueError(f"Anonymous download verification failed: {label}")
        print(f"Verified anonymous GitHub download: {label}", flush=True)
    print(f"https://github.com/{REPO}/releases/tag/{TAG}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original ZIPs and download-manifest.json")
    parser.add_argument("catalogs", type=Path, help="Active API resources/sticker directory")
    parser.add_argument("output", type=Path, help="Local staging directory")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--target", default="classic", help="Commit/branch for a new release tag")
    args = parser.parse_args()
    assets = prepare(args.source.resolve(), args.catalogs.resolve(), args.output.resolve())
    print(f"Prepared {len(assets)} verified assets in {args.output}", flush=True)
    if args.publish:
        publish(assets, args.target)


if __name__ == "__main__":
    main()
