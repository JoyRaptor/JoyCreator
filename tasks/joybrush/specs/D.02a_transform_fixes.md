# D.02a — Fix the Studio transform tool's four gesture bugs (before it becomes shared)

| | |
|---|---|
| **Tier** | T2 (T1 review) + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | — (must land BEFORE D.02 moves these files) |
| **Owner area** | EDIT `app/src/main/java/com/fadcam/ui/faditor/transform/TransformOverlayView.java`, `AffineTransformHost.java`, `SpineTransformHost.java`, `CornerPinTransformHost.java`; EDIT `tools/jvm-harness/SpecHBendMirrorTest.java`; NEW `tools/jvm-harness/GestureAngleTest.java` + `run-gestureangle.sh` |
| **Estimated size** | ~120 lines changed + ~150 lines of tests |

## Goal
The owner likes the Studio transform tool but finds it "sometimes a bit buggy". The Lead's audit
(`tasks/joybrush/reviews/STUDIO_TRANSFORM_AUDIT__lead.md`) found the reasons. Fix T1–T6 exactly as
described there. Joy Brush reuses this tool, so it gets the fixes too.

## Steps
1. **Host contract (T1):** add to `TransformOverlayView.Host`:
   `default void rebaseGesture() { }` with KDoc "Re-read the start values (centre, size, rotation)
   from the object AS IT IS NOW, WITHOUT a new undo snapshot — the gesture continues." Implement it in
   the three hosts: copy each host's `beginGesture()` body MINUS the snapshot/`target.beginGesture()`
   / `before = …` line (e.g. `AffineTransformHost`: the `startCx … startH` block only; keep
   `SpineTransformHost.before` and `CornerPinTransformHost.hadPinKeys` untouched).
2. **startPinch (T1, T2, T3):**
   - If `pinching` is ALREADY true (third finger): re-pick the pair = the first two pointers that are
     still down, call `h.rebaseGesture()`, re-seed `quadAtGrab`, `pinchA*/B*`, `pinchPivot*`,
     `pinchStartDeg`, reset `pinchFactor/pinchDeg` — and do NOT call `beginGesture`, do NOT touch `moved`.
   - Absorbing a one-finger drag or a bend drag: compute `boolean carried = (dragKind != null && moved) || bendMoved;`
     BEFORE clearing them, call `h.rebaseGesture()` when `dragKind != null || bendDragIndex >= 0`,
     and set `moved = carried` (not false).
   - `ACTION_POINTER_UP` while pinching with more than 2 pointers: if the lifted pointer is A or B,
     re-pick the pair from the remaining pointers exactly as the third-finger case (same helper).
3. **Rotation unwrapping (T4):** rotate handle — keep `rotLastRad`; on each MOVE add
   `wrap(ang − rotLastRad)` (to ±π) to an accumulated `rotAccumRad`; `deltaDeg = degrees(rotAccumRad)`.
   Pinch — same with the finger-pair angle (`pinchLastRad`, `pinchAccumRad`) replacing the
   start-relative `th`. The dead-zone, detents and snap then run on the unwrapped value unchanged.
4. **Corner scale about centre (T5):** in `applyDrag` CORNER/SCALE, after `scaleCornerFactors`,
   convert each factor `f → 2f − 1`, clamp to `TransformQuad.MIN_FACTOR..MAX_FACTOR`, THEN
   `snapUniformFactors`. (Only the centre-anchored path; `scaleCorner()` itself is unchanged.)
5. **Stale test (T6):** `SpecHBendMirrorTest` asserts the NEW shader rule: mirror applied to `aUv`,
   and NOT to `aLocal`. Delete T7's no-op copy and its comment.
6. **New test `GestureAngleTest`** (plain Java, harness style, no Android): extract the two pure
   helpers the steps need into `TransformQuad` (`static float wrapRad(float)`,
   `static float centreFactor(float f)`), then test: 20 steps of +10° starting at 170° accumulate to
   +200° (not −160°); centreFactor(1.5) = 2, (1) = 1, (0.2) clamps to MIN_FACTOR. Script
   `run-gestureangle.sh` in the same style as `run-speck.sh`.

## Verification
- Harness: `run-speck.sh`, `run-escape.sh`, `run-flip.sh`, `run-scalesnap.sh`, `run-rebase.sh`,
  `run-spech.sh` (now green), `run-gestureangle.sh` — paste the last lines.
- Watcher `build.log` green with `:app:compileDebugJavaWithJavac` EXECUTED.
- **Owner check on the phone (T3, 3 minutes)** in the Studio, on an image:
  1. drag a corner bigger, keep the finger down, add a second finger → no jump back;
  2. pinch with two fingers, rest a third finger briefly, keep pinching, lift all → ONE undo puts
     it back where it started;
  3. turn the spin arc about a quarter turn anticlockwise, key it, key 0° a second earlier, play →
     it turns the short way;
  4. drag a corner: the corner stays under your finger.

## Do not
Change no other behaviour: the dead-zone, detents, snap, hold-to-pinch and the bend net stay as they
are. Do not move files (that is D.02). Never run gradle on the owner's PC (the watcher builds).

## Definition of done
Harness + watcher green (paste) · owner check noted · commit `D.02a: transform gesture fixes` ·
ROADMAP row → 🟧 Built.

## Questions
