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
