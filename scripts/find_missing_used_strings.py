#!/usr/bin/env python3
"""
Find string resources that are used by the app but missing from locale targets.

The script scans app source files for R.string.name and @string/name references,
then compares those names with target locale strings.xml files. Missing entries
are printed from the default values/strings.xml file so they can be translated
and pasted into the target locale file.

Usage:
    python scripts\find_missing_used_strings.py --show-undefined --show-usages

By default the script runs in dry-run/report mode and does not modify files.

Options:
    --base           Repository root to run from.
    --source-values  Source strings.xml used for fallback text. Default: app/src/main/res/values/strings.xml.
    --src            Source directory to scan. Default: app/src/main.
    --target         Target locale strings.xml path relative to --base. Can be repeated.
    --show-missing   Show missing string XML blocks from --source-values.
    --show-usages    Show source locations where each missing string is referenced.
    --show-undefined Show used string names that are missing from --source-values.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_SOURCE_VALUES = "app/src/main/res/values/strings.xml"
DEFAULT_SOURCE_ROOT = "app/src/main"
DEFAULT_TARGETS = [
    "app/src/main/res/values-en/strings.xml",
    "app/src/main/res/values-ja/strings.xml",
    "app/src/main/res/values-ko/strings.xml",
    "app/src/main/res/values-tr/strings.xml",
    "app/src/main/res/values-ru/strings.xml",
    "app/src/main/res/values-zh-rTW/strings.xml",
]

STRING_RE = re.compile(r"(?s)<string\b(?P<attrs>[^>]*)>.*?</string>")
NAME_RE = re.compile(r"""\bname\s*=\s*(?P<quote>["'])(?P<name>.*?)(?P=quote)""")
CODE_STRING_RE = re.compile(r"(?<![\w.])R\.string\.([A-Za-z_][A-Za-z0-9_]*)")
XML_STRING_RE = re.compile(r"(?<![\w:])@string/([A-Za-z_][A-Za-z0-9_]*)")
SCAN_EXTENSIONS = {".java", ".kt", ".xml"}
XML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


@dataclass(frozen=True)
class Usage:
    path: Path
    line: int


@dataclass
class StringFile:
    path: Path
    order: list[str]
    blocks: dict[str, str]
    line_numbers: dict[str, int]


@dataclass
class UsedStrings:
    order: list[str] = field(default_factory=list)
    usages: dict[str, list[Usage]] = field(default_factory=dict)

    def add(self, name: str, usage: Usage) -> None:
        if name not in self.usages:
            self.order.append(name)
            self.usages[name] = []
        self.usages[name].append(usage)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Find used Android string resources missing from locale strings.xml files."
    )
    parser.add_argument(
        "--base",
        type=Path,
        default=Path.cwd(),
        help="Repository root. Defaults to the current working directory.",
    )
    parser.add_argument(
        "--source-values",
        default=DEFAULT_SOURCE_VALUES,
        help=f"Fallback strings.xml path relative to --base. Default: {DEFAULT_SOURCE_VALUES}",
    )
    parser.add_argument(
        "--src",
        default=DEFAULT_SOURCE_ROOT,
        help=f"Source directory to scan relative to --base. Default: {DEFAULT_SOURCE_ROOT}",
    )
    parser.add_argument(
        "--target",
        action="append",
        dest="targets",
        help="Target locale strings.xml path relative to --base. Can be repeated.",
    )
    parser.add_argument(
        "--show-missing",
        action="store_true",
        help="Print missing string XML blocks from --source-values.",
    )
    parser.add_argument(
        "--show-usages",
        action="store_true",
        help="Print source locations where each missing string is referenced.",
    )
    parser.add_argument(
        "--show-undefined",
        action="store_true",
        help="Print used string names that are missing from --source-values. Use --show-usages to include source locations.",
    )
    return parser.parse_args()


def resolve(base: Path, maybe_relative: str) -> Path:
    path = Path(maybe_relative)
    return path if path.is_absolute() else base / path


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def blank_non_newlines(text: str) -> str:
    return "".join("\n" if ch == "\n" else " " for ch in text)


def strip_comments(text: str, path: Path) -> str:
    if path.suffix == ".xml":
        return XML_COMMENT_RE.sub(lambda m: blank_non_newlines(m.group(0)), text)

    if path.suffix in {".java", ".kt"}:
        text = BLOCK_COMMENT_RE.sub(lambda m: blank_non_newlines(m.group(0)), text)
        text = LINE_COMMENT_RE.sub(lambda m: blank_non_newlines(m.group(0)), text)
        return text

    return text


def string_name(attrs: str, path: Path) -> str:
    match = NAME_RE.search(attrs)
    if not match:
        raise ValueError(f"{path}: found <string> without name attribute")
    return match.group("name")


def parse_string_file(path: Path) -> StringFile:
    text = read_text(path)
    order: list[str] = []
    blocks: dict[str, str] = {}
    line_numbers: dict[str, int] = {}

    for match in STRING_RE.finditer(text):
        name = string_name(match.group("attrs"), path)
        if name in blocks:
            raise ValueError(f"{path}: duplicate string name {name!r}")
        order.append(name)
        blocks[name] = match.group(0).strip()
        line_numbers[name] = text.count("\n", 0, match.start()) + 1

    return StringFile(path=path, order=order, blocks=blocks, line_numbers=line_numbers)


def should_scan(path: Path) -> bool:
    if path.suffix not in SCAN_EXTENSIONS:
        return False
    return not any(part.startswith("values") for part in path.parts)


def find_line_numbers(text: str, pattern: re.Pattern[str]) -> list[tuple[str, int]]:
    found: list[tuple[str, int]] = []
    for match in pattern.finditer(text):
        found.append((match.group(1), text.count("\n", 0, match.start()) + 1))
    return found


def collect_used_strings(src_root: Path) -> UsedStrings:
    used = UsedStrings()

    for path in sorted(src_root.rglob("*")):
        if not path.is_file() or not should_scan(path):
            continue

        text = strip_comments(read_text(path), path)
        for name, line in find_line_numbers(text, CODE_STRING_RE):
            used.add(name, Usage(path=path, line=line))
        for name, line in find_line_numbers(text, XML_STRING_RE):
            used.add(name, Usage(path=path, line=line))

    return used


def missing_used_names(source: StringFile, target: StringFile, used: UsedStrings) -> list[str]:
    target_names = set(target.order)
    used_names = set(used.order)
    return [name for name in source.order if name in used_names and name not in target_names]


def undefined_used_names(source: StringFile, used: UsedStrings) -> list[str]:
    source_names = set(source.order)
    return [name for name in used.order if name not in source_names]


def relative_to_base(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    args = parse_args()
    base = args.base.resolve()
    source_path = resolve(base, args.source_values)
    src_root = resolve(base, args.src)
    target_args = args.targets if args.targets else DEFAULT_TARGETS

    try:
        source = parse_string_file(source_path)
        used = collect_used_strings(src_root)
        undefined = undefined_used_names(source, used)

        print(f"used_strings={len(used.order)}, source_values={len(source.order)}")
        if undefined:
            print(f"warning: used strings not found in {source.path}: {len(undefined)}")
            if args.show_undefined:
                print("  undefined:")
                for name in undefined:
                    print(f"    {name}")
                    usages = used.usages.get(name, [])
                    if args.show_usages:
                        for usage in usages:
                            rel_path = relative_to_base(usage.path, base)
                            print(f"      used at {rel_path}:{usage.line}")

        for target_arg in target_args:
            target = parse_string_file(resolve(base, target_arg))
            names = missing_used_names(source, target, used)
            print(f"{target.path}: missing_used={len(names)}")

            if args.show_missing and names:
                print("  missing:")
                for name in names:
                    print(f"    {source.blocks[name]} (source line {source.line_numbers[name]})")
                    if args.show_usages:
                        for usage in used.usages[name]:
                            rel_path = relative_to_base(usage.path, base)
                            print(f"      used at {rel_path}:{usage.line}")

        return 0
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
