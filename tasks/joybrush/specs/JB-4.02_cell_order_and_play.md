# JB-4.02 — Tap cells in order, and play the preview (SpriteLab's chip mechanic, reused)

| | |
|---|---|
| **Tier** | **T2** (the roll is pure `:core` maths, and the timing it drives is the project one reviewed clock) |
| **Status** | 🟦 **Ready** — core half only. R36's Q1 is inlined as Decision 15. No Lead ruling is outstanding for the build. |
| **xr** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — cut to the **core half** per R30; **the Q2 finding is CONFIRMED real and its fix has LANDED** (`Clip.weights` is in `SpritePacker.kt:40`; the packer writes `weights` under the app's own `hasWeights()` rule at `SpritePacker.kt:285-289`), so the old Q2 is closed and this spec no longer describes a hold as repeated cell indices anywhere; **the roll maths is independent of the packer** — `Clip` does not appear in this row's file, in its tests, or in its contract; R36 Q1 (session-only) stated plainly as Decision 15 rather than left open; **every line number quoted from the Studio's Java was re-read this pass and two of the old spec's were wrong** (`labSeq.add` is 2122, not 4304; `unAddCell` is 4123, which was right); `bumpHold`'s clamp is confirmed as 1..16 at line 2210. |
| **Needs** | JB-4.01 (the grid's cell count — **a number, not a class**: this row does not import `SpriteBoard`), JB-3.05a (`PlaybackClock` + `FrameStepper`, 🟧 Built), JB-0.02 (`Board`, `Frame`, `DocOps`). |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/CellRoll.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollTest.kt` · (3) NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollShapeTest.kt` · **NOTHING ELSE.** In particular **NOT** `SpriteGridView.kt` (the view half's, and it does not exist yet), **NOT** `export/SpritePacker.kt` (Reviewed — a re-review follows any edit), **NOT** `export/AnimExportPlan.kt`, **NOT** `doc/`, **NOT** anything in `joybrush-android/` or `app/`, no Gradle file. |
| **Estimated size** | ~200 lines of Kotlin, ~320 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## What was CUT, and where it now lives

The review's verdict is `🟦 (core half)`: *"roll maths independent of the packer; export of holds waits
for 4.03c."* The old spec promised the roll **and** a preview view **and** an edit to the sprite grid
view to draw the badges. The last two are a `JoyBrushActivity`-adjacent cluster the Lead owns. So:

| Cut | What it was | Where it now lives |
|---|---|---|
| `RollPreviewView.kt` | the preview: the scrub bar, the playing-cell highlight, the play chip | the **view half of JB-4.02**, the Lead's, behind JB-2.01. **No spec for it exists yet.** |
| `EDIT SpriteGridView.kt` (the order badges) | `setOrderBadges`, the badge's colour and shape | the same view half, and the badge's *state* is JB-4.01's Cut Decision C1. **The rule** — a cell in the roll three times shows the order of its **LAST** entry — is pure arithmetic, so it stayed here as `lastOrderOf` and is tested here (test 13). |
| The three wrap-mode CHIPS (`loop` / `pingpong` / `once`) | chrome | the view half. The **mapping** onto `PlayMode` is here and is tested (test 11), because a mapping with no test is a mapping that drifts. |
| "Play all" / "Clear" as buttons | chrome | the view half. Both are `CellGesture` cases and both are here, tested (tests 7, 8). |
| **The export of a roll** | `Clip` construction, sidecar, `SpritePacker.pack` | **JB-4.03b** (the buttons) and **JB-4.03c** (the weights, `🟧 Built`). This row hands them two parallel lists and nothing else — see Decision 13. |
| The old Q2 in full | "a Built contract cannot express a held frame" | **CLOSED.** The finding was real; R36 confirmed it; JB-4.03c fixed it and the code is landed. What is left is one new MINOR finding about a different file, in Q1 below. |

**This row builds the roll, its grammar, its playback and its timing. It does not draw a chip, a badge
or a preview, and it does not write a file.**

## Goal

Blueprint §1 idea 9, verbatim: **"tap cells in order, play preview"**, with the change:

> *"The tap-cells-then-play mechanic is SpriteLab's chip system — **reuse it, don't rebuild it**."*

R23 is the owner's rule that makes "reuse" a hard word: *"When Joy Brush needs something the Studio has,
it moves into `:studiokit` … improvements land once."* So the **grammar** here is SpriteLab's, copied
faithfully and deliberately — the tap / untap / hold triple, the badge order, the roll — and the
**timing** is Joy Brush's, because Joy Brush already has the one reviewed playback clock in the project
and a second player would be the exact duplication R23 exists to stop.

## What SpriteLab's mechanic actually is, read from the shipped code

`app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java` (4 823 lines) is the
authority (R23). These are its rules, transcribed, and this spec implements them rather than re-inventing
them. **Every line number below was read in this pass** — the old spec quoted two that are wrong, and a
quotation a builder cannot check is a decoration:

- The roll is a `List<int[]>` of `{cellIndex, hold}`, in tap order: `labSeq`, line **244**.
- **A tap on a cell appends it, with hold 1.** Line **2122**: `labSeq.add(new int[]{currentCell(), 1})`,
  then `focusRoll(labSeq.size() - 1, true)`. Always hold 1 — a tap is one tick.
- **A tap on the cell's BADGE takes the LAST one back.** `unAddCell(cell, all=false)`, line **4123**;
  lines 4131-4133 walk `for (int i = labSeq.size() - 1; i >= 0; i--)` and `break` on the first match, so
  it removes the LAST use. (Dispatched from `onBadgeTapped` at line 289.)
- **A long-press on the badge clears every use of that cell** — `unAddCell(cell, all=true)`, lines
  4126-4129, dispatched from `onBadgeHeld` at line 291. The Studio's own comment at 4118-4121 says why:
  *"building a roll is a rhythm of taps on the sheet, and having to break off and hunt down the wrong
  chip on a strip that has scrolled somewhere else breaks it."*
- **A cell may appear many times.** A repeat is how ping-pong is written.
- **A hold is per ENTRY, not per cell.** `bumpHold`, line **2207**:
  `f[1] = Math.max(1, Math.min(16, f[1] + delta))` on `labSeq.get(labCur)`. So tapping cell 3 three times
  gives three entries of hold 1, and holding one of them gives that one entry a hold — the cell is not
  globally "held for 3". The clamp is **1..16** and it is in the app's own words.
- **The app prunes `frames` and `weights` together**, `pruneDeadFrames` line **4211**, and at line
  **4222** it removes `pr.weights[i]` in the same pass that removes `pr.frames[i]`. That is the app
  itself saying the two arrays are parallel and per entry.
- **`advanceRoll` steps by one FRAME, not one entry** (line 820): `if (++labHold < max(1, labSeq.get(labCur)[1])) return true;`
  — a hold of ×4 stays put for four ticks — and ping-pong turns with `labCur = labSeq.size() - 2; labDir = -1`.
- **The wrap mode is `loop` / `pingpong` / `once`**, in `SpriteSheet.Preset.type`, and the app's own
  quote for building a roll by hand is on the strip: line 532, `f[0] + ":" + f[1]` per entry.
- **The quote the old spec used for `prunedTo`** is at lines 4196-4201: *"Take a 4x7 sheet down to 2x2 and
  the roll still held frames 4..27 … Nothing crashed, because the renderer bounds-checks and draws
  nothing, which is the worst of both worlds."*

## Contract

### What this file calls — pasted verbatim from the landed source

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PlaybackClock.kt`:

```kotlin
/** How a board plays once it reaches the end of its range. */
enum class PlayMode { LOOP, PING_PONG, ONCE }

class PlaybackClock(
    board: Board,
    val mode: PlayMode = PlayMode.LOOP,
    firstFrame: Int = 0,
    lastFrame: Int = board.frames.size - 1,
    speed: Float = 1f,
) {
    val rangeMs: Double
    fun frameIndexAt(elapsedMs: Double): Int
    fun isFinished(elapsedMs: Double): Boolean
    fun nextChangeMs(elapsedMs: Double): Double
}
```

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FrameStepper.kt`:

```kotlin
class FrameStepper(val clock: PlaybackClock) {
    fun step(elapsedMs: Double, frameIndex: Int, audioAtMs: Double?): List<Step>
    fun frameOnScreen(elapsedMs: Double): Int
    fun nextWakeMs(elapsedMs: Double): Double
}
```

`FrameStepper.step` "takes the frame that is CURRENTLY on screen and never emits [Step.ShowFrame] for
it" — that one parameter is the whole of the fix for the backward-boundary spin. The sprite preview
inherits it by using the class rather than reimplementing the loop (Decision 9).

And from `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`:

```kotlin
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable data class Frame(
    val id: String,
    val holdFrames: Int = 1,                // shown for this many ticks of the board's fps
)

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

**`PlaybackClock` takes a `Board`, not a document** — so the roll can be played without touching the
document at all, which is what makes Decision 15 cheap rather than a format change.

### The new file

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/CellRoll.kt`

```kotlin
package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx

/**
 * The roll: the cells a person tapped, IN ORDER, each with its own hold. This is SpriteLab's
 * `labSeq` (`SpriteSheetEditorActivity.java:244`) and it is the same shape on purpose.
 *
 * IMMUTABLE, like every other value in this project. Every operation returns a new roll and none of
 * them mutates, so the undo stack and a preview can both hold one and be sure of what they hold.
 *
 * **The roll is SESSION STATE and is not in the document** (Decision 15, R36 Q1). It is not a field on
 * `SpriteGrid`, it is not serialised, and it is not written into any export's plan layer. A person who
 * builds a walk cycle's order, closes the drawing and comes back finds a bare grid — see Q2, which is
 * the honest cost of that decision and the Lead's to revisit.
 *
 * **This class knows nothing about a grid.** It is told a cell COUNT and refuses anything outside it.
 * That is not tidiness: it is what keeps this row off `SpriteBoard`, and therefore off JB-4.01.
 */
data class CellRoll(val entries: List<Entry> = emptyList(), val cursor: Int = 0) {

    /** One tap: which cell, and how many ticks it is held for. A hold belongs to an ENTRY. */
    data class Entry(val cell: Int, val hold: Int = 1)

    val isEmpty: Boolean get() = entries.isEmpty()
    val size: Int get() = entries.size

    /**
     * Tap a cell: append `{cell, 1}` and put the cursor on it. `SpriteSheetEditorActivity.java:2122`,
     * exactly, and always hold 1 — a tap is one tick and never inherits an earlier entry's hold.
     * A cell outside `0 until cellCount` is refused and the roll is returned unchanged.
     */
    fun tapped(cell: Int, cellCount: Int): CellRoll

    /**
     * Tap a badge: remove the LAST use of [cell], scanning backwards — `unAddCell(all=false)`,
     * lines 4131-4133. Returns this roll unchanged if there is none.
     */
    fun untapped(cell: Int): CellRoll

    /**
     * Long-press a badge: remove EVERY use of [cell] and report how many went, so the caller can say
     * "removed every use of cell 7" in words rather than silently changing the picture.
     */
    fun untappedAll(cell: Int): Prune

    /**
     * Hold +1 / −1 on the entry under the cursor, clamped to 1..16 — `bumpHold`, line 2210,
     * `Math.max(1, Math.min(16, f[1] + delta))`. Per ENTRY.
     */
    fun holdBumped(delta: Int): CellRoll

    /** Move the cursor to an entry, clamped into the roll. */
    fun focused(index: Int): CellRoll

    /** The app's `Clear` chip (line 2191): empty, cursor 0, and the grid's selection stays the
     *  "current cell" (`focusCell(Math.max(0, gridView.getSelectedCell()), true)`, line 2197). */
    fun cleared(): CellRoll

    /**
     * "Play all" — every cell in `0 until cellCount`, hold 1, in reading order, REPLACING the roll.
     * The app's own `playAll` does `labSeq.clear()` first (line 2134) and then adds (line 2138).
     */
    fun playAll(cellCount: Int): CellRoll

    /**
     * Drop every entry naming a cell this grid no longer has — index outside `0 until [cellCount]` —
     * and report how many went. `pruneDeadFrames`, line 4211, which walks backwards and reports a
     * count, and which prunes `weights` in the same pass (line 4222).
     */
    fun prunedTo(cellCount: Int): Prune

    /** A roll plus a count, so a caller can SAY what changed instead of only showing it. */
    data class Prune(val roll: CellRoll, val dropped: Int)

    /**
     * The ORDER BADGE for [cell]: the 1-based order of its LAST entry, or null when the cell is not
     * in the roll. *Why the last one:* a badge tap takes the last use back (Decision 2), so a badge
     * showing a first use's order would point at an entry the finger cannot reach.
     *
     * The rule is here and the badge is the view half's (Decision 13).
     */
    fun lastOrderOf(cell: Int): Int?

    /**
     * The ANIMATION board this roll IS, as a value: one frame per entry, in order, `holdFrames` = the
     * entry's hold, ids `"r0"`, `"r1"`…
     *
     * **A value, not a document.** Nothing here reads or writes `JbDocument`, and the board this
     * returns is not on any board list — it exists to be played. That is what makes the roll's being
     * session state a fact about the roll and not about the preview.
     *
     * This is the whole design: the sprite board's preview is the SAME reviewed clock the animation
     * board plays through, so a ping-pong on a sprite board has the same seam, the same sequence and
     * the same no-spin guarantee as a ping-pong on an animation board. See Decisions 9 and 10.
     */
    fun asBoard(fps: Float, rect: RectPx, name: String = "Roll"): Board

    /**
     * The CELL showing at roll index [index] — the cell, not the entry. `cellAtIndex(-1)` and an index
     * past the end are refused with an `IllegalArgumentException` rather than answered, because a
     * caller holding a bad index has a bug and a wrong cell is a wrong picture.
     */
    fun cellAtIndex(index: Int): Int

    /**
     * The roll as the TWO PARALLEL LISTS the sidecar is made of: `frames` and `weights`, the app's own
     * names and the app's own shape (see Decision 13). This is the entire hand-off to JB-4.03b, and it
     * is two lists because `SpritePacker.Clip` is a value this row deliberately does not name.
     */
    fun cellsAndHolds(): Pair<List<Int>, List<Int>>

    companion object {
        /** SpriteLab's own clamp on a hold, from `bumpHold` (line 2210). The only numbers here. */
        const val MIN_HOLD: Int = 1
        const val MAX_HOLD: Int = 16
    }
}
```

```kotlin
/** The grammar's verbs, as one sealed type, so a view cannot invent a fifth. */
sealed class CellGesture {
    /** A tap on a cell's body: `tapped`, hold 1, always. */
    data class Tapped(val cell: Int) : CellGesture()

    /** A tap on a cell's badge: take the LAST use back. */
    data class BadgeTapped(val cell: Int) : CellGesture()

    /** A long-press on a badge: take EVERY use back, and say how many. */
    data class BadgeHeld(val cell: Int) : CellGesture()

    /** Hold +1 / −1 on the entry under the cursor, clamped 1..16. */
    data class HoldBumped(val delta: Int) : CellGesture()

    /** The cursor moved to an entry. */
    data class Focused(val index: Int) : CellGesture()

    /** The app's "Play all" chip: every cell, hold 1, REPLACING the roll. */
    object PlayAll : CellGesture()

    /** The app's "Clear" chip. */
    object Clear : CellGesture()

    /** A second finger. The gesture is over and nothing is applied — Decision 14. */
    object Cancel : CellGesture()
}

/**
 * The tap/untap grammar, separated from the roll so it is testable on its own and so the view has
 * exactly one thing to call. Pure, total, no Android, no document, no clock.
 */
object CellRollGrammar {

    /** What [gesture] does to [roll], given a grid of [cellCount] cells. */
    fun apply(roll: CellRoll, gesture: CellGesture, cellCount: Int): CellRoll

    /** How many entries [gesture] would remove, or null when it removes none. 0 for a `BadgeTapped`
     *  with no entry to take, so a caller can always say something. */
    fun removedBy(roll: CellRoll, gesture: CellGesture, cellCount: Int): Int

    /** "Play all" and "Clear", which the grammar owns and the roll does not. */
    fun isBulk(gesture: CellGesture): Boolean

    /**
     * The wrap mode a chip means, mapped one for one onto `PlayMode` — `loop → LOOP`,
     * `pingpong → PING_PONG`, `once → ONCE`. Takes the app's own word and refuses an unknown one in
     * words, because an unknown word stored verbatim is a preset that plays as a loop.
     */
    fun modeOf(appWord: String): PlayMode
}
```

## Decisions

1. **A tap appends `{cell, 1}` and moves the cursor to it — always hold 1, never "the cell's current
   hold".** *Why:* `SpriteSheetEditorActivity.java:2122` is `labSeq.add(new int[]{currentCell(), 1})` with
   no reference to anything else, and a tap that inherited a hold would make the fourth tap of cell 3
   behave differently from the first three. Test 1.
2. **A badge tap removes the LAST use of that cell, scanning backwards.** A cell in the roll three
   times, badge-tapped, loses the third. *Why:* lines 4131-4133 walk down and `break` on the first
   match. It is also the only undo that makes sense: the thing you just added is the thing you take
   back. Test 2.
3. **A badge long-press removes EVERY use of that cell, and reports how many in words.** *Why:*
   SpriteLab's own toast and the reason in its own comment (lines 4118-4121): the rhythm of taps must
   not be broken by hunting for a chip on a strip that has scrolled. `Prune.dropped` is what lets the
   caller say it. Test 3.
4. **A hold belongs to an ENTRY, never to a cell.** `Hold ±1` on the entry under the cursor, clamped
   1..16. *Why:* `bumpHold` edits `labSeq.get(labCur)[1]` (line 2209-2210), and the app prunes
   `frames` and `weights` in the same pass (`pruneDeadFrames`, line 4222) — two parallel arrays, one
   entry each. A per-cell hold cannot express ping-pong, which is a per-entry property. Test 4.
5. **A tap on a cell that is already in the roll appends a SECOND entry. Duplicates are legal and are
   how ping-pong is written.** *Why:* `SpritePacker.Clip`'s own KDoc says it — *"a repeat is a repeat,
   not a mistake, which is how a ping-pong animation is written (`0,1,2,1`)"* — and the app's
   `unAddCell` walks backwards precisely because there can be several. Test 5.
6. **A tap on a cell outside the grid is refused — the grammar returns the roll unchanged** and the
   view says nothing rather than adding cell −1. *Why:* `SpriteGridMath.cellAt` answers −1 out there and
   `SpritePacker` refuses a clip that names a cell the sheet does not hold; a roll naming a cell that
   does not exist is the "a name on nothing" failure. Test 6.
7. **"Play all" is `{every cell, hold 1}` in reading order and it REPLACES the roll.** *Why:* the app's
   own `playAll` calls `labSeq.clear()` (line 2134) before adding (line 2138). The alternative —
   appending — turns "play all" into "play all, again". Test 7.
8. **"Clear" empties the roll and the cursor goes to 0**, and the grid's selection becomes the current
   cell. *Why:* the app's `Clear` chip, line 2191, and the `focusCell(Math.max(0,
   gridView.getSelectedCell()), true)` on line 2197 that goes with it. Test 8.
9. **The preview is `PlaybackClock` over `roll.asBoard(...)` — a transient ANIMATION board — driven by
   `FrameStepper`.** The roll's entry index IS the clock's frame index, and `cellAtIndex` maps it back.
   *Why, and this is the important one:* `PlaybackClock` **refuses a board that is not `ANIMATION`**
   (`AnimOps.playableSchedule` via `AnimOps.frameStartsMs`, the same rule `DocOps.validate` rule 4
   uses), so a SPRITE board cannot be played. But the roll *is* a schedule — an ordered list of
   `(cell, hold)` pairs with per-entry holds — and that is precisely `List<Frame>`. So the roll becomes
   a value and the board plays it through the one reviewed clock in the project. **The consequence is
   the whole point:** the sprite board's ping-pong gets the same seam convention, the same
   `A B C D C B` sequence and the same no-spin guarantee as the animation board's, for free, because
   it *is* the same code. Test 10 is what proves it is the same code.
10. **The backward-boundary warning applies here verbatim, and it is why the preview goes through
    `FrameStepper` and not through a `nextChangeMs` loop.** At a backward boundary of a PING_PONG leg,
    `nextChangeMs` is an **infimum**, not a minimum — `PlaybackClock.nextChangeMs`'s own KDoc says so
    at length, and `FrameStepper`'s KDoc says the parameter that fixes it is the frame already on
    screen. A preview that sleeps until `nextChangeMs` and redraws unconditionally **spins forever**,
    once per turn, burning battery and showing a frozen cell. `FrameStepper.step(elapsed, showing,
    audioAt)` takes the frame that is on screen and emits `ShowFrame` only for a different one, so the
    comparison cannot be forgotten. **Test 9 is the one that would catch it, and the builder must run
    the non-vacuity proof below.**
11. **The three wrap modes are the app's three words, mapped onto `PlayMode` one for one:** `loop →
    LOOP`, `pingpong → PING_PONG`, `once → ONCE`, in that order, wrapping. An unknown word is refused
    in words. *Why:* R23. The sidecar's `type` is a closed vocabulary the app reads
    (`SpritePacker.CLIP_TYPES`), and two vocababularies for three values is how an export stops opening
    in SpriteLab. Test 11.
12. **The preview's fps is the board's own fps, read from the document, default 12** — the same number
    an animation board plays at, and it is passed to `asBoard` rather than held here, so there is no
    second cadence setting anywhere. *Why:* `SpriteSheet.Preset.fps` is the sheet's fps and Joy Brush's
    `Board.fps` is the board's, and one board one cadence is a promise the model can keep. Test 12.
13. **The roll's hold spelling is `(cell, hold)` per entry, and the hand-off to the exporter is
    `cellsAndHolds()` — TWO PARALLEL LISTS. `Clip` does not appear in this file.** *Why, and this is the
    fix R36 asked for:* the old Q2 was right that a held frame was being lost, and JB-4.03c has since
    landed the fix in the landed source: `Clip` gained `weights: List<Int> = emptyList()`
    (`SpritePacker.kt:40`) and `SpritePacker.pack` writes `weights` under the app's own
    `hasWeights()` rule, straight after `frames` (`SpritePacker.kt:285-289`). **So a hold is a `weights`
    entry parallel to a `frames` entry, and a roll must never be spelled as repeated cell indices** —
    a hold of 3 is `frames = [0, 1]`, `weights = [3, 1]`, not `frames = [0, 1, 1, 1]`. Both spell the
    same animation and only one of them is a hold a person can still change. This row names neither
    type: it produces two lists and lets JB-4.03b make the `Clip`. **The fewer cross-row couplings this
    row has, the fewer ways it can block on another, and a name is a coupling.** Tests 14, 15.
14. **A second finger cancels the roll gesture**, and a drag across the grid is never a roll gesture.
    *Why:* JB-3.03 Decision 9 and JB-4.01 Cut Decision C4, the same house rule for the same reason: a
    second finger is a palm or a pinch, and neither means "change my roll".
15. **The roll is SESSION STATE, for now, and this spec says so rather than leaving it open (R36 Q1).**
    It is not in the document, not in `SpriteGrid`, not in `DocJson`, and not in any export's plan
    layer. *Why:* `SpriteGrid` is four `Int`s in a reviewed contract and adding a list of
    `(cell, hold)` to it is a document-format change (R3) in JB-0.02's owner area — the Lead's, one at
    a time. SpriteLab behaves the same way (`labSeq` is an Activity field, not a sheet field), so it is
    faithful to "reuse, don't rebuild". **The cost is real and is Q2, not a footnote: a person who
    spends ten minutes building a walk cycle's order and closes the drawing loses it.** The roll is
    reachable from nothing, so this row cannot drift into saving it by accident; J1 pins that.
16. **A long-press on a cell BODY opens the per-cell menu** (hold +1, hold −1, remove all) and a
    long-press never also fires a tap. *Why:* the same "long-press is the options gesture" rule as
    JB-3.02 and JB-4.01, and a double-fire puts a cell in the roll twice by accident. The MENU is the
    view half's; what the menu *does* is `CellGesture` and is here.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (tap appends hold 1) | 1 |
| 2 (badge takes the LAST) | 2 |
| 3 (long-press takes all, and counts) | 3 |
| 4 (hold per entry, 1..16) | 4 |
| 5 (duplicates are legal) | 5 |
| 6 (outside the grid refused) | 6 |
| 7 (play all replaces) | 7 |
| 8 (clear, cursor 0) | 8 |
| 9 (preview = the reviewed clock) | 10 |
| 10 (no spin) | 9 |
| 11 (three words, one for one) | 11 |
| 12 (fps is the board's) | 12 |
| 13 (`cellsAndHolds`, never repeated indices) | 14, 15 |
| 14 (second finger cancels) | 16 |
| 15 (session-only, unreachable from the document) | **J1**, **J2** |
| 16 (long-press is the menu, no double-fire) | 16 |

## Tests

### `commonTest` — `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollTest.kt`

**Fixture.** A grid of **4 × 2 = 8 cells** and rolls built the way a person builds them, one tap at a
time. The grid's cell COUNT is the only thing this row needs to know about a grid, and J2 is what says
so mechanically.

1. `aTapAlwaysAppendsHoldOneEvenAfterTheCellHasBeenHeld` — tap cell 0, bump its hold to 5, tap cell 0
   again → the roll is `[(0,5), (0,1)]` and the cursor is on index 1. The second tap is **one tick**,
   not five. This is Decision 1's whole content and the bug it prevents is a subtle one.
2. `aBadgeTapTakesTheLastUseNotTheFirst` — roll `[(3,1),(1,1),(3,1)]`, badge-tap cell 3 →
   `[(3,1),(1,1)]`, and `removedBy` is 1. A first-match removal would give `[(1,1),(3,1)]`, which is a
   different animation; the test names the difference in its comment.
3. `aBadgeHoldRemovesEveryUseAndReportsHowMany` — roll `[(3,1),(1,1),(3,1),(3,1)]`, badge-hold 3 →
   `[(1,1)]` and `Prune.dropped == 3`. The count is returned, not just the roll, so the caller can say
   "removed every use of cell 3" (the app's own toast, line 4139+).
4. `aHoldBelongsToTheEntryAndNotToTheCell` / `aHoldStopsAtSixteenAndAtOne` — roll `[(3,1),(3,1)]`,
   cursor 0: bump +1 → `[(3,2),(3,1)]`; bump +99 → **16**; bump −99 → **1**. The two entries stay
   different, which is the whole point of a per-entry hold, and the two ends are the app's own
   `Math.max(1, Math.min(16, …))` at line 2210.
5. `theSameCellCanBeInTheRollThreeTimes` / `aSequenceOfTapsIsExactlyTheTapsInOrder` — taps 0, 1, 2, 1
   → `[(0,1),(1,1),(2,1),(1,1)]`, order preserved, cursor on the last.
6. `aTapOutsideTheGridChangesNothing` — `cellCount = 8`: tap 8, tap −1, tap 99 → the roll is `==` to
   the one before, three times over, and `removedBy` for a badge tap on a cell not in the roll is **0**.
7. `playAllReplacesTheRollAndNotAppendsToIt` — roll `[(7,3)]`, then `PlayAll` with `cellCount = 8` →
   `[(0,1),(1,1),(2,1),(3,1),(4,1),(5,1),(6,1),(7,1)]` — eight entries, the old one **gone**, in reading
   order and every hold 1. And from an **empty** roll, `PlayAll` is the same eight entries. *Why:* the
   app clears first (line 2134), and the old spec's version of this test filtered to "used" cells,
   which is a decision this row does not have — see Decision 7 and Q3.
8. `clearingEmptiesTheRollAndResetsTheCursor` — from a five-entry roll with the cursor at 4:
   `Clear` → `isEmpty`, `cursor == 0`, and `isEmpty` is what a second `Clear` returns unchanged.
9. **`thePreviewDoesNotSpinAtABackwardBoundary`** — the port of `FrameStepperTest`'s
   `theStepperDoesNotSpinAtABackwardBoundary` (line 222) onto cells, with **every number derived**:
   - Roll `[(0,3),(1,3),(2,3),(3,3),(4,3),(5,3)]` at **12 fps** — holds of 3 so every boundary is
     `3 × 1000 / 12` = **250.0 exactly**, and no millisecond in this test depends on a rounding.
   - `asBoard(12f, …)` and a real `PlaybackClock(mode = PING_PONG)`: `rangeMs` = **1500.0** (six
     frames × 250) and the cycle is `rangeMs + (s[5] − s[1])` = 1500 + (1250 − 250) = **2500.0**.
     Both asserted against the clock's own numbers, not against literals alone.
   - Walk `t = 0, 1, 2, … 2500`, carrying `shown` forward exactly as a player would, and assert at
     every step that `clock.frameIndexAt(t) == shown`. Emit too little and the test goes red HERE.
   - The emission list is exactly **`[1, 2, 3, 4, 5, 4, 3, 2, 1, 0]`** — ten of them, which is
     **`2n − 2` for n = 6** — at **`250, 500, 750, 1000, 1250, 1501, 1751, 2001, 2251, 2500`**. The
     derivation, in the test's comment: a RISING edge draws AT it (a frame start is when the incoming
     frame is already up), a FALLING edge draws **one step after** it (the boundary is the infimum), and
     the wrap draws AT the cycle. The four falling edges are `1500 + (1250 − s[k])` for k = 5, 4, 3, 2,
     i.e. `1500, 1750, 2000, 2250`.
   - The visible sequence, sampled at each slot's **midpoint** — the only place in a slot where no
     rounding can be argued about — is `0 1 2 3 4 5 4 3 2 1` at `125, 375, 625, 875, 1125, 1375, 1625,
     1875, 2125, 2375`, with **no frame shown twice in a row**.
   - `nextWakeMs(t) > t` **strictly at every one of the 2 501 steps**, including the 1 ms before each
     falling edge and the edge itself. This is the property that makes the spin impossible, and it is
     `FrameStepper.nextWakeMs`'s own pinned contract.
   - **This is the test that would catch the spin, and the builder must run the non-vacuity proof.**
10. `theRollIsPlayableByTheReviewedClock` — `asBoard(12f, RectPx(0, 0, 256, 256))` and a real
    `PlaybackClock` over it: `rangeMs` is the sum of the holds at 12 fps, `frameIndexAt` walks the roll,
    and `roll.cellAtIndex(clock.frameIndexAt(t))` is the entry's cell at every sample. Asserted against
    `PlaybackClock`, not a re-implementation. Plus: `asBoard` returns `kind = ANIMATION`, its `frames`
    are `r0, r1, …` with `holdFrames` equal to each entry's hold, and **the document the roll came from
    is untouched** — `asBoard` takes no document at all, which J2 pins.
11. `theThreeWrapModesMapOneForOneOntoTheAppsOwnWords` — `modeOf("loop") = LOOP`, `("pingpong") =
    PING_PONG`, `("once") = ONCE`, and `modeOf("bounce")` **throws with a message naming the three**.
    Then the playback, on the same six-entry roll at 12 fps, sampled at slot midpoints: `LOOP` shows
    `0 1 2 3 4 5 0 …`; `PING_PONG` shows `0 1 2 3 4 5 4 3 2 1 0 …` with no frame twice in a row;
    `ONCE` holds 5 and `isFinished` flips at `rangeMs` = 1500 and **stays** flipped (it is level
    triggered, `FrameStepper.Step.Finish`'s own KDoc).
12. `thePreviewPlaysAtTheBoardsOwnFpsAndNotAtItsOwn` — three entries at holds 1, 1, 1: at **24 fps** a
    frame is **41.667 ms** (`1000/24`) and `rangeMs` is 125.0; at **12 fps** a frame is 83.333 ms and
    `rangeMs` is **250.0**. Both asserted, because a sprite preview that quietly ran at a different
    speed from the animation board is exactly the "preview ≠ export" bug this project guards against in
    six other places. Assert `rangeMs` (a `Double` compared with a tolerance of 1e-9, named in the
    comment) and never a per-frame duration written out by hand.
13. `aCellInTheRollThreeTimesShowsItsLastOrdersBadge` — `[(3,1),(1,1),(3,1)]`: `lastOrderOf(3) == 3`,
    `lastOrderOf(1) == 2`, `lastOrderOf(0) == null`, and on a cleared roll every cell is `null`.
14. `theHoldsAreAlwaysInsideTheRangeTheAppReads` — for every roll the suite builds, and for 200
    pseudo-random bumps, **every hold is in 1..16** and `cellsAndHolds()` returns two lists **of the
    same length**. The comment names where the outer range comes from: `SequenceTiming.MIN_WEIGHT` /
    `MAX_WEIGHT` = 1..9999, read by `SpriteSheet.java:651` and enforced by `SpritePacker.pack`
    (`SpritePacker.kt:220-232`), which **refuses** a weight it would have to change on read. *Why this
    test matters:* a roll can therefore never hand the exporter a weight the app would silently alter —
    the failure mode `SequenceTiming.fit`'s KDoc calls out, and the one R36's whole ruling was about.
15. `cellsAndHoldsIsTwoParallelListsAndNeverRepeatedIndices` — roll `[(0,3),(1,1)]`:
    `cellsAndHolds()` is `([0, 1], [3, 1])` — **two entries, not four**, and emphatically **not**
    `([0, 1, 1, 1], [])`. Both spell the same animation; only one of them is a hold a person can still
    change, and the old Q2 was the story of what happens when the second one is written. Assert the
    pair, assert the lengths are equal, and assert that `frames.size == entries.size` for every roll in
    the suite.
16. `aSecondFingerEndsARollGestureWithNothingApplied` / `aLongPressFiresNoTap` — `apply(roll, Cancel,
    8)` returns the roll `==` to what it was given; `isBulk(Cancel)` is false; `isBulk(PlayAll)` and
    `isBulk(Clear)` are true; and `apply(roll, Tapped(c), 8)` followed by `apply(…, CellGesture.BadgeHeld,
    8)` on the same cell removes **every** use, so a double-fire cannot put a cell in twice by accident.
    `CellLongPressed` is not a `CellGesture` case at all — the view half's menu, per Decision 16 — and
    **J2** is the test that says so, by reflection.

### `jvmTest` — `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollShapeTest.kt`

**These are here because they use Java reflection** (`::class.java`), which does not exist in a
multiplatform `commonTest` source set. This is the Lead's slip 3: a `commonTest` that opens a file, or
that calls `::class.java`, does not build. JB-1.07, JB-2.04, JB-2.12, JB-2.16, JB-2.17, JB-2.23 and
JB-3.04's specs all had one.

**J1.** `theRollIsNotInTheDocument` — `CellRoll` appears in the signature of **no** type in
`cc.joycreator.joybrush.core.doc` (walk `JbDocument`, `Board`, `Layer`, `Cel`, `Frame`, `SpriteGrid`,
`Paper`, `RectPx` and their declared fields), and `DocJson.encode(doc)` of a document that had a roll
played against it is **byte-identical** to the same document before, and contains no `roll`, `order` or
`weights` key. *Why both halves:* the field walk says the model cannot hold it, and the encode says the
model does not accidentally say it. Decision 15.

**J2.** `theRollHasNoIdeaWhatAGridIs` — `CellRoll`'s declared methods mention no `Board`, no
`SpriteGrid`, no `Layer`, no `Cel` and no `Int`-returning "cell count" source other than the `cellCount`
parameters it is handed; `CellRollGrammar` likewise. And `Clip` appears in **neither** file's imports
(Decision 13). `CellGesture::class.java.declaredClasses` is exactly `[Tapped, BadgeTapped, BadgeHeld,
HoldBumped, Focused, PlayAll, Clear, Cancel]` — **eight, and no `CellLongPressed`**, because a
long-press opens the view half's menu and must not also fire a tap (Decision 16, C3 in JB-4.01).
*Why:* this is the cross-row coupling guard — a roll that reached for `SpriteBoard` or for `Clip` would
make JB-4.02 depend on a row it does not need, which is how a row blocks on another.

**Non-vacuity the builder must run and paste:** (1) change `untapped` to remove the FIRST match and
watch **test 2** fail; (2) change `asBoard` to put `holdFrames = 1` on every frame and watch **tests 9,
11 and 12** fail; (3) replace `FrameStepper.step(elapsed, shown, audio)` with a version that emits
`ShowFrame` unconditionally and **watch test 9 fail** — and record how long it takes to fail, which is
the length of the spin; (4) make `cellsAndHolds()` return the frames with each hold repeated (the old,
wrong spelling) and watch **test 15** fail; (5) give `CellRoll` a `grid: SpriteGrid?` field, or add a
`CellLongPressed` case to `CellGesture`, and watch **J2** fail. A helper that has never been seen wrong
is a helper nobody can rely on.

**Command:** `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures.

## Do not

- **Do not sleep on `nextChangeMs`.** Decision 10 and test 9. The preview is a `FrameStepper`, and its
  `nextWakeMs` is a floor for a handler's delay, never a schedule.
- Do not write a second player, a second ping-pong, or a second "advance one frame" function. The roll
  is a `List<Frame>` and `PlaybackClock` plays it. `advanceRoll` (Java line 820) is the app's version of
  this and we do not rebuild it — we use the reviewed clock instead, which is the same reason and a
  better answer.
- Do not re-derive a hold in ms anywhere. `PlaybackClock` does it, from `AnimOps.frameStartsMs`.
- Do not give a **cell** a hold. Per entry, or ping-pong cannot be expressed (Decision 4).
- Do not remove the FIRST use of a cell on a badge tap.
- **Do not spell a hold as repeated cell indices.** `frames = [0, 1, 1, 1]` for a hold of 3 is the old
  and wrong spelling; it is `frames = [0, 1]`, `weights = [3, 1]` (Decision 13, and `cellsAndHolds()`
  is the only place this row produces either list). `AnimExportPlan.sheetClip` still does the old
  thing — see Q1; that is not this row's owner area and must not be copied from it.
- Do not name `Clip`, `SpritePacker`, `SpriteBoard` or `SpriteGrid` in this row. Two parallel lists are
  the whole hand-off (Decision 13, J2).
- Do not put the roll in the document, in `SpriteGrid`, or in any export plan — Decision 15 is a
  decision, not a licence to route around it.
- Do not add a `loop once` / `ping-pong twice` / a bounce count / a reverse. Three modes (Decision 11).
- Do not create `RollPreviewView.kt` and do not edit `SpriteGridView.kt`. They are the view half and the
  Lead's.
- No hex literals anywhere. `JbColors.palette(context)` only — and that is a view-half rule anyway.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the five non-vacuity runs pasted, **including the unconditional-`ShowFrame` run and how long
      test 9 took to fail**
- [ ] `git status --short` shows **only** the three owner-area paths
- [ ] committed `JB-4.02: cell roll core`; the ROADMAP row is the Lead's to set

## Stop rule

**Stop, set the row `⛔ Blocked`, and write the question in this file's Questions section — do not guess
and do not widen the owner area — if any of these happen:**

1. A signature in the "Contract" section does not match the landed file it is pasted from
   (`PlaybackClock.kt`, `FrameStepper.kt`, `DocModel.kt`, `AnimOps.kt`). The contract is authoritative
   over this spec.
2. Test 9's derived numbers do not come out of the real `PlaybackClock` — specifically if `rangeMs` is
   not 1500.0, or the cycle is not 2500.0, or the emission list is not ten long. That means the roll's
   `asBoard` is not building the board the tests describe, and that is a bug in the roll, not a number
   to adjust. Derive it again from the clock, do not edit the expectation.
3. A test's expected value cannot be derived from the numbers in this spec or from the pasted contract.
4. Making something pass would need an edit to `export/SpritePacker.kt` (Reviewed), `export/
   AnimExportPlan.kt`, `doc/`, or anything outside the three owner-area paths.
5. Anything here turns out to need `SpriteBoard`, `Clip`, a `View`, a file, or a document write. Then
   the scope is wrong, and the answer is a question — see the Do-not list and Q3.

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The roll, the grammar, the playback and
the timing are decided and pinned by 16 `commonTest` cases and 2 `jvmTest` ones, plus five non-vacuity
runs. **The old Q2 is closed: the finding was real and the fix has landed.** No question below blocks the
build.)_

### Q1 — for the Lead, MINOR: `AnimExportPlan.sheetClip` still spells a hold as repeated indices, and its KDoc is now false.

Not this row's owner area, and **not a timing bug** — the repeats play at the right speed, so I am not
claiming a silent timing loss here. It is two smaller things, in a Built file:

- `AnimExportPlan.kt:151-154` says *"`Clip` has no `weights` field today, and this row must not start
  writing one: JB-4.03c may add it later, and when it does this row keeps using repeats."* **JB-4.03c
  has landed and `Clip` has `weights`** (`SpritePacker.kt:40`). The KDoc is describing a world that no
  longer exists, and the file is someone's instruction.
- `sheetClip` (line 156) still builds `frames = cellIndices(plan)` with each hold folded in as a repeat
  (line 258-264), and there is now a second, better spelling in the same codebase. Two spellings of a
  hold is a drift risk, and the repeats also make a 4-frame board with holds `[1,2,1,3]` claim **7** cells
  where 4 would do.

**Recommend a small row (or an edit inside JB-3.06b's owner area) to make `sheetClip` write
`weights = plan.frameIds.indices.map { ticksOf(plan, it) }` and drop the repeats**, with the empty-weights
rule doing the rest — `SpritePacker` already omits an all-1s array. I have not touched it: it is a Built
file, it is not this row's area, and the fix is a re-review.

### Q2 — for the Lead, non-blocking, and R36 Q1 restated as a cost: the roll is session-only and it will generate a complaint.

R36 ruled (a): session-only for now. This spec builds that. The consequence, said plainly rather than
buried:

> A person spends ten minutes building a walk cycle's order out of forty cells, closes the drawing, and
> comes back to a bare grid.

SpriteLab has the same behaviour (`labSeq` is an Activity field, line 244), so this is faithful to "reuse,
don't rebuild" — but SpriteLab's **"Save clip"** (line 2216) writes the result into a
`SpriteSheet.Preset`, and **Joy Brush's analogue of that is the export** (JB-4.03b), which is a file
*out*, not a save in. So the order a person spent ten minutes on has exactly one door out of the app and
it is a file on the phone.

The alternatives, none of which this spec may take: (b) add `order: List<Int>` and `holds: List<Int>` to
`Board` for SPRITE only — a document-format change (R3), a `DOC_VERSION` bump per R30/R31, a `DocJson`
round-trip test and a default for every existing sprite board, in **JB-0.02's owner area**, and it is the
Lead's; (c) derive the order from the cells' own content at save time — **ruled out**, because it makes
the order a function of the picture, so re-drawing one cell silently reorders the animation, which is a
wrong answer rather than a missing one. **Nothing here waits on it; the roll is unreachable from the
document, so no later change can disturb this row.**

### Q3 — for the Lead, and it is a decision this row made that the Lead should see: "Play all" plays EVERY cell, not only the used ones.

The app's `playAll` (line 2131-2146) builds its list from the cells it considers worth playing — its
toast says "Playing all N frames", which implies N is the used count. The old spec's test 7 asserted the
**used** set (`[(2,1),(5,1)]`). I have ruled the opposite: `playAll` takes a `cellCount` and produces
**every** cell, hold 1, and does not know what "used" is.

*Why:* "used" is DERIVED (R36 Q1, JB-4.01 Decision 8) and deriving it needs the cells rendered or packed
— a cost `:core` cannot pay on every tap of a chip. A "Play all" that is sometimes 40 frames and
sometimes 3, depending on whether something has rendered, is a worse answer than one that is always the
whole sheet: on a fresh sheet, playing 40 cells of which 3 have something in it shows 37 blanks, which is
honest and obvious, rather than 3 frames that mysteriously appear once you touch something. **It is
PROVISIONAL and reversible, and it is one function.** If the Lead prefers the app's reading, `playAll`
gains a `used: Set<Int>` parameter and nothing else in this file changes.
