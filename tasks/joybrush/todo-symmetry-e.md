# Phase E — symmetry for the realistic brushes (pencil, watercolour, oil)

Worktree: `C:/Temp/jb-symmetry-e` (branch `lead/symmetry-e`, from `lead/symmetry-1019`).

## The design (settled, from the Lead's brief)

- ONE 1024×1024 media window, PINNED on the symmetry centre `C` for the whole stroke: every copy's
  wet paint lives in ONE water field, and the window never moves (a move is a flush + reload).
- The window's origin is TILE-ALIGNED and placed by ROUNDING `C` (`placeForRounded`), so `C` is
  never nearer than 384 px to an edge. The safe radius is COMPUTED, never a constant:
  `R = min(C − left, right − C, C − top, bottom − C) − MARGIN(128) − reach − pad(4)`.
- `R ≤ 0` ⇒ no safe zone ⇒ the stroke is REFUSED in words ("This is the largest area your device
  can safely paint with this brush."). A sample outside `R` is never painted: no partial copies,
  no clipped dabs.
- One media stroke holds N LANES (each its own spline and its own wet/dry/paste builder), all
  sharing the ONE engine, the ONE window and the ONE undo step.

## The steps

- [x] core: `MediaWindowMath.placeForRounded` (the rounded pin).
- [x] core: `MediaSpec.reachDocPx` (the brush's own reach: 264 / 90 / 120 / 440 doc px).
- [x] core: `MediaSafety` (the radius, the zone, the refusal sentence).
- [x] core: `MediaPin` (may the window move? does the frame run whole or is it refused?).
- [x] core: `MediaLane` / `MediaLaneSet` + `SymmetryLanes.buildMedia`; the media families are no
      longer refused in words — their caps apply.
- [x] core: `DabBatch.merge` (the copies' dabs in ONE frame, so the water runs once).
- [x] androidkit: `MediaWindow.pin` / `unpin`; every move is a no-op while pinned.
- [x] androidkit: `MediaCanvas` — the lanes, the pin, the refusal channel (`add` returns the
      sentence; the frame's refusal waits in a volatile field).
- [x] androidkit: `JbCanvasView` — the guide and the preset reach the canvas; the safe radius is
      exposed for the overlay.
- [x] app: `GuideOverlayView` — the dotted circle and the 20 % darkening outside it.
- [x] app: `JoyBrushActivity` — the overlay is told the brush family and the radius.

## The tests

- [x] core `media/MediaSafetyTest`: the floor vs the rounding placement, the reach subtracted, the
      negative radius (the pencil) ⇒ the refusal, the per-side test.
- [x] core `symmetry/MediaLaneTest`: the lane's samples are the transformed ones, the lanes share
      the seed, the cap applies to the media families.
- [x] androidkit `gl/media/MediaWindowPinTest`: the pinned window does not move; a frame that does
      not fit is REFUSED, not clipped; the copies' dabs go into ONE frame (one undo step's worth).

## The adversarial self-test

Each new test was proved able to fail: the implementation was broken on purpose and the test was
seen to fail. The breaks are named in the commit message and in the final report.

| the break | the test that caught it |
|---|---|
| the safe radius used the window's CENTRE instead of its actual origin | `MediaSafetyTest.theRoundedPinGivesTheCentreAtLeast384PxOnEverySide`, `theReachIsSubtractedFromTheRadius`, `theSafeRadiusOfEachMediaFamily`, `aBrushWithNoSafeZoneIsRefusedInWords`, `theZoneIsJudgedFromTheCentreAndNotFromTheWindowsEdges` |
| every media lane had its own seed | `MediaLaneTest.theMediaLanesShareTheSeed` |
| the copies' dabs were not merged into one batch | `MediaLaneTest.theCopiesDabsGoIntoOneBatch` |
| the safe zone was a SQUARE about the centre instead of the circle | `MediaSafetyTest.theZoneIsJudgedFromTheCentreAndNotFromTheWindowsEdges` |
| the media families were not capped | `MediaLaneTest.theCapAppliesToTheMediaFamilies` + the existing `SymmetryLaneTest.theCapsAreTheOwnersAnswerAndTheyAreCountedInCopies` and `aGuideThatAsksForMoreCopiesThanTheCapGetsExactlyTheCapInCopies` |
| the media lane did not transform the sample | `MediaLaneTest.theMediaLanesSamplesAreTheTransformedOnes`, `anAsymmetricGuideCopiesThePositionAndNotTheLean` |
| the pinned window FOLLOWED the pen | `MediaWindowPinTest.aPinnedWindowDoesNotMoveAndAnUnpinnedOneTriesTo` |
| a frame that did not fit was CLIPPED instead of refused | `MediaWindowPinTest.aFrameThatWouldNotFitIsRefusedAndNotClipped` |
