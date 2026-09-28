package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import kotlin.math.floor

/**
 * Supplies one tile's pixels: 256x256 RGBA8, PREMULTIPLIED, row 0 = the tile's top document row.
 * Null if the tile is empty. This is the whole of what the renderer knows about storage, which is
 * what lets the phone read a GL texture, the exporter read a zip and a test read a map without the
 * renderer caring which.
 *
 * THE ADDRESS IS (LAYER, CEL, TILE), and the layer is not decoration. A cel id is unique only WITHIN
 * a layer — `DocOps.validate` forbids a repeat inside one layer's cels and says nothing about
 * across layers — so two layers may legitimately both hold a cel called "c", and those are two
 * different pixel stores. That is why the engine keys its tiles by layer (`JbArchive` writes
 * `layers/<layerId>/<celId>/<tx>_<ty>.rgba`), and why every layer is asked for its own tiles even
 * when two of them share a cel id. An implementation that caches or dedupes by cel id alone will
 * quietly show one layer's pixels on top of another's.
 */
fun interface TileSource {
    fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray?
}

/**
 * Flattens any rectangle of a document to pixels: "the picture in this box, at this frame, with or
 * without paper". Every export (PNG, OpenRaster, GIF, video, sprite sheet, thumbnail) goes through
 * here, so they cannot disagree with each other.
 *
 * WHAT IT AGREES WITH. The GPU composites with the same rules: paper is a `glClearColor` backdrop
 * rather than a layer (so a region nothing covers is TRANSPARENT, not black, unless paper was
 * asked for), each layer is its premultiplied tile scaled by its opacity and then blended over
 * (`jb_tile.frag`), and an erase multiplies the backdrop by one minus the source alpha on all four
 * channels (`jb_commit.frag`). The arithmetic itself is [Blend].
 *
 * WHICH CEL. Not derived here: [DocOps.celFor] owns the frame-to-cel rule, so a renderer can never
 * grow its own, slightly different, idea of which cel a frame shows.
 *
 * A LOCKED layer still renders. Locking stops a person editing a layer; it is not an instruction to
 * hide the layer, and treating it as one would make a locked layer vanish from exports while
 * remaining plainly visible on the phone.
 *
 * Coordinates are document pixels and may be negative — the canvas is unbounded — but a region
 * must fit inside Int coordinate range, which no real board comes near. A region of nothing (w or h
 * of 0) returns an empty image rather than throwing, because a board of no size is a legal thing to
 * ask about; a NEGATIVE size throws, because that is a caller bug and silently returning nothing
 * would hide it.
 */
object RegionRenderer {

    /** Bytes in one tile: 256 x 256 x 4. */
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4

    /** Paper is stored as `#RRGGBB` — the format every layer panel already speaks. */
    private val PAPER_COLOR = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * The picture inside [rect], composited bottom to top through each layer's opacity and blend
     * mode, optionally over [paper] as an opaque `#RRGGBB` backdrop.
     *
     * @return STRAIGHT (un-premultiplied) RGBA8, `rect.w * rect.h * 4` bytes, row 0 = top. The
     *   straight form is what every exporter writes, so the un-premultiply happens here, once,
     *   instead of in each of the five exporters that would otherwise each get it slightly wrong.
     */
    fun render(
        doc: JbDocument,
        tiles: TileSource,
        rect: RectPx,
        frameId: String?,
        paper: String?,
    ): ByteArray {
        requireSize(rect)
        val out = ByteArray(rect.w * rect.h * 4)
        if (rect.w == 0 || rect.h == 0) return out
        val p = renderPremultiplied(doc, tiles, rect, frameId, paper)
        var i = 0
        while (i < p.size) {
            val a = p[i + 3]
            // Alpha 0 has no colour to recover, and dividing by it would be 0/0. Transparent is
            // 0,0,0,0 — NOT the last colour that passed through.
            val k = if (a > 0f) 1f / a else 0f
            out[i] = toByte255(p[i] * k)
            out[i + 1] = toByte255(p[i + 1] * k)
            out[i + 2] = toByte255(p[i + 2] * k)
            out[i + 3] = toByte255(a)
            i += 4
        }
        return out
    }

    /**
     * The same picture, left PREMULTIPLIED in 0..1 floats — for compositing onward, and for tests
     * that care about the arithmetic rather than about the bytes.
     */
    fun renderPremultiplied(
        doc: JbDocument,
        tiles: TileSource,
        rect: RectPx,
        frameId: String?,
        paper: String?,
    ): FloatArray {
        requireSize(rect)
        val px = FloatArray(rect.w * rect.h * 4)
        if (rect.w == 0 || rect.h == 0) return px

        // Paper is a SETTING under the art, so it is the floor of the stack and not a layer: it
        // is laid down first and the layers blend over it exactly as the GPU blends over a clear
        // colour. Absent paper, the floor stays all zeroes, which is transparent.
        val backdrop = parsePaper(paper)
        if (backdrop != null) {
            var i = 0
            while (i < px.size) {
                px[i] = backdrop[0]
                px[i + 1] = backdrop[1]
                px[i + 2] = backdrop[2]
                px[i + 3] = 1f
                i += 4
            }
        }

        // One scratch pixel for the whole image: a source, the running result, and the result of
        // one blend. Allocated here rather than per pixel so a large region does not allocate a
        // quarter of a million short-lived arrays.
        val s = FloatArray(4)
        val d = FloatArray(4)
        val o = FloatArray(4)

        // Tiles are walked, not pixels, so each tile is fetched once per layer rather than once
        // per row. This is also what makes a region crossing four tile seams cost four fetches.
        val tx0 = tileOf(rect.x)
        val tx1 = tileOf(rect.x + rect.w - 1)
        val ty0 = tileOf(rect.y)
        val ty1 = tileOf(rect.y + rect.h - 1)
        val xLast = rect.x + rect.w - 1
        val yLast = rect.y + rect.h - 1

        for (layer in doc.layers) {
            if (!layer.visible) continue
            // The frame-to-cel rule belongs to DocOps; a layer with no cel on this frame (a static
            // layer asked for an animation frame, or a mapping the document has lost) shows nothing.
            val cel = DocOps.celFor(layer, frameId) ?: continue
            val opacity = opacityOf(layer)
            if (opacity <= 0f) continue
            val mode = layer.blend

            for (ty in ty0..ty1) {
                val ry0 = maxOf(ty * TILE_SIZE, rect.y)
                val ry1 = minOf(ty * TILE_SIZE + TILE_SIZE - 1, yLast)
                for (tx in tx0..tx1) {
                    val bytes = tiles.tile(layer.id, cel.id, tx, ty) ?: continue
                    require(bytes.size == TILE_BYTES) {
                        "tile $tx,$ty of layer \"${layer.id}\" cel \"${cel.id}\" is ${bytes.size} bytes, not $TILE_BYTES"
                    }
                    val rx0 = maxOf(tx * TILE_SIZE, rect.x)
                    val rx1 = minOf(tx * TILE_SIZE + TILE_SIZE - 1, xLast)
                    for (y in ry0..ry1) {
                        val srcRow = (y - ty * TILE_SIZE) * TILE_SIZE * 4
                        val dstRow = (y - rect.y) * rect.w * 4
                        for (x in rx0..rx1) {
                            val si = srcRow + (x - tx * TILE_SIZE) * 4
                            val di = dstRow + (x - rect.x) * 4
                            // ALL FOUR channels, not just the alpha. Opacity belongs to the whole
                            // premultiplied pixel: scaling only the alpha leaves a pixel whose colour
                            // is brighter than its own alpha, which un-premultiplies to something
                            // above 1.0 — a white layer at half opacity becomes a "colour" of 2.0,
                            // and MULTIPLY by white stops being a no-op. `jb_tile.frag` scales the
                            // whole texel, and so does this.
                            s[0] = unit(bytes, si) * opacity
                            s[1] = unit(bytes, si + 1) * opacity
                            s[2] = unit(bytes, si + 2) * opacity
                            s[3] = unit(bytes, si + 3) * opacity
                            d[0] = px[di]
                            d[1] = px[di + 1]
                            d[2] = px[di + 2]
                            d[3] = px[di + 3]
                            Blend.apply(mode, s, d, o)
                            px[di] = o[0]
                            px[di + 1] = o[1]
                            px[di + 2] = o[2]
                            px[di + 3] = o[3]
                        }
                    }
                }
            }
        }
        return px
    }

    private fun requireSize(rect: RectPx) {
        require(rect.w >= 0 && rect.h >= 0) { "a region cannot be ${rect.w} by ${rect.h}" }
    }

    /** Which tile a document pixel is in. The canvas is unbounded, so this floors and stays negative. */
    private fun tileOf(px: Int): Int = floor(px.toDouble() / TILE_SIZE).toInt()

    /** One premultiplied byte as a 0..1 float. Bytes are unsigned; `toInt() and 0xFF` says so. */
    private fun unit(bytes: ByteArray, i: Int): Float = (bytes[i].toInt() and 0xFF) / 255f

    /**
     * A 0..1 float as a byte. Clamped, because ADD can legitimately leave a premultiplied colour
     * brighter than its own alpha and an un-clamped `toByte` would wrap it to a dark pixel instead
     * of the white one the GPU shows.
     */
    private fun toByte255(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    /**
     * A layer's opacity, made safe. `coerceIn` would pass a NaN straight through (NaN fails both of
     * its comparisons), and one NaN opacity would turn the whole region into NaN pixels rather than
     * into an invisible layer, which is what a document that means "0%" is asking for.
     */
    private fun opacityOf(layer: Layer): Float {
        val o = layer.opacity
        if (o.isNaN()) return 0f
        if (o < 0f) return 0f
        if (o > 1f) return 1f
        return o
    }

    /**
     * `#RRGGBB` as three 0..1 channels, or null for no paper. Alpha is always 1 — paper is a
     * backdrop, never a translucent one. A colour this cannot read is refused rather than guessed
     * at, because guessing here would silently export a black background.
     */
    private fun parsePaper(paper: String?): FloatArray? {
        if (paper == null) return null
        require(PAPER_COLOR.matches(paper)) { "paper \"$paper\" is not a #RRGGBB colour" }
        return floatArrayOf(
            paper.substring(1, 3).toInt(16) / 255f,
            paper.substring(3, 5).toInt(16) / 255f,
            paper.substring(5, 7).toInt(16) / 255f,
        )
    }
}
