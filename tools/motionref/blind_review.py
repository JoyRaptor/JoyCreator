"""Audit, prepare and score a private-mapping STU-7 blind crop review (§8)."""

import argparse
import csv
import hashlib
import json
import re
import secrets
import struct
import sys
import zlib
from pathlib import Path


MIN_SHOTS = 20
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_LABEL_CHUNKS = {b"tEXt", b"zTXt", b"iTXt", b"eXIf", b"tIME"}
SIDES = {"a", "b"}
CORPUS_KINDS = {"authored", "footage"}
# The fail-first fixture list in merged plan section 8. One shot may cover several tags, but the
# private corpus record must account for every risk before a 20-shot packet can be called complete.
REQUIRED_CORPUS_TAGS = {
    "still", "translation", "zoom", "spoked-wheel", "crossing", "bezier-arm", "tracked-hand-arc",
    "alternate-drawing", "flipbook-hold", "fence-pan", "person-crossing", "flash",
    "low-light", "dolly-zoom", "whip-pan", "hard-cut", "dissolve", "retime-2x",
    "reverse", "freeze", "speed-ramp", "loop", "ping-pong", "step-key",
    "highlight-streak", "transparent-black", "transparent-white",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def png_crop(path):
    """Keep encoded pixels and colour information, dropping common identifying metadata."""
    data = path.read_bytes()
    if data[:8] != PNG_SIGNATURE:
        raise ValueError(f"{path}: expected a PNG crop")
    kept = [PNG_SIGNATURE]
    size = None
    saw_pixels = False
    offset = len(PNG_SIGNATURE)
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + length
        if end > len(data):
            break
        payload = data[offset + 8:end - 4]
        expected, = struct.unpack_from(">I", data, end - 4)
        if zlib.crc32(payload, zlib.crc32(kind)) != expected:
            raise ValueError(f"{path}: bad PNG checksum")
        if size is None:
            if kind != b"IHDR" or length != 13:
                raise ValueError(f"{path}: missing PNG header")
            size = struct.unpack_from(">II", payload)
            if not all(size):
                raise ValueError(f"{path}: empty PNG crop")
        if kind in {b"acTL", b"fcTL", b"fdAT"}:
            raise ValueError(f"{path}: expected a still PNG crop")
        if kind == b"IDAT":
            saw_pixels = True
        if kind not in PNG_LABEL_CHUNKS:
            kept.append(data[offset:end])
        if kind == b"IEND" and length == 0 and end == len(data) and saw_pixels:
            return b"".join(kept), size
        offset = end
    raise ValueError(f"{path}: incomplete PNG crop")


def read_shots(manifest):
    with manifest.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, strict=True)
        if reader.fieldnames != ["shot_id", "studio", "reference"]:
            raise ValueError("expected CSV header shot_id,studio,reference")
        shots = {}
        for row in reader:
            if None in row or not all(value and value.strip() for value in row.values()):
                raise ValueError(f"incomplete shot at CSV line {reader.line_num}")
            shot_id = row["shot_id"].strip()
            if shot_id in shots:
                raise ValueError(f"duplicate shot ID: {shot_id}")
            shots[shot_id] = [manifest.parent / row[key].strip() for key in ("studio", "reference")]
    if len(shots) < MIN_SHOTS:
        raise ValueError(f"expected at least {MIN_SHOTS} distinct shots")
    return shots


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode("ascii")


def assign(shots, seed):
    """Rank shots and side placements separately, balancing A and B within one shot."""
    def order(values, purpose):
        return sorted(sorted(values), key=lambda shot: sha(canonical([seed, purpose, shot])))

    order_of_shots = order(shots, "order")
    a_count = len(shots) // 2
    if len(shots) % 2 and int(sha(canonical([seed, "odd side"]))[-1], 16) % 2:
        a_count += 1
    studio_a = set(order(shots, "side")[:a_count])
    return [(f"pair-{i:03d}", shot, "a" if shot in studio_a else "b")
            for i, shot in enumerate(order_of_shots, 1)]


def prepare(manifest, output, seed, corpus=None):
    shots = read_shots(manifest)
    audit = check_corpus(shots, corpus) if corpus is not None else None
    if audit is not None and not audit["passed"]:
        raise ValueError("missing corpus tags: " + ", ".join(audit["missing_tags"]))
    assignments = assign(shots, seed)
    template = Path(__file__).with_name("blind_review.html").read_text(encoding="utf-8")
    output.mkdir()
    review = output / "review"
    pairs_dir = review / "pairs"
    pairs_dir.mkdir(parents=True)
    pairs = {}
    seen = set()
    for pair_id, shot, studio_side in assignments:
        studio, studio_size = png_crop(shots[shot][0])
        reference, reference_size = png_crop(shots[shot][1])
        if studio_size != reference_size:
            raise ValueError(f"{shot}: crops must have matching dimensions")
        studio_hash, reference_hash = sha(studio), sha(reference)
        unordered_hashes = tuple(sorted((studio_hash, reference_hash)))
        if unordered_hashes in seen:
            raise ValueError(f"{shot}: crop pair is reused")
        seen.add(unordered_hashes)
        a, b = (studio, reference) if studio_side == "a" else (reference, studio)
        (pairs_dir / f"{pair_id}-a.png").write_bytes(a)
        (pairs_dir / f"{pair_id}-b.png").write_bytes(b)
        pairs[pair_id] = {"shot": shot, "studio": studio_side,
                          "studio_sha256": studio_hash, "reference_sha256": reference_hash}

    private = {"schema": 1, "seed": seed, "pairs": pairs}
    if audit is not None:
        private["corpus_audit"] = audit
    packet = sha(canonical(private))
    private["packet"] = packet
    public = json.dumps({"packet": packet, "pairs": list(pairs)})
    (output / "private-key.json").write_text(json.dumps(private, indent=2) + "\n", encoding="utf-8")
    (review / "index.html").write_text(template.replace("/* PACKET */ null", public), encoding="utf-8")
    return packet


def score(key, ratings):
    if key.get("schema") != 1:
        raise ValueError("unsupported private key schema")
    packet = key.get("packet")
    if packet != sha(canonical({name: value for name, value in key.items() if name != "packet"})):
        raise ValueError("private key does not match the packet ID")
    if ratings.get("packet") != packet:
        raise ValueError("ratings belong to a different packet")
    pairs = key["pairs"]
    decisions = ratings.get("ratings")
    if not isinstance(decisions, dict) or len(pairs) < MIN_SHOTS or pairs.keys() != decisions.keys():
        raise ValueError(f"expected one rating for every pair in a packet of at least {MIN_SHOTS} shots")
    comparable_or_better = 0
    severe = {"cut": [], "alpha": []}
    for pair, assignment in pairs.items():
        rating = decisions[pair]
        if not isinstance(rating, dict) or rating.keys() != {"preference", "cut", "alpha"}:
            raise ValueError(f"{pair}: missing preference or artifact ratings")
        choice = rating["preference"]
        studio_side = assignment["studio"]
        if choice not in ("a", "b", "comparable") or studio_side not in SIDES:
            raise ValueError(f"{pair}: invalid preference or private assignment")
        comparable_or_better += choice in (studio_side, "comparable")
        for kind in severe:
            if rating[kind] not in ("neither", "a", "b", "both"):
                raise ValueError(f"{pair}: invalid severe {kind} rating")
            if rating[kind] in (studio_side, "both"):
                severe[kind].append(assignment["shot"])
    result = {
        "packet": packet,
        "shots": len(pairs),
        "comparable_or_better": comparable_or_better,
        "comparable_or_better_percent": round(100 * comparable_or_better / len(pairs), 2),
        "severe_studio_cut_shots": severe["cut"],
        "severe_studio_alpha_shots": severe["alpha"],
        "passed": 5 * comparable_or_better >= 4 * len(pairs) and not any(severe.values()),
    }
    if "corpus_audit" in key:
        result["corpus_audit"] = key["corpus_audit"]
    return result


def unique_json(path):
    def unique_object(items):
        obj = {}
        for name, value in items:
            if name in obj:
                raise ValueError(f"{path}: duplicate JSON key {name!r}")
            obj[name] = value
        return obj

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def audit_corpus(manifest, corpus):
    """Cross-check the private review corpus against every section 8 risk fixture."""
    return check_corpus(read_shots(manifest), corpus)


def check_corpus(shots, corpus):
    shot_ids = set(shots)
    if not isinstance(corpus, dict) or corpus.keys() != {"schema", "candidate_commit", "shots"}:
        raise ValueError("corpus must contain only schema, candidate_commit and shots")
    if type(corpus["schema"]) is not int or corpus["schema"] != 1:
        raise ValueError("unsupported corpus schema")
    candidate = corpus["candidate_commit"]
    if not isinstance(candidate, str) or not re.fullmatch(r"[0-9a-f]{40}", candidate):
        raise ValueError("candidate_commit must be one exact lowercase Git commit")
    rows = corpus["shots"]
    if not isinstance(rows, list):
        raise ValueError("corpus shots must be a list")

    seen = set()
    kind_counts = {kind: 0 for kind in sorted(CORPUS_KINDS)}
    tag_counts = {tag: 0 for tag in sorted(REQUIRED_CORPUS_TAGS)}
    for index, row in enumerate(rows, 1):
        if not isinstance(row, dict) or row.keys() != {
                "shot_id", "kind", "tags", "reference_recipe"}:
            raise ValueError(f"corpus shot {index} has unexpected or missing fields")
        shot = row["shot_id"]
        if not isinstance(shot, str) or not shot.strip() or shot != shot.strip():
            raise ValueError(f"corpus shot {index} has an invalid shot_id")
        if shot in seen:
            raise ValueError(f"duplicate corpus shot ID: {shot}")
        seen.add(shot)
        kind = row["kind"]
        if not isinstance(kind, str) or kind not in CORPUS_KINDS:
            raise ValueError(f"{shot}: kind must be authored or footage")
        tags = row["tags"]
        if (not isinstance(tags, list) or not tags
                or any(not isinstance(tag, str) for tag in tags)):
            raise ValueError(f"{shot}: tags must be a non-empty string list")
        if len(tags) != len(set(tags)):
            raise ValueError(f"{shot}: duplicate corpus tag")
        unknown = set(tags) - REQUIRED_CORPUS_TAGS
        if unknown:
            raise ValueError(f"{shot}: unknown corpus tags: {', '.join(sorted(unknown))}")
        recipe = row["reference_recipe"]
        if not isinstance(recipe, str) or not recipe.strip():
            raise ValueError(f"{shot}: missing reference recipe")
        kind_counts[kind] += 1
        for tag in tags:
            tag_counts[tag] += 1

    if seen != shot_ids:
        missing = sorted(shot_ids - seen)
        extra = sorted(seen - shot_ids)
        raise ValueError(f"corpus shot IDs do not match manifest; missing={missing}, extra={extra}")
    if any(count == 0 for count in kind_counts.values()):
        raise ValueError("corpus must contain both authored and footage shots")
    missing_tags = [tag for tag, count in tag_counts.items() if count == 0]
    return {
        "candidate_commit": candidate,
        "corpus_sha256": sha(canonical(corpus)),
        "shots": len(rows),
        "kind_counts": kind_counts,
        "tag_counts": tag_counts,
        "missing_tags": missing_tags,
        "passed": not missing_tags,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pack = commands.add_parser("prepare", help="make a packet and separate private scoring key")
    pack.add_argument("manifest", type=Path)
    pack.add_argument("output", type=Path, help="new directory outside Git")
    pack.add_argument("--seed", help="private reproducibility seed (random if omitted)")
    pack.add_argument("--corpus", type=Path, help="private provenance for a gate review")
    mark = commands.add_parser("score", help="score complete blind ratings")
    mark.add_argument("private_key", type=Path)
    mark.add_argument("ratings", type=Path)
    check = commands.add_parser("audit", help="prove the private corpus covers section 8")
    check.add_argument("manifest", type=Path)
    check.add_argument("corpus", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            corpus = unique_json(args.corpus) if args.corpus is not None else None
            if args.corpus is not None and not isinstance(corpus, dict):
                raise ValueError("corpus must contain a JSON object")
            print(prepare(args.manifest, args.output, args.seed or secrets.token_hex(16), corpus))
            return 0
        if args.command == "score":
            result = score(unique_json(args.private_key), unique_json(args.ratings))
        else:
            result = audit_corpus(args.manifest, unique_json(args.corpus))
        print(json.dumps(result, indent=2))
        return 0 if result["passed"] else 1
    except (OSError, ValueError, KeyError, TypeError, AttributeError, csv.Error) as error:
        print(f"blind review: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
