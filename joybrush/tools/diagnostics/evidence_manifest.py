#!/usr/bin/env python3
"""Reproducible evidence-bundle manifest tool (read-only, stdlib only).

Takes explicitly listed local artifact paths plus user-supplied metadata
(job, base/commit, optional device serial, optional command) and prints one
JSON manifest on stdout. Each named file is hashed with SHA256 in bounded
streaming chunks; the manifest records the exact digest, byte size, the
input path verbatim, and the UTC time the bytes were observed.

What this tool never does:

* never searches directories: a listed directory is reported, never walked;
* never opens anything except the paths named on the command line, so it
  never goes looking for credentials or configuration files;
* never follows symbolic links: a link is listed with its target, never
  hashed through, and a link appearing mid-read is reported, not silently
  accepted;
* never redacts paths: inputs are recorded exactly as given (repeated
  basenames stay distinct by full path), so scrub secrets before sharing;
* never zips, uploads, installs, phones home, touches the network, or
  mutates source control: it only reads the named files and writes JSON
  to stdout.

Timestamps record when bytes were collected, not that any check ran. A
manifest carrying digests is a collection record, nothing more.

Changed-during-read detection: size and nanosecond mtime are sampled
before and after the streaming hash, and the counted bytes are compared
against both samples. Any drift is reported on the entry and fails the
run. Limit: a same-size, same-mtime swap landing inside a single read
cannot be seen by stat comparison; that is a stated limit of this check,
not a claim of atomicity.
"""

import argparse
import datetime
import hashlib
import json
import os
import stat
import sys

TOOL_NAME = "evidence_manifest"

# Fixed streaming chunk: hashes never load a whole file into memory.
_CHUNK = 64 * 1024

COLLECTION_NOTE = (
    "This manifest lists the SHA256 digests and byte sizes seen while "
    "reading the named files. It is a collection record only: the digests "
    "show what bytes were present at collection time, not that any check "
    "ran, passed, or was reviewed, and not that the files are fit for any "
    "use. Paths are recorded exactly as given; no redaction is applied, "
    "so scrub secrets before sharing."
)

# Filesystem seams: module-level indirection so tests can inject
# deterministic fakes instead of racing the real filesystem.
def _utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def _lstat_path(path):
    return os.lstat(path)


def _stat_path(path):
    return os.stat(path)


def _open_binary(path):
    return open(path, "rb")


def _read_link(path):
    return os.readlink(path)


def _format_utc(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _short_error(exc):
    text = str(exc)
    if len(text) > 160:
        text = text[:160]
    return text


def _safe_link_target(path):
    try:
        return _read_link(path)
    except OSError:
        return None


def hash_stream(handle):
    """Hash an open binary handle in fixed bounded chunks.

    Returns (hex_digest, total_bytes). The caller owns the handle.
    """
    digest = hashlib.sha256()
    total = 0
    while True:
        piece = handle.read(_CHUNK)
        if not piece:
            break
        digest.update(piece)
        total += len(piece)
    return (digest.hexdigest(), total)


def _new_entry(input_path):
    return {
        "input": input_path,
        "status": None,
        "byte_size": None,
        "sha256": None,
        "observed_at_utc": _format_utc(_utc_now()),
        "detail": None,
    }


def collect_one(input_path):
    """Hash one explicitly listed path. Never walks, never follows links.

    Returns an artifact dict with status 'collected', 'missing',
    'is_directory', 'symlink', 'changed_during_read', 'not_regular_file'
    or 'unreadable'. Entries for problem files carry byte_size/sha256 only
    where bytes were actually seen, plus a human 'detail' string.
    """
    if not isinstance(input_path, str):
        input_path = os.fspath(input_path)
    entry = _new_entry(input_path)

    try:
        looked = _lstat_path(input_path)
    except FileNotFoundError:
        entry["status"] = "missing"
        entry["detail"] = (
            "there is no file at %r; pass an existing file path" % (input_path,)
        )
        return entry
    except NotADirectoryError:
        entry["status"] = "missing"
        entry["detail"] = (
            "a parent of %r is missing or not a directory" % (input_path,)
        )
        return entry
    except ValueError:
        entry["status"] = "unreadable"
        entry["detail"] = "%r is not a usable path" % (input_path,)
        return entry
    except OSError as exc:
        entry["status"] = "unreadable"
        entry["detail"] = "%r could not be examined: %s" % (
            input_path,
            _short_error(exc),
        )
        return entry

    if stat.S_ISLNK(looked.st_mode):
        entry["status"] = "symlink"
        entry["link_target"] = _safe_link_target(input_path)
        entry["detail"] = (
            "%r is a symbolic link; links are listed, never followed"
            % (input_path,)
        )
        return entry

    try:
        pre = _stat_path(input_path)
    except OSError as exc:
        entry["status"] = "unreadable"
        entry["detail"] = "%r could not be examined: %s" % (
            input_path,
            _short_error(exc),
        )
        return entry

    if stat.S_ISDIR(pre.st_mode):
        entry["status"] = "is_directory"
        entry["detail"] = (
            "%r is a directory; list files explicitly, "
            "directories are never searched" % (input_path,)
        )
        return entry
    if not stat.S_ISREG(pre.st_mode):
        entry["status"] = "not_regular_file"
        entry["detail"] = (
            "%r is not a regular file; only regular files are hashed"
            % (input_path,)
        )
        return entry

    pre_size = pre.st_size
    pre_mtime = pre.st_mtime_ns

    try:
        handle = _open_binary(input_path)
    except OSError as exc:
        entry["status"] = "unreadable"
        entry["detail"] = "%r could not be opened: %s" % (
            input_path,
            _short_error(exc),
        )
        return entry

    with handle:
        try:
            digest, total = hash_stream(handle)
        except OSError as exc:
            entry["status"] = "unreadable"
            entry["detail"] = "%r could not be read: %s" % (
                input_path,
                _short_error(exc),
            )
            return entry

    try:
        post = _stat_path(input_path)
        post_look = _lstat_path(input_path)
    except OSError:
        entry["status"] = "changed_during_read"
        entry["byte_size"] = total
        entry["sha256"] = digest
        entry["detail"] = (
            "%r disappeared or became inaccessible while it was being read; "
            "the digest covers only the %d bytes seen" % (input_path, total)
        )
        return entry

    if stat.S_ISLNK(post_look.st_mode):
        entry["status"] = "symlink"
        entry["link_target"] = _safe_link_target(input_path)
        entry["byte_size"] = total
        entry["sha256"] = digest
        entry["detail"] = (
            "%r became a symbolic link while it was being read; the digest "
            "covers only the %d bytes seen before the switch and is reported "
            "for forensics, not as a clean collection" % (input_path, total)
        )
        return entry

    post_size = post.st_size
    post_mtime = post.st_mtime_ns
    mtime_moved = post_mtime != pre_mtime
    if post_size != pre_size or total != pre_size or total != post_size or mtime_moved:
        entry["status"] = "changed_during_read"
        entry["byte_size"] = total
        entry["sha256"] = digest
        entry["detail"] = (
            "%r changed while it was being read "
            "(size before %d, bytes read %d, size after %d, mtime moved: %s); "
            "the digest covers only the bytes seen"
            % (
                input_path,
                pre_size,
                total,
                post_size,
                "yes" if mtime_moved else "no",
            )
        )
        return entry

    entry["status"] = "collected"
    entry["byte_size"] = total
    entry["sha256"] = digest
    return entry


def collect_manifest(job, base, inputs, device_serial=None, command=None):
    """Hash every explicitly listed input. Returns (report_dict, exit_code).

    Exit 0 when every input was collected; exit 1 when any input was
    missing, a directory, a link, changed mid-read, or otherwise
    unhashable. The report is always a plain JSON-serializable dict.
    """
    collected_at = _format_utc(_utc_now())
    artifacts = [collect_one(path) for path in inputs]
    problems = sum(1 for item in artifacts if item["status"] != "collected")
    report = {
        "tool": TOOL_NAME,
        "collection_only": True,
        "note": COLLECTION_NOTE,
        "job": job,
        "base": base,
        "device_serial": device_serial,
        "command": command,
        "collected_at_utc": collected_at,
        "manifest_complete": problems == 0,
        "artifact_count": len(artifacts),
        "problem_count": problems,
        "artifacts": artifacts,
    }
    return (report, 0 if problems == 0 else 1)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Record SHA256 digests and byte sizes for explicitly listed "
            "files and print one JSON manifest on stdout. Read-only: "
            "directories are reported, never searched; links are listed, "
            "never followed."
        )
    )
    parser.add_argument(
        "--job",
        required=True,
        help="job id recorded in the manifest (for example JB-NOW-29)",
    )
    parser.add_argument(
        "--base",
        "--commit",
        dest="base",
        required=True,
        help="base or commit hash the artifacts belong to",
    )
    parser.add_argument(
        "--device-serial",
        default=None,
        help="optional device serial recorded in the manifest",
    )
    parser.add_argument(
        "--command",
        default=None,
        help="optional command line that produced the artifacts",
    )
    parser.add_argument(
        "artifacts",
        nargs="+",
        help="explicit local file paths to hash; "
        "directories are rejected, never walked",
    )
    args = parser.parse_args(argv)
    report, code = collect_manifest(
        job=args.job,
        base=args.base,
        inputs=args.artifacts,
        device_serial=args.device_serial,
        command=args.command,
    )
    sys.stdout.write(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
