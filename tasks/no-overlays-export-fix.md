# Fix: "no overlays in export" for single-clip / original-canvas projects

## Root cause (already analyzed upstream)

Two related bugs:

1. `buildClipItem` only adds the composite overlay when `outW > 0 && outH > 0`.
   When the project uses the `"original"` canvas preset (no canvas transform)
   and is a single-clip project, `resolveCanvasDims()` returns `null` so
   `outW` and `outH` are both `0`, and the overlay is never added.
2. `isSimpleTrim` short-circuit enables Media3's `trimOptimization` which
   bypasses the effects chain entirely. So even if the overlay were added,
   it would not run. The current "simple trim" path is unaware of overlays.

## Plan

- [x] Add overlay-respect checks to `isSimpleTrim` (text / captions / waveform).
- [x] Add an `else` log line marking the full re-encode path.
- [x] In `buildClipItem`, infer `overlayW`/`overlayH` from the source when
      `canvasDims == null`, falling back to a 1080x1920 default for image
      clips (same convention as `resolveCanvasDims`).
- [x] Log a warning and skip the overlay if dimensions still cannot be inferred.
- [x] Verify with Gradle compile.

## Status

PASS — see final report.
