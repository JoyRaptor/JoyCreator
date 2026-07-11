# PLAN AV5 — Tape-Waveform Performance + Dead-Code Removal

> **🔴 NOW USER-VISIBLE (JoyRaptor, 2026-07-11): playback and scrolling are CHOPPY with the tape
> live — "not silky smooth like before." This plan is no longer speculative; it is the top
> perf item on the roadmap.** Cause (as Part A predicted): `TapeWaveformRenderer.draw()` runs
> full vector work — path building, per-band `LinearGradient` allocation, two-pass glow
> strokes, spark scan, `columns()` frame scans — on EVERY 60fps `onDraw` for every visible
> audio item, and now ALSO for every open clip-audio drawer (`5179647`). Fix order:
> **A3 tile caching first** (bake each tape into bitmap tiles keyed by clip+zoom+style-epoch;
> blit while panning/playing; only the playhead layer redraws — this alone should restore
> silky scroll), then **A1 envelope mipmaps** + **A2 Uint8 quantization**. The clip-audio
> drawer's shelf body must use the same tile cache (its rect is the clip's segRect × 40dp).
> Quick interim lever if needed before A3 lands: skip fxGlow's double-stroke + sparks while
> `isPlaying || isScrolling` (degrade gracefully under motion, full fidelity at rest).

Scope: quad-band "tape" audio waveform (commits AV1 `2120720`, AV2 `8501b1d`).
Target device: Note-8-class phone (SM-N960U, Adreno 540, 4 GB). Plan only — no code
in this doc. All paths are repo-root-relative under `app/src/main/java/com/fadcam/ui/faditor/`.

Pipeline recap (current, verified in-tree):
- `waveform/BandWaveformExtractor.java` — MediaCodec decode + 4 biquad band chains -> per-hop
  RMS. `HOP = 128` (line 43) -> ~345 env-frames/sec @ 44.1k. Streaming, O(n). Disk cache
  `float`-per-frame (`writeCache` 309-331).
- `model/BandedWaveformData.java` — raw linear RMS, `public final float[][] rms` (line 27),
  `frameAt()` (69), `frameCount()` (56).
- `waveform/BandEnvelopeShaper.java` — dB/gate/gamma/ballistics -> `float[][]` 0..1
  (`shape()` 42-71, `shapeBand()` 73-95).
- `waveform/TapeWaveformRenderer.java` — Canvas draw (`draw()` 52-102, `drawBand()` 104-169,
  `columns()` 196-235).
- `waveform/BandedTimelineWaveformCache.java` — lazy extract + shape + LRU (`MAX_ENTRIES = 32`,
  line 37) + disk cache; holds `Shaped{raw, shaped}` (40-47), `get()` 75-109, `reshapeAll()` 112.

Per-frame hot path (the thing being optimized): `layers/LayerRowRenderer.drawItem`
(~917-950) calls `tapeProvider.get(clip)` then `tapeRenderer.draw(...)` for **every visible
audio item on every `onDraw`** (scroll, fling, playhead tick = 60fps). `EditorTimelineView.onDraw`
(1782) drives it. The playhead is a SEPARATE overlay (`drawCenterPlayhead`), not per-item — which
is what makes tile caching viable.

---

## PART A — AV5 performance plan

### Priority order (do first -> last)
1. **TILE CACHING** (A3) — the big win for many tracks; removes the whole `draw()` cost from
   the pan/play loop. Do this first.
2. **ENVELOPE MIPMAPS** (A1) — removes the per-pixel multi-frame scan; also the data structure
   tiles read from. Natural pairing with A3 (tiles at zoomed-out tiers read mip levels).
3. **QUANTIZE to Uint8** (A2) — memory only; cheap, low-risk, do alongside A1 (quantize the
   mip levels as you build them).
4. **NATIVE / streaming analysis during import** (A4) — largest effort, only affects first-import
   latency, not steady-state draw. Do last / opportunistically.

---

### A1 — Envelope MIPMAPS

**What to change.** `waveform/BandEnvelopeShaper` (add a mip-builder) +
`waveform/BandedTimelineWaveformCache.Shaped` (store the pyramid) +
`waveform/TapeWaveformRenderer.columns()` (pick a level instead of scanning).

**Why it is needed.** `columns()` (196-235) does, EVERY frame, when `framesPerPx >= 1`:
for each of `W` pixels it scans `[f0, f1)` = ~`framesPerPx` frames taking a max (207-212).
A 60s clip drawn in 300px = `60*345/300 ~= 69` frames/pixel x 4 bands x 300px = ~83k float
reads per item per frame. Zoomed further out it is worse.

**Concrete data-structure change.** After `shape()`, precompute a max-pooled pyramid per band:
level 0 = the shaped `float[frames]`; level L = `ceil(len/2)` where
`mip[L][i] = max(mip[L-1][2i], mip[L-1][2i+1])`. Stop at length ~256. Store as
`float[BAND_COUNT][][]` (or `byte[][][]` after A2) on `Shaped`. In `columns()`, when
`framesPerPx >= 1`, pick level `L = floor(log2(framesPerPx))` and read ~1-2 samples/pixel
from `mip[b][L]` (still a max within the residual sub-range so transients survive). The
interpolated (zoomed-in) branch (225-234) is unchanged — it already reads level 0.

**Memory / CPU win.** CPU: the per-pixel inner scan collapses from `framesPerPx` reads to ~1.
Zoomed-out draw goes from O(frames) to O(pixels). Memory: a max-pool pyramid adds ~`len`
extra samples total (geometric series 2x), i.e. +100% over the single shaped band — acceptable,
and A2 makes the whole thing byte-sized.

**Risk.** Low-medium. Level selection off-by-one -> slightly softer or slightly hairier peaks;
mitigate by max-pooling (never mean) and keeping the existing pixel-space 3-tap smooth (214-222).

**Visual change.** None intended. Max-pool preserves the same peak-per-bucket the current
`columns()` max-loop already produces; output should be pixel-identical or imperceptibly softer.

---

### A2 — QUANTIZE envelopes to Uint8 (256 levels)

**What to change.** `waveform/BandEnvelopeShaper.shape()` output type + the mip levels (A1) +
`Shaped.shaped` field in `waveform/BandedTimelineWaveformCache` +
`TapeWaveformRenderer.columns()`/`sample()` read sites (`shapedBand[f]`).

**Why it is needed.** Shaped values are already clamped 0..1 (`shapeBand` 73-95). Storing them as
`float` (4 bytes) wastes 4x vs a `byte` holding `round(v*255)`.

**Concrete data-structure change.** Change shaped/mip storage from `float[]` to `byte[]`
(unsigned 0..255). At read time in `columns()` do `(band[f] & 0xFF) / 255f`. Keep the RAW
`BandedWaveformData.rms` as `float` (it feeds `reshapeAll()` 112 and needs headroom); quantize
only the SHAPED product and its mips. Optionally also quantize the on-disk raw cache
(`BandWaveformExtractor.writeCache` 309-331) later, but that is a cache-version bump — out of scope
for the visual-parity pass.

**Memory / CPU win.** Shaped+mip footprint drops ~4x. With `MAX_ENTRIES = 32` (cache line 37) and
~10s spans (~3450 frames x 4 bands), shaped float ~= 55 KB/clip -> ~14 KB/clip; the LRU ceiling
drops from ~1.8 MB to ~0.45 MB for shaped data. Trivial CPU cost (one mask + divide per read; can
use a 256-entry `float` LUT to avoid the divide).

**Risk.** Low. 256 levels is below the ~1px vertical quantization the renderer already lives with.
Only pitfall: Java `byte` is signed — must mask `& 0xFF` at every read.

**Visual change.** None perceptible (8-bit amplitude in a strip a few tens of px tall).

---

### A3 — TILE CACHING (render once to bitmaps, blit while panning/playing)

**What to change.** `layers/LayerRowRenderer.drawItem` (~917-950) and a new tile store
(new class, e.g. `waveform/TapeTileCache`, keyed by clip id + zoom tier + style epoch).
`TapeWaveformRenderer.draw()` stays as the tile-BAKING function.

**Why it is needed.** Today `tapeRenderer.draw()` — full path building, `LinearGradient` shader
alloc per band per frame (`drawBand` 119-121, 91-92), two-pass glow strokes (145-150), per-pixel
sparks loop (156-167), and `columns()` — runs for every visible audio item on every `onDraw`.
With several audio tracks and a moving playhead this is the dominant cost. Nothing in the waveform
changes between frames while panning/playing; only the viewport offset and the playhead move.

**Concrete data-structure change.** Bake each clip's tape into fixed-width `Bitmap` tiles
(e.g. 512px content-space columns) at the current zoom tier, cached in an LRU keyed by
`clipId | zoomTier | styleEpoch`. In `drawItem`, when a tile exists for the visible span,
`canvas.drawBitmap(tile, srcRect, dstRect, null)` instead of calling `tapeRenderer.draw()`.
Invalidate tiles on: zoom tier change, style change (bump `styleEpoch`; ties into
`reshapeAll()`/`clear()` in the cache 112/122), or waveform-ready swap-in. The playhead already
draws as a separate overlay pass, so it keeps redrawing per frame at full rate over the blitted
tiles — no per-frame waveform work.

**Memory / CPU win.** CPU: pan/play frames drop from "N items x full vector draw" to "N items x
one drawBitmap" — the headline multi-track win. Memory: a tile is `W*H*4`; a 512x48px tile ~=
98 KB. Bound it with an LRU sized to on-screen + a small margin (e.g. 12-16 tiles ~= 1.5 MB).
Prefer `Bitmap.Config.ARGB_8888`; the bands need alpha for the translucent overlays.

**Risk.** Medium — highest-effort of the four. Watch: (a) tile seams (bake 1px overlap or align
tiles to integer content-x); (b) HW-accelerated bitmap upload churn — keep tiles stable and reuse;
(c) invalidation completeness (a missed `styleEpoch` bump = stale tape after a knob change);
(d) memory under many long tracks — LRU must evict off-screen tiles.

**Visual change.** None if tiles are baked with the same `TapeWaveformRenderer.draw()` at the
same density and re-baked on zoom-tier change. Between tiers a tile may be scaled by `drawBitmap`
for a few frames before re-bake — acceptable transient; re-bake on tier settle.

---

### A4 — NATIVE / streaming analysis during import

**What to change.** `waveform/BandWaveformExtractor.extract()` (99-242) and the eager path in
`extractAsync()` (77-97). Optionally an NDK/native filter+RMS kernel for the inner loop (182-199).

**Why it is needed.** The per-sample inner loop (182-199) runs 4 biquad chains + RMS accumulation
in Java on the decode thread; for long imports this is the one-time few-seconds cost the class
javadoc calls out. Two independent improvements: (1) STREAM the shaping/caching during import so
the tape appears progressively rather than after the whole pass; (2) move the biquad+RMS kernel to
native for throughput.

**Concrete data-structure change.** (1) Streaming: emit `BandedWaveformData` in span chunks (the
extractor already supports span-limited passes and quantized spans via the cache's
`SPAN_QUANTUM_MS = 10_000`); flush partial envelope arrays through the existing `onProgress`
channel (57, 169-175) so the cache can shape + swap in tiers as they complete. (2) Native:
replace the `List<Float>` accumulators (139, `toArray` 251-256) with a preallocated `float[]`/
`FloatBuffer` sized from `durationUs*envRate`, and optionally a JNI `processBlock(float[] pcm)`
returning per-hop RMS for the 4 bands. Keep `Biquad` coefficients identical so output matches.

**Memory / CPU win.** CPU: native biquad+RMS is typically 3-5x the Java loop; preallocating kills
the `ArrayList<Float>` boxing/growth garbage. Latency: progressive swap-in makes import FEEL
instant even when total analysis time is unchanged. Memory: bounded preallocation instead of
`ArrayList<Float>` autoboxing (~16 bytes/sample vs 4).

**Risk.** Medium-high (NDK build surface, per-ABI testing on SM-N960U arm64). Streaming alone
(no native) is lower risk and captures most of the perceived-latency win — do that first if A4 is
attempted. Native kernel must bit-match the Java `Biquad` or the disk cache key/version must bump.

**Visual change.** None (same coefficients, same HOP, same RMS). Progressive swap-in changes only
WHEN the tape appears, not what it looks like.

---

## PART B — Dead-code removal plan

Method: greps run against the current `joy-creator` working tree, not the (stale) LANES.md punch
list. **Correction to the punch list:** the large `drawLayers` / `hitTestLayer*` / `hitTestLayerRow`
/ `hitTestLayerTap` / `activeLayerIndex` / `Drag.LAYER_*` / `selectedLayerKind` / `selectedLayerValue`
/ `doLayerDrag` / `layerSibling*` / `sameLayerLane` subsystem is **ALREADY REMOVED** (handoff.md
records commit `96cba7f`, "506 lines removed"). Grep of `EditorTimelineView.java` for all those
symbols now returns only the unrelated layout constants `LAYER_ROW_HEIGHT_DP` / `LAYER_ROW_GAP_DP`
/ `LAYER_TOP_GAP_DP` (94-96, used at 1715-1716, 1729, 2206) — those are LIVE geometry, **not**
`Drag.LAYER_*`. Nothing left to remove there.

Remaining removal candidates below.

### B1 — Orphaned layer-row listener callbacks — CONFIRMED-DEAD

The six "tap / long-press a read-only layer row" callbacks are declared, implemented, but **never
invoked** (their only caller was the removed `hitTestLayer*` path).

Declarations — `timeline/EditorTimelineView.java`, `OnSegmentActionListener` interface:
- `onOverlayLayerTapped(int)` — line 1032
- `onVisualizerLayerTapped(int)` — line 1033
- `onCaptionLayerTapped(int)` — line 1034
- `onOverlayLayerLongPressed(int)` — line 1036
- `onVisualizerLayerLongPressed(int)` — line 1037
- `onCaptionLayerLongPressed(int)` — line 1038

Implementations (all with real bodies, all now unreachable) —
`FaditorEditorActivity.java`:
- `onVisualizerLayerTapped` 1742-1749
- `onOverlayLayerTapped` 1752-1759
- `onCaptionLayerTapped` 1762-1768
- `onVisualizerLayerLongPressed` 1771-1797
- `onOverlayLayerLongPressed` 1800-1808
- `onCaptionLayerLongPressed` 1811-1817

**Evidence of dead:** grep for the invocation forms
`.onOverlayLayerTapped(` / `.onVisualizerLayerTapped(` / `.onCaptionLayerTapped(` /
`.on*LayerLongPressed(` across `app/src/main/java` -> **No matches** (zero call sites). The only
non-declaration/non-implementation hits are a stale reference comment in
`FaditorEditorActivity.java:10677` and design doc `tasks/PLAN_asset_browser_v2_layers.md`
(308-320) — neither is code that calls them.

**Status:** CONFIRMED-DEAD (all six). The task named caption/viz; the overlay pair is the same
retired mechanism and equally unreferenced — remove all six together (interface decls 1032-1038 +
the six activity overrides) for a coherent pure-removal commit. NOTE for the executor: some bodies
call live helpers (`showVisualizerStylePicker`, `showObjectMenuSheetForTextOverlay`,
`openCaptionKeyframeDrawer`, `deleteVisualizerWithConfirmation` path, etc.) — those helpers are
reached elsewhere and must NOT be removed; only delete the six override methods and the six
interface declarations. Verify each helper still has another caller before deleting anything beyond
the six methods.

### B2 — Caption delete-badge no-op — STILL-REFERENCED (live wart; guard, not pure-removal)

Not dead code — it is a live path that does nothing for captions. `LayerRowRenderer` draws the
delete-badge roundel for ANY selected item wide enough (`deleteBadgeCx` non-NaN), unconditional on
kind — draw block `layers/LayerRowRenderer.java:1273-1299`, hit-zone `1559-1571`. But the handler
`FaditorEditorActivity.onItemDeleteRequested` (10317-10333) branches only on `getTextOverlay()`,
`getAudioClip()`, `getClip().isOverlayClip()`, `getWaveform()` — there is **no `getCaptionSpan()`
branch**, so a selected caption shows a trash badge whose tap falls through to nothing.
(Confirmed intent: comment `FaditorEditorActivity.java:9615` — "Captions (clip-owned, no delete
semantics)".)

**Status:** STILL-REFERENCED (badge draw + hit-test are live). This is NOT a pure deletion.
Recommended fix (for the executor, pick one): (a) suppress the badge for caption items —
add a caption guard where `deleteBadgeCx` is computed / drawn in `LayerRowRenderer` (1279-1280)
so captions never render it; OR (b) give `onItemDeleteRequested` a `getCaptionSpan()` branch that
disables captions on the clip with an undo step. Do NOT remove the badge machinery wholesale —
it is live for text/audio/overlay/visualizer items.

### Out of scope (explicitly not removed)
- Old audio path `drawAudioTrack` / legacy audio hit-tests — LANES.md flags this as the PRIMARY
  audio UI in some notes and RETIRED/gated in others; contradictory, subsystem-sized, and
  interactive. Do not touch in a dead-code pass; needs its own audited slice.
- `LAYER_ROW_*_DP` constants in `EditorTimelineView` — LIVE layout geometry (see B header).

---

## Summary of highest-value work
- **A3 tile caching first** — biggest steady-state win; removes the full vector tape draw from the
  60fps pan/play loop for every visible audio item (blit bitmaps, redraw only the separate playhead).
- **A1 mipmaps second** — kills the per-pixel multi-frame max-scan in `columns()` (zoomed-out draw
  goes O(frames)->O(pixels)) and provides the tiered data tiles read from.
- **A2 quantize** rides along with A1 for a ~4x memory cut on shaped/mip data, no visual change.
- **B**: the large `drawLayers`/`Drag.LAYER_*` subsystem is already gone (`96cba7f`); only the six
  orphaned `on*Layer{Tapped,LongPressed}` callbacks are CONFIRMED-DEAD and safe to delete; the
  caption delete-badge is a LIVE no-op to guard, not remove.
