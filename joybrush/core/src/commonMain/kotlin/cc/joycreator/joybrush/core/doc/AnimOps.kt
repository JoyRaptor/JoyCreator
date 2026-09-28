package cc.joycreator.joybrush.core.doc

/**
 * Pixel work the engine still has to do after a document operation.
 *
 * The operations here are document MATHS: they move frames and cels around and say which cel ought
 * to end up holding which pixels, and they do not touch a single tile. Copying tiles from one cel
 * to another is cheap on the GPU and belongs to the engine, so an operation that needs it returns
 * the instruction instead of doing it — which is also why every one of these is testable with no
 * canvas, no files and no pixels anywhere.
 *
 * A [CopyCel] names ids that exist in the returned [AnimResult.doc]. A [DropCel] names the one that
 * does not, which is the whole point of it: nothing shows that cel any more, so free its tiles.
 */
sealed class CelWork {
    /**
     * Give [toCelId], a cel that was just created on [layerId], the contents of [fromCelId], a cel
     * [layerId] already had. This is what "duplicate" means: a NEW cel, not a second name for the
     * old one, so painting on one frame can never scribble on the other.
     */
    data class CopyCel(val layerId: String, val fromCelId: String, val toCelId: String) : CelWork()

    /**
     * [celId] has been removed from [layerId] because no frame shows it any more.
     *
     * Only ever emitted for a cel nothing else points at, which is what makes "duplicate as link,
     * then delete one of the pair" free rather than a silent loss of the other frame.
     */
    data class DropCel(val layerId: String, val celId: String) : CelWork()
}

/** A new document, plus the pixel work the engine has to do for that document to be true. */
data class AnimResult(val doc: JbDocument, val work: List<CelWork>)

/**
 * What a newly inserted frame starts as.
 *
 * NOT serialised, so there is no version to bump here: this is a gesture, not a thing a saved file
 * holds. All three end up written the same way — "frame X shows cel Y" — and the difference is
 * only in what the engine is asked to paint. (Lead ruling R3 applies to the enums that DO land in
 * `document.json`, such as [BoardKind].)
 */
enum class NewFrame {
    /** An empty cel. Nothing has been drawn on the frame yet. */
    BLANK,

    /** A new cel holding a COPY of the source frame's cel — [CelWork.CopyCel] for the engine. */
    DUPLICATE,

    /**
     * The source frame's cel ITSELF, shared: a second frame id pointing at the same cel. No new cel
     * and no pixel work. This is how "hold" is stored, and deleting either frame keeps the cel for
     * the other.
     */
    LINK,
}

/**
 * The animation board's frame operations, as pure document maths. JB-3.01.
 *
 * There is NO second layer system here, and the model in `DocModel.kt` is the one that is stored: a
 * layer is static (exactly one cel, or an ink layer's strokes) or it is animated in exactly one
 * ANIMATION board, and several frames may point at ONE cel. Every operation below rewrites
 * [Board.frames], [Layer.cels] and [Layer.frameCel] together, because those three are only ever
 * true together:
 *
 *  - a frame of an ANIMATION board with no cel on an animated layer is a hole ([DocOps.validate]
 *    rule 7 says so in words);
 *  - a `frameCel` key naming a frame the board does not have is junk that would sit in the file
 *    forever (also rule 7);
 *  - a static layer with two cels, or with any `frameCel` entry at all, is not a static layer
 *    (rule 6).
 *
 * So the rule this object follows is: **every operation returns a document that [DocOps.validate]
 * is happy with, or throws.** It never repairs a broken document quietly and it never hands back a
 * half-applied one. Refusals are [DocException] with a message naming the thing that was wrong,
 * because every caller of this is a program (the film strip, the undo stack) and should hear about
 * it at once rather than paint a broken frame.
 *
 * Nothing here mutates: every function returns a new [JbDocument], so the undo stack can hold the
 * old one and a caller can try a move and look at it before committing.
 */
object AnimOps {

    /**
     * The longest a frame may be held, in ticks of the board's fps.
     *
     * A hold is a person dragging a slider, and nothing an animation needs is longer than this; an
     * unbounded one would let a document claim to run for years and make every playhead calculation
     * downstream reason about an absurd number.
     */
    private const val MAX_HOLD_FRAMES = 999

    /**
     * The rates a board may play at, and they are [DocOps.validate] rule 4's numbers, not this
     * object's: one rule, written once, so the model and the maths cannot drift apart.
     */
    private const val MIN_FPS = 1f
    private const val MAX_FPS = 60f

    // ------------------------------------------------------------------ animateLayer

    /**
     * Turns a static layer into one animated in [boardId]: its existing cel becomes the cel of
     * EVERY frame of that board.
     *
     * Every frame, not just the first, is the point. A layer that has been static until now has
     * exactly one cel of history, and the honest reading of "this layer now animates here" is that
     * all of its existing artwork shows on every frame until somebody draws on one of them. The
     * alternative — mapping only the first frame — would leave every later frame with no cel, which
     * [DocOps.validate] reports as a hole and which a renderer has no way to draw.
     *
     * Refuses: a layer that is already animated (a layer animates on one board or none, never two),
     * a board that is not an [BoardKind.ANIMATION] board, an unknown id, a board with no frames
     * (there is nothing to be animated across — add the first frame and come back), and a static
     * layer that is already wrong under [DocOps.validate] rule 6 (not one cel, or any frame
     * mappings). Those last two are refused rather than tidied away: fixing them here would be a
     * silent repair, and whatever put them there is the thing that has to be undone.
     */
    fun animateLayer(doc: JbDocument, layerId: String, boardId: String): JbDocument {
        val board = animationBoard(doc, boardId)
        val target = layer(doc, layerId)
        if (target.animatedIn != null) {
            throw DocException(
                "layer \"$layerId\" already animates on board \"${target.animatedIn}\"; " +
                    "a layer animates on one board or on none, never two",
            )
        }
        if (board.frames.isEmpty()) {
            throw DocException(
                "board \"$boardId\" has no frames, so there is nothing for layer \"$layerId\" to " +
                    "animate across: add the first frame first",
            )
        }
        if (target.frameCel.isNotEmpty()) {
            // Rule 6 already calls this document wrong. Overwriting the mappings would be a quiet
            // repair, and this object never repairs quietly: whatever put them there has to be undone
            // by whatever put them there, or the file has to be fixed by hand.
            throw DocException(
                "layer \"$layerId\" is not animated but has ${target.frameCel.size} frame " +
                    "mappings, which is a broken document; fix those before animating it",
            )
        }
        val cel = target.cels.singleOrNull()
            ?: throw DocException(
                "layer \"$layerId\" is not animated, so it should hold exactly one cel and holds " +
                    "${target.cels.size}, which is a broken document; fix that before animating it",
            )
        val map = LinkedHashMap<String, String>()
        for (frame in board.frames) map[frame.id] = cel.id
        return withLayer(doc, target.copy(animatedIn = boardId, frameCel = map))
    }

    // ------------------------------------------------------------------ addFrame

    /**
     * Inserts a new frame after [afterFrameId], or at the very start when it is null.
     *
     * The new frame is held for one tick ([Frame.holdFrames]), because the film strip shows the
     * duration and a person adjusts it after the fact; guessing a longer hold here would make
     * [setHold] a correction rather than a choice.
     *
     * For every layer animated in [boardId]:
     *  - [NewFrame.BLANK] gets a new empty cel and nothing else;
     *  - [NewFrame.DUPLICATE] gets a new cel plus a [CelWork.CopyCel] from the SOURCE frame's cel —
     *    a new cel, so drawing on the copy cannot touch the original;
     *  - [NewFrame.LINK] points at the source frame's cel itself. No new cel, no pixel work, and
     *    the two frames stay linked until one of them is deleted.
     *
     * The SOURCE frame is [afterFrameId] itself, or — when inserting at the start — the frame that
     * is currently first, i.e. the one the new frame lands on top of. That is the frame a person
     * was just looking at, so it is the one they mean.
     *
     * Layers that are not animated in this board are NOT touched: a static layer is a held
     * background that is simply there on every frame, and a layer animated on some other board
     * belongs to that board's film strip.
     *
     * Ids are drawn from [ids] in a fixed SHAPE — the new frame first, then one cel per animated
     * layer, in the order the layers sit in the document — so a failure names the same id the code
     * used and a person reading a log can follow it. That shape is for diagnosis only: which layer
     * gets which id is not part of this contract, and a caller must read the answer out of the
     * returned document rather than assume it. [ids] must hand out an id this document is not
     * already using.
     *
     * Refuses: an unknown board, a board that is not [BoardKind.ANIMATION], an [afterFrameId] that
     * is not a frame of this board, [NewFrame.DUPLICATE] or [NewFrame.LINK] on a board with no
     * frames to copy from, an animated layer that has no cel for the source frame, and an id that
     * the document already uses.
     */
    fun addFrame(
        doc: JbDocument,
        boardId: String,
        afterFrameId: String?,
        mode: NewFrame,
        ids: () -> String,
    ): AnimResult {
        val board = animationBoard(doc, boardId)

        val insertAt: Int
        val sourceFrameId: String?
        if (afterFrameId == null) {
            insertAt = 0
            sourceFrameId = board.frames.firstOrNull()?.id
        } else {
            insertAt = frameIndexOf(board, afterFrameId) + 1
            sourceFrameId = afterFrameId
        }
        if (mode != NewFrame.BLANK && sourceFrameId == null) {
            val verb = if (mode == NewFrame.DUPLICATE) "duplicate" else "link to"
            throw DocException("board \"$boardId\" has no frames to $verb: add a blank frame first")
        }

        val taken = usedIds(doc)
        val frameId = freshId(ids, taken, "a new frame of board \"$boardId\"")

        val frames = ArrayList<Frame>(board.frames.size + 1)
        frames.addAll(board.frames)
        frames.add(insertAt, Frame(id = frameId, holdFrames = 1))

        val work = ArrayList<CelWork>()
        val layers = ArrayList<Layer>(doc.layers.size)
        for (layer in doc.layers) {
            if (layer.animatedIn != boardId) {
                layers.add(layer)
                continue
            }
            val sourceCelId = sourceFrameId?.let { layer.frameCel[it] }
            when (mode) {
                NewFrame.LINK -> {
                    val celId = sourceCelId ?: throw DocException(
                        "layer \"${layer.id}\" has no cel for frame \"$sourceFrameId\", " +
                            "so a new frame cannot be linked to it",
                    )
                    layers.add(layer.copy(frameCel = layer.frameCel + (frameId to celId)))
                }
                NewFrame.DUPLICATE -> {
                    val from = sourceCelId ?: throw DocException(
                        "layer \"${layer.id}\" has no cel for frame \"$sourceFrameId\", " +
                            "so a new frame cannot be duplicated from it",
                    )
                    val celId = freshId(ids, taken, "a new cel on layer \"${layer.id}\"")
                    layers.add(
                        layer.copy(
                            cels = layer.cels + Cel(id = celId),
                            frameCel = layer.frameCel + (frameId to celId),
                        ),
                    )
                    work.add(CelWork.CopyCel(layerId = layer.id, fromCelId = from, toCelId = celId))
                }
                NewFrame.BLANK -> {
                    val celId = freshId(ids, taken, "a new cel on layer \"${layer.id}\"")
                    layers.add(
                        layer.copy(
                            cels = layer.cels + Cel(id = celId),
                            frameCel = layer.frameCel + (frameId to celId),
                        ),
                    )
                }
            }
        }

        return AnimResult(doc = withFramesAndLayers(doc, boardId, frames, layers), work = work)
    }

    // ------------------------------------------------------------------ deleteFrame

    /**
     * Removes a frame, and with it the cel that only that frame was showing.
     *
     * A cel that no remaining frame points at is removed from the layer and reported as
     * [CelWork.DropCel], so the engine can free its tiles. A cel ANOTHER frame still points at is
     * left completely alone — that is the [NewFrame.LINK] case, and it is why deleting one frame of
     * a linked pair costs nothing and loses nothing. The mapping key is removed either way: a
     * `frameCel` entry naming a frame the board no longer has is exactly the junk
     * [DocOps.validate] rule 7 complains about.
     *
     * Refuses to delete the last frame of a board: [DocOps.validate] rule 4 says an animation board
     * with no frames is not a board, so the operation that produced it would be handing back a
     * document it had just been told is broken. Add a frame, then delete this one.
     */
    fun deleteFrame(doc: JbDocument, boardId: String, frameId: String): AnimResult {
        val board = animationBoard(doc, boardId)
        val index = frameIndexOf(board, frameId)
        if (board.frames.size <= 1) {
            throw DocException(
                "board \"$boardId\" has one frame; a board cannot play with no frames, " +
                    "so add a frame before removing this one",
            )
        }

        val frames = ArrayList<Frame>(board.frames)
        frames.removeAt(index)

        val work = ArrayList<CelWork>()
        val layers = ArrayList<Layer>(doc.layers.size)
        for (layer in doc.layers) {
            if (layer.animatedIn != boardId) {
                layers.add(layer)
                continue
            }
            val celId = layer.frameCel[frameId]
            // LinkedHashMap, so the surviving keys keep the order they were built in and a re-save
            // of the document is still a diff and not a shuffle.
            val map = LinkedHashMap<String, String>(layer.frameCel)
            map.remove(frameId)
            if (celId != null && !map.containsValue(celId)) {
                layers.add(layer.copy(cels = layer.cels.filterNot { it.id == celId }, frameCel = map))
                work.add(CelWork.DropCel(layerId = layer.id, celId = celId))
            } else {
                layers.add(layer.copy(frameCel = map))
            }
        }

        return AnimResult(doc = withFramesAndLayers(doc, boardId, frames, layers), work = work)
    }

    // ------------------------------------------------------------------ setHold

    /**
     * Holds [frameId] for [holdFrames] ticks of the board's fps, clamped to 1..999.
     *
     * Clamped rather than refused because the number arrives from a slider and a frame is never
     * shown for zero ticks — [DocOps.validate] rule 4 rejects a hold below 1 — and a hold of zero
     * would also make [frameAt] and [totalDurationMs] meaningless for that frame. [Frame.holdFrames]
     * is the only timing number in the document, so the clamp is the whole of "is this sane".
     *
     * No cel moves and no pixels change, so this is a plain [JbDocument] and no [CelWork].
     */
    fun setHold(doc: JbDocument, boardId: String, frameId: String, holdFrames: Int): JbDocument {
        val board = animationBoard(doc, boardId)
        frameIndexOf(board, frameId) // refuses an unknown frame before anything is written
        val hold = holdFrames.coerceIn(1, MAX_HOLD_FRAMES)
        val frames = board.frames.map { if (it.id == frameId) it.copy(holdFrames = hold) else it }
        return withFrames(doc, boardId, frames)
    }

    // ------------------------------------------------------------------ moveFrame

    /**
     * Moves [frameId] so that it sits at index [toIndex] of [Board.frames] afterwards. [toIndex] is
     * the index the frame ENDS UP at, not the gap it is dropped into, so the legal range is
     * `0` to `frames.size - 1` inclusive and there is one answer rather than two.
     *
     * Play order and the frame-to-cel mapping are independent — [Layer.frameCel] is keyed by frame
     * id, not by position — so reordering must not touch a single cel, and does not. A move to the
     * index a frame is already at returns the document it was given.
     */
    fun moveFrame(doc: JbDocument, boardId: String, frameId: String, toIndex: Int): JbDocument {
        val board = animationBoard(doc, boardId)
        val from = frameIndexOf(board, frameId)
        if (toIndex < 0 || toIndex >= board.frames.size) {
            throw DocException(
                "cannot move frame \"$frameId\" to index $toIndex on board \"$boardId\": " +
                    "the frames are indexed 0 to ${board.frames.size - 1}",
            )
        }
        if (toIndex == from) return doc
        val frames = ArrayList<Frame>(board.frames)
        val moved = frames.removeAt(from)
        frames.add(toIndex, moved)
        return withFrames(doc, boardId, frames)
    }

    // ------------------------------------------------------------------ the schedule

    /**
     * The frame showing at [timeMs] when the board is played once from 0.
     *
     * Boundaries belong to the frame that is starting: at exactly the moment frame 2 begins, frame 2
     * is the one on screen. Before the start ([timeMs] negative) is the first frame; at or past the
     * end is the last frame, because a playhead a hair over the end is a playhead between loops
     * and must show the picture somebody is about to see again. Looping itself is the caller's job —
     * pass `timeMs mod totalDurationMs(board)` — so that this function answers one question instead
     * of two.
     *
     * Refuses a board with no frames, because [frameAt] has to return a [Frame] and there is no
     * honest one to return. A [Double.NaN] time is treated as before the start rather than being
     * allowed to fall out of the search below as "no frame matched".
     */
    fun frameAt(board: Board, timeMs: Double): Frame {
        if (board.frames.isEmpty()) {
            throw DocException("board \"${board.id}\" has no frames, so nothing is showing on it")
        }
        val starts = frameStartsMs(board) // also refuses a board that cannot be timed
        if (timeMs < 0.0) return board.frames.first()
        if (timeMs >= totalDurationMs(board)) return board.frames.last()
        var chosen = 0
        for (i in starts.indices) {
            if (starts[i] <= timeMs) chosen = i else break
        }
        return board.frames[chosen]
    }

    /**
     * How long one play of [board] lasts, in milliseconds: the sum of `holdFrames × 1000 / fps`.
     *
     * An empty board is 0 ms rather than an error, and this function refuses a board whose fps is
     * outside 1..60 — which is [DocOps.validate] rule 4's own range, and catches [Float.NaN] and
     * infinities as well as a rate that is merely too fast. Together those two are the only reasons a
     * document's timing cannot be answered at all.
     */
    fun totalDurationMs(board: Board): Double {
        val fps = playableFps(board)
        var total = 0.0
        for (frame in board.frames) total += durationMs(frame, fps)
        return total
    }

    /**
     * When each frame of [board] starts, in play order — the same order as [Board.frames], so the
     * index of a start is the index of its frame.
     *
     * A frame's own length is worked out as `holdFrames × 1000 / fps`, multiplying before dividing,
     * so the values add up exactly to [totalDurationMs]: both walk the same expression in the same
     * order, and the last start plus the last frame's length IS the total.
     */
    fun frameStartsMs(board: Board): List<Double> {
        val fps = playableFps(board)
        val starts = ArrayList<Double>(board.frames.size)
        var at = 0.0
        for (frame in board.frames) {
            starts.add(at)
            at += durationMs(frame, fps)
        }
        return starts
    }

    // ------------------------------------------------------------------ the small parts

    /** The length of [frame] in ms. Multiply before dividing, so `holdFrames = 3` at 12 fps is 250. */
    private fun durationMs(frame: Frame, fps: Double): Double = frame.holdFrames * 1000.0 / fps

    /**
     * [board]'s fps as a [Double], refused unless it is a rate a board can actually play at.
     *
     * This is the SAME range, in the SAME idiom, as [DocOps.validate] rule 4's `fps !in 1f..60f`, and
     * that is the whole point of writing it that way: this object must never call a board playable
     * that the validator calls broken, or the other way round, or the two disagree about the same
     * document in the same run.
     *
     * `!in` rather than `<`/`>` on purpose. `fps <= 0` lets an INFINITE fps straight through, and an
     * infinite fps is not a fast board, it is a broken number: every frame's length works out to
     * `x / Infinity` = 0 ms, so `totalDurationMs` would answer 0.0 and `frameStartsMs` would answer
     * `0.0, 0.0, 0.0` — and `frameAt` would then report the LAST frame at every time, because every
     * time is at or past a total of zero. A plausible, confident, wrong answer. The range check
     * catches Infinity because Infinity is not a rate between 1 and 60, and it catches [Float.NaN]
     * because NaN is in no range at all — which is exactly why the model uses `!in` and not `<`.
     */
    private fun playableFps(board: Board): Double {
        val fps = board.fps
        if (fps !in MIN_FPS..MAX_FPS) {
            throw DocException(
                "board \"${board.id}\" runs at $fps fps, so it has no length in time " +
                    "(a board plays at $MIN_FPS to $MAX_FPS)",
            )
        }
        return fps.toDouble()
    }

    private fun board(doc: JbDocument, boardId: String): Board =
        doc.boards.firstOrNull { it.id == boardId }
            ?: throw DocException("this document has no board \"$boardId\"")

    /** An [BoardKind.ANIMATION] board, which is the only kind that can hold frames. */
    private fun animationBoard(doc: JbDocument, boardId: String): Board {
        val found = board(doc, boardId)
        if (found.kind != BoardKind.ANIMATION) {
            throw DocException("board \"$boardId\" is a ${found.kind} board; only an ANIMATION board has frames")
        }
        return found
    }

    private fun layer(doc: JbDocument, layerId: String): Layer =
        doc.layers.firstOrNull { it.id == layerId }
            ?: throw DocException("this document has no layer \"$layerId\"")

    /** Where [frameId] sits in [board]'s play order, or a refusal naming the board. */
    private fun frameIndexOf(board: Board, frameId: String): Int {
        val index = board.frames.indexOfFirst { it.id == frameId }
        if (index < 0) throw DocException("board \"${board.id}\" has no frame \"$frameId\"")
        return index
    }

    private fun withFrames(doc: JbDocument, boardId: String, frames: List<Frame>): JbDocument =
        doc.copy(boards = doc.boards.map { if (it.id == boardId) it.copy(frames = frames) else it })

    /**
     * A board's frames AND the layers that animate in it, rewritten together.
     *
     * One function, because writing one without the other is the bug this object exists to avoid:
     * a new frame whose layers still point at the old set of frames is a document [DocOps.validate]
     * refuses, and the fix has to be made in one place rather than remembered at each call site.
     */
    private fun withFramesAndLayers(
        doc: JbDocument,
        boardId: String,
        frames: List<Frame>,
        layers: List<Layer>,
    ): JbDocument = doc.copy(
        boards = doc.boards.map { if (it.id == boardId) it.copy(frames = frames) else it },
        layers = layers,
    )

    private fun withLayer(doc: JbDocument, layer: Layer): JbDocument =
        doc.copy(layers = doc.layers.map { if (it.id == layer.id) layer else it })

    /**
     * Every id the document is already using, in every namespace.
     *
     * Deliberately stricter than [DocOps.validate], which only forbids a repeat WITHIN a list (two
     * frames of one board, two cels of one layer). One flat set buys a much simpler promise to the
     * caller — "your generator gave me an id this document already has, and that is a bug worth
     * hearing about" — instead of four namespace rules that only hold if you remember all four.
     */
    private fun usedIds(doc: JbDocument): MutableSet<String> {
        val taken = HashSet<String>()
        for (b in doc.boards) {
            taken.add(b.id)
            for (frame in b.frames) taken.add(frame.id)
        }
        for (l in doc.layers) {
            taken.add(l.id)
            for (cel in l.cels) taken.add(cel.id)
        }
        return taken
    }

    /** The next id from [ids], refused if the document is already using it. */
    private fun freshId(ids: () -> String, taken: MutableSet<String>, what: String): String {
        val id = ids()
        if (!taken.add(id)) {
            throw DocException(
                "the id generator returned \"$id\", which this document is already using; " +
                    "it was asked for $what",
            )
        }
        return id
    }
}
