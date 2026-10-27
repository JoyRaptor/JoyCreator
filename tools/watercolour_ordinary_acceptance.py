"""Offline report checks only: this program neither runs ADB nor establishes GPU execution."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import re
import struct

CASE = "recordedWatercolourOrdinaryProbe"
PHASES = ["add", "translate_37_minus19", "tint", "undo_tint", "undo_move", "redo_move", "redo_tint"]


class InvalidReport(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidReport(message)


def integer(value, lo, hi):
    return type(value) is int and lo <= value <= hi


def number(value, lo, hi):
    return (type(value) is int and lo <= value <= hi) or (
        type(value) is float and math.isfinite(value) and lo <= value <= hi)


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"duplicate JSON key: {key}")
            result[key] = value
        return result

    def bad_constant(value):
        raise InvalidReport(f"nonfinite JSON constant: {value}")

    def decimal(value):
        parsed = float(value)
        require(math.isfinite(parsed), "nonfinite JSON number")
        return parsed

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=bad_constant, parse_float=decimal)
    except (ValueError, TypeError) as exc:
        raise InvalidReport(str(exc)) from exc


def read_capture(text, brush, run=1):
    """Accept one JSON report or a fresh fenced log with every selected chunk exactly once."""
    require(integer(run, 1, 20), "invalid requested run")
    require(len(text.encode("utf-8")) <= 8_000_000, "capture too large")
    require("FATAL EXCEPTION" not in text, "fatal exception in capture")
    documents = []
    try:
        whole = strict_json(text)
    except InvalidReport:
        whole = None
    if isinstance(whole, dict):
        documents = [whole]
    else:
        for line in text.splitlines():
            start = line.find("{")
            if start < 0:
                continue
            # A selected malformed line is an error, never quietly repaired or skipped.
            try:
                value = strict_json(line[start:])
            except InvalidReport:
                if CASE in line or "recordedProbeChunk" in line:
                    raise
                continue
            if isinstance(value, dict):
                documents.append(value)
    direct, chunks, total = [], {}, None
    for value in documents:
        if value.get("case") != CASE or value.get("brush") != brush or value.get("run") != run:
            continue
        require(type(value.get("run")) is int, "invalid report/chunk run type")
        if value.get("recordedProbeChunk") is True:
            require(not direct, "mixed direct and chunked reports")
            count, part = value.get("parts"), value.get("part")
            require(integer(count, 1, 2048), "invalid chunk count")
            require(integer(part, 0, count - 1), "invalid chunk index")
            require(total is None or total == count, "conflicting chunk counts")
            require(part not in chunks, "duplicate chunk; capture may contain multiple launches")
            require(isinstance(value.get("dataBase64"), str), "missing chunk data")
            try:
                data = base64.b64decode(value["dataBase64"], validate=True)
            except ValueError as exc:
                raise InvalidReport("invalid base64") from exc
            require(0 < len(data) <= 1000, "invalid chunk byte length")
            chunks[part], total = data, count
        else:
            require(not chunks, "mixed direct and chunked reports")
            direct.append(value)
    if chunks:
        require(len(chunks) == total, "missing report chunks")
        try:
            # UTF8 sequences may straddle chunk boundaries; decode only AFTER byte assembly.
            report = strict_json(b"".join(chunks[i] for i in range(total)).decode("utf-8"))
        except UnicodeError as exc:
            raise InvalidReport("invalid report UTF8") from exc
    else:
        require(len(direct) == 1, "need exactly one selected report")
        report = direct[0]
    validate_report(report, brush, run)
    return report


def validate_report(report, brush, run=1):
    require(isinstance(report, dict), "report must be an object")
    require(report.get("case") == CASE and report.get("brush") == brush, "wrong case/brush")
    require(integer(report.get("run"), 1, 20) and report["run"] == run, "wrong run")
    require(report.get("probe") == "watercolourOrdinary", "cache or other probe is not ordinary proof")
    require(report.get("passed") is True and report.get("phase") == "complete", "probe not complete/pass")
    require("failure" not in report, "passing report also contains failure")
    require(type(report.get("engineReleaseGlError")) is int and report["engineReleaseGlError"] == 0, "release GL error")
    require(report.get("translationPixels") == [37, -19], "wrong translation")
    require(type(report.get("initialUndoDepth")) is int and report["initialUndoDepth"] == 1, "wrong initial history depth")
    require(type(report.get("finalUndoDepth")) is int and report["finalUndoDepth"] == 3, "wrong final history depth")
    union = report.get("tileUnion")
    require(integer(union, 1, 128), "invalid tile union")
    rows = report.get("tileChecks")
    require(isinstance(rows, list) and len(rows) == 7, "need all seven pixel/history rows")
    previous = 0
    for phase, row in zip(PHASES, rows):
        require(isinstance(row, dict) and row.get("phase") == phase, "missing/reordered/duplicate phase")
        require(row.get("exactRgba8") is True and row.get("transparentTilesIncluded") is True, "partial pixel comparison")
        count = row.get("comparedTiles")
        require(integer(count, max(1, previous), union), "invalid/decreasing tile comparison union")
        previous = count
    require(previous == union, "final row omits union tiles")
    expected, actual = report.get("tintControlChangedPixels"), report.get("tintActualChangedPixels")
    require(integer(expected, 1, union * 256 * 256) and integer(actual, 1, union * 256 * 256), "vacuous/invalid tint")
    require(expected == actual, "actual tint differs from validated control")

    fixture = report.get("fixture")
    require(isinstance(fixture, dict), "missing fixture metadata")
    require(fixture.get("fixtureSourceId") == brush, "fixture source is not selected brush")
    require(type(fixture.get("fixtureSeed")) is int and fixture["fixtureSeed"] == 78234, "wrong fixture seed")
    require(type(fixture.get("fixtureRawSamples")) is int and fixture["fixtureRawSamples"] == 24, "wrong fixture trace")
    size = fixture.get("fixtureSize")
    require(number(size, 1, 300), "invalid fixture size")
    require(number(fixture.get("fixtureGrain"), 0, 0) and number(fixture.get("fixturePaperCut"), 0, 0), "fixture is not isolated from canvas grain/paper")
    operator = fixture.get("fixtureOperator")
    require(operator in ("normal", "multiply"), "unsupported fixture operator")
    encoded = report.get("fixtureFrozenBrushJson")
    require(isinstance(encoded, str) and len(encoded) <= 1_000_000, "missing/oversized frozen brush JSON")
    digest = report.get("fixtureFrozenBrushSha256")
    require(isinstance(digest, str) and hashlib.sha256(encoded.encode("utf-8")).hexdigest() == digest.lower(), "frozen brush hash mismatch")
    frozen = strict_json(encoded)
    require(isinstance(frozen, dict), "frozen brush must be an object")
    require(frozen.get("id") == fixture.get("fixtureFrozenId") and isinstance(frozen.get("id"), str)
            and bool(frozen["id"]), "frozen identity mismatch")
    require(frozen.get("engine") == "watercolour" and frozen.get("blend") == operator, "frozen engine/operator mismatch")
    wc, generic = frozen.get("watercolour"), frozen.get("size")
    require(isinstance(wc, dict) and isinstance(generic, dict), "missing frozen runtime settings")
    require(number(wc.get("size"), 1, 300) and wc["size"] == size, "frozen watercolor size mismatch")
    require(number(generic.get("base"), 1, 300), "invalid frozen generic size")
    require(struct.pack("<f", generic["base"]) == struct.pack("<f", size), "generic knob disagrees with fixture size")
    # Frozen BrushJson currently encodes defaults; absence cannot imply disabled grain/paper.
    require(number(wc.get("grain"), 0, 0) and number(wc.get("paperCut"), 0, 0), "frozen grain/paper mismatch")
    changes = fixture.get("fixtureOverrides")
    require(isinstance(changes, list) and len(changes) <= 4, "invalid override list")
    seen = set()
    for change in changes:
        require(isinstance(change, dict), "invalid override entry")
        field, before, after = change.get("field"), change.get("before"), change.get("after")
        require(field in ("watercolour.grain", "watercolour.paperCut", "runtime.size", "blend") and field not in seen, "unknown/duplicate override")
        require(isinstance(before, str) and isinstance(after, str) and before != after, "unreported/invented override")
        seen.add(field)
        if field == "blend":
            require(before in ("normal", "multiply") and after == operator and before != after, "invalid operator override")
        else:
            try:
                a, b = float(before), float(after)
            except ValueError as exc:
                raise InvalidReport("invalid numeric override") from exc
            require(math.isfinite(a) and math.isfinite(b) and a != b, "invalid/no-op numeric override")
            if field == "runtime.size":
                require(a > 300 and b == 300 and size == 300, "invalid size cap override")
            else:
                require(0 < a <= 1 and b == 0, "invalid grain/paper override")
    return report


def validate_receipt(receipt):
    """Optional host receipt schema; validates supplied facts, never obtains them from a phone."""
    require(isinstance(receipt, dict), "receipt must be an object")
    require(isinstance(receipt.get("sourceRevision"), str) and re.fullmatch(r"[0-9a-fA-F]{40}", receipt["sourceRevision"]), "missing exact source SHA")
    apk = receipt.get("apkSha256")
    require(isinstance(apk, str) and re.fullmatch(r"[0-9a-fA-F]{64}", apk), "missing APK hash")
    require(all(isinstance(receipt.get(field), str) and receipt[field].lower() == apk.lower()
                for field in ("installedBeforeSha256", "installedAfterSha256")), "installed APK mismatch")
    require(receipt.get("model") == "SM-N960U" and type(receipt.get("api")) is int and receipt["api"] == 29, "wrong sandbox")
    before, after = receipt.get("savedBefore"), receipt.get("savedAfter")
    require(isinstance(before, dict) and bool(before) and before == after, "saved-root manifest changed/missing")
    required = receipt.get("requiredSavedPaths")
    require(isinstance(required, list) and len(required) >= 2 and all(isinstance(p, str) for p in required)
            and len(set(required)) == len(required), "missing Working/backup requirements")
    require(all(isinstance(p, str) and p in before for p in required), "required saved paths absent")
    require(any(p.endswith("current.joybrush") for p in required) and
            any(p.endswith("current.joybrush.bak") for p in required), "Working and backup must be named")
    for manifest in (before, after):
        for path, entry in manifest.items():
            require(isinstance(path, str) and isinstance(entry, dict) and integer(entry.get("size"), 0, 2**63 - 1), "invalid saved file entry")
            require(isinstance(entry.get("sha256"), str) and re.fullmatch(r"[0-9a-fA-F]{64}", entry["sha256"]), "invalid saved file hash")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="one JSON report or one fresh fenced log")
    parser.add_argument("--brush", required=True)
    parser.add_argument("--run", type=int, default=1)
    parser.add_argument("--receipt", type=Path, help="optional independently captured host receipt")
    args = parser.parse_args()
    try:
        read_capture(args.capture.read_text(encoding="utf-8"), args.brush, args.run)
        if args.receipt:
            validate_receipt(strict_json(args.receipt.read_text(encoding="utf-8")))
    except (InvalidReport, OSError, UnicodeError) as exc:
        parser.exit(1, f"REJECT: {exc}\n")
    print("PASS: offline report invariants" + (" and supplied host receipt" if args.receipt else " (device receipt not checked)")
          + "; no GPU execution, owner appearance or View recovery claim")


if __name__ == "__main__":
    main()
