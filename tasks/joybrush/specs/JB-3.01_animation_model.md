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

*From the JB-3.01 builder, 2026-09-28. None of these block the build — `DocModel.kt` and `DocOps.kt`
are untouched and nothing above contradicts them. They are the four things the contract above left
open, each of which a later task would otherwise have to guess at, plus one thing the validator does
not check.*

1. **`moveFrame`'s `toIndex` — final index or drop gap?** Built as the index the frame **ends up
   at**, so the legal range is `0..frames.size - 1` and `toIndex == frames.size` is REFUSED. A drag
   handler that reports "the gap after the last frame" will therefore throw and must clamp. This is
   deliberate (one meaning beats two) but JB-3.03's film strip has to know it.
2. **An ANIMATION board with no frames** (which `DocOps.validate` rule 4 rejects). Built as:
   `addFrame(BLANK)` **works** — it is how such a board is made into a board, and it leaves a
   document that validates clean. `DUPLICATE`, `LINK`, `animateLayer`, `deleteFrame`, `setHold` and
   `moveFrame` all refuse, as does `frameAt` (it must return a `Frame` and there is none to return).
   `totalDurationMs` and `frameStartsMs` are **total**: 0.0 and an empty list. A board that is empty
   is an empty schedule; a board whose `fps` is outside **1..60** is a broken one, and all three
   time functions refuse that in words.
   **The rate guard is `DocOps.validate` rule 4's own `fps !in 1f..60f`, on purpose** (added after a
   first run: a `fps <= 0` guard let `Infinity` through, and an infinite fps makes every frame 0 ms
   long, so `totalDurationMs` answered 0.0 and `frameAt` answered "the last frame" at every time —
   a confident wrong answer from a document the validator had already called broken). `AnimOps` must
   not be able to call a board playable that `validate` calls broken, so it uses the same range in
   the same idiom. JB-3.0x: **do the same for any other number you do arithmetic on.**
3. **`addFrame`'s SOURCE frame when `afterFrameId` is null** (inserting at the start). Built as the
   frame that is **currently first** — the one the new frame lands on top of, which is the frame the
   playhead is on when somebody taps "add frame here". `DUPLICATE` and `LINK` are refused on an
   empty board rather than quietly doing something else.
4. **`addFrame` id allocation.** Ids are drawn in a fixed SHAPE — the new FRAME first, then one cel
   per animated layer, in the order the layers sit in the document — and an id the document is
   already using anywhere is **refused** in words, which is stricter than `DocOps.validate` (that
   only forbids a repeat *within* one list). One flat rule beat four namespaces to remember; say the
   word if the Lead wants it loosened to the per-namespace rule.
   The shape is for DIAGNOSIS only. **Which layer gets which id is not part of this contract**: the
   first test run proved that the hard way, when a test asserting `[c-anim, gen1]` failed with
   `[c-anim, gen2]` — correct behaviour (the fixture's bottom layer animates first and so drew the
   earlier number), wrong test. Every test now asserts structure — two cels, the right mapping,
   `validate` clean, no orphans — and **exactly one** test pins the shape on purpose. A caller must
   read ids out of the returned document, never assume them.
5. **`DocOps.validate` does not require every cel of an animated layer to be shown by some frame** —
   an orphan cel is *valid* as far as the validator is concerned. `AnimOps` never produces one
   (`deleteFrame` drops a cel no remaining frame points at, and emits `DropCel` for the engine to
   free), and a 300-step randomised test asserts the invariant anyway. JB-3.0x should not assume
   the validator will catch a leak.

*From the JB-3.01 fix pass, 2026-09-29, after mimo's review. Code is in and tested; these four are
the decisions the fix had to make on the spec's behalf and the ones the Lead still owns.*

6. **Two frames of one board with the same id: FORBIDDEN, so every frame operation refuses.**
   `DocOps.validate` rule 4 says so — `duplicateIds(b.frames.map { it.id }) { "board \"${b.id}\" has
   two frames called \"$it\"" }` (`DocOps.kt:80`) — and rule 2's ids-address-things argument applies,
   so the fix is a refusal in words, not support for the case. `AnimOps.playableBoard` now refuses
   such a board for **all four** frame operations, not just `deleteFrame`: `deleteFrame` could not
   avoid losing a cel (it removed one copy of the frame and the mapping both copies share), `setHold`
   would have set the hold on *both* copies, and `moveFrame` could only pick one of the two. The
   **question for the Lead is the one place where this is visible as a change of behaviour**:
   `addFrame(BLANK)` and `animateLayer` used to work on such a board (they only ever ADD a frame, so
   they cannot make the duplicate worse) and now refuse. That is deliberate — the file's promise is
   "every operation returns a document `validate` is happy with, or throws", and no op here can make
   a board with two frames called `f1` valid — but Question 2 above blessed `addFrame` on a board
   `validate` rejects (no frames, a different rule), and if the Lead wants BLANK to stay available on
   a duplicate-id board, say so and the check moves from `playableBoard` into the two operations that
   can actually lose or corrupt something.
7. **An ANIMATION board with no frames AND a bad fps (m3, still unresolved).** Question 2 says both
   that an empty board's schedule is `0.0` and an empty list **and** that a board whose fps is outside
   1..60 is refused by all three functions. The code refuses, because the rate guard runs before the
   frames are walked (`AnimOps.playableSchedule` → `playableFps`), so an empty board at `fps = 0f`
   throws rather than answering 0.0. That is defensible — an empty board is not a board, and `fps` is
   a rate the model stores — but the spec currently says both, and the fix deliberately did **not**
   add a test pinning either answer. Please pick one: refuse (current code) or answer the empty
   schedule; if refuse, Question 2's wording should stop promising `0.0` for an empty board.
8. **`setHold` clamps to 999 ticks, and `DocOps.validate` has no upper bound on `holdFrames` at
   all.** So a document with `holdFrames = 5000` — hand-edited, or written by a future build — is
   VALID, and calling `setHold(doc, …, 5000)` on it answers 999, silently changing a document nobody
   said was wrong. This is the mirror of Question 4's stricter-than-`validate` id rule, and it is
   deliberate (`MAX_HOLD_FRAMES` exists so a document cannot claim to run for years), but it is an
   asymmetry between the model and the maths that nobody has ratified. Fixing it means either a rule
   in `DocOps` (`holdFrames > 999`) — **outside this task's owner area** — or dropping the clamp, and
   both are the Lead's call. Not changed here.
9. **Reachability of the three new refusals (F1/F2/F3 + the `kind` guard).** Archive read and write
   both call `DocOps.validate` before anything else (`androidkit/.../io/JbArchive.kt:170` on write,
   `:388` on read), and no `AnimOps` operation can *create* these documents — `setHold` clamps, and
   ids are refused. So all four guards are reachable only through `DocJson.decode`, which by design
   does not validate. The fix follows the fps guard's existing argument ("each is a value a
   hand-edited or half-written document really does contain") rather than inventing a new one. If the
   Lead would rather not carry four such guards, the alternative is a cheap structural check at
   decode time — **`DocJson.kt` is outside this task's owner area**, so it is recorded here and not
   touched.

