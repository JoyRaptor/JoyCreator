package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.RectPx
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
 * [MAX_REGION_PX] in `Long` arithmetic, the decoded pixels the chosen format would hold alive
 * against [MAX_EXPORT_PX], and the paper colour being a colour at all are all decided before
 * `onFrame` is ever called. A refused export costs a sentence, not a stalled phone.
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
     *   render, or if this [format] would hold more decoded pixels alive than [MAX_EXPORT_PX] —
     *   both decided before any frame is rendered.
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
        paperRenderer: ((RectPx) -> ByteArray)? = null,
        onWarning: (String) -> Unit = {},
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AnimFile> {
        val doc = contents.doc
        val board = doc.boards.firstOrNull { it.id == boardId }
            ?: throw DocException("this document has no board called \"$boardId\"")
        checkPlanFitsBoard(boardId, board.frames.map { it.id }, plan.frameIds)
        checkRegionFits(boardId, plan.width, plan.height)
        checkExportFits(boardId, format, plan, cols)
        val paper = if (includePaper && !doc.paper.screenTransparent) checkedPaper(doc.paper.color) else null
        val renderer = CanvasPng.paperRendererFor(contents, includePaper, paperRenderer, onWarning)

        // **THE LIST STAYS, AND IT IS NOT AN OVERSIGHT (Decision 3).** It reads like the obvious
        // memory fix — hold every frame at once, encode as you go instead — and it would move no
        // bytes. `GifEncoder` retains every frame's array by design (`PendingFrame` / `frames` at
        // `GifEncoder.kt:413-415`, `frames.add(…)` at `:443`, read back in `finish()` at `:456`),
        // so dropping this list frees REFERENCES and leaves every byte exactly where it is; and for
        // the sheet `pack` is handed the whole list and allocates the whole sheet on top of it
        // (`SpritePacker.kt:248`), so it needs it in one piece anyway. `GifEncoder.kt` is a
        // JB-3.06a file and `SpritePacker.kt` a reviewed landed one, so a streaming entry point is
        // not this row's to add — it is Question 2 of the JB-3.06c spec. What IS this row's is the
        // budget above, which turns the board that would run out of memory into a sentence naming
        // both numbers before a single frame exists.
        val cells = ArrayList<ByteArray>(plan.frameCount)
        plan.frameIds.forEachIndexed { i, frameId ->
            // ONE CALL, ONE TRUTH. A textured paper is the floor of the stack now, not a sheet
            // pasted over a transparent one, so a MULTIPLY layer multiplies the paper here exactly
            // as it does on screen. The renderer is asked per frame in bounded blocks rather than
            // once for the whole region, which costs a re-rasterisation per frame and buys back the
            // region-sized paper array that used to sit beside every frame in `cells` — for a GIF
            // that list holds the whole animation, so the saving is the larger of the two.
            val pixels = RegionRenderer.render(
                doc, tileSource(contents), plan.rect, frameId,
                if (renderer == null) paper else null, renderer,
            )
            cells.add(pixels)
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
     * @throws cc.joycreator.joybrush.core.render.RegionException if the board is larger than one
     *   render, or if a sequence of this size would hold more decoded pixels alive than
     *   [MAX_EXPORT_PX] — which for this format is one cell, because the loop below streams. Both
     *   are decided before the first frame is rendered and before [sink] is called.
     * @throws JbArchiveException naming how far it got when [sink] refuses a file, so the export
     *   button can show `"Exported 19 of 40 frames — frame 20 could not be written."`
     */
    fun writeSequence(
        contents: JbContents,
        boardId: String,
        plan: AnimExportPlan,
        includePaper: Boolean,
        sink: AnimFileSink,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
        onWarning: (String) -> Unit = {},
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> {
        val doc = contents.doc
        val board = doc.boards.firstOrNull { it.id == boardId }
            ?: throw DocException("this document has no board called \"$boardId\"")
        checkPlanFitsBoard(boardId, board.frames.map { it.id }, plan.frameIds)
        checkRegionFits(boardId, plan.width, plan.height)
        // There is no `format` and no `cols` in scope here, so both are spelled out. `sheetCols` is
        // this file's own default for `encodeOne`'s `cols` and `decodedPixels` returns at the
        // `PNG_SEQUENCE` line before it reads `cols` at all — the column count is deliberately
        // ignored, because a sequence renders, writes and drops inside its loop. Pinned by
        // `theBudgetIsTheSameForEveryColumnCountOnATwoDimensionalFormat`.
        checkExportFits(boardId, AnimFormat.PNG_SEQUENCE, plan, AnimExport.sheetCols(plan.frameCount))
        val paper = if (includePaper && !doc.paper.screenTransparent) checkedPaper(doc.paper.color) else null
        val tiles = tileSource(contents)
        val renderer = CanvasPng.paperRendererFor(contents, includePaper, paperRenderer, onWarning)

        val written = ArrayList<String>(plan.frameCount + 1)
        val total = plan.frameCount
        for ((i, frameId) in plan.frameIds.withIndex()) {
            val name = sequenceName(i + 1)
            val bytes = PngWriter.encode(
                plan.width,
                plan.height,
                RegionRenderer.render(
                    doc, tiles, plan.rect, frameId,
                    if (renderer == null) paper else null, renderer,
                ),
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
            // repeat of that cell's index. `Clip` HAS a `weights` field now (JB-4.03c) and this row
            // does not write one — that is Question 1 of the JB-3.06c spec, not a fact about the
            // field. A repeat is a repeat, not a mistake, and it is what the app's own KDoc means.
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
     * The most DECODED picture one export may hold alive at once, in pixels.
     *
     * **DERIVED, NOT CHOSEN — and written as `MAX_REGION_PX * 2` so the derivation cannot rot.**
     * 1. [MAX_REGION_PX] is this tree's one-render budget, and it is documented as a MEMORY budget,
     *    not a range check (`RegionRenderer.kt:141` says so, and `RegionRenderer.kt:345-350` backs
     *    it), and `BYTES_PER_PX = 20` (`RegionRenderer.kt:92`, four result bytes plus sixteen of
     *    float scratch) puts one render's live footprint at `MAX_REGION_PX * 20` = 160 MiB.
     * 2. This codebase's own ceiling for ONE decoded blob is 64 MiB, and it says so three times
     *    with the same number: `ProcreateImport.MAX_INFLATED_BYTES = 64L * 1024 * 1024`
     *    (`ProcreateImport.kt:76`, "What one deflate entry may inflate to. 64 MiB, and the declared
     *    size is not believed"), `KritaImport.MAX_FILE_BYTES` (`KritaImport.kt:89`) and
     *    `AbrImport.MAX_FILE_BYTES = 64L * 1024 * 1024` (`AbrImport.kt:39`) — and
     *    `KritaImport.kt:87` names the agreement in prose, which is where the number comes from.
     * 3. 64 MiB of straight RGBA8 is 64 * 1024 * 1024 / 4 = 16 777 216 pixels, and
     *    16 777 216 = 2 * 8 388 608. **So the multiplier is 2 because the tree's blob ceiling
     *    happens to be exactly twice the renderer's pixel budget, not because two felt right.**
     *
     * The consequence is the sentence a person gets instead of an `OutOfMemoryError`, and the
     * number moves on its own if the Lead rules on the renderer's own pending question
     * (`RegionRenderer.kt:85-86`: "LEAD RULING PENDING … the implementer picked it, not the Lead").
     * **PROVISIONAL (Decision 6):** it caps what a person can export, so the Lead may want a
     * different multiple; this one constant is the only thing to change and the arithmetic moves
     * with it.
     */
    internal const val MAX_EXPORT_PX = MAX_REGION_PX * 2L

    /**
     * How many decoded RGBA pixels [format] holds alive at once. **ONE TABLE, THREE ANSWERS, and
     * each answer is a line of landed code rather than a preference:**
     *
     * - `GIF`: `frames * cellW * cellH`, because `GifEncoder` retains every frame's array by
     *   design — `PendingFrame` / `frames` (`GifEncoder.kt:413-415`), `frames.add(…)` (`:443`),
     *   read back in `finish()` (`:456`) — and `GifEncoder.kt` is not this row's to change.
     * - `PNG_SEQUENCE`: ONE cell, because `writeSequence` above renders, encodes and writes inside
     *   its loop and nothing accumulates. **This is why the count is not
     *   `frames * …` for every format: a 600-frame sequence runs in constant memory, and a rule
     *   that refused it would be refusing an export that works.**
     * - `SPRITE_SHEET`: `frames * cellW * cellH` PLUS the sheet itself, because `encodeOne` holds
     *   the cells list and `pack` allocates the whole sheet on top of it (`SpritePacker.kt:248`) —
     *   the one place two full copies of the same picture are alive.
     *
     * `cols` is ignored for the two formats that have no grid, and the test that proves it is
     * `theBudgetIsTheSameForEveryColumnCountOnATwoDimensionalFormat`.
     */
    internal fun decodedPixels(format: AnimFormat, plan: AnimExportPlan, cols: Int): Long {
        val cell = plan.width.toLong() * plan.height.toLong()
        // **PNG_SEQUENCE FIRST, and it returns ONE cell.** `writeSequence` renders, encodes, writes
        // and drops inside its loop, so nothing accumulates and the peak is a single frame. Costing
        // it `frames * cell` would be refusing a 400-frame 1024x1024 sequence that runs in constant
        // memory - the exact "a rule that refused an export that works" the KDoc above rules out, and
        // the exact failure `anExportOverThePixelBudgetIsRefusedBeforeAnyFrameIsRendered`'s last
        // assertion exists to catch.
        if (format == AnimFormat.PNG_SEQUENCE) return cell
        val count = plan.frameCount.toLong()
        val frames = cell * count
        if (format != AnimFormat.SPRITE_SHEET) return frames
        // **`rows` is ceil(FRAME COUNT / cols), NOT ceil(pixel total / cols).** The packer's own line is
        // `((cells.size.toLong() + cols - 1) / cols)` (`SpritePacker.kt:235`), where `cells.size` is the
        // frame count, and dividing the pixel total instead makes this term ~`cell / cols` times too
        // large: 7 frames of 1024x1024 at 3 columns becomes `ceil(7_340_032 / 3) = 2_446_678` rows and
        // a 7 696 590 831_616-pixel "need", so **every sprite sheet at every size is refused** - which is
        // why Decision 6's own costs (16 / 64 / 2 as a GIF and **7 / 30 / 1** as a sheet) are the proof
        // that this line is meant to be the frame count. The exact-cap sheet boundary is the other
        // proof: it only lands on the cap exactly with `rows = 3 = ceil(7 / 3)`.
        val rows = (count + cols - 1) / cols
        return frames + (cols.toLong() * plan.width) * (rows * plan.height.toLong())
    }

    /**
     * The refusal, in a sentence that names the board and both numbers.
     *
     * `RegionException`, not `DocException`: [checkRegionFits] above throws `RegionException` for
     * the same reason — this export will not fit in memory — and one type means a caller wraps
     * `encodeOne` in one `catch`. The sentence names what the export WOULD need, what the most IS,
     * and one remedy. **It does not name a control** (see *Do not*, R35).
     */
    private fun checkExportFits(boardId: String, format: AnimFormat, plan: AnimExportPlan, cols: Int) {
        val px = decodedPixels(format, plan, cols)
        if (px > MAX_EXPORT_PX) {
            throw RegionException(
                "board \"$boardId\" would hold $px pixels of picture at once for this " +
                    "${format.label} — ${plan.frameCount} frames of ${plan.width} by " +
                    "${plan.height} — and the most one export will hold is $MAX_EXPORT_PX. " +
                    "Export a shorter range of it, or a smaller board.",
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
     *
     * **THE SIGNATURE IS CHECKED IN FULL, AND THE LENGTH IS CHECKED FIRST.** [PNG_SIGNATURE] is
     * eight bytes and the loop walks `PNG_SIGNATURE.indices`, so the bound cannot drift from the
     * array the way a typed `7` did — that was the bug this row fixes. The length guard is ABOVE the
     * loop because the loop indexes the array: a short file must arrive as the sentence below and
     * never as an `ArrayIndexOutOfBoundsException`.
     *
     * **A RESTATEMENT OF `PngChunks`'s own `PNG_SIGNATURE`, NOT AN IMPORT OF IT.** That array is
     * `private` in `:core` (`PngChunks.kt:148`) and the module dependency runs `androidkit -> core`
     * (`androidkit/build.gradle.kts:45`), so this file cannot see it. `PngChunks.kt:172-179` does
     * this same check in this same order; keep them in step, and put a comment on both sides naming
     * the other. The difference is deliberate: `PngChunks` refuses a file it is *reading*, `pngSize`
     * refuses one it has just *written*, which is a backstop against a writer that lies.
     */
    internal fun pngSize(bytes: ByteArray, fileName: String): Pair<Int, Int> {
        if (bytes.size < WIDTH_AT + 8) {
            throw JbArchiveException("$fileName is ${bytes.size} bytes, which is too short to be a PNG")
        }
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw JbArchiveException("$fileName does not start with a PNG signature, so its size cannot be checked")
            }
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
