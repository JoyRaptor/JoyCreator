# JB-2.02 — Zoom, pan, rotate, and the tap gestures (undo / redo)

| | |
|---|---|
| **Tier** | T2 (T1 review — touches the drawing view) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05 (screen exists), JB-0.07 (Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/view/ViewTransform.kt` + test `.../commonTest/.../view/ViewTransformTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/CanvasGestures.kt`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (only as listed below) |
| **Estimated size** | ~350 lines + ~150 lines of tests |

## Goal
Draw big, zoom in for detail, turn the page like paper. The standard gestures every painting app
shares (research R5/R6): two fingers pan/zoom/rotate, two-finger TAP = undo, three-finger TAP = redo.
After a pen has been seen, fingers navigate and never draw (owner's ruling).

## ViewTransform (core, pure maths)
```kotlin
package cc.joycreator.joybrush.core.view
class ViewTransform {
    var zoom: Float = 1f          // screen px per document px, clamped to 0.05..64
    var rotation: Float = 0f      // radians
    var panX: Float = 0f          // screen position of document (0,0)
    var panY: Float = 0f
    fun docToScreen(x: Float, y: Float): Pair<Float, Float>
    fun screenToDoc(x: Float, y: Float): Pair<Float, Float>
    /** Column-major 3×3: document px → clip space for a viewport w×h (y up in clip). */
    fun docToClip(w: Int, h: Int): FloatArray
    /** Applies a two-finger gesture step: the document point under oldCentroid ends up under newCentroid,
     *  scaled by scaleFactor and rotated by dRotation about it. */
    fun applyPinch(oldCx: Float, oldCy: Float, newCx: Float, newCy: Float, scaleFactor: Float, dRotation: Float)
    /** When the pinch ends: if rotation is within 7° of a multiple of 90°, snap to it (keeping the centroid fixed). */
    fun snapRotation(cx: Float, cy: Float)
    /** Fit a document rectangle into a w×h view with a 5% margin, rotation 0. */
    fun fit(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int)
}
```
docToScreen = translate(pan) · rotate(rotation) · scale(zoom). docToClip = (screen → clip:
x·2/w − 1, 1 − y·2/h) · docToScreen.

## CanvasGestures (androidkit)
A small state machine fed every MotionEvent that is NOT a drawing stroke:
- **Two or more fingers down and moving:** centroid, distance and angle between the first two
  pointers → `applyPinch` each move. Rotation dead-zone: ignore rotation until it exceeds 7° in this
  gesture (then follow fully). On lift → `snapRotation`.
- **Tap detection:** a gesture is a TAP if every finger lifted within **250 ms** of the first going
  down and no finger moved more than **20 px**. Max pointer count 2 → `onUndo()`, 3 → `onRedo()`,
  4 → `onToggleUi()` (a callback; the activity may ignore it for now).
- **One finger drag** (only after a pen has been seen): pan.
- Callbacks: `onViewChanged()`, `onUndo()`, `onRedo()`, `onToggleUi()`.

## JbCanvasView edits (exactly these)
1. Add `val view = ViewTransform()`; `onDrawFrame` uses `view.docToClip(viewW, viewH)` instead of
   `viewToClip`.
2. Pen samples: `MotionEventSamples.from(ev, idx, { x, y -> view.screenToDoc(x, y) }, view.rotation)`,
   and `StrokeSmoother(smoothing, screenPerDoc = view.zoom)`.
3. Routing: a pen (stylus/eraser) always draws. A finger draws only if no pen has been seen AND only
   one finger is down; the moment a second finger lands during a finger stroke, cancel that stroke
   and hand the event stream to `CanvasGestures`. After a pen has been seen, all finger events go to
   `CanvasGestures`.
4. Wire `onUndo` → `undo()`, `onRedo` → `redo()`, `onViewChanged` → `requestRender()`.
5. On first layout, `view.fit(0, 0, w, h …)` so document (0,0) is the top-left of the screen (zoom 1).

## Tests (ViewTransformTest)
1. screenToDoc(docToScreen(p)) == p (within 1e-3) at several zoom/rotation/pan values.
2. applyPinch keeps the document point under the centroid fixed.
3. zoom clamps to 0.05..64. 4. snapRotation: 85° → 90°, 80° stays. 5. fit centres a rect with 5% margin.
6. docToClip maps the view's corners to (±1, ±1) with y flipped.

**Command:** `./gradlew -p joybrush :core:jvmTest :androidkit:compileKotlin` — green. Then the
watcher build + sandbox phone: pinch, rotate, 2-finger tap undo, 3-finger tap redo; screenshot.

## Do not
Do not add pen-button behaviour, rotation handles or any UI chrome. Do not change the engine.

## Definition of done
Tests pass (paste) · device screenshot · only owner-area files · commit `JB-2.02: zoom, pan, rotate,
tap gestures` · ROADMAP row → 🟧 Built.

## Questions

_(Builder: subagent of openrouter/stealth/space-bunny-alpha, 2026-09-28. It could not run gradle; I
ran `:core:jvmTest` myself — **410 tests, 0 failures**, 16 of them `ViewTransformTest` — and the
watcher's `build.log` shows `:joybrush:androidkit:compileKotlin` EXECUTED inside
`BUILD SUCCESSFUL`. Eight questions; six are mine to rule, two are the Lead's.)_

1. ✅ **Edit 5's `fit` call contradicts its own parenthetical** (`fit(0, 0, w, h …)` "so that
   document (0,0) is the top-left at zoom 1" — but that call gives zoom ≈ 0.909 and centres the
   page). **Ruling: the stated outcome wins.** `fit` is a pure function of a viewport and a document
   rect; on first layout the caller has no document bounds (JB-0.08b builds them), so it passes a
   rect equal to the viewport and the result is identity. A real fit arrives with the bounds. No
   behaviour change now, and the function is not dead — it is waiting for its input.
2. ✅ **The 5% fit margin.** **Ruling: 5% of the view on each side, so the content occupies 90%.**
   Provisional — it is one named constant and cheap to change.
3. ✅ **Palm rejection vs "after a pen has been seen, all finger events go to the gesture machine."**
   **Ruling: palm rejection stays a hard ignore**, and it wins. A palm resting on the Note 9's
   screen is a physical fact and the owner's own constraints make fingers navigate only *after* a
   pen; a page that drifts under a resting palm is worse than a gesture that needs two fingers
   lifted and re-placed. `penSeen` decides only whether **one** finger pans.
4. ✅ **Two fingers navigate even before any pen has been seen.** **Ruling: confirmed.** The owner
   ruled that fingers *draw* until a pen appears, and a second finger is not drawing. This also
   matters practically: the sandbox phone the device check uses has no pen, so a pen-gated pinch
   would be untestable there.
5. 🔴 **For the Lead: `Brush.sizePx` is now in document px.** At 4× a "12 px" brush is 48 screen px.
   That is what Procreate and Infinite Painter do and the agent was right not to touch the radius
   maths, but it is a real behaviour change at zoom ≠ 1 and it lands on **JB-2.16**, which will have
   to decide whether dragging the brush swatch scales `size.base` in document px or screen px.
6. 🔴 **For the Lead, and it is a real (if small) defect: four `Float`s cross the thread boundary
   un-synchronised.** `onDrawFrame` reads `zoom`/`rotation`/`panX`/`panY` written by the UI thread.
   The spec's contract is plain `var`s in `commonMain` and `@Volatile` is not automatically
   available there. Floats do not tear, so the worst case is a single frame combining an old zoom
   with a new pan — a visible wobble while pinching, never a wrong document. The fix is
   `@kotlin.concurrent.Volatile` on the four fields (stdlib, not a new dependency) or one immutable
   per-frame snapshot handed across. **This will be visible on a real phone, so it belongs on the
   T1 review list rather than in a hole.**
7. ✅ **The tap mapping is hard-coded in one 3-line function.** Correct call: the owner's "two- and
   three-finger gestures are user-assignable" is JB-2.02b, and keeping it in one place is what
   makes lifting it out cheap.
8. ✅ **Two numbers the agent chose:** `MIN_SPREAD_PX = 12` (below that finger separation the
   two-finger angle is treated as noise and the move only translates) and arming the rotation dead
   zone on the frame *after* the 7° crossing, so the page never jumps by the dead zone. Both
   provisional, both sensible, both one constants.
