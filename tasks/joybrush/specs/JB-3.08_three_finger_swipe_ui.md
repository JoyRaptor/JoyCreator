# JB-3.08 — The three-finger swipe on the screen: the corner badge, the tap, and the readout under the finger

| | |
|---|---|
| **Tier** | T2 (pure presentation lands in `:core` and is fully testable; the view is `androidkit` and is verified on the phone) |
| **Status** | 🟨 **Draft.** JB-3.08a's maths is **built and reviewed with one unresolved contradiction** that is not mine to settle (see Q1 and the header note below). This spec is the **UI over that maths**, and it is deliberately built so that either ruling works. It adds nothing to the mode maths. |
| **Needs** | 2.02 (`CanvasGestures` — Built), 3.03 (film strip, the frame index comes from there), 3.08a (`ThreeFingerSwipe` — Built, 2 tests red on a spec contradiction) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/view/SwipeBadge.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/view/SwipeBadgeTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/SwipeOverlay.kt` (badge view + gesture host, ONE file) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/CanvasGestures.kt` (**only** `suppressTapOnce()` and the ≥ 3-pointer rule, Decisions 6 and 7) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (**only** the two lines in Step 5) |
| **Estimated size** | ~260 lines of Kotlin in `:core` + ~200 of tests; ~330 lines in `androidkit` |
| **Command** | `./gradlew -p joybrush :core:jvmTest :androidkit:test` — 0 failures. Then the watcher shows `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`. |

> **⚠️ The unresolved thing this spec is written around, in the builder's face.**
> JB-3.08a Decision 2's KDoc says an override "can never be restored" *and*, in the same KDoc, that a
> canvas board and an animation board "can each keep their own answer". Its test asserts **both**, and
> the built code (`overrideBoardId` + `forgetOtherBoard`) satisfies only the second. The Lead has to
> rule whether the badge override is **per-board and persistent** or **single and forgotten on
> switch**. **Do not resolve it, and do not write code that assumes either answer.**
> Decision 1 is the whole of this spec's answer to that, and it is: *the UI keeps no copy of the
> override, shows no label for where the mode came from, and re-reads `ThreeFingerSwipe.badge()`
> at three named moments only.* Change the model underneath and not one line of this spec changes.
> A second, smaller thing the same reviewer flagged: **`badge()` mutates state from what reads like a
> query** (`forgetOtherBoard` runs inside it). Decision 1's answer to that is that **`onDraw` never
> calls `badge()`** — the drawn mode is a cached field refreshed only at the three named moments.

## Goal

The owner asked for a three-finger swipe that does the obvious thing on the board you are standing
in, and for a small mark in the corner that always says which thing it is currently doing. This spec
delivers that mark and everything a finger notices about it: **where it is, what it says, what a tap
does, that a gesture cannot change under you, and that the number under your hand is the number your
hand is about to change.**

Blueprint §6 question 1 (owner): *"a three-finger swipe flips frames when the active board is an
animation board with 2+ frames, else brush size/opacity; a corner badge (running figure / brush)
shows which; tapping it overrides; the mode never changes mid-gesture."*

## Contract

### 1. The badge's content and geometry — pure, in `:core`, no Android

```kotlin
package cc.joycreator.joybrush.core.view

import cc.joycreator.joybrush.core.tool.SwipeMode

/**
 * The corner badge: where it sits, what is in it, and the number under the finger. **No Android
 * type appears in this file** — the same rule, and the same reason, as [ViewTransform] in this
 * package: it is the maths of a screen control, so it is testable on a computer and it is the part
 * that has to be right. [SwipeOverlay] draws what this says.
 *
 * It is a VALUE, not a widget. There is no `View` here, no invalidate, and — deliberately — **no
 * reference to [cc.joycreator.joybrush.core.tool.ThreeFingerSwipe]**: this file cannot ask the
 * document which mode is in force, because asking is a *query with a side effect* (Q1), and a
 * drawing pass must never run a side effect. The shell reads the mode and hands it in.
 */
object SwipeBadge {

    /** One integer rect, so the core does not need a platform type. x, y, w, h in px. */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
        fun overlaps(o: Rect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom
        operator fun contains(px: Float, py: Float): Boolean =
            px >= x && px < right && py >= y && py < bottom
    }

    /**
     * Where the badge goes, in px.
     *
     * Top-END, inset from the safe area, and **pushed below [avoid]** — the caller's rectangle for
     * anything already in that corner (the screen's close button). A caller that passes nothing
     * gets the bare inset. Deliberately a parameter rather than a constant: the chrome above this
     * row is JB-2.01's, and this row must not know its layout.
     *
     * All the arithmetic is [Float] and is converted to [Int] **once**, at the end
     * (LEAD_RULINGS R19: check in Long/Float before any Int pixel arithmetic — a `dp(40)` computed
     * in Int is wrong on every screen that is not the reference density).
     *
     * @param safe the inset rectangle the badge must stay inside, px.
     * @param density the display density; a value that is not finite or not > 0 is read as 1, the
     *   same guard and the same reason as `SizeOpacityDrag.density` and `ThreeFingerSwipe.density`.
     * @param avoid rectangles the badge must not touch, px; may be empty.
     */
    fun place(
        safe: Rect,
        density: Float,
        sizeDp: Float = SIZE_DP,
        insetDp: Float = INSET_DP,
        gapDp: Float = GAP_DP,
        avoid: List<Rect> = emptyList(),
    ): Rect

    /**
     * The badge's TOUCH rectangle, which is never smaller than [MIN_TOUCH_DP] in either direction
     * even when the drawn circle is [SIZE_DP]. A 28dp mark is a 28dp *picture*; it is not a 28dp
     * target. Never zero, never negative: a guard, not a clamp, because a zero-sized hit
     * rectangle is a badge nobody can tap and the only symptom is "the override does not work".
     */
    fun touchRect(drawn: Rect, density: Float): Rect

    /**
     * What the badge is SHOWING, as a small closed set. **Never "where the mode came from"** —
     * see Q1 and Decision 1: "is this override remembered?" is the unresolved question, and a
     * label that guesses is a lie under one of the two rulings. So there is no AUTO/MANUAL state,
     * no third value, and no way for the shell to draw provenance.
     */
    data class Badge(
        /** The mode the badge is showing. The shell copies it in; the core never derives it. */
        val mode: SwipeMode,
        /** 1-based frame being shown, or 0 when the mode is BRUSH. */
        val frame: Int = 0,
        /** Frames in the active board, or 0 when the mode is BRUSH. */
        val frameCount: Int = 0,
        /** The axis the current gesture has locked, or NONE before the lock. `SizeOpacityDrag.Axis`. */
        val axis: Int = AXIS_NONE,
        /** The brush size in DOCUMENT px (R10), or 0 when the readout is not about size. */
        val sizeDoc: Float = 0f,
        /** The brush opacity 0..1, or -1f when the readout is not about opacity. */
        val opacity: Float = -1f,
    )

    /**
     * The one line of text in the badge. **The rule a finger expects: during a gesture the badge
     * describes THE MODE THAT GESTURE IS IN, never the mode the document would choose now.**
     *
     *  - FRAMES → `"7 / 12"`, 1-based. A frame number the person can read off and compare with
     *    the film strip.
     *  - BRUSH, axis SIZE → the size in whole document px (`"24 px"`), because R10 settled that
     *    `size.base` is document px and the on-screen circle is the screen size; the number in the
     *    badge is the number that gets written.
     *  - BRUSH, axis OPACITY → the opacity as a whole percent (`"60%"`), rounded to nearest with
     *    ties away from zero (the project's one rounding rule — JB-3.06b Decision 2, JB-4.01a
     *    `dragEdge`; never Kotlin's `round`).
     *  - BRUSH, axis NONE → the last committed value, whichever it was. It must not blank and it
     *    must not flicker; see Decision 5.
     *  - Anything non-finite → `"—"`. A badge that says `NaN%` is worse than one that says nothing.
     */
    fun readout(badge: Badge): String

    /**
     * The radius of the live size circle, in SCREEN px, for a brush of [sizeDocPx] at
     * [screenPerDoc]. This is R10's rule made mechanical: **the circle is the size the stroke will
     * be on this screen right now.**
     *
     * It is a delegate, not a formula: it constructs the real `SizeOpacityDrag` and reads
     * `previewRadiusScreenPx`, so the ring under the finger and the number in the badge cannot
     * disagree, and there is no second copy of the size maths in this file (R19's lesson).
     */
    fun sizeCircleRadiusScreenPx(sizeDocPx: Float, screenPerDoc: Float, density: Float = 1f): Float

    /** The two glyphs, as normalised point lists in a 1×1 box. Drawn by [SwipeOverlay]. */
    fun glyph(mode: SwipeMode): List<List<FloatArray>>

    const val SIZE_DP = 28f        // the drawn circle: the visual language's round icon button
    const val INSET_DP = 12f
    const val GAP_DP = 8f
    const val MIN_TOUCH_DP = 44f   // the smallest target a thumb can be asked to hit
    const val STROKE_DP = 1.7f     // the house icon stroke width

    /** Axis values, mirrored from `SizeOpacityDrag.Axis` as Ints so this file needs no import of it. */
    const val AXIS_NONE = 0
    const val AXIS_SIZE = 1
    const val AXIS_OPACITY = 2
}
```

### 2. The overlay — `androidkit`, one file

```kotlin
package cc.joycreator.joybrush.androidkit

/**
 * The badge and the gesture, together, because they are the same decision seen twice: the badge
 * SAYS what the gesture is about to do, and the gesture is what the badge said.
 *
 * **The two caches, and why there are two (Decision 1).**
 *  - [badgeMode] — what the corner shows. Refreshed ONLY at the three named moments in [refresh].
 *  - [gestureMode] — what the running gesture is doing. Written ONLY by [onBegin] and [onEnd].
 *
 * [onDraw] reads these fields and touches nothing else. In particular **it never calls
 * `ThreeFingerSwipe.badge()`**, which is a query with a side effect today (Q1) — a draw pass that
 * mutates the override is a draw pass whose result depends on how many times the screen has been
 * drawn, and that class of bug is invisible until it is a wrong frame under somebody's finger.
 */
class SwipeOverlay(
    private val swipe: ThreeFingerSwipe,
    private val density: Float,
    private val onFrame: (Int) -> Unit,          // show frame n of the active board
    private val onBrush: (sizeDoc: Float, opacity: Float) -> Unit,
    private val onTick: (strong: Boolean) -> Unit, // haptics: false = Ended, true = Wrapped
    private val onSuppressTap: () -> Unit,        // CanvasGestures.suppressTapOnce()
    private val safeInsets: () -> SwipeBadge.Rect,
    private val cornerAvoid: () -> List<SwipeBadge.Rect>,
) {
    /** The mode the badge shows. A copy; the authority is always [swipe]. */
    var badgeMode: SwipeMode = SwipeMode.BRUSH; private set
    /** The mode of the gesture in progress, or null when there is none. */
    var gestureMode: SwipeMode? = null; private set

    /** A tap landed on the badge. Never called while a gesture is running (Decision 4). */
    fun onBadgeTap()
    /** True when (px, py) is inside the badge's touch rectangle. */
    fun hitBadge(px: Float, py: Float): Boolean
    /** The three moments a badge refresh is allowed (Decision 1). */
    fun refresh(doc: JbDocument)
    /** A drawing changed: frames added, board switched, a board deleted. */
    fun onDocumentChanged(doc: JbDocument)
    /** The gesture ended: whatever the mode was, it is over. */
    fun onGestureEnd()
    /** Where the badge is, and what it says — read by [draw] and by the tests. */
    fun badgeState(): SwipeBadge.Badge
    fun draw(canvas: android.graphics.Canvas)
}
```

`ThreeFingerSwipe`, `JbDocument` and `SizeOpacityDrag.Axis` are **used, never re-implemented** and
never re-typed. `SwipeBadge.AXIS_*` exist only because a `:core` file must not import an `androidkit`
enum; `SwipeOverlay` maps between them in one line and a test asserts the mapping is the identity.

## Decisions

1. **The UI keeps NO copy of the override, and the drawing pass never asks for the mode.**
   `badgeMode` is refreshed at exactly three moments — a badge tap, a document change, and the
   screen being shown — and `onDraw` reads the field. *Why:* Q1 is unresolved. Any UI that caches
   the *source* of the mode (auto vs override), or that predicts when an override comes back, is
   code written against one of the two candidate rulings and will be wrong under the other. This
   spec's UI is invariant under the ruling, and the KDoc says so at the field. It is also the fix
   for the reviewer's second point: a query with a side effect must not be reachable from `onDraw`.

2. **The badge shows the mode and never its provenance.** Two glyphs, no AUTO/MANUAL chip, no
   "reset" affordance, no message when an override is dropped. *Why:* the only way to be honest
   about an unresolved question is to not claim to know the answer, and every one of those three
   affordances is a sentence about persistence.

3. **The badge is a top-END circle, 32dp-equivalent, pushed below whatever is already in that
   corner, and it is not under the pen.** It sits where the thumb is not, because the person's other
   hand is drawing. *Why:* the whole screen is a drawing surface; a badge in the middle of it is a
   thing the pen can hit. The `avoid` parameter is what keeps this row independent of JB-2.01's
   chrome, which does not exist yet.

4. **A tap on the badge is REFUSED while a gesture is running, and the next tap is the override.**
   The tap is still *absorbed* (it does not fall through to the canvas and draw a dot), it just
   changes nothing and the badge does not redraw a different glyph. *Why:* Decision 3 of 3.08a fixes
   the mode at `begin`; a tap that changed the badge mid-gesture would show a mode the gesture is
   not in, which is the one lie this control must never tell. Absorbing rather than ignoring keeps
   a stray pen tap from becoming a stroke.

5. **Before the axis lock, the badge keeps saying the last committed value.** `SizeOpacityDrag`
   reports `Axis.NONE` until `LOCK_TRAVEL_DP` (12dp) of travel, and Decision 5 of 3.08a says the
   badge may be anything at that point. *Why (PROVISIONAL — I am ruling this, not the owner):* a
   badge that blanks and refills as a finger crosses 12dp reads as a glitch, and the person has no
   way to know which of the two numbers is about to change. The alternative — showing both — is a
   wider badge over a drawing. **Q4 asks the owner.**

6. **Three fingers are the swipe's, from the first move, and the page does not move.** `begin` is
   called on the **first** `ACTION_MOVE` that carries a third pointer, so **no travel is lost** — the
   frame maths is relative to the centroid at `begin`, and starting it 20px late would eat more
   than half of a 36dp step. *Why:* the alternative (wait for the wander threshold, then `begin`)
   is correct on paper and wrong in the hand.

7. **A three-finger TAP is still redo, and a three-finger DRAG is still the swipe — the two are
   separated by the tap test, not by a second threshold.** When the centroid has travelled **more
   than `CanvasGestures.TAP_SLOP_PX`**, the host calls `suppressTapOnce()`; that is the *same*
   constant, read from the *same* class, not a copy. *Why:* the two rules are exactly complementary
   — any movement that fails the tap test (`> TAP_SLOP_PX`) claims the swipe, and any movement that
   passes it (`≤`) does not. There is no gap where neither fires and no overlap where both do. A
   second, hand-picked threshold would be a number that drifts from JB-2.02's and nobody would
   notice until a 3-finger tap started undoing a frame flip.

8. **A gesture with three or more pointers never pinches.** `CanvasGestures.pinch` applies only at
   exactly two pointers; a third marks the gesture as the swipe's and no further `onViewChanged`
   fires for it. Two fingers are untouched. *Why:* the page moving under a frame flip is the
   single worst outcome available here — the frame changes and the picture slides, so neither
   happened. **Q3 asks the Lead which row owns the 2/3-finger assignment table** (JB-2.02b is
   drafted for exactly that and overlaps this row).

9. **The live size circle is drawn at the gesture's centroid, in BRUSH mode only, and only while
   the gesture runs.** Its radius is `SwipeBadge.sizeCircleRadiusScreenPx(...)`, which delegates to
   the real `SizeOpacityDrag`. *Why:* R10 — the control shows the on-screen circle at its true
   screen size while you drag, and the number written is document px. Showing the document-px number
   as a screen-px circle is the bug R10 was raised about.

10. **The live readout appears on the FIRST `Step` and is replaced on every later one; the badge
    returns to plain mode-only text on `end()`.** *Why:* a readout that is already correct before
    the finger moves is a label; a readout that changes under the finger is feedback. And the
    badge must not keep showing `7 / 12` after the fingers leave, or the person cannot tell a
    stale frame number from a live one.

11. **`Ended` gives one light tick, `Wrapped` one strong tick, and every other `Step` none.**
    The tick is fired **by the host, once per `Step`, at the moment the `Step` is handled** — not
    by `ThreeFingerSwipe`, which is `androidkit`-free and has no platform. *Why:* the whole reason
    the owner asked for "stop at the ends, with push-through" is that the stop is *felt*; a stop
    that is only visible is a stop people discover by pushing.

12. **The badge hides and shows with the rest of the chrome, and its mode survives.** The four-finger
    tap (`onToggleUi`) hides it; showing it re-reads the mode at that moment. *Why:* a badge that
    survives a hide shows a mode that was decided before you looked, which is the "stale control"
    smell; a badge that is re-read on show cannot be stale.

13. **No resources, no strings, no localisation, and no new dependency.** The two glyphs are point
    lists in `SwipeBadge` and the two readouts are formatted in `:core`; `androidkit` has no Android
    resources today and this row does not change that. *Why:* JB-0.06's open question is exactly
    this, and adding resources to `androidkit` to serve two glyphs would make that question worse,
    not better. **Q5 asks for the owner's iconography.**

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (no copy, no `badge()` in `onDraw`) | `drawingNeverAsksTheDocumentWhichModeItIs` (the overlay's draw path is invoked 50× with a `ThreeFingerSwipe` whose `badge()` is instrumented; the call count does not move) · `theBadgeIsReReadOnlyAtTheThreeNamedMoments` (tap / document change / shown) |
| 2 (no provenance) | `theBadgeHasExactlyTwoStates` — `SwipeBadge` declares no third value, and `readout` never contains `auto`, `manual`, `reset` or `default`, case-insensitively |
| 3 (top-END, avoids the corner) | `theBadgeSitsBelowTheCloseButtonAndNeverOverlapsIt` · `theBadgeIsAlwaysInsideTheSafeArea` |
| 4 (a tap mid-gesture is refused) | `aTapDuringAGestureChangesNothingAndIsStillAbsorbed` · `theNextTapAfterAGestureIsTheOverride` |
| 5 (pre-lock readout) | `beforeTheAxisLocksTheBadgeKeepsSayingTheLastCommittedValue` (PROVISIONAL — the test names Q4 in its KDoc) |
| 6 (`begin` on the first move) | `noTravelIsLostAtTheStartOfASwipe` — a swipe of exactly `STEP_DP` flips exactly one frame, measured from the first move |
| 7 (tap vs drag, one constant) | `threeFingersAndNoMovementIsRedoAndTheSwipeNeverBegins` · `threeFingersAndTwentyOnePixelsIsTheSwipeAndRedoNeverFires` · `theWanderThresholdIsCanvasGesturesOwnAndNotACopy` (reflection: no `Int`/`Float` in `SwipeOverlay` equal to 20f) |
| 8 (no pinch at three) | `threeFingerDragDoesNotMoveThePage` · `twoFingerPinchStillZooms` |
| 9 (the live circle) | `theSizeCircleIsTheRealSizeOpacityDragAnswer` — asserted **against the constructed class**, over 40 (size, zoom) pairs, not against a formula |
| 10 (readout lifecycle) | `theReadoutAppearsOnTheFirstStepAndGoesOnEnd` |
| 11 (ticks) | `endedTicksOnceAndWrappedTicksStrongerAndNothingElseTicks` — the tick log is `[false]`, `[true]`, and the *count* is asserted |
| 12 (hide/show) | `hidingTheChromeHidesTheBadgeAndShowingItReReadsTheMode` |
| 13 (nothing new) | `theBadgeNeedsNoResourceAndNoNewDependency` — `SwipeBadge.kt` and `SwipeOverlay.kt` declare no `R.` reference and no `Context` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/view/SwipeBadgeTest.kt`

Fixture: density 1.0 and 2.75; a safe rect `(0, 44, 1080, 2204)` (a Note 9 with a cutout); an
`avoid` list holding the close button at `(1020, 12, 40, 40)`.

1. `theBadgeSitsBelowTheCloseButtonAndNeverOverlapsIt`: `place(safe, 2.75, avoid = [close])` → the
   badge's `x` is inset from the right by `INSET_DP × 2.75`, its `y` is **≥ 40 + 8dp below the
   close button's bottom**, and `overlaps(close)` is **false**. Also asserted for `avoid = []` (the
   badge is at the bare inset) and for an `avoid` that touches the top edge.
2. `theBadgeIsAlwaysInsideTheSafeArea`: over a grid of safe rects (including a zero-width one and a
   safe rect smaller than the badge) the returned rect is clamped so
   `x >= safe.x && right <= safe.right && y >= safe.y && bottom <= safe.bottom`. A safe area smaller
   than the badge yields a rect of the safe area's size, **never a negative width** — the R19
   failure this exists to prevent.
3. `theTouchRectangleIsNeverSmallerThanFortyFourDp` and `neverZero`: at density 1.0, 2.0 and 2.75,
   with a drawn rect of 0×0, 1×1 and 28dp; every result has `w >= 44dp` and `h >= 44dp`.
4. `theReadoutIsTheFramesTheFingerIsOn`: `Badge(FRAMES, frame = 7, frameCount = 12)` → `"7 / 12"`,
   and frame 1 of 1 → `"1 / 1"`. **A frame count of 0 with mode FRAMES is a state the UI must never
   build**; the test asserts `readout` degrades to `"—"` rather than to `"7 / 0"`.
5. `theReadoutIsSizeOnTheHorizontalAxisAndOpacityOnTheVerticalAxis`: `axis = AXIS_SIZE`,
   `sizeDoc = 24.4f` → `"24 px"`; `axis = AXIS_OPACITY`, `opacity = 0.6f` → `"60%"`;
   `opacity = 0.605f` → `"61%"` (**ties away from zero**, with the derivation in a comment);
   `axis = AXIS_NONE` with `sizeDoc = 12f` → `"12 px"`.
6. `aNonFiniteValueSaysNothingRatherThanNaN`: `sizeDoc = NaN`, `opacity = Float.NaN`,
   `opacity = 1.7f` (out of range) → all three are `"—"`, and the string never contains `NaN`,
   `Infinity` or `%` with an out-of-range number.
7. `theSizeCircleIsTheRealSizeOpacityDragAnswer`: for 40 pairs of `(sizeDocPx, screenPerDoc)` drawn
   from `{0.5, 1, 12, 24, 4096} × {0.05, 0.5, 1, 4, 64}` (all finite, all positive),
   `SwipeBadge.sizeCircleRadiusScreenPx(size, zoom)` is **bit-equal** to
   `SizeOpacityDrag(size, 1f, zoom).previewRadiusScreenPx`. R19's rule in test form: the radius is
   read, never re-derived. A non-finite or non-positive input gives `0f`, not `NaN`.
8. `theTwoGlyphsAreCentredAndInsideTheirBox`: each glyph's points are within `[0, 1]`, each has
   ≥ 3 points, the union's bounding box is centred on (0.5, 0.5) within 0.02, and the two glyphs
   are **not equal** (a badge whose two icons are the same picture is not a mode indicator).

`joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/SwipeOverlayTest.kt`
(a fake `Canvas`, a fake haptic sink, a real `ThreeFingerSwipe` and a real `JbDocument`)

9. `drawingNeverAsksTheDocumentWhichModeItIs`: `draw()` 50 times, and the badge's drawn mode and
   rect are **identical** on all 50 — then `refresh(doc)` once and they still are, because nothing
   about the document reached the drawing. Asserted **from the source of `SwipeOverlay.kt`** as
   well, because a call-count assertion against a final Kotlin class is not available: the `draw`
   function's body must contain **no** `badge(` and no `refresh(`. This is the same
   read-the-source-as-a-test trick as case 19, and it is the mechanical form of the reviewer's
   finding rather than a promise in a comment.
10. `theBadgeIsReReadOnlyAtTheThreeNamedMoments`: a counting `SwipeOverlay` subclass is impossible
    (`badgeMode` is `private set`), so the count is asserted on the **call log** the test feeds in:
    over a scripted sequence — board switch, frame added, board deleted, screen shown, screen
    hidden, then 20 `draw`s — the drawn mode equals `swipe.badge(doc)` at every step (so the badge
    is never stale), and the *only* moment at which a stale value would be visible is a draw, which
    is asserted by the previous case. Two draws separated by a board switch show the OLD mode until
    `refresh` is called, and the switch path calls it — asserted as: `onDocumentChanged(doc)` then
    `draw()` shows the NEW mode.
11. `aTapDuringAGestureChangesNothingAndIsStillAbsorbed`: begin a 3-finger gesture, tap the badge →
    `badgeMode` unchanged, `gestureMode` unchanged, and the tap's return value is `true` (absorbed,
    not passed to the canvas). `theNextTapAfterAGestureIsTheOverride`: after `end()`, the same tap
    flips it.
12. `noTravelIsLostAtTheStartOfASwipe`: three pointers down, then a single `ACTION_MOVE` with the
    centroid `STEP_DP` to the left → exactly one `Step.ShowFrame`, index = begin + 1. The 3.08a
    maths is not changed; what is tested is that `begin` saw the centroid at rest.
13. `threeFingersAndNoMovementIsRedoAndTheSwipeNeverBegins` / `threeFingersAndTwentyOnePixelsIsTheSwipeAndRedoNeverFires`:
    through the **real** `CanvasGestures`, driven with fake `MotionEvent`s (or its pure event-feeding
    seam, if the builder adds one — and says so in Questions if it does). The first case: the
    gesture machine's redo callback fires **once** and `ThreeFingerSwipe.begin` was never called.
    The second: redo fires **0** times, the swipe begins, and the page's `panX/panY/zoom` are
    **unchanged** — asserted by value, before and after.
14. `twoFingerPinchStillZooms`: the same two-finger event run through the machine → `view.zoom`
    changed. Decision 8 must not cost the gesture JB-2.02 shipped.
15. `theSizeCircleIsDrawnAtTheCentroidOnlyWhileRunning`: a recording `Canvas`; during a BRUSH
    gesture one circle is drawn with radius equal to `sizeCircleRadiusScreenPx` at the centroid
    `x, y`; after `end()` the next `draw` draws **no** circle; during a FRAMES gesture there is
    **never** a circle.
16. `theReadoutAppearsOnTheFirstStepAndGoesOnEnd`: the readout text recorded at each draw, for a
    scripted BRUSH gesture → `[null, "24 px", "24 px", "61%", "61%"]`-style trace, where the first
    entry is "no readout yet" and the last (after `end`) is "no readout".
17. `endedTicksOnceAndWrappedTicksStrongerAndNothingElseTicks`: 10 frames, start at 4, drag to the
    first frame and push through; the tick log is exactly `[false]` then `[true]`, and a
    6-frame `ShowFrame` run produces an **empty** log. Counted, not described.
18. `hidingTheChromeHidesTheBadgeAndShowingItReReadsTheMode`: hide → the draw records no badge
    rect; show → the badge is back and the read count incremented by exactly 1.
19. `theBadgeNeedsNoResourceAndNoNewDependency`: `SwipeBadge.kt` and `SwipeOverlay.kt` contain no
    `R.`, no `Context`, and no `getString`. Asserted by reading the two source files' text — the
    mechanical form of Decision 13, and the same trick JB-4.03a test 2 and JB-4.03b test 15 use.
20. **Non-vacuity the builder must run and paste.** (a) Delete the `suppressTapOnce()` call and run
    test 13's second case: redo fires and the swipe fires. (b) Move `onBegin`'s mode copy out of
    `onBegin` and into `move`: change the document mid-gesture (delete the board) and watch test
    11's `gestureMode` assertion go red — **that is Decision 4's whole content**.

**Command:** `./gradlew -p joybrush :core:jvmTest :androidkit:test` — 0 failures.

## Steps

1. Write `SwipeBadgeTest.kt` **first**, in full, from the Tests section above. It cannot pass yet and
   that is correct.
2. `SwipeBadge.kt` until test 8 is green. `place` in `Float`, convert once (R19).
3. `SwipeOverlayTest.kt` next, in full.
4. `CanvasGestures.kt`: add `suppressTapOnce()` (one flag, cleared by the next `up(ev)`) and gate
   `pinch` on `ev.pointerCount == 2`. **Touch nothing else in that file** — JB-2.02 is Built and
   16 tests plus a cleared review are standing behind it.
5. `JbCanvasView.kt`: construct one `SwipeOverlay`, add it to nothing (it is not a `View` — it is
   drawn by the screen's overlay pass; see Q6), and forward the raw finger events to it. **Two
   lines**, named in this spec, and both must be inside a comment saying `JB-3.08`. If the wiring
   turns out to need more than that, **stop and put it in Questions** — `JbCanvasView` is shared
   with JB-2.03a and JB-2.06b and R18 says they never run together.
6. `SwipeOverlay.kt` until test 19 is green.
7. Run the non-vacuity proof and paste it.

## Do not

- **Do not resolve Q1.** Do not decide whether an override is per-board or single, do not add a
  "reset" affordance, an AUTO/MANUAL label, or a message about an override being dropped. The
  `ThreeFingerSwipe` file is not this row's to edit and its two red tests are waiting on the Lead.
- **Do not call `swipe.badge(doc)` from a draw, a layout, or an animation tick.** Decision 1. It is
  a query with a side effect today, and the side effect is the unresolved question.
- **Do not re-derive the mode, the step, the wrap, the axis lock, the size or the opacity.** All of
  it is `ThreeFingerSwipe` and `SizeOpacityDrag`. Call them.
- Do not copy `TAP_SLOP_PX`, `TAP_MS`, `STEP_DP`, `WRAP_PUSH_STEPS` or `MIN_FLIPPABLE_FRAMES` into
  this row's code. Read them from the class that owns them.
- Do not add Android resources to `androidkit` for the two glyphs (Q5), and do not add a font.
- Do not touch `JoyBrushActivity.kt` (a different row, and it is where the `avoid` rectangle will
  eventually be supplied — Q6), `AnimOps`, `PlaybackClock`, the film strip, or any app file.
- Do not implement the 2/3-finger **assignment** UI. That is JB-2.02b (Q3).
- No new dependencies, no `enum`, no `@Serializable` type — so LEAD_RULINGS R3 is not engaged.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest :androidkit:test` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (no `suppressTapOnce` → test 13 red; mode read in `move` → test 11 red)
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the five owner-area paths
- [ ] **owner check, Note 9, one minute:** on a 2-frame animation board, swipe three fingers left —
      the frame changes, the page does not move, and the badge says `2 / 2`; tap the badge, swipe
      again — the **brush size** changes instead and the badge says the size; tap it back; tap three
      fingers with no movement — undo fires; drag three fingers 20px — no undo, the swipe.
- [ ] committed `JB-3.08: three-finger swipe badge and readout`; the ROADMAP row is set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. Every part of this spec that can be
decided without the owner is decided and pinned. The first question is not mine, the second is a
finding about code a reviewer flagged, and the rest are small.)_

### Q1 — 🔴 for the Lead: the badge-override contradiction is NOT resolved here, and this spec is built so it does not have to be

JB-3.08a's Decision 2 KDoc says an override "can never be restored" and, in the same KDoc, that a
canvas board and an animation board "can each keep their own answer". The test
`anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother` asserts **both** (line 128 "coming
back does NOT bring it back", line 130 "each board keeps its own answer"), and the orchestrator's note
on the ROADMAP row records that the single-override + `forgetOtherBoard` model **cannot pass both
halves** — so no edit to that file can make the suite green.

**What I need ruled: is the override per-board and persistent, or single and forgotten on switch?**

**What I have done instead of deciding, and this is the part worth checking:**

- The UI **never stores** the override, the board it was made on, or whether one exists. It stores
  the mode it last *drew*, and refreshes it at three named moments only. Whichever model wins, the
  sequence of reads is the same and the badge shows whatever `ThreeFingerSwipe.badge()` says.
- The UI **never labels the source** of the mode. No AUTO, no MANUAL, no "reset", no "your override
  was forgotten". Under ruling A (single, forgotten) an "override on" chip would be a lie half the
  time; under ruling B (per-board, persistent) a "back to automatic" chip is not always reachable
  in one tap, because after a board switch the badge is showing the automatic answer and a tap
  would *set* an override rather than clear one. **Both affordances are wrong under one of the two
  rulings, so there are none.**
- The tap is always `swipe.tapBadge(doc)`. Under **both** rulings a second tap on the same board
  returns to automatic (that half is not in dispute), so the badge is always one tap from the other
  mode and the "one tap from both answers" property in the 3.08a KDoc holds either way.

**If the ruling is B (per-board, persistent), one line of this spec becomes available and I have
deliberately not written it:** the badge could offer a long-press "clear the override for this
board". Under ruling A that long-press is meaningless, which is why it is not here.

### Q2 — 🔴 a finding about JB-3.08a, not a question I can answer: `badge()` mutates state from a query

`ThreeFingerSwipe.badge(doc)` calls `forgetOtherBoard(doc)`, which **writes** `overrideBoardId` and
`overrideMode`. So a function that reads like `badge(doc): SwipeMode` is a command wearing a query's
clothes, and two things follow that a reviewer already noticed and a builder will hit:

1. **It must never be called from a draw pass**, or the drawn answer depends on how many times the
   screen has been drawn. Decision 1 forbids it and test 9 makes the prohibition mechanical.
2. **Its name is a promise the code does not keep**, and Q1's ruling will change what it does. My
   recommendation, for whoever rules: either rename it to something that admits the write
   (`resolveBadge`), or split it into a pure `peek(doc)` and a mutating `badge(doc)`. **I have not
   asked for either**, because renaming a Built row's public method is a Lead's call and a
   cross-review of 3.08a follows the ruling anyway.

### Q3 — for the Lead: which row owns the 2/3/4-finger assignment table

JB-2.02b ("Tool finger modes and assignable 2/3-finger gestures", T2, Draft)
exists to let the person *assign* gestures to fingers, and Decision 8 of this spec hard-codes
"three fingers are always the swipe". Two rows now claim the same three fingers:

- **What I have ruled (provisionally, and it is the minimum this row can ship):** ≥ 3 pointers is the
  swipe; 2 is the pinch; the tap/drag split inside the swipe is the tap test. This is the only thing
  that makes the two features coexist without a settings screen, and it is what 3.08a's maths was
  written against.
- **What I need ruled:** does JB-3.08's claim become a **default that JB-2.02b can change**, or is it
  **fixed**? If it is a default, JB-2.02b must be able to turn the three-finger swipe OFF, and this
  row should say so now so 2.02b is not written against a fixed rule. If it is fixed, JB-2.02b
  should be told it only owns the *tool* finger modes.

I have not edited JB-2.02b's spec and will not.

### Q4 — for the owner (small, and I ruled it so the row is buildable): what the badge says before the axis locks

`SizeOpacityDrag` reports `Axis.NONE` for the first 12dp of travel. Decision 5 says the badge keeps
saying the last committed value in that window. The alternative is to show both (`"24 px · 60%"`),
which is a wider badge over a drawing. **Mine is a guess about a 12dp window on a phone; say the
word and it changes.** It is one `when` branch and one test.

### Q5 — for the Lead: the two glyphs are drawn from point lists, and the house icons are generated

`androidkit` has no Android resources today (JB-0.06's open question, still open), so the running
figure and the brush are two `List<List<FloatArray>>` in `SwipeBadge`, stroked at 1.7dp. That is the
right call for two glyphs and it is **not** how this project draws icons — the app generates them
from HTML `<symbol>`s at stroke 1.7 via `tools/spritelab/genicons.py`, and the visual language says a
Joy Brush icon set should be authored the same way. Options: (a) keep the point lists (zero
dependencies, done today); (b) generate a two-glyph set into `androidkit`'s resources, which also
**settles JB-0.06's question in the affirmative** and gives the diagnostics panel its two colours for
free. I lean (b) but it is a resource decision on a module that currently has none, so it is not
mine.

### Q6 — for the Lead: how the badge is composited, given JB-2.01 does not exist yet

`SwipeOverlay` is deliberately **not a `View`**: it is a plain object with a `draw(Canvas)`, because a
`View` on top of a `GLSurfaceView` has to be told when to invalidate, and a badge that redraws at the
wrong time is a badge that lies for a frame. So something has to own the overlay pass, and today the
only thing that does is `JoyBrushActivity`'s own overlay `FrameLayout` — a file this row may not
edit. The three ways:

- **(a) `JoyBrushActivity` adds one more `View` to the overlay layer that draws the badge.** Needs
  ~6 lines in that file and a `setWillNotDraw(false)` overlay. Simplest, and it is one file that
  four other rows also want.
- **(b) The badge is drawn by `JbCanvasView` itself**, in the same pass that draws the paper, so it
  lives with the canvas and moves with it. Wrong on two counts: it is UI over a GL surface (a `GLSurfaceView`
  cannot draw 2D without a second surface or a `TextureView`), and the badge is UI, not picture.
- **(c) Wait for JB-2.01** (screen chrome) to own the overlay pass and hand this row a slot.

I have written the `JbCanvasView` step as two lines and left the **compositing** to (a), because
delaying the whole row on a Draft row that itself waits on this one (`JB-2.01` Needs `0.09`, which is
blocked) would deadlock the runway. **Say the word and I will make (a) part of the owner area** — it
is a six-line edit to a file the Lead's serialised order does not cover, since `joybrush-android` is
not an app file, but four dispatched rows have it open at once.
