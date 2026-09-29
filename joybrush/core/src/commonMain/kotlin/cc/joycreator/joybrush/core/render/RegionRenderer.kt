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
 * A region this renderer will not try to allocate.
 *
 * An [Exception] and NOT an [Error], and that is the contract rather than an accident. This is a
 * checkable fact about the request — a person can be told "that region is 900,000,000 pixels, the
 * most is 8,388,608" in one sentence and act on it — so it belongs in the same `catch` as
 * [cc.joycreator.joybrush.core.doc.DocException] and `JbArchiveException`. The [OutOfMemoryError]
 * that a 20,000-square region used to raise is a different animal: catching it would leave a
 * half-written export and a confused program, so it is PREVENTED by [MAX_REGION_PX] and never
 * caught. JB-0.08a draws the same line for the same reason, and the rule behind both is the same
 * one — never catch what you should have prevented.
 */
class RegionException(message: String) : Exception(message)

/**
 * The most pixels one region may hold, which is therefore the most any exporter can ask for in one
 * call. Every export funnels through [RegionRenderer], so this number is the export path's appetite.
 *
 * A DECLARED SIZE IS A WISH. `DocOps.validate` accepts any board rect with `w > 0, h > 0`, and the
 * rect handed to [RegionRenderer.render] is not validated at all, so a 30,000-square board is a
 * perfectly VALID document that cannot be allocated. JB-0.08a already refuses an archive entry
 * that merely *declares* four gigabytes; this is the same posture applied to a rect, which is the
 * one number every exporter trusts.
 *
 * WHY 2^23, ASSUMING A GALAXY NOTE 9 — the ruled performance floor (Snapdragon 845, 6 GB,
 * Android 8). One render holds the result and the float scratch at the same time, so the live
 * footprint is 20 bytes per pixel: 4 for the returned RGBA8 and 16 for the premultiplied
 * `FloatArray`.
 *
 *     8,388,608 px x 20 B = 167,772,160 B = exactly 160 MiB
 *
 * 160 MiB is about three fifths of the 256 MB per-app heap a device of that class reports to
 * `ActivityManager.getMemoryClass()`, which leaves room for whatever the exporter is holding at the
 * same moment — a PNG encoder's own buffers, say. The power of two is not decoration: it makes every
 * size below land on a round number of mebibytes, so the claim is arithmetic rather than an
 * estimate, and the megabyte figure in the refusal message is computed from this constant rather
 * than typed beside it, so the two cannot drift apart.
 *
 * IT DOES NOT REFUSE A REAL EXPORT. 3840 x 2160 is 8,294,400 px — 94,208 under the cap — so a 4K
 * board, the largest thing this app is realistically asked to export, goes through untouched. A
 * bigger region is not lost: an exporter is expected to render it in strips and stitch, or to say
 * plainly why it will not. There is a test for both halves of that sentence, so a future editor
 * who lowers this number breaks a test instead of shipping a regression.
 *
 * EVERY SIZE DERIVED FROM THE RECT IS BOUNDED BY THIS, and the constant is chosen so that all of
 * them fit an [Int] as well — every one is a length and an index, and an index that does not fit
 * is a wrap:
 *
 *     pixels   8,388,608            (this constant)
 *     result   8,388,608 x  4 B  =  33,554,432 B   (ByteArray in `render`)
 *     scratch  8,388,608 x 16 B  = 134,217,728 B   (FloatArray in `renderPremultiplied`)
 *
 * The scratch is the largest, so one check on the pixel count bounds the other two — which is why
 * [RegionRenderer] guards `w * h` and not each array separately. Both derived sizes are asserted
 * against [Int.MAX_VALUE] by a test, so raising this constant past that point is a red test rather
 * than a silent wrap.
 *
 * LEAD RULING PENDING (see JB-2.13a `## Questions`): the number is defensible against a Note 9, but
 * the implementer picked it, not the Lead, and it is a product decision in disguise — a board larger
 * than 2^23 pixels cannot be exported in one call.
 */
const val MAX_REGION_PX = 8_388_608L

/** The live footprint of one [RegionRenderer.render] per pixel: 4 result bytes + 16 scratch bytes. */
private const val BYTES_PER_PX = 20L

/** [MAX_REGION_PX] in mebibytes, for the refusal message. Derived, so it cannot contradict it. */
private val MAX_REGION_PEAK_MIB = MAX_REGION_PX * BYTES_PER_PX / (1024L * 1024L)

/**
 * Flattens any rectangle of a document to pixels: "the picture in this box, at this frame, with or
 * without paper". Every export (PNG, OpenRaster, GIF, video, sprite sheet, thumbnail) goes through
 * here, so they cannot disagree with each other.
 *
 * WHAT IT AGREES WITH — AND EXACTLY HOW MUCH, because the honest answer is "three things". Paper
 * is a `glClearColor` backdrop rather than a layer, so a region nothing covers is TRANSPARENT, not
 * black, unless paper was asked for. NORMAL is source-over, which is what the GPU does:
 * `jb_tile.frag:13` emits `texture(u_layer) * u_layerOpacity` into a blend func of
 * `(ONE, ONE_MINUS_SRC_ALPHA)` that is set ONCE, before the layer loop, at `GlPaintEngine.kt:332`.
 * And an erase multiplies the backdrop by one minus the source alpha on all four channels
 * (`jb_commit.frag:21`).
 *
 * THE OTHER SIX MODES DO NOT AGREE WITH THE GPU, BECAUSE THE GPU DOES NOT HAVE THEM. There is no
 * mode uniform, no mode branch and no second program — `GlPaintEngine` sets that one blend func and
 * never changes it — so MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN and LIGHTEN composite source-over ON
 * THE PHONE while this file composites them per W3C. That is a different picture, not a rounding
 * difference: a person with a MULTIPLY layer sees one thing on screen and gets another in the
 * exported file. It has bitten nobody yet only because nothing in `commonMain` can currently
 * produce a non-NORMAL layer; JB-2.04 — the task that puts layers in the view — is where it
 * starts. Until the GPU has the modes, this file is the specification and the screen is the
 * approximation. The arithmetic itself is [Blend].
 *
 * WHICH CEL. Not derived here: [DocOps.celFor] owns the frame-to-cel rule, so a renderer can never
 * grow its own, slightly different, idea of which cel a frame shows.
 *
 * A LOCKED layer still renders. Locking stops a person editing a layer; it is not an instruction to
 * hide the layer, and treating it as one would make a locked layer vanish from exports while
 * remaining plainly visible on the phone.
 *
 * Coordinates are document pixels and may be negative — the canvas is unbounded. A region must fit
 * inside [MAX_REGION_PX], and that is a MEMORY budget, not a restatement of Int range: a declared
 * size is a wish, `DocOps.validate` calls any `w > 0, h > 0` board rect a valid document, and the
 * rect handed to [render] is not validated at all, so a region can be perfectly legal and still be
 * impossible to allocate. There are three refusals and they are three different facts: a region of
 * nothing (w or h of 0) returns an empty image rather than throwing, because a board of no size is
 * a legal thing to ask about; a NEGATIVE size throws [IllegalArgumentException], because that is a
 * caller bug and silently returning nothing would hide it; and a region too large to allocate
 * throws [RegionException], which is a property of the request rather than a bug in it.
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
     * @throws IllegalArgumentException if a side is negative, which is a caller bug.
     * @throws RegionException if the region holds more than [MAX_REGION_PX] pixels. This is the
     *   refusal an exporter shows instead of dying: every exporter calls this, so every exporter
     *   can catch this one type and say what it wanted in a sentence.
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
     *
     * @throws IllegalArgumentException if a side is negative, which is a caller bug.
     * @throws RegionException if the region holds more than [MAX_REGION_PX] pixels. The same
     *   refusal as [render] and for the same reason: this door allocates from the same rect, so a
     *   guard on one door and not the other would be a guard that can be walked around.
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

    /**
     * A region has to be renderable, and there are three different ways to fail that. They are kept
     * apart on purpose, because they are three different facts about the request.
     *
     * A NEGATIVE size is a caller bug and stays an [IllegalArgumentException], exactly as it always
     * was — a silent empty image would hide it. A size this renderer will not ALLOCATE is not a bug
     * at all: a 30,000-square board is a valid document that `DocOps.validate` passes, so it gets its
     * own [RegionException] and a sentence rather than a stack trace.
     *
     * THE MULTIPLICATION IS IN [Long] ON PURPOSE, and that one line is the whole of the wrap fix. In
     * [Int], 30,000 x 30,000 is 900,000,000 pixels, which is fine, and 900,000,000 x 4 is
     * 3,600,000,000, which is not: two's complement wraps it to -694,967,296, and `ByteArray`
     * answers `NegativeArraySizeException` — unchecked, undocumented, thrown from the one function
     * every exporter in the app calls. The wrap is the CRASH. The budget is what stops the cases
     * that do not wrap but cannot be allocated either: 20,000 x 20,000 is 400,000,000 px, a 1.6 GB
     * result and a 6.4 GB scratch — 8.0 GB of them together — and no phone has that much.
     *
     * ONE CHECK BOUNDS BOTH ARRAYS. The result is `px x 4` and the scratch is `px x 16`, and
     * [MAX_REGION_PX] is set so the larger of those already fits an [Int]; checking the pixel count
     * therefore bounds every size derived from the rect, and a per-array check would be a second
     * copy of the same arithmetic to keep in step. `RegionRendererTest` asserts all three sizes
     * against the constant, which is where a claim like that belongs — it can be checked in
     * microseconds there instead of by allocating 160 MiB.
     */
    private fun requireSize(rect: RectPx) {
        require(rect.w >= 0 && rect.h >= 0) { "a region cannot be ${rect.w} by ${rect.h}" }
        // Long, not Int — see above. Both sides are widened BEFORE multiplying, so this cannot
        // overflow: the largest product is 2^31 x 2^31 = 2^62, which a Long holds.
        val px = rect.w.toLong() * rect.h.toLong()
        if (px > MAX_REGION_PX) {
            throw RegionException(
                "a ${rect.w} by ${rect.h} region is $px pixels, and the most this renderer will " +
                    "allocate is $MAX_REGION_PX ($MAX_REGION_PEAK_MIB MiB for the result and the " +
                    "float scratch together). Export a smaller area, or a piece of it at a time.",
            )
        }
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
