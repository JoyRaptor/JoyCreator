package cc.joycreator.joybrush.androidkit.bench

import cc.joycreator.joybrush.androidkit.io.ARCHIVE_DEFLATE_LEVEL
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.bench.Bench
import cc.joycreator.joybrush.core.bench.BenchCase
import cc.joycreator.joybrush.core.bench.BenchOutcome
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.fill.FillOptions
import cc.joycreator.joybrush.core.fill.FloodFill
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * The jobs the blueprint names, the two the paths a person waits on, and the budgets they are
 * measured against. THE BUDGETS ARE PROVISIONAL (R39); see [budgetMsFor].
 *
 * WHY THIS HALF IS JVM AND THE HARNESS HALF IS NOT. `Bench` is pure Kotlin in `core` so its
 * arithmetic runs and is tested anywhere. The workloads here are not: tile compression is measured
 * through the same `java.util.zip` deflater the archive itself writes with, so the level under
 * measurement is the archive's real level rather than a portable stand-in that would answer a
 * different question. Nothing in `core` imports `java.*`.
 *
 * WHY THERE IS NO `main()` HERE. A `main()` would measure the machine it ran on, which is not the
 * phone, and the entry point R39 asks for is a "Run benchmarks" row in the hidden pen-diagnostics
 * panel -- a separate row, because it touches `JoyBrushActivity.kt` and R30 puts that file behind
 * three other rows in a one-row-at-a-time lock order. [all] and [Bench.report] are callable from a
 * button, a test or a desktop harness; the button adds an entry point, not a capability.
 *
 * EVERY FIXTURE IS BUILT BEFORE THE `BenchCase` IS CONSTRUCTED, never inside its `run()`, and
 * [FixtureBuilds] is the deterministic work counter that keeps it that way. Building input inside
 * the timed closure is the single most expensive mistake available in a benchmark: the number that
 * comes out is the cost of constructing a fixture, which is not the job anybody is being asked to
 * pay. `archiveRead()` states the rule in full -- a document built and deflated inside the closure
 * would report the cost of WRITING an archive, which is the `tile-deflate` case's job and not this
 * one's.
 */
object BenchCases {

    // ---------------------------------------------------------------- the sizes, and where they come from

    /**
     * The flood-fill reference: 2048 x 2048.
     *
     * DERIVED, NOT COPIED. `fill.MAX_FILL_PX` is 8 388 608 (2^23) and its own KDoc already costs one
     * fill at that size; 2048 x 2048 = 4 194 304 is exactly half the cap, so the case is a fill of a
     * size the tool really accepts with 2x headroom still in hand, and 2048 x 2048 is a board a
     * person can actually have painted. `BenchCasesTest` re-derives the comparison against the
     * constant it imports rather than against this sentence.
     */
    const val FILL_SIDE = 2048

    /** Four px of line, which at 2048 px is the ratio a pen of about 2 pt draws at 2x. */
    const val INK_THICK = 4

    /**
     * Where the tap goes: dead centre of the FIRST WHITE CELL, which is `INK_THICK`..`TILE_SIZE - 1`.
     *
     * DERIVED FROM THE GEOMETRY rather than written as a number, because this is exactly the value
     * that was wrong once: a seed of `FILL_SIDE / 8` is 256 on a 2048 board whose ink lines are at
     * 0, 256, 512... -- so it lands ON a line, the flood leaves the paper and fills the connected ink
     * instead, and BOTH fill cases return the same count because neither of them is filling what the
     * case is named for. `BenchCasesTest` asserts the seed pixel is opaque white, which is the guard
     * against that whole class of mistake.
     */
    const val FILL_SEED_X = INK_THICK + (TILE_SIZE - INK_THICK) / 2
    const val FILL_SEED_Y = INK_THICK + (TILE_SIZE - INK_THICK) / 2

    /**
     * The board behind the archive-read fixture. 1024 x 1024 is (1024 / 256) x (1024 / 256) = 4 x 4
     * = [ARCHIVE_TILES] tiles of exactly [TILE_BYTES], i.e. 16 x 262 144 = 4 194 304 B of deflate on
     * the OPEN path, which is a board a person really has rather than a stress case.
     */
    const val ARCHIVE_SIDE = 1024

    /** Tiles in the archive fixture, DERIVED from [ARCHIVE_SIDE] and the engine's own [TILE_SIZE]. */
    const val ARCHIVE_TILES = (ARCHIVE_SIDE / TILE_SIZE) * (ARCHIVE_SIDE / TILE_SIZE)

    /**
     * Paper for the two render cases.
     *
     * `"#ffffff"`, not null: "a region no layer covers is TRANSPARENT" is `RegionRenderer`'s own
     * rule, so a render with no paper writes alpha and one with paper writes colour, and those are
     * two different jobs. The tile fixture leaves part of every tile at alpha 0, so the difference
     * is real rather than theoretical.
     */
    const val BENCH_PAPER = "#ffffff"

    /** The render sizes: a board a person has, and the largest real export. */
    const val RENDER_SMALL_W = 1024
    const val RENDER_SMALL_H = 1024
    const val RENDER_4K_W = 3840
    const val RENDER_4K_H = 2160

    /**
     * THE BUDGET TABLE, AND EVERY NUMBER IN IT IS PROVISIONAL (R39).
     *
     * Nothing here has been measured on the phone. A budget that has never been measured against is
     * a TARGET, and this file says so rather than letting a number read as a result. **What settles
     * it is this harness, run on the Note 9, with [all]'s device string set to that phone's own model
     * and Android version** -- not a laptop, not a cloud box, not a Pixel. The first run on real
     * hardware is the measurement this table is a guess at, and the table is then rewritten from
     * those numbers with the derivations kept. The two sizes it is worth arguing about:
     *
     *  - `region-render-3840x2160` is 8 294 400 px against the 1024^2 case's 1 048 576, so
     *    8 294 400 / 1 048 576 = 7.91x, and 250 x 7.91 = 1 978 ms if the work were perfectly linear.
     *    The budget is 4 000 ms, about 2x that, DELIBERATELY loose: this case is about the 160 MiB
     *    peak (`MAX_REGION_PX x 20 B`) and whether the renderer needs strip-stitching on the phone,
     *    not about a person waiting, and a false alarm here costs real C++ work.
     *  - `archive-read-16` is the open path, where a person sees a spinner after tapping a file.
     *    Tens of ms is the expectation and anything past 400 ms is a visible hang.
     *
     * A name that is not in this table gets [Double.NaN], which [BenchOutcome.Measured.verdict]
     * turns into `NO_BUDGET` -- so changing a budget here, or adding a case without one, cannot
     * break the harness and cannot produce a silent pass either.
     */
    private val BUDGETS_MS: Map<String, Double> = mapOf(
        // A tap-fill that takes half a second is a dropped frame the person sees.
        "flood-fill-${FILL_SIDE}x$FILL_SIDE-gap0" to 400.0,
        // gap closing is two more full-image arrays and two dilations: the fill tool's SLOW path.
        "flood-fill-${FILL_SIDE}x$FILL_SIDE-gap3" to 1200.0,
        // Per tile, on a writer thread: a four-tile stroke's worth of work per save.
        "tile-deflate-$ARCHIVE_DEFLATE_LEVEL" to 60.0,
        // A board-sized flatten on an export a person is waiting on. 1 048 576 px.
        "region-render-${RENDER_SMALL_W}x$RENDER_SMALL_H" to 250.0,
        // See above: 7.91x the pixel count of the 1024^2 case, budgeted at about 2x its linear cost.
        "region-render-${RENDER_4K_W}x$RENDER_4K_H" to 4000.0,
        // 16 tiles of TILE_BYTES on the OPEN path.
        "archive-read-$ARCHIVE_TILES" to 400.0,
        // psd-write: no row, on purpose. The PSD writer is JB-2.14c and is not built; a budget for a
        // job nobody can run is a fiction, and it would be a target nobody could ever be measured
        // against.
    )

    /**
     * The budget for [caseName] in ms, or [Double.NaN] when the table has no entry -- which
     * [BenchOutcome.Measured.verdict] reports as `NO_BUDGET` rather than as a pass.
     */
    fun budgetMsFor(caseName: String): Double = BUDGETS_MS[caseName] ?: Double.NaN

    // ---------------------------------------------------------------- the checksum

    /**
     * How many bytes [fold] steps over. Stated, not typed into the loop.
     *
     * THE STRIDE, AND WHY IT IS NOT 1. Every case's `run()` derives its result from its own output
     * through this one function, so a `run()` that returned nothing would print `checksum 0` and a
     * refactor that quietly emptied the work would not move a single timing figure. Walking every
     * byte would make that guarantee true and would also make the GUARANTEE the benchmark: a
     * 33 MB 4K render is thirty-three million adds against the work being measured, and on the
     * `tile-deflate` case a full CRC of a quarter of a megabyte is measured against a deflater and
     * could plausibly WIN, at which point the checksum would be reporting on itself.
     *
     * **WHAT A STRIDE ACTUALLY GUARANTEES, STATED PLAINLY BECAUSE IT IS WEAKER THAN IT LOOKS:** the
     * observed positions are `0, 4096, 8192, ...` -- one byte in 4 096 -- NOT "the first 4 KiB".
     * Changing byte 4 100 changes the fold; changing byte 4 101 does not. So this catches a case that
     * returned nothing, a case that returned a truncated output (the length is mixed in), and any
     * change to a sampled byte, and it does NOT certify that two different outputs of the same size
     * and the same sampled bytes differ. `BenchCasesTest` asserts that asymmetry rather than hiding
     * it, because a guarantee written down as stronger than the code is the same defect as a budget
     * written down as measured.
     */
    const val FOLD_STRIDE = 4096

    /** The one number every case's `run()` derives its result into. See [FOLD_STRIDE]. */
    fun fold(bytes: ByteArray): Long = fold(bytes, 0L)

    /**
     * [fold] continuing from [into], so a case with several outputs (the archive case, one per tile)
     * folds them all into one number without concatenating 16 arrays into a fourth one.
     */
    internal fun fold(bytes: ByteArray, into: Long): Long {
        var h = into
        var i = 0
        while (i < bytes.size) {
            h = h * 31L + bytes[i].toLong()
            i += FOLD_STRIDE
        }
        // The length LAST, so a truncated output is a different number from a full one -- a fold that
        // sampled only bytes 0, 4096, 8192 would otherwise give a short array and a long one the
        // same answer whenever the samples agree.
        return h * 31L + bytes.size.toLong()
    }

    // ---------------------------------------------------------------- the cases

    /**
     * "flood-fill-2048x2048-gap0" and "flood-fill-2048x2048-gap3": the SAME reference image at two
     * [FillOptions.gapClosePx] settings.
     *
     * `gap0` is the ordinary tap-fill, gaps in the line notwithstanding -- which is what "easy fills"
     * means. `gap3` is the slow path: closing gaps allocates two more full-image arrays and runs two
     * dilations, over the whole image whatever it fills.
     *
     * **THE TWO FILL DIFFERENT AMOUNTS, AND IT IS NOT THE OBVIOUS WAY ROUND.** The reference is a
     * closed 8 x 8 grid of cells with ONE six-pixel break in the wall of the cell the tap lands in.
     * `gap0` leaks through that break into the next cell, so it fills 2 cells. `gap3` dilates the ink
     * until the six-pixel break is sealed, so the fill CANNOT leave its own cell and fills 1. So the
     * gap-closing case fills LESS and costs about three and a half times as much (measured, not
     * assumed): the work is in the full-image passes, not in the filled pixels. Both are the jobs the
     * fill tool really does, and a reader who assumed "more settings = more filled pixels" would have
     * read this table backwards -- which is why the checksums are printed at all.
     */
    fun floodFill(): List<BenchCase> {
        val reference = fillReference() // built HERE, never inside a closure
        return listOf(0, 3).map { gap ->
            BenchCase("flood-fill-${FILL_SIDE}x$FILL_SIDE-gap$gap") {
                val mask = FloodFill.fill(
                    FILL_SIDE,
                    FILL_SIDE,
                    reference,
                    FILL_SEED_X,
                    FILL_SEED_Y,
                    FillOptions(gapClosePx = gap),
                )
                filledPixels(mask)
            }
        }
    }

    /**
     * "tile-deflate-6": one tile of exactly [TILE_BYTES] through a [DeflaterOutputStream] at
     * [ARCHIVE_DEFLATE_LEVEL].
     *
     * THE LEVEL IS READ, NEVER RESTATED, and the case NAME carries it, so the report says which level
     * was measured even after the constant is changed. `TILE_BYTES` is the `androidkit.io` one -- the
     * tile size the archive itself writes with, in this module -- and NOT `RegionRenderer.TILE_BYTES`
     * in `core`, which happens to agree today; importing the core one would be a bet that two files
     * that agree keep agreeing, in the one place where being wrong is a benchmark of the wrong thing.
     * A test asserts the two are equal so the bet is at least checked.
     *
     * The image is a smooth gradient plus a filled rectangle rather than synthetic white noise:
     * noise does not compress, and a case that measures "deflate noise" is a different job from the
     * one the archive does every time it saves a tile of real art.
     */
    fun tileDeflate(): List<BenchCase> {
        val image = tileImage(0, 0) // built HERE, never inside the closure
        return listOf(
            BenchCase("tile-deflate-$ARCHIVE_DEFLATE_LEVEL") { fold(deflated(image)) }
        )
    }

    /**
     * "region-render-1024x1024" and "region-render-3840x2160": a real flatten over a real
     * [TileSource], at a board a person has and at the largest real export.
     *
     * 3840 x 2160 is 8 294 400 px, which is inside `render.MAX_REGION_PX` (8 388 608) by 94 208 px
     * and is the size that engine's own KDoc names as the largest real export. `BenchCasesTest`
     * re-derives both comparisons against the constants it imports.
     *
     * Both return `fold(render(...))`: the whole 4 MB and 33 MB outputs go through the case and the
     * fold samples them ([FOLD_STRIDE]), which is the whole reason the fold has a stride.
     */
    fun regionRender(): List<BenchCase> = listOf(
        RENDER_SMALL_W to RENDER_SMALL_H,
        RENDER_4K_W to RENDER_4K_H,
    ).map { (w, h) ->
        val doc = renderDoc(w, h)            // built HERE
        val source = renderSource(w, h)      // and here
        val rect = RectPx(0, 0, w, h)
        BenchCase("region-render-${w}x$h") {
            fold(RegionRenderer.render(doc, source, rect, null, BENCH_PAPER))
        }
    }

    /**
     * "archive-read-16": `JbArchive.read` over a sixteen-tile archive.
     *
     * THE FIXTURE IS BUILT ONCE, HERE, OUTSIDE THE TIMED CLOSURE, and this is load-bearing rather
     * than tidy. The document is written with `JbArchive.write` into a [ByteArrayOutputStream] and
     * only the BYTES are kept; the `run()` reads them and folds what comes back. Building the
     * document and deflating 4 MB inside the closure would report the cost of writing an archive,
     * which is `tile-deflate`'s job -- and it would look entirely plausible.
     *
     * TWO WRITES ARE NOT BYTE-IDENTICAL and this file does not pretend they are: `ZipOutputStream`
     * stamps every entry with the current time, so two writes of the same drawing differ in their
     * headers while their CONTENTS do not. Nothing here asserts two builds agree byte for byte; the
     * round trip is what is asserted.
     */
    fun archiveRead(): List<BenchCase> {
        val fixture = archiveFixture() // built HERE, once, outside every closure
        return listOf(
            BenchCase("archive-read-$ARCHIVE_TILES") {
                val contents = JbArchive.read(ByteArrayInputStream(fixture.bytes))
                // Fold every tile's bytes, seeded by its key so two archives of different tiles cannot
                // fold alike, and mix the tile COUNT in last so a short read is a different number.
                // `read` returns a LinkedHashMap in archive order, so this is deterministic.
                var h = 0L
                for (entry in contents.tiles) h = fold(entry.value, h * 31L + entry.key.hashCode().toLong())
                h * 31L + contents.tiles.size.toLong()
            }
        )
    }

    /**
     * `psd-write`, as NOT MEASURED.
     *
     * The blueprint names three jobs and this is the third, but the PSD writer is JB-2.14c, which is
     * Draft and NOT BUILT. It is neither a pass nor a fail and it is emphatically not a zero, a null
     * or an empty list: a summary reading "6 cases, 0 failures" for a run that measured five is the
     * lie this exists to prevent. When JB-2.14c lands this is a one-method addition to this file and
     * one row in [BUDGETS_MS].
     */
    fun psdWrite(): BenchOutcome.Unavailable =
        BenchOutcome.Unavailable("psd-write", "the PSD writer is JB-2.14c and is not built")

    /**
     * Everything above, MEASURED, in the order it is reported: the two fills, the deflate, the two
     * renders, the archive read, and the unavailable one LAST so the report ends on the thing that is
     * missing.
     *
     * The clock is supplied HERE, by this file, and never read inside a case. That is what the split
     * in this package's KDoc buys: [Bench] owns no clock, and the only clock in the tree is this
     * monotonic one, which exists because the workloads are JVM.
     */
    fun all(): List<BenchOutcome> {
        val cases = floodFill() + tileDeflate() + regionRender() + archiveRead()
        val out = ArrayList<BenchOutcome>(cases.size + 1)
        for (case in cases) {
            // `measure` has no budget parameter: a budget is a fact about the PLAN and the plan is
            // this file. NaN comes back and is replaced here, so an unlisted case is NO_BUDGET.
            out += Bench.measure(case, nowMs = ::monotonicMs)
                .copy(budgetMs = budgetMsFor(case.name))
        }
        out += psdWrite()
        return out
    }

    /** The one clock in this row, and it is monotonic. Never called from inside a case. */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    // ---------------------------------------------------------------- fixtures, built before any closure

    /**
     * The flood-fill reference: 2048 x 2048 premultiplied RGBA8, white paper, black line art.
     *
     * A DRAWING, NOT A FIELD OF ONE COLOUR, because a fill over a single flat colour is the easiest
     * possible job and would measure nothing. Ink every [TILE_SIZE] px in both directions, four px
     * thick, which on a 2048 board is a closed 8 x 8 grid of 252 x 252 white cells with no way
     * between them -- EXCEPT for ONE six-pixel break, in the right-hand wall of the cell the tap lands
     * in. One break is the whole design: `gapClosePx = 3` seals a gap up to 2 x 3 px wide, so `gap3`
     * cannot leave its cell and `gap0` leaks into the next one. Two breaks in two different places
     * would be untidy rather than clearer, and a break anywhere else would be a break the tap cannot
     * reach.
     *
     * INTERNAL, and built by a function rather than inlined into the case, for one reason: a
     * benchmark whose input could differ between runs is measuring the input. `BenchCasesTest` calls
     * this twice and asserts `contentEquals`, so the input is a fact.
     */
    internal fun fillReference(): ByteArray {
        FixtureBuilds.fill++
        val rgba = ByteArray(FILL_SIDE * FILL_SIDE * 4) { WHITE_BYTE }
        // Ink every TILE_SIZE px in both directions, four px thick, so the paper is 8 x 8 cells of
        // (256 - 4) x (256 - 4) = 252 x 252 with no way between them -- EXCEPT for one six-pixel break
        // in the right-hand wall of the cell the tap lands in, and there is the whole story below.
        for (edge in 0 until FILL_SIDE step TILE_SIZE) {
            for (t in 0 until INK_THICK) {
                val vx = edge + t
                if (vx < FILL_SIDE) {
                    for (y in 0 until FILL_SIDE) {
                        if (edge == BREAK_EDGE && y >= BREAK_FROM && y < BREAK_FROM + BREAK_PX) continue
                        put(rgba, vx, y)
                    }
                }
                val hy = edge + t
                if (hy < FILL_SIDE) {
                    for (x in 0 until FILL_SIDE) {
                        put(rgba, x, hy)
                    }
                }
            }
        }
        return rgba
    }

    /** Premultiplied white: (255, 255, 255, 255). */
    private const val WHITE_BYTE: Byte = 0xFF.toByte()

    /** A break six px wide: `gapClosePx = 3` seals a gap up to 2 x 3 px wide, so this one IS sealed. */
    private const val BREAK_PX = 6

    /** The line carrying the drawing's one flaw: the right-hand wall of the cell the tap lands in. */
    private const val BREAK_EDGE = TILE_SIZE

    /** Where on that wall the break starts. Inside the seed cell's own span, so the flood reaches it. */
    private const val BREAK_FROM = 200

    /** One opaque black pixel of premultiplied RGBA8. */
    private fun put(rgba: ByteArray, x: Int, y: Int) {
        val o = (y * FILL_SIDE + x) * 4
        rgba[o] = 0
        rgba[o + 1] = 0
        rgba[o + 2] = 0
        rgba[o + 3] = WHITE_BYTE // opaque: the line is black, not transparent
    }

    /** 255 in a flood mask, which is the only other value `FloodFill.fill` ever writes. */
    private const val MASK_FILLED: Byte = 0xFF.toByte()

    private fun filledPixels(mask: ByteArray): Long {
        var n = 0L
        for (b in mask) if (b == MASK_FILLED) n++
        return n
    }

    /**
     * One PAINT layer's tile at `(tx, ty)`: a smooth gradient with a filled rectangle on it, exactly
     * [TILE_BYTES] of premultiplied RGBA8.
     *
     * Deterministic from its own address, so the same `(tx, ty)` gives the same bytes in two runs, in
     * two processes and on two machines, and two tiles of one drawing differ from each other. A
     * `TileSource` that answered every address with the same tile would make the render fast and
     * wrong and no timing would show it.
     */
    internal fun tileImage(tx: Int, ty: Int): ByteArray {
        FixtureBuilds.tile++
        val out = ByteArray(TILE_BYTES)
        val baseX = tx * TILE_SIZE
        val baseY = ty * TILE_SIZE
        var o = 0
        for (y in 0 until TILE_SIZE) {
            val gy = baseY + y
            val inBoxRow = gy % BOX_PX in BOX_MARGIN until BOX_MARGIN + BOX_PX
            for (x in 0 until TILE_SIZE) {
                val gx = baseX + x
                if (inBoxRow && gx % BOX_PX in BOX_MARGIN until BOX_MARGIN + BOX_PX) {
                    out[o] = 40
                    out[o + 1] = 90
                    out[o + 2] = 200.toByte()
                    out[o + 3] = WHITE_BYTE
                } else {
                    out[o] = (gx * 255 / BOX_PX).toByte()
                    out[o + 1] = (gy * 255 / BOX_PX).toByte()
                    out[o + 2] = 128.toByte()
                    out[o + 3] = WHITE_BYTE
                }
                o += 4
            }
        }
        return out
    }

    private const val BOX_PX = 512
    private const val BOX_MARGIN = 64

    /**
     * [image] deflated at [ARCHIVE_DEFLATE_LEVEL] -- the archive's own level, through the archive's
     * own class.
     *
     * A function of one argument so the test can hold the OUTPUT and check it is strictly shorter
     * than its input: a case that had quietly become a memcpy (an empty output, a level that
     * compresses nothing, a stream that was never finished) would still report a time and a checksum
     * and look like a working benchmark. `use` closes the stream, which finishes the deflate AND
     * releases its native memory -- sixty runs of a native deflater that is never closed is a slow
     * leak, and a leak inside a benchmark is a number that drifts upward for no reason anyone can
     * read off the report.
     */
    internal fun deflated(image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(TILE_BYTES / 4)
        // The level lives on `Deflater`, not on the stream, so the deflater is built here at the
        // archive's level and handed to the stream. `use` finishes the stream; `end` is ours to do
        // because a deflater handed in from outside is NOT ended by `close()`, and sixty runs of an
        // unended native deflater is a slow leak inside a benchmark -- a number that drifts upward
        // for no reason anybody can read off the report.
        val deflater = Deflater(ARCHIVE_DEFLATE_LEVEL)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(image) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    /** Every tile a `w x h` board at (0, 0) covers, in row-major order. */
    internal fun tileCoords(w: Int, h: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (ty in 0 until ceilDiv(h, TILE_SIZE)) {
            for (tx in 0 until ceilDiv(w, TILE_SIZE)) out += tx to ty
        }
        return out
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /**
     * A one-board, one-PAYINT-layer document whose single cel DECLARES every tile of the board, so
     * the renderer's fixture and the document agree about what exists. `DocOps.newDocument` makes the
     * shape; the `copy` adds the tile list, because "a cel that lists no tiles" is a document that
     * describes nothing to read.
     */
    internal fun renderDoc(w: Int, h: Int): JbDocument {
        FixtureBuilds.render++
        var next = 0
        val base = DocOps.newDocument("bench-render", "Bench render", w, h) { "id${next++}" }
        val keys = tileCoords(w, h).map { DocOps.key(it.first, it.second) }
        return base.copy(layers = base.layers.map { l -> l.copy(cels = l.cels.map { c -> c.copy(tiles = keys) }) })
    }

    /**
     * The `TileSource` the two render cases use: every tile of the board built ONCE, by [tileImage],
     * and answered by address.
     *
     * It logs what it was asked for, because "the render asked for every tile the cel declares" is a
     * claim about this object and not about `RegionRenderer`, and a source that returned `null`
     * everywhere would make the render very fast and very wrong: a transparent 4K flatten over paper
     * is a different job from a 4K flatten of 135 real tiles. The log is one short string per tile --
     * 135 of them against 8 294 400 blended pixels -- so it is not a part of what is measured.
     */
    internal fun renderSource(w: Int, h: Int): BenchTileSource {
        FixtureBuilds.render++
        return BenchTileSource(tileCoords(w, h).associateWith { tileImage(it.first, it.second) })
    }

    /** See [renderSource]. */
    internal class BenchTileSource(private val tiles: Map<Pair<Int, Int>, ByteArray>) : TileSource {
        /** Every address asked for, in order, as `layer/cel/tx,ty`. */
        val asked: MutableList<String> = ArrayList()

        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
            asked.add("$layerId/$celId/$tx,$ty")
            return tiles[tx to ty]
        }
    }

    /** The archive fixture: the document it was written from and the BYTES that were written. */
    internal class ArchiveFixture(
        val doc: JbDocument,
        val bytes: ByteArray,
        val keys: List<String>,
    )

    /**
     * A real `.joybrush` in memory: a [ARCHIVE_SIDE] x [ARCHIVE_SIDE] board, one PAINT cel declaring
     * all [ARCHIVE_TILES] tile keys, one tile of exactly [TILE_BYTES] per key, no strokes and no
     * thumbnail. Written ONCE with [JbArchive.write] and only the bytes kept.
     *
     * Built by [archiveRead] before its `BenchCase` exists. [BenchCasesTest] asserts the round trip
     * rather than a byte-for-byte match between two builds, because `ZipOutputStream` timestamps
     * every entry and two writes are never byte-identical.
     */
    internal fun archiveFixture(): ArchiveFixture {
        FixtureBuilds.archive++
        var next = 0
        val base = DocOps.newDocument("bench-archive", "Bench archive", ARCHIVE_SIDE, ARCHIVE_SIDE) { "id${next++}" }
        val coords = tileCoords(ARCHIVE_SIDE, ARCHIVE_SIDE)
        val keys = coords.map { DocOps.key(it.first, it.second) }
        val doc = base.copy(layers = base.layers.map { l -> l.copy(cels = l.cels.map { c -> c.copy(tiles = keys) }) })
        val layer = doc.layers[0]
        val cel = layer.cels[0]
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        for (i in coords.indices) tiles[Triple(layer.id, cel.id, keys[i])] = tileImage(coords[i].first, coords[i].second)
        val out = ByteArrayOutputStream(ARCHIVE_TILES * TILE_BYTES / 2)
        JbArchive.write(out, JbContents(doc, tiles, emptyMap(), null))
        return ArchiveFixture(doc, out.toByteArray(), keys)
    }

    /**
     * THE WORK COUNTER (R28's idiom), and it is the assertion this whole file exists to support.
     *
     * R28 replaced a wall-clock assertion with "a deterministic work counter ... add the counter as an
     * `internal` test hook". This is that, one level up: it counts FIXTURE BUILDS, which is the
     * failure mode a timing figure cannot reveal. If a fixture were ever moved inside a case's
     * `run()`, that case's reported milliseconds would include constructing its own input -- a
     * plausible, stable, completely meaningless number -- and nothing in the report would show it.
     *
     * So `BenchCasesTest` measures a full `all()` and asserts the counter does not move during it.
     * The counter costs one increment per fixture build (a handful per case, not per run), is
     * `internal` so no caller outside this module's own tests can read it, and is never consulted
     * when a benchmark is measured -- there is no path from it into any reported figure.
     */
    internal object FixtureBuilds {
        var fill = 0
        var tile = 0
        var render = 0
        var archive = 0

        val total: Int get() = fill + tile + render + archive
    }
}
