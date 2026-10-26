#!/usr/bin/env python3
"""Offline consistency/completeness validator for Select bench evidence."""
from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path
from typing import Any

MAX_MANIFEST = 256 * 1024
MAX_BUILD = 64 * 1024
MAX_RESULTS = 4 * 1024 * 1024
MAX_RECORD = 64 * 1024
MAX_TEMPS = 64 * 1024
MAX_PNG = 64 * 1024 * 1024
MAX_STEPS = 1000
MAX_PIXELS = 100_000_000
HEX40 = re.compile(r"^[0-9a-fA-F]{40}$")
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class EvidenceError(Exception):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant {value}")


def strict_json_loads(text: str) -> Any:
    return json.loads(text, object_pairs_hook=reject_duplicate_keys, parse_constant=reject_json_constant)


def read_json(path: Path, limit: int) -> Any:
    try:
        size = path.stat().st_size
        if size > limit:
            raise EvidenceError(f"{path.name}: exceeds {limit} byte limit")
        with path.open("rb") as f:
            raw = f.read(limit + 1)
        if len(raw) > limit:
            raise EvidenceError(f"{path.name}: exceeds {limit} byte limit")
        return strict_json_loads(raw.decode("utf-8"))
    except FileNotFoundError:
        raise EvidenceError(f"missing file: {path.name}")
    except (UnicodeDecodeError, ValueError) as e:
        raise EvidenceError(f"{path.name}: invalid JSON/UTF-8 ({e})")
    except OSError as e:
        raise EvidenceError(f"{path.name}: cannot read ({e})")


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{label}: expected JSON object")
    return value


def load_manifest(path: Path) -> tuple[list[str], list[str]]:
    data = require_object(read_json(path, MAX_MANIFEST), "manifest")
    if type(data.get("version")) is not int or data["version"] != 1:
        raise EvidenceError("manifest: version must be integer 1")
    steps, shots = data.get("steps"), data.get("screenshots")
    if not isinstance(steps, list) or not isinstance(shots, list):
        raise EvidenceError("manifest: steps and screenshots must be arrays")
    if not steps:
        raise EvidenceError("manifest: steps must not be empty")
    if not shots:
        raise EvidenceError("manifest: screenshots must not be empty")
    if len(steps) > MAX_STEPS or len(shots) > MAX_STEPS:
        raise EvidenceError(f"manifest: steps/screenshots limit is {MAX_STEPS}")
    for label, values in (("steps", steps), ("screenshots", shots)):
        if any(not isinstance(v, str) or not v or v.strip() != v for v in values):
            raise EvidenceError(f"manifest: every {label} entry must be a nonempty trimmed string")
        if len(values) != len(set(values)):
            raise EvidenceError(f"manifest: duplicate {label} entry")
    if any("\n" in s or "\r" in s for s in steps):
        raise EvidenceError("manifest: step names may not contain line breaks")
    if "summary" in steps:
        raise EvidenceError("manifest: 'summary' is reserved and cannot be a step name")
    for shot in shots:
        if Path(shot).name != shot or not shot.lower().endswith(".png"):
            raise EvidenceError("manifest: screenshots must be PNG basenames, not paths")
    return steps, shots


def validate_build(path: Path, run_id: str) -> None:
    d = require_object(read_json(path, MAX_BUILD), "build.json")
    source = d.get("source40hex")
    expected = d.get("expectedApkSha256")
    installed = d.get("installedApkSha256")
    if not isinstance(source, str) or not HEX40.fullmatch(source):
        raise EvidenceError("build.json: source40hex must be 40 hexadecimal characters")
    if not isinstance(expected, str) or not HEX64.fullmatch(expected):
        raise EvidenceError("build.json: expectedApkSha256 must be 64 hexadecimal characters")
    if not isinstance(installed, str) or not HEX64.fullmatch(installed):
        raise EvidenceError("build.json: installedApkSha256 must be 64 hexadecimal characters")
    if expected.lower() != installed.lower():
        raise EvidenceError("build.json: expected and installed APK SHA-256 do not match")
    if d.get("runId") != run_id:
        raise EvidenceError("build.json: runId mismatch")
    if d.get("deviceModel") != "SM-N960U":
        raise EvidenceError("build.json: deviceModel must be SM-N960U")
    if type(d.get("deviceApi")) is not int or d["deviceApi"] != 29:
        raise EvidenceError("build.json: deviceApi must be integer 29")


def validate_temps(path: Path) -> None:
    try:
        if path.stat().st_size > MAX_TEMPS:
            raise EvidenceError(f"temps.txt: exceeds {MAX_TEMPS} byte limit")
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise EvidenceError("missing file: temps.txt")
    except (OSError, UnicodeDecodeError) as e:
        raise EvidenceError(f"temps.txt: cannot read UTF-8 text ({e})")
    values: dict[str, float] = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) != 2 or parts[0] not in ("temp_before_C", "temp_after_C"):
            raise EvidenceError(f"temps.txt: malformed or unexpected line: {line!r}")
        key = parts[0]
        if key in values:
            raise EvidenceError(f"temps.txt: duplicate {key}")
        try:
            number = float(parts[1])
        except ValueError:
            raise EvidenceError(f"temps.txt: {key} must be numeric")
        if not math.isfinite(number):
            raise EvidenceError(f"temps.txt: {key} must be finite")
        values[key] = number
    for key in ("temp_before_C", "temp_after_C"):
        if key not in values:
            raise EvidenceError(f"temps.txt: missing {key}")


def validate_png(path: Path) -> None:
    try:
        size = path.stat().st_size
        if size <= 0:
            raise EvidenceError(f"{path.name}: empty screenshot")
        if size > MAX_PNG:
            raise EvidenceError(f"{path.name}: exceeds {MAX_PNG} byte limit")
        with path.open("rb") as f:
            if f.read(8) != PNG_SIGNATURE:
                raise EvidenceError(f"{path.name}: invalid PNG signature")
            first = True
            saw_iend = False
            saw_idat = False
            while f.tell() < size:
                header = f.read(8)
                if len(header) != 8:
                    raise EvidenceError(f"{path.name}: truncated PNG chunk header")
                length, kind = struct.unpack(">I4s", header)
                if length > MAX_PNG or f.tell() + length + 4 > size:
                    raise EvidenceError(f"{path.name}: truncated/oversized PNG chunk")
                if first and kind != b"IHDR":
                    raise EvidenceError(f"{path.name}: IHDR is not the first PNG chunk")
                if not first and kind == b"IHDR":
                    raise EvidenceError(f"{path.name}: duplicate IHDR chunk")
                if first:
                    if length != 13:
                        raise EvidenceError(f"{path.name}: invalid IHDR length")
                    ihdr = f.read(13)
                    width, height = struct.unpack(">II", ihdr[:8])
                    if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                        raise EvidenceError(f"{path.name}: invalid or excessive PNG dimensions")
                    crc = zlib.crc32(kind)
                    crc = zlib.crc32(ihdr, crc) & 0xFFFFFFFF
                    got = f.read(4)
                    if len(got) != 4 or struct.unpack(">I", got)[0] != crc:
                        raise EvidenceError(f"{path.name}: invalid IHDR CRC")
                    first = False
                    continue
                crc = zlib.crc32(kind)
                remaining = length
                while remaining:
                    chunk = f.read(min(64 * 1024, remaining))
                    if not chunk:
                        raise EvidenceError(f"{path.name}: truncated PNG chunk data")
                    crc = zlib.crc32(chunk, crc)
                    remaining -= len(chunk)
                got = f.read(4)
                if len(got) != 4 or struct.unpack(">I", got)[0] != (crc & 0xFFFFFFFF):
                    raise EvidenceError(f"{path.name}: invalid PNG chunk CRC")
                if kind == b"IEND":
                    if length != 0:
                        raise EvidenceError(f"{path.name}: invalid IEND length")
                    if f.tell() != size:
                        raise EvidenceError(f"{path.name}: bytes follow PNG IEND")
                    saw_iend = True
                    break
                if kind == b"IDAT":
                    if length == 0:
                        raise EvidenceError(f"{path.name}: IDAT chunk must be nonempty")
                    saw_idat = True
            if first or not saw_iend or not saw_idat:
                raise EvidenceError(f"{path.name}: missing IHDR, nonempty IDAT, or complete IEND")
    except FileNotFoundError:
        raise EvidenceError(f"missing screenshot: {path.name}")
    except OSError as e:
        raise EvidenceError(f"{path.name}: cannot read ({e})")


def validate_results(path: Path, steps: list[str], run_id: str, case: str | None = None) -> None:
    try:
        size = path.stat().st_size
        if size > MAX_RESULTS:
            raise EvidenceError(f"results.jsonl: exceeds {MAX_RESULTS} byte limit")
        reports: dict[str, bool] = {}
        summaries: list[dict[str, Any]] = []
        seen_summary = False
        with path.open("rb") as f:
            for line_no, raw in enumerate(f, 1):
                if len(raw) > MAX_RECORD:
                    raise EvidenceError(f"results.jsonl:{line_no}: record exceeds {MAX_RECORD} byte limit")
                if not raw.strip():
                    continue
                try:
                    record = strict_json_loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, ValueError) as e:
                    raise EvidenceError(f"results.jsonl:{line_no}: invalid JSON/UTF-8 ({e})")
                if not isinstance(record, dict):
                    raise EvidenceError(f"results.jsonl:{line_no}: report must be an object")
                if case is not None and record.get("benchCase") != case:
                    raise EvidenceError(f"results.jsonl:{line_no}: benchCase mismatch")
                name = record.get("selectBench")
                if not isinstance(name, str):
                    raise EvidenceError(f"results.jsonl:{line_no}: missing selectBench name")
                if seen_summary and name != "summary":
                    raise EvidenceError(f"results.jsonl:{line_no}: summary must be the last nonblank report")
                if record.get("runId") != run_id:
                    raise EvidenceError(f"results.jsonl:{line_no}: runId mismatch")
                if name == "summary":
                    seen_summary = True
                    summaries.append(record)
                    continue
                if name not in steps:
                    raise EvidenceError(f"results.jsonl:{line_no}: unknown report {name!r}")
                if name in reports:
                    raise EvidenceError(f"results.jsonl:{line_no}: duplicate report {name!r}")
                if type(record.get("pass")) is not bool:
                    raise EvidenceError(f"results.jsonl:{line_no}: pass must be boolean")
                reports[name] = record["pass"]
    except FileNotFoundError:
        raise EvidenceError("missing file: results.jsonl")
    except OSError as e:
        raise EvidenceError(f"results.jsonl: cannot read ({e})")
    missing = [name for name in steps if name not in reports]
    if missing:
        raise EvidenceError("results.jsonl: missing reports: " + ", ".join(missing))
    if not summaries:
        raise EvidenceError("results.jsonl: missing summary")
    if len(summaries) != 1:
        raise EvidenceError("results.jsonl: expected exactly one summary")
    summary = summaries[0]
    passed = sum(reports.values())
    failed = len(reports) - passed
    if type(summary.get("passed")) is not int or summary["passed"] != passed:
        raise EvidenceError("results.jsonl: summary passed count does not agree with reports")
    if type(summary.get("failed")) is not int or summary["failed"] != failed:
        raise EvidenceError("results.jsonl: summary failed count does not agree with reports")
    if failed:
        raise EvidenceError(f"results.jsonl: {failed} report(s) failed")


def validate(out_dir: Path, manifest_path: Path, run_id: str) -> None:
    if not run_id or run_id.strip() != run_id:
        raise EvidenceError("run-id must be nonempty and trimmed")
    steps, screenshots = load_manifest(manifest_path)
    case = read_json(manifest_path, MAX_MANIFEST).get("benchCase")
    if case is not None:
        if case != "selectA10":
            raise EvidenceError("manifest: unsupported benchCase")
        build = require_object(read_json(out_dir / "build.json", MAX_BUILD), "build.json")
        if build.get("benchCase") != case:
            raise EvidenceError("build.json: benchCase mismatch")
    validate_results(out_dir / "results.jsonl", steps, run_id, case)
    validate_build(out_dir / "build.json", run_id)
    validate_temps(out_dir / "temps.txt")
    for name in screenshots:
        validate_png(out_dir / name)
    # An exact screenshot manifest makes an accidental omission/addition visible.
    try:
        present = {p.name for p in out_dir.iterdir() if p.is_file() and p.suffix.lower() == ".png"}
    except OSError as e:
        raise EvidenceError(f"cannot list evidence directory ({e})")
    expected = set(screenshots)
    if present != expected:
        missing, extra = sorted(expected - present), sorted(present - expected)
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if extra:
            details.append("unexpected=" + ",".join(extra))
        raise EvidenceError("screenshot set mismatch: " + "; ".join(details))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args(argv)
    try:
        validate(args.out_dir, args.manifest, args.run_id)
    except EvidenceError as e:
        print(f"INVALID: {e}", file=sys.stderr)
        return 1
    print("VALID: evidence files are internally consistent and complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
