package cc.joycreator.joybrush.core.doc

/**
 * Creating and checking documents: the little bit of logic that `document.json` cannot hold.
 *
 * [validate] exists because a document arrives from outside — a shared `.joybrush`, a hand-edited
 * json, a future Joy Brush — and a renderer that trusts a broken document crashes on the phone instead
 * of telling the person what is wrong. So every rule states what is wrong in words, in the person's
 * terms, and returns ALL the problems rather than the first one: a person fixing a document should
 * see the whole list once.
 */
object DocOps {

    /** Paper colour is stored as `#RRGGBB` — the format every layer panel already speaks. */
    private val PAPER_COLOR = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * A new document: one CANVAS board at (0,0,w,h) named "Board 1", one PAINT layer "Layer 1".
     *
     * A document with no room cannot be drawn on, so that is refused here rather than left for
     * [validate] — a caller making a document is a program, and should hear about it at once.
     * Lead ruling, 2026-09-28.
     */
    fun newDocument(id: String, name: String, w: Int, h: Int, ids: () -> String): JbDocument {
        require(w > 0 && h > 0) { "a new document needs some room: ${w}x$h" }
        val boardId = ids()
        val layerId = ids()
        val celId = ids()
        return JbDocument(
            id = id,
            name = name,
            boards = listOf(
                Board(
                    id = boardId,
                    name = "Board 1",
                    kind = BoardKind.CANVAS,
                    rect = RectPx(0, 0, w, h),
                ),
            ),
            layers = listOf(
                Layer(
                    id = layerId,
                    name = "Layer 1",
                    kind = LayerKind.PAINT,
                    cels = listOf(Cel(id = celId)),
                ),
            ),
            activeLayerId = layerId,
            activeBoardId = boardId,
        )
    }

    /** Every problem found (empty = valid). One message per problem, never a crash. */
    fun validate(doc: JbDocument): List<String> {
        val out = ArrayList<String>()

        // 1 — is this even a Joy Brush document, and can this build understand it?
        if (doc.format != DOC_FORMAT) out += "not a Joy Brush document: format is \"${doc.format}\""
        if (doc.version > DOC_VERSION) {
            out += "document is from a newer Joy Brush (version ${doc.version}, this build reads $DOC_VERSION)"
        }

        // 2 — ids address things, so they have to be unique.
        out += duplicateIds(doc.boards.map { it.id }) { "two boards are both called \"$it\"" }
        out += duplicateIds(doc.layers.map { it.id }) { "two layers are both called \"$it\"" }
        for (l in doc.layers) {
            out += duplicateIds(l.cels.map { it.id }) { "layer \"${l.id}\" has two cels called \"$it\"" }
        }

        // 3 — a board with no room exports nothing.
        for (b in doc.boards) {
            if (b.rect.w <= 0 || b.rect.h <= 0) {
                out += "board \"${b.id}\" has no room: it is ${b.rect.w} by ${b.rect.h}"
            }
        }

        // 4 — an animation board that cannot play.
        for (b in doc.boards) if (b.kind == BoardKind.ANIMATION) {
            if (b.frames.isEmpty()) out += "animation board \"${b.id}\" has no frames"
            out += duplicateIds(b.frames.map { it.id }) { "board \"${b.id}\" has two frames called \"$it\"" }
            for (f in b.frames) {
                if (f.holdFrames < 1) {
                    out += "board \"${b.id}\" frame \"${f.id}\" is held for ${f.holdFrames} frames"
                }
            }
            // `!in` rather than `<` / `>`, so NaN is caught instead of sailing through both tests.
            if (b.fps !in 1f..60f) out += "board \"${b.id}\" runs at ${b.fps} fps (must be 1 to 60)"
        }

        // 5 — a sprite board with no grid is a plain rectangle by accident.
        for (b in doc.boards) if (b.kind == BoardKind.SPRITE) {
            val g = b.grid
            when {
                g == null -> out += "sprite board \"${b.id}\" has no grid"
                g.cols < 1 || g.rows < 1 || g.cellW < 1 || g.cellH < 1 ->
                    out += "sprite board \"${b.id}\" has a grid with no cells (${g.cols}x${g.rows} of ${g.cellW}x${g.cellH})"
            }
        }

        val boardsById = doc.boards.associateBy { it.id }

        // 6 & 7 — a layer is either static (one cel) or animated in exactly one ANIMATION board.
        for (l in doc.layers) {
            val anim = l.animatedIn
            if (anim == null) {
                if (l.cels.size != 1) {
                    out += "layer \"${l.id}\" is not animated so it has ${l.cels.size} cels (it needs 1)"
                }
                if (l.frameCel.isNotEmpty()) {
                    out += "layer \"${l.id}\" is not animated but has ${l.frameCel.size} frame mappings"
                }
            } else {
                val board = boardsById[anim]
                when {
                    board == null -> out += "layer \"${l.id}\" animates on board \"$anim\", which is not in this document"
                    board.kind != BoardKind.ANIMATION ->
                        out += "layer \"${l.id}\" animates on board \"$anim\", which is a ${board.kind} board"
                    else -> {
                        val celIds = l.cels.mapTo(HashSet()) { it.id }
                        val frameIds = board.frames.mapTo(HashSet()) { it.id }
                        for (f in board.frames) {
                            val cel = l.frameCel[f.id]
                            when {
                                cel == null -> out += "layer \"${l.id}\" has no cel for frame \"${f.id}\""
                                cel !in celIds -> out += "layer \"${l.id}\" shows frame \"${f.id}\" with cel \"$cel\", which it does not have"
                            }
                        }
                        // And the other way round: a mapping for a frame that does not exist is junk
                        // that would otherwise sit in the file forever. Lead ruling, 2026-09-28.
                        for (id in l.frameCel.keys) {
                            if (id !in frameIds) {
                                out += "layer \"${l.id}\" maps frame \"$id\", which is not a frame of board \"$anim\""
                            }
                        }
                    }
                }
            }
        }

        // 7b — masks and clipping (JB-2.23, R48).
        for ((index, l) in doc.layers.withIndex()) {
            val m = l.mask
            if (m != null) {
                if (l.kind != LayerKind.PAINT) out += "layer \"${l.id}\" is ${l.kind}, and only a paint layer can have a mask"
                if (l.cels.any { it.id == m.id }) out += "layer \"${l.id}\" has a mask and a cel both called \"${m.id}\""
                if (m.strokesFile != null) out += "the mask of layer \"${l.id}\" has strokes; a mask is pixels"
            }
            if (l.clip && index == 0) out += "layer \"${l.id}\" is clipped, and there is no layer below it to clip to"
        }

        // 8 — pixels and strokes are different truths; a cel is one or the other.
        for (l in doc.layers) for (c in l.cels) when (l.kind) {
            LayerKind.PAINT ->
                if (c.strokesFile != null) out += "layer \"${l.id}\" is paint, so cel \"${c.id}\" cannot have strokes"
            LayerKind.INK ->
                if (c.tiles.isNotEmpty()) out += "layer \"${l.id}\" is ink, so cel \"${c.id}\" cannot have tiles"
        }

        // 9 — a saved document remembers where the person was.
        doc.activeLayerId?.let { id ->
            if (doc.layers.none { it.id == id }) out += "the active layer \"$id\" is not in this document"
        }
        doc.activeBoardId?.let { id ->
            if (doc.boards.none { it.id == id }) out += "the active board \"$id\" is not in this document"
        }

        // 10 — values a renderer would multiply by, or paint with.
        for (l in doc.layers) {
            if (l.opacity !in 0f..1f) out += "layer \"${l.id}\" is ${l.opacity} opaque (must be 0 to 1)"
        }
        if (!PAPER_COLOR.matches(doc.paper.color)) {
            out += "the paper colour \"${doc.paper.color}\" is not a #RRGGBB colour"
        }
        // Zero hides the paper and a huge scale shows one texel stretched across the page. Lead
        // ruling, 2026-09-28. `isNaN` first, because NaN answers false to every comparison below.
        //
        // The scale keeps its v3 rule on purpose (JB-9.05, specialist answer 3): the 0.25..4 window
        // is a CLAMP inside `PaperState.resolve`, not a validation rule, so a file written by a build
        // that allowed 64 still opens and still gets a sane paper instead of turning red on open.
        val scale = doc.paper.textureScale
        if (scale.isNaN() || scale <= 0f || scale > 64f) {
            out += "the paper texture is scaled ${scale}x (must be over 0 and no more than 64)"
        }
        // 10b — the v4 paper controls (JB-9.05). `show` and `bite` are fractions a renderer multiplies
        // by, so a value outside 0..1 is either an invisible paper or brushes that feel too much or
        // nothing; `!in` rather than `< 0f || > 1f` so NaN is refused too, since a NaN `show` is a
        // paper pass that draws nothing at all and says so nowhere.
        //
        // **`lookId` and `textureId` are NOT checked here.** `DocOps` knows nothing about the paper
        // catalogue, and an id it cannot resolve is not a broken document: a drawing saved with a paper
        // a later Joy Brush added must still open here. `PaperState.problems` resolves the ids and says
        // so, which is the one place that can do it without this file growing a dependency on the
        // catalogue.
        if (doc.paper.show !in 0f..1f) {
            out += "the paper is shown at ${doc.paper.show} (must be 0 to 1)"
        }
        if (doc.paper.bite !in 0f..1f) {
            out += "the paper's bite is ${doc.paper.bite} (must be 0 to 1)"
        }
        // A tint is a colour a renderer parses, so the same `^#[0-9A-Fa-f]{6}$` the paper's own colour
        // is held to. Null is not a bad tint: null means "the look's own colours".
        val tint = doc.paper.tint
        if (tint != null && !PAPER_COLOR.matches(tint)) {
            out += "the paper tint \"$tint\" is not a #RRGGBB colour"
        }

        // 11 — a document with nothing in it is not a document. Lead ruling, 2026-09-28.
        if (doc.boards.isEmpty()) out += "this document has no boards"
        if (doc.layers.isEmpty()) out += "this document has no layers"

        return out
    }

    /** Every cel of a layer that holds tiles in the archive: its [Layer.cels] and its mask (JB-2.23, R48). */
    fun storedCels(layer: Layer): List<Cel> = layer.mask?.let { layer.cels + it } ?: layer.cels

    /** The cel a layer shows on a frame: static → its only cel; animated → what the frame maps to. */
    fun celFor(layer: Layer, frameId: String?): Cel? {
        if (layer.animatedIn == null) return layer.cels.singleOrNull()
        val celId = frameId?.let { layer.frameCel[it] } ?: return null
        return layer.cels.firstOrNull { it.id == celId }
    }

    /** Tile key helpers: `key(3, -2)` is `"3_-2"`, and `tileOf` floors to [TILE_SIZE] either way. */
    fun key(tx: Int, ty: Int): String = "${tx}_$ty"

    /** Which tile a pixel is in. The canvas is unbounded, so this floors and stays negative. */
    fun tileOf(px: Int, py: Int): Pair<Int, Int> = px.floorDiv(TILE_SIZE) to py.floorDiv(TILE_SIZE)

    /** One message per id that appears more than once, in the order it first repeats. */
    private fun duplicateIds(ids: List<String>, message: (String) -> String): List<String> {
        val seen = HashSet<String>()
        val repeated = LinkedHashSet<String>()
        for (id in ids) if (!seen.add(id)) repeated.add(id)
        return repeated.map(message)
    }
}
