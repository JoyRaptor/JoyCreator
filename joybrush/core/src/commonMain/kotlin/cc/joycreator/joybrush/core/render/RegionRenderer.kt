package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.Cel
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

/**
 * The largest block of textured paper asked for in one call: 256 x 32 pixels, so 32 KiB of
 * straight RGBA8 and nothing like a second region.
 *
 * WHY A BOUNDED BLOCK RATHER THAN THE WHOLE REGION. Paper used to reach an export as one
 * `rect.w * rect.h * 4` array composited AFTER the layer stack, which is both a second full-region
 * buffer held alive beside the composite and the wrong arithmetic — see [render]'s paper paragraph.
 * Laying the paper down as the floor of the stack in bounded blocks removes the second buffer, and
 * the bound is what keeps the temporary cost constant: a 4K board is 1012 calls of 32 KiB, never one
 * call of 33 MiB. It is also the honest number to put in the 160 MiB claim above, which is about the
 * REGION, not about the paper.
 *
 * The shape is wide-and-short on purpose. 256 is the tile edge, so a block never straddles more
 * tile seams than it has to; 32 rows is small enough that a phone rasterises it without a visible
 * pause, and the pair is what `RegionRendererTest` asserts, so raising either one is a red test
 * rather than a silent memory regression.
 */
const val PAPER_BLOCK_W = 256
const val PAPER_BLOCK_H = 32

/** [MAX_REGION_PX] in mebibytes, for the refusal message. Derived, so it cannot contradict it. */
private val MAX_REGION_PEAK_MIB = MAX_REGION_PX * BYTES_PER_PX / (1024L * 1024L)

/**
 * Flattens any rectangle of a document to pixels: "the picture in this box, at this frame, with or
 * without paper". Every export (PNG, OpenRaster, GIF, video, sprite sheet, thumbnail) goes through
 * here, so they cannot disagree with each other.
 *
 * WHAT IT AGREES WITH. Paper is a `glClearColor` backdrop rather than a layer —
 * `GlPaintEngine.draw` clears to it and composites over it — so a region no layer covers is
 * TRANSPARENT, not black, unless paper was asked for, and the phone agrees about that. A TEXTURED
 * paper (JB-9.06b) is the same backdrop in a different form: it arrives as a callback, it is laid
 * down in exactly this place as the floor of the stack, and it is asked for in bounded blocks
 * ([PAPER_BLOCK_W] by [PAPER_BLOCK_H]) rather than as one region-sized array. That is the point of
 * the row. The screen has always composited over paper before the layers; an export that composited
 * a transparent stack and pasted the paper over the top gave MULTIPLY and the other separable modes
 * a different backdrop from the one the artist was looking at.
 *
 * The CPU side implements ALL TWENTY-SEVEN [BlendMode]s: the seven separable ones per channel,
 * [BlendMode.ERASE_BELOW] as destination-out, and the nineteen the Studio's arithmetic brought in
 * under JB-2.20a. SO DOES THE GPU LAYER PATH, SINCE JB-2.20b: when any visible layer is not NORMAL,
 * `GlPaintEngine.draw` builds the stack in an offscreen target and `jb_composite.frag` blends each
 * layer over it with the Studio's own `blendPix` (`jb_blend.glsl`, GENERATED from `BlendModes.java`
 * by `tools/blend-glsl/gen_blend_glsl.sh`, and drift-checked). A stack of NORMAL layers keeps the
 * fixed-function fast path, whose picture is the same by construction.
 *
 * WHAT IS PROVED, AND WHAT IS NOT. Proved: the shader gives the Studio's Java answers, for all 26
 * modes and ERASE_BELOW at seven alpha combinations, 853,524 comparisons through the generated golden
 * table on a real GL driver (`joybrush/tools/blend_gpu_check.js`, worst error 3e-7). Not yet proved:
 * that the PHONE's driver agrees — that is the JB-2.20b owner check, a screenshot of one layer per
 * mode. And the two paths differ by eight-bit rounding (the phone composites in RGBA8 a layer at a
 * time, this class in floats once): at most a few 1/255 on a deep stack, and an exact equality
 * nowhere claimed.
 *
 * ONE NARROWER AGREEMENT THAT PREDATES ALL THAT. The eraser TOOL writes destination-out pixels —
 * `jb_commit.frag` is `dst * (1.0 - a)` on the whole `vec4`, driven by the `u_erase` uniform — so an
 * erased PIXEL comes out the same on screen and in the export, and the grey-smear failure mode is
 * structurally impossible here. That is a brush writing into a tile; compositing an ERASE_BELOW
 * LAYER is the composite pass's own branch.
 *
 * AND THE CLAIM IS TESTED, NOT MERELY WRITTEN DOWN, because a KDoc that lies about wrong pixels is
 * the defect rather than the documentation of it. `theParityClaimNamesExactlyTheModesEachSideHas`
 * in `RegionRendererTest` lists the GPU's modes BY NAME and checks they partition the enum, so a
 * twenty-eighth mode turns the suite red instead of quietly turning this paragraph into a lie.
 *
 * WHICH CEL. Legacy layers use [DocOps.celFor]. Region layers use [RegionTileSource], which
 * assembles exact board slices using the shared paint plan. A requested frame overrides only
 * its owning board; other boards keep their saved cursors.
 *
 * A LOCKED layer still renders. Locking stops a person editing a layer; it is not an instruction to
 * hide the layer, and treating it as one would make a locked layer vanish from exports while
 * remaining plainly visible on the phone.
 *
 * Coordinates are document pixels and may be negative — the canvas is unbounded. A region must fit
 * inside [MAX_REGION_PX], and that is a MEMORY budget, not a restatement of Int range: a declared
 * size is a wish, `DocOps.validate` calls any `w > 0, h > 0` board rect a valid document, and the
 * rect handed to [render] is not validated at all, so a region can be perfectly legal and still be
 * impossible to allocate. There are four refusals and they are four different facts: a region of
 * nothing (w or h of 0) returns an empty image rather than throwing, because a board of no size is
 * a legal thing to ask about; a NEGATIVE size throws [IllegalArgumentException], because that is a
 * caller bug and silently returning nothing would hide it; a region too large to allocate throws
 * [RegionException], which is a property of the request rather than a bug in it; and a [paper]
 * this renderer cannot read throws [IllegalArgumentException] too, because a document whose paper
 * is not a colour is a document nobody wants to export and guessing one would silently paint a
 * black background. Both doors check both, so a guard cannot be walked around by calling the other.
 * A TEXTURED paper adds refusals of the same kind and the same placement — two backdrops at once, a
 * block of the wrong length, a block that is not opaque — and they are checked BEFORE the region is
 * allocated and before the first block is asked for, so an impossible request never costs a decode.
 *
 * THE DOCUMENT ITSELF IS NOT VALIDATED, and that is the caller's half of the deal. These functions
 * read [doc] as given: they do not call [DocOps.validate], they do not check that a cel exists, and
 * they do not check that the layer is one this renderer can blend (it can — see the claim above).
 * An exporter's job is to validate the document at OPEN time, once, where there is a person to
 * tell; a renderer's job is to refuse loudly the specific requests it cannot answer, which is what
 * the four above are. A renderer that validated the whole document on every call would make every
 * export pay for a check that belongs to the door before it.
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
     * [paperRenderer] is the TEXTURED form of the same idea, for a paper that is a picture rather
     * than a colour (JB-9.06b). It is a function rather than an array precisely so that it need not
     * be one: this renderer asks it for one [PAPER_BLOCK_W] x [PAPER_BLOCK_H] block at a time, in
     * DOCUMENT coordinates, and lays those down as the floor of the stack before the first layer —
     * so a MULTIPLY layer multiplies the paper, exactly as it does on screen, instead of multiplying
     * transparency and having the paper pasted over the result afterwards.
     *
     * OPAQUE IS A REQUIREMENT OF THE CALLBACK, NOT A PROMISE ABOUT THE OUTPUT. Paper arrives alpha
     * 255; the picture that leaves may still be translucent, because ERASE_BELOW is destination-out
     * and takes the paper's alpha with it exactly as it takes a flat backdrop's. That is the same
     * arithmetic the flat [paper] path has always done, and a test that demanded a fully opaque
     * export would be demanding that ERASE_BELOW stop working.
     *
     * @param paperRenderer returns STRAIGHT opaque RGBA8 for the exact [RectPx] it is handed, row 0
     *   = that rectangle's top document row. Every returned shape and alpha is checked rather than
     *   padded or truncated, and a block that fails is a refusal, not a partial region.
     * @return STRAIGHT (un-premultiplied) RGBA8, `rect.w * rect.h * 4` bytes, row 0 = top. The
     *   straight form is what every exporter writes, so the un-premultiply happens here, once,
     *   instead of in each of the five exporters that would otherwise each get it slightly wrong.
     * @throws IllegalArgumentException if a side is negative, which is a caller bug, if [paper]
     *   is neither null nor a `#RRGGBB` colour, if [paper] and [paperRenderer] are both given —
     *   two backdrops is one too many and silently preferring either would export a paper the caller
     *   did not ask for — or if a block comes back the wrong length or not opaque. All of these are
     *   decided before the [ByteArray] is allocated, because a 4K export is 160 MiB of scratch to
     *   spend on a request that is going to be refused anyway.
     * @throws RegionException if the region holds more than [MAX_REGION_PX] pixels. This is the
     *   refusal an exporter shows instead of dying: every exporter calls this, so every exporter
     *   can catch this one type and say what it wanted in a sentence. The [paperRenderer] is NOT
     *   called for a refused region: the guard is cheaper than the question, and a caller whose
     *   region is impossible has not earned a texture decode.
     */
    fun render(
        doc: JbDocument,
        tiles: TileSource,
        rect: RectPx,
        frameId: String?,
        paper: String?,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
    ): ByteArray {
        requireSize(rect)
        requirePaperIfAny(paper)
        requireOneBackdrop(paper, paperRenderer)
        val out = ByteArray(rect.w * rect.h * 4)
        if (rect.w == 0 || rect.h == 0) return out
        val p = renderPremultiplied(doc, tiles, rect, frameId, paper, paperRenderer)
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
     * @param paperRenderer see [render]. The blocks are laid down here, under the layers, which is
     *   the whole of JB-9.06b: the floor of the stack is decided once, before the first layer is
     *   read, so no blend mode can see a different backdrop here than on the screen.
     * @throws IllegalArgumentException if a side is negative, which is a caller bug, if [paper] is
     *   neither null nor a `#RRGGBB` colour, if both [paper] and [paperRenderer] are given, or if a
     *   block comes back the wrong length or not opaque.
     * @throws RegionException if the region holds more than [MAX_REGION_PX] pixels. The same
     *   refusal as [render] and for the same reason: this door allocates from the same rect, so a
     *   guard on one door and not the other would be a guard that can be walked around. The
     *   [paperRenderer] is not called for a refused region here either.
     */
    fun renderPremultiplied(
        doc: JbDocument,
        tiles: TileSource,
        rect: RectPx,
        frameId: String?,
        paper: String?,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
    ): FloatArray {
        requireSize(rect)
        requirePaperIfAny(paper)
        requireOneBackdrop(paper, paperRenderer)
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
        } else if (paperRenderer != null) {
            // AFTER the allocation and after every guard above, which is the ordering the whole row
            // turns on: a refused or empty region must cost a sentence, not a texture decode.
            layPaperBlocks(px, rect, paperRenderer)
        }

        // One scratch pixel for the whole image: a source, the running result, and the result of
        // one blend. Allocated here rather than per pixel so a large region does not allocate a
        // quarter of a million short-lived arrays.
        val projectedTiles = RegionTileSource(doc, tiles, frameId)
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

        for ((index, layer) in doc.layers.withIndex()) {
            if (!layer.visible) continue
            // The frame-to-cel rule belongs to DocOps; a layer with no cel on this frame (a static
            // layer asked for an animation frame, or a mapping the document has lost) shows nothing.
            val cel = renderCel(layer, frameId) ?: continue
            val opacity = opacityOf(layer)
            if (opacity <= 0f) continue
            val mode = layer.blend
            // JB-2.23: the mask and the clip base, by LayerMask's rules (the GPU asks the same object). A clipped layer
            // whose base is hidden, or has no cel on this frame, shows nothing — the base is its shape.
            val mask = layer.mask
            val baseIndex = LayerMask.clipBaseOf(index, doc.layers)
            val base = baseIndex?.let { doc.layers[it] }
            if (base != null && !base.visible) continue
            val baseCel = base?.let { renderCel(it, frameId) }
            if (base != null && baseCel == null) continue
            val baseMask = base?.mask

            for (ty in ty0..ty1) {
                val ry0 = maxOf(ty * TILE_SIZE, rect.y)
                val ry1 = minOf(ty * TILE_SIZE + TILE_SIZE - 1, yLast)
                for (tx in tx0..tx1) {
                    val bytes = projectedTiles.tile(layer.id, cel.id, tx, ty) ?: continue
                    require(bytes.size == TILE_BYTES) {
                        "tile $tx,$ty of layer \"${layer.id}\" cel \"${cel.id}\" is ${bytes.size} bytes, not $TILE_BYTES"
                    }
                    val maskTile = mask?.let { m -> projectedTiles.tile(layer.id, m.id, tx, ty)?.also { requireTile(it, layer.id, m.id, tx, ty) } }
                    // Where the clip base has no tile, a clipped layer shows nothing there.
                    val baseTile = if (base == null) null else (projectedTiles.tile(base.id, baseCel!!.id, tx, ty) ?: continue)
                    baseTile?.let { requireTile(it, base!!.id, baseCel!!.id, tx, ty) }
                    val baseMaskTile = baseMask?.let { m -> projectedTiles.tile(base!!.id, m.id, tx, ty)?.also { requireTile(it, base.id, m.id, tx, ty) } }
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
                            // Mask and clip first, then opacity, then the blend (JB-2.23 Decision 6: the Photoshop order).
                            var k = opacity * LayerMask.coverage(maskTile, si)
                            if (base != null) k *= LayerMask.clipAlpha(baseTile, baseMaskTile, si)
                            s[0] = unit(bytes, si) * k
                            s[1] = unit(bytes, si + 1) * k
                            s[2] = unit(bytes, si + 2) * k
                            s[3] = unit(bytes, si + 3) * k
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
    private fun renderCel(layer: Layer, frameId: String?): Cel? =
        if (layer.regions.isEmpty()) DocOps.celFor(layer, frameId)
        else layer.cels.firstOrNull { it.id == layer.sharedCelId }

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

    private fun requireTile(bytes: ByteArray, layerId: String, celId: String, tx: Int, ty: Int) {
        require(bytes.size == TILE_BYTES) { "tile $tx,$ty of layer \"$layerId\" cel \"$celId\" is ${bytes.size} bytes, not $TILE_BYTES" }
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
     * Two backdrops is one too many, and the refusal is deliberate rather than a convenience.
     *
     * A caller that passes both has said two contradictory things — "the paper is this colour" and
     * "the paper is whatever that function returns" — and there is no tie-break that is not a guess:
     * preferring the colour silently drops a texture somebody spent an asset pipeline on, and
     * preferring the renderer silently exports a flat sheet for a document that is textured. Either
     * would be a wrong file that looks fine, which is why this is a refusal with a sentence in it
     * rather than a precedence rule. The exporters pick one and say which: a textured paper goes in
     * as a renderer and a flat one as a colour, never both.
     */
    private fun requireOneBackdrop(paper: String?, paperRenderer: ((RectPx) -> ByteArray)?) {
        require(paper == null || paperRenderer == null) {
            "paper was given both as a colour and as a renderer; pass one, because two backdrops is " +
                "one too many and there is no honest way to choose between them"
        }
    }

    /**
     * Fills the floor of the stack with textured paper, one bounded block at a time.
     *
     * DOCUMENT COORDINATES, EXACTLY, is the part that is not negotiable. Each block is asked for as
     * a [RectPx] in the same space as [rect] itself, so a paper renderer that tiles by
     * `x mod width` samples the same paper for the same document pixel whether it is answering a
     * block, a whole board or a strip — and a board at a negative origin (the canvas is unbounded)
     * gets its paper shifted with it rather than pinned to zero. Passing region-relative
     * coordinates here instead would make every board below or left of the origin show the wrong
     * part of the sheet, and would do it only on exports, which is the worst possible place to be
     * wrong quietly.
     *
     * BLOCKS TILE THE RECT EXACTLY, with no overlap and no gap, and the last block in each
     * direction is short rather than clipped — the renderer is handed a rectangle it can honour
     * exactly, so there is no seam for a rounding difference to open. Walking the short edge
     * (`minOf`) rather than padding is also what keeps the last call honest: a renderer that
     * answers a 256-wide request with 256 columns of data for a 17-wide block would otherwise be
     * truncated silently here.
     *
     * The straight RGBA8 is written into the premultiplied float array directly, and that is not a
     * shortcut: [requirePaperBlock] has already refused any block with an alpha below 255, so
     * premultiplying by alpha is multiplying by exactly 1. The un-premultiply on the way out is
     * therefore the identity for paper, and a paper pixel comes back out the byte it went in as.
     */
    private fun layPaperBlocks(px: FloatArray, rect: RectPx, paperRenderer: (RectPx) -> ByteArray) {
        val xEnd = rect.x + rect.w
        val yEnd = rect.y + rect.h
        var by = rect.y
        while (by < yEnd) {
            val bh = minOf(PAPER_BLOCK_H, yEnd - by)
            var bx = rect.x
            while (bx < xEnd) {
                val bw = minOf(PAPER_BLOCK_W, xEnd - bx)
                val block = RectPx(bx, by, bw, bh)
                val bytes = paperRenderer(block)
                requirePaperBlock(bytes, block)
                // Row by row, because a block is bw wide and the region is rect.w wide: copying the
                // block as one run would lay every row down at the region's row stride and shear
                // the paper. The seam between two blocks is therefore an ordinary row boundary.
                var row = 0
                while (row < bh) {
                    val from = row * bw * 4
                    val to = ((by + row - rect.y) * rect.w + (bx - rect.x)) * 4
                    var i = 0
                    val run = bw * 4
                    while (i < run) {
                        px[to + i] = unit(bytes, from + i)
                        i++
                    }
                    row++
                }
                bx += bw
            }
            by += bh
        }
    }

    /**
     * One block's contract, checked rather than assumed: exactly the bytes asked for, and opaque.
     *
     * THE LENGTH IS NOT PADDED AND NOT TRUNCATED. A renderer that answers 32 KiB for a 17x32 block
     * has either misread the rectangle or ignored it, and both are worth a sentence at the call
     * site: truncating would put a band of the wrong paper along one edge, and padding would put a
     * band of nothing there, and neither would fail a test that only looked at the middle.
     *
     * THE ALPHA IS REFUSED, NOT COMPOSED, because a translucent "paper" is a contradiction this API
     * cannot honour. Laying one down would mean a Multiply layer blending against a partly see-
     * through floor, and the honest reading of that floor is a question for the caller, not a guess
     * here. It is worth being precise about what this does NOT claim: the refused alpha is the
     * BLOCK's, and the finished picture may still be translucent afterwards, because ERASE_BELOW
     * removes the very alpha this check insisted on. The check is about the input, never the output.
     */
    private fun requirePaperBlock(bytes: ByteArray, block: RectPx) {
        val want = block.w * block.h * 4
        require(bytes.size == want) {
            "the paper renderer answered ${bytes.size} bytes for a ${block.w} by ${block.h} block at " +
                "${block.x},${block.y}, which is $want; paper blocks are never padded or truncated"
        }
        var i = 3
        while (i < bytes.size) {
            val pixel = i / 4
            val x = block.x + pixel % block.w
            val y = block.y + pixel / block.w
            require((bytes[i].toInt() and 0xFF) == 255) {
                "the paper renderer answered alpha ${bytes[i].toInt() and 0xFF} at document pixel " +
                    "$x,$y in a ${block.w} by ${block.h} block; paper is a backdrop and is always opaque"
            }
            i += 4
        }
    }

    /**
     * `#RRGGBB` as three 0..1 channels, or null for no paper. Alpha is always 1 — paper is a
     * backdrop, never a translucent one.
     */
    private fun parsePaper(paper: String?): FloatArray? {
        if (paper == null) return null
        requirePaperIfAny(paper)
        return floatArrayOf(
            paper.substring(1, 3).toInt(16) / 255f,
            paper.substring(3, 5).toInt(16) / 255f,
            paper.substring(5, 7).toInt(16) / 255f,
        )
    }

    /**
     * The paper precondition, as its own door rather than a line inside [parsePaper].
     *
     * Both public doors call this, so the precondition is stated at the REQUEST rather than buried
     * in a helper that only one of them happens to reach, and so a bad paper is refused BEFORE the
     * allocation rather than after — a 4K export is 160 MiB of scratch to spend on a request that
     * is going to be refused anyway, and paying it first is the difference between a sentence and
     * a stall. It is a separate function only because there are three callers: [render]'s, this
     * one's and [parsePaper]'s. One function means one answer; a second copy of the regex would be
     * a second opinion about what a colour is, and this project's own history is a case where two
     * lists of the same fact drifted.
     *
     * A colour this cannot read is REFUSED, not defaulted, and the reason is worth writing down:
     * guessing here would silently export a black background, which is the one answer nobody can
     * mistake for a bug and therefore the one that reaches a person as "the export is just black".
     * [doc]'s own `paper.color` is not validated either — the caller passes a validated document,
     * and this checks only the string it was actually handed.
     */
    private fun requirePaperIfAny(paper: String?) {
        if (paper == null) return
        require(PAPER_COLOR.matches(paper)) { "paper \"$paper\" is not a #RRGGBB colour" }
    }
}
