#!/usr/bin/env python3
"""
Sort target locale strings and remove extra strings by the master strings.xml file.
By default, the master file is the base Android fallback locale.

Usage:
    python scripts\sort_locale_strings.py

By default the script runs in dry-run mode and does not modify files.

Options:
    --base           Repository root to run from.
    --master         File used as the sorting and filtering base.
    --target         Target file to process. Can be repeated.
    --write          Apply sorting and removal.
    --show-missing   Show strings that exist in the master file but are missing from the target file.
    --show-removed   Show strings that will be removed from the target because they do not exist in the master file.

Translation workflow:
    First find untranslated/missing strings with --show-missing, translate them
    at the end of the target file, then run the script to sort them relative to
    the master file:

    python scripts\sort_locale_strings.py --write
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


DEFAULT_MASTER = "app/src/main/res/values/strings.xml"
DEFAULT_TARGETS = [
    "app/src/main/res/values-en/strings.xml",
    "app/src/main/res/values-zh-rTW/strings.xml",
    "app/src/main/res/values-ja/strings.xml",
    "app/src/main/res/values-ko/strings.xml",
    "app/src/main/res/values-tr/strings.xml",
    "app/src/main/res/values-ru/strings.xml",
]

RESOURCES_RE = re.compile(r"(?s)(?P<head>.*?<resources\b[^>]*>)(?P<body>.*)(?P<tail>\s*</resources>\s*)$")
STRING_RE = re.compile(r"(?s)<string\b(?P<attrs>[^>]*)>.*?</string>")
NAME_RE = re.compile(r"""\bname\s*=\s*(?P<quote>["'])(?P<name>.*?)(?P=quote)""")
WS_OR_COMMENT_RE = re.compile(r"(?s)^(?:\s|<!--.*?-->)*$")


@dataclass
class StringFile:
    path: Path
    text: str
    head: str
    body: str
    tail: str
    order: list[str]
    blocks: dict[str, str]
    line_numbers: dict[str, int]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sort selected Android locale strings by base values/strings.xml key order."
    )
    parser.add_argument(
        "--base",
        type=Path,
        default=Path.cwd(),
        help="Repository root. Defaults to the current working directory.",
    )
    parser.add_argument(
        "--master",
        default=DEFAULT_MASTER,
        help=f"Master strings.xml path relative to --base. Default: {DEFAULT_MASTER}",
    )
    parser.add_argument(
        "--target",
        action="append",
        dest="targets",
        help="Target locale strings.xml path relative to --base. Can be repeated.",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Rewrite target files. Without this flag the script only reports changes.",
    )
    parser.add_argument(
        "--show-missing",
        action="store_true",
        help="Print string names that exist in the master file but are missing in each target.",
    )
    parser.add_argument(
        "--show-removed",
        action="store_true",
        help="Print string names that exist in each target but not in the master file.",
    )
    return parser.parse_args()


def resolve(base: Path, maybe_relative: str) -> Path:
    path = Path(maybe_relative)
    return path if path.is_absolute() else base / path


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def split_resources(path: Path, text: str) -> tuple[str, str, str]:
    match = RESOURCES_RE.match(text)
    if not match:
        raise ValueError(f"{path}: could not find a single <resources> root")
    return match.group("head"), match.group("body"), match.group("tail")


def string_name(attrs: str, path: Path) -> str:
    match = NAME_RE.search(attrs)
    if not match:
        raise ValueError(f"{path}: found <string> without name attribute")
    return match.group("name")


def parse_string_file(path: Path) -> StringFile:
    text = read_text(path)
    head, body, tail = split_resources(path, text)

    order: list[str] = []
    blocks: dict[str, str] = {}
    line_numbers: dict[str, int] = {}
    previous_end = 0

    for match in STRING_RE.finditer(body):
        name = string_name(match.group("attrs"), path)
        if name in blocks:
            raise ValueError(f"{path}: duplicate string name {name!r}")

        prefix = body[previous_end:match.start()]
        string_block = match.group(0)
        if WS_OR_COMMENT_RE.fullmatch(prefix):
            block = prefix + string_block
        else:
            block = string_block

        order.append(name)
        blocks[name] = block.strip()
        line_numbers[name] = text.count("\n", 0, len(head) + match.start()) + 1
        previous_end = match.end()

    return StringFile(
        path=path,
        text=text,
        head=head,
        body=body,
        tail=tail,
        order=order,
        blocks=blocks,
        line_numbers=line_numbers,
    )


def build_target_text(master_order: list[str], target: StringFile) -> str:
    sorted_blocks = [target.blocks[name] for name in master_order if name in target.blocks]
    if sorted_blocks:
        body = "\n" + "\n".join(indent_first_line(block) for block in sorted_blocks) + "\n"
    else:
        body = "\n"
    return target.head.rstrip() + body + target.tail.lstrip()


def indent_first_line(block: str) -> str:
    lines = block.splitlines()
    if not lines:
        return ""
    lines[0] = "    " + lines[0].lstrip()
    return "\n".join(lines)


def missing_names(master: StringFile, target: StringFile) -> list[str]:
    target_names = set(target.order)
    return [name for name in master.order if name not in target_names]


def removed_names(master: StringFile, target: StringFile) -> list[str]:
    master_names = set(master.order)
    return [name for name in target.order if name not in master_names]


def summarize(master: StringFile, target: StringFile, new_text: str) -> str:
    master_names = set(master.order)
    target_names = set(target.order)
    kept = len(master_names & target_names)
    removed = len(removed_names(master, target))
    missing_not_added = len(missing_names(master, target))
    changed = "changed" if new_text != target.text else "ok"
    return (
        f"{target.path}: {changed}; kept={kept}, removed={removed}, "
        f"missing_not_added={missing_not_added}"
    )


def main() -> int:
    args = parse_args()
    base = args.base.resolve()
    target_args = args.targets if args.targets else DEFAULT_TARGETS

    try:
        master = parse_string_file(resolve(base, args.master))

        for target_arg in target_args:
            target = parse_string_file(resolve(base, target_arg))
            new_text = build_target_text(master.order, target)
            print(summarize(master, target, new_text))
            if args.show_missing:
                names = missing_names(master, target)
                if names:
                    print("  missing_not_added:")
                    for name in names:
                        print(f"    {name} (master line {master.line_numbers[name]})")
            if args.show_removed:
                names = removed_names(master, target)
                if names:
                    print("  removed:")
                    for name in names:
                        print(f"    {name} (target line {target.line_numbers[name]})")

            if new_text != target.text:
                if args.write:
                    target.path.write_text(new_text, encoding="utf-8", newline="\n")

        return 0
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
