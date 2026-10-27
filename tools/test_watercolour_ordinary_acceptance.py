import base64
import hashlib
import importlib.util
import json
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("watercolour_ordinary_acceptance.py")
spec = importlib.util.spec_from_file_location("watercolour_ordinary_acceptance", MODULE_PATH)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

BRUSH = "selected-brush"

def frozen_json():
    return json.dumps({"version": 10, "format": "joybrush.brush", "id": "frozen-wc",
                       "engine": "watercolour", "blend": "multiply", "size": {"base": 300},
                       "watercolour": {"size": 300, "grain": 0, "paperCut": 0}},
                      ensure_ascii=False, separators=(",", ":"))

def valid_report():
    encoded = frozen_json()
    return {"case": checker.CASE, "brush": BRUSH, "run": 1,
            "probe": "watercolourOrdinary", "passed": True, "phase": "complete",
            "engineReleaseGlError": 0, "translationPixels": [37, -19],
            "initialUndoDepth": 1, "finalUndoDepth": 3, "tileUnion": 2,
            "tileChecks": [{"phase": phase, "exactRgba8": True,
                            "transparentTilesIncluded": True, "comparedTiles": 2}
                           for phase in checker.PHASES],
            "tintControlChangedPixels": 7, "tintActualChangedPixels": 7,
            "fixture": {"fixtureSourceId": BRUSH, "fixtureSeed": 78234,
                        "fixtureRawSamples": 24, "fixtureSize": 300,
                        "fixtureGrain": 0, "fixturePaperCut": 0,
                        "fixtureOperator": "multiply", "fixtureFrozenId": "frozen-wc",
                        "fixtureOverrides": []},
            "fixtureFrozenBrushJson": encoded,
            "fixtureFrozenBrushSha256": hashlib.sha256(encoded.encode()).hexdigest()}

def chunk_log(report, chunk_size=97):
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    # Ensure test exercises a multibyte UTF-8 code point split across byte chunks.
    marker = "café雪"
    report["syntheticUnicode"] = marker
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    pos = raw.index("雪".encode("utf-8")) + 1
    start = max(1, pos - chunk_size // 2)
    sizes, cursor = [], 0
    while cursor < len(raw):
        n = chunk_size
        if cursor < pos < cursor + n:
            n = pos - cursor
        sizes.append(raw[cursor:cursor+n]); cursor += n
    lines = []
    for i, data in enumerate(sizes):
        part = {"case": checker.CASE, "brush": BRUSH, "run": 1,
                "recordedProbeChunk": True, "parts": len(sizes), "part": i,
                "dataBase64": base64.b64encode(data).decode("ascii")}
        lines.append("I/Probe: " + json.dumps(part, separators=(",", ":")))
    return "\n".join(lines), len(sizes)

class AcceptanceTests(unittest.TestCase):
    def rejected(self, report, brush=BRUSH, run=1):
        with self.assertRaises(checker.InvalidReport):
            checker.validate_report(report, brush, run)

    def test_valid_seven_row_report_and_frozen_hash(self):
        got = checker.validate_report(valid_report(), BRUSH, 1)
        self.assertEqual(len(got["tileChecks"]), 7)
        self.assertEqual(got["fixtureFrozenBrushSha256"], hashlib.sha256(got["fixtureFrozenBrushJson"].encode()).hexdigest())

    def test_chunk_assembly_accepts_non_ascii_byte_boundary(self):
        log, count = chunk_log(valid_report())
        self.assertGreater(count, 1)
        got = checker.read_capture(log, BRUSH, 1)
        self.assertEqual(got["syntheticUnicode"], "café雪")

    def test_missing_duplicate_conflicting_chunks_and_truncated_selected_log_rejected(self):
        log, _ = chunk_log(valid_report())
        lines = log.split("\n")
        self.assert_invalid_capture("\n".join(lines[:-1]))
        self.assert_invalid_capture(log + "\n" + lines[0])
        changed = json.loads(lines[-1].split(": ", 1)[1])
        changed["parts"] += 1
        self.assert_invalid_capture(log + "\\nI/Probe: " + json.dumps(changed))
        self.assert_invalid_capture('I/Probe: {"case":"' + checker.CASE + '",')

    def assert_invalid_capture(self, text):
        with self.assertRaises(checker.InvalidReport):
            checker.read_capture(text, BRUSH, 1)

    def test_wrong_cache_case_brush_or_run_rejected(self):
        for key, value in (("probe", "watercolourCache"), ("case", "other"), ("brush", "other"), ("run", 2)):
            with self.subTest(key=key):
                r = valid_report(); r[key] = value; self.rejected(r)

    def test_missing_duplicate_phase_partial_pixels_and_incomplete_union(self):
        for mutate in (
            lambda r: r["tileChecks"].pop(),
            lambda r: r["tileChecks"][1].update(phase="add"),
            lambda r: r["tileChecks"][2].update(exactRgba8=False),
            lambda r: r["tileChecks"][-1].update(comparedTiles=1),
        ):
            with self.subTest(mutation=mutate):
                r = valid_report(); mutate(r); self.rejected(r)

    def test_translation_history_and_release_gl_failures(self):
        for key, value in (("translationPixels", [36, -19]), ("finalUndoDepth", 2),
                           ("initialUndoDepth", 0), ("engineReleaseGlError", 1280)):
            r = valid_report(); r[key] = value; self.rejected(r)

    def test_tint_vacuity_and_control_mismatch(self):
        for expected, actual in ((0, 0), (4, 5)):
            r = valid_report(); r["tintControlChangedPixels"] = expected; r["tintActualChangedPixels"] = actual
            self.rejected(r)

    def test_fixture_metadata_hash_identity_and_operator_are_bound(self):
        for mutate in (
            lambda r: r["fixture"].update(fixtureGrain=0.2),
            lambda r: r["fixture"].update(fixtureSeed=1),
            lambda r: r["fixture"].update(fixtureSourceId="other"),
            lambda r: r["fixture"].update(fixtureOperator="normal"),
            lambda r: r.update(fixtureFrozenBrushSha256="0" * 64),
            lambda r: r["fixture"].update(fixtureFrozenId="other"),
            lambda r: r["fixture"].update(fixtureOverrides=[{"field":"watercolour.grain","before":"0.5","after":"0.2"}]),
        ):
            with self.subTest(mutation=mutate):
                r = valid_report(); mutate(r); self.rejected(r)

    def test_duplicate_keys_and_nonfinite_frozen_json_rejected(self):
        for encoded in ('{"id":"a","id":"b"}', '{"id":"a","n":NaN}', '{"id":"a","n":1e999}'):
            r = valid_report(); r["fixtureFrozenBrushJson"] = encoded
            r["fixtureFrozenBrushSha256"] = hashlib.sha256(encoded.encode()).hexdigest()
            self.rejected(r)

    def test_optional_receipt_valid_and_rejects_apk_manifest_or_missing_working(self):
        apk = "a" * 64
        manifest = {"Working/current.joybrush": {"size": 12, "sha256": "b" * 64},
                    "Working/current.joybrush.bak": {"size": 12, "sha256": "c" * 64}}
        receipt = {"sourceRevision": "1" * 40, "apkSha256": apk,
                   "installedBeforeSha256": apk, "installedAfterSha256": apk,
                   "model": "SM-N960U", "api": 29, "savedBefore": manifest,
                   "savedAfter": dict(manifest), "requiredSavedPaths": list(manifest)}
        self.assertIsNone(checker.validate_receipt(receipt))
        bad = dict(receipt); bad["installedAfterSha256"] = "d" * 64
        with self.assertRaises(checker.InvalidReport): checker.validate_receipt(bad)
        bad = dict(receipt); bad["savedAfter"] = {}
        with self.assertRaises(checker.InvalidReport): checker.validate_receipt(bad)
        bad = dict(receipt); bad["requiredSavedPaths"] = ["Working/current.joybrush"]
        with self.assertRaises(checker.InvalidReport): checker.validate_receipt(bad)

if __name__ == "__main__":
    print("SYNTHETIC ONLY: these tests validate offline checker logic; they make no GPU execution claim.")
    unittest.main(verbosity=2)



