# JB-2.02b — The tool finger (select · lasso · pick) and assignable 2/3-finger gestures

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.02 (`ViewTransform`, `CanvasGestures` — Built), JB-2.05 (selection + transform UI) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/ToolFinger.kt`, NEW `.../tool/GestureMap.kt`; NEW `.../commonTest/.../tool/ToolFingerTest.kt`, `GestureMapTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/ToolFingerRouter.kt`; EDIT `.../androidkit/CanvasGestures.kt` (call the map instead of the hard-coded taps); EDIT `joybrush-android/.../JoyBrushActivity.kt` (a mode badge + a settings screen) |
| **Estimated size** | ~200 lines of core + ~180 lines of tests; ~150 lines of wiring |

## Goal

OWNER_CONSTRAINTS, 2026-09-28: *"with no pen seen, fingers draw. The moment a pen is detected, fingers
stop drawing and become a **tool finger** with modes the user cycles between — **select objects /
lasso / colour pick** (Concepts' model; "that's really what the finger is for") — plus nudging. Two-
and three-finger gestures are **user-assignable in settings**."*

Blueprint §3.5 says the same, and adds *nudging* to the same sentence. So this row delivers four
finger modes and an assignable gesture table, and it must not invent a fifth.

The hard part is not the badge; it is that `CanvasGestures` currently has the tap mapping written
into one three-line function (`tap(fingers)`), and JB-2.02's own Q7 said that was deliberate:
*"the tap mapping is hard-coded in one 3-line function. Correct call … keeping it in one place is
what makes lifting it out cheap."* This is that lift.

## Contract

```kotlin
package cc.joycreator.joybrush.core.tool

/**
 * What ONE finger does after a pen has been seen. Before a pen is seen the finger DRAWS and this
 * enum is not consulted at all (LEAD_RULINGS R5) — a mode that only exists after a pen would be a
 * mode that cannot be tested on a device with no pen, so the router is told the answer.
 */
enum class FingerMode {
    /** Tap/drag picks and transforms what is selected (JB-2.05). */
    SELECT,
    /** Drag draws a lasso (JB-2.05a's polygon). */
    LASSO,
    /** Tap takes the colour under the finger (JB-2.03a's sampler, no ring gesture here). */
    PICK,
    /** Drag moves the selection by one nudge per `Nudge.stepDoc` (zoom-scaled, JB-2.16a). */
    NUDGE,
    ;
    companion object {
        /** The next mode in the owner's order, wrapping. */
        fun next(m: FingerMode): FingerMode
    }
}

/** What a 2- or 3-finger TAP does. These are the only two counts that are assignable. */
enum class FingerAction {
    NONE, UNDO, REDO, TOGGLE_CHROME, FRAME_PREV, FRAME_NEXT, ZOOM_FIT,
}

/** Fingers, 1..5, → action. Pure table, serialisable, the settings screen's whole model. */
data class GestureMap(
    val twoFingerTap: FingerAction = FingerAction.UNDO,
    val threeFingerTap: FingerAction = FingerAction.REDO,
) {
    /** What a tap of [fingers] fingers does, or NONE for 1, 4 and 5. */
    fun actionFor(fingers: Int): FingerAction
    fun withTwoFingerTap(a: FingerAction): GestureMap
    fun withThreeFingerTap(a: FingerAction): GestureMap
    companion object { val DEFAULT: GestureMap }
}

/**
 * The one finger's state for the current screen. Lives as long as the screen; every decision is a
 * pure function of (mode, current state) so it can be tested without a device.
 */
class ToolFinger(val map: GestureMap = GestureMap.DEFAULT) {
    /** The badge's content. */
    var mode: FingerMode = FingerMode.SELECT
        private set
    /** Cycles to [FingerMode.next]. Returns the new mode. */
    fun cycle(): FingerMode
    /** A long-press or a badge tap is what cycles; a tap is a tap. */
    fun setMode(m: FingerMode): FingerMode
}
```

## Decisions

1. **Four modes, in the owner's order, and NUDGE is the fourth.** Blueprint §3.5's sentence puts
   nudging in the same list as the three, so it is a mode and not a gesture. It is deliberately
   LAST: cycling is a long-press-and-release or a badge tap, and the three the owner named are
   what a person meets first.
2. **One-finger DOWN routes by mode; a drag is the mode's drag.** SELECT drags the selection
   (JB-2.05), LASSO drags a lasso, NUDGE drags the selection in `Nudge.stepDoc` increments, PICK
   takes the colour on UP. There is no "the mode is only for taps" reading, because a lasso is a
   drag by definition and Concepts' model has no other way to draw one.
3. **`fingersNavigate` is the switch that makes this work at all, and it is not enough.** It says a
   finger must not draw; it does not say what the finger should DO. The router below runs only when
   it is true, so a mode is never consulted for a drawing finger.
4. **Two- and three-finger taps are assignable; 4 and 5 are not.** 4-finger tap is
   `TOGGLE_CHROME` (JB-2.02's `onToggleUi`, blueprint §3.5 "4-finger tap hides the UI") and the
   blueprint does not offer it as assignable. 5-finger tap is nothing on any device and the table
   refuses it. The settings screen shows 4-finger as a **read-only row saying "Hides the drawing
   controls"** — a setting that shows one fixed value is more honest than hiding the row.
5. **The two rows must not be set to the same action.** `withTwoFingerTap(REDO)` on a map whose
   three-finger row is already `REDO` is **refused in words** (returns the map unchanged and the
   screen says which two are the same). Two identical rows means one of them is unreachable, which
   is exactly the class of silent loss this project keeps filing.
6. **`NONE` is a legal value for both rows and means "do nothing on that tap"** — not "unassigned".
   The gesture is still consumed (a two-finger tap is never also a pan-start), so `NONE` is
   silence, not a leak.
7. **Persistence is the app's `SharedPreferences`, one key, JSON of the two enum names**, and a
   name this build does not have falls back to `GestureMap.DEFAULT` **with a log line**, never
   crashes — the same asymmetry `BlendModes.modeCode` already uses for a wire value.
8. **The mapping table moves; the tap DETECTION does not.** `TAP_MS = 250` and `TAP_SLOP_PX = 20`
   stay in `CanvasGestures` (JB-2.02's constants, referenced by JB-2.17's cheat-sheet) and this
   row replaces one call: `tap(maxPointers)` becomes `fire(maxPointers)`, which asks the map. The
   detection's tests stay where they are and must stay green unchanged.
9. **R5's "Fingers draw" setting lives here, not in JB-2.02.** `fingersNavigate = !fingersDraw && penSeen`
   — a person who wants to draw with fingers after a pen has been seen sets it on, and a pen still
   always draws. Default: fingers draw until a pen is seen (R5, owner's ruling), i.e. the setting
   defaults to *off* meaning "no finger drawing after a pen".

## Tests

`GestureMapTest` (JVM, pure):
1. `DEFAULT` answers UNDO for 2 and REDO for 3; `actionFor(1)`, `actionFor(4)`, `actionFor(5)`,
   `actionFor(0)`, `actionFor(99)` are all `NONE` — never a crash, never a default.
2. `withTwoFingerTap(REDO)` on the default map is **refused and returns a map equal to the
   original**; `withThreeFingerTap(REDO)` on a map whose two-finger row is REDO is likewise.
3. Round trip through the JSON encoding of both rows, including a name this build does not know
   (falls back to DEFAULT, and the log-line decision is recorded in the KDoc).

`ToolFingerTest`:
4. `cycle()` from SELECT walks SELECT→LASSO→PICK→NUDGE→SELECT and returns the new mode each time;
   the badge value equals the mode at every step.
5. `setMode` is idempotent and `cycle()` after it starts from the mode set, not from SELECT.

`ToolFingerRouter` (androidkit; a JVM test with a fake sink, no Android runtime):
6. With `fingersNavigate = false` the router is **never called** — a finger that draws is not a
   tool finger. (The fake sink counts calls and the test asserts zero.)
7. A one-finger drag in LASSO reports a lasso, never a pan, and never a stroke.
8. A one-finger tap in PICK reports exactly one pick, at the DOWN point.
9. A one-finger drag in NUDGE reports the total offset in **document px**, equal to
   `Nudge.stepDoc(zoom) × (steps crossed)`; at zoom 4 a 10 px drag is 2.5 document px.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then
`./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` and the watcher green.

## Do not

- Do not change `TAP_MS`, `TAP_SLOP_PX` or `MIN_SPREAD_PX`. JB-2.17's cheat sheet quotes them.
- Do not change the palm-rejection rules (JB-2.02 Ruling 3: palm rejection is a hard ignore and
  wins). A palm is still not a tool finger.
- Do not add a mode. Four. If a fifth is wanted it is a Lead ruling, and it needs a place to be
  tested, not just a name in an enum.
- Do not implement selection, lasso or picking maths — JB-2.05, JB-2.05a and JB-2.03a own them.
  This row is the router and the table.
- Never run gradle on the owner's PC.

## Definition of done

Tests pass (paste) · `git status --short` shows only owner-area files · watcher green ·
commit `JB-2.02b: tool finger modes and assignable gestures` · ROADMAP row → 🟧 Built.

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **Is NUDGE a mode or a gesture, and does this change anything you have already said?** The
   blueprint's §3.5 sentence puts it in the mode list; the roadmap row names only "select · lasso ·
   colour pick". I read NUDGE as a fourth mode because §3.5's sentence is the owner's own words
   and it is the only place the four appear together. If it is instead a modifier (nudge happens
   *whenever* the selection moves, rather than being a mode you enter), that is a smaller class and
   a different Decision 2. **The user-visible cost of my reading:** a person who wants to nudge has
   to cycle to NUDGE first, and a person in SELECT who drags the selection by accident moves it a
   lot instead of a little. That is a real feel question and it is yours, not mine.
2. **What CYCLES the mode — and with what cancel?** A long-press cycles (but long-press is already
   the eyedropper, JB-2.03a Decision 2, which cancels by sliding back or by a second finger), a
   badge in the cluster cycles (JB-2.01, which I am writing in parallel), or a two-finger tap does
   (which Decision 6 makes a *user-assignable row*). My reading: the **badge in the control cluster**
   is the only one that cannot collide, and this row emits an event rather than owning a button.
   But that couples 2.02b to 2.01's cluster, and 2.01 is Draft. Confirm the badge, and say where it
   lives when the chrome is hidden — a hidden chrome with a hidden mode badge means the mode cannot
   be changed, which is a trap.
3. **Is `ZOOM_FIT` in the assignable set at all?** I added it because the blueprint mentions "quick
   pinch fits the board" as a standard gesture and the only place a *tap-count* could carry it is
   here. It is also the one action in the list that needs a document rect this row has no access
   to. If you would rather the table held only undo/redo/chrome/frame actions, say so and I will
   drop it — it is the one entry in the enum that cannot be tested end-to-end from this spec.

### Low-risk, ruled provisionally

4. **`NONE` is a value, not the absence of one** (Decision 6), and the settings screen offers it as
   "Do nothing". Reversible in one line.
5. **A two-finger tap is consumed even when its action is `NONE`** (Decision 6), so a person who
   sets both rows to `NONE` loses undo-by-gesture entirely and must use the cluster buttons. That
   is what they asked for; the settings row says so.
6. **The 4-finger row is shown read-only** rather than hidden (Decision 4).
