package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx

/**
 * Small, opaque RGBA crops from the same renderer as export. Own this cache on ONE preview worker,
 * never the UI thread; the UI clips a returned square to a circle and discards obsolete completions.
 * Android supplies `{ paper, rect -> PaperResources.load(paper).render(rect) }` as [render].
 * A crop covers its width/height in document pixels at the origin, with no display-only detail.
 */
class PaperPreviews(
    private val maxEntries: Int = 24,
    private val render: (ResolvedPaper, RectPx) -> ByteArray,
) {
    init { require(maxEntries > 0) }
    private data class Key(val paper: ResolvedPaper, val width: Int, val height: Int)
    private val cache = LinkedHashMap<Key, ByteArray>()

    /** Includes Show, Scale, Light and catalogue metadata, so changing any visible setting is live. */
    fun crop(paper: ResolvedPaper, width: Int, height: Int = width): ByteArray {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE)
        val key = Key(paper, width, height)
        val bytes = cache.remove(key) ?: (if (paper.screenTransparent) checker(width,height)
            else render(paper, RectPx(0, 0, width, height))).also {
            require(it.size == width * height * 4) { "paper preview renderer returned the wrong RGBA size" }
        }.copyOf()
        cache[key] = bytes // Reinsert hits at the end: least-recently-used entry is first.
        if (cache.size > maxEntries) cache.remove(cache.keys.first())
        return bytes.copyOf() // Android may modify a crop; it cannot alter the next preview.
    }

    /** Catalogue background circles include that look's default physical surface. */
    fun background(look: LookEntry, catalogue: PaperCatalogue, size: Int): ByteArray =
        crop(PaperState.resolve(Paper(lookId = look.id, textureId = look.defaultSurface), catalogue), size)

    /** Preview a picker colour with the current physical surface and visible controls. */
    fun colour(current: Paper, colour: String, catalogue: PaperCatalogue, size: Int): ByteArray =
        crop(PaperState.resolve(customColour(current, colour), catalogue), size)

    /** The neutral base lets a surface be judged without a look's fibres or colour clouding it. */
    fun surface(surface: SurfaceEntry?, size: Int): ByteArray = crop(
        ResolvedPaper(surface, null, SURFACE_BASE, 1f, 1f, 1f, light = true), size)

    fun clear() = cache.clear()

    companion object {
        /** Background colour replaces the look/tint; it never changes brush tooth or export choice. */
        fun customColour(current: Paper, colour: String): Paper {
            require(Regex("#[0-9a-fA-F]{6}").matches(colour)) { "paper colour must be #RRGGBB" }
            return current.copy(color = colour, lookId = null, tint = null, screenTransparent = false)
        }

        private fun checker(w: Int, h: Int): ByteArray = ByteArray(w*h*4).also { out ->
            for(y in 0 until h) for(x in 0 until w) {
                val i=(y*w+x)*4; val grey=if((x/8+y/8)%2==0) 204 else 230
                out[i]=grey.toByte();out[i+1]=grey.toByte();out[i+2]=grey.toByte();out[i+3]=255.toByte()
            }
        }

        const val MAX_SIZE = 128
        private const val SURFACE_BASE = -0x272728 // opaque #D8D8D8
    }
}
