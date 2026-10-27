# Symmetry recovery (SYMMETRY-COMPLETE), 2026-10-27

Agent: Claude Sonnet 5.5 session "Joybrush roadmap task (sibling)". Source only; nothing installed, no phone.

## What

- Checkout `C:/Temp/jb-symmetry-recover-20261027`, branch `claude/symmetry-recover-20261027`.
- Base `bdd3f8ff5` (tip of `lead/select-reconcile-20261024` when I started), merged with `lead/symmetry-e` (`789bef0af`, the latest symmetry code; not on origin).
- Exclusive paths: the symmetry files of the audit (SymmetryMath, SymmetryLane(s), MediaLane, SymmetryCapNote, SymmetryWidgetView, tests) plus the merge hunks in JbCanvasView / MediaCanvas. Hot files are NOT changed on the coordinator; this is a private candidate for the Engine Lead.

## Conflicts (5 hunks, 2 files) and how each was resolved

| File | Hunk | Resolution |
|---|---|---|
| JbCanvasView | `preset` setter | Both lines kept: watercolour prewarm and `refreshSafeZone()` |
| JbCanvasView | `cancelStroke` | Symmetry's `lineContacts` cancel, then the coordinator's `cancelMediaStroke()` (give the stroke up, no step). Symmetry's older "keep it as a step" line dropped on purpose (the 10-21 owner finger-daub fix) |
| MediaCanvas | `begin` | Coordinator's `payOwedStores` kept, then symmetry's pin/zone block; coordinator's `scale <= 0.0` check moved to `preset.mediaScale()` |
| MediaCanvas | `end` | Per-lane tails, coordinator's `try/finally` (frame that cannot be stored must not leave the stroke open), `unpin()` inside the `finally` |
| MediaCanvas | `buildLanes` | Symmetry's lane builder; coordinator's `engine.wetGran = brush.gran` carried into the wet lane |
| MediaCanvas | `cancel()` (no marker, semantic) | `unpin()` added so a cancelled stroke does not leave the window pinned |

## Known limits of the merge

- The coordinator's media layer is now the **atlas** (`MediaAtlas`) beside `MediaWindow`. Symmetry's media pin only exists on `MediaWindow`; with an atlas the code refuses in words ("Symmetry is not available for pencil, watercolour and oil on this build"). Realistic-brush symmetry on the atlas is NOT done (needs Engine Lead design: spec E4 phase 3, owner yes on the memory trade).
- Ordinary stamp brushes, Unibrush, eraser and editable lines are the supported set.

## Proof

See the end of this file (filled in as the runs finish).

### Compile fixes found by the first androidkit build (not conflict markers)

- The coordinator's `PasteSpacing` (a phone behind the pen paints oil steps further apart) read `stroke.paste`; with lanes it now steps every lane's own paste stroke.
- The bench probes (`MediaSeamProbe`, `MediaUndoProbe`) feed already-read `MediaSample`s; symmetry's `MediaCanvas.add` takes `PenSample`s. New `addSamples(List<MediaSample>)` (the ordinary no-symmetry stroke) keeps the probes unchanged in behaviour.

### Proof (exact tree = the commit this file is in)

| Check | Result |
|---|---|
| `:core:jvmTest` filtered to the 12 symmetry-related suites (SymmetryMath 21, SymmetryLane 13, MediaLane 6, MediaSafety 7, BrushDabber 30, Scatter 14, GuideSettings 12, GuideLines 11, GuideSnapper 17, DirectionTracker 12, Paint 13, MaskPaint 24) | 180 tests, 0 failures, 0 errors, 0 skipped (XML counted) |
| `:androidkit:compileKotlin` with the merged JbCanvasView / MediaCanvas | passes |
| `:androidkit:test` SymmetryCapNote 4, SymmetryLineRecords 11, MediaWindowPin 3 | 18 tests, 0 failures, 0 errors, 0 skipped |

### NOT done

- Full `:core:jvmTest` / `:androidkit:test` (only the symmetry-related classes ran, so a regression elsewhere from the merged hot files is not excluded).
- `joybrush-android` (Activity, SymmetryWidgetView, GuideOverlayView) not compiled: free RAM was 1.8 GiB, under the 2.2 GiB guard.
- No APK, no phone, no pixel parity of the ordinary stroke with symmetry off.
- Realistic-paint symmetry on the atlas (refuses in words).
