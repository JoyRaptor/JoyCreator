# Adversarial review — JB-3.06a Animated GIF encoder (pure, deterministic)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family; orchestrator finished).
- Task status: 🟧 Built. Commit reviewed: `4259f2bc`.
- Spec reviewed: `tasks/joybrush/specs/JB-3.06a_gif_encoder.md` (contract, decisions 1–4, tests + the two-bugs-found-by-decoders writeup).
- §5b checks: diff touches only NEW `GifEncoder.kt` + NEW `GifEncoderTest.kt` + NEW `GifDecodeTest.kt` + board row + spec appendix — inside the owner area. Suite run by me: `GifEncoderTest` 15/15 + `GifDecodeTest` 5/5, 0 failures (fresh `:core:jvmTest` 410/0). The third-party-decoder oracle (`javax.imageio` round-trip) is present as specified.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): frame retention is unbounded — a long animation is an OOM with no refusal
Proof: `GifEncoder.frames` (`GifEncoder.kt:415`) retains every frame's full RGBA until `finish()` (the global palette needs two passes: `countOpaque` then per-frame `quantise`, `:461-464, :496-520`). 1920×1080×4 ≈ 8 MB/frame, so ~300 frames ≈ 2.4 GB — a 30 s scene at 12 fps is ordinary for an animation board and unmakable here, dying in `OutOfMemoryError` rather than a sentence. Mitigating honesty: the caller already holds those arrays (retention adds references, not copies), and the encoder's *own* peak is the output file plus one frame's working set (`IntArray` indices + LZW bytes, freed per frame). So this is a footprint to document/cap at the wiring layer (or a frame-count refusal here), not a leak — filed MINOR (spec-silent edge, crash-class consequence, caller-visible arithmetic).

## Verified (proof — both fixed bugs confirmed present-and-correct in code)
- LSD is 7 bytes (width/height/packed/bg-index/aspect: `:481-485`) with the two-missing-bytes post-mortem in the comment; GCT flag + `log2(slots)−1` size field; bg = transparent slot; aspect honestly 0.
- Table sizing exact: `needed = boxes+1`, power-of-two round-up by hand, `needed ≤ 256` asserted-not-clamped (`:220-234` — a clamp would desync field from table); `minCodeSize = max(2, bits)` (`:249`); empty animation → legal 2-slot table (`:141-143`); padding repeats last colour with the black-band rationale (`:241-248`).
- Median cut deterministic by construction: HashMap → sorted total `RGB_ORDER` (`:145-147`), weighted-median split with the load-bearing end-clamp (`weightedMedian`, `:295-312` — the two-colour-majority case that would otherwise return an empty half and stall the loop, plus the `check` at `:186-189` that makes non-progress impossible), lower-index tie-breaks throughout, integer-division means (`:196-216`).
- Cache soundness: 15-bit key bounded (max 32767 < 32768), `0` a correct miss-sentinel (valid indices start at 1 — `nearest` loops `1 until size` with `size ≥ 2` guaranteed), search on the 8-multiple expansion as documented with the error budget stated (`PaletteMapper`, `:332-361`).
- LZW writer/reader width discipline as specified: writer widens on `nextCode > (1 shl codeSize)` (`:700`), reader mirrors one entry later — the GDI+/ImageIO-oracled rule with the 4×1 separator case cited (`:688-699`); clear-first always, clear-before-reset on full table with width restored (`:707-711`), 12-bit cap (never a 13-bit code), trailing partial byte flushed, final width asserted (`:725-729`).
- Container: GCE disposal-2 + transparency flag + transparent index (`:500-506`); full-canvas non-interlaced descriptors; NETSCAPE ext only when looping with count 0 (`:578-587`); `0x3B` trailer; sub-block splitting with the same stall-guard idiom as `PngWriter` (`:597-624`); `finish()` twice → identical bytes (nothing mutated).
- Delays: half-up rounding, floor 2 cs with the browsers-clamp rationale, u16 ceiling applied pre-rounding so rounding cannot overflow (`delayCentiseconds`, `:372-376`); negative → 2 cs.
- Int-safety: `width·height·4` and offsets proven safe *through* `addFrame`'s Long gate (`:439-443`) — a real array implies `w·h ≤ 536M`, so `count`/`shl 2` in `quantise`/`countOpaque` cannot overflow; per-colour counts bounded by pixel count (Int-safe); `MAX_SCREEN` u16 ceiling consistent with PNG's 65535.
- Timing-wiring note (`frameStartsMs` differences → delays) matches `AnimOps` semantics; no-validate-needed design (delays clamp, sizes require) holds.
- The five test-side bugs are recorded as process lessons in the spec; the oracle lesson ("a decoder that did not write the bytes") is structural in the suite via `GifDecodeTest`, which is the correct fix for the class, not just the instance.

## Recommendation
No send-back. One MINOR (document/cap the frame footprint at wiring time). No BLOCKER or MAJOR open.
