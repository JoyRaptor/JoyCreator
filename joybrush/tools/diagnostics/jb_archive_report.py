#!/usr/bin/env python3
"""Offline JoyBrush project archive (.joybrush) inspection tool.

Read-only, Python standard library only. One saved archive path in, JSON on
stdout. Inspects the ZIP directory and bounded metadata; it never extracts
files, never decompresses every tile, never allocates from a declared ZIP
size, and never repairs, resaves, or migrates anything.

Results are INSPECTION, not proof the JoyBrush app can load, render, or
export the archive. Exact loader behaviour is a frontier/app review gate.

Verified-against-source constants (do NOT change without re-reading source):
  JB_MIMETYPE / entry names / tile layouts:
    joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt
      JB_MIMETYPE ........... line 24  ("application/x-joybrush")
      TILE_BYTES ............ line 32  (TILE_SIZE * TILE_SIZE * 4)
      MIMETYPE_NAME ......... line 112 ("mimetype", STORED, FIRST entry)
      DOCUMENT_NAME ......... line 113 ("document.json", deflate)
      THUMBNAIL_NAME ........ line 114 ("thumbnail.png")
      LAYERS_DIR ............ line 115 ("layers")
      STROKES_NAME .......... line 116 ("strokes.jbs")
      TILE_EXT .............. line 117 (".rgba")
      Entry table ........... lines 76-82
      unsafeReason .......... lines 636-660 (mirrored by suspicious_reason())
  TILE_SIZE = 256:
    joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/Tiles.kt:11
    (SIZE = 256), referenced as TILE_SIZE in
    joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt:21
    => TILE_BYTES = 256 * 256 * 4 = 262144.
  Media stores:
    joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaStores.kt
      ALL = ["p0", "p1", "paper", "w0", "w1"] (line 19)
      EXT = ".f16" (line 25), BYTES_PER_TEXEL = 8 (line 28)
      tileBytes(tile) = tile * tile * 8 (line 31)
    => one media store tile at rest = 256 * 256 * 8 = 524288 bytes.
  Document schema:
    joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt
      DOC_FORMAT = "joybrush.document" (line 6), DOC_VERSION = 8 (line 14)
      JbDocument { format, version, id, name, paper, boards[], layers[] }
      Layer { id, ..., cels[] }, Cel { id, tiles[], strokesFile?, floatTiles[] }
"""

import argparse
import json
import os
import re
import sys
import zipfile

# ---------------------------------------------------------------- constants
# Every value below was read out of the sources named in the module docstring.
JB_MIMETYPE = "application/x-joybrush"
MIMETYPE_NAME = "mimetype"
DOCUMENT_NAME = "document.json"
THUMBNAIL_NAME = "thumbnail.png"
LAYERS_DIR = "layers"
STROKES_NAME = "strokes.jbs"
TILE_EXT = ".rgba"

TILE_SIZE_PX = 256
TILE_BYTES = TILE_SIZE_PX * TILE_SIZE_PX * 4  # 262144, RGBA8 paint tile
MEDIA_STORES = ("p0", "p1", "paper", "w0", "w1")
MEDIA_EXT = ".f16"
MEDIA_TILE_BYTES = TILE_SIZE_PX * TILE_SIZE_PX * 8  # 524288, RGBA16F store tile

DOC_FORMAT = "joybrush.document"
DOC_VERSION = 8

# Inspection caps chosen HERE, never from numbers the archive declares.
DOCUMENT_READ_CAP = 8 * 1024 * 1024  # per contract: parse document.json only under 8MiB
MIMETYPE_READ_CAP = 65  # loader bound is 64 (JbArchive.kt:125); +1 detects overlong
MAX_NAME_CHARS = 512  # same bound the loader enforces (JbArchive.kt:124)
_CHUNK = 8 * 1024

TOOL_NAME = "jb_archive_report"
INSPECTION_NOTE = (
    "Static inspection of the ZIP directory and bounded metadata only; "
    "not proof the JoyBrush app can load, render, or export this archive. "
    "Exact loader behaviour remains a frontier/app review gate."
)

_TILE_PART = re.compile(r"^[+-]?[0-9]+$")
_KNOWN_SUFFIXES = {".rgba", ".f16", ".jbs", ".json", ".png"}
_METHOD_NAMES = {0: "STORED", 8: "DEFLATED", 12: "BZIP2", 14: "LZMA"}


# ---------------------------------------------------------------- helpers
def method_name(code):
    """Human name for a ZIP compression method code; never raises."""
    return _METHOD_NAMES.get(code, "METHOD_%d" % code)


def suspicious_reason(name):
    """Why an entry name is suspicious, or None if it looks ordinary.

    Mirrors JbArchive.unsafeReason (JbArchive.kt:636-660): refuses empty
    names, overlong names, control characters, backslashes, absolute paths,
    colons, empty segments, '.'/'..' segments, and the Windows trailing
    dot/space trick. This tool only REPORTS; the loader is what refuses.
    """
    if name == "":
        return "it has no name"
    if len(name) > MAX_NAME_CHARS:
        return "its name is %d characters long" % len(name)
    for ch in name:
        if ord(ch) < 0x20 or ord(ch) == 0x7F:
            return "its name contains a control character"
    if "\\" in name:
        return "its name contains a backslash, which is a path separator on Windows"
    if name.startswith("/"):
        return "its name is an absolute path"
    if ":" in name:
        return "its name contains a colon, which is a drive letter or an NTFS stream"
    # One trailing slash is a directory marker, which is harmless.
    path = name[:-1] if name.endswith("/") else name
    if path == "":
        return "its name is nothing but a separator"
    for segment in path.split("/"):
        if segment == "":
            return "its name has an empty path segment"
        if segment == "." or segment == "..":
            return 'its name has a "%s" segment, which climbs out of the folder' % segment
        trimmed = segment.rstrip(" .")
        if trimmed != segment and trimmed in ("", ".", ".."):
            return (
                'its name has a "%s" segment, which Windows would read as "%s"'
                % (segment, trimmed)
            )
    return None


def _is_tile_key(key):
    """'3_-2' is a tile key; '3', 'banana', '3_4_5' are not (JbArchive.kt:615-619)."""
    left, sep, right = key.partition("_")
    if not sep:
        return False
    return bool(_TILE_PART.match(left)) and bool(_TILE_PART.match(right))


def classify_entry(name):
    """Classify an archive entry path. Returns (kind, detail dict).

    Kinds: directory, mimetype, document, thumbnail, paint_tile,
    media_tile, strokes, unknown. Shapes follow the JbArchive entry table
    (JbArchive.kt:76-82). Malformed layer paths stay 'unknown': the tool
    summarises rather than rejecting future payloads.
    """
    if name.endswith("/"):
        return ("directory", {})
    if name == MIMETYPE_NAME:
        return ("mimetype", {})
    if name == DOCUMENT_NAME:
        return ("document", {})
    if name == THUMBNAIL_NAME:
        return ("thumbnail", {})
    if name.startswith(LAYERS_DIR + "/"):
        parts = name.split("/")
        if len(parts) == 4 and parts[1] and parts[2]:
            leaf = parts[3]
            if leaf == STROKES_NAME:
                return ("strokes", {"layer": parts[1], "cel": parts[2]})
            if leaf.endswith(TILE_EXT):
                stem = leaf[: -len(TILE_EXT)]
                if stem and _is_tile_key(stem):
                    return (
                        "paint_tile",
                        {"layer": parts[1], "cel": parts[2], "key": stem},
                    )
            if leaf.endswith(MEDIA_EXT):
                stem = leaf[: -len(MEDIA_EXT)]
                dot = stem.rfind(".")
                if dot > 0:
                    key, store = stem[:dot], stem[dot + 1 :]
                    if store in MEDIA_STORES and _is_tile_key(key):
                        return (
                            "media_tile",
                            {
                                "layer": parts[1],
                                "cel": parts[2],
                                "key": key,
                                "store": store,
                            },
                        )
    return ("unknown", {})


def _read_bounded(zf, info, cap):
    """Stream one entry up to cap+1 bytes in fixed 8 KiB chunks.

    Never sizes an allocation from the archive's declared size. Returns
    (data, truncated): data holds at most cap bytes.
    """
    chunks = []
    total = 0
    truncated = False
    with zf.open(info, "r") as handle:
        while True:
            piece = handle.read(_CHUNK)
            if not piece:
                break
            total += len(piece)
            if total > cap:
                truncated = True
                break
            chunks.append(piece)
    return (b"".join(chunks), truncated)


def summarize_document(raw):
    """Summarise decoded document.json bytes without validating the document.

    Shows the declared version and board/layer/cel counts ONLY where the
    current schema (DocModel.kt: JbDocument/Layer/Cel) unambiguously defines
    them. Anything else -- unknown version, missing keys, wrong types --
    stays 'unknown', never 'invalid': this tool does not judge what the app
    loader accepts.
    """
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return {
            "status": "parse_error",
            "detail": "document.json is not UTF-8 text",
            "format": None,
            "version": None,
            "version_status": "unknown",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: document.json could not be decoded as text",
        }
    try:
        obj = json.loads(text)
    except ValueError as exc:
        return {
            "status": "parse_error",
            "detail": "document.json is not JSON: %s" % str(exc)[:160],
            "format": None,
            "version": None,
            "version_status": "unknown",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: document.json is not JSON",
        }
    if not isinstance(obj, dict):
        return {
            "status": "unknown_schema",
            "detail": "document root is not an object",
            "format": None,
            "version": None,
            "version_status": "unknown",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: unknown schema (root is not an object)",
        }
    fmt = obj.get("format")
    version = obj.get("version")
    if isinstance(version, bool) or not isinstance(version, int):
        return {
            "status": "unknown_schema",
            "detail": "version is absent or not an integer",
            "format": fmt if isinstance(fmt, str) else None,
            "version": None,
            "version_status": "missing" if version is None else "non_integer",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: version is absent or not an integer",
        }
    if version != DOC_VERSION:
        return {
            "status": "unknown_version",
            "detail": "version %d differs from this checkout's %d; left unknown, not judged"
            % (version, DOC_VERSION),
            "format": fmt if isinstance(fmt, str) else None,
            "version": version,
            "version_status": "unknown",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: unknown document version %d" % version,
        }
    boards = obj.get("boards")
    layers = obj.get("layers")
    if not isinstance(boards, list) or not isinstance(layers, list):
        return {
            "status": "unknown_schema",
            "detail": "current version but boards/layers are not both lists",
            "format": fmt if isinstance(fmt, str) else None,
            "version": version,
            "version_status": "current",
            "board_count": None,
            "layer_count": None,
            "cel_count": None,
            "counts_note": "withheld: boards/layers are not both lists",
        }
    cel_total = 0
    cels_known = True
    for layer in layers:
        if not isinstance(layer, dict) or not isinstance(layer.get("cels"), list):
            cels_known = False
            break
        cel_total += len(layer["cels"])
    return {
        "status": "parsed",
        "detail": None,
        "format": fmt if isinstance(fmt, str) else None,
        "version": version,
        "version_status": "current",
        "board_count": len(boards),
        "layer_count": len(layers),
        "cel_count": cel_total if cels_known else None,
        "counts_note": (
            "counts from current-schema boards[]/layers[]/cels[]"
            if cels_known
            else "board/layer counts from current schema; cel count withheld: a layer has no cels list"
        ),
    }


def _error_report(path, message, hint):
    return {
        "tool": TOOL_NAME,
        "inspection_only": True,
        "note": INSPECTION_NOTE,
        "archive": path,
        "error": message,
        "hint": hint,
    }


def inspect_archive(path):
    """Inspect one archive. Returns (report_dict, exit_code).

    Exit 0 with a report whenever the ZIP directory could be listed -- even
    with warnings. Nonzero with an actionable error object only when the
    path is unreadable or the file is not a readable ZIP.
    """
    if not os.path.exists(path):
        return (
            _error_report(
                path,
                "there is no file at %s" % path,
                "check the saved archive path and re-run; the tool reads the file as-is and never modifies it.",
            ),
            2,
        )
    if not os.path.isfile(path):
        return (
            _error_report(
                path,
                "%s is not a file" % path,
                "pass one saved .joybrush archive file path, not a directory.",
            ),
            2,
        )
    try:
        zf = zipfile.ZipFile(path, "r")  # read-only; never 'w'/'a', never extract
    except zipfile.BadZipFile as exc:
        return (
            _error_report(
                path,
                "%s cannot be read as a ZIP archive: %s" % (path, str(exc)[:160]),
                "the file may be truncated or half-copied; re-copy the saved .joybrush and retry. The tool made no changes.",
            ),
            3,
        )
    except OSError as exc:
        return (
            _error_report(
                path,
                "%s cannot be opened: %s" % (path, str(exc)[:160]),
                "check file permissions and that no other program holds an exclusive lock, then retry.",
            ),
            2,
        )

    with zf:
        try:
            infos = zf.infolist()
        except zipfile.BadZipFile as exc:
            return (
                _error_report(
                    path,
                    "%s has an unreadable ZIP directory: %s" % (path, str(exc)[:160]),
                    "the file may be truncated or half-copied; re-copy the saved .joybrush and retry. The tool made no changes.",
                ),
                3,
            )

        entries = []
        seen_counts = {}
        for index, info in enumerate(infos):
            kind, _detail = classify_entry(info.filename)
            entries.append(
                {
                    "index": index,
                    "name": info.filename,
                    "file_size": info.file_size,
                    "compress_size": info.compress_size,
                    "method": method_name(info.compress_type),
                    "method_code": info.compress_type,
                    "kind": kind,
                    "is_directory": info.is_dir(),
                }
            )
            seen_counts[info.filename] = seen_counts.get(info.filename, 0) + 1

        duplicates = [
            {"name": name, "count": count}
            for name, count in sorted(seen_counts.items())
            if count > 1
        ]
        suspicious = []
        for name in sorted(seen_counts):
            reason = suspicious_reason(name)
            if reason is not None:
                suspicious.append({"name": name, "reason": reason})

        total_file = sum(e["file_size"] for e in entries if not e["is_directory"])
        total_compress = sum(
            e["compress_size"] for e in entries if not e["is_directory"]
        )
        counts_by_suffix = {}
        counts_by_kind = {}
        methods = {}
        for entry in entries:
            counts_by_kind[entry["kind"]] = counts_by_kind.get(entry["kind"], 0) + 1
            methods[entry["method"]] = methods.get(entry["method"], 0) + 1
            if entry["is_directory"]:
                suffix = "<dir>"
            else:
                _root, ext = os.path.splitext(entry["name"])
                suffix = ext.lower() if ext else "<none>"
            counts_by_suffix[suffix] = counts_by_suffix.get(suffix, 0) + 1
        unknown_extensions = {
            suffix: count
            for suffix, count in sorted(counts_by_suffix.items())
            if suffix not in _KNOWN_SUFFIXES and suffix not in ("<dir>", "<none>")
        }

        warnings = []
        for dup in duplicates:
            warnings.append(
                'entry "%s" appears %d times; only the first is described below'
                % (dup["name"], dup["count"])
            )
        for item in suspicious:
            warnings.append(
                'suspicious entry name "%s": %s' % (item["name"], item["reason"])
            )

        # Mimetype: bounded metadata + bounded content peek only.
        mimetype_infos = [i for i in infos if i.filename == MIMETYPE_NAME]
        mimetype_report = {
            "present": bool(mimetype_infos),
            "expected": JB_MIMETYPE,
        }
        if mimetype_infos:
            first_mime = mimetype_infos[0]
            mimetype_report["first"] = bool(entries) and entries[0]["name"] == MIMETYPE_NAME
            mimetype_report["method"] = method_name(first_mime.compress_type)
            if first_mime.file_size > MIMETYPE_READ_CAP - 1:
                mimetype_report["matches_expected"] = False
                mimetype_report["byte_size"] = first_mime.file_size
                warnings.append(
                    "mimetype entry declares %d bytes (over the %d-byte bound); "
                    "content left unread" % (first_mime.file_size, MIMETYPE_READ_CAP - 1)
                )
            else:
                try:
                    mime_bytes, _ = _read_bounded(zf, first_mime, MIMETYPE_READ_CAP)
                except (RuntimeError, NotImplementedError, zipfile.BadZipFile) as exc:
                    mimetype_report["matches_expected"] = False
                    mimetype_report["byte_size"] = first_mime.file_size
                    warnings.append(
                        "mimetype entry could not be decompressed: %s" % str(exc)[:160]
                    )
                else:
                    try:
                        mime_text = mime_bytes.decode("ascii")
                    except UnicodeDecodeError:
                        mime_text = None
                    mimetype_report["matches_expected"] = mime_bytes == JB_MIMETYPE.encode(
                        "ascii"
                    )
                    mimetype_report["byte_size"] = len(mime_bytes)
                    if not mimetype_report["matches_expected"]:
                        shown = mime_text if mime_text is not None else "<non-ascii>"
                        warnings.append(
                            'mimetype content is %r (%d bytes), expected %r; '
                            "inspection note only" % (shown[:64], len(mime_bytes), JB_MIMETYPE)
                        )
            if not mimetype_report["first"]:
                warnings.append("first entry is not mimetype; current writer puts mimetype first")
            if mimetype_report["method"] != "STORED":
                warnings.append(
                    "mimetype entry uses %s; current writer uses STORED" % mimetype_report["method"]
                )
        else:
            warnings.append("archive has no mimetype entry")

        # Document JSON: explicit 8MiB cap, streamed, never from declared size.
        document_infos = [i for i in infos if i.filename == DOCUMENT_NAME]
        document_report = None
        if not document_infos:
            document_report = {
                "status": "missing",
                "detail": "archive has no document.json entry",
                "format": None,
                "version": None,
                "version_status": "unknown",
                "board_count": None,
                "layer_count": None,
                "cel_count": None,
                "counts_note": "withheld: document.json is absent",
                "byte_size": None,
            }
            warnings.append("archive has no document.json entry; document counts unknown")
        else:
            doc_info = document_infos[0]
            if doc_info.file_size > DOCUMENT_READ_CAP:
                document_report = {
                    "status": "over_cap",
                    "detail": "document.json declares %d bytes, over the %d-byte inspection cap; left unread"
                    % (doc_info.file_size, DOCUMENT_READ_CAP),
                    "format": None,
                    "version": None,
                    "version_status": "unknown",
                    "board_count": None,
                    "layer_count": None,
                    "cel_count": None,
                    "counts_note": "withheld: document.json is over the 8MiB inspection cap",
                    "byte_size": doc_info.file_size,
                }
                warnings.append(
                    "document.json declares %d bytes, over the 8MiB inspection cap; left unread"
                    % doc_info.file_size
                )
            else:
                try:
                    doc_bytes, truncated = _read_bounded(zf, doc_info, DOCUMENT_READ_CAP)
                except (RuntimeError, NotImplementedError, zipfile.BadZipFile) as exc:
                    document_report = {
                        "status": "unreadable_entry",
                        "detail": "document.json could not be decompressed: %s" % str(exc)[:160],
                        "format": None,
                        "version": None,
                        "version_status": "unknown",
                        "board_count": None,
                        "layer_count": None,
                        "cel_count": None,
                        "counts_note": "withheld: document.json could not be decompressed",
                        "byte_size": doc_info.file_size,
                    }
                    warnings.append("document.json could not be decompressed; document counts unknown")
                else:
                    if truncated:
                        document_report = {
                            "status": "over_cap",
                            "detail": "document.json streams past the %d-byte inspection cap; left unread"
                            % DOCUMENT_READ_CAP,
                            "format": None,
                            "version": None,
                            "version_status": "unknown",
                            "board_count": None,
                            "layer_count": None,
                            "cel_count": None,
                            "counts_note": "withheld: document.json is over the 8MiB inspection cap",
                            "byte_size": doc_info.file_size,
                        }
                        warnings.append(
                            "document.json streams past the 8MiB inspection cap; left unread"
                        )
                    else:
                        document_report = summarize_document(doc_bytes)
                        document_report["byte_size"] = len(doc_bytes)
                        status = document_report["status"]
                        if status == "parse_error":
                            warnings.append(
                                "document.json could not be parsed (%s); document counts unknown"
                                % document_report["detail"]
                            )
                        elif status == "unknown_version":
                            warnings.append(
                                "document declares version %s; counts withheld as unknown, "
                                "not judged (this checkout writes version %d)"
                                % (document_report["version"], DOC_VERSION)
                            )
                        elif status == "unknown_schema":
                            warnings.append(
                                "document structure is not the current schema (%s); "
                                "counts withheld as unknown, not judged"
                                % document_report["detail"]
                            )
                        if (
                            document_report.get("format") is not None
                            and document_report["format"] != DOC_FORMAT
                        ):
                            warnings.append(
                                'document format is %r; this checkout writes %r'
                                % (document_report["format"], DOC_FORMAT)
                            )

        # Tile byte-length disagreements: declared sizes only, never decompressed.
        # Warnings, not claims the app loader refuses them.
        for entry in entries:
            if entry["kind"] == "paint_tile" and entry["file_size"] != TILE_BYTES:
                warnings.append(
                    'tile-size disagreement: entry "%s" declares %d bytes, current paint '
                    "tile expectation is %d bytes (256x256 RGBA8); inspection warning "
                    "only, not a claim the app loader refuses it"
                    % (entry["name"], entry["file_size"], TILE_BYTES)
                )
            elif entry["kind"] == "media_tile" and entry["file_size"] != MEDIA_TILE_BYTES:
                warnings.append(
                    'tile-size disagreement: entry "%s" declares %d bytes, current media '
                    "store tile expectation is %d bytes (256x256 RGBA16F); inspection "
                    "warning only, not a claim the app loader refuses it"
                    % (entry["name"], entry["file_size"], MEDIA_TILE_BYTES)
                )

        recognized = {
            "mimetype": mimetype_report,
            "document_present": bool(document_infos),
            "thumbnail_present": any(i.filename == THUMBNAIL_NAME for i in infos),
            "paint_tiles": counts_by_kind.get("paint_tile", 0),
            "media_tiles": counts_by_kind.get("media_tile", 0),
            "strokes_files": counts_by_kind.get("strokes", 0),
            "directories": counts_by_kind.get("directory", 0),
            "unknown_entries": counts_by_kind.get("unknown", 0),
        }

        report = {
            "tool": TOOL_NAME,
            "inspection_only": True,
            "note": INSPECTION_NOTE,
            "archive": path,
            "zip": {
                "entry_count": len(entries),
                "first_entry": entries[0]["name"] if entries else None,
                "total_declared_file_bytes": total_file,
                "total_declared_compress_bytes": total_compress,
                "counts_by_suffix": counts_by_suffix,
                "counts_by_kind": counts_by_kind,
                "compression_methods": methods,
                "entries": entries,
                "duplicate_names": duplicates,
                "suspicious_names": suspicious,
                "unknown_extensions": unknown_extensions,
            },
            "recognized": recognized,
            "document": document_report,
            "tile_size_expectations": {
                "tile_size_px": TILE_SIZE_PX,
                "paint_tile_bytes": TILE_BYTES,
                "media_store_tile_bytes": MEDIA_TILE_BYTES,
                "media_stores": list(MEDIA_STORES),
                "sources": "TILE_SIZE 256 (Tiles.kt:11 via DocModel.kt:21); "
                "TILE_BYTES 262144 (JbArchive.kt:32); "
                "media tileBytes (MediaStores.kt:19,25,28-31)",
            },
            "current_document": {"format": DOC_FORMAT, "version": DOC_VERSION},
            "warnings": warnings,
        }
        return (report, 0)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Inspect a saved JoyBrush .joybrush archive (read-only) and print a JSON report."
    )
    parser.add_argument("archive", help="path to one saved .joybrush archive file")
    args = parser.parse_args(argv)
    report, code = inspect_archive(args.archive)
    sys.stdout.write(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
