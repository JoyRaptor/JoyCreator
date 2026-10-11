"""CPU slice summary for saved Perfetto slice-table CSV exports (JB-NOW-07).

Reads a saved CSV text file with required columns ``name,ts,dur,track_id``
(``ts``/``dur`` integer nanoseconds) plus any optional extra columns, and
emits a deterministic JSON report to stdout.

Input contract is a slice-table export, NOT raw Perfetto binary or assumed
adb text. Only rows whose ``name`` starts with ``JB.media.`` are treated as
media evidence; unknown future ``JB.media.*`` stage names are preserved as
their own groups. Actual existing labels (see MediaLayerEngine.kt and
MediaTrace.kt) include ``JB.media.dry.upload``, ``JB.media.dry.delta``,
``JB.media.dry.apply``, ``JB.media.dry.copy`` and ``JB.media.look.render``.

Grouping is by (name, track_id). Per group the report gives count, sum, min,
median, p95 and max duration in milliseconds. p95 is nearest-rank
``ceil(0.95*n)``, NOT interpolation. Median of an even sample count is the
mean of the two middle values.

Zero-duration rows are valid and included in aggregates, with a separate
zero count. Negative durations (including unfinished ``-1``), invalid fields
and malformed rows are reported separately and EXCLUDED from aggregates.

The tool never sums nested/overlapping slices into a frame total, never
computes input-to-visible latency, and never calls CPU submission timings
GPU execution: MediaTrace sections bracket GL submission calls only. Empty
captures explicitly lack evidence. No capture/device control, no trace
changes, no adb invocation.

Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
from pathlib import Path

MEDIA_PREFIX = "JB.media."

KNOWN_MEDIA_LABELS = (
    "JB.media.dry.upload",
    "JB.media.dry.delta",
    "JB.media.dry.apply",
    "JB.media.dry.copy",
    "JB.media.look.render",
)

REQUIRED_COLUMNS = ("name", "ts", "dur", "track_id")

NS_PER_MS = 1_000_000


class TraceReportError(ValueError):
    """Raised for unreadable input or a header that violates the contract."""


def _median_ms(sorted_ms: list[float]) -> float:
    n = len(sorted_ms)
    if n == 0:
        raise ValueError("median of empty sample")
    mid = n // 2
    if n % 2 == 1:
        return sorted_ms[mid]
    return (sorted_ms[mid - 1] + sorted_ms[mid]) / 2.0


def _p95_ms(sorted_ms: list[float]) -> float:
    n = len(sorted_ms)
    if n == 0:
        raise ValueError("p95 of empty sample")
    # Nearest rank: ceil(0.95 * n), 1-indexed.
    k = (95 * n + 99) // 100
    k = min(max(k, 1), n)
    return sorted_ms[k - 1]


def _parse_int_ns(raw: object, field: str) -> int:
    if raw is None:
        raise ValueError("%s is missing" % field)
    text = str(raw).strip()
    if text == "":
        raise ValueError("%s is empty" % field)
    try:
        value = int(text, 10)
    except ValueError:
        raise ValueError("%s is not an integer: %r" % (field, str(raw)[:80]))
    return value


def build_report(input_path: str | Path) -> dict:
    """Parse the slice-table CSV at ``input_path`` and return a JSON-able dict."""
    path = Path(input_path)
    if not path.is_file():
        raise FileNotFoundError("input CSV not found: %s" % path)
    raw_bytes = path.read_bytes()
    try:
        text = raw_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise TraceReportError("input is not UTF-8 text: %s" % exc)

    if text.strip() == "":
        raise TraceReportError(
            "missing header: expected CSV columns %s" % ",".join(REQUIRED_COLUMNS)
        )

    # Preserve the actual CSV text stream (do not splitlines) so quoted
    # newlines stay inside their record. strict=True surfaces malformed
    # quoting (unclosed quotes, stray quotes) as csv.Error instead of
    # silently accepting the row as timing evidence.
    stream = io.StringIO(text)
    reader = csv.DictReader(stream, strict=True)
    try:
        fieldnames = reader.fieldnames
    except csv.Error as exc:
        raise TraceReportError(
            "csv parse error in header: %s" % (str(exc)[:200])
        )
    if fieldnames is None:
        raise TraceReportError(
            "missing header: expected CSV columns %s" % ",".join(REQUIRED_COLUMNS)
        )
    # Tolerate surrounding whitespace (and BOM, already stripped) in headers.
    normalized_fields = [
        name.strip() if isinstance(name, str) else name for name in reader.fieldnames
    ]
    missing = [c for c in REQUIRED_COLUMNS if c not in normalized_fields]
    if missing:
        raise TraceReportError(
            "missing required columns %s; found %s"
            % (",".join(missing), ",".join(str(f) for f in reader.fieldnames))
        )
    # Map normalized column name -> actual DictReader key.
    key_of: dict[str, str] = {}
    for actual in reader.fieldnames:
        if isinstance(actual, str) and actual.strip() in REQUIRED_COLUMNS:
            key_of.setdefault(actual.strip(), actual)

    valid_media: list[dict] = []
    all_valid_ts: list[int] = []
    all_valid_ends: list[int] = []
    negative_rows: list[dict] = []
    invalid_rows: list[dict] = []
    malformed_rows: list[dict] = []
    unrelated_count = 0
    unrelated_names: set[str] = set()
    zero_duration_count = 0
    data_rows = 0
    csv_error: str | None = None

    # Iterate record-by-record so a csv.Error on one record becomes
    # malformed-record evidence while prior valid rows are retained.
    # reader.line_num is the physical line count (end line for multiline
    # quoted records), which keeps provenance accurate after embedded
    # newlines. DictReader already skips truly empty lines (row == []).
    while True:
        try:
            row = next(reader)
        except StopIteration:
            break
        except csv.Error as exc:
            csv_error = str(exc)[:200]
            # DictReader updates its line_num only after a successful next(); the underlying reader advances on errors too.
            err_line = reader.reader.line_num or 1
            data_rows += 1
            malformed_rows.append(
                {"line": err_line, "detail": "csv parse error: %s" % csv_error[:160]}
            )
            continue
        lineno = reader.line_num
        # Skip fully blank lines without counting them as data.
        if row is None:
            malformed_rows.append({"line": lineno, "detail": "unparseable row"})
            continue
        values = list(row.values())
        if all(v is None or (isinstance(v, str) and v.strip() == "") for v in values):
            continue
        data_rows += 1
        # Extra fields beyond the header land under key None.
        if None in row:
            malformed_rows.append(
                {
                    "line": lineno,
                    "detail": "field count does not match header",
                }
            )
            continue
        try:
            name_raw = row.get(key_of["name"])
            ts_raw = row.get(key_of["ts"])
            dur_raw = row.get(key_of["dur"])
            track_raw = row.get(key_of["track_id"])
            if name_raw is None or ts_raw is None or dur_raw is None or track_raw is None:
                malformed_rows.append(
                    {"line": lineno, "detail": "short row: fewer fields than header"}
                )
                continue
            name = str(name_raw).strip()
            track_id = str(track_raw).strip()
            if name == "" or track_id == "":
                invalid_rows.append(
                    {
                        "line": lineno,
                        "name": str(name_raw)[:120],
                        "track_id": str(track_raw)[:120],
                        "reason": "empty name or track_id",
                    }
                )
                continue
            try:
                ts = _parse_int_ns(ts_raw, "ts")
            except ValueError as exc:
                invalid_rows.append(
                    {
                        "line": lineno,
                        "name": name[:120],
                        "track_id": track_id[:120],
                        "reason": str(exc)[:160],
                    }
                )
                continue
            if ts < 0:
                invalid_rows.append(
                    {
                        "line": lineno,
                        "name": name[:120],
                        "track_id": track_id[:120],
                        "reason": "negative ts %d" % ts,
                    }
                )
                continue
            try:
                dur = _parse_int_ns(dur_raw, "dur")
            except ValueError as exc:
                invalid_rows.append(
                    {
                        "line": lineno,
                        "name": name[:120],
                        "track_id": track_id[:120],
                        "reason": str(exc)[:160],
                    }
                )
                continue
            # ts is valid here regardless of name/dur outcome: keep for extent.
            all_valid_ts.append(ts)
            if not name.startswith(MEDIA_PREFIX):
                unrelated_count += 1
                if len(unrelated_names) < 200:
                    unrelated_names.add(name[:200])
                if dur >= 0:
                    all_valid_ends.append(ts + dur)
                continue
            if dur < 0:
                negative_rows.append(
                    {
                        "line": lineno,
                        "name": name[:200],
                        "track_id": track_id[:120],
                        "dur_ns": dur,
                        "note": "excluded from aggregates; -1 usually means unfinished slice",
                    }
                )
                continue
            # Valid media row (dur == 0 included in aggregates).
            if dur == 0:
                zero_duration_count += 1
            all_valid_ends.append(ts + dur)
            valid_media.append(
                {"line": lineno, "name": name, "track_id": track_id, "ts": ts, "dur": dur}
            )
        except (AttributeError, TypeError) as exc:  # defensive: odd csv shapes
            malformed_rows.append({"line": lineno, "detail": str(exc)[:160]})
            continue

    # Group valid media rows by (name, track_id); deterministic order.
    buckets: dict[tuple[str, str], list[int]] = {}
    for item in valid_media:
        buckets.setdefault((item["name"], item["track_id"]), []).append(item["dur"])

    groups: list[dict] = []
    for (name, track_id) in sorted(buckets):
        durs_ns = sorted(buckets[(name, track_id)])
        durs_ms = [d / NS_PER_MS for d in durs_ns]
        groups.append(
            {
                "name": name,
                "track_id": track_id,
                "count": len(durs_ns),
                "sum_ms": sum(durs_ms),
                "min_ms": durs_ms[0],
                "median_ms": _median_ms(durs_ms),
                "p95_ms": _p95_ms(durs_ms),
                "max_ms": durs_ms[-1],
                "zero_duration_rows": sum(1 for d in durs_ns if d == 0),
            }
        )

    unknown_labels = sorted({g["name"] for g in groups if g["name"] not in KNOWN_MEDIA_LABELS})

    if all_valid_ts:
        input_extent: dict | None = {
            "min_ts_ns": min(all_valid_ts),
            "max_ts_ns": max(all_valid_ts),
            "max_end_ns": max(all_valid_ends) if all_valid_ends else max(all_valid_ts),
            "extent_ns": (max(all_valid_ends) if all_valid_ends else max(all_valid_ts))
            - min(all_valid_ts),
        }
    else:
        input_extent = None
    media_ts = [m["ts"] for m in valid_media]
    media_ends = [m["ts"] + m["dur"] for m in valid_media]
    if media_ts:
        media_extent: dict | None = {
            "min_ts_ns": min(media_ts),
            "max_ts_ns": max(media_ts),
            "max_end_ns": max(media_ends),
            "extent_ns": max(media_ends) - min(media_ts),
        }
    else:
        media_extent = None

    warnings: list[str] = []
    if unknown_labels:
        warnings.append(
            "unknown JB.media.* labels preserved as own groups: %s" % ", ".join(unknown_labels)
        )
    if negative_rows:
        warnings.append(
            "%d negative-duration row(s) excluded from aggregates; "
            "-1 usually means an unfinished slice" % len(negative_rows)
        )
    if invalid_rows:
        warnings.append("%d invalid-field row(s) excluded from aggregates" % len(invalid_rows))
    if malformed_rows:
        warnings.append("%d malformed row(s) excluded from aggregates" % len(malformed_rows))
    if csv_error:
        warnings.append("csv parse error encountered: %s" % csv_error)
    warnings.append(
        "overlapping/nested slices are reported per slice only; they are never "
        "summed into a frame total"
    )
    if not valid_media:
        warnings.append(
            "empty capture (or no JB.media.* rows): lacks evidence of media cost; "
            "not proof of no cost"
        )

    has_evidence = bool(valid_media)
    report = {
        "tool": "media_trace_report",
        "contract": (
            "slice-table CSV export with required columns name,ts,dur,track_id "
            "(ts/dur integer nanoseconds); extra columns ignored; not raw "
            "Perfetto binary or assumed adb text"
        ),
        "input": {
            "path": str(path),
            "bytes": len(raw_bytes),
            "data_rows": data_rows,
        },
        "provenance_note": (
            "input path/bytes/rows above identify the exact file summarized; "
            "synthetic test fixtures are labeled as such in tests and are not "
            "physical device evidence"
        ),
        "media_prefix": MEDIA_PREFIX,
        "known_media_labels": list(KNOWN_MEDIA_LABELS),
        "groups": groups,
        "unknown_media_labels": unknown_labels,
        "totals": {
            "data_rows": data_rows,
            "valid_media_rows": len(valid_media),
            "unrelated_rows": unrelated_count,
            "zero_duration_rows": zero_duration_count,
            "negative_duration_rows": len(negative_rows),
            "invalid_rows": len(invalid_rows),
            "malformed_rows": len(malformed_rows),
        },
        "negative_duration_rows": negative_rows,
        "invalid_rows": invalid_rows,
        "malformed_rows": malformed_rows,
        "unrelated_names": sorted(unrelated_names),
        "input_extent_ns": input_extent,
        "media_extent_ns": media_extent,
        "timing_units": "CPU slice durations in milliseconds (dur_ns/1e6), per (name, track_id) group",
        "timing_scope": (
            "CPU slice durations only: MediaTrace sections bracket GL command "
            "submission, not GPU execution; GPU execution and visual/input-to-"
            "visible latency are unmeasured by this report"
        ),
        "frame_total_ms": None,
        "frame_total_note": (
            "not computed: nested/overlapping slices must not be summed into a "
            "frame total"
        ),
        "input_to_visible_latency_ms": None,
        "input_to_visible_latency_note": "not computed from slice durations",
        "gpu_execution_ms": None,
        "gpu_note": "unmeasured: CPU submission timings are not GPU execution timings",
        "warnings": warnings,
        "has_media_evidence": has_evidence,
        "evidence": (
            "observed %d valid JB.media.* slice(s) across %d group(s)"
            % (len(valid_media), len(groups))
            if has_evidence
            else "no JB.media.* slices observed; empty capture lacks evidence, not proof of no cost"
        ),
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Summarize a saved Perfetto slice-table CSV (columns "
            "name,ts,dur,track_id) into deterministic JSON on stdout. "
            "CPU slice durations only; GPU/visual latency unmeasured."
        )
    )
    parser.add_argument("csv_path", help="saved slice-table CSV file (UTF-8)")
    args = parser.parse_args(argv)
    try:
        report = build_report(args.csv_path)
    except FileNotFoundError as exc:
        print("media_trace_report: error: %s" % exc, file=sys.stderr)
        return 2
    except TraceReportError as exc:
        print("media_trace_report: error: %s" % exc, file=sys.stderr)
        return 1
    except OSError as exc:
        print("media_trace_report: error: cannot read input: %s" % exc, file=sys.stderr)
        return 1
    json.dump(report, sys.stdout, sort_keys=True, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
