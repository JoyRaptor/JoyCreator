package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx

/**
 * The id the board a roll IS carries, so two rolls' boards can never be mistaken for one document's
 * boards — and are never ON a document's board list, which is what Decision 15 is.
 *
 * A `const` at file level rather than a member of [CellRoll.Companion], because it is not part of the
 * roll's contract: it is a label, and the companion is where the two numbers a person can change
 * live. It is `private`, so it is a field of the file's own class in the bytecode and not of
 * [CellRoll] — which is what `CellRollShapeTest` walks when it says the roll holds no document.
 */
private const val ROLL_BOARD_ID = "roll"

/**
 * The roll: the cells a person tapped, IN ORDER, each with its own hold. This is SpriteLab's
 * `labSeq` (`SpriteSheetEditorActivity.java:244`) and it is the same shape on purpose.
 *
 * R23 — "when Joy Brush needs something the Studio has, it moves into `:studiokit` … improvements
 * land once" — is what makes "reuse, don't rebuild it" a hard word, and this file is the reuse: the
 * grammar below is copied faithfully from the app's own code, line by line, while the TIMING is Joy
 * Brush's, because the project already has the one reviewed playback clock and a second player
 * would be the exact duplication R23 exists to stop.
 *
 * IMMUTABLE, like every other value in this project. Every operation returns a new roll and none of
 * them mutates, so the undo stack and a preview can both hold one and be sure of what they hold.
 *
 * **The roll is SESSION STATE and is not in the document** (Decision 15, R36 Q1). It is not a field on
 * a sprite board, it is not serialised, and it is not written into any export's plan layer. A person
 * who builds a walk cycle's order, closes the drawing and comes back finds a bare grid — see the
 * spec's Q2, which is the honest cost of that decision and the Lead's to revisit. It is unreachable
 * from the document, so no later change can disturb this row by accident.
 *
 * **This class knows nothing about a grid.** It is told a cell COUNT and refuses anything outside it.
 * That is not tidiness: it is what keeps this row off the sprite board type, and therefore off
 * JB-4.01, which owns the cell count and the badges.
 */
data class CellRoll(val entries: List<Entry> = emptyList(), val cursor: Int = 0) {

    /** One tap: which cell, and how many ticks it is held for. A hold belongs to an ENTRY. */
    data class Entry(val cell: Int, val hold: Int = 1)

    val isEmpty: Boolean get() = entries.isEmpty()
    val size: Int get() = entries.size

    /**
     * Tap a cell: append `{cell, 1}` and put the cursor on it. `SpriteSheetEditorActivity.java:2122`,
     * exactly, and always hold [MIN_HOLD] — a tap is one tick and never inherits an earlier entry's
     * hold. A cell outside `0 until cellCount` is refused and the roll is returned unchanged.
     *
     * A tap that inherited "the cell's current hold" is the bug Decision 1 rules out, and it is a
     * subtle one: the fourth tap of cell 3 would then behave differently from the first three, and
     * the only way to see it is to tap a cell four times.
     */
    fun tapped(cell: Int, cellCount: Int): CellRoll =
        if (cell in 0 until cellCount) {
            copy(entries = entries + Entry(cell = cell, hold = MIN_HOLD), cursor = entries.size)
        } else {
            this
        }

    /**
     * Tap a badge: remove the LAST use of [cell], scanning backwards — `unAddCell(all=false)`,
     * lines 4131-4133. Returns this roll unchanged if there is none. The cursor is clamped down to
     * the last surviving entry (Decision 17), whatever it was.
     *
     * **The LAST use, not the first.** The thing a person just added is the thing they can reach
     * with the finger they are already holding up, and a roll `[3, 1, 3]` taken from the front
     * becomes `[1, 3]` — a different animation, not an undo.
     */
    fun untapped(cell: Int): CellRoll {
        val at = entries.indexOfLast { it.cell == cell }
        if (at < 0) return this
        return clampedTo(entries.without(at))
    }

    /**
     * Long-press a badge: remove EVERY use of [cell] and report how many went, so the caller can say
     * "removed every use of cell 7" in words rather than silently changing the picture.
     *
     * The app's own sentence is `"Removed every use of cell N"` (lines 4146-4147), and the reason in
     * its own comment (4118-4121) is that building a roll is a rhythm of taps on the sheet, and
     * breaking off to hunt down the wrong chip on a strip that has scrolled somewhere else breaks
     * that rhythm. So the count is a return value, not a thing the caller has to count itself.
     */
    fun untappedAll(cell: Int): Prune {
        val kept = entries.filterNot { it.cell == cell }
        if (kept.size == entries.size) return Prune(this, 0)
        return Prune(clampedTo(kept), entries.size - kept.size)
    }

    /**
     * Hold +1 / −1 on the entry under the cursor, clamped to 1..16 — `bumpHold`, line 2210,
     * `Math.max(1, Math.min(16, f[1] + delta))`. Per ENTRY.
     *
     * A **per-CELL** hold cannot express a ping-pong, which is a per-entry property: `0 1 2 1` needs
     * the second `1` and the third `1` to be the same cell with different lengths, and a cell that
     * had one hold would have to be the same length in both places.
     */
    fun holdBumped(delta: Int): CellRoll {
        if (entries.isEmpty()) return this
        val at = cursor.coerceIn(0, entries.size - 1)
        val entry = entries[at]
        // Long, then narrowed (LEAD R19): a delta off a slider is small, and `Int + Int` that wraps
        // would clamp a bump of +2147483647 to 1 rather than to 16 — the wrong end, silently.
        val held = (entry.hold.toLong() + delta.toLong())
            .coerceIn(MIN_HOLD.toLong(), MAX_HOLD.toLong())
            .toInt()
        val bumped = entries.toMutableList()
        bumped[at] = entry.copy(hold = held)
        return copy(entries = bumped)
    }

    /** Move the cursor to an entry, clamped into the roll. */
    fun focused(index: Int): CellRoll = copy(cursor = index.coerceIn(0, maxOf(0, size - 1)))

    /**
     * The app's `Clear` chip (line 2191): empty, cursor 0, and the grid's selection stays the
     * "current cell" (`focusCell(Math.max(0, gridView.getSelectedCell()), true)`, line 2197).
     *
     * One of only two operations that puts the cursor at 0 rather than clamping it — the other is
     * `playAll`, and both are the app's `focusRoll(0, true)` on line 2141. Everything else that
     * removes clamps DOWN, because 0 after a removal in a long roll throws away a person's place.
     */
    fun cleared(): CellRoll = CellRoll()

    /**
     * "Play all" — every cell in `0 until cellCount`, hold 1, in reading order, REPLACING the roll.
     * The app's own `playAll` does `labSeq.clear()` first (line 2134) and then adds (line 2138).
     *
     * It plays EVERY cell and not only the used ones, which is a decision the spec records as its
     * Q3: "used" is derived, and deriving it needs the cells rendered or packed — a cost `:core`
     * cannot pay on every tap of a chip. A "Play all" that is sometimes 40 frames and sometimes 3,
     * depending on whether something has rendered, is a worse answer than one that is always the
     * whole sheet.
     */
    fun playAll(cellCount: Int): CellRoll {
        val every = (0 until cellCount).map { Entry(cell = it, hold = MIN_HOLD) }
        return CellRoll(entries = every, cursor = 0)
    }

    /**
     * Drop every entry naming a cell this grid no longer has — index outside `0 until [cellCount]` —
     * and report how many went. `pruneDeadFrames`, line 4211, which walks backwards and reports a
     * count, and which prunes `weights` in the same pass (line 4222) because the two arrays are
     * parallel and per entry.
     *
     * Returns this roll unchanged, count 0, when there was nothing to drop, for the same reason
     * [untapped] does: a caller can compare the roll it gave with the roll it got back and know
     * whether anything happened without reading the count.
     */
    fun prunedTo(cellCount: Int): Prune {
        val kept = entries.filter { it.cell in 0 until cellCount }
        if (kept.size == entries.size) return Prune(this, 0)
        return Prune(clampedTo(kept), entries.size - kept.size)
    }

    /** A roll plus a count, so a caller can SAY what changed instead of only showing it. */
    data class Prune(val roll: CellRoll, val dropped: Int)

    /**
     * The ORDER BADGE for [cell]: the 1-based order of its LAST entry, or null when the cell is not
     * in the roll. *Why the last one:* a badge tap takes the last use back (Decision 2), so a badge
     * showing a first use's order would point at an entry the finger cannot reach.
     *
     * The rule is here and the badge is the view half's (Decision 13).
     */
    fun lastOrderOf(cell: Int): Int? = entries.indexOfLast { it.cell == cell }.takeIf { it >= 0 }?.plus(1)

    /**
     * The ANIMATION board this roll IS, as a value: one frame per entry, in order, `holdFrames` = the
     * entry's hold, ids `"r0"`, `"r1"`.
     *
     * **A value, not a document.** Nothing here reads or writes a document, and the board this
     * returns is not on any board list — it exists to be played. That is what makes the roll's being
     * session state a fact about the roll and not about the preview.
     *
     * **ANIMATION, and that is not a detail** (Decision 9). `AnimOps.playableSchedule` refuses a
     * board that is not `ANIMATION` — the same rule `DocOps.validate` rule 4 uses — so a SPRITE
     * board cannot be played at all. But the roll *is* a schedule: an ordered list of `(cell, hold)`
     * pairs with per-entry holds, which is precisely `List<Frame>`. So the roll becomes a value and
     * the one reviewed clock in the project plays it.
     *
     * The board this returns is not the document's board, so `id` is [ROLL_BOARD_ID] and no document
     * can be found by asking the model which board this is.
     */
    fun asBoard(fps: Float, rect: RectPx, name: String = "Roll"): Board = Board(
        id = ROLL_BOARD_ID,
        name = name,
        kind = BoardKind.ANIMATION,
        rect = rect,
        fps = fps,
        frames = entries.mapIndexed { i, entry -> Frame(id = "r$i", holdFrames = entry.hold) },
    )

    /**
     * The CELL showing at roll index [index] — the cell, not the entry. `cellAtIndex(-1)` and an
     * index past the end are refused with an `IllegalArgumentException` rather than answered, because
     * a caller holding a bad index has a bug and a wrong cell is a wrong picture.
     *
     * This is the whole of the clock-index → sprite mapping: the roll's entry index IS the board's
     * frame index, and the frame's ID is the only thing in between.
     */
    fun cellAtIndex(index: Int): Int {
        require(index in entries.indices) {
            "a roll of ${entries.size} entries has no index $index, so there is no cell to show for it"
        }
        return entries[index].cell
    }

    /**
     * The roll as the TWO PARALLEL LISTS the sidecar is made of: the cells and the holds, the app's
     * own names and the app's own shape (see Decision 13). This is the entire hand-off to JB-4.03b,
     * and it is two lists because the packer's clip type is a value this row deliberately does not
     * name.
     *
     * **A hold of 3 is `cells = [0, 1]`, `holds = [3, 1]`, and emphatically not
     * `cells = [0, 1, 1, 1]`.** Both spell the same animation and only one of them is a hold a
     * person can still change: the repeats are already lost by the time anybody could edit them,
     * and the app reads a per-frame weight.
     */
    fun cellsAndHolds(): Pair<List<Int>, List<Int>> = entries.map { it.cell } to entries.map { it.hold }

    // ---------------------------------------------------------------- the internals

    /** [entries] without the entry at [at]. A copy, never a view onto the old list. */
    private fun List<Entry>.without(at: Int): List<Entry> {
        val out = ArrayList<Entry>(size - 1)
        for (i in indices) if (i != at) out.add(this[i])
        return out
    }

    /**
     * This roll with [kept] instead of its entries and the cursor clamped DOWN onto a surviving
     * entry — `unAddCell` line 4137,
     * `if (labCur >= labSeq.size()) labCur = Math.max(0, labSeq.size() - 1);`, and `pruneDeadFrames`
     * line 4229 saying the same thing again. At 0 on an empty roll, which is lines 4139-4141.
     *
     * **The clamp is to `size - 1` and not to `size`** (Decision 17). Clamping to `size` would leave
     * the cursor one past the end on every removal, and every reader would have to clamp it again;
     * that is the one of the two obvious wrong answers this exists to rule out.
     *
     * It lives here and not on the grammar, because every removal goes through a copy of the roll.
     */
    private fun clampedTo(kept: List<Entry>): CellRoll =
        CellRoll(entries = kept, cursor = cursor.coerceIn(0, maxOf(0, kept.size - 1)))

    companion object {
        /** SpriteLab's own clamp on a hold, from `bumpHold` (line 2210). The only numbers here. */
        const val MIN_HOLD: Int = 1
        const val MAX_HOLD: Int = 16
    }
}

/** The grammar's verbs, as one sealed type, so a view cannot invent a fifth. */
sealed class CellGesture {
    /** A tap on a cell's body: [CellRoll.tapped], hold 1, always. */
    data class Tapped(val cell: Int) : CellGesture()

    /** A tap on a cell's badge: take the LAST use back. */
    data class BadgeTapped(val cell: Int) : CellGesture()

    /**
     * A long-press on a badge: take EVERY use back, and say how many.
     *
     * **There is deliberately no `CellLongPressed` case** (Decision 16). A long-press on a cell BODY
     * opens the view half's per-cell menu, and a long-press that also fired a tap would put a cell in
     * the roll twice by accident. `CellRollShapeTest` says so by reflection.
     */
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
    fun apply(roll: CellRoll, gesture: CellGesture, cellCount: Int): CellRoll = when (gesture) {
        is CellGesture.Tapped -> roll.tapped(gesture.cell, cellCount)
        is CellGesture.BadgeTapped -> roll.untapped(gesture.cell)
        is CellGesture.BadgeHeld -> roll.untappedAll(gesture.cell).roll
        is CellGesture.HoldBumped -> roll.holdBumped(gesture.delta)
        is CellGesture.Focused -> roll.focused(gesture.index)
        is CellGesture.PlayAll -> roll.playAll(cellCount)
        is CellGesture.Clear -> roll.cleared()
        is CellGesture.Cancel -> roll
    }

    /**
     * How many entries [gesture] would remove. **Always a number, never null** — `0` for a
     * [CellGesture.BadgeTapped] on a cell that is not in the roll, so a caller can always say
     * something (and a nullable return would be the one thing this grammar must not have, since a
     * caller that forgets the null case is the caller that silently says nothing). `0` for every
     * gesture that removes nothing at all, including [CellGesture.Cancel].
     *
     * Answered from the rolls rather than from a second count of its own, so the number a caller is
     * told and the roll it is handed can never disagree.
     */
    fun removedBy(roll: CellRoll, gesture: CellGesture, cellCount: Int): Int = when (gesture) {
        is CellGesture.BadgeTapped -> roll.size - roll.untapped(gesture.cell).size
        is CellGesture.BadgeHeld -> roll.untappedAll(gesture.cell).dropped
        is CellGesture.Tapped,
        is CellGesture.HoldBumped,
        is CellGesture.Focused,
        is CellGesture.PlayAll,
        is CellGesture.Clear,
        is CellGesture.Cancel,
        -> 0
    }

    /**
     * "Play all" and "Clear", which the grammar owns and the roll does not.
     *
     * A bulk gesture replaces or empties the whole roll, so a caller that wants to confirm with a
     * dialog, or to push ONE undo step rather than several, has to be able to ask before it applies.
     */
    fun isBulk(gesture: CellGesture): Boolean =
        gesture is CellGesture.PlayAll || gesture is CellGesture.Clear

    /**
     * The wrap mode a chip means, mapped one for one onto [PlayMode] — `loop → LOOP`,
     * `pingpong → PING_PONG`, `once → ONCE`. Takes the app's own word and refuses an unknown one in
     * words, because an unknown word stored verbatim is a preset that plays as a loop.
     *
     * **EXACT, and case-sensitively so**: three words and nothing else. The sidecar's `type` is a
     * closed vocabulary the app reads, and two vocabularies for three values is how an export stops
     * opening in SpriteLab at all (R23, Decision 11).
     *
     * @throws IllegalArgumentException naming the three words, because "unknown wrap mode" is not a
     *   sentence a person can act on and the three legal ones are.
     */
    fun modeOf(appWord: String): PlayMode = when (appWord) {
        "loop" -> PlayMode.LOOP
        "pingpong" -> PlayMode.PING_PONG
        "once" -> PlayMode.ONCE
        else -> throw IllegalArgumentException(
            "\"$appWord\" is not a wrap mode; the three are \"loop\", \"pingpong\" and \"once\"",
        )
    }
}
