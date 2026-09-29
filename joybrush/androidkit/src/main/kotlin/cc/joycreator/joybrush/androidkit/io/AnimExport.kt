package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.export.AnimExportPlan
import cc.joycreator.joybrush.core.export.GifEncoder
import cc.joycreator.joybrush.core.export.SpritePacker
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource

// `JbContents`, `JbArchiveException` and `PngWriter` are all in THIS package, so they need no import.

/** The manifest's own type. Nothing else in the repo writes one, and nothing else reads one. */
private const val TIMING_MIME = "text/plain"

/** What a sidecar is, for a sink that needs to know. */
private const val SIDECAR_MIME = "application/json"

/** The sidecar's extension. It is the packer's format, not this row's invention. */
private const val SIDECAR_EXT = "sprite.json"

/** The manifest's extension, and R35's correction of an earlier draft's `NNNN.txt`. */
private const val TIMING_EXT = "timing.txt"

/** The name the manifest gets, beside the images: `<baseName>.timing.txt`. */
private const val TIMING_NAME = "$TIMING_EXT"

/** The clip type a sheet of an animation board is written with. GIF loops (Decision 14), so this does. */
private const val CLIP_TYPE = "loop"

/**
 * The three formats this row exports. **MP4 and WebP are deliberately absent** — MP4 is delivered by
 * "Send to Studio" (JB-3.07, R35) and animated WebP cannot be written by anything in reach (see the
 * note at the top of this spec). Adding either is a different row with its own reasons.
 *
 * An `AnimFormat` entry the runner REFUSES would be a dead control, which is worse than a missing
 * one: every entry here is offered, and every entry here can be finished.
 */
enum class AnimFormat(val label: String, val extension: String, val mime: String) {
    GIF("GIF", "gif", "image/gif"),
    PNG_SEQUENCE("PNG sequence", "png", "image/png"),
    SPRITE_SHEET("Sprite sheet", "png", "image/png"),
}

/**
 * One file an export produced, in memory.
 *
 * **NOT a `data class`, and that is a decision.** A generated `equals` on a `ByteArray` compares
 * arrays by REFERENCE, so two `AnimFile`s holding identical bytes would say they differ — the exact
 * trap `JbContents` (`JbArchive.kt:39-42`) and `OraExport.Thumb` (`OraExport.kt:53-58`) already
 * document and already work around. A plain class has no such `equals` to mislead anyone.
 */
class AnimFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Where the bytes go. The **caller owns the folder, the SAF `Uri` and the `cacheDir` staging** —
 * this row has no `android.net.Uri` and no `Context` anywhere in it, exactly as `JbArchive`,
 * `OraExport` and `PngWriter` do not, and for exactly the reason they do not: `android.jar` is
 * `compileOnly`, so an `android.*` type in a signature here would put `NoClassDefFoundError` between
 * this row and every test below it. Staging into `cacheDir` and streaming to the `Uri` is R11 and
 * belongs to the row that owns the screen (see *Questions*).
 */
fun interface AnimFileSink {
    fun write(name: String, mime: String, bytes: ByteArray)
}

/**
 * The three encoders, and nothing else. Which frames, in what order, held for how long and at what
 * size is `AnimExportPlan`, which is a pure function of the board; this object renders those frames
 * and hands the bytes to a sink, and computes no timing of its own.
 *
 * **ALL THREE FORMATS CONSUME ONE PLAN (Decision 1).** A GIF's per-frame delay, a sequence's
 * manifest line and a sheet's folded cell list are all the same list read three ways, and the test
 * that checks all three against each other is what makes that a fact rather than an intention.
 *
 * **THE TIMING IS NOT RE-DERIVED HERE.** `plan.delayMs` is the only delay this file ever holds, and
 * it came out of `AnimOps.frameStartsMs` by the one derivation `PlaybackClock` already uses. A
 * second copy of that arithmetic in an exporter is a second answer that can differ in the last ulp,
 * and the last ulp is a frame boundary in a file somebody else plays.
 *
 * **EVERY REFUSAL HAPPENS BEFORE THE FIRST BYTE AND BEFORE THE FIRST FRAME (Decision 8).** The
 * board's existence, the plan's frame ids belonging to that board, the board's size against
 * [MAX_REGION_PX] in `Long` arithmetic, and the paper colour being a colour at all are all decided
 * before `onFrame` is ever called. A refused export costs a sentence, not a stalled phone.
 */
object AnimExportRunner {

    /**
     * GIF or the sprite sheet: **every byte, or an exception.** Nothing is written by this call and
     * nothing is written until it has returned, which is R11's rule satisfied by construction
     * rather than by remembering it.
     *
     * @param includePaper `doc.paper.color` as the backdrop, or null. The CALLER defaults this to
     *   `doc.paper.includeInExport`; this row writes nothing back to the document (Decision 11).
     * @param cols the sheet's columns; ignored by `GIF`. [AnimExport.sheetCols] is the default.
     * @param onFrame `(done, total)` after each frame is rendered, so a 40-frame export says
     *   "frame 7 of 40" instead of freezing. Never called before the first refusal, which is why
     *   Decision 8 can claim "refused before frame 1" and test it.
     * @return the files written, in the order a reader must receive them: the sheet's PNG and then
     *   its sidecar, because a sidecar that arrives first describes a file that is not there yet.
     * @throws cc.joycreator.joybrush.core.doc.DocException if [plan]'s frame ids are not all frames
     *   of board [boardId] — the one consistency check this call makes, and it names the board and
     *   the frame that is wrong.
     * @throws cc.joycreator.joybrush.core.render.RegionException if the board is larger than one
     *   render, before any frame is rendered.
     * @throws IllegalArgumentException for [AnimFormat.PNG_SEQUENCE], which is written by
     *   [writeSequence] and nowhere else — a sheet or a GIF is one file and a sequence is many.
     */
    fun encodeOne(
        format: AnimFormat,
        contents: JbContents,
        boardId: String,
        plan: AnimExportPlan,
        includePaper: Boolean,
        cols: Int = AnimExport.sheetCols(plan.frameCount),
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AnimFile> {
        val doc = contents.doc
        val board = doc.boards.firstOrNull { it.id == boardId }
            ?: throw DocException("this document has no board called \"$boardId\"")
        checkPlanFitsBoard(boardId, board.frames.map { it.id }, plan.frameIds)
        checkRegionFits(boardId, plan.width, plan.height)
        val paper = if (includePaper) checkedPaper(doc.paper.color) else null

        val cells = ArrayList<ByteArray>(plan.frameCount)
        plan.frameIds.forEachIndexed { i, frameId ->
            cells.add(RegionRenderer.render(doc, tileSource(contents), plan.rect, frameId, paper))
            onFrame(i + 1, plan.frameCount)
        }

        return when (format) {
            AnimFormat.GIF -> listOf(gif(plan, cells))
            AnimFormat.SPRITE_SHEET -> sheet(plan, cells, cols)
            AnimFormat.PNG_SEQUENCE -> throw IllegalArgumentException(
                "a PNG sequence is many files and is written by writeSequence, not by encodeOne, " +
                    "which is for the one-file formats (GIF and the sprite sheet).",
            )
        }
    }

    /**
     * The PNG sequence: frames in order, then the manifest, into [sink], in that order.
     *
     * The manifest is written LAST and only after the last frame has been accepted by [sink], which
     * is what makes "an incomplete sequence is recognisable by its missing manifest" true instead of
     * aspirational (Decision 12).
     *
     * A sequence is the one export that cannot be built whole — it is a folder, not a file — so it
     * is written as it goes and a failure part way out leaves a folder with no manifest in it. That
     * is a fact a person can see rather than a promise this file makes in a comment: the images are
     * there, and the one file that says what they mean is not, which is the same message Decision 16
     * puts in words.
     *
     * @return the names written, in the order they were written, manifest included.
     * @throws JbArchiveException naming how far it got when [sink] refuses a file, so the export
     *   button can show `"Exported 19 of 40 frames — frame 20 could not be written."`
     */
    fun writeSequence(
        contents: JbContents,
        boardId: String,
        plan: AnimExportPlan,
        includePaper: Boolean,
        sink: AnimFileSink,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val doc = contents.doc
        val board = doc.boards.firstOrNull { it.id == boardId }
            ?: throw DocException("this document has no board called \"$boardId\"")
        checkPlanFitsBoard(boardId, board.frames.map { it.id }, plan.frameIds)
        checkRegionFits(boardId, plan.width, plan.height)
        val paper = if (includePaper) checkedPaper(doc.paper.color) else null
        val tiles = tileSource(contents)

        val written = ArrayList<String>(plan.frameCount + 1)
        val total = plan.frameCount
        for ((i, frameId) in plan.frameIds.withIndex()) {
            val name = sequenceName(i + 1)
            val bytes = PngWriter.encode(
                plan.width,
                plan.height,
                RegionRenderer.render(doc, tiles, plan.rect, frameId, paper),
            )
            try {
                sink.write(name, AnimFormat.PNG_SEQUENCE.mime, bytes)
            } catch (e: Exception) {
                // Decision 16, in a sentence that can be shown. `written.size` is the count of
                // images that were ACCEPTED, so the two numbers in the message are the last one
                // that worked and the first one that did not — never a guess at either.
                throw JbArchiveException(
                    "Exported ${written.size} of $total frames — frame ${i + 1} could not be " +
                        "written, so there is no timing file beside them and this folder is " +
                        "incomplete: ${brief(e)}",
                )
            }
            written.add(name)
            onFrame(i + 1, total)
        }

        val manifest = timingName(plan.baseName)
        sink.write(manifest, TIMING_MIME, timingText(plan))
        written.add(manifest)
        return written
    }

    /** The one rule both halves obey, as a function so a test can call it: the file name for [n]. */
    fun sequenceName(n: Int): String {
        require(n >= 1) { "sequence frames are numbered from 1, not $n" }
        // Padded to AT LEAST four digits and never truncated: 10 000 frames give "10000.png",
        // which is longer and still sorts correctly, where a truncation would be two frames with
        // one name. `padStart` is exactly that rule and needs no special case for the long ones.
        return n.toString().padStart(4, '0') + "." + AnimFormat.PNG_SEQUENCE.extension
    }

    /** `<baseName>.timing.txt` — the manifest's own name, beside the images rather than one of them. */
    fun timingName(baseName: String): String = "$baseName.$TIMING_NAME"

    // ---------------------------------------------------------------- the encoders

    /**
     * The GIF: `GifEncoder` with `loop = true` and the plan's delays, untouched.
     *
     * The encoder owns the unit. Its centisecond rounding (`(ms + 5) / 10`, floored at 2 cs) is
     * `GifEncoder.delayCentiseconds` and this file does not know what it is: the plan's
     * milliseconds are the input and the encoder's hundredths are the output, and a second copy of
     * that rule here would be a second answer to a question the encoder has already answered.
     */
    private fun gif(plan: AnimExportPlan, cells: List<ByteArray>): AnimFile {
        val encoder = GifEncoder(plan.width, plan.height, loop = true)
        for ((i, rgba) in cells.withIndex()) encoder.addFrame(rgba, plan.delayMs[i])
        return AnimFile(
            name = "${plan.baseName}.${AnimFormat.GIF.extension}",
            mime = AnimFormat.GIF.mime,
            bytes = encoder.finish(),
        )
    }

    /**
     * The sheet's two files, and the check between building them and returning them.
     *
     * `SpritePacker.pack` first, then the PNG, then [cc.joycreator.joybrush.core.export.PackedSheet.assertEncodedSize]
     * against the size the PNG ITSELF says in its header — not against the size this file asked
     * for, which would make the check a tautology. It is a method ON the packed sheet and it is
     * called before either file leaves, so the pair of files that disagree is a message rather than
     * something on disk.
     *
     * The PNG's own dimensions come from its IHDR, which is eight bytes at a fixed offset. That is
     * not a second PNG reader: it reads the two numbers the file declares about itself and refuses
     * if the signature is not there, and the pixel data is still `PngWriter`'s alone. The test
     * beside this file decodes the result with `javax.imageio`, so a header that lies is caught by
     * a decoder that did not write it.
     */
    private fun sheet(plan: AnimExportPlan, cells: List<ByteArray>, cols: Int): List<AnimFile> {
        val sheetName = "${plan.baseName}.${AnimFormat.SPRITE_SHEET.extension}"
        val packed = SpritePacker.pack(
            cells = cells,
            cellW = plan.width,
            cellH = plan.height,
            cols = cols,
            id = plan.baseName,
            name = plan.baseName,
            sheetFileName = sheetName,
            fps = plan.fps,
            // Decision 7: ONE clip covering every exported frame, with each hold folded in as a
            // repeat of that cell's index. `Clip` has no `weights` field today and this row must
            // not start writing one; a repeat is a repeat, not a mistake.
            clips = listOf(AnimExport.sheetClip(plan, plan.baseName, CLIP_TYPE)),
            cellNames = AnimExport.sheetCellNames(plan),
        )
        val png = PngWriter.encode(packed.width, packed.height, packed.rgba)
        val (pngW, pngH) = pngSize(png, sheetName)
        packed.assertEncodedSize(sheetName, pngW, pngH)
        return listOf(
            AnimFile(sheetName, AnimFormat.SPRITE_SHEET.mime, png),
            AnimFile(
                "${plan.baseName}.$SIDECAR_EXT",
                SIDECAR_MIME,
                packed.sidecarJson.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * The manifest: UTF-8, LF, one line per frame in play order, `<fileName><TAB><delayMs>`, no
     * header, a trailing LF on the last line (Decision 12).
     *
     * The delays are the whole content of a sequence and a folder of PNGs has none, so without this
     * the export is a folder; with it, it is a document anybody can play at the right speed. Every
     * number in it is `plan.delayMs[i]` — the same number the GIF hands the encoder and the sheet
     * folds into cell repeats.
     */
    private fun timingText(plan: AnimExportPlan): ByteArray {
        val out = StringBuilder(plan.frameCount * 16)
        for (i in plan.frameIds.indices) {
            out.append(sequenceName(i + 1)).append('\t').append(plan.delayMs[i]).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- the refusals, all before frame 1

    /**
     * The one consistency check: every frame in [plan] is a frame of this board.
     *
     * The plan and the board arrive as two arguments, so they can disagree, and nothing downstream
     * would notice: a frame id that is not on the board renders as an empty frame (no cel maps to
     * it) and the file is written with a hole in it. So it is checked here, before anything is
     * rendered, and the sentence names the board AND the frame, because "the export failed" with a
     * number in it is what a person needs.
     */
    private fun checkPlanFitsBoard(boardId: String, boardFrames: List<String>, planFrames: List<String>) {
        for (frameId in planFrames) {
            if (frameId !in boardFrames) {
                throw DocException(
                    "board \"$boardId\" has no frame called \"$frameId\", so an export that asks " +
                        "for it cannot be made. The board was probably edited after it was planned.",
                )
            }
        }
    }

    /**
     * The board's size, against the one budget the renderer has, in `Long` arithmetic.
     *
     * `4000 × 4000` is 16 million pixels and [MAX_REGION_PX] is 8 388 608, and the renderer's own
     * [cc.joycreator.joybrush.core.render.RegionException] says the same thing a few lines later. This
     * copy is not a second limit: it is the same constant, compared in the same width, so that the
     * refusal arrives before a frame is rendered rather than on the first one. A `Long` because
     * `4000 * 4000` is fine and `100000 * 100000` is not, and an `Int` that wrapped would be a size
     * check that passes.
     */
    private fun checkRegionFits(boardId: String, width: Int, height: Int) {
        val px = width.toLong() * height.toLong()
        if (px > MAX_REGION_PX) {
            throw RegionException(
                "board \"$boardId\" is $width by $height, which is $px pixels, and the most one " +
                    "export will render is $MAX_REGION_PX. Export a smaller board, or a piece of it " +
                    "at a time.",
            )
        }
    }

    /**
     * The paper colour, or a refusal in a sentence naming it.
     *
     * Checked HERE rather than left to the renderer's `IllegalArgumentException` for the same reason
     * `OraExport.checkedPaper` checks it: a document whose paper is not a colour is a document
     * nobody wants to export, and the message a person reads should say WHICH colour rather than
     * naming a rectangle it was never asked about. Same `#RRGGBB` rule as the renderer, same rule
     * as the layer panel; this is the third spelling in the repo and it is the same answer.
     */
    private fun checkedPaper(color: String): String {
        if (!PAPER_COLOR.matches(color)) {
            throw DocException(
                "the paper colour \"$color\" is not a #RRGGBB colour, so there is no paper to " +
                    "export. Set the paper to a colour, or turn \"Include paper\" off.",
            )
        }
        return color
    }

    // ---------------------------------------------------------------- the small parts

    /** Tiles addressed by (layer, cel, tile), which is what a cel id is unique only WITHIN. */
    private fun tileSource(contents: JbContents) = TileSource { layerId, celId, tx, ty ->
        contents.tiles[Triple(layerId, celId, DocOps.key(tx, ty))]
    }

    /**
     * The width and height a PNG says about ITSELF, out of its IHDR.
     *
     * Needed because [cc.joycreator.joybrush.core.export.PackedSheet.assertEncodedSize] is only
     * worth calling if the two numbers come from the encoded file and not from the arguments that
     * produced it — otherwise it compares `PngWriter.encode(w, h, …)` with `w` and `h` and can
     * never fail. PNG puts a fixed 8-byte signature, a 4-byte length and the type `IHDR` in front
     * of the two 4-byte big-endian numbers, so [WIDTH_AT] is where the width starts; nothing here
     * decodes a pixel, inflates an IDAT or checks a CRC, and a file that is not a PNG is refused
     * rather than read at random.
     */
    private fun pngSize(bytes: ByteArray, fileName: String): Pair<Int, Int> {
        for (i in 0 until 7) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw JbArchiveException("$fileName does not start with a PNG signature, so its size cannot be checked")
            }
        }
        if (bytes.size < WIDTH_AT + 8) {
            throw JbArchiveException("$fileName is ${bytes.size} bytes, which is too short to be a PNG")
        }
        val w = ((bytes[WIDTH_AT].toInt() and 0xFF) shl 24) or
            ((bytes[WIDTH_AT + 1].toInt() and 0xFF) shl 16) or
            ((bytes[WIDTH_AT + 2].toInt() and 0xFF) shl 8) or
            (bytes[WIDTH_AT + 3].toInt() and 0xFF)
        val h = ((bytes[WIDTH_AT + 4].toInt() and 0xFF) shl 24) or
            ((bytes[WIDTH_AT + 5].toInt() and 0xFF) shl 16) or
            ((bytes[WIDTH_AT + 6].toInt() and 0xFF) shl 8) or
            (bytes[WIDTH_AT + 7].toInt() and 0xFF)
        return w to h
    }

    /** A one-line reason, for an exception that has just crossed into this file's own error type. */
    private fun brief(e: Throwable): String {
        val message = e.message ?: e.javaClass.simpleName
        return if (message.length > 200) message.take(200) + "..." else message
    }

    /** 137, PNG, CR LF LF — the eight bytes every PNG starts with. */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Signature (8) + length (4) + `"IHDR"` (4) is where the width's first byte is. */
    private const val WIDTH_AT = 16

    /** Paper colour is stored as `#RRGGBB` — the format every layer panel already speaks. */
    private val PAPER_COLOR = Regex("^#[0-9a-fA-F]{6}$")
}
