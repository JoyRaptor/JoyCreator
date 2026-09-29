# Adversarial review — JB-0.04 Stroke recording codec

- Reviewer: muse-spark (cross-reviewer, different family from builder space bunny agent #3).
- Task status: 🟧 Built. Commit reviewed: `f8366435` ("JB-0.04: stroke codec").
- Spec reviewed: `tasks/joybrush/specs/JB-0.04_stroke_codec.md`, all 10 test clauses.
- §5b checks: diff `f8366435` touches only `core/io/*`, `core/stroke/StrokeCodec.kt`, `StrokeCodecTest.kt`, and the ROADMAP row — inside the owner area. No `java.*`, no new dependency, `PenSample`/`StrokeRecord` untouched. Spec command `./gradlew -p joybrush :core:jvmTest` run 2026-09-28: StrokeCodecTest 11/11, 0 failures (incl. exact-size test 10 and all bad-input cases of test 9).
- Severity scale: High = work lost; Medium = hostile file harms the phone; Low = edge; Info = verified-good.

## Finding 1 (Low): `decode`/`decodeAll` pre-size array lists from an untrusted count (hostile-file memory spike)
Proof: `StrokeCodec.kt:95` — `ArrayList(count.toInt())` after only the `count * MIN_SAMPLE_BYTES > remaining` sanity check (`:92`); `decodeAll` similarly at `:131`. The check bounds count by *file size* (`count ≤ remaining/21`), so a ~200 MB hostile `.joybrush` could demand a tens-of-millions-entry list before a single sample is read — plausibly OOM on the Note 9. Crafting it requires producing the large file, so this is hardening, not a live hole. `ArrayList()` + `ensureCapacity(min(count, reasonable))` or chunked growth closes it.

## Finding 2 (Low): trailing bytes are silently ignored
Proof: `decode` never asserts `remaining == 0` after the last sample (`StrokeCodec.kt:99-110`); `decodeAll` returns after `count` records without checking the tail (`:132-137`). Appended/corrupt tail bytes decode as success. The spec does not require rejection, and ignoring trailing bytes is a defensible forward-compat posture — recording it so JB-0.08 decides explicitly (checksums live there, not here).

## Finding 3 (Low): the "bad UTF-8" error path is effectively dead code
Proof: `ByteReader.utf8` (`ByteReader.kt:50-58`) catches `Exception` around `decodeToString()` — but Kotlin's `decodeToString` replaces malformed sequences with U+FFFD rather than throwing. Malformed id bytes thus decode to replacement characters instead of raising. Blast radius is display-only (ids, not pixels), and round-trip of *valid* text incl. `"étoile-星"`/`𝄞`/300-char ids is proven by test 4. Either drop the catch or validate with a strict decoder; do not leave a promise the code cannot keep.

## Verified good (with proof — this is the round's most attack-resistant piece)
- Bit-exactness: raw-bits floats incl. NaN/−0.0/MIN_VALUE and absolute f64 timeMs incl. NaN (test 5, `StrokeCodecTest.kt:103-115`); per-channel flags with NaN fill (tests 2–3); all four tools; empty strokes; multi-record order + framing (tests 6–8).
- Refusals, all tested (test 9): bad magic, versions 0 *and* 2 with the exact spec message, truncation at 0/8/10/n−1 bytes, lying sample count, unknown tool ordinal, unknown flag bit (refuse-don't-guess, `:81-83`), empty/short/lying multi-record files.
- Exact size formula (test 10, `StrokeCodecTest.kt:198-210`); little-endian + growth primitives (`byteWriterAndReaderAreLittleEndianAndGrow`); `u16`-byte-length utf8 (byte- not char-count, test `:97`).
- `predicted` can never be decoded (constructed `PenSample` defaults `predicted=false`, `:108`; `StrokeRecord.init` rejects them at encode time).
- Enum-order fragility noted and dismissed: `tool` persists `ordinal` (`StrokeCodec.kt:66,141`), so inserting a `Tool` variant mid-enum breaks old files. `Tool` is JB-0.01's contract and stable; just don't reorder it — flagging for the JB-0.08 author.

## Recommendation
No send-back. The codec keeps its promise: identical strokes in, identical strokes out, loud failure on anything else. Findings 1–3 are hardening/clarity for the JB-0.08 author, none blocks.
