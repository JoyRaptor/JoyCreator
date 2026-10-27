#!/usr/bin/env python3
"""Check local Markdown links in the live JoyBrush entry-point documents.

This is a read-only, standard-library check. It checks link destinations only; it
does not validate fragments or determine whether a feature-status claim is true.
The historical roadmap archive is deliberately not among the scanned documents.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


LIVE_DOCUMENTS = (
    Path("tasks/joybrush/ROADMAP.md"),
    Path("tasks/joybrush/AGENT_START_20261026.md"),
    Path("tasks/STATE.md"),
)

_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
_INLINE_LINK = re.compile(r"\[[^\]\n]*\]\(\s*(?:<([^>\n]*)>|((?:\\.|[^\s)])+))")
_REFERENCE = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(?:<([^>\n]*)>|((?:\\.|\S)+))")
_WINDOWS_DRIVE = re.compile(r"^[A-Za-z]:[\\/]")


def _markdown_lines(text: str):
    """Yield lines outside fenced code blocks, which are examples, not links."""
    fence_char = None
    fence_size = 0
    for number, line in enumerate(text.splitlines(), start=1):
        match = _FENCE.match(line)
        if match:
            marker = match.group(1)
            char = marker[0]
            if fence_char is None:
                fence_char, fence_size = char, len(marker)
            elif char == fence_char and len(marker) >= fence_size:
                fence_char, fence_size = None, 0
            continue
        if fence_char is None:
            yield number, line


def _link_targets(text: str):
    for line_number, line in _markdown_lines(text):
        for match in _INLINE_LINK.finditer(line):
            target = match.group(1) if match.group(1) is not None else match.group(2)
            if target:
                yield line_number, target.replace(r"\ ", " ")
        match = _REFERENCE.match(line)
        if match:
            target = match.group(1) if match.group(1) is not None else match.group(2)
            if target:
                yield line_number, target.replace(r"\ ", " ")


def _local_target(root: Path, document: Path, target: str) -> Path | None:
    """Return a local destination path, or None for external and fragment-only URLs."""
    parsed = urlsplit(target)
    if parsed.scheme and not _WINDOWS_DRIVE.match(target):
        return None
    if parsed.netloc or target.startswith("#") or not parsed.path:
        return None

    destination = Path(unquote(parsed.path.replace("\\", "/")))
    if destination.is_absolute():
        # Markdown links beginning with / are repository-root-relative.
        if not _WINDOWS_DRIVE.match(target):
            destination = root / str(destination).lstrip("/\\")
    else:
        destination = document.parent / destination
    return destination.resolve()


def check(root: Path) -> tuple[list[str], int]:
    """Return actionable errors and the number of local links checked."""
    errors = []
    checked = 0
    root = root.resolve()
    for relative_document in LIVE_DOCUMENTS:
        document = root / relative_document
        if not document.is_file():
            errors.append(f"{relative_document.as_posix()}: live document is missing")
            continue
        text = document.read_text(encoding="utf-8")
        for line_number, target in _link_targets(text):
            resolved = _local_target(root, document, target)
            if resolved is None:
                continue
            checked += 1
            if not resolved.exists():
                errors.append(
                    f"{relative_document.as_posix()}:{line_number}: "
                    f"missing local link target {target!r} (resolved to {resolved})"
                )
    return errors, checked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        help="repository root (defaults to the parent of this script's tools/ directory)",
    )
    args = parser.parse_args(argv)
    root = args.root.resolve() if args.root else Path(__file__).resolve().parents[1]
    errors, checked = check(root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print(f"{len(errors)} problem(s); checked {checked} local link(s).", file=sys.stderr)
        return 1
    print(f"JoyBrush documentation links OK ({checked} local links in 3 live documents).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
