# JB-3.08 — Context-aware three-finger swipe: the decision the finger can see

| | |
|---|---|
| **Tier** | T2 — the whole of this row is pure Kotlin in `:core`, no Android, no clock, no file |
| **Depends on** | JB-2.02 (`CanvasGestures` — Built), JB-3.03 (`FilmStrip`, the playhead is an id — Built), JB-3.08a (`ThreeFingerSwipe` — Built, 🟧). *(Header key is `Depends on` per `SPEC_TEMPLATE.md:7`; this project's specs in practice write `Needs` — see `JB-3.03_film_strip.md:11` — so both words appear in this repo and the template's is used here.)* |
| **Status** | 📝 **Draft spec.** `INDEX.md` does not exist in this repo (`tasks/joybrush/` contains no such file), so the template's "update INDEX.md" DoD item is **not applicable** and is not claimed; the ROADMAP row is set by the orchestrator, never by a spec writer |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/SwipeGesture.kt` · (2) NEW `.../core/anim/SwipeFrames.kt` · (3) NEW `.../core/view/SwipeBadge.kt` · (4) NEW `.../commonTest/.../tool/SwipeGestureTest.kt` · (5) NEW `.../commonTest/.../anim/SwipeFramesTest.kt` · (6) NEW `.../commonTest/.../view/SwipeBadgeTest.kt` · (7) NEW `.../commonTest/.../tool/SwipeDensityTest.kt` · (8) NEW `.../src/jvmTest/.../tool/SwipeGestureNoSecondCopyTest.kt` · (9) NEW `.../src/jvmTest/.../anim/SwipeFramesTouchesNoDocumentTest.kt` · **NOTHING ELSE.** In particular **NOT** `ThreeFingerSwipe.kt`, **NOT** `SizeOpacityDrag.kt`, **NOT** `FilmStrip.kt`, **NOT** `AnimOps.kt`, **NOT** `DocModel.kt`, **NOT** `CanvasGestures.kt`, **NOT** `JbCanvasView.kt`, **NOT** `JoyBrushActivity.kt`, no Gradle file, no build file, nothing in `joybrush-android/` or `app/` |
| **Estimated size** | ~280 lines of Kotlin in `:core`, ~450 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

### Revision log — 2nd reading, `reviews/JB-3.08__space-bunny-alpha-2nd-reader.md`

> **That review is a same-family second reading, not an independent cross-review, and this row is not
> ready on its word alone.** Its file is explicit that it must not be recorded as `xr`, and I agree: I
> wrote this spec and a model with my failure modes read it, which is worth something (it found a test
> that could not pass and a mutation caught by nothing) but is not a second opinion. **A different-family
> reader should look at it before the row is accepted.**

**Both load-bearing behavioural claims were independently traced and both hold** — see the two
derivations at the end of this log.

| Finding | Fix |
|---|---|
| **BLOCKER** — T3a forbade `copy` in the constant pool while the Contract requires `data class` payloads, so it was red on arrival | T3a rewritten as **three clauses with three scopes**: model types across all eight classes; `copy` across `SwipeFrames` + `SwipeFramesKt` **only**, with the payload classes explicitly exempt and the KDoc now saying why `data class` must stay |
| **M1** — T3a was a JVM test in `commonTest` | Moved to a new `jvmTest` file `SwipeFramesTouchesNoDocumentTest.kt`; owner area widened from seven paths to nine; Step 5 now says it in the place where it used to contradict itself |
| **M2** — mutation 4 was caught by nothing | T4a now re-reads `latched` **and** `displayed` **and** `swipe.badge` after `move`, with the reason written down |
| **M3** — T8b's own non-vacuity example did not contain the token it forbids, and mutation 16 was false | T8b restated as catching a **named reference, not a literal copy**, token list changed to prefixes, example changed to `ThreeFingerSwipe.STEP_DP` (which works), mutation 16 corrected to **T2d only** |
| **M4** — `place`'s avoid-loop had no termination rule | **Decision 10**: one pass, never a loop, overlap left visible. T6h with full derivation, and the honest note that a `while` implementation **hangs the suite** |
| **M5** — BRUSH drew an empty string against the blueprint's "always shows which" | Decision 8 now **records the cost**, Decision 13 makes the host's obligation explicit, and **Q4(a)** asks the owner with three concrete options |
| **M6** — `b4` was called corrupt; `validate(b4)` returns `[]` | Decision 2 and the fixture rewritten: `b4` is **valid**, which is *why* the kind check is load-bearing. T1c renamed and now asserts `validate` is empty |
| **M7** — T3a forbade `Brush`, which `commit`'s own signature reaches | Clause 3 is **fields only**, `Brush` removed from every forbidden list, with the reason (a method walk reds on the spec's own `commit`) |
| 14 MINORs | all fixed except where noted in the report — including the four **wrong line numbers** (`frames.size` is `:126`, not `:127`), the stale `Decision 12` → **JB-3.03 Decision 16**, the incomplete `StripStep` paste, `Axis` being public, the `Decision 11` pointer, the Q4/Q5 mix-up, the `isCommentLine` privacy, T2b's missing inputs, T1a's tautology, T7b's `/` ban, T3b, T8c, T8a's over-broad scope, the `JbArchive` module, the "case 19" cross-references, and the header keys |

**Preserved deliberately, because they are the parts a same-family reader is most likely to wave
through:** T4a's `assertEquals(swipe.badge(doc0), BRUSH)` **before** asserting `displayed(doc0)` (the
built-in anti-vacuity guard); T5a's `move(13, 0) → Nothing`, which works only because `SizeOpacityDrag`
re-bases the lock at the crossing sample (`SizeOpacityDrag.kt:98`, measured from `:126`) — now with that
reason written into the test rather than left as a coincidence; T6f's executable `assertEquals` pinning
`TOUCH_DP` to `PaperGeometry.PEG_PITCH_DP`, with its mutation named (and now correctly reporting that
T6a/T6b/T6c/T6e move with it).

**Re-derived by me, not copied, after the review landed:**

- **Claim A — the mode is latched at `begin`.** The field is declared at `ThreeFingerSwipe.kt:155`; its
  only assignment is `mode = badge(doc)` inside `begin` at `:184`; `move` **reads** it at `:213` and
  assigns nothing; `end()` at `:218-221` sets `running = false` and `brush = null` and leaves `mode`
  alone. So the gesture's own mode already cannot change mid-flight — what this row adds is the
  **badge's** mode, because `badge()` re-reads `doc.activeBoardId` and `overrides` on every call
  (`:75-80`) and legitimately changes under a running gesture.
- **Claim B — a mid-gesture tap is refused, not deferred, and refusal is observable.** The landed API
  genuinely *cannot* say it: `tapBadge` returns `Unit`, `running` is `private` (`:154`), and nothing
  consults it there. It does not have to, because **refusal is the absence of a call** in a file this
  row owns. Observability rests on `overrides` being written at exactly `:101`, `:106` and `:109`, all
  inside `tapBadge`, and on `end()` not touching it. So a deferred tap would flip
  `overrides["b-anim1"]` to `BRUSH` at the lift and `badge(doc1)` would answer `BRUSH`; a refused tap
  leaves it `FRAMES` for ever. **T4b's "after `end()`, still FRAMES" line is therefore a real
  discriminator, and it is the reason it is in the suite.**
- **Claim C — the kind check precedes the count.** `ThreeFingerSwipe.kt:125` is
  `if (board.kind != BoardKind.ANIMATION) return 0` and `:126` is `return board.frames.size`. For `b4`
  the function returns 0 at 125 and line 126 is never reached.


> **This file supersedes a draft of 2026-09-29 that was written against the PRE-R25 `ThreeFingerSwipe`**
> (it quoted `overrideBoardId`, `forgetOtherBoard` and a `badge()` that wrote state on read — none of
> which exists in the tree any more, and its `contract` would not compile against it). The record of
> what that draft asked, and the answers:
>
> | The old draft's question | Status now |
> |---|---|
> | **Q1 — is the badge override per-board and persistent, or single and forgotten on switch?** ("no implementation can pass both halves") | **RULED, LEAD_RULINGS R25: per board, remembered.** `badge()` is a **pure read**; the state is a `HashMap<boardId, SwipeMode>` declared at `ThreeFingerSwipe.kt:66` and written only by `tapBadge` (`:101`, `:106`, `:109`). This row asks the question no further. |
> | **Q2 — `badge()` mutates state from something that reads like a query** | **FIXED.** Zero assignments in the six lines `ThreeFingerSwipe.kt:75-80` (two `?: return`, one `overrides[boardId] ?:`, one call, one `return`). The prohibition the old draft built a cache for is no longer needed, and this row deletes the cache. |
> | **Q4 — what does the badge say before the size/opacity axis locks?** | **MOOT, by deletion.** This row's badge shows the *frame number only*; the size and opacity are JB-2.16's swatch's business (Decision 8). No axis is needed, so there is no pre-lock window. |
> | **Q5 — glyphs as point lists or generated from `<symbol>`s?** | **STILL OPEN, and re-raised as Q5 below** (this row's Q5 is the same question, renumbered). Cut from this row. |
> | **Q3 — who owns the 2/3/4-finger assignment table?** | **STILL OPEN, re-raised as Q6.** Cut from this row. |
> | **Q6 — how is the badge composited, given JB-2.01 does not exist?** | **STILL OPEN, re-raised as Q1, and it is what makes this row core-only.** |
>
> The old draft's Decisions 3, 6, 7, 8, 9, 11, 12 (corner, `begin` on the first third-pointer MOVE,
> tap-vs-drag at `TAP_SLOP_PX`, no pinch at three pointers, the live circle, the ticks, hide/show) were
> all **view** decisions needing files this row may not touch. They are preserved as **Decision 14**
> below, stated as *contracts the host must honour*, and as Questions 1 and 6 — not as code.
>
> **Test-number mapping for the two other specs that cite this row.** `JB-0.09_lobby_entry_and_chrome.md:237`
> and `JB-3.07_send_to_studio.md:273` both cite "JB-3.08's **case 19**" for the read-the-source-as-a-test
> trick. **In this revision that test is `T8b`**, and the `data class`-scope version of the same family
> is `T3a`. Anyone following either citation should read `T8b` here and note that its scope differs
> from the old `case 19` — see T3a and T8b for why they are two tests and not one.

---

## Goal

The owner asked, in blueprint §6 question 1 (his own idea, decided 2026-09-28): **a three-finger swipe
flips frames when the active board is an animation board with at least two frames, and adjusts brush
size/opacity otherwise; a small corner badge shows which; tapping the badge overrides the automatic
choice; and the mode never changes in the middle of a gesture.**

JB-3.08a built the arithmetic. What is left, and what this row is, is the part a person can **see**:
which of the two jobs the gesture is doing, *decided once and only once*, so that the badge under their
hand never says something the fingers are not doing — and so that the frame a swipe lands on is a
playhead move, in the exact terms the film strip already uses, rather than an index that has to be
translated by whoever drew it.

This row adds **no arithmetic**. Every number it uses is read from the class that owns it, and a test
says so.

## Contract (verbatim)

### What already exists, pasted from the landed files — every signature this row calls

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/ThreeFingerSwipe.kt` (JB-3.08a,
🟧 Built):

```kotlin
package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.JbDocument

import kotlin.math.round

enum class SwipeMode {
    /** Flip frames of the active animation board. */
    FRAMES,

    /** Adjust brush size (across) or opacity (up/down) — JB-2.16a's maths, unchanged. */
    BRUSH,
}

class ThreeFingerSwipe(val density: Float = 1f) {

    /** What the badge shows right now for [doc]'s active board, with any override applied. */
    fun badge(doc: JbDocument): SwipeMode
    /** The badge was tapped: flip the mode for THIS active board until it changes. */
    fun tapBadge(doc: JbDocument)

    sealed class Step {
        /** Show frame [index] of the active board (already clamped to 0 until frames − 1). */
        data class ShowFrame(val index: Int) : Step()

        data class Brush(val size: Float, val opacity: Float) : Step()

        /** The index just reached the first (atEnd = false) or last (true) frame and stops there. */
        data class Ended(val index: Int, val atEnd: Boolean) : Step()

        /** Pushed through an end: now showing [index] at the other end. */
        data class Wrapped(val index: Int) : Step()

        object Nothing : Step()
    }

    /** Three fingers came down. [frameIndex] = the frame showing; brush values as now; zoom now. */
    fun begin(doc: JbDocument, frameIndex: Int, size: Float, opacity: Float, screenPerDoc: Float)

    /** Total centroid offset since begin, screen px (+x right, +y down). */
    fun move(dxScreen: Float, dyScreen: Float): Step

    fun end()

    companion object {
        /** Screen px of horizontal travel per frame, in dp, scaled by [density]. */
        const val STEP_DP = 36f

        /** Whole steps past an end that carry it through to the other end. */
        const val WRAP_PUSH_STEPS = 3

        /** Below this many frames there is nothing to flip, so the badge cannot offer FRAMES. */
        const val MIN_FLIPPABLE_FRAMES = 2
    }
}
```

**Verified by reading the file**, not by memory — `ThreeFingerSwipe.kt` lines 12, 50, 75, 80, 96, 131-145,
183, 209, 218, 306, 309, 312. Two claims this row leans on and the exact lines that carry them:

- `badge()` **contains no assignment** (lines 75-80: `?: return`, `?: return`, one read of `overrides`).
  It is a pure read. LEAD_RULINGS R25.
- `frameCountOf(doc)` checks the **kind before the count**. Pasting the five lines verbatim, with their
  numbers, because the whole of Decision 2 rests on the order and not on either value:

  ```kotlin
  private fun frameCountOf(doc: JbDocument): Int {                            // 122
      val active = doc.activeBoardId ?: return 0                              // 123
      val board = doc.boards.firstOrNull { it.id == active } ?: return 0      // 124
      if (board.kind != BoardKind.ANIMATION) return 0                         // 125  ← the kind check
      return board.frames.size                                                // 126  ← the count
  }                                                                          // 127
  ```

  **Line 125 is the kind check and it returns before line 126 is ever read.**

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/SizeOpacityDrag.kt` (JB-2.16a, 🟩):

```kotlin
class SizeOpacityDrag(
    val startSize: Float,
    val startOpacity: Float,
    val screenPerDoc: Float,
    val density: Float = 1f,
) {
    enum class Axis { NONE, SIZE, OPACITY }
    val axis: Axis
    val size: Float                 // DOCUMENT px
    val opacity: Float              // 0.01..1
    val previewRadiusScreenPx: Float // size / 2 × screenPerDoc
    fun move(dxScreen: Float, dyScreen: Float)

    companion object {
        const val LOCK_TRAVEL_DP = 12f
        const val SIZE_PER_DOUBLING_DP = 160f
        const val OPACITY_SPAN_DP = 300f
        const val MIN_SIZE = 0.5f
        const val MAX_SIZE = BrushValidate.MAX_SIZE_PX      // SizeOpacityDrag.kt:168
        const val MIN_OPACITY = 0.01f
    }
}
```

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt` (JB-3.03, 🟧) — used
as the **vocabulary** this row writes into, never called. The **whole** of `StripStep` is pasted,
including the two members this row does not use, because one of them is the counter-example Decision 3
argues from and the other is what a *state* step looks like:

```kotlin
class FilmStrip(val board: Board, val density: Float = 1f) {
    /**
     * The playhead one step along, for the strip's own prev/next. Clamped at the ends and **never
     * wrapped** — wrapping is playback's business and the strip is not playback (Decision 16).
     */
    fun stepPlayhead(frameId: String?, delta: Int): String?
}

/** What a lift, or a second finger, produces. Nothing here is a view type. */
sealed class StripStep {                                                        // 373
    /** The playhead moves. Session state, not a document: no document is produced. */
    data class PlayheadTo(val frameId: String) : StripStep()                    // 375

    /**
     * The hold changed. [doc] is a NEW document; the one the gesture was given is untouched and is
     * still what the undo stack holds. There is exactly ONE of these per gesture.
     */
    data class HoldChanged(val doc: JbDocument, val frameId: String, val holdFrames: Int) : StripStep()  // 381

    /** A second finger, or a lift that changed nothing. The document is not touched at all. */
    object Nothing : StripStep()                                                // 384
}
```

**`HoldChanged` (`:381`) is the contrast, and it is why the paste is complete.** It is the ONE member
of that family that carries a `JbDocument`, and it carries it because an edge drag genuinely changes
the model. Decision 3's argument is that `SwipeFrames.Move` must be the *other* shape — a family whose
every member is a pair of `String`s and a `Boolean`, which is why T3a's field walk (and not its method
walk) is the check that bites.

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PaperGeometry.kt` (JB-3.02) — the
**house touch floor**, read not copied:

```kotlin
object PaperGeometry {
    /** Distance between peg centres, in dp: the house touch floor (R32). */
    const val PEG_PITCH_DP = 44f          // PaperGeometry.kt:45
    /** A peg's drawn radius, in dp. */
    const val PEG_RADIUS_DP = 14f         // PaperGeometry.kt:48
    /** How close a finger-down must be to a peg's CENTRE, in dp, to press it. */
    const val PEG_HIT_RADIUS_DP = 22f     // PaperGeometry.kt:54
}
```

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` — only what is read:

```kotlin
@Serializable enum class BoardKind { CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER }

@Serializable data class Frame(val id: String, val holdFrames: Int = 1)

@Serializable data class SpriteGrid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, in play order
    val grid: SpriteGrid? = null,           // SPRITE only
)
```

Also read, not cited as code: `CanvasGestures.TAP_SLOP_PX = 20f` (`CanvasGestures.kt:327`) and
`TAP_MS = 250L` (`:324`) — **androidkit, so `:core` cannot import them and must not copy them** (see
Decision 9's numbers and test T8).

### What this row adds

```kotlin
package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.tool.ThreeFingerSwipe.Step

/**
 * ONE three-finger gesture at a time, with its mode **latched at [begin]**. JB-3.08.
 *
 * It OWNS the gesture: the host calls [begin] / [move] / [end] / [badgeTap] on this and on nothing
 * else. [ThreeFingerSwipe] is called through here and is never touched directly, because this class
 * keeps the one piece of state the swipe does not expose — *which mode the running gesture is in* — and
 * two owners of "is a gesture running" is the drift this project has paid for.
 *
 * **CONSTRUCT IT WITH THE DISPLAY DENSITY, ALWAYS** (Decision 12). [swipe] defaults to
 * `ThreeFingerSwipe()` whose density is **1**, and `stepPx = STEP_DP × dp` (`ThreeFingerSwipe.kt:152`)
 * — so a default-constructed swipe flips a frame every **36 px**, and on a Note 9 at density 3 it
 * should be every **108 px**. A swipe that is three times too sensitive is not a tuning nit, it is a
 * different gesture. The overload below makes the correct call the short one, and T5d pins both the
 * density-3 step and the fact that the default is never taken in production.
 *
 * NO ARITHMETIC. The flip, the wrap, the ends, the axis lock, the size and the opacity are all
 * [ThreeFingerSwipe] and [SizeOpacityDrag]. This class decides WHEN they are consulted and WHAT THE
 * BADGE SHOWS, which is the whole of the owner's "the mode never changes in the middle of a gesture".
 */
class SwipeGesture(val swipe: ThreeFingerSwipe) {

    /** The constructor a host uses. [density] is `resources.displayMetrics.density`, unconverted. */
    constructor(density: Float) : this(ThreeFingerSwipe(density))

    /** Test-only convenience, and the reason T5d exists: this is a density-1 swipe. */
    constructor() : this(ThreeFingerSwipe())

    /** Where the gesture is. The transition table is Decision 3. */
    enum class State { IDLE, GESTURE }

    val state: State get() = if (latched == null) State.IDLE else State.GESTURE

    /**
     * The mode of the gesture in flight, or null when [state] is [State.IDLE].
     *
     * **Written by [begin] and by nothing else, ever.** It is read by [displayed] and never written
     * from [move]. That single line is the owner's rule made mechanical: a mode is fixed at `begin`,
     * the way an axis is fixed at 12 dp of travel (JB-2.16a Decision 1) and a strip gesture is fixed
     * at finger-down (JB-3.03 Decision 8) — the same house rule three times, for the same reason.
     */
    val latched: SwipeMode? = null
        private set

    /**
     * What the corner badge DRAWS, right now.
     *
     * [SwipeMode.IDLE]: [swipe]'s own answer for [doc] — the pure read, three lines, zero state.
     * [SwipeMode.GESTURE]: [latched], and **not** `swipe.badge(doc)`.
     *
     * *Why the second half is the whole of this class:* `badge()` is allowed to change mid-gesture —
     * the board can be deleted, a frame added, the active board switched — and a badge that followed it
     * would show a mode the fingers under the person's palm are not doing. `ThreeFingerSwipe`'s own
     * KDoc says `badge()` "is never read as 'the mode this gesture is in'. [begin] is what reads it,
     * once." (`ThreeFingerSwipe.kt:73`). This is the reader that sentence was waiting for.
     *
     * **NOTE, so nobody reads the two KDocs and thinks one of them is a mistake:** the same paragraph
     * also calls `badge()` "what the badge draws" (`:72`), which is true **between** gestures and false
     * **during** one. This class is the thing that makes it false on purpose. Test T4a pins both halves:
     * it asserts `swipe.badge(doc0) == BRUSH` (the delegate really has changed) while
     * `displayed(doc0) == FRAMES` (the badge has not).
     */
    fun displayed(doc: JbDocument): SwipeMode = latched ?: swipe.badge(doc)

    /** What a badge tap DID. Two answers, and never a third (Decision 4). */
    enum class TapResult {
        /** Handed to [ThreeFingerSwipe.tapBadge]. The badge may or may not have changed — see Decision 4. */
        APPLIED,

        /** A gesture is running: **refused**, not deferred. Nothing was written and nothing will be. */
        REFUSED_GESTURE_RUNNING,
    }

    /**
     * The badge was tapped. Returns [TapResult.REFUSED_GESTURE_RUNNING] while a gesture is in flight
     * **without calling [ThreeFingerSwipe.tapBadge] at all** — that is what "refused" means here, and
     * the refusal is observable because the badge still says the same thing after the next [end].
     */
    fun badgeTap(doc: JbDocument): TapResult

    /** Three fingers came down. Latches [latched] = `swipe.badge(doc)` and delegates. */
    fun begin(doc: JbDocument, frameIndex: Int, size: Float, opacity: Float, screenPerDoc: Float)

    /** Total centroid offset since [begin], screen px. Delegates; never touches [latched]. */
    fun move(dxScreen: Float, dyScreen: Float): Step

    /** The fingers are up. Clears [latched]; the badge follows the document again from here. */
    fun end()
}
```

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.tool.ThreeFingerSwipe

/**
 * What a flip COMMITS, in the film strip's own vocabulary. JB-3.08.
 *
 * [ThreeFingerSwipe] answers with a frame **index**; the playhead is a frame **id**
 * (JB-3.03 Decision 1) and it is **session state** (FilmStrip.kt:374, verbatim: "The playhead moves.
 * Session state, not a document: no document is produced"). This object is the one-way door between
 * the two, and it is a door with no hinges: nothing here constructs, copies, mutates or returns a
 * [cc.joycreator.joybrush.core.doc.JbDocument].
 *
 * IT DECIDES NOTHING about the flip. It does not know whether a board can be flipped, does not count
 * frames, does not wrap and does not clamp — every one of those is `ThreeFingerSwipe`'s and is asked
 * there. An index this board does not have is [Move.Nothing], never an exception and never a
 * substitute (Decision 3).
 */
object SwipeFrames {

    sealed class Move {
        /**
         * Show [frameId] of [boardId]. Nothing else changed, and nothing was written.
         *
         * **`data class`, and it must stay one** — the equality is what lets a host assert a whole
         * gesture's outcome in one line, and every step type in this codebase that a test compares
         * is one (`Step.ShowFrame`, `StripStep.PlayheadTo`, `CelWork.CopyCel`). The cost is that
         * Kotlin generates `copy`, `componentN`, `equals`, `hashCode` and `toString` into each
         * payload class's own constant pool, which is why **T3a scans the pool for the model types
         * and scans the pool for `copy` only on the non-payload classes.** Do not "fix" that by
         * downgrading these to plain classes.
         */
        data class PlayheadTo(val boardId: String, val frameId: String) : Move()

        /**
         * The index first reached an end and STOPPED there, so the frame changes too — and the stop is
         * worth a light tick, because the owner asked for a stop that can be *felt*.
         */
        data class EndTick(val boardId: String, val frameId: String, val atEnd: Boolean) : Move()

        /** Pushed through an end onto the other one: a stronger tick. */
        data class WrapTick(val boardId: String, val frameId: String) : Move()

        /** The step carried no frame: `Nothing`, a `Brush`, or an index this board does not have. */
        object Nothing : Move()
    }

    /** [step] as a playhead move on [board]. Total; never throws. See Decision 3. */
    fun commit(board: Board, step: ThreeFingerSwipe.Step): Move
}
```

```kotlin
package cc.joycreator.joybrush.core.view

import cc.joycreator.joybrush.core.tool.SwipeMode

/**
 * Where the corner badge goes and what it says. JB-3.08. **No Android type in this file** — the same
 * rule and the same reason as [cc.joycreator.joybrush.core.view.ViewTransform] in this package: it is
 * the maths of a screen control, so it is testable on a computer and it is the part that has to be
 * right.
 *
 * It DERIVES NOTHING about the mode. [mode] is handed in — by [cc.joycreator.joybrush.core.tool.SwipeGesture.displayed],
 * which is the one reader of `badge()` there is — and a badge that second-guessed its input would be a
 * second copy of the predicate R19 forbids.
 */
object SwipeBadge {

    /** One integer rect, so the core needs no platform type. x, y, w, h in px. */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
        fun overlaps(o: Rect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom
        operator fun contains(px: Float, py: Float): Boolean =
            px >= x && px < right && py >= y && py < bottom
    }

    /**
     * The badge's DRAWN rectangle: a [DRAWN_DP] circle whose CENTRE is half a [TOUCH_DP] touch target
     * in from the top-END of [safe], pushed **down** until its touch rectangle clears everything in
     * [avoid] by [gapPx], then clamped inside [safe].
     *
     * Top-END and not centred, because the screen is a drawing surface and the pen is in the other
     * hand. [avoid] is a PARAMETER, not a constant: the chrome that would live in that corner is
     * JB-2.01's, JB-2.01 does not exist, and this row must not know its layout.
     *
     * **THE PUSH IS A SINGLE PASS AND IT TERMINATES (Decision 10).** It is
     * `y += (lowest overlapping avoid rect's bottom + gapPx − y).coerceAtLeast(0f)`, evaluated **once**,
     * followed by the clamp — **not** a `while (overlapsAny())`. One pass is what makes the function
     * total: a rectangle in [avoid] that reaches or passes `safe.bottom` can never be cleared by
     * pushing down, and a `while` loop over that condition **never terminates** — a hang in a layout
     * call, which is the worst place in this app to have one. What happens instead when the badge
     * still overlaps after the single pass and the clamp is stated as **Decision 10** and pinned by
     * **T6h**, which asserts the exact answer for the un-clearable case.
     *
     * All the arithmetic is [Float] and converts to [Int] **once**, at the end (LEAD_RULINGS R19: a
     * `dp(40)` computed in `Int` is wrong on every screen that is not the reference density).
     * A [density] that is not finite or not > 0 is read as 1 — the same guard, and the same reason,
     * as `SizeOpacityDrag.dp` (`SizeOpacityDrag.kt:58`) and `ThreeFingerSwipe.dp` (`:149`).
     *
     * **`/ 2` IS ALLOWED AND EXPECTED** (half a touch target, half the derived gap). An earlier draft
     * of this spec forbade `/` in this file's code lines, which would have reds a correct
     * implementation; T7b now bans only the two zoom names.
     */
    fun place(
        safe: Rect,
        density: Float,
        avoid: List<Rect> = emptyList(),
    ): Rect

    /**
     * The badge's TOUCH rectangle: a [TOUCH_DP] square centred on the same centre as [drawn]. Visual ≠
     * touch size — the house rule, `JOYBRUSH_VISUAL_LANGUAGE.md` §"Floor". Never smaller than
     * [TOUCH_DP] in either direction and never zero or negative: a zero-sized hit rectangle is a badge
     * nobody can tap, and the only symptom is "the override does not work".
     */
    fun touchRect(drawn: Rect, density: Float): Rect

    /**
     * The badge's line of text, and the ONLY line it has.
     *
     *  - [SwipeMode.FRAMES] → `"7 / 12"`, **1-based**, `frame` over `count`.
     *  - [SwipeMode.BRUSH] → `""`. Always. The size and opacity are the **swatch's** numbers
     *    (JB-2.16's row), not the badge's, and the live circle is the swatch's too. See Decision 7.
     *  - Anything that is not a legal 1-based `frame` within `count` → `""`. Never `"7 / 0"`, never
     *    `"0 / 12"`: a badge that lies about a denominator is worse than one that says nothing.
     */
    fun readout(mode: SwipeMode, frame: Int, count: Int): String

    /**
     * The touch target's side, in dp — **the house touch floor**, and deliberately the same number
     * `PaperGeometry` already publishes for the peg bar rather than a second copy of 44. LEAD_RULINGS
     * R32 lists it; `PaperGeometry.PEG_PITCH_DP`'s own KDoc calls it "the house touch floor". Test T6
     * pins this constant **equal** to `PaperGeometry.PEG_PITCH_DP`, which is how a second copy is
     * caught without reflection.
     */
    const val TOUCH_DP: Float = 44f

    /**
     * The drawn circle's diameter, in dp, from the visual language and nowhere else: "Floor — nothing
     * below 28dp" and "Round icon button — 28dp circle"
     * (`JOYBRUSH_VISUAL_LANGUAGE.md`, §"Floor" and §"Round icon button"). PROVISIONAL — Q3.
     */
    const val DRAWN_DP: Float = 28f

    /** The clearance between the badge's touch rectangle and anything in `avoid`, in px. */
    fun gapPx(density: Float): Float
}
```

## Decisions already made

1. **The predicate is `ThreeFingerSwipe.badge(doc: JbDocument): SwipeMode`, and this row re-derives it
   nowhere.** *Why:* it already exists, it is public, and since R25 it is a **pure read** with no
   assignment in it (`ThreeFingerSwipe.kt:75-80`, read to confirm). "Flippable" is therefore
   `badge(doc) == SwipeMode.FRAMES`, and the threshold that decides it is the public
   `ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES` (`:312`, value 2), which the host reads and never copies.
   A second `frames.size >= 2` anywhere in this row is the R19 trap in its plainest form.

2. **A sprite board's cells are NOT frames, and the answer is BRUSH even for a board that carries both.**
   *Why, in the order the code decides it:* `frameCountOf` returns 0 for **any** board whose
   `kind != BoardKind.ANIMATION` (`ThreeFingerSwipe.kt:125`), and it asks that **before** it looks at
   `board.frames.size` (line 126). So:
   - a **SPRITE** board with 5 cells → **BRUSH**. Its cells are not `Board.frames` at all: they are
     `Board.grid: SpriteGrid(cols, rows, cellW, cellH)` (`DocModel.kt:67`, `:77`), a **spatial** grid
     addressed by column and row. A `Frame` is a **temporal** thing — it has an `id` and a
     `holdFrames` and it sits in a play order on a board with an `fps`. The model's own comments say
     "ANIMATION only" (`DocModel.kt:76`) and "SPRITE only" (`:77`), and `DocOps.validate` checks the
     two lists under two different rules (rule 4 for `ANIMATION` frames, `DocOps.kt:78-88`; rule 5 for
     the `SPRITE` grid, `:91-98`). One timeline, two vocabularies, never mixed.
   - a **SPRITE** board that *also* carries 5 `Frame`s (fixture `b4`) → **still BRUSH**, because line
     125 returns before line 126 is read. **And `DocOps.validate(b4)` returns `[]` — this is a
     perfectly valid document, not a corrupt one.** I checked every rule: rule 4 is gated on
     `kind == ANIMATION` (`:78`) so it is skipped; rule 5 checks `grid` only (`:91-98`); rule 7 reads
     `board.frames` only for the ANIMATION board a layer is `animatedIn` (`:102-138`); rules 6 and
     8-11 never mention it. **Nothing upstream stops this shape, which is exactly why the predicate
     has to.** An earlier draft of this spec called `b4` "hand-edited … a corrupted file"; that was
     wrong, and it was wrong in the direction that would have made the guard sound cosmetic. The guard
     is load-bearing *because* the model permits the shape.
   - `PUPPET` and `CHARACTER` → **BRUSH**, same line.
   - an **empty** ANIMATION board → **BRUSH**, and `DocOps.validate` rule 4 *does* call that document
     broken in words ("animation board has no frames", `DocOps.kt:79`). The predicate is total over it
     and never throws.
   - an ANIMATION board with **one** frame → **BRUSH**. See Decision 4.

3. **A flip is a PLAYHEAD move, and it is NOT an undo step.** `SwipeFrames.commit(board, step)` returns
   `PlayheadTo` / `EndTick` / `WrapTick` / `Nothing` and has **no way** to produce or accept a
   `JbDocument`. *Why:* the playhead is session state by the film strip's own sentence — "The playhead
   moves. Session state, not a document: no document is produced" (`FilmStrip.kt:374`) — and the undo
   stack in this app holds tile changes, never a `StrokeRecord` (JB-0.01's cross-review finding, recorded
   on the JB-0.01 board row). So a three-finger swipe puts **nothing** on the undo stack, and cannot be
   undone by undo; it is undone by swiping back. That is the correct behaviour for "which frame am I
   looking at", and the cost is stated rather than hidden: someone who flips four frames and hits undo
   stays on the fourth frame. **Q2 asks the Lead to confirm the ruling, with the cost named.**

4. **`commit` maps an index to an id and refuses nothing else.** It is `board.frames.getOrNull(index)`:
   out of range (10 on a 10-frame board, or −1) → `Move.Nothing`, never an exception and never a
   substitute frame. A `Step.Brush` and a `Step.Nothing` → `Move.Nothing` too, so a host physically
   cannot write a playhead from a brush drag.
   *And `commit` deliberately does NOT check whether the board can be flipped.* On a one-frame board
   `Step.ShowFrame(0)` therefore still commits `PlayheadTo(frameId)` — because index 0 *means* "frame 0"
   on that board, and the flippability decision belongs to exactly one place (Decision 1). The predicate
   having already answered BRUSH is why the host never asks. **On a one-frame board the question
   "refuse or do nothing?" is therefore vacuous: the mode is BRUSH, so no FRAMES step is ever produced,
   the badge never offers it, and `tapBadge` refuses an override to it** (`ThreeFingerSwipe.kt:105-108`;
   the landed `anOverrideToFramesOnABoardThatCannotFlipIsRefused` covers it). This row pins the whole
   chain in test T9 rather than adding a second guard that could disagree.

5. **`commit` does NOT go through `FilmStrip.stepPlayhead`.** *Why:* `stepPlayhead` takes a **delta**,
   **clamps** at the ends and **never wraps** (`FilmStrip.kt:289-309`, and **JB-3.03 Decision 16**:
   "The strip's only stepper is prev/next, and it does not wrap" — `JB-3.03_film_strip.md:439`; the
   landed KDoc at `FilmStrip.kt:291` carries a stale "Decision 12", which is JB-3.03's rounding-idiom
   decision, and the reviewer's MINOR m4 is right that the number in the code and the number in the
   spec disagree — the spec is correct and the KDoc is the stale one, which is not this row's file).
   A three-finger swipe's whole behaviour — stop, then push through and wrap to the other end — is the
   opposite of that. Reusing it would clamp the wrap away. `SwipeFrames` uses the strip's **vocabulary**
   (a playhead is a frame id; a move is session state) and not its stepper.

6. **The mode is latched at `begin`, and `begin` is the ONLY thing that changes it.** *Why:* this is the
   owner's own sentence ("the mode never changes in the middle of a gesture", blueprint §6 q1) and it is
   the same house rule already written three times for the same reason — a swipe that changes what it
   means halfway is how a scrub becomes an accidental hold change (JB-3.03 Decision 8), and two axes
   following at once is what makes a two-axis control fiddly (JB-2.16a Decision 1). Nothing else in
   `SwipeGesture` writes `latched`, and test T4 reds if it does.

7. **A badge tap during a gesture is REFUSED, not deferred.** *Why, and this is the row's most testable
   claim:*
   - A **deferred** tap would fire at the next `end`, at a moment the person did not choose, changing
     the mode of a gesture they have not started. They would see the badge change with no tap of their
     own and no gesture in flight to explain it.
   - A **refused** tap changes nothing and the badge keeps saying the same thing — which is *visible*.
     Refusal is observable; deferral is only observable by not tapping again.
   - It is also the only answer compatible with Decision 4's rule: the badge's content during a gesture
     is `latched`, so a tap that changed `swipe`'s state would make `badge()` and the drawn badge
     disagree for as long as the gesture ran. Refusal keeps them the same object.
   - A host must still **absorb** the touch (return "handled") so a stray pen tap on the badge does not
     become a stroke. That is host code — Q1.
   - **Two** answers, never a third. `APPLIED` does **not** promise the badge changed: on a canvas board
     `tapBadge` is refused inside `ThreeFingerSwipe` and the badge correctly still says BRUSH. This row
     does not re-derive that outcome, because re-deriving it is a second predicate (Decision 1); the
     host re-reads `displayed()` if it needs to know.

8. **The badge shows the frame number and nothing else.** `"7 / 12"` in FRAMES, `""` in BRUSH.
   *Why:* the owner's sentence is "a corner badge (running figure / brush) shows which" — the mode, and
   the mode is a `SwipeMode`. The size and the opacity are the **swatch's** numbers: JB-2.16's row owns
   the drag on the swatch and the preview circle drawn from `previewRadiusScreenPx`
   (`SizeOpacityDrag.kt:81`), and a badge showing size *as well as* a swatch showing size is two numbers
   that can disagree.
   **THE COST, RECORDED BECAUSE IT IS REAL (see Q5 and the reviewer's MAJOR):** in BRUSH this badge's
   *text* is empty. The mode is still announced — by a **glyph**, which is the owner's own wording and
   lives outside `:core` because drawing it is Q5. So on a canvas board this row contributes the
   **geometry, the hit rectangle and the decision** (`SwipeGesture.displayed(doc)`, pinned by T4a and
   T5b) and contributes **no ink of its own**. A host that draws nothing for the mode is not honouring
   the blueprint, and Decision 13 says so in a place the builder will read.
   *And the axis question does not arise at all:* `SizeOpacityDrag.Axis` is a **public** enum
   (`SizeOpacityDrag.kt:40`, exposed as `val axis` at `:67`), but the **instance** is private inside
   `ThreeFingerSwipe` (`private var brush: SizeOpacityDrag? = null`, `ThreeFingerSwipe.kt:167`), and
   `ThreeFingerSwipe` exposes no getter for it. So the badge cannot ask which axis a gesture locked
   **without an additive getter on a Built, cross-reviewed file**, which is the Lead's call and not a
   T2 row's. **Q4** asks the owner whether he wants the size in the badge and names that cost.

9. **The badge's numbers are the house's, read rather than invented.** `TOUCH_DP = 44f` — R32's touch
   floor, the same number `PaperGeometry.PEG_PITCH_DP` publishes and whose KDoc calls it the house
   touch floor (`PaperGeometry.kt:44-45`); pinned equal by test T6f. `DRAWN_DP = 28f` — the visual
   language's floor ("Floor | nothing below 28dp; almost everything 40 or 44",
   `JOYBRUSH_VISUAL_LANGUAGE.md:155`) and its round-icon-button circle (`:189`). The gap is **derived,
   not declared**: `(TOUCH_DP − DRAWN_DP) / 2` = 8 dp. It coincides with `Eyedropper.DRAG_OFF_DP = 8f`
   (`Eyedropper.kt:42`), which is a coincidence of value and **not** the derivation — the derivation is
   the rule, and the rule stands on its own. The inset is derived too: the drawn circle's centre sits
   half a touch target in from the safe edge, so the 44 dp target ends up **flush with the safe area**
   and the whole thing is reachable. Density is applied **at the use site** (R32), and a density that is
   not a usable number reads as 1 — the guard `SizeOpacityDrag` and `ThreeFingerSwipe` both already
   carry, for the same reason (a zero density divides by zero in both mappings).

10. **`place` pushes down ONCE and never loops (the termination rule).** *Why:* the naive reading of
    "pushed down until it clears everything" is `while (avoid.any { overlaps }) y += gapPx`, and that
    **never terminates** for an `avoid` rect that reaches or passes `safe.bottom` — a hang inside a
    layout call, which is the worst place in this app to have one. The rule is therefore a **single
    pass**: push by whatever the lowest overlapping rect demands, **once**, then clamp inside `safe`.
    **What happens when it still overlaps after that: it stays where the clamp puts it and the overlap
    is the caller's to see.** *Why that and not "push left" or "give up":* pushing left would move the
    badge out of the corner it was placed in for a chrome rectangle that may not even be drawn there
    yet, and "give up" would hide the badge. A badge that slightly overlaps an undrawn rectangle is
    harmless; a badge that jumps across the screen is not. Pinned by T6h with exact expected values for
    the un-clearable case, and by T6i's termination canary.

11. **Zoom: `screenPerDoc` is `ViewTransform.zoom` and never its inverse.** JB-2.16a Q4 / LEAD_RULINGS
    R19, and `ViewTransform.zoom`'s own KDoc: "Screen px per document px" (`ViewTransform.kt:32-33`).
    This row passes it straight through and never multiplies or divides by it. Sizes are **document
    px** (R10): `Step.Brush.size` goes into `size.base` unchanged.

12. **DENSITY IS THE HOST'S TO SUPPLY AND THE DEFAULT IS NEVER CORRECT.**
    `ThreeFingerSwipe()`'s density is **1** and `stepPx = STEP_DP × dp` (`ThreeFingerSwipe.kt:152`), so
    a default-constructed swipe flips a frame every **36 px** where a Note 9 at density 3 wants every
    **108 px** — a swipe three times too sensitive, which is a different gesture rather than a tuning
    nit. `SwipeGesture(density)` is therefore the constructor a host calls, `SwipeGesture()` is marked
    test-only in its own KDoc, and T5d pins the density-3 step (108 px) *and* asserts that no production
    file constructs a default `ThreeFingerSwipe` (there is none today; `ThreeFingerSwipeTest.kt` is the
    only place in the tree that constructs one at all).

13. **A host that draws no mode indicator is not honouring the blueprint.** Blueprint §6 question 1
    says the badge "**always** shows which". This row delivers the *decision* (`displayed(doc)`) and
    the *geometry*, and the **glyph** is Q5. So the obligation to put a running figure or a brush in
    the circle belongs to the host, it is the owner's own wording, and it is stated here — with the
    consequence that on a BRUSH board this row's badge has **no ink of its own** until Q5 lands.

14. **The host's obligations, recorded so they are not re-derived and not lost.** These need files this
    row may not touch (Questions 1 and 6), and they are contracts, not code:
    - **`begin` is called on the FIRST `ACTION_MOVE` that carries a third pointer**, so no travel is
      lost. The arithmetic for why: `STEP_DP = 36`, and `CanvasGestures.TAP_SLOP_PX = 20f`. A host that
      waits for the 20 px wander threshold and calls `begin` then delivers `36 − 20 = 16 px` of the
      person's 36 px swipe, and `round(16 / 36) = round(0.444) = 0` — **zero frames flipped**. Test T7b
      pins both halves of that as arithmetic so the number cannot be quietly argued with.
    - **A three-finger TAP is still redo; a three-finger DRAG is the swipe.** The split is the tap test
      itself and nothing else: `centroid travel > TAP_SLOP_PX` ⇒ the swipe claims it and the redo tap is
      suppressed; `≤ TAP_SLOP_PX` ⇒ redo, and the swipe never begins. There is no gap and no overlap,
      because the two predicates are exact complements of one number.
    - **Three or more pointers never pinch.** `CanvasGestures.pinch` applies at `pointerCount >= 2`
      (`:168`) today; three must stop there, or the page slides under a frame flip and neither happened.
    - **`Ended` gives one light tick, `Wrapped` one strong tick, every other step none**, fired once per
      `Move` at the moment it is handled — which is why `EndTick` and `WrapTick` are separate cases
      rather than a boolean. `ShowFrame` ticks nothing: a tick per frame would be a machine gun.
    - **Hiding the chrome hides the badge; showing it re-reads** `displayed()` at that moment, so a
      badge can never show a mode decided before the person looked at it.
    - **The live size circle** is drawn at the gesture centroid in BRUSH mode only, at
      `SizeOpacityDrag.previewRadiusScreenPx` — the swatch's row's number, not a re-derivation.

## Steps

1. Write `SwipeGestureTest.kt`, `SwipeFramesTest.kt` and `SwipeBadgeTest.kt` **first, in full**, from the
   Tests section. They cannot pass yet and that is correct.
2. `SwipeFrames.kt` until `SwipeFramesTest` is green. `commit` is one `when` over `Step` and one
   `getOrNull`.
3. `SwipeGesture.kt` until `SwipeGestureTest` is green. It is `ThreeFingerSwipe` plus one nullable field.
4. `SwipeBadge.kt` until `SwipeBadgeTest` is green. `place` is [Float] arithmetic converted once (R19),
   and the push is a **single pass** (Decision 10) — not a loop.
5. `SwipeFramesTouchesNoDocumentTest.kt` **in `jvmTest`** — T3a, in three clauses with three different
   scopes (model types across every class, `copy` across two, fields only). **It needs `::class.java`, a
   class file's bytes and a constant pool, so it does not compile in `commonTest`** — that has happened
   eight times in this project (`FilmStripNoSecondCopyTest.kt:41-45` names them) and an earlier draft of
   this spec put it in `commonTest` while stating the rule three lines away.
6. `SwipeGestureNoSecondCopyTest.kt` **in `jvmTest`** — T8a, T8b, T8c. Model it on
   `FilmStripNoSecondCopyTest`, which is the landed precedent for exactly this trick, and **copy** its
   `routeTo`/`edgesOf` walk and its `isCommentLine` filter rather than rewriting them (both are `private`
   there, and R34 is the ruling about two hand-written transcriptions compared with each other).
7. Run the non-vacuity plan below and paste the output. **Mutation 5 is on `ThreeFingerSwipe.kt`**: make
   it, run it, paste the red, revert it, and declare it temporary.
8. Stop at Questions 1, 4, 5 and 6 — the view half of this row is a different row's files.

## Tests

Fixture used by all three suites unless stated otherwise, **the same shape the landed
`ThreeFingerSwipeTest` uses so the arithmetic is checkable against it** (`ThreeFingerSwipeTest.kt:35-65`):

- `b0` — CANVAS board `b-canvas`, `RectPx(0, 0, 800, 600)`.
- `b1` — ANIMATION board `b-anim1`, `fps = 12f`, **10 frames** called `f1 … f10`, in that order.
- `b2` — ANIMATION board `b-anim2`, 3 frames.
- `b3` — SPRITE board, `grid = SpriteGrid(5, 1, 32, 32)`, `frames = emptyList()`.
- `b4` — **the same SPRITE board, but carrying 5 `Frame`s as well as its grid.** `DocOps.validate(b4)`
  returns **`[]`**: rule 4 is gated on `kind == ANIMATION` (`DocOps.kt:78`) so it is skipped, rule 5
  checks `grid` only (`:91-98`), rule 7 reads `board.frames` only for the ANIMATION board a layer is
  `animatedIn` (`:102-138`), and rules 6 and 8-11 never mention it. **This is a valid document, not a
  corrupt one**, and the fixture exists because nothing upstream forbids the shape — which is what makes
  the predicate's kind check load-bearing rather than decorative. An earlier draft called it
  "hand-edited … a corrupted file" and that was wrong; T1c's name is now `aSpriteBoardWithBothIsStillBrush`.
- One static PAINT layer, `activeLayerId = "l1"`. Nothing animates; the swipe only ever reads boards.
- Safe rect for the badge: `Rect(0, 44, 1080, 2204)` — a Note 9 with a cutout. Close button in `avoid`:
  `Rect(1020, 12, 40, 40)`.

### `core/commonTest/.../anim/SwipeFramesTest.kt`

- **T1a `flippabilityIsReadThroughBadgeAndNeverReDerived`** — the predicate, from `badge()` alone:
  active `b0` → BRUSH; `b1` with 1 frame → BRUSH; with 2 → FRAMES; `b1` with 10 → FRAMES; `b3` → BRUSH;
  `activeBoardId = null` → BRUSH; an id naming nothing → BRUSH; an ANIMATION board with 0 frames → BRUSH
  **and no exception**. Also `assertEquals(2, ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES)` so the threshold is
  read, and `assertEquals(SwipeFrames.Move.Nothing, SwipeFrames.commit(b1, Step.Brush(24f, 0.5f)))`
  **written as the equality it means** — an earlier draft of this line compared `0` to `0` and could not
  fail, while claiming to prove that a brush drag cannot move a playhead.
- **T1b `aSpriteBoardsCellsAreNotFrames`** — `b3` (5 cells in `grid`, `frames` empty) → BRUSH, and
  `assertTrue(b3.frames.isEmpty())` first, so the test proves the cells were never frames and did not
  merely happen to be ignored.
- **T1c `aSpriteBoardWithBothIsStillBrush`** — `b4` (SPRITE, with a grid **and** 5 `Frame`s) → BRUSH.
  `assertTrue(DocOps.validate(docOf(b4)).isEmpty())` first, so the test says out loud that the model
  accepts this document — the predicate is the only thing standing between it and a frame-flipping
  badge, and a reader who thinks `validate` covers it will one day delete the guard.
  *Non-vacuity: reorder `frameCountOf` so `board.frames.size` is read before the `kind` check
  (`ThreeFingerSwipe.kt:125`/`:126`) and this case answers FRAMES and goes red.* That mutation is on a
  Built file; make it, run it, paste it, revert it, and say so in the report.
- **T2a `anOutOfRangeIndexCommitsNothing`** — on `b1`: `commit(b1, ShowFrame(0))` → `PlayheadTo("b-anim1", "f1")`;
  `ShowFrame(9)` → `"f10"`; `ShowFrame(10)` → `Nothing`; `ShowFrame(-1)` → `Nothing`;
  `ShowFrame(Int.MAX_VALUE)` → `Nothing`; `ShowFrame(Int.MIN_VALUE)` → `Nothing`.
- **T2b `theEndsAndTheWrapSurviveTheTranslation`** — 10 frames, begin at index 4, then the exact dx
  values 3.08a's own test 4 uses (`ThreeFingerSwipeTest.kt:310-338`), with the arithmetic in a comment on
  each line:
  `+127` → `EndTick("b-anim1","f1", atEnd=false)` — `127 / 36 = 3.53 → 4`, `4 − 4 = 0`;
  `+216`, `+234` → `Move.Nothing` (pushing 72 px and 90 px past a 108 px `WRAP_PUSH_STEPS × step`,
  both still clamped to index 0);
  `+252` → `WrapTick("b-anim1","f10")` — `144 + 3 × 36`, three whole steps of push;
  `+288` → `PlayheadTo("b-anim1","f9")` — one step past the wrap, index 8.
  **Then the symmetric run, and it gets its OWN gesture with its dx values given** — an earlier draft
  named the endpoint `f2` and no inputs, which is not a test. A **second** `ThreeFingerSwipe` begun at
  index 4 on the same doc, then `−180` → `EndTick("b-anim1","f10", atEnd=true)` (five steps left, `4 + 5 = 9`),
  `−216` and `−252` → `Move.Nothing` (pushes of 36 px and 72 px, under the 108 px needed),
  `−270` → `Move.Nothing` (90 px),
  `−288` → `WrapTick("b-anim1","f1")` (`−180 − 3 × 36`, three whole steps of push),
  `−324` → `PlayheadTo("b-anim1","f2")` (one step past the wrap: `baseDx` reset to `−288`, so the shift
  is `−36` and index `0 + 1 = 1`).
  Every one of those five is `ThreeFingerSwipeTest.kt:341-363` with an **id** substituted for an index,
  so the arithmetic is checkable against a landed test rather than against this spec.
- **T2c `reversingAtAnEndFlipsBackAndDoesNotWrap`** — from `EndTick(…,"f1",false)`, a `−36` move commits
  `PlayheadTo("b-anim1","f6")` and **no** tick. *The mutation: make `commit` treat `Ended` as `ShowFrame`
  and this reds on the class, not just the tick.*
- **T2d `aOneFrameBoardStillCommitsTheIndexItWasGiven`** — `b5` = ANIMATION with 1 frame:
  `commit(b5, ShowFrame(0))` → `PlayheadTo(b5.id, "f1")`. This is Decision 4's "no second predicate",
  as a test: a `commit` that refused because the board has < 2 frames would red here.
- **T3a `commitProducesNoDocumentAndNoUndoStep`** — reflective, over **every class `SwipeFrames.kt`
  compiles to** (the object, its companion if any, the file facade `SwipeFramesKt`, `Move` and its four
  subclasses): no declared field's type is, or reaches, `cc.joycreator.joybrush.core.doc.JbDocument`,
  `AnimOps`, `AnimResult`, `CelWork`, `DocOps`, `JbArchive`, `SaveQueue`, or `Brush`; and the class
  file's **constant pool**, read as ISO-8859-1 text, names none of `JbDocument`, `AnimOps`, `AnimResult`,
  `CelWork`, `copy`, `undo`, `SaveQueue`. `copy` and `undo` are the two that matter: `data class` fields
  generate `copy` for the subclass constructors, which is why they are checked by name and the
  non-`Move` classes are the ones the assertion is really about.
- **T3b `commitDoesNotMutateTheBoardItWasGiven`** — the exact run: on `b1`, `commit` the five steps
  `ShowFrame(3)`, `ShowFrame(4)`, `ShowFrame(5)`, `ShowFrame(4)`, `ShowFrame(3)` and capture
  `b1.frames.map { it.id }` before the first and after the last, then `assertEquals` — two steps back
  and forth are enough to catch a `commit` that clamped into the list, deduped it, or reordered it, and
  naming the five steps means the builder does not have to invent a count.

**T3a is NOT in this file.** It is a JVM test and belongs to
`core/src/jvmTest/.../anim/SwipeFramesTouchesNoDocumentTest.kt` — see below. `SwipeFramesTest.kt` is
`commonTest`, and a test needing `::class.java`, a class file's bytes or a constant pool in
`commonTest` is a **build failure**, which has happened eight times in this project
(`FilmStripNoSecondCopyTest.kt:41-45` names them). An earlier draft of this spec put T3a here and put
the rule in Step 5 three lines later.

### `core/commonTest/.../tool/SwipeGestureTest.kt`

- **T4a `theModeIsLatchedAtBeginAndTheBadgeShowsTheLatchedMode`** — the headline test.
  `displayed(doc1)` is FRAMES with no gesture. `begin(doc1, 4, 10f, 0.5f, 1f)` → `state == GESTURE`,
  `latched == FRAMES`. Now the document changes under it: a doc whose `activeBoardId` is `b0`. Assert
  `swipe.badge(doc0) == SwipeMode.BRUSH` (**the delegate disagrees with the latch — otherwise the test is
  vacuous**) and then `displayed(doc0) == SwipeMode.FRAMES`. Move `−36` → still `Step.ShowFrame(5)`,
  never a `Brush`.
  **THEN RE-READ THE LATCH, because that is the only assertion that survives the mutation this test
  exists for.** An earlier draft stopped at the step, so "move writes `latched`" passed every test in
  the suite — the field is read before the move and cleared by `end()` after it, and nothing in
  between looks at it again. So, immediately after `move(−36)` and with the document still saying
  BRUSH:
  ```kotlin
  assertEquals(SwipeMode.FRAMES, latched)          // the field the mutation writes is still FRAMES
  assertEquals(SwipeMode.FRAMES, displayed(doc0))  // and what the badge draws is still FRAMES
  assertEquals(SwipeMode.BRUSH, swipe.badge(doc0)) // while the delegate still disagrees
  ```
  The third line keeps the second honest: without it, a mutation that latched `BRUSH` and displayed
  `BRUSH` would be indistinguishable from a correct implementation on the second assertion alone.
  Finally `end()` → `state == IDLE`, `latched == null`, `displayed(doc0) == BRUSH`.
  *Non-vacuity: implement `displayed` as `swipe.badge(doc)` and the mid-gesture assertion reds;
  implement `move` as writing `latched` and the `assertEquals(SwipeMode.FRAMES, latched)` line reds.*
- **T4b `aBadgeTapDuringAGestureIsRefusedAndChangesNothing`** — begin on `doc1`, `badgeTap(doc1)` →
  `REFUSED_GESTURE_RUNNING`, `swipe.badge(doc1) == FRAMES` still, `latched == FRAMES` still,
  `displayed(doc1) == FRAMES` still, and `move(−36)` is still `ShowFrame(5)`. **`end()`, then assert
  `swipe.badge(doc1) == FRAMES` — this single line is what separates REFUSED from DEFERRED**, because a
  deferred tap would have landed here and turned it BRUSH. Then `badgeTap(doc1)` → `APPLIED` and
  `swipe.badge(doc1) == BRUSH`. *Non-vacuity: implement `badgeTap` as "queue, apply at `end`" and this
  line reds; that is the whole of Decision 7.*
- **T4c `beginIsTheOnlyThingThatChangesTheLatch`** — begin on `doc1` (FRAMES); `move`, `badgeTap`,
  `end`, `begin` again on `doc0` (BRUSH) → `latched == BRUSH`. `end()` → `latched == null` and
  `displayed(doc0) == BRUSH`. *The point: `begin` re-latching is the LEGAL way to change mode, and a
  host calling `begin` twice has begun twice.*
- **T4d `nothingIsShownBeforeTheFirstStepAndNothingAfterTheEnd`** — `begin` on `doc1` at index 4 →
  `frameShown` is 0 (`SwipeBadge.readout(FRAMES, 0, 10) == ""`), the first `ShowFrame(5)` makes the
  readout `"5 / 10"`, and after `end()` the host's `displayed` is back to the document's answer.
- **T5a `theGestureIsExactlyThreeFingerSwipesMaths`** — FRAMES: begin at 4, `move(−36)` → `ShowFrame(5)`,
  `move(−37)` → `Nothing`, `move(−72)` → `ShowFrame(6)` (3.08a's numbers, so a drift in either row
  shows up here). BRUSH on `doc0`: begin at size 10, opacity 0.5; `move(11, 0)` → `Nothing`,
  `move(13, 0)` → `Nothing` (the 12 dp lock, `SizeOpacityDrag.kt:156`), `move(173, 0)` →
  `Step.Brush(20f, 0.5f)` — derivation `10 × 2^((173 − 13) / 160) = 20`, from
  `SIZE_PER_DOUBLING_DP = 160f`. Assert the values **against `SizeOpacityDrag`'s constants by
  construction**, i.e. the test computes `10f * 2f.pow((173f - 13f) / SizeOpacityDrag.SIZE_PER_DOUBLING_DP)`,
  so a change to 160 moves the test with it and a change to the maths does not.
- **T5b `theModeIsDecidedAtBeginAndTheBadgeAnswersBeforeAnyoneCommits`** — with no fingers down,
  `displayed(doc1) == FRAMES` and `displayed(doc0) == BRUSH`: the badge is the answer to "which one is
  it" **before** any finger arrives, which is the only place a person can look. Then begin and confirm
  `latched == FRAMES` — the same value the badge was already showing.
- **T5c `moveBeforeBeginOrAfterEndIsNothing`** — `move(36f, 0f)` before any `begin` → `Step.Nothing`;
  after `end()` → `Step.Nothing`; `Float.NaN` → `Step.Nothing`; and the gesture survives the NaN
  (`move(−36f, 0f)` afterwards → `ShowFrame(5)`).
- **T5d `densityIsTheHostsToSupplyAndTheStepScalesWithIt`** — `SwipeGesture(2f)` on `doc1`, begin at index
  4, and run the **exact four assertions of the landed `ThreeFingerSwipeTest.densityDoublesTheStep`
  (`ThreeFingerSwipeTest.kt:295-307`)**, reached through `SwipeGesture` instead of directly. At density 2
  the step is `36 × 2 = 72` px, so:
  `move(−35f, 0f)` → `Nothing` (`35 / 72 = 0.486 → 0` — under half a step, where at density 1 the same
  travel would have flipped); `move(−37f, 0f)` → `ShowFrame(5)` (`0.514 → 1`, so `4 + 1 = 5`);
  `move(−107f, 0f)` → `Nothing` (`1.486 → 1`, so frame 5 again and the index has not changed);
  `move(−109f, 0f)` → `ShowFrame(6)` (`1.514 → 2`, so `4 + 2 = 6`).
  **The purpose is not the arithmetic — 3.08a owns that — it is that the density reached the swipe at
  all.** A `SwipeGesture` built on a density-1 `ThreeFingerSwipe` fails the second line, and that is
  the whole point of Decision 12.
  **And the census half:** no `SwipeGesture.kt` code line constructs a `ThreeFingerSwipe` except inside
  the `SwipeGesture()` test-only constructor — asserted by T8b's read-the-source trick, so the density-1
  default cannot be taken in production without a red test.

### `core/commonTest/.../view/SwipeBadgeTest.kt`

- **T6a `theBadgeIsFlushWithTheTopEndOfTheSafeArea`** — `place(Rect(0, 44, 1080, 2204), 1f)` →
  `Rect(1044, 52, 28, 28)`. Derivation, in the test: the touch target is `44`, so the centre is
  `1080 − 22 = 1058` and `44 + 22 = 66`; the drawn circle is `28`, so its half is 14 and
  `1058 − 14 = 1044`, `66 − 14 = 52`. Then `touchRect(drawn, 1f)` → `Rect(1036, 44, 44, 44)` and
  `assertEquals(safe.right, touch.right)` and `assertEquals(safe.y, touch.y)`: the target sits **flush**
  with the safe area, which is the point of the half-target inset.
- **T6b `theBadgeScalesWithDensityAndNothingElse`** — at `density = 2.75`: `Rect(981, 66, 77, 77)` and
  `touchRect` → `Rect(959, 44, 121, 121)`. Derivation: `44 × 2.75 = 121`, `28 × 2.75 = 77`,
  half of each `60.5` and `38.5`, and `1080 − 60.5 − 38.5 = 981.0`, `44 + 60.5 − 38.5 = 66.0` — every
  value exact in binary floating point at density 2.75, which is why 2.75 and not 3. And at
  `density = 0f`, `Float.NaN` and `-2f`, `place` and `touchRect` answer **identically to density 1** —
  the `SizeOpacityDrag.dp` guard (`SizeOpacityDrag.kt:58`), not a new one.
- **T6c `theBadgeIsPushedDownClearOfWhatIsAlreadyInTheCorner`** — `place(safe, 1f, avoid = listOf(close))`
  → `Rect(1044, 68, 28, 28)` and `assertFalse(touchRect(result, 1f).overlaps(close))`. Derivation: the
  touch rect `Rect(1036, 44, 44, 44)` **does** overlap `Rect(1020, 12, 40, 40)` (its bottom is 52), so
  the badge is pushed to `touch.y = 52 + gapPx(1f) = 52 + 8 = 60`, and the drawn circle follows to
  `68 = 60 + (44 − 28) / 2`. Assert `gapPx(1f) == 8f` with `(TOUCH_DP − DRAWN_DP) / 2` in the comment.
- **T6d `aSafeAreaSmallerThanTheBadgeClampsAndNeverGoesNegative`** — `place(Rect(0, 0, 10, 10), 1f)` →
  `Rect(0, 8, 10, 2)`: `w >= 0` and `h >= 0`. Derivation: half-target 22 puts the centre at
  `10 − 22 = −12`, half-drawn 14 puts the drawn rect at `(−26, 8, 28, 28)`, and the clamp into
  `(0, 0, 10, 10)` gives `x = 0`, `right = 10` ⇒ `w = 10`, `y = 8`, `bottom = 10` ⇒ `h = 2`. This is the
  R19 failure (a negative width, or an `Int` overflow on `x + w`) and it is why every intermediate is a
  `Float`.
- **T6h `thePushIsOnePassAndAnUnclearableRegionDoesNotHang`** — Decision 10, the termination rule, with
  an `avoid` that **cannot** be cleared: `avoid = listOf(Rect(0, 44, 1080, 2204))`, i.e. the whole safe
  area, so its `bottom` is `2248` = `safe.bottom` and no amount of pushing down gets below it.
  Derivation, and the builder should copy these five lines rather than re-derive them:
  ```
  centreX = safe.right − touch/2 = 1080 − 22 = 1058          (unchanged; the push is vertical)
  touch at rest      = (1036, 44, 44, 44)                     → overlaps avoid? 44 < 2248 and 44 < 88 → YES
  one pass           → touch.y = avoid.bottom + gapPx = 2248 + 8 = 2256
  clamp              → touch.y + 44 = 2300 > safe.bottom (2248), so touch.y = 2248 − 44 = 2204
  drawn follows      → drawn.y = 2204 + (44 − 28)/2 = 2212,  drawn.x still 1058 − 14 = 1044
  ```
  Assert, in this order:
  1. `place` **returns**. This is the termination proof and it is worth stating why it is valuable: a
     `while (overlapsAny())` implementation **hangs the suite** rather than failing it, which is the
     loudest possible outcome for the one bug this row cannot otherwise see.
  2. `assertEquals(Rect(1044, 2212, 28, 28), place(safe, 1f, avoid))` — and `assertEquals(2240, it.bottom)`,
     so the drawn circle is inside the safe area even though it overlaps.
  3. `assertTrue(it.overlaps(Rect(0, 44, 1080, 2204)))` — **the overlap is accepted and left visible to
     the caller**, which is exactly what Decision 10 chose. A future "hide the badge on overlap" edit
     reds here, and that is the point: the decision is pinned, not merely permitted.
  4. `assertEquals(place(safe, 1f, avoid), place(safe, 1f, avoid))` — a single pass is deterministic.
     *This assertion is a weak canary on its own and is labelled as such; assertion 1 is the real one.*
- **T6e `theTouchTargetIsNeverSmallerThanFortyFourDp`** — for drawn rects of `0×0`, `1×1`, `28×28`,
  `77×77` and densities 1, 2, 2.75: `touchRect.w >= TOUCH_DP * density` and likewise `h`, and both
  `> 0`. A zero-sized hit rectangle is a badge nobody can tap and its only symptom is a broken override.
- **T6f `theTouchFloorIsTheHouseNumberAndNotACopy`** — `assertEquals(PaperGeometry.PEG_PITCH_DP, SwipeBadge.TOUCH_DP)`
  and `assertTrue(SwipeBadge.DRAWN_DP < SwipeBadge.TOUCH_DP)`. The equality is the R19 rule in test form:
  if the peg bar's floor ever moves, this row's badge moves with it instead of drifting.
- **T7a `theReadoutIsTheFrameNumberAndOnlyTheFrameNumber`** — `(FRAMES, 1, 12)` → `"1 / 12"`;
  `(FRAMES, 12, 12)` → `"12 / 12"`; `(BRUSH, 7, 12)` → `""`; `(BRUSH, 0, 0)` → `""`;
  `(FRAMES, 7, 0)` → `""`; `(FRAMES, 0, 12)` → `""`; `(FRAMES, 7, 5)` → `""` (frame beyond the count);
  `(FRAMES, -1, 12)` → `""`. And **no** case returns a string containing `NaN`, `Infinity`, `-` or `0 /`.
- **T7b `theBadgeKnowsNothingAboutZoom`** — the **signatures** are the primary assertion and the source
  scan is the backup: `SwipeBadge.place(safe, density, avoid)`, `touchRect(drawn, density)`,
  `readout(mode, frame, count)` and `gapPx(density)` take **no zoom parameter and no document**, so
  there is nothing for a zoom to enter through. Asserted by reading `SwipeBadge.kt`'s source and
  requiring that **no non-comment line contains `zoom` or `screenPerDoc`**.
  **`/` IS NOT FORBIDDEN.** An earlier draft of this spec also banned `/` in this file's code lines,
  which would have reds a correct implementation — halving a side (`touch / 2f`) and the derived gap
  both need it, and Decision 9 and T6a/T6b are written in those terms. The scan bans the two zoom
  *names* and nothing else. This is Decision 11 as a test: the badge is chrome, not canvas, and the one
  place a zoom would be wrong is a `dp` multiplied by it.
- **T7c `waitingForTheSlopWouldLoseAWholeFrame`** — arithmetic as a test, and it is the number behind
  Decision 11: `round(36f / ThreeFingerSwipe.STEP_DP) == 1` and
  `round((36f - 20f) / ThreeFingerSwipe.STEP_DP) == 0`, with `20f` named in a comment as
  `CanvasGestures.TAP_SLOP_PX` (androidkit, deliberately not imported and not copied into `:core`). A
  host that begins on the wander threshold flips nothing on a full-length swipe.

### `core/src/jvmTest/.../anim/SwipeFramesTouchesNoDocumentTest.kt` — T3a, and only T3a

**This file is in `jvmTest` and must be.** T3a needs `::class.java`,
`klass.protectionDomain?.codeSource?.location` and a class file's bytes, none of which exist in
`commonTest`; `FilmStripNoSecondCopyTest.kt:41-45` says so and names the eight rows that have got it
wrong. **The census of classes to scan, in both halves of T3a, is:**
`SwipeFrames`, `SwipeFramesKt` (the file facade — fetched with `runCatching { Class.forName(...) }` so an
absent facade skips rather than fails, `FilmStripNoSecondCopyTest.kt:247-265`), `Move`, `Move.PlayheadTo`,
`Move.EndTick`, `Move.WrapTick`, and `Move.Nothing`. **`ThreeFingerSwipe` and its nested types are NOT
in either list** — an earlier draft of this spec wrote "and `Step`'s referents", which would have pulled
`ThreeFingerSwipe`'s own `STEP_DP` / `WRAP_PUSH_STEPS` / `MIN_FLIPPABLE_FRAMES` into a census whose
expected answer is "no numbers", and failed.

- **T3a `commitTouchesNoDocumentAndNoUndoStack` — TWO SCOPES, AND THE DIFFERENCE IS THE POINT.**

  **Clause 1 — the MODEL types, over ALL eight classes above, in the constant pool** (read as
  ISO-8859-1 text, so one byte is one character and a `CONSTANT_Utf8` cannot hide behind an encoding —
  the reading `FilmStripNoSecondCopyTest.classFileText` uses, `:487-501`). The pool of each of the eight
  must name **none** of:

  ```
  JbDocument   AnimOps   AnimResult   CelWork   DocOps   SaveQueue   undo
  ```

  `JbArchive` is in this list too, but it lives in **androidkit**
  (`joybrush/androidkit/.../androidkit/io/JbArchive.kt`), so it can only ever appear as a **string** in a
  pool and cannot be named in a type walk from `:core:jvmTest`. Clause 1 is therefore a pool check only,
  and says so.

  **Clause 2 — `copy`, over `SwipeFrames` and `SwipeFramesKt` ONLY.** The pool of those two must not name
  `copy`. **The three payload classes are exempt, and must be:** the Contract requires them to be
  `data class`, Kotlin generates `copy`, `componentN`, `equals`, `hashCode` and `toString` into each of
  their own pools, and an earlier draft of this spec forbade `copy` across all of them — which is red on
  arrival against the spec's own required implementation. **The exemption is the design, not a
  loophole:** a `data class` payload with two `String`s *cannot* carry a document, which is what
  Clause 1 already checks on the very same three classes. Do not "fix" a failure here by downgrading
  `Move.PlayheadTo` to a plain class — the KDoc in the Contract says why it must stay a `data class`.

  **Clause 3 — FIELDS ONLY, and the reason is M7 in one sentence.** Walk `declaredFields` over the same
  eight classes: no field's type is, or reaches, `JbDocument`, `AnimOps`, `AnimResult`, `CelWork`,
  `SaveQueue` or `DocOps`. **Fields only — never parameters and never return types.** The
  `FilmStripNoSecondCopyTest` precedent walks `declaredMethods` return *and* parameter types
  (`:98-104`), and walking them here **reds on the spec's own `commit`**, whose parameter is
  `ThreeFingerSwipe.Step` — whose hierarchy contains `Step.Brush` (`ThreeFingerSwipe.kt:136`), the one
  thing this file is *supposed* to receive and ignore. `Brush` is **not** on any forbidden list in this
  spec, for that reason. As fields, `Move.PlayheadTo` holds two `String`s and `Move.EndTick` a
  `Boolean`, and the walk is exact.
  **How to reach the types:** copy the walk from `FilmStripNoSecondCopyTest.kt:239-262` (`routeTo` /
  `edgesOf`, a BFS over owner/`Type` pairs with a `Type`-keyed `expanded` set — the class-keyed set
  hangs on `Enum<E extends E>`). Copy it as **this file's own** helper: the precedent's copy is
  `private`, so it cannot be called, and re-deriving it is the one thing this project has been bitten
  by (R34: "two hand-written transcriptions compared with each other; neither reads the real code" —
  the failure is fixed by copying, not by rewriting).
  *Non-vacuity: add `val after: JbDocument` to `Move.PlayheadTo` → Clause 1 and Clause 3 both red.*
  *And the proof the walk can fail at all:* assert that `Step.Brush` **does** reach `Brush` from the
  seed `ThreeFingerSwipe.Step::class.java`, before trusting a clean bill of health for the fields. A
  walk that cannot see anything reports "clean" for the wrong reason — which is the exact trap
  `FilmStripNoSecondCopyTest.theTypeWalkCanStillSeeTheClock` (`:437-461`) exists to catch, and its shape
  should be copied here.

### `core/src/jvmTest/.../tool/SwipeGestureNoSecondCopyTest.kt` — T8a, T8b, T8c

- **T8a `thisRowInventsNoGestureNumber`** — a census of every `static final` **primitive** field on every
  class **the three new main files compile to**: `SwipeGesture`, `SwipeGestureKt` (its facade),
  `SwipeGesture.State`, `SwipeGesture.TapResult`, `SwipeBadge`, `SwipeBadgeKt` (its facade),
  `SwipeBadge.Rect`, and — because `SwipeFrames.kt` is one of the three files — the `SwipeFrames` classes
  T3a lists, whose census must also be empty.
  **Nothing else.** An earlier draft of this spec wrote "…and `Step`'s referents", which would have pulled
  `ThreeFingerSwipe`'s own `STEP_DP` / `WRAP_PUSH_STEPS` / `MIN_FLIPPABLE_FRAMES` (`:306`, `:309`,
  `:312`) into a census whose expected answer is a two-element list, and failed. `ThreeFingerSwipe` is
  **not** in this census; T3a has its own file and its own class list.
  The census must equal, exactly and in this order: `["DRAWN_DP", "TOUCH_DP"]`. Instance fields
  (`latched`, `swipe`) are excluded, as are `static` fields whose type is a class (`INSTANCE`,
  `Companion`) — the mistake `FilmStripNoSecondCopyTest` records at length, whose own KDoc explains
  that `javap` shows a `const val` as a static field of the **outer** class, not of its companion, so
  asking the companion returns `[]` (`:203-213`).
  *Non-vacuity, and this is the point of the file: add `const val STEP = 36f` to `SwipeGesture.kt` and
  this reds. That is how a second copy of `ThreeFingerSwipe.STEP_DP` gets in, and it is invisible to
  every other test in the project.*
- **T8b `noSourceLineInThisRowNamesAnotherRowsConstant`** — read the three source files from
  `joybrushRoot()` (`cc.joycreator.joybrush.core.brush.joybrushRoot`, `internal` in `jvmTest`, the one
  root-finder for the source set — `DefaultPresetsTest.kt:460`; `FilmStripNoSecondCopyTest.kt:123` reads
  a sibling file the same way) and assert that **no non-comment line** in any of the three contains:

  ```
  STEP_DP   WRAP_PUSH   MIN_FLIPPABLE   LOCK_TRAVEL   SIZE_PER_DOUBLING
  OPACITY_SPAN   MAX_SIZE   MIN_SIZE   MIN_OPACITY   TAP_SLOP   TAP_MS   PEG_
  ```

  **What this test catches, stated honestly because it is narrower than "no copies":** it catches a line
  that **names** another row's constant — `val step = ThreeFingerSwipe.STEP_DP`,
  `const val MIN_FLIPPABLE_FRAMES = 2f` — which is how a reference and a copy are usually written. It
  **cannot** catch a differently-named literal, `private val threshold = 2`, because a copy is by
  definition differently named. The token list is deliberately **prefix**-based (`MIN_FLIPPABLE`, not
  `MIN_FLIPPABLE_FRAMES`) for exactly that reason: a copy written as `MIN_FLIPPABLE` is the edit this
  must catch, and an earlier draft of this spec listed only the exact names, so its own non-vacuity
  example did not fail it.
  **The comment filter is this file's own three lines.** `FilmStripNoSecondCopyTest.isCommentLine` is
  `private` (`:505-508`) and cannot be called, so copy its body into this file — a KDoc, block-comment or
  line-comment line is one whose trimmed text starts with `*`, `//` or `/*`. Comments may name any of
  the twelve tokens freely; that is how the KDoc explains where each number came from.
  *Non-vacuity, and the example now works: write `private val step = ThreeFingerSwipe.STEP_DP` in
  `SwipeGesture.kt` — it **contains** `STEP_DP`, so T8b reds while T8a's census does **not** (no
  `const val` was added). The builder may also check the reverse: `const val STEP = 36f` reds T8a only.
  The two tests are complementary and neither is redundant.*
  **And the census half of Decision 12 rides here too:** `SwipeGesture.kt`'s code lines may contain
  `ThreeFingerSwipe(` **only** inside the `SwipeGesture()` test-only constructor — so the density-1
  default cannot be reached in production without a red test.
- **T8c `theDensityGuardIsTheGuardedOne` — `SwipeBadge.kt` ONLY.** `SwipeBadge.kt` is the only one of the
  three files that contains density arithmetic: `SwipeFrames` takes a `Board` and an `Int` index, and
  `SwipeGesture` hands `density` straight to `ThreeFingerSwipe` without touching it. An earlier draft
  claimed "for each of the three files", which would have required a density guard in two files that
  have no density. **Assert the behaviour, never the text:** density 0, `Float.NaN` and `−2f` each
  answer identically to density 1 for `place`, `touchRect` and `gapPx` (T6b pins this), which is the
  guard `SizeOpacityDrag.kt:58` and `ThreeFingerSwipe.kt:149` already carry. Do not grep for the
  guard's text.

### Non-vacuity plan — run these, paste the output

| # | Mutation | Must redden |
|---|---|---|
| 1 | `SwipeGesture.displayed` = `swipe.badge(doc)` | T4a (mid-gesture assertion) |
| 2 | `badgeTap` queues and applies at `end` | T4b (the `badge == FRAMES` line after `end`) |
| 3 | `badgeTap` forwards to `tapBadge` during a gesture | T4b (the assertion before `end`) |
| 4 | `move` writes `latched` | T4a — **the `assertEquals(SwipeMode.FRAMES, latched)` line after `move`.** An earlier draft of this table claimed T4a caught it, and it did not: the field was read before the move and cleared by `end` after it, and nothing looked at it in between |
| 5 | `commit` reorders `frameCountOf` so the count is read before the `kind` check (`ThreeFingerSwipe.kt:125`/`:126`) | T1c |
| 6 | `commit` maps an index by **delta through** `FilmStrip.stepPlayhead(board.frames[i]?.id, ±1)` instead of reading `frames[index]` — so the wrap at index 9 becomes a clamped stop | T2b (the `WrapTick`) and T2b's symmetric run. *Rewritten: the earlier phrasing "route a FRAMES step through it" was not executable, because `stepPlayhead` takes an **id and a delta** and `commit` has an **index**; the delta in this version is explicit, so the mutation can actually be written* |
| 7 | `commit` returns a `JbDocument` in `Move.PlayheadTo` | T3a Clause 1 **and** Clause 3 |
| 8 | `commit` clamps an out-of-range index instead of refusing | T2a |
| 9 | `commit` refuses a board with < 2 frames | T2d |
| 10 | `place` does its arithmetic in `Int` | T6b (981/66 at density 2.75) and T6d |
| 11 | `place` centres the badge instead of hugging the top-END | T6a |
| 12 | `touchRect` returns the drawn rect unchanged | T6a, T6e |
| 13 | `TOUCH_DP` set to 40f | T6f — **and under-reported in the earlier draft: T6a, T6b, T6c and T6e all move too**, because every one of them is written with 44 in its expected values |
| 14 | `readout` renders `"$frame / $count"` unconditionally | T7a |
| 15 | any `const val` added to the row's own files | T8a |
| 16 | `SwipeFrames.kt` given `val flippable = frames.size >= 2` | **T2d only.** *Corrected: the earlier draft also claimed T8b, which is false — a literal `frames.size >= 2` contains none of T8b's twelve tokens, and T8b catches a **named reference**, not a literal copy. That narrowing is now stated in T8b itself* |
| 17 | `place` loops (`while (avoid.any { overlaps }) y += gapPx`) | T6h — the suite **hangs** rather than fails, which is the loudest outcome available for the one bug no assertion can see |
| 18 | `place` hides the badge when it cannot be made clear | T6h assertion 3 (the overlap must still be there) |
| 19 | `SwipeGesture(density)` ignores its argument and builds `ThreeFingerSwipe()` | T5d |
| 20 | a `ThreeFingerSwipe(` appears anywhere but the test-only constructor | T8b's census clause |
| 21 | `SwipeBadge.kt` gains a `zoom` or `screenPerDoc` parameter | T7b |
| 22 | `place` computes the gap as a literal `8f` instead of `(TOUCH_DP − DRAWN_DP) / 2` | T8b (`TOUCH_DP`/`DRAWN_DP` unchanged, so T8a's census is clean) **and** T6c — *only when `DRAWN_DP` is also mutated; at the shipped values a literal 8f and the rule agree, and this spec says so rather than claiming a pin it does not have* |

Mutations 1-4, 7-22 are in this row's own files. **Mutation 5 is on a Built file** (`ThreeFingerSwipe.kt`):
make it, run it, paste the red, revert it, and say in the report that it was temporary. Mutation 6 touches
`SwipeFrames.kt` only — the *call* to `stepPlayhead` changes, not `FilmStrip.kt` itself.

**Row 22 is an honest negative and is here because the table is more useful with it than without it:**
`gapPx` is pinned at the **value** 8 by T6c, but not at the **derivation**, because at the shipped
`TOUCH_DP`/`DRAWN_DP` the two are numerically identical and no JVM test can tell them apart. The rule is
stated so the next reader knows why the constant is written the long way, and T8a keeps the file's
constant census at exactly two entries — so a hard-coded `8f` would have to be a non-`const` local to
slip through, and T8b would not see it either. **A source-level assertion that the literal `8f` does not
appear in `SwipeBadge.kt` is cheap and closes this; the builder should add it and say so in the report
rather than treating it as covered.**

## Do not

- **Do not edit `ThreeFingerSwipe.kt`, `SizeOpacityDrag.kt`, `FilmStrip.kt`, `AnimOps.kt`,
  `DocModel.kt` or `PaperGeometry.kt`.** All six are Built and three are behind a cleared cross-review.
  This row composes them; that is the whole technique.
- **Do not add a `BoardKind`, a `SwipeMode`, a `LayerKind` or any `@Serializable` field.** R3: new
  enum constants and new serialised fields are append-only and bump the version, and version numbers are
  assigned at landing, never in a spec (R30 item 3). This row adds none.
- **Do not write a predicate.** No `frames.size >= 2`, no `kind == ANIMATION`, no `activeBoardId`
  arithmetic. Ask `badge(doc)` (`ThreeFingerSwipe.kt:75`). T8b and T1a are the mechanical forms.
- **Do not re-derive the flip, the wrap, the ends, the axis lock, the size or the opacity.** Call
  `ThreeFingerSwipe` and `SizeOpacityDrag`. In particular do not route a flip through
  `FilmStrip.stepPlayhead`: it clamps and never wraps (Decision 5).
- **Do not produce or accept a `JbDocument` in `SwipeFrames`.** A flip is a playhead move and a playhead
  is session state (`FilmStrip.kt:374`). Undo is not involved; Q2 asks the Lead to confirm.
- **Do not call `badge()` from a draw, a layout, or an animation tick.** Read `SwipeGesture.displayed`
  (a field read when latched, and one 3-line pure call when not). The old reason — `badge()` used to
  mutate on read — is gone with R25, but the reason that survives is better: a draw pass that asks a
  question whose answer depends on the board is a draw pass that can redraw differently.
- **Do not copy `TAP_SLOP_PX`, `TAP_MS`, `STEP_DP`, `WRAP_PUSH_STEPS`, `MIN_FLIPPABLE_FRAMES`,
  `LOCK_TRAVEL_DP`, `SIZE_PER_DOUBLING_DP`, `OPACITY_SPAN_DP`, `MIN_SIZE`, `MAX_SIZE` or `MIN_OPACITY`
  into this row.** Read them from the class that owns them, or — for the two androidkit ones — cite them
  in a comment and let the host read them, as Decision 14 does. T8a catches a new `const val` and T8b
  catches a line that *names* another row's constant; **neither catches a differently-named literal**,
  which is stated in T8b rather than papered over.
- **Do not ban `/` from this row's code.** Halving a side and the derived gap both need it; T7b bans
  `zoom` and `screenPerDoc` and nothing else.
- **Do not loop in `place`.** The push is one pass (Decision 10). A `while` over "still overlapping"
  hangs for an `avoid` rect that reaches `safe.bottom`, and T6h makes that a suite timeout.
- **Do not construct a default `ThreeFingerSwipe`.** `ThreeFingerSwipe()` is density **1**, so its step
  is 36 px where a Note 9 wants 108 (Decision 12). `SwipeGesture(density)` is the host's constructor;
  `SwipeGesture()` is test-only, and T5d plus T8b both watch it.
- **Do not touch `CanvasGestures.kt`, `JbCanvasView.kt` or `JoyBrushActivity.kt`.** R30 item 2 makes the
  second and third the Lead's, and the first is JB-2.02's Built file with 16 tests and a cleared review
  behind it, which JB-2.02b (Draft) also wants. The three places this row would have to touch them are
  Questions 1 and 6.
- **Do not draw a glyph, and do not treat the badge as finished when this row lands.** Q5. The mode is
  the owner's "always shows which" and a host that draws nothing for it is not honouring the blueprint
  (Decision 13); on a BRUSH board this row contributes geometry and a decision and **no ink**.
- **No new dependency, no Gradle file, no build file, no resource, no string, no localisation.**

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` pasted — **0 failures**
- [ ] the non-vacuity table pasted, all 22 rows, with **mutation 5 marked temporary and reverted**
- [ ] `git status --short` pasted, showing only the nine owner-area paths and nothing else
- [ ] the two `jvmTest` files are in `jvmTest` and neither `commonTest` file mentions `::class.java`,
      `codeSource`, `ISO_8859_1` or `joybrushRoot` — a grep, pasted, because putting a JVM test in
      `commonTest` is the project's most repeated build failure
- [ ] the spec's Questions updated with anything the builder had to stop on
- [ ] committed `JB-3.08: context-aware three-finger swipe decision`; the ROADMAP row is set by the
      **orchestrator** (a spec writer never edits the board)
- [ ] **not applicable:** the template's "`INDEX.md` updated" item — `tasks/joybrush/INDEX.md` does not
      exist in this repo and this row does not create it

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-30. Everything in this row that could be
decided without the owner or the Lead is decided and pinned by a test. The first two questions are the
ones that keep the view half of this row off the board, and they are why the row is `:core`-only.)_

### Q1 — ⛔ Blocked for Claude: where the badge is composited, and who routes the fingers

Three of the four integration points are files this row may not touch, and two of them are the Lead's by
R30:

| What | File | Why this row cannot do it |
|---|---|---|
| The badge needs a draw pass on a `GLSurfaceView` | `JoyBrushActivity.kt` (its overlay `FrameLayout`) | **R30 item 1** — a locked, ordered resource waiting for JB-2.01's cluster |
| Three pointers must stop pinching; a 3-finger drag must suppress the redo tap | `CanvasGestures.kt` | not on R30's list, but JB-2.02's Built file with 16 tests and a cleared review behind it, and **JB-2.02b (Draft) also wants it** |
| The finger stream has to reach `SwipeGesture.begin/move/end` | `JbCanvasView.kt` | **R30 item 2 — Lead only** |
| The badge must be re-read when the chrome is shown | `JoyBrushActivity.kt` | R30 item 1 |

**What I have done instead of deciding:** the row lands entirely in `:core` and is fully testable today.
The four obligations are written as **Decisions 7 and 11** with their arithmetic, so that whichever row
does the wiring has no design decision left — only four edits to place.

**What I need ruled:** (a) does the badge get its own `View` in `JoyBrushActivity`'s overlay layer (about
six lines, one file four dispatched rows want), or does it wait for **JB-2.01** to own the overlay pass?
(b) If it waits, does that leave a badge-less animation board until JB-2.01 lands — which is a Phase 3
showpiece with no visible sign of the feature? I have assumed (a) so the runway is not deadlocked
(JB-2.01 needs JB-0.09, which is 🟧, so it is close), but the choice is the Lead's and not mine.

### Q2 — for the Lead: confirm that a frame flip is **not** an undo step

Decision 3 rules it **no**, on the evidence that the playhead is session state (`FilmStrip.kt:374`) and
that the undo stack holds tile changes (JB-0.01's cross-review finding).

**The cost, stated rather than buried:** a person who swipes four frames forward and then presses undo
stays on the fifth frame, and the undo appears to have done nothing. If instead flips were undoable, every
`Step.ShowFrame` would be an undo entry — four entries for one gesture — which is precisely what JB-3.03
Decision 10 rejected for the hold drag ("a document write per move would put one entry per frame of
dragging on the undo stack"). My answer is no, and the two rows agree; say the word if you want it
different and it is a different row's design.

### Q3 — PROVISIONAL — Claude to confirm: `DRAWN_DP = 28f`

Derived from the visual language, not invented: "Floor — nothing below 28dp; almost everything 40 or 44"
and "Round icon button — 28dp circle". The floor line's own next clause ("almost everything 40 or 44") is
why the **touch** target is 44 and the **drawn** circle is 28.

`TOUCH_DP = 44f` is not provisional: it is R32's number and T6f pins it equal to
`PaperGeometry.PEG_PITCH_DP`, so it cannot drift. **If the Lead wants 40, it is one constant and two test
numbers** (T6a's `Rect(1044, 52, 28, 28)` becomes `Rect(1046, 50, 28, 28)`, and T6c's push target moves
with it), and `DRAWN_DP` would follow as `TOUCH_DP − 16`.

### Q4 — for the owner: the badge's content in BRUSH mode, and whether it should carry the brush numbers

**Two questions, and the first one is the reviewer's MAJOR and it is a real gap in what I wrote.**

**(a) In BRUSH, `SwipeBadge.readout` returns `""`, so this row contributes no ink of its own to the
one mode that is not a frame number.** Blueprint §6 question 1 says the badge "**always** shows which".
I ruled the *numeric* addendum as frame-only (Decision 8) and did not say out loud that the consequence
is a blank circle on a canvas board until the host draws a glyph — which is Q5. So the honest statement
is: **this row delivers the mode (`SwipeGesture.displayed(doc)`, pinned by T4a and T5b), the geometry and
the hit rectangle, and nothing else.** Three ways out, and I am not choosing between them because the
first is a drawing decision (Q5) and the other two change what a person sees:

- **(i) Q5 lands** and the host draws the running figure or the brush. The blueprint's sentence is
  satisfied and nothing in this row changes. **This is the answer I expect.**
- **(ii) a text mode word** — `SwipeBadge` grows `fun modeWord(mode: SwipeMode): String` returning
  `"FRAMES"` / `"BRUSH"`, so the badge is never blank and the row is still `:core`-only. It is about
  six lines and one test, and it is honest, but the visual language is iconographic and a word on a
  28 dp circle is a different look.
- **(iii) the badge is accepted as a mode *hint* on an animation board only** and simply does not appear
  on a canvas board. Cheapest, and it contradicts "always shows which" outright.

**Say which, or say "i", and I will put the answer in Decision 13.**

**(b) Does the badge carry the brush numbers as well?** I ruled no (Decision 8): the size and opacity
are the **swatch's** numbers — JB-2.16's row, Draft, which already draws the preview circle from
`previewRadiusScreenPx` (`SizeOpacityDrag.kt:81`) — and two controls showing one value is how they drift.

**What (b) would cost:** `SizeOpacityDrag.Axis` is a **public** enum (`SizeOpacityDrag.kt:40`, exposed as
`val axis` at `:67`), but the **instance** is private inside `ThreeFingerSwipe`
(`private var brush: SizeOpacityDrag? = null`, `ThreeFingerSwipe.kt:167`) and `ThreeFingerSwipe` exposes
no getter for it. So the badge cannot ask which axis a gesture locked **without one additive public
getter on a Built, cross-reviewed file** — the Lead's call, not a T2 row's. It also re-opens the
pre-lock window (`Axis` is `NONE` for the first `LOCK_TRAVEL_DP` = 12 dp of travel), which is the
question the superseded draft had to invent an answer for and this one deleted.

### Q5 — for the Lead: the two glyphs, and whether this row may own a resource

**And the answer to Q4(a)(i) lives here, which is why Q4 points at it.**

The badge needs a running figure and a brush. `androidkit` has **no Android resources today** (JB-0.06's
open question is still open), so the superseded draft drew them as `List<List<FloatArray>>` point lists
in `:core`. The house does it differently: the app generates stroked icons from HTML `<symbol>`s at
stroke **1.7** via `tools/spritelab/genicons.py`, and the visual language says a Joy Brush icon set
should be authored the same way. Options: (a) keep the point lists — zero dependencies, done today, and
wrong for the house; (b) generate a two-glyph set into `androidkit`'s resources, which **also settles
JB-0.06's question in the affirmative**. I lean (b) and it is not mine: it is a resource decision on a
module that currently has none. **Cut from this row either way** — the badge's *content* is
`SwipeMode`, `SwipeGesture.displayed()` is that decision, and it is tested.

### Q6 — for the Lead: which row owns the 2/3/4-finger assignment table

**JB-2.02b** ("Tool finger modes and assignable 2/3-finger gestures", T2, 📝 Draft) exists so the person
can *assign* gestures to finger counts, and Decision 14 hard-codes "three fingers are the swipe". Two
rows now claim the same three fingers.

**What I have ruled, provisionally, and it is the minimum a row with no settings screen can ship:** ≥ 3
pointers is the swipe, 2 is the pinch, and the tap-versus-drag split inside the swipe **is** the tap test
at `CanvasGestures.TAP_SLOP_PX` — one constant, read from its owner, and the exact complement of redo's
`≤ TAP_SLOP_PX`, so there is no gap and no overlap.

**What I need ruled:** is JB-3.08's claim a **default JB-2.02b can change**, or is it **fixed**? If it is
a default, JB-2.02b needs a way to turn the three-finger swipe OFF and this spec should say so now so
2.02b is not written against a fixed rule. I have not edited JB-2.02b's spec and will not.
