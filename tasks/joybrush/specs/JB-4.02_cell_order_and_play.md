# JB-4.02 — Tap cells in order, and play the preview (SpriteLab's chip mechanic, reused)

| | |
|---|---|
| **Tier** | T2-V (the roll is pure `:core` maths and is fully tested; the chips and the preview drawing are the vision half) |
| **Status** | 🟦 **Ready, with one question that does not block the build.** The roll, the tap grammar and the playback are all decided below and pinned by tests. **Q1 is flagged non-blocking** and the code above does not depend on the answer. **Q2 is a real gap in a Built contract, not in this spec** — `SpritePacker` (JB-4.03a) cannot write a held frame, so a roll with a hold loses its timing on export. That is a finding about JB-4.03a, and it is recorded here because JB-4.02 is the row that creates the thing JB-4.03a cannot express. |
| **Needs** | 4.01 (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/CellRoll.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/sprite/RollPreviewView.kt` · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/sprite/SpriteGridView.kt` (the order badges only — no other change) |
| **Estimated size** | ~180 lines of Kotlin in `:core` + ~280 lines of tests, ~240 lines of view |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` green. |

## Goal

Blueprint §1 idea 9, verbatim: **"tap cells in order, play preview"**, with the change:

> *"The tap-cells-then-play mechanic is SpriteLab's chip system — **reuse it, don't rebuild it**."*

And R23 is the owner's rule that makes "reuse" a hard word: *"When Joy Brush needs something the
Studio has, it moves into `:studiokit` … improvements land once."* So the **grammar** here is
SpriteLab's, copied faithfully and deliberately — the tap/untap/hold triple, the badges, the roll —
and the **timing** is Joy Brush's, because Joy Brush already has the one reviewed playback clock in
the project and a second player would be the exact duplication R23 exists to stop.

## What SpriteLab's mechanic actually is, read from the shipped code

`app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java`, which is the
authority (R23). These are its rules, transcribed, and this spec implements them rather than
re-inventing them:

- The roll is a `List<int[]>` of `{cellIndex, hold}`, in tap order. `labSeq`, line 244.
- **A tap on a cell appends it.** Line 4304: `labSeq.add(new int[]{index, 1})`. Always hold 1 — a
  tap is one tick.
- **A tap on the cell's BADGE takes the last one back.** Line 4123-4134, `unAddCell(cell, all=false)`:
  it walks **backwards** and removes the first match, so it removes the LAST use, not the first.
- **A long-press on the badge clears every use of that cell.** `unAddCell(cell, all=true)`, and the
  Studio's own comment says why: *"building a roll is a rhythm of taps on the sheet, and having to
  break off and hunt down the wrong chip on a strip that has scrolled somewhere else breaks it."*
- **A cell may appear many times.** A repeat is how ping-pong is written.
- **A hold is per ENTRY, not per cell.** `bumpHold` edits `labSeq.get(labCur)[1]`, clamped 1..16.
  So tapping cell 3 three times gives three entries of hold 1, and holding one of them gives that one
  entry a hold — the cell is not globally "held for 3".
- **`advanceRoll` steps by one FRAME, not one entry**: a hold of ×4 stays put for four ticks, and
  ping-pong bounces without repeating the ends (`labCur = size - 2; labDir = -1`).
- **The wrap mode is `loop` / `pingpong` / `once`** — the app's own three words, in
  `SpriteSheet.Preset.type`.

Everything in Decisions 1–8 is one of those, in Joy Brush's naming.

## Contract

```kotlin
package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.doc.Board

/**
 * The roll: the cells a person tapped, IN ORDER, each with its own hold. This is SpriteLab's
 * `labSeq` and it is the same shape on purpose.
 *
 * IMMUTABLE, like every other value in this project. Every operation returns a new roll.
 */
data class CellRoll(val entries: List<Entry> = emptyList(), val cursor: Int = 0) {

    data class Entry(val cell: Int, val hold: Int = 1)

    val isEmpty: Boolean get() = entries.isEmpty()
    val size: Int get() = entries.size

    /** Tap a cell: append `{cell, 1}` and put the cursor on it. SpriteLab line 4304, exactly. */
    fun tapped(cell: Int, cellCount: Int): CellRoll

    /** Tap a badge: remove the LAST use of [cell]. Returns this roll unchanged if there is none. */
    fun untapped(cell: Int): CellRoll

    /** Long-press a badge: remove EVERY use of [cell]. */
    fun untappedAll(cell: Int): CellRoll

    /** Hold +1 / −1 on the entry under the cursor, clamped 1..16 — SpriteLab's own range. */
    fun holdBumped(delta: Int): CellRoll

    /** Move the cursor to an entry, clamped into the roll. */
    fun focused(index: Int): CellRoll

    fun cleared(): CellRoll

    /**
     * Drop every entry naming a cell this grid no longer has — index >= [cellCount].
     * Returns the roll AND how many entries went.
     */
    fun prunedTo(cellCount: Int): Prune

    data class Prune(val roll: CellRoll, val dropped: Int)

    /**
     * The ANIMATION board this roll IS, as a value: one frame per entry, in order, `holdFrames`
     * = the entry's hold, ids `"r0"`, `"r1"`…
     *
     * This is the whole design: the sprite board's preview is the SAME reviewed clock the
     * animation board plays through, so a ping-pong on a sprite board has the same seam, the same
     * sequence and the same no-spin guarantee as a ping-pong on an animation board. See Decision 9.
     */
    fun asBoard(fps: Float, rect: RectPx, name: String = "Roll"): Board

    /** The cell showing at [elapsedMs] under [clock] — the clock's answer, looked up in the roll. */
    fun cellAtIndex(index: Int): Int

    companion object {
        /** SpriteLab's own clamp on a hold, from `bumpHold`: `Math.max(1, Math.min(16, f[1] + delta))`. */
        const val MIN_HOLD: Int = 1
        const val MAX_HOLD: Int = 16
    }
}

/** The grammar's three verbs, as one sealed type, so a view cannot invent a fourth. */
sealed class CellGesture {
    data class Tapped(val cell: Int) : CellGesture()
    data class BadgeTapped(val cell: Int) : CellGesture()
    data class BadgeHeld(val cell: Int) : CellGesture()
    data class HoldBumped(val delta: Int) : CellGesture()
    data class Focused(val index: Int) : CellGesture()
    object PlayAll : CellGesture()
    object Clear : CellGesture()
    object Cancel : CellGesture()      // a second finger
}

/** The tap/untap grammar, separated from the roll so it is testable on its own. */
object CellRollGrammar {
    /** What [gesture] does to [roll], given a grid of [cellCount] cells. Pure, total, no Android. */
    fun apply(roll: CellRoll, gesture: CellGesture, cellCount: Int): CellRoll
    /** "Play all" and "Clear", which the grammar owns and the roll does not. */
    fun isBulk(gesture: CellGesture): Boolean
}
```

## Decisions

1. **A tap appends `{cell, 1}` and moves the cursor to it — always hold 1, never "the cell's
   current hold".** *Why:* SpriteLab line 4304 is `new int[]{index, 1}` with no reference to
   anything else, and a tap that inherited a hold would make the fourth tap of cell 3 behave
   differently from the first three.
2. **A badge tap removes the LAST use of that cell, scanning backwards.** A cell in the roll three
   times, badge-tapped, loses the third. *Why:* SpriteLab `unAddCell(all=false)` walks
   `for (int i = labSeq.size() - 1; i >= 0; i--)`. It is also the only undo that makes sense: the
   thing you just added is the thing you take back.
3. **A badge long-press removes EVERY use of that cell, and says how many in words.** *Why:*
   SpriteLab's own toast — "Removed every use of cell 7" — and the reason is in its comment: the
   rhythm of taps must not be broken by hunting for a chip on a strip that has scrolled.
4. **A hold belongs to an ENTRY, never to a cell.** Hold +1 on the entry under the cursor, clamped
   1..16. *Why:* SpriteLab's clamp is literally `1..16` and its `weights` are parallel to `frames`
   — per entry. A per-cell hold cannot express ping-pong, which is a per-entry property.
5. **A tap on a cell that is already in the roll appends a SECOND entry. Duplicates are legal and
   are how ping-pong is written.** `SpritePacker`'s own KDoc says it: *"a repeat is a repeat, not a
   mistake, which is how a ping-pong animation is written (`0,1,2,1`)"*.
6. **A tap on a cell outside the grid is refused — the grammar returns the roll unchanged**, and
   the view says nothing rather than adding cell −1. *Why:* `cellAt` answers −1 out there
   (JB-4.01a Decision 3) and a roll naming a cell that does not exist is the "a name on nothing"
   failure `SpritePacker` already refuses.
7. **"Play all" is `{every used cell, hold 1}, in reading order`, and it REPLACES the roll.**
   SpriteLab's own `playAll` and `addAll` both `labSeq.clear()` first. *Why:* it is in the shipped
   code and the alternative — appending — turns "play all" into "play all, again".
8. **"Clear" empties the roll and the cursor goes to 0**, and the grid's selection becomes the
   current cell — SpriteLab's `Clear` chip, which calls `focusCell(Math.max(0, gridView
   .getSelectedCell()), true)` for exactly that reason.
9. **The preview is `PlaybackClock` and `FrameStepper` over `roll.asBoard(...)` — a transient
   ANIMATION board.** The roll's cell index is the clock's frame index. *Why, and this is the
   important one:* `PlaybackClock` **refuses a board that is not `ANIMATION`** (`AnimOps`
   `playableSchedule`, the same rule `DocOps.validate` rule 4 uses), so a SPRITE board cannot be
   played. But the roll *is* a schedule — an ordered list of `(cell, hold)` pairs with per-entry
   holds — and that is precisely `List<Frame>`. So the roll becomes a value, and the board plays it
   through the one reviewed clock in the project. **The consequence is the whole point:** the
   sprite board's ping-pong gets the same seam convention, the same `A B C D C B` sequence and the
   same no-spin guarantee as the animation board's, for free, because it *is* the same code.
   *This spec's Decisions 10 and 11 exist only to state what that inheritance obliges.*
10. **The backward-boundary warning applies here verbatim, and it is why the preview goes through
    `FrameStepper` and not through a `nextChangeMs` loop.** At a backward boundary of a PING_PONG
    leg, `nextChangeMs` is an **infimum**, not a minimum (JB-3.05a Decision 3 and its Questions
    §4). A preview that sleeps until `nextChangeMs` and redraws unconditionally **spins forever**,
    once per turn, burning battery and showing a frozen cell. `FrameStepper.step(elapsed, showing,
    audioAt)` takes the frame that is on screen and emits `ShowFrame` only for a different one, so
    the comparison cannot be forgotten. **Test 8 is the one that would catch it, and the builder
    must run the non-vacuity proof.**
11. **The three wrap modes are the app's three words, mapped onto `PlayMode` one for one:**
    `loop → LOOP`, `pingpong → PING_PONG`, `once → ONCE`, in that chip order, wrapping.
    *Why:* R23. The sidecar's `type` is a closed vocabulary the app reads
    (`SpritePacker.CLIP_TYPES`), and Joy Brush's `PlayMode` is the same three ideas. Two
    vocabularies for three values is how an export stops opening in SpriteLab.
12. **The preview's fps is the board's own `fps`, read from the document, default 12** — the same
    number an animation board plays at, so "the same loop at the same speed" is true across the
    two boards. It is NOT a second setting on the sprite board. *Why:* `SpriteSheet.Preset.fps` is
    the sheet's fps and Joy Brush's `Board.fps` is the board's, and one board one cadence is a
    promise the model can keep.
13. **Badges are drawn on the grid, cyan with a pink "now" state, top-centre** — SpriteLab's exact
    cell-badge treatment (`JOYBRUSH_VISUAL_LANGUAGE.md` §4.3: *"cyan order badge top-centre, pink
    when now"*), and the same tokens as JB-4.01 Decision 10. The badge is the ORDER (1-based), and a
    cell in the roll three times shows the order of its **last** entry, because that is the one a
    badge tap takes back. *Why:* a badge showing the first use's order would point at an entry the
    finger cannot reach.
14. **A second finger cancels the roll gesture**, and a drag on the grid is never a roll gesture.
    *Why:* JB-3.03 Decision 11 and JB-4.01 Decision 13, the same house rule for the same reason.
15. **A long-press on a cell BODY opens the per-cell menu** (hold +1, hold −1, remove all) rather
    than the badge, and a long-press never also fires a tap. *Why:* the same "long-press is the
    options gesture" rule as JB-3.02 Decision 2 and JB-4.01 Decision 12, and a double-fire puts a
    cell in the roll twice by accident.
16. **The roll is not in the document.** It is session state, like SpriteLab's `labSeq` is, and it
    is **not** saved, **not** part of `SpriteGrid` and **not** part of any export's plan layer.
    *Why:* it is a play ORDER, and the blueprint's export is a sheet. See Q1 — the honest tension
    here is that a person who builds a walk cycle, closes the drawing and comes back has lost it,
    and that is a real complaint waiting to happen.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (tap appends hold 1) | `aTapAlwaysAppendsHoldOneEvenAfterTheCellHasBeenHeld` |
| 2 (badge takes the LAST) | `aBadgeTapTakesTheLastUseNotTheFirst` |
| 3 (long-press takes all, and says) | `aBadgeHoldRemovesEveryUseAndReportsHowMany` |
| 4 (hold per entry, 1..16) | `aHoldBelongsToTheEntryAndNotToTheCell`, `aHoldStopsAtSixteenAndAtOne` |
| 5 (duplicates are legal) | `theSameCellCanBeInTheRollThreeTimes` |
| 6 (outside the grid refused) | `aTapOutsideTheGridChangesNothing` |
| 7 (play all replaces) | `playAllReplacesTheRollAndNotAppendsToIt` |
| 8 (clear, cursor 0) | `clearingEmptiesTheRollAndResetsTheCursor` |
| 9 (preview = the reviewed clock) | `theRollIsPlayableByTheReviewedClock` — a `PlaybackClock` over `asBoard` and no other timing code in the file |
| 10 (no spin) | `thePreviewDoesNotSpinAtABackwardBoundary` — the port of JB-3.05a test 3, on cells |
| 11 (three words, one for one) | `theThreeWrapModesMapOneForOneOntoTheAppsOwnWords` |
| 12 (fps is the board's) | `thePreviewPlaysAtTheBoardsOwnFpsAndNotAtItsOwn` |
| 13 (badge = last entry's order) | `aCellInTheRollThreeTimesShowsItsLastOrdersBadge` |
| 14 (second finger cancels) | `aSecondFingerEndsARollGestureWithNothingApplied` |
| 15 (long-press is the menu, no double-fire) | `aLongPressFiresNoTap` |
| 16 (roll is not saved) | `theRollIsNotInTheDocument` — reflection: `CellRoll` is not referenced by any `DocModel` type, and the roll round-trips through `DocJson.encode`/`decode` unchanged |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/sprite/CellRollTest.kt`

Fixture: a 4 × 2 grid (8 cells) and a roll built by the grammar from taps, exactly as a person
would. The grid's cell count is the only thing `:core` needs to know — **`CellRoll` has no idea
what a grid is**, which is asserted (test 6's reflection).

1. `aTapAlwaysAppendsHoldOneEvenAfterTheCellHasBeenHeld`: tap cell 0, bump its hold to 5, tap cell
   0 again → the roll is `[(0,5), (0,1)]`. The second tap is **one tick**, not five. This is
   Decision 1's whole content and the bug it prevents is a subtle one.
2. `aBadgeTapTakesTheLastUseNotTheFirst`: roll `[(3,1),(1,1),(3,1)]` → badge-tap cell 3 →
   `[(3,1),(1,1)]`. A first-match removal would give `[(1,1),(3,1)]`, which is a different
   animation; the test names the difference.
3. `aBadgeHoldRemovesEveryUseAndReportsHowMany`: roll `[(3,1),(1,1),(3,1),(3,1)]` → badge-hold 3 →
   `[(1,1)]` and `dropped == 3`. The count is returned, not just the roll.
4. `aHoldBelongsToTheEntryAndNotToTheCell` / `aHoldStopsAtSixteenAndAtOne`: cursor 0 of
   `[(3,1),(3,1)]`, bump +1 → `[(3,2),(3,1)]`; bump +99 → 16; bump −99 → 1. The two entries stay
   different, which is the whole point of a per-entry hold.
5. `theSameCellCanBeInTheRollThreeTimes` and `aSequenceOfTapsIsExactlyTheTapsInOrder`: taps
   0, 1, 2, 1 → `[(0,1),(1,1),(2,1),(1,1)]`, order preserved.
6. `aTapOutsideTheGridChangesNothing`: `cellCount = 8`, tap cell 8, tap cell −1, tap cell 99 →
   the roll is `==` to the one before, three times over. And `cellAt` is not consulted: the view
   does that, and `CellRoll` has no `Board` in its signature (reflection).
7. `playAllReplacesTheRollAndNotAppendsToIt`: roll `[(7,3)]`, then `PlayAll` on a 4 × 2 grid with
   cells 2 and 5 used → `[(2,1),(5,1)]`. The old entry is **gone**, and the used set is honoured.
8. `thePreviewDoesNotSpinAtABackwardBoundary` — the port of `FrameStepperTest` test 3, on cells.
   Roll `[(0,1),(1,1),(2,1),(3,1),(4,1),(5,1)]`, PING_PONG, 1 ms steps, carrying the shown cell
   forward as a player would. Assert: the walk terminates; `ShowFrame` fires at most `2n − 2` times
   per cycle; the emitted cells are exactly `0 1 2 3 4 5 4 3 2 1 0 …` with **no repeats**; and
   `nextWakeMs` is strictly greater than the current elapsed at every step where nothing changes.
   **This is the test that would catch the spin, and the builder must run the non-vacuity proof
   below.**
9. `theRollIsPlayableByTheReviewedClock`: `asBoard(12f, …)` then a real `PlaybackClock` — its
   `rangeMs` equals the sum of the holds at 12 fps, `frameIndexAt` walks the roll, and the roll's
   cell for each index is `entries[index].cell`. Asserted against `PlaybackClock`, not a
   re-implementation.
10. `theThreeWrapModesMapOneForOneOntoTheAppsOwnWords`: `LOOP` shows `0 1 2 3 4 5 0 …`;
    `PING_PONG` shows `0 1 2 3 4 5 4 3 2 1 0 …` with **no frame shown twice in a row**; `ONCE` stops
    on 5 and `isFinished` flips at `rangeMs`. Sampled at each slot's **midpoint**, the only place in
    a slot where no rounding can be argued about.
11. `thePreviewPlaysAtTheBoardsOwnFpsAndNotAtItsOwn`: a roll of three entries at holds 1,1,1 on a
    board at 24 fps plays at 125 ms a frame, and at 12 fps at 250 ms. Both asserted, because a
    sprite preview that quietly ran at a different speed from the animation board is exactly the
    "preview ≠ export" bug this project is guarding against in six other places.
12. `aCellInTheRollThreeTimesShowsItsLastOrdersBadge`: `[(3,1),(1,1),(3,1)]` → cell 3's badge is
    **3**, and cell 1's is 2.
13. `theRollIsNotInTheDocument`: `CellRoll` appears in no `DocModel` type's signature, and a
    `DocJson.encode` → `decode` round trip of a document leaves `CellRoll` nowhere in the result.
14. `aSecondFingerEndsARollGestureWithNothingApplied` / `aLongPressFiresNoTap` /
    `clearingEmptiesTheRollAndResetsTheCursor` / `aBadgeHoldRemovesEveryUseAndReportsHowMany`:
    structural assertions, no generation counters.
15. **`prunedTo`:** a roll naming cells 5, 6, 7 on a grid that shrank to 4 cells → `dropped == 3`
    and the roll is empty. SpriteLab's `pruneDeadFrames` does exactly this, and its comment is
    worth repeating: *"Take a 4×7 sheet down to 2×2 and the roll still held frames 4..27 — nothing
    crashed, because the renderer bounds-checks and draws nothing, which is the worst of both
    worlds: an animation with invisible frames in it and no explanation anywhere on screen."*

**Non-vacuity the builder must run and paste:** (1) change `untapped` to remove the FIRST match and
watch test 2 fail; (2) change `asBoard` to put `holdFrames = 1` on every frame and watch tests 9, 10
and 11 fail; (3) replace `FrameStepper.step(elapsed, showing, audio)` with a version that emits
`ShowFrame` unconditionally and **watch test 8 fail** — and record how long it takes to fail, which
is the length of the spin.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not sleep on `nextChangeMs`.** Decision 10 and test 8. The preview is a `FrameStepper`.
- Do not write a second player, a second ping-pong, or a second "advance one frame" function. The
  roll is a `List<Frame>` and the clock plays it.
- Do not re-derive a hold in ms anywhere. `PlaybackClock` does it, from `AnimOps`.
- Do not give a cell a hold. Per entry, or it cannot express ping-pong (Decision 4).
- Do not remove the FIRST use of a cell on a badge tap.
- Do not put the roll in the document, in `SpriteGrid`, or in any export plan — see Q1, which is a
  question, not a licence.
- Do not add a `loop once` / `ping-pong twice` / a bounce count / a reverse. Three modes.
- No hex literals — `JbColors.palette(context)` only.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted, **including the unconditional-`ShowFrame` run and how long test 8 took to fail**
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] **owner check, Note 9:** tap four cells in order, badge-tap one, long-press another, then Play
      — the preview must show exactly the four cells in that order, ping-pong must turn round at
      the ends without repeating either, and a two-finger tap must change nothing
- [ ] committed `JB-4.02: tap cells and play preview`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The mechanic is SpriteLab's,
transcribed from the shipped code and pinned. Two things need a ruling; **neither blocks the
build** — the code above does not depend on either.)_

### Q1 — for the Lead: the roll is not saved, and that will generate a complaint

Decision 16 keeps the roll as session state, because `SpriteGrid` is four `Int`s in a reviewed
contract and adding a list of `(cell, hold)` to it is a document-format change (R3) in JB-0.02's
owner area. The consequence is real and I am not going to bury it:

> A person spends ten minutes building a walk cycle's order out of forty cells, closes the drawing,
> and comes back to a bare grid.

SpriteLab has the same behaviour — `labSeq` is a field on the Activity, not on the sheet — so this
is faithful to "reuse, don't rebuild". But SpriteLab's "Save clip" writes the result into a
`SpriteSheet.Preset`, and **Joy Brush's analogue of that is the export** (JB-4.03b), which is a file
out, not a save in.

**Three options, and I have ruled (a) provisionally:**

- **(a) Session-only, and the export is where the order goes.** *Why:* it is faithful, it is what
  the mechanic being reused does, and it adds nothing to the document.
- **(b) Add `order: List<Int>` and `holds: List<Int>` to `Board` (SPRITE only)** — a document-format
  change, a `DOC_VERSION` question, a `DocJson` round-trip test, and a migration for every existing
  sprite board (which has none, so: a default). **This is JB-0.02's owner area and it is the
  Lead's.**
- **(c) Derive the order from the cells' own content at save time** — i.e. a save-time "if the
  animation board has frames, the sprite board's order is the frames' cels". I ruled this **out**:
  it makes the order a function of the picture, so re-drawing one cell silently reorders the
  animation, and that is a wrong answer rather than a missing one.

**My recommendation: (a) now, and open JB-4.03c for (b)** — because the complaint above is real and
it is a format change, and format changes are the Lead's, one at a time.

### Q2 — 🔴 for the Lead: a Built contract cannot express a held frame, and this row is what finds it

**`SpritePacker` (JB-4.03a, 🟩 Reviewed) writes `frames` and never writes `weights`.** I read both
sides of the contract:

- The app's `SpriteSheet.toJson()` (line 648) writes a `"weights"` array beside `"frames"` **when
  `p.hasWeights()`**, with the comment *"Written only when something is actually held: an all-1s
  array carries no information, and omitting it keeps a pre-weights preset byte-identically."*
  `SequenceTiming` reads them back, `MIN_WEIGHT = 1`, `MAX_WEIGHT = 9999`.
- `SpritePacker.pack` (line 239) writes `putJsonArray("frames") { … }` and **nothing else** on a
  preset. There is no `weights` key and no `Clip.weights` field.

**So a roll with a hold — `[(0,3),(1,1)]`, "cell 0 held for three ticks", which is Decision 4 and the
most ordinary thing a person will do — exports as a sidecar that says `frames: [0,1]` and loses the
hold entirely.** In SpriteLab the first cell will play for one tick instead of three, with nothing
to indicate that anything was lost. This is a silent timing loss in a file contract, which is the
worst shape of bug in this project.

**It is not a bug in JB-4.03a's code** — its spec's sidecar listing does not include `weights`, it
built exactly what it was told, and the review cleared it correctly against that spec. **The spec
and the app's contract are out of step, and the step was invisible until a row needed a hold.**

**What a ruling needs to cover:**

1. **Does `Clip` gain `weights: List<Int> = emptyList()`?** A default, so every existing call site
   keeps compiling, written **only when non-empty and not all 1** (the app's own
   `hasWeights()` rule, and omitting an all-1s array is what keeps a pre-weights sheet
   byte-identical).
2. **Who writes it** — `SpritePacker` (a NEW edit to a reviewed, cleared file, which is a
   cross-review matter) or the caller (which means the caller is doing the encoder's job, and the
   format is closed so the two can drift).
3. **Does the weight range match `SequenceTiming` (1..9999) or `CellRoll`'s (1..16)?** They cannot
   both be the contract. The app is the reader, so 1..9999 — and `CellRoll.MAX_HOLD = 16` is a UI
   clamp, not a format limit, exactly as `MAX_HOLD_FRAMES = 999` is a UI clamp on
   `AnimOps.setHold`.
4. **Or is the answer that Joy Brush's export never writes a hold**, and a person who wants a held
   frame taps the cell three times? That is a real answer and it is the one `SpritePacker`'s
   existing spec implies — but it makes the hold control in Decision 4 a lie, and I would rather
   not ship that without a ruling.

My recommendation: **1 and 2, with `SpritePacker` gaining `Clip.weights` and writing it under the
app's own `hasWeights()` rule**, and a named test that a hold round-trips through a real
`SpritePacker.pack` and comes back out of the JSON. That test is the sort JB-4.01a's test 8 already
is — calling the landed packer and reading the result — and it is what would have caught this.
