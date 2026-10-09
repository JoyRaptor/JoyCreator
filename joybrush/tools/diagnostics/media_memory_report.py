#!/usr/bin/env python3
"""JB-NOW-05: memory refusal evidence reader.

Reads a saved UTF-8 logcat text file, consumes ONLY lines tagged
``JoyBrushMediaMemory`` that contain ``refused``, and emits a deterministic
JSON report to stdout.

Current real producers (read from source, not paraphrased):

* ``JbCanvasView.kt`` UI gate::
      refused stage=ui wet=<bool> available=<bytes> threshold=<bytes> held=<bytes>
* ``MediaCanvas.kt`` cold-window gate (``asleep`` is a nullable Boolean, so
  ``null`` is a real value when no engine exists yet):: 
      refused stage=gl-cold wet=<bool> available=<bytes> threshold=<bytes> asleep=<true|false|null>
* ``MediaCanvas.kt`` water-upgrade gate (no ``wet``/``asleep`` by design):: 
      refused stage=gl-water available=<bytes> threshold=<bytes> held=<bytes>

Semantics:

* Repeated events are preserved (never deduplicated); counts group by stage.
* Byte fields (``available``/``threshold``/``held``) must be nonnegative
  integers. Invalid or missing ``available``/``threshold`` values are reported
  as unknown (``null``) with a warning instead of zero. An absent ``held``
  value is reported as unknown (``null``) with NO warning and is never
  inferred: the ``gl-cold`` format legitimately carries no ``held`` token.
* Booleans (``wet``/``asleep``) keep ``true``/``false``/``null`` distinct.
  An absent boolean is ``null`` without warning; an explicit ``null`` token
  (Kotlin nullable, e.g. ``asleep=null``) is also ``null`` without warning.
  Any other boolean spelling is ``null`` with a warning.
* Unrecognized future stages are kept verbatim (missing stage groups under
  ``"unknown"``) and unrecognized ``key=value`` tokens are kept in
  ``extras``. No total budget is invented and no claim is made about which
  gate should have passed.
* Zero observed events is reported explicitly and is NOT proof of no memory
  problem (see ``notes`` in the output).

Usage:
    python media_memory_report.py INPUT_PATH

Exit codes: 0 on success (even with zero events); 2 on usage errors;
1 on input read failures (missing/unreadable path, undecodable bytes).
The tool only reads the given file and writes stdout; it never invokes adb
and never deletes data.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

TOOL_NAME = "media_memory_report"
REPORT_VERSION = 1
TAG = "JoyBrushMediaMemory"
MARKER = "refused"
UNKNOWN_STAGE = "unknown"

# Fixed processing order keeps warnings deterministic.
_NUMERIC_REQUIRED = ("available", "threshold")
_BOOL_FIELDS = ("wet", "asleep")

# Real logcat tag positions only (narrowly defined; no overbroad grammar).
# threadtime: "MM-DD HH:MM:SS.mmm PID TID LEVEL TAG:" (variable PID/TID width).
_THREADTIME_TAG_RE = re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} +\d+ +\d+ +[VDIWEF] " + re.escape(TAG) + r":")
# brief: "LEVEL/TAG(PID):" (PID may be space-padded, e.g. "( 1234)").
_BRIEF_TAG_RE = re.compile(r"^[VDIWEF]/" + re.escape(TAG) + r"\(\s*\d+\s*\):")
# bare: "TAG:" at start of line (saved-message excerpt with no prefix).
_BARE_TAG_RE = re.compile(r"^" + re.escape(TAG) + r":")
_TAG_PREFIX_RES = (_THREADTIME_TAG_RE, _BRIEF_TAG_RE, _BARE_TAG_RE)
_TOKEN_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)=([^\s]*)")
_NONNEG_INT_RE = re.compile(r"[0-9]+$")

_TRUE_FALSE_NULL = {"true": True, "false": False, "null": None}


def is_candidate(line: str) -> bool:
    """True for lines TAGGED JoyBrushMediaMemory containing `refused`.

    Only real tag positions are accepted: threadtime
    (``MM-DD HH:MM:SS.mmm PID TID LEVEL TAG:``), brief
    (``LEVEL/TAG(PID):``), or bare (``TAG:`` at start of line). The
    ``refused`` marker must appear in the message portion after the tag
    colon. A line whose message merely mentions the tag token (e.g. another
    tag's ``W OtherTag: JoyBrushMediaMemory refused ...`` or
    ``SomeOtherTag: refused ... JoyBrushMediaMemory``) is rejected, as are
    lookalike tags (``JoyBrushMediaMemoryX:``) and malformed prefixes
    (missing colon/parens/timestamp fields).
    """
    for prefix_re in _TAG_PREFIX_RES:
        match = prefix_re.match(line)
        if match is not None:
            return MARKER in line[match.end():]
    return False


def _parse_byte(raw: str):
    """Return (int, True) for nonnegative integers, else (None, False)."""
    if _NONNEG_INT_RE.match(raw) is not None:
        try:
            return int(raw, 10), True
        except ValueError:
            return None, False
    return None, False


def parse_line(line_no: int, text: str):
    """Parse one candidate line into (event, warnings).

    ``event`` preserves the 1-based ``line`` number, the original ``text``,
    parsed ``stage``/``wet``/``available``/``threshold``/``held``/``asleep``
    (unknown as None), unrecognized ``key=value`` tokens in ``extras``, and
    bare non-``key=value`` words after the ``refused`` marker in
    ``extra_flags``. ``warnings`` is a list of ``{"line", "field",
    "message"}`` dicts in deterministic order.
    """
    marker_at = text.find(MARKER)
    tail = text[marker_at + len(MARKER):] if marker_at >= 0 else text

    seen: dict[str, str] = {}
    first_order: list[str] = []
    dup_warnings = []
    for match in _TOKEN_RE.finditer(tail):
        key, raw = match.group(1), match.group(2)
        if key in seen:
            dup_warnings.append(
                {
                    "line": line_no,
                    "field": key,
                    "message": (
                        "duplicate field '%s'; last value kept" % key
                    ),
                }
            )
        else:
            first_order.append(key)
        seen[key] = raw

    bare = [tok for tok in tail.split() if "=" not in tok]

    warnings = list(dup_warnings)

    raw_stage = seen.get("stage")
    if raw_stage is None or raw_stage == "":
        stage = None
        warnings.append(
            {
                "line": line_no,
                "field": "stage",
                "message": "missing stage; grouped under 'unknown'",
            }
        )
    else:
        stage = raw_stage

    parsed: dict[str, object] = {}
    for field in _NUMERIC_REQUIRED:
        raw = seen.get(field)
        if raw is None:
            parsed[field] = None
            warnings.append(
                {
                    "line": line_no,
                    "field": field,
                    "message": (
                        "missing required numeric field '%s'; "
                        "reported as unknown (null)" % field
                    ),
                }
            )
        else:
            value, ok = _parse_byte(raw)
            parsed[field] = value
            if not ok:
                warnings.append(
                    {
                        "line": line_no,
                        "field": field,
                        "message": (
                            "invalid nonnegative integer %r for field '%s'; "
                            "reported as unknown (null)" % (raw, field)
                        ),
                    }
                )

    raw_held = seen.get("held")
    if raw_held is None:
        # Absent held is legitimate (gl-cold carries none): unknown, no
        # warning, never inferred.
        parsed["held"] = None
    else:
        value, ok = _parse_byte(raw_held)
        parsed["held"] = value
        if not ok:
            warnings.append(
                {
                    "line": line_no,
                    "field": "held",
                    "message": (
                        "invalid nonnegative integer %r for field 'held'; "
                        "reported as unknown (null)" % (raw_held,)
                    ),
                }
            )

    for field in _BOOL_FIELDS:
        raw = seen.get(field)
        if raw is None or raw in _TRUE_FALSE_NULL:
            # Absent and explicit `null` are both JSON null without warning;
            # the true/false/null distinction itself is preserved in values.
            parsed[field] = _TRUE_FALSE_NULL.get(raw) if raw is not None else None
        else:
            parsed[field] = None
            warnings.append(
                {
                    "line": line_no,
                    "field": field,
                    "message": (
                        "invalid boolean %r for field '%s'; "
                        "reported as unknown (null)" % (raw, field)
                    ),
                }
            )

    extras = {
        key: seen[key]
        for key in first_order
        if key not in ("stage", "wet", "available", "threshold", "held", "asleep")
    }

    event = {
        "line": line_no,
        "text": text,
        "stage": stage,
        "wet": parsed["wet"],
        "available": parsed["available"],
        "threshold": parsed["threshold"],
        "held": parsed["held"],
        "asleep": parsed["asleep"],
        "extras": extras,
        "extra_flags": bare,
    }
    return event, warnings


def build_report(input_path: str, lines: list[str]) -> dict:
    """Build the deterministic report dict for already-read lines."""
    events: list[dict] = []
    warnings: list[dict] = []
    by_stage: dict[str, int] = {}
    consumed = 0
    for line_no, raw in enumerate(lines, start=1):
        text = raw.rstrip("\r\n")
        if not is_candidate(text):
            continue
        consumed += 1
        event, event_warnings = parse_line(line_no, text)
        events.append(event)
        warnings.extend(event_warnings)
        bucket = event["stage"] if event["stage"] else UNKNOWN_STAGE
        by_stage[bucket] = by_stage.get(bucket, 0) + 1
    return {
        "tool": TOOL_NAME,
        "version": REPORT_VERSION,
        "input": {
            "path": input_path,
            "encoding": "utf-8",
            "lines_total": len(lines),
            "lines_consumed": consumed,
        },
        "events_observed": len(events),
        "by_stage": by_stage,
        "events": events,
        "warnings": warnings,
        "notes": [
            "Zero observed refusal events means no JoyBrushMediaMemory "
            "refusal lines were found in this input file; it is not proof "
            "of no memory problem.",
            "Absent held values are reported as unknown (null) and never "
            "inferred; no total budget is invented and no claim is made "
            "about which gate should have passed.",
        ],
    }


def load_report(input_path: str) -> dict:
    """Read a saved UTF-8 logcat file and return its report dict.

    Raises OSError for missing/unreadable paths and UnicodeDecodeError for
    undecodable bytes.
    """
    with open(input_path, "r", encoding="utf-8", errors="strict", newline=None) as handle:
        lines = handle.read().splitlines()
    # splitlines() drops terminators; report text lines without newlines.
    return build_report(input_path, lines)


def report_to_json(report: dict) -> str:
    """Serialize a report deterministically (sorted keys, indent 2)."""
    return json.dumps(report, sort_keys=True, indent=2) + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="media_memory_report.py",
        description=(
            "Read a saved UTF-8 logcat file and report JoyBrushMediaMemory "
            "refusal evidence as JSON on stdout."
        ),
    )
    parser.add_argument("input", help="path to the saved UTF-8 logcat text file")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report = load_report(args.input)
    except (OSError, UnicodeDecodeError) as exc:
        print(
            "media_memory_report.py: error: cannot read input %r: %s"
            % (args.input, exc),
            file=sys.stderr,
        )
        return 1
    try:
        sys.stdout.write(report_to_json(report))
    except BrokenPipeError:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
