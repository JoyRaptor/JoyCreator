package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.math.floor

// `Clip`, `PackedSheet` and `SpritePacker` are in THIS package, so they need no import — adding one
// would be noise. `DocException` is named in KDoc only, so it is qualified there rather than imported.

/**
 * WHAT to export, as data. Every encoder below is a consumer of this and adds nothing of its own.
 *
 * The delays come from [cc.joycreator.joybrush.core.doc.AnimOps.frameStartsMs] and
 * [cc.joycreator.joybrush.core.doc.AnimOps.totalDurationMs] and are **never** re-derived by
 * accumulating `holdFrames × 1000 / fps` — the same rule as JB-3.05a and JB-3.03, and for the same
 * reason: a boundary one ulp out is a frame of the wrong length in a file somebody else will play.
 */
data class AnimExportPlan(
    /** Frame ids, in play order. The cells of a sprite sheet, and the frames of a GIF or a sequence. */
    val frameIds: List<String>,
    /** How long each frame is shown, in ms, parallel to [frameIds]. Every entry is at least 1. */
    val delayMs: List<Int>,
    /** The picture size, in pixels. Every frame is this size; a board has one rectangle. */
    val width: Int,
    val height: Int,
    /** The board's own fps, for the one format that wants a cadence rather than a list of delays. */
    val fps: Float,
    /** The file name WITHOUT an extension, already through [AnimExport.safeBaseName]. */
    val baseName: String,
    /**
     * The board's own rectangle, not just its size, and every render is at exactly this rect.
     * A board may sit at a negative origin and the export crops to it (the same crop
     * `RegionRenderer` does and the same one `OraExport` makes).
     */
    val rect: RectPx,
) {
    val totalMs: Int get() = delayMs.sum()
    val frameCount: Int get() = frameIds.size
}

/**
 * The plan, the ranges, the name rule and the two sheet helpers: everything about WHAT an animation
 * export contains, with no encoder and no file anywhere near it.
 *
 * One seam, three encoders (Decision 1). The moment each format computes its own delays, two formats
 * drift — and the drift arrives as "the GIF is a frame short", which is a bug report with no cause
 * anybody can find.
 */
object AnimExport {

    /**
     * The whole board, in play order, at [board]'s own rect.
     *
     * @param baseName the file name without an extension. Sanitised by [safeBaseName] — the caller
     *   passes whatever the board is called and does not pre-clean it.
     * @throws cc.joycreator.joybrush.core.doc.DocException on a board with no frames, a board with
     *   two frames of the same id, a board that is not `BoardKind.ANIMATION`, or an fps outside
     *   1..60. **All four are `DocException`, not `IllegalArgumentException`** — TWO of them are
     *   `AnimOps`' own refusals arriving unchanged (the board kind and the fps; it throws
     *   `DocException`, which extends `Exception`, `DocJson.kt:8`), and the other two are this
     *   object's own sentences in the same type, because a sentence is what the export button shows.
     */
    fun plan(board: Board, baseName: String): AnimExportPlan =
        build(board, 0, board.frames.size - 1, baseName)

    /**
     * A SUB-RANGE of the board: frames [first]..[last] inclusive, in play order.
     *
     * Swapped if given backwards and clamped into the board, because that is exactly what
     * `PlaybackClock`'s constructor does with the same two numbers (`PlaybackClock.kt:52-53`), and
     * "export frames 2..5" and "play frames 2..5" must be the same four frames by construction.
     * See Decision 4 and the test that proves it against the clock's own answers.
     */
    fun planRange(board: Board, first: Int, last: Int, baseName: String): AnimExportPlan {
        val frames = board.frames.size
        if (frames == 0) {
            // No range to clamp into, and nothing to say about a range: the refusal is the same one
            // `plan` gives, so the two never disagree about an empty board. Delegated rather than
            // duplicated, and the `0 until -1` below is never reached.
            return build(board, 0, -1, baseName)
        }
        // `minOf`/`maxOf` then `coerceIn`, in that order, so the pair is swapped first and clamped
        // second — the same two lines as `PlaybackClock`'s constructor, in the same order.
        val lo = minOf(first, last).coerceIn(0, frames - 1)
        val hi = maxOf(first, last).coerceIn(0, frames - 1)
        return build(board, lo, hi, baseName)
    }

    /**
     * The file name that goes in front of every extension: no path separator, no colon, no control
     * character, trimmed, and never empty. **The algorithm is Decision 5 — there is no judgement
     * left.** The steps, in order:
     *
     * ```
     * val s = raw
     *     .filter { it.code >= 0x20 && it.code != 0x7F }   // 1. drop control chars
     *     .replace(Regex("[/\\:*?\"<>|]"), " ")           // 2. separators + Windows-illegal -> ONE space
     *     .replace(Regex(" +"), " ")                        // 3. collapse the runs that step 2 made
     *     .trim()                                            // 4. trim
     *     .trim('.', ' ')                                   // 5. Windows drops trailing dots and spaces
     * val capped = if (s.length > 512) s.take(512).trim('.', ' ') else s   // 6. MAX_NAME_CHARS
     * return if (capped.isEmpty()) "Animation" else capped                // 7. the named fallback
     * ```
     *
     * `"C:evil"` → `"C evil"`, `"../etc/passwd"` → `"etc passwd"`, a name with a NUL in it → the
     * same name without it, `"///"` → `"Animation"`.
     *
     * **THIS RULE IS A RESTATEMENT OF `JbArchive.unsafeReason`, NOT AN IMPORT OF IT.** That
     * function is **private** (`JbArchive.kt:514`) and it lives in `:androidkit`, whose dependency
     * runs the other way (`androidkit/build.gradle.kts:45`), so `commonMain` cannot see it at all.
     * The two must be kept in step by hand; the comment above is the half of the pair that lives
     * here, and `JbArchive`'s side is the other half. The difference in scope is deliberate and not
     * an omission: `unsafeReason` REFUSES (a `JbArchiveException` in a sentence) because it is
     * guarding a zip entry name, while this SANITISES, because a board's name is not somebody
     * typing a path into a field and a hard failure would be worse than a sensible default.
     */
    fun safeBaseName(raw: String): String {
        val s = raw
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .replace(SEPARATORS, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('.', ' ')
        val capped = if (s.length > MAX_NAME_CHARS) s.take(MAX_NAME_CHARS).trim('.', ' ') else s
        return if (capped.isEmpty()) FALLBACK_NAME else capped
    }

    /**
     * Columns for the sprite sheet of [frameCount] frames, with no picker and no caller argument:
     * `ceil(sqrt(frameCount))`, at least 1. See Decision 6.
     *
     * Computed with the integer walk rather than `ceil(sqrt(n.toDouble()))` for one reason: this is
     * a column count and the two are provably the same number for every `Int`, but the integer
     * version cannot be one ulp out on a perfect square, and a grid that is one row short is a
     * sheet whose last frame has fallen off it.
     */
    fun sheetCols(frameCount: Int): Int {
        if (frameCount <= 1) return 1
        var c = 1
        while (c.toLong() * c.toLong() < frameCount.toLong()) c++
        return c
    }

    /**
     * The sidecar's ONE clip for [plan]: every frame of the plan as a cell index, **with each hold
     * folded in as a repeat of that index** (`frames = [0, 1, 1, 2]` for holds `[1, 2, 1]`), [type]
     * verbatim, [fps] = the plan's. See Decision 7.
     *
     * `Clip` has no `weights` field today, and this row must not start writing one: JB-4.03c may
     * add it later, and when it does this row keeps using repeats. A repeat is a repeat, not a
     * mistake — that is `SpritePacker`'s own KDoc on `Clip.frames`, and it is how a ping-pong is
     * written too.
     */
    fun sheetClip(plan: AnimExportPlan, name: String, type: String): Clip =
        Clip(
            name = name,
            frames = cellIndices(plan),
            type = type,
            fps = plan.fps,
        )

    /**
     * Every cell index named after the frame it holds — the sparse `cellNames` map
     * `SpritePacker.pack` already writes, so the sidecar says which cell is which frame.
     *
     * `mapIndexed { index, frameId -> index to frameId }.toMap()`: the map is sparse in the sense
     * that a key that is not a cell is not written, and it is ascending in index order because
     * `SpritePacker` sorts it. Both are its decision and this map hands it something to sort.
     */
    fun sheetCellNames(plan: AnimExportPlan): Map<Int, String> =
        plan.frameIds.mapIndexed { index, frameId -> index to frameId }.toMap()

    // ---------------------------------------------------------------- the two derivations

    /**
     * The whole plan, from a range of the board's frames.
     *
     * The two refusals below are this object's own sentences, in `DocException`, because the export
     * button shows a message. The rest — the board kind, the fps, a frame held for no time at all —
     * are `AnimOps`' refusals and are left to arrive unchanged by the call at the top of the
     * derivation, so there is exactly one answer to "may this board play" in the whole codebase.
     */
    private fun build(board: Board, lo: Int, hi: Int, baseName: String): AnimExportPlan {
        // `AnimOps` FIRST, deliberately, before this object's own two sentences below. Its
        // `playableSchedule` is where the board kind, the fps range and a frame held for no time at
        // all are decided, and it throws a `DocException` naming the board for all three. Calling it
        // first means "this is a CANVAS board" is what a person is told about a CANVAS board, rather
        // than a complaint about it having no frames — which is what happens if the emptiness check
        // runs first, and is a sentence that names a different problem from the real one.
        //
        // An empty ANIMATION board with a legal fps gets past it: `playableSchedule` refuses a board
        // that cannot be TIMED, and an empty schedule is empty rather than untimeable.
        val starts = AnimOps.frameStartsMs(board)
        val total = AnimOps.totalDurationMs(board)

        if (board.frames.isEmpty()) refuseNoFrames(board)
        val seen = HashSet<String>()
        for (frame in board.frames) {
            if (!seen.add(frame.id)) {
                throw DocException(
                    "board \"${board.id}\" has two frames called \"${frame.id}\", so an export of " +
                        "it cannot say which one is which. Rename one of them and try again.",
                )
            }
        }

        // `frameStartsMs` returns one START per frame and NO trailing end, which is why the last
        // frame is differenced against `totalDurationMs` rather than against a start. Both are
        // already in MILLISECONDS: the `1000 / fps` has been applied inside them, and there is no
        // `× 1000` in this file. `PlaybackClock.kt:69-78` is this same derivation, already landed
        // and already reviewed, and it is the reason the line below is spelled this way.
        val last = board.frames.size - 1
        val delays = ArrayList<Int>(hi - lo + 1)
        for (i in lo..hi) {
            val end = if (i < last) starts[i + 1] else total
            delays.add(floor(end - starts[i] + 0.5).toInt().coerceAtLeast(1))
        }

        return AnimExportPlan(
            frameIds = board.frames.subList(lo, hi + 1).map { it.id },
            delayMs = delays,
            width = board.rect.w,
            height = board.rect.h,
            fps = board.fps,
            baseName = safeBaseName(baseName),
            rect = board.rect,
        )
    }

    /**
     * `floor(x + 0.5)`, and the reason it is written that way.
     *
     * `kotlin.math.round` is TIES TO EVEN, and a board is not a place where that is what anybody
     * meant: at 16 fps a hold of one frame is exactly 62.5 ms, and the two spellings disagree there
     * (62 against 63). Every delay in a plan is positive, so `floor(x + 0.5)` IS "round half up",
     * which on a positive number is also "ties away from zero" — and it says which one it is.
     */
    private fun roundHalfUpMs(x: Double): Int = floor(x + 0.5).toInt().coerceAtLeast(1)

    /**
     * How many board ticks frame [i] was held for, read back out of the plan's own delay.
     *
     * **THIS IS THE INVERSE OF THE DELAY, NOT A SECOND DEFINITION OF THE HOLD.** The plan carries a
     * duration in milliseconds and a rate, and a cell index is a position, so a sidecar that has to
     * survive a hold has to get the hold from somewhere and there is exactly one somewhere. It is
     * exact, not approximate: [AnimExportPlan.delayMs] is `floor(exact + 0.5)`, so the error is at
     * most half a millisecond, and half a millisecond at 60 fps is 0.03 of a tick — far too little to
     * reach the next whole tick in either direction. `noHoldIsLostOrGainedByTheRoundTrip` in the
     * test beside this file walks all 59 940 rate/hold pairs through the real `AnimOps` and checks
     * that this returns the hold that went in, every time.
     */
    private fun ticksOf(plan: AnimExportPlan, i: Int): Int =
        roundHalfUpMs(plan.delayMs[i].toDouble() * plan.fps.toDouble() / 1000.0)

    /** Every frame of the plan as a cell index, with each hold folded in as a repeat of it. */
    private fun cellIndices(plan: AnimExportPlan): List<Int> {
        val out = ArrayList<Int>(plan.frameIds.size)
        for (i in plan.frameIds.indices) {
            repeat(ticksOf(plan, i)) { out.add(i) }
        }
        return out
    }

    private fun refuseNoFrames(board: Board): Nothing = throw DocException(
        "board \"${board.id}\" has no frames, so there is nothing to export. Draw on the animation " +
            "board and try again.",
    )

    /** `JbArchive.unsafeReason` refuses these for a zip entry name; here they become one space. */
    private val SEPARATORS = Regex("[/\\\\:*?\"<>|]")

    /** The runs of spaces step 2 leaves behind, and only those: a tab was already dropped. */
    private val SPACES = Regex(" +")

    /** The same ceiling `JbArchive` applies to a name. See [safeBaseName] for why it is a copy. */
    private const val MAX_NAME_CHARS = 512

    /** The named fallback, so a test can assert it and a person can recognise it. */
    private const val FALLBACK_NAME = "Animation"
}
