# Studio transform tool — adversarial audit (Lead, 2026-09-29)

Scope: `app/.../faditor/transform/` — `TransformOverlayView` (2571 lines), `TransformQuad`, `HandleModel`,
the three hosts, and the `mesh/` engine — before Joy Brush reuses it (D.02). The owner reports the
tool is "sometimes a bit buggy". Method: read the touch state machine and the host contract end to
end, looking for sequences a real hand produces that the code does not expect; ran the existing
`tools/jvm-harness` suites for transform, mesh and puppet (they are written for Windows `;`
classpaths — run by hand with `:` on Linux).

## Health of what exists
- **Transform maths suites green:** SpecK round-trip (fuzz 400), SpecL escape, flip, scale-snap,
  rebase (120 cases).
- **Mesh/puppet suites green:** MeshEngine 66/66, PuppetContour 31/31, PuppetSolver 42/42,
  PuppetWeights 49/49, PuppetHole 14/14, PuppetIsland 36/36, PinWarp 16/16, PuppetRigSolver 30/30,
  MeshEasingFit 10/10.
- **One red:** SpecHBendMirrorTest 2 failures — STALE TEST (see T6), not a live bug.

## Findings (most severe first)

**T1 — MAJOR — Adding a finger after a handle drag makes the object jump back.** All three hosts
(`AffineTransformHost`, `SpineTransformHost`, `CornerPinTransformHost`) write a pinch as
`startSize × factor` and `startRot + delta`, with `start*` captured in `beginGesture()` at the FIRST
finger's DOWN. `startPinch` deliberately absorbs a drag in flight without calling `beginGesture`
again (one gesture = one undo), but the pinch's factor/angle are measured from the quad AT THE PINCH.
Sequence: drag a corner to 150 %, put a second finger down → on the first pinch frame
`factor ≈ 1` → the object snaps back to 100 %. Same for a rotate-handle drag then a finger (snaps
back to the old angle). **Fix:** a new `Host.rebaseGesture()` (default no-op) that re-reads the
`start*` values from the object as it is NOW without taking a new undo snapshot; `startPinch` calls
it whenever it absorbs a drag (and on T2's re-pinch).

**T2 — MAJOR — A third finger mid-pinch splits the edit and loses the first part from undo.**
`ACTION_POINTER_DOWN` always calls `startPinch`, which calls `h.beginGesture()` whenever
`dragKind == null` — and during a pinch `dragKind` IS null. So a third finger (a palm edge, a knuckle —
common on a tablet) starts a SECOND gesture (new snapshot) and resets `moved = false`. Whatever the
pinch did before it is now outside any undo step; if the fingers then hold still, nothing commits at
all. **Fix:** if `pinching` already, only re-pick the two tracked pointers (keep the gesture, keep
`moved`, call `rebaseGesture()` and re-seed the pinch start from the current quad).
Related: when a tracked pinch finger lifts while two others remain (`POINTER_UP` with > 2 pointers),
`applyPinch` finds `ia < 0` and the pinch freezes for the rest of the gesture. Re-pick the pair.

**T3 — MAJOR — A drag absorbed into a pinch can end without any undo step.** `startPinch` sets
`moved = false` even when the one-finger drag (or bend-dot drag) it absorbs had already moved and
written the model. If the two fingers then lift without moving, `endPinch` commits only
`if (clean && moved)` → the drag's change stays applied but is never committed: undo skips it and
the next gesture's snapshot swallows it. **Fix:** `moved = moved || bendMoved` when absorbing.

**T4 — MAJOR (animation) — Rotation past the ±180° seam stores the angle the long way round.**
Rotate handle: `deltaDeg = degrees(atan2(now) − atan2(start))` with no unwrapping. The spin arc sits
above the object (−90°), so turning it about 100° counter-clockwise crosses the seam and the stored
rotation becomes +260° instead of −100°. Visually identical — until the object is keyframed: the
animation then spins almost a full turn the wrong way. The pinch has the same flaw (it wraps `th` to
±π relative to the start, so a pinch that turns past 180° jumps by 360°). **Fix:** accumulate the
angle per MOVE with each step wrapped to ±180° (unwrap), never difference against the start angle.

**T5 — MINOR (feel) — A centre-anchored corner scale lags the finger by half.** Corner SCALE
computes its factors with the OPPOSITE corner as origin (`scaleCornerFactors`) but applies them
about the CENTRE (`scaleCornerApplyAboutCentre`, owner ruling 2026-09-13). Box 0..100, drag the
corner from 100 to 150: factor 1.5, corner lands at 125 — the handle slides out from under the
finger and the HUD's "150 %" is not what the finger measured. **Fix:** convert to a centre factor,
`f' = 2f − 1` per axis (exact for parallelograms: the finger's frame coordinate measured from the
centre), then clamp to `MIN_FACTOR`; keep the uniform-snap on `f'`.

**T6 — MINOR (process) — Stale harness test hides future regressions.** `bc949c4` ("A mirrored and
bent picture draws mirrored again") correctly moved the mirror to the source UV only, but
`SpecHBendMirrorTest` still asserts the old shader strings (`uMirror.x * (aLocal.x - 0.5)`), so the
suite is permanently red and a real mirror regression would look like the known failure. **Fix:**
assert the NEW rule (mirror on `aUv`, NOT on `aLocal`) and add the missing on-device note: bc949c4
says "Compiled; not yet seen on a device" — the owner should check a mirrored + bent image once.

**T7 — NIT — `applyPinch` copies `quadLastGood` into `quadAtGrab` every frame** under the comment
"keep the rollback pose current", but nothing updates `quadLastGood` during a pinch, so the copy is a
no-op and the comment describes behaviour that does not exist. Delete it or make it true.

**T8 — NIT — The harness scripts only run on Windows** (`;` classpath separator, `C:/` path rewrite).
A `:`/`;` switch on `uname` would let the cloud Lead run them (it did so by hand for this audit).

## Warp and puppet — what Joy Brush gets almost free
- **Grid warp** (`LatticeDeformer`, Catmull-Rom lattice, fold guard, pose tracks): pure Java, green.
  Joy Brush "warp a selection" = draw the lifted pixels as a textured mesh from `MeshBuffers` in
  Joy Brush's GL engine. New work: ~1 small GL draw path + a Host. Not new maths.
- **Puppet** (alpha contour → triangulator → weights across the body → rigid MLS solver → pose
  track with easing fit): pure Java, green, and Avatar Studio already drives it. Joy Brush's puppet
  board (Phase 7) becomes mostly wiring: the drawing's alpha IS the contour. It also gives animators a
  free "puppet-warp this frame into the next" tool.
- **Not in there:** liquify (push/pinch/twirl as a brush). The planned nudge brush (JB-1.06) covers
  "push"; a full liquify would be new.
- One dependency to cut: `MeshWarpSpec` uses Gson for its JSON — fine inside the app; Joy Brush's
  document stores its own copy of the pose, so the move (D.03) keeps Gson as the kit's dependency.
