package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.Paper

/**
 * A document's paper, resolved: the numbers a renderer needs, with no ids left in them.
 *
 * **Everything here is either an entry from the catalogue or a plain number.** The engine (JB-9.06)
 * reads this and nothing else about paper, so it never has to look an id up, never has to decide what
 * a missing id means, and never has to know that `document.json` and `catalogue.json` are two files.
 */
data class ResolvedPaper(
    val surface: SurfaceEntry?,   // null = smooth: brushes feel nothing, nothing to light
    val look: LookEntry?,         // null = flat colour
    val baseArgb: Int,            // tint ?: look.base ?: color, opaque
    val scale: Float,             // textureScale clamped 0.25..4
    val show: Float, val bite: Float,
    val light: Boolean,           // light ?: look?.lightByDefault ?: true
    val tintSet: Boolean = false, // explicit tint, including one equal to the look's base
)

/**
 * Joins a document's [Paper] to the paper catalogue (JB-9.05, R10 §3).
 *
 * **The join must never fail, and everything here is arranged around that.** A document names papers
 * by id, and a catalogue can always be a version behind the drawing: a file saved by a later Joy
 * Brush names a paper this build has never heard of, and an entry can be renamed between builds. In
 * both cases the drawing is a real drawing with real work on it, and the only thing at stake is a
 * background — so an unknown id resolves to **null** (smooth, or a flat colour) and is *reported* by
 * [problems]. The alternative, refusing to open, would cost somebody their artwork over their
 * wallpaper, and that is not a trade any format should make.
 *
 * **So there are two questions, deliberately asked separately.** [resolve] is the one a renderer
 * calls on every frame, and it answers "what do I draw" — it never throws, never returns an error, and
 * never allocates a message. [problems] is the one a person or a log asks once, when a paper did not
 * look right, and it is what turns "silently smooth" into "this drawing asks for a paper this build
 * does not have". [PaperCatalogues.problems] is the same split for the catalogue's own contents.
 */
object PaperState {

    /**
     * The document's paper against the catalogue, with no ids left in it.
     *
     * **The colour is one precedence chain, and the order is the owner's mental model rather than the
     * struct's:** a **tint** is the owner saying "make this paper this colour", so it wins; the look's
     * **`base`** is what that paper IS, so it is next; the document's own **`color`** is the pre-catalogue
     * flat colour, which only matters when there is no look at all. Written in that order in the
     * expression so the code reads as the sentence above it.
     */
    fun resolve(p: Paper, c: PaperCatalogue): ResolvedPaper {
        val look = p.lookId?.let { PaperCatalogues.look(c, it) }
        val surface = p.textureId?.let { PaperCatalogues.surface(c, it) }
        return ResolvedPaper(
            surface = surface,
            look = look,
            baseArgb = argb(p.tint ?: look?.base ?: p.color),
            // Clamped, not validated: `DocOps` deliberately kept the v3 rule on this field (JB-9.05
            // answer 3) so an old file stays valid, which means an old file's 64 arrives here and has
            // to become a sensible 4 rather than a paper with one texel across the page.
            scale = p.textureScale.let { if (it.isNaN()) 1f else it.coerceIn(MIN_SCALE, MAX_SCALE) },
            show = p.show,
            bite = p.bite,
            // Three states, and null is a real answer: the look's own default is what makes AMOLED
            // black stay black without every document having to remember to say so.
            light = p.light ?: look?.lightByDefault ?: true,
            tintSet = p.tint != null,
        )
    }

    /**
     * Every problem with a document's paper ids, in words, naming the id. **Empty = every id resolved.**
     *
     * An unknown id is here and NOT in `DocOps.validate`, because `DocOps` cannot check it: it knows
     * nothing about the catalogue, and a document naming a paper this build does not have is a valid
     * document (Decision 3). This is also the only place a "your paper did not load" message can be
     * written, since it is the only place that has both halves in hand.
     */
    fun problems(p: Paper, c: PaperCatalogue): List<String> {
        val out = ArrayList<String>(2)
        p.lookId?.let { id ->
            if (PaperCatalogues.look(c, id) == null) {
                out += "the paper's look \"$id\" is not in the paper catalogue, so the paper is a flat colour"
            }
        }
        p.textureId?.let { id ->
            if (PaperCatalogues.surface(c, id) == null) {
                out += "the paper's surface \"$id\" is not in the paper catalogue, so the paper is smooth"
            }
        }
        return out
    }

    /** The sheet's Scale stops here. 0.25 is a whole paper across a page; 4 is a texture you can count. */
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f

    /**
     * `#RRGGBB` as opaque ARGB. **`DocOps` has already refused anything that is not that shape**, so
     * this cannot fail on a validated document; white is the fallback so a caller that skipped
     * validation gets a drawable colour rather than a thrown parse on the render thread. The length is
     * checked before the digits are read, because a short hex would otherwise parse as a small number
     * and become a nearly transparent black.
     */
    private fun argb(hex: String): Int {
        if (hex.length != 7 || hex[0] != '#') return 0xFFFFFFFF.toInt()
        val v = hex.substring(1).toLongOrNull(16) ?: return 0xFFFFFFFF.toInt()
        return (0xFF000000L or (v and 0xFFFFFF)).toInt()
    }
}
