# JB-3.01 — Animation model operations: frames, holds, duplicate, link, timing

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (Built: `JbDocument`, `Board`, `Frame`, `Layer`, `Cel`, `DocOps.validate`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/AnimOps.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/AnimOpsTest.kt` |
| **Estimated size** | ~250 lines + ~250 lines of tests |

## Goal
The animation board's film strip (JB-3.03) needs every frame operation as pure, tested document
maths: add a frame, duplicate or link it, hold it longer, delete it, move it, and "which frame shows
at time t". Pixels are not copied here — operations that need pixel work RETURN instructions for the
engine.

## Contract
```kotlin
package cc.joycreator.joybrush.core.doc

/** Pixel work the engine must do after an op (tile copies are cheap on the GPU). */
sealed class CelWork {
    data class CopyCel(val layerId: String, val fromCelId: String, val toCelId: String) : CelWork()
    data class DropCel(val layerId: String, val celId: String) : CelWork()
}
data class AnimResult(val doc: JbDocument, val work: List<CelWork>)

enum class NewFrame { BLANK, DUPLICATE, LINK }

object AnimOps {
    /** Turns a static layer into one animated in [boardId]: its existing cel becomes frame 1's cel for EVERY frame. */
    fun animateLayer(doc: JbDocument, layerId: String, boardId: String): JbDocument
    /** Inserts a frame after [afterFrameId] (null = at the start). For each layer animated in the board:
     *  BLANK → a new empty cel; DUPLICATE → a new cel + CopyCel from the source frame's cel; LINK → the SAME cel id. */
    fun addFrame(doc: JbDocument, boardId: String, afterFrameId: String?, mode: NewFrame, ids: () -> String): AnimResult
    /** Removes a frame. A cel no other frame uses is removed from the layer and a DropCel emitted. Refuses to delete the last frame. */
    fun deleteFrame(doc: JbDocument, boardId: String, frameId: String): AnimResult
    fun setHold(doc: JbDocument, boardId: String, frameId: String, holdFrames: Int): JbDocument   // clamps 1..999
    fun moveFrame(doc: JbDocument, boardId: String, frameId: String, toIndex: Int): JbDocument
    /** Which frame is showing at [timeMs] when playing the board once from 0 (looping: pass timeMs mod total). */
    fun frameAt(board: Board, timeMs: Double): Frame
    fun totalDurationMs(board: Board): Double   // Σ holdFrames × 1000 / fps
    /** Start time of each frame, same order as board.frames. */
    fun frameStartsMs(board: Board): List<Double>
}
```

## Decisions
1. Every function returns a NEW document (immutable data classes); `DocOps.validate` must return no
   problems after every operation on a valid input — assert this in every test.
2. Refusals throw `DocException` with a readable message (unknown ids; deleting the last frame;
   animating a layer already animated; toIndex out of range).
3. `addFrame` new frame: `Frame(id = ids(), holdFrames = 1)`; new cels `Cel(id = ids())`.
4. `frameAt`: times at or beyond the end return the last frame; negative returns the first.
5. Layers not animated in the board are untouched by every op.

## Tests (AnimOpsTest)
Build a document with DocOps.newDocument + an ANIMATION board (fps 12, 1 frame) + two layers, one
animated there, one static.
1. animateLayer → every frame maps to the original cel; validate clean.
2. addFrame BLANK/DUPLICATE/LINK: cel counts, frameCel mapping, CopyCel emitted only for DUPLICATE.
3. deleteFrame of a linked frame keeps the shared cel (no DropCel); of an unshared frame drops it.
4. deleting the only frame throws.
5. setHold 3 at 12 fps → that frame lasts 250 ms; totalDurationMs and frameStartsMs agree.
6. frameAt at boundaries (exact start of frame 2 → frame 2), negative, past the end.
7. moveFrame reorders; the static layer and other boards are unchanged.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
No pixels, no engine calls, no UI. Don't change DocModel types.

## Definition of done
Tests pass (paste) · commit `JB-3.01: animation model` · ROADMAP row → 🟧 Built.

## Questions
