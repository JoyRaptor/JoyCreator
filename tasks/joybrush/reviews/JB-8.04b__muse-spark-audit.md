# JB-8.04b — adversarial audit (muse, for bunny)

- Auditor: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta). Read-only: no gradle,
  no edits. Spec `tasks/joybrush/specs/JB-8.04b_bundle_inflate.md` read whole (1304 lines);
  R44 item 2 + R40 read. Local HEAD `7bc60742`.
- Target: 🟧 Built 2026-09-30 (sibling orchestrator's builder; 10 files, 27 tests).

**AUDIT-VERDICT: CLEAN (0 BLOCKER, 0 MAJOR). No findings filed.**

## What was attacked (all held)

1. **Second DEFLATE / second caps — not found.** Single `internal expect fun inflateRaw`
   (`imports/Inflate.kt:29`), single `actual` (`Inflate.jvm.kt:24`, `java.util.zip.Inflater(true)`).
   Three call sites, all shared (`ProcreateImport.kt:502`, `KritaImport.kt:1473`,
   `PngChunks.kt:426`) via shared `inflateMaxOut` + `looksLikeZlib`. Caps declared once
   (`ImportSupport.kt:83,86`: `MAX_INFLATE_RATIO=200L`, `MAX_INFLATED_BYTES=64 MiB`); no second
   `200L`/`67108864` literal in `core/src`. `MAX_PNG_STRING_BYTES` is a different, tighter
   quantity — not a second bomb pair. `jvmTest` Deflater/ZipOutputStream uses are fixture
   writers, never readers.
2. **Caps bind every path, incl. zTXt with no stated size.** Zip: ratio is `Int*Long→Long`
   (no wrap), ceiling `Int>Long→Long`, then `inflateMaxOut(declared)` = `min(declared, 64MiB)`;
   `Inflate.jvm.kt:51` refuses past `maxOut` before writing, never truncates; `:54-56` refuses
   trailing bytes. Worst zip (declared 64 MiB at ratio 199.99…) still refused past 64 MiB at
   ≤64 KiB allocated. Worst zTXt: `inflateMaxOut(8388608)` — 8 MiB worst cost + one 64 KiB
   chunk; tiny payloads expanding past 8 MiB refused loud, never short. Spec Q1 ceiling-only
   is intended and KDoc-stated; ISIZE pre-check stays Lead-open/non-blocking. No bypass.
3. **`convertBundle` opens DEFLATE bundles (Decision 5 reversal executed).** `KritaZip.read`
   (`:1409-1473`) copies STORED, inflates DEFLATED via shared caps, refuses 12/99 by name. All
   three former `readStored` callers converted (`:262,:275,:361,:906-907`); no `readStored`
   remains in `commonMain`. E.4 KDoc updated.
4. **`KritaImport.kt:953-957` `because` assembly — minimal, STORED unaffected.** Single ternary
   into one existing `warn`; success returns early at `:916-919` before it (STORED and DEFLATED
   identical); STORED failure path unchanged, only generalized. Test 16's sentence present.
5. **Crash/bomb inputs — none found.** Empty zip (0 B) → `BrushException` (`:1493`, `:532`,
   `PngChunks.walkPngChunks:176`); truncated streams bounds-checked (`le16/le32`,
   `at+compressedSize>size` at `:1461-1466`, `length<1` at `:1472`); `looksLikeZlib`
   bounds-checked; `uncompressedSize==0` → `maxOut=0` refuses non-empty expansion; raw DEFLATE
   has no ISIZE and zlib Adler is stripped-unverified per landed Decision 12, so nothing
   believed is lie-able. No non-`BrushException` throw, no short return, no unbounded alloc.
