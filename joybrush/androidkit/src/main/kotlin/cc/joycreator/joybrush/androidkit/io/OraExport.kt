package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.LayerMask
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the `mimetype` entry says, byte for byte, with no trailing newline. */
const val ORA_MIMETYPE = "image/openraster"

/** The OpenRaster version this file writes. 0.0.3 is what Krita writes, and the first that has `composite-op`. */
private const val ORA_VERSION = "0.0.3"

private const val MIMETYPE_NAME = "mimetype"
private const val STACK_NAME = "stack.xml"
private const val MERGED_NAME = "mergedimage.png"
private const val THUMBNAIL_NAME = "Thumbnails/thumbnail.png"
private const val DATA_DIR = "data"

/** The longest side a thumbnail may have. The OpenRaster spec says at most 256. */
private const val THUMBNAIL_MAX_SIDE = 256

private const val PAPER_LAYER_NAME = "Paper"

/** A PNG side is four bytes in IHDR, so neither can be over 65535. The same ceiling `PngWriter` uses. */
private const val MAX_PNG_SIDE = 65535

/** Paper colour is stored as `#RRGGBB` — the format every layer panel already speaks. */
private val PAPER_COLOR = Regex("^#[0-9a-fA-F]{6}$")

/**
 * What an illegal character in a name becomes: U+FFFD REPLACEMENT CHARACTER.
 *
 * U+FFFD is a legal XML character and a NUL is not, so substituting one for the other is what keeps
 * a hostile name from producing a `stack.xml` that is not a document. Spelled as a code point and
 * not as a literal character so that no editor, no encoding and no diff tool can turn it into a
 * question mark on the way here — which is exactly the accident that would make this file's
 * replacement character illegal in the one place it must be legal.
 */
private val REPLACEMENT: Char = 0xFFFD.toChar()

/**
 * A thumbnail's three numbers, together, so a caller cannot pass one size with another's pixels.
 *
 * NOT A `data class`, and that is a decision rather than an oversight: the generated `equals` on a
 * `ByteArray` compares arrays by REFERENCE, so a data class here would say two byte-identical
 * thumbnails are different — which is the exact trap `JbContents` documents and `JbArchiveTest`
 * works around everywhere.
 */
private class Thumb(val w: Int, val h: Int, val pixels: ByteArray)

/**
 * The `.ora` file: the whole drawing, in layers, in a zip that Krita, GIMP and MyPaint all read. One
 * PNG per PAINT layer, one `stack.xml` saying how the layers go together, and nothing in the file
 * that only this app understands.
 *
 * WHY THE LAYERS ARE RENDERED ONE AT A TIME, AT FULL OPACITY, AND THE OPACITY IS IN THE XML. That
 * is the only shape a layered format has. A layer PNG is that layer ALONE, straight alpha, at
 * `opacity 1`, `composite-op="svg:src-over"`; its real opacity and its real blend mode are two
 * numbers in `stack.xml`, and the reading app combines them. So the GLOSS layer in a two-layer
 * document is a file of pure white, and the half-transparent multiply that makes the export look
 * right happens in Krita, not here. Baking either number into the pixels instead would produce a
 * file that LOOKS like a multilayer drawing and is not one: every layer would already be flattened
 * against transparent black, and no reader could put them back in the right order.
 *
 * THE PHONE COMPOSITES ALL TWENTY-SEVEN MODES TOO (JB-2.20b), so this file and the screen now
 * agree about a MULTIPLY layer, to within eight-bit rounding: `RegionRenderer` composites per W3C
 * in floats, and `GlPaintEngine.draw` runs the Studio's `blendPix` on the GPU (see `RegionRenderer`'s
 * KDoc for what is proved and what is still the owner's screenshot). An `.ora` is the file other apps
 * read, so a person who exports a MULTIPLY layer and opens it in Krita sees the painting they made.
 *
 * `ADD` IS THE ONE MODE A W3C READER MAY STILL DISAGREE WITH, AND IT IS THE READER THAT IS
 * DIFFERENT. `svg:plus` is the SVG compositing operator, and it is the only thing in the format that
 * means "add" — but it computes `min(1, sa*Cs + da*Cb)` while `Blend` computes the separable
 * `B = min(1, Cs + Cb)` inside the standard formula. The two agree while the sum does not clamp and
 * both alphas are 1, and part company where it clamps or the backdrop is translucent (two pixels at
 * alpha 0.8 give `Blend` an out-alpha of 0.96 and `svg:plus` an out-alpha of 1.0). `svg:plus` is
 * still the right thing to write: a file that claims ADD is a NORMAL layer would be flatly wrong,
 * and this is a rounding of the right answer rather than a different picture.
 *
 * THE ZIP. This file follows `JbArchive`'s conventions exactly — `mimetype` STORED and first, level
 * 6, `finish()` and never `close()` so the caller's stream stays open, and every refusal arriving as
 * a [JbArchiveException]. It does NOT re-implement `JbArchive`'s zip-slip guard, and the reason is
 * structural rather than lazy: `JbArchive` writes `layers/<layerId>/<celId>/<tileKey>` where two of
 * those are caller strings, so it runs every name through its checks. This writer emits four fixed
 * names plus `data/<n>.png`, where `n` is an `Int` this file counted. There is no caller string in
 * an entry name, so there is nothing to check. (Its byte limits are all on the read path, which
 * this file does not have.) A future change that puts a caller's string into a name must widen
 * `JbArchive.unsafeReason` to `internal` and call it, rather than open-coding a weaker copy.
 *
 * BYTE-FOR-BYTE REPEATABILITY IS NOT CLAIMED, and the only reason is a timestamp: `ZipOutputStream`
 * stamps each entry with the current time. Two exports of the same drawing therefore have the same
 * length, the same entry names in the same order and the same entry bytes, and differ only in those
 * stamps — which is what `JbArchiveTest` asserts for `.joybrush` too, and for the same reason.
 * Forcing a fixed `entry.time` would buy exact bytes at the price of writing a DOS timestamp of
 * zero, which is technically not a valid date and which some strict readers object to.
 *
 * THE BOARD'S ORIGIN IS NORMALISED AWAY, and it has to be. An OpenRaster canvas has its own origin
 * at its top-left corner and every layer is positioned on it with `x` and `y`; there is no
 * document-wide coordinate space for a board at (‑40, ‑15) to live in. Every PNG here is already
 * cropped to the board rect — `RegionRenderer` puts document pixel `rect.x, rect.y` at PNG pixel
 * 0,0 — so `x="0" y="0"` is the only correct pair of numbers and is also what every other writer
 * emits. It is a crop, not a shift: the picture inside the file is byte-identical to the same
 * picture exported from a board whose rect starts at the origin.
 *
 * NOTHING IS DROPPED SILENTLY. A layer that is not in the file is named in a `stack.xml` comment
 * with the reason, because "my layer is gone" is the single worst thing an export can do to
 * somebody. Three kinds are left out, and each is a deliberate act by the person rather than an
 * accident: an INK layer (its strokes are not pixels until JB-5.01 draws them), a hidden layer, and
 * a layer at zero opacity. A PAINT layer that has not been drawn on yet IS exported, as a fully
 * transparent PNG, because the layer exists in the stack and the person is about to draw on it.
 *
 * THE MERGED IMAGE AND THE LAYERS CANNOT DISAGREE, because both come out of [RegionRenderer] over
 * the same rect: `mergedimage.png` is the whole stack in one call with paper as the backdrop, and
 * the Paper LAYER is the same colour at the bottom of the stack at full opacity, which composites
 * to the same pixels. `Thumbnails/thumbnail.png` is a downscale of exactly those merged bytes, so
 * the file's preview, its flattened image and its layers are all one picture.
 *
 * WHAT IS CHECKED BEFORE THE FIRST BYTE IS WRITTEN, so a refused export leaves `out` as it was
 * found rather than half a file long: the board exists, the board has room, the board fits both a
 * PNG and the render budget, and the paper colour is one this renderer can read. That last one is
 * why a `cc.joycreator.joybrush.core.render.RegionException` or an `IllegalArgumentException` from
 * the renderer cannot escape [write] for a request this file has already refused — they are
 * strictly downstream of checks made here, with strictly smaller numbers. The catch-all below
 * exists for the other thing that can go wrong here, which is a stream that dies.
 *
 * NOTE WHAT IS NOT CHECKED: `DocOps.validate`. An export is a request about a BOARD, and a document
 * that fails validation for some unrelated reason — a sprite board with no grid, a document from a
 * newer build — can still be drawn on and still be worth exporting. Every field this file actually
 * reads is range-checked at the point of use (opacity clamped exactly as the renderer clamps it, the
 * paper colour above, the blend modes exhaustively, the cel through [DocOps.celFor]), and nothing
 * else in the document is read at all.
 *
 * MEMORY. The render budget is [MAX_REGION_PX], and this file's own peak on top of it is two full
 * region buffers — the merged render, and the PNG encoder's output — because the layer renders are
 * written and released one at a time and the merged render has to outlive the layers so the
 * thumbnail can be made from it. On a 4K board that is about 33 MB a buffer, against the renderer's
 * own 160 MiB ceiling.
 */
object OraExport {

    /**
     * Writes board [boardId] of [contents] to [out] as an OpenRaster file, for frame [frameId] (null
     * for a static document).
     *
     * [out] is NOT closed: `finish()` is called and the stream is flushed, so the caller decides when
     * it ends. [includePaper] adds a "Paper" layer at the bottom of the stack and puts the paper
     * colour under the flattened image.
     *
     * @throws JbArchiveException for every refusal, so one `catch` at the export button is enough
     *   and a person is shown a sentence rather than a stack trace.
     */
    fun write(
        out: OutputStream,
        contents: JbContents,
        boardId: String,
        frameId: String?,
        includePaper: Boolean,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
        onWarning: (String) -> Unit = {},
    ) {
        val doc = contents.doc
        val rect = boardOf(doc, boardId)
        val paper = if (includePaper && !doc.paper.screenTransparent) checkedPaper(doc) else null
        val tiles = tileSource(contents)
        val renderer = CanvasPng.paperRendererFor(contents, includePaper, paperRenderer, onWarning)

        // Which layers, and which are not. Both decided before a byte is written, so a document that
        // cannot be exported costs nothing to find out.
        val kept = ArrayList<Layer>()
        val omitted = ArrayList<Pair<Layer, String>>()
        for (layer in doc.layers) {
            val why = omittedBecause(layer, frameId)
            if (why == null) kept.add(layer) else omitted.add(layer to why)
        }
        // This format already names omitted vector layers in stack.xml. Keep them out of the
        // flattened raster preview too; the general renderer now refuses unsupported vector art.
        val rasterDoc = doc.copy(layers = doc.layers.map {
            if (it.kind == LayerKind.INK) it.copy(visible = false) else it
        })

        // DOCUMENT ORDER, BOTTOM FIRST — which is what `asReversed` in [stackXml] turns into the
        // top-first order OpenRaster reads, and what a `data/<n>.png` number follows. So `data/0.png`
        // is the bottom layer and the numbers in the XML run downwards while the XML runs upwards.
        //
        // THE PAPER GOES IN AT THE BOTTOM, not at the end of this list. That is the whole reason it
        // is inserted first: an entry added last would be the last line reversed, which is the TOP
        // line — so a document exported with paper would open in Krita with an opaque white sheet
        // sitting ON TOP of the painting, and the merged image would disagree with the layers. The
        // bug is invisible in every test that only looks at one of the two halves, which is why the
        // self-consistency test composites the stack FROM THE XML rather than from this list.
        val entries = ArrayList<Entry>(kept.size + 1)
        if (paper != null) {
            entries.add(
                Entry(
                    name = paperName(kept),
                    src = dataPath(entries.size),
                    opacity = 1f,
                    op = compositeOp(BlendMode.NORMAL),
                    paper = paper,
                    layer = null,
                    doc = doc,
                    tiles = tiles,
                    paperRenderer = renderer,
                ),
            )
        }
        for (layer in kept) {
            entries.add(
                Entry(
                    name = layer.name,
                    src = dataPath(entries.size),
                    opacity = opacityOf(layer),
                    op = compositeOp(layer.blend),
                    paper = null,
                    layer = layer,
                    doc = doc,
                    tiles = tiles,
                ),
            )
        }

        // THE PAPER LAYER'S OWN PNG IS RENDERED HERE, BEFORE THE FIRST BYTE IS WRITTEN, and this is
        // load-bearing rather than tidy. `data/0.png` is a real whole region of paper — a reader
        // opens that file directly — so it is asked for in one call instead of in blocks. Doing it
        // here, above `ZipOutputStream(out)`, means a paper that cannot be produced fails while the
        // caller's stream is still untouched: JB-9.06b's contract is that a renderer failure ABORTS
        // the export and never leaves a partial file, and a `ZipOutputStream` that has already had
        // `mimetype`, `stack.xml` and every layer PNG pushed into it cannot be un-written — the
        // central directory only appears at `finish()`, so a throw after that point leaves a corrupt
        // archive sitting at the path the user chose. One transient region-sized array is the price;
        // it is unreachable by the time `merged` is rendered below.
        //
        // IT IS STILL WRAPPED, because it now happens outside the `try` below and this function's
        // promise is that every refusal arrives as a `JbArchiveException` — one `catch` at the export
        // button has to be enough, and a raw `IllegalArgumentException` from a texture decoder would
        // be an unexplained crash on the way out.
        val paperEntryPixels = try {
            entries.firstOrNull { it.isPaper }?.pixels(rect, frameId)
        } catch (e: JbArchiveException) {
            throw e
        } catch (e: Exception) {
            throw JbArchiveException("the OpenRaster file could not be written: ${brief(e)}")
        }

        val zos = ZipOutputStream(out)
        zos.setLevel(ARCHIVE_DEFLATE_LEVEL)
        try {
            writeMimetype(zos)
            put(zos, STACK_NAME, stackXml(rect, entries, omitted).toByteArray(Charsets.UTF_8))
            for (entry in entries) {
                // The Paper entry reuses the array resolved above rather than asking again, so the
                // renderer is called exactly once for it and a mid-loop failure cannot change the
                // paper between `data/0.png` and `mergedimage.png`.
                val pixels = if (entry.isPaper) paperEntryPixels!! else entry.pixels(rect, frameId)
                put(zos, entry.src, PngWriter.encode(rect.w, rect.h, pixels))
            }
            // Paper is the floor of the stack, not a sheet over it: a MULTIPLY layer in `merged`
            // multiplies this paper exactly as it does on screen, and in Krita, because the Paper
            // LAYER above it is the same pixels at NORMAL and opacity 1. The two halves of the file
            // agreeing is what `theLayersAndTheXmlReproduceTheMergedImage` checks.
            val merged = RegionRenderer.render(
                rasterDoc, tiles, rect, frameId,
                if (renderer == null) paper else null, renderer,
            )
            val thumb = thumbnail(merged, rect.w, rect.h)
            put(zos, THUMBNAIL_NAME, PngWriter.encode(thumb.w, thumb.h, thumb.pixels))
            put(zos, MERGED_NAME, PngWriter.encode(rect.w, rect.h, merged))
            zos.finish()
            zos.flush()
            // close() is deliberately not called: it would close the caller's stream too, and
            // finish() has already written the central directory.
        } catch (e: JbArchiveException) {
            throw e
        } catch (e: Exception) {
            // Real, and not paranoia: this is where a stream that dies half way out arrives.
            throw JbArchiveException("the OpenRaster file could not be written: ${brief(e)}")
        }
    }

    /**
     * One `<layer>` in the stack, and the pixels that go in its `data/<n>.png`.
     *
     * A LAYER entry renders that one layer alone; a PAPER entry fills the board with the paper —
     * the textured renderer's pixels when the document has a material, the flat colour when it does
     * not. Two kinds rather than one kind with a nullable layer, so there is no `!!` in the write
     * path and no way to write the paper's pixels into somebody's layer.
     */
    private class Entry(
        val name: String,
        val src: String,
        val opacity: Float,
        val op: String,
        private val paper: String?,
        private val layer: Layer?,
        private val doc: JbDocument,
        private val tiles: TileSource,
        private val paperRenderer: ((RectPx) -> ByteArray)? = null,
    ) {
        /**
         * Whether this entry's pixels come from the paper rather than from a layer render.
         *
         * A property rather than an exposed [paperRenderer], because the only thing the caller needs
         * to know is WHICH entry is the Paper one: it has to pre-resolve that entry's pixels above the
         * first written byte and then reuse them in the loop. Handing out the renderer as well would
         * invite a second invocation and a second, possibly different, answer.
         */
        val isPaper: Boolean get() = paperRenderer != null
        /**
         * `rect.w * rect.h * 4` bytes of straight RGBA8 for this entry.
         *
         * `paper = null` and always: paper is a LAYER in this file, not a backdrop, so baking it in
         * here would put an opaque floor under every layer and the stack would composite wrong in
         * every reader. `opacity 1` and `NORMAL` for the same reason, in the other direction: they
         * belong in the XML, and this file's only job is to hand the reader the layer's own pixels.
         */
        fun pixels(rect: RectPx, frameId: String?): ByteArray {
            val subject = layer
            if (subject != null) {
                // A mask is the layer's own and is baked in by the renderer. A CLIP needs its base (JB-2.23): OpenRaster has
                // no clipping, so the clip is baked too — the base rides along at opacity 0, which gives the shape and paints
                // nothing, and the layer PNG then agrees with the merged image.
                val index = doc.layers.indexOfFirst { it.id == subject.id }
                val baseIndex = if (index >= 0) LayerMask.clipBaseOf(index, doc.layers) else null
                val own = subject.copy(opacity = 1f, blend = BlendMode.NORMAL)
                val layers = if (baseIndex == null) listOf(own.copy(clip = false))
                else listOf(doc.layers[baseIndex].copy(opacity = 0f, blend = BlendMode.NORMAL, clip = false), own)
                return RegionRenderer.render(doc.copy(layers = layers), tiles, rect, frameId, null)
            }
            // The whole-region arrival goes through [RegionRenderer.requireOpaquePaper] — the SAME check the
            // block path applies — so a translucent or wrong-length paper is refused here, while the
            // caller's stream is still untouched, instead of producing a `data/0.png` that disagrees
            // with the `mergedimage.png` written on the next line.
            val paper = paperRenderer?.invoke(rect)
            if (paper != null) RegionRenderer.requireOpaquePaper(paper, rect)
            return paper ?: paperPixels(rect, requireNotNull(this.paper) { "a Paper entry with no paper colour" })
        }
    }

    // ---------------------------------------------------------------- what goes in the file

    /**
     * The board's rectangle, or a refusal in a sentence.
     *
     * THREE SEPARATE CEILINGS, because they are three different facts and the smallest one is not
     * the interesting one. A board of no room is a document that cannot be drawn on. A side over
     * 65535 cannot be a PNG at all, which the renderer's pixel budget does not notice: 65536 by 1 is
     * one pixel past the cap nowhere near it. And a board over [MAX_REGION_PX] is refused by the
     * renderer itself, with the same number in the same words, a few lines later.
     */
    private fun boardOf(doc: JbDocument, boardId: String): RectPx {
        val board = doc.boards.firstOrNull { it.id == boardId }
            ?: throw JbArchiveException(
                "this document has no board called \"$boardId\" (it has ${doc.boards.size})",
            )
        val rect = board.rect
        if (rect.w < 1 || rect.h < 1) {
            throw JbArchiveException("board \"$boardId\" has no room: it is ${rect.w} by ${rect.h}")
        }
        if (rect.w > MAX_PNG_SIDE || rect.h > MAX_PNG_SIDE) {
            throw JbArchiveException(
                "board \"$boardId\" is ${rect.w} by ${rect.h}, and a PNG side cannot be over $MAX_PNG_SIDE. " +
                    "Export a smaller area, or a piece of it at a time.",
            )
        }
        val px = rect.w.toLong() * rect.h.toLong()
        if (px > MAX_REGION_PX) {
            throw JbArchiveException(
                "board \"$boardId\" is $px pixels, and the most one export will render is $MAX_REGION_PX. " +
                    "Export a smaller area, or a piece of it at a time.",
            )
        }
        return rect
    }

    /**
     * The paper colour, or a refusal. The same `#RRGGBB` the renderer and the layer panel speak, and
     * a bad one is refused here rather than reaching the renderer as an `IllegalArgumentException`
     * about a rectangle.
     */
    private fun checkedPaper(doc: JbDocument): String {
        val color = doc.paper.color
        if (!PAPER_COLOR.matches(color)) {
            throw JbArchiveException("the paper colour \"$color\" is not a #RRGGBB colour, so there is no Paper layer to write")
        }
        return color
    }

    /**
     * Why this layer is not in the file, or null for a layer that is.
     *
     * EACH OF THESE IS SOMEBODY'S DECISION, not a failure, which is why none of them is silent. A
     * hidden layer is hidden on purpose; a layer at 0% is at 0% on purpose; an INK layer is not
     * pixels yet. What is NOT here is "has no tiles": a layer nobody has drawn on is a real layer
     * in the real stack and is exported as a transparent PNG, because the next thing the person does
     * is draw on it.
     */
    private fun omittedBecause(layer: Layer, frameId: String?): String? = when {
        layer.kind != LayerKind.PAINT ->
            "an INK layer; its strokes are drawn by JB-5.01, not exported as pixels yet"
        !layer.visible -> "it is hidden"
        opacityOf(layer) <= 0f -> "it is 0% opaque"
        // The frame-to-cel rule belongs to DocOps, and a layer with no cel for this frame has nothing
        // to render. An animated layer asked for a static export shows nothing on the phone either.
        DocOps.celFor(layer, frameId) == null -> "it has no cel for this frame"
        else -> null
    }

    /** Tiles addressed by (layer, cel, tile), which is what a cel id is unique only WITHIN. */
    private fun tileSource(contents: JbContents) = TileSource { layerId, celId, tx, ty ->
        contents.tiles[Triple(layerId, celId, DocOps.key(tx, ty))]
    }

    /** `data/3.png`, from a count this file did itself. The only number in any entry name. */
    private fun dataPath(n: Int): String = "$DATA_DIR/$n.png"

    /**
     * "Paper", or the first "Paper 2", "Paper 3" that no layer is already called.
     *
     * A duplicate layer name is not illegal in OpenRaster and Krita shows both, so this is a
     * papercut rather than a bug — but a person looking at two rows both reading "Paper" in a file
     * they cannot see the layers of is exactly who this file exists for.
     *
     * THE LOOP CANNOT SPIN. Each turn adds its candidate to `taken`, which already holds every layer
     * name and grows by one each time, so at worst it runs once per existing name and then takes a
     * name nobody has. A document cannot have an infinite number of layers.
     */
    private fun paperName(kept: List<Layer>): String {
        val taken = HashSet<String>()
        for (layer in kept) taken.add(layer.name)
        if (taken.add(PAPER_LAYER_NAME)) return PAPER_LAYER_NAME
        var n = 2
        while (!taken.add("$PAPER_LAYER_NAME $n")) n++
        return "$PAPER_LAYER_NAME $n"
    }

    /** The board filled with one opaque colour, `rect.w * rect.h * 4` bytes, row 0 at the top. */
    private fun paperPixels(rect: RectPx, paper: String): ByteArray {
        val r = paper.substring(1, 3).toInt(16).toByte()
        val g = paper.substring(3, 5).toInt(16).toByte()
        val b = paper.substring(5, 7).toInt(16).toByte()
        val out = ByteArray(rect.w * rect.h * 4)
        // `at` is a multiple of four and the size is a multiple of four, so `at + 3` is always inside
        // and the cursor always moves. There is no `at` here that does not advance.
        var at = 0
        while (at < out.size) {
            out[at] = r
            out[at + 1] = g
            out[at + 2] = b
            out[at + 3] = 0xFF.toByte()
            at += 4
        }
        return out
    }

    // ---------------------------------------------------------------- stack.xml

    /**
     * The `stack.xml` document, as the exact bytes that go in the zip.
     *
     * Layers are listed TOP FIRST, which is the OpenRaster order and the opposite of the order
     * `JbDocument.layers` holds them in. The `data/<n>.png` numbers still ascend with the Joy Brush
     * stack, so the XML's numbers run downwards while its lines run upwards; that is deliberate,
     * because the number is what ties a line to a file in the zip, and the bottom layer being
     * `data/0.png` is the answer a person unzipping the file is looking for. With paper that bottom
     * layer is the Paper layer, so `data/0.png` is the opaque sheet and the art starts at
     * `data/1.png`.
     */
    private fun stackXml(
        rect: RectPx,
        entries: List<Entry>,
        omitted: List<Pair<Layer, String>>,
    ): String {
        val out = StringBuilder(256 + 160 * entries.size)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<image version=\"").append(ORA_VERSION).append("\" w=\"").append(rect.w)
            .append("\" h=\"").append(rect.h).append("\">\n")
        out.append("  <stack>\n")
        for ((layer, why) in omitted) {
            val body = commentText("joybrush: layer \"${layer.name}\" is not in this file: $why")
            out.append("    <!-- ").append(body).append(" -->\n")
        }
        for (entry in entries.asReversed()) {
            out.append("    <layer name=\"").append(attributeText(entry.name))
                .append("\" src=\"").append(entry.src)
                .append("\" x=\"0\" y=\"0\" opacity=\"").append(opacityText(entry.opacity))
                .append("\" visibility=\"visible\" composite-op=\"").append(entry.op)
                .append("\"/>\n")
        }
        out.append("  </stack>\n")
        out.append("</image>\n")
        return out.toString()
    }

    /**
     * One [BlendMode] as the SVG compositing operator that means it, in the `svg:` form OpenRaster
     * uses.
     *
     * Written out BY NAME rather than behind an `else`, so a twenty-eighth blend mode cannot be
     * added without a compiler error here — the same promise the arithmetic in `Blend` keeps, and
     * for the same reason: a mode that quietly became `svg:src-over` would flatten a painting and
     * say nothing. JB-2.20a made that promise fire for real: it appended nineteen modes and this
     * function refused to compile, which is the guard working.
     *
     * `ERASE_BELOW` is `svg:dst-out`, which takes the destination's alpha with it — the whole reason
     * `Blend` cannot express it by swapping a separable function. `ADD` is `svg:plus`; see the class
     * note for the one place a W3C reader and `Blend` still differ.
     *
     * **THE NINE WITH NO SVG EQUIVALENT ARE REFUSED, NOT APPROXIMATED.** SVG has no `linear-burn`,
     * `vivid-light`, `pin-light`, `hard-mix`, `subtract`, `divide`, `darker-color`,
     * `lighter-color` or `linear-light` operator. Mapping any of them onto the nearest relative is
     * the one failure mode this function exists to prevent, and it would be invisible: the .ora would
     * open, look plausible, and be wrong in a way nobody notices until they compare it to the app.
     * So the export stops and says which mode it cannot express.
     *
     * This costs nothing in practice, and that is the point worth recording: `DOC_VERSION` 2 is what
     * introduced these modes, so no document written by an earlier Joy Brush can contain one. The
     * refusal cannot strand anybody's existing work — it can only stop a drawing made since.
     * Referred to the Lead: if ORA consumers turn out to need these nine, the honest route is a
     * `feComposite`/filter-based fallback with its own parity proof, not a nearest-relative guess.
     *
     * @throws IllegalArgumentException naming the mode, when SVG cannot express it.
     */
    private fun compositeOp(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "svg:src-over"
        BlendMode.MULTIPLY -> "svg:multiply"
        BlendMode.SCREEN -> "svg:screen"
        BlendMode.OVERLAY -> "svg:overlay"
        BlendMode.ADD -> "svg:plus"
        BlendMode.DARKEN -> "svg:darken"
        BlendMode.LIGHTEN -> "svg:lighten"
        BlendMode.ERASE_BELOW -> "svg:dst-out"
        // The Studio's modes that SVG's feBlend also names, so the mapping is exact.
        BlendMode.DIFFERENCE -> "svg:difference"
        BlendMode.EXCLUSION -> "svg:exclusion"
        BlendMode.COLOR_DODGE -> "svg:color-dodge"
        BlendMode.COLOR_BURN -> "svg:color-burn"
        BlendMode.HARD_LIGHT -> "svg:hard-light"
        BlendMode.SOFT_LIGHT -> "svg:soft-light"
        BlendMode.HUE -> "svg:hue"
        BlendMode.SATURATION -> "svg:saturation"
        BlendMode.COLOR -> "svg:color"
        BlendMode.LUMINOSITY -> "svg:luminosity"
        // No SVG operator means this. NEVER a nearest-relative guess - see the KDoc above.
        else -> throw IllegalArgumentException(
            "OpenRaster cannot express the $mode blend mode: SVG has no operator for it, and " +
                "substituting the nearest one would silently change the painting. Export as PNG, " +
                "or change the layer to a mode OpenRaster can express.",
        )
    }

    /**
     * A layer's opacity, made safe exactly as `RegionRenderer` makes it safe.
     *
     * The clamp is not tidiness: `DocOps.validate` says a document's opacity is 0 to 1, but an
     * export is asked for before anybody has checked, and this file must not write an `opacity` of
     * `NaN` or `2.000` into a `stack.xml` that a strict reader would refuse to parse. The same
     * `coerceIn`, in the same direction, so the number in the XML is the number the renderer used.
     */
    private fun opacityOf(layer: Layer): Float {
        val o = layer.opacity
        if (o.isNaN()) return 0f
        if (o < 0f) return 0f
        if (o > 1f) return 1f
        return o
    }

    /**
     * `0.500`, in the C locale.
     *
     * `Locale.ROOT` IS NOT TIDINESS. `String.format` with no locale uses the device's, and a phone
     * set to German writes `0,500` — which is not a number to anything reading the XML, so the file
     * stops opening on exactly the devices whose owner most deserves to open it.
     */
    private fun opacityText(v: Float): String = String.format(Locale.ROOT, "%.3f", v)

    // ---------------------------------------------------------------- names, XML

    /**
     * A layer name as an XML attribute value.
     *
     * The five entities, plus the three WHITESPACE characters as numeric references. Those three are
     * the quiet ones: XML attribute-value normalisation turns a literal tab, newline or carriage
     * return in an attribute into a plain space, so a layer called "Line 1\nLine 2" would arrive in
     * Krita called "Line 1 Line 2" and nothing would say so. `&#9;`, `&#10;` and `&#13;` are not
     * normalised and survive intact.
     */
    private fun attributeText(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (ch in raw) {
            val c = if (isXmlChar(ch)) ch else REPLACEMENT
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\t' -> out.append("&#9;")
                '\n' -> out.append("&#10;")
                '\r' -> out.append("&#13;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * The body of an XML comment, made safe to put there.
     *
     * A layer name is a caller's string and an XML comment is the one place in a document where
     * NOTHING is escaped. A name containing `--` would end the comment early and leave a file no
     * reader can parse, so a hyphen that follows a hyphen gets a space between them. That is enough
     * and it is complete: the loop only ever emits one hyphen per input hyphen, so the output can
     * never contain two in a row, which is the only thing a comment cannot hold.
     *
     * NO ENTITY ESCAPING HERE, deliberately. `&` and `<` are ordinary characters inside a comment and
     * are NOT entity references, so writing `&amp;` in here would show up in the comment as literal
     * `&amp;` and read as a mistake.
     */
    private fun commentText(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        var afterHyphen = false
        for (ch in raw) {
            val c = if (isXmlChar(ch)) ch else REPLACEMENT
            if (c == '-') {
                if (afterHyphen) out.append(' ')
                out.append('-')
                afterHyphen = true
            } else {
                out.append(c)
                afterHyphen = false
            }
        }
        return out.toString()
    }

    /**
     * Whether [ch] is an XML 1.0 `Char`, decided per UTF-16 unit.
     *
     * XML 1.0 allows `#x9`, `#xA`, `#xD`, `#x20`–`#xD7FF`, `#xE000`–`#xFFFD` and everything above
     * `#xFFFF`. A NUL or a BEL in a layer name is therefore not a name this file may write, and
     * passing one through produces a `stack.xml` that is not a document at all — every reader
     * refuses the whole FILE, so one stray control character would cost a person their entire
     * export rather than one layer's name.
     *
     * SURROGATES PASS. Rejecting `#xD800`–`#xDFFF` would mangle every emoji and every CJK extension
     * character, because those are two of them together. A well-formed pair becomes a legal
     * four-byte code point when the string is encoded, and a LONE one becomes `?` in the JDK's
     * encoder, which is itself a legal character. `#xFFFE` and `#xFFFF` are the two code points XML
     * 1.0 forbids outright, so they are replaced.
     */
    private fun isXmlChar(ch: Char): Boolean {
        val c = ch.code
        if (c == 0x9 || c == 0xA || c == 0xD) return true
        if (c < 0x20) return false
        return c != 0xFFFE && c != 0xFFFF
    }

    // ---------------------------------------------------------------- the thumbnail

    /**
     * The whole region, nearest-neighbour, with its longest side at most [THUMBNAIL_MAX_SIDE].
     *
     * THE SCALE IS INTEGER ARITHMETIC, so `max(tw, th)` is not merely near 256 but exactly the cap,
     * and the two sides cannot disagree about it: both come from the same `longest` divisor. Floor
     * rather than round, so a downscale can never come out one pixel OVER the cap, and
     * `coerceAtLeast(1)` because a very wide, very short board would otherwise floor to a zero side
     * — a PNG of no height, which is not a file. It also never upscales, because the cap is at most
     * `longest`.
     *
     * NEAREST, AND WHICH NEAREST. Each destination pixel takes the source pixel whose centre is
     * nearest its own, rather than the source pixel at a fixed stride. At 300 by 200 down to 256 by
     * 170 a stride samples rows it skips and repeats others; centre-sampling cannot, and the loop
     * cannot stall either: `tw` and `th` are both at least 1 so both loops run, and each step of `dx`
     * advances the source by `w / tw` which is at least 1 because `tw` is at most `w`.
     *
     * The offsets are `Int` and fit, because the board was already refused unless `w * h` is within
     * [MAX_REGION_PX], so the largest byte offset touched is four times that.
     */
    private fun thumbnail(rgba: ByteArray, w: Int, h: Int): Thumb {
        val longest = maxOf(w, h).toLong()
        val cap = minOf(THUMBNAIL_MAX_SIDE.toLong(), longest)
        val tw = ((w.toLong() * cap) / longest).toInt().coerceAtLeast(1)
        val th = ((h.toLong() * cap) / longest).toInt().coerceAtLeast(1)
        require(tw in 1..THUMBNAIL_MAX_SIDE && th in 1..THUMBNAIL_MAX_SIDE) {
            "a thumbnail of $w by $h came out $tw by $th, and the longest side may be $THUMBNAIL_MAX_SIDE"
        }
        val out = ByteArray(tw * th * 4)
        for (dy in 0 until th) {
            val sy = ((dy.toLong() * 2 + 1) * h / (2L * th)).toInt().coerceIn(0, h - 1)
            val srcRow = sy * w * 4
            val dstRow = dy * tw * 4
            for (dx in 0 until tw) {
                val sx = ((dx.toLong() * 2 + 1) * w / (2L * tw)).toInt().coerceIn(0, w - 1)
                val s = srcRow + sx * 4
                val d = dstRow + dx * 4
                out[d] = rgba[s]
                out[d + 1] = rgba[s + 1]
                out[d + 2] = rgba[s + 2]
                out[d + 3] = rgba[s + 3]
            }
        }
        check(out.size == tw * th * 4) { "a $tw by $th thumbnail is not ${tw * th * 4} bytes" }
        return Thumb(tw, th, out)
    }

    // ---------------------------------------------------------------- zip

    /**
     * The `mimetype` entry, STORED and first, exactly as `JbArchive` writes it and for the same two
     * reasons: a reader can identify the file by its first bytes without inflating anything, and a
     * STORED entry keeps its own size and CRC in its own header.
     */
    private fun writeMimetype(zos: ZipOutputStream) {
        val bytes = ORA_MIMETYPE.toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry(MIMETYPE_NAME)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun put(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    /** A one-line reason, for an exception that has just crossed into this file's own error type. */
    private fun brief(e: Throwable): String {
        val message = e.message ?: e.javaClass.simpleName
        return if (message.length > 200) message.take(200) + "..." else message
    }
}
