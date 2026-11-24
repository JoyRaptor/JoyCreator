import csv
import json
import re
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import blind_review as review


def png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def png(brightness, label, width=1):
    return b"".join([
        review.PNG_SIGNATURE,
        png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, 1, 8, 0, 0, 0, 0)),
        png_chunk(b"gAMA", struct.pack(">I", 45455)),
        png_chunk(b"tEXt", b"Source\x00" + label.encode("ascii")),
        png_chunk(b"IDAT", zlib.compress(b"\x00" + bytes([brightness]) * width)),
        png_chunk(b"IEND", b""),
    ])


class BlindReviewTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.rows = []
        for i in range(20):
            labels = (f"studio-{i}.png", f"reference-{i}.png")
            for side, label in enumerate(labels):
                (self.root / label).write_bytes(png(2 * i + side, label))
            self.rows.append((f"fixture-{i}", *labels))
        self.manifest = self.root / "shots.csv"
        self.write_manifest()

    def write_manifest(self):
        with self.manifest.open("w", newline="", encoding="utf-8") as file:
            writer = csv.writer(file)
            writer.writerow(["shot_id", "studio", "reference"])
            writer.writerows(self.rows)

    def pack(self, name="packet", corpus=None):
        path = self.root / name
        identifier = review.prepare(self.manifest, path, "private-seed", corpus)
        key = review.unique_json(path / "private-key.json")
        self.assertEqual(key["packet"], identifier)
        return path, key

    def ratings(self, key, passing_shots):
        values = {}
        for i, (pair, private) in enumerate(key["pairs"].items()):
            studio = private["studio"]
            reference = "b" if studio == "a" else "a"
            choice = ("comparable" if i % 2 else studio) if i < passing_shots else reference
            values[pair] = {"preference": choice, "cut": "neither", "alpha": "neither"}
        return {"packet": key["packet"], "ratings": values}

    def corpus(self):
        tags = sorted(review.REQUIRED_CORPUS_TAGS)
        rows = []
        for i, shot in enumerate(self.rows):
            rows.append({
                "shot_id": shot[0],
                "kind": "authored" if i % 2 == 0 else "footage",
                "tags": tags[i::len(self.rows)],
                "reference_recipe": f"reference recipe {i}",
            })
        return {"schema": 1, "candidate_commit": "a" * 40, "shots": rows}

    def test_deterministic_balanced_packet_keeps_identity_private(self):
        path, key = self.pack()
        self.rows.reverse()
        self.write_manifest()
        _, same_key = self.pack("same")
        self.assertEqual(key, same_key)
        self.assertEqual(10, sum(pair["studio"] == "a" for pair in key["pairs"].values()))
        self.assertFalse((path / "review/private-key.json").exists())
        html = (path / "review/index.html").read_text(encoding="utf-8")
        self.assertNotIn("private-seed", html)
        self.assertNotIn("fixture-", html)
        self.assertNotIn("studio-", html)
        public = json.loads(re.search(r"const packet = (\{.*\});", html).group(1))
        self.assertEqual({"packet": key["packet"], "pairs": list(key["pairs"])}, public)
        for pair, private in key["pairs"].items():
            data = (path / "review/pairs" / f"{pair}-{private['studio']}.png").read_bytes()
            self.assertNotIn(b"tEXt", data)
            self.assertIn(b"gAMA", data)
            self.assertEqual(private["studio_sha256"], review.sha(data))

    def test_exact_eighty_percent_and_side_specific_severe_failures(self):
        _, key = self.pack()
        ratings = self.ratings(key, 16)
        score = review.score(key, ratings)
        self.assertTrue(score["passed"])
        self.assertEqual(80.0, score["comparable_or_better_percent"])
        self.assertFalse(review.score(key, self.ratings(key, 15))["passed"])

        pair, private = next(iter(key["pairs"].items()))
        ratings["ratings"][pair]["cut"] = "b" if private["studio"] == "a" else "a"
        self.assertTrue(review.score(key, ratings)["passed"])
        ratings["ratings"][pair]["cut"] = "both"
        cut = review.score(key, ratings)
        self.assertFalse(cut["passed"])
        self.assertEqual([private["shot"]], cut["severe_studio_cut_shots"])
        ratings["ratings"][pair]["cut"] = "neither"
        ratings["ratings"][pair]["alpha"] = private["studio"]
        alpha = review.score(key, ratings)
        self.assertFalse(alpha["passed"])
        self.assertEqual([private["shot"]], alpha["severe_studio_alpha_shots"])

    def test_missing_ratings_and_wrong_packet_cannot_score(self):
        _, key = self.pack()
        ratings = self.ratings(key, 20)
        del ratings["ratings"]["pair-001"]
        with self.assertRaisesRegex(ValueError, "one rating for every pair"):
            review.score(key, ratings)
        ratings = self.ratings(key, 20)
        ratings["packet"] = "other"
        with self.assertRaisesRegex(ValueError, "different packet"):
            review.score(key, ratings)
        duplicate = self.root / "duplicate.json"
        duplicate.write_text('{"rating": 1, "rating": 2}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            review.unique_json(duplicate)

    def test_fewer_than_twenty_and_reused_crops_are_rejected(self):
        last = self.rows.pop()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "at least 20"):
            self.pack()
        self.rows.append((last[0], *reversed(self.rows[0][1:])))
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "crop pair is reused"):
            self.pack()

    def test_mismatched_crop_dimensions_and_bad_checksum_are_rejected(self):
        (self.root / self.rows[0][1]).write_bytes(png(55, "wide", width=2))
        with self.assertRaisesRegex(ValueError, "matching dimensions"):
            self.pack()
        bad = bytearray(png(0, "bad"))
        bad[-1] ^= 1
        (self.root / self.rows[0][1]).write_bytes(bad)
        with self.assertRaisesRegex(ValueError, "bad PNG checksum"):
            self.pack("corrupt")

    def test_corpus_audit_covers_every_fixture_and_exact_candidate(self):
        result = review.audit_corpus(self.manifest, self.corpus())
        self.assertTrue(result["passed"])
        self.assertEqual(20, result["shots"])
        self.assertEqual({"authored": 10, "footage": 10}, result["kind_counts"])
        self.assertEqual([], result["missing_tags"])

        incomplete = self.corpus()
        for shot in incomplete["shots"]:
            if "whip-pan" in shot["tags"]:
                shot["tags"].remove("whip-pan")
        failed = review.audit_corpus(self.manifest, incomplete)
        self.assertFalse(failed["passed"])
        self.assertEqual(["whip-pan"], failed["missing_tags"])

    def test_corpus_audit_rejects_untraceable_or_mismatched_records(self):
        corpus = self.corpus()
        corpus["candidate_commit"] = "short"
        with self.assertRaisesRegex(ValueError, "exact lowercase Git commit"):
            review.audit_corpus(self.manifest, corpus)

        corpus = self.corpus()
        corpus["shots"][0]["tags"].append("made-up-risk")
        with self.assertRaisesRegex(ValueError, "unknown corpus tags"):
            review.audit_corpus(self.manifest, corpus)

        corpus = self.corpus()
        corpus["shots"][0]["shot_id"] = "not-in-packet"
        with self.assertRaisesRegex(ValueError, "shot IDs do not match manifest"):
            review.audit_corpus(self.manifest, corpus)

    def test_audited_packet_binds_candidate_privately_and_rejects_missing_fixture(self):
        corpus = self.corpus()
        path, key = self.pack(corpus=corpus)
        self.assertEqual("a" * 40, key["corpus_audit"]["candidate_commit"])
        self.assertEqual(review.sha(review.canonical(corpus)), key["corpus_audit"]["corpus_sha256"])
        html = (path / "review/index.html").read_text(encoding="utf-8")
        self.assertNotIn("candidate_commit", html)
        self.assertNotIn("corpus_audit", html)
        ratings = self.ratings(key, 16)
        score = review.score(key, ratings)
        self.assertTrue(score["passed"])
        self.assertEqual(key["corpus_audit"], score["corpus_audit"])

        key["corpus_audit"]["candidate_commit"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "private key does not match the packet"):
            review.score(key, ratings)

        for shot in corpus["shots"]:
            if "whip-pan" in shot["tags"]:
                shot["tags"].remove("whip-pan")
        output = self.root / "missing-fixture"
        with self.assertRaisesRegex(ValueError, "missing corpus tags: whip-pan"):
            review.prepare(self.manifest, output, "private-seed", corpus)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
