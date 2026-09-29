# Adversarial review — JB-2.14a PNG writer (exact, JVM-tested)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commits reviewed: `302095d0` (landed 16/17 with 1 OOMing test) + `cf240c5d` (IDAT-cursor fix).
- Spec reviewed: `tasks/joybrush/specs/JB-2.14a_png_writer.md` (contract, decisions 1–4, tests 1–4).
- §5b checks: diffs touch only NEW `PngWriter.kt` + NEW `PngWriterTest.kt` (+ the fix commit's test additions) and the board row — inside the owner area (+ the `testImplementation(kotlin("test"))` build line). Suite run by me: `PngWriterTest` 17/17, 0 failures (fresh `:androidkit:test` 85/0). All four spec tests map (ImageIO round-trip incl. alpha extremes, 300×200 gradient, bad-size throws, signature+IHDR).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead

1. **The review-found IDAT bug is fixed with a guard, not just a patch.** The cursor-now-moves-by-`end` fix carries the `check(end > at)` stall guard (`PngWriter.kt:135-137`) plus the subtraction-form overflow note (`:131-134`) — the exact defence this codebase uses for the same loop shape in `GifEncoder.writeSubBlocks` and `OraExport.paperPixels`. A future regression here fails immediately with a line number instead of eating the heap. The `do-while` guarantees ≥1 IDAT and never an empty one (`:122-140`).
2. **Integer discipline throughout:** `requireSize` computes in Long with the 17 GB worked example in the comment (`:340-370`); the filter-byte row overhead gets its own second check (`:366-369`); stride/row buffers are safe downstream of those gates; `filterRow` scores in Long; CRC `ushr` write handles >2³¹ values (`:333-338`).
3. **Format exactness hand-verified:** 8-byte signature (`:68-70`); IHDR 13 bytes, BE, 8/6/0/0/0 (`:258-268`); zlib-wrapped (not raw) deflate with Adler-32 by the JDK (`:114-117`); CRC over type+data only (`:289-292`); filter-method 0 with all five filters legal; deterministic tie-break (strictly-less, None-wins — same picture twice is same bytes, and 1×1 hand-checkable: `:176-184`); Paeth/average-floor correct (`:220-229, :243-249`); `deflate` loop asserts `finished()` with `end()` in `finally` (`:296-317`).
4. **Contract choices as specified:** straight passthrough incl. alpha-0 (no white-fill — the kdoc's white-box argument at `:47-53` is correct for layer PNGs and thumbnails); nothing optional written (no gamma/sRGB/text/time); level 0/−1 semantics documented; `MAX_IDAT_BYTES` chunking with the production-vs-test split via the internal overload (`:88-97`, incl. the honest note about the 1024²-noise OOM that motivated it).
5. Peak memory is ~4× the image (rgba + filtered + deflated + file) — bounded upstream by `RegionRenderer`'s 2²³-px budget on every production path, and the only unbounded caller would be a hand-built array, which `requireSize` still gates to real arrays. No finding (budgeted upstream, refused loudly otherwise).

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. The fixed bug's guard is the model for how this codebase should trap cursor loops.
