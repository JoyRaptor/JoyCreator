package cc.joycreator.joybrush.core.fill

import kotlin.math.max
import kotlin.math.min

/**
 * How forgiving [FloodFill.fill] is, and how hard it tries to close a broken line.
 *
 * The ranges are the contract (JB-2.06a). A value outside them is CLAMPED, not refused: these come
 * off a slider, and a slider that overshoots by one step must not take a fill down with it.
 */
data class FillOptions(
    /**
     * 0..1 — the largest colour distance from the SEED pixel that still counts as the same colour.
     *
     * Premultiplied RGBA, Euclidean over all four channels, divided by two — so the scale runs a
     * clean 0..1 with (0,0,0,0) and (255,255,255,255) exactly 1.0 apart. Alpha is IN the sum, and
     * that is the load-bearing part: a per-channel test would rate a nearly transparent pixel and a
     * fully transparent one as the same colour, and a fill on a soft edge would quietly change shape
     * depending on which of the two the tap landed on.
     */
    val tolerance: Float = 0.1f,
    /**
     * 0..8 — lines this far apart count as closed. The wall grows on BOTH sides, so what this
     * actually seals is a gap up to `2 * gapClosePx` px wide.
     */
    val gapClosePx: Int = 0,
    /**
     * 0..4 — the region is grown this many px past its own edge, under the line, so the anti-aliased
     * rim of a stroke is covered and no pale fringe shows.
     */
    val grow: Int = 1,
)

/**
 * The largest reference image one [FloodFill.fill] will accept, in pixels: 2^23, which is 2896 x
 * 2896, or 2048 x 2048 with 2x headroom over the spec's own performance case.
 *
 * WHY THAT NUMBER. The same power of two, and the same value, as `render.MAX_REGION_PX`, and for
 * the same reason — a fill's reference is normally the composite `RegionRenderer.render` has just
 * produced, and a fill that refused an image the renderer had accepted would be a limit you can walk
 * around by changing which door you came in through. It is NOT imported from `render`, because the
 * dependency would run the wrong way: this file is arithmetic and knows nothing about compositing.
 * The two are separate decisions that happen to agree today.
 *
 * THE FOOTPRINT, because a cap nobody has costed is a guess:
 *
 *     reference  8,388,608 x  4 B  =  33,554,432 B   (the caller's, already allocated)
 *     region     8,388,608 x  1 B  =   8,388,608 B
 *     workA      8,388,608 x  1 B  =   8,388,608 B
 *     workB      8,388,608 x  1 B  =   8,388,608 B
 *
 * Three byte arrays, reused between the gap-closing stages and the final grow, so the peak is 24 MiB
 * of engine memory whatever the options are. The flood's stack is deliberately absent from that
 * table: it holds scanline SEEDS, not pixels, so it is a few kilobytes on real art and is bounded
 * by `2 * w * h` entries on a serpentine, which cannot be reached at this cap without the reference
 * itself already being the memory.
 */
const val MAX_FILL_PX = 8_388_608L

/** Engine bytes per pixel at the peak: region + workA + workB. See [MAX_FILL_PX]. */
private const val FILL_BYTES_PER_PX = 3L

/** [MAX_FILL_PX] in mebibytes, for the refusal message. Derived, so it cannot contradict it. */
private val MAX_FILL_PEAK_MIB = MAX_FILL_PX * FILL_BYTES_PER_PX / (1024L * 1024L)

/**
 * Squared distance between the two most distant legal premultiplied colours, in byte units:
 * 4 channels x 255 x 255. This is distance 1.0 on the [FillOptions.tolerance] scale, so the limit
 * for a tolerance `t` is this times `t` times `t` and the test is a single exact comparison against
 * an Int — no sqrt, no accumulated float error, the same answer on every platform.
 */
private const val MAX_DIST_SQ = 260_100.0

/**
 * "Easy fills" (owner): tap inside line art and the region fills, gaps in the line notwithstanding,
 * with no pale fringe along an anti-aliased edge.
 *
 * WHAT THE REFERENCE IMAGE IS, and why the fill is bounded by it rather than by the document. The
 * caller hands over a w x h premultiplied RGBA8 composite — usually the line-art layer, sometimes
 * everything — and that rectangle is the entire universe of this function. A cel's tile list never
 * reaches here, which is deliberate and is the answer to "what if the fill walks into a tile the cel
 * does not have": it cannot, because a tile the cel does not have is not in the reference, and the
 * reference is the only thing this file can read an index of. A caller filling tile -1, tile 0 or
 * tile 511 therefore needs no special case here, and a fill handed a window over empty space sees
 * only that window. The price is that a caller who wants a fill bigger than one reference has to
 * tile the work itself; [MAX_FILL_PX] is where that stops being reasonable.
 *
 * ONE PREDICATE, USED TWICE. The region is defined as the 4-connected set of pixels whose colour is
 * within [FillOptions.tolerance] of the SEED pixel, and the only test that decides membership is
 * [SeedTest.within]. There is no second, "adjacent enough" test for a neighbour: a connectivity rule
 * that disagreed with the tolerance would grow a fringe of pixels that are connected by one rule and
 * a different colour by the other, and that reads on screen as dithering rather than as the logic
 * error it is. Measuring against the seed rather than against the neighbour is what makes the region
 * well defined at all — it is why a gradient stops where it stops instead of walking a ramp.
 *
 * IT TERMINATES, and it terminates because of the image rather than because of the artwork. The
 * scanline flood writes each pixel at most once (the mask IS the visited set), so its stack can
 * never hold more than `2 * w * h` seeds and its loop cannot run more than that many times. The size
 * cap above bounds memory. Neither bound can be reached by a picture that fits; a picture that does
 * not fit is refused before a byte is allocated rather than being allowed to take the UI thread with
 * it, which is the same lesson as the `MAX_REGION_PX` blocker.
 */
object FloodFill {

    /** 255 in the mask. Nothing else is ever written, so a mask can be tested without a comparison. */
    private val FILLED: Byte = 255.toByte()

    /** 1 in the internal wall/blocked arrays. */
    private val ONE: Byte = 1

    /** 0 everywhere, and the only other value a mask holds. */
    private val ZERO: Byte = 0

    private const val MAX_GAP_PX = 8
    private const val MAX_GROW_PX = 4

    /** First size of the flood's seed stack. Grows by doubling; a filled region is usually a few. */
    private const val SEED_STACK = 256

    /**
     * The region the seed is in, as a w x h mask of 255 (fill) and 0 (not).
     *
     * @param w,h size of the reference image, row 0 = top. The reference is what bounds the fill.
     * @param rgba premultiplied RGBA8, row 0 = top, at least `w * h * 4` bytes.
     * @param seedX,seedY the tap, in reference pixels. Outside the reference means no region at all
     *   rather than an error: a tap that misses the board is a thing a person does.
     * @return a mask of w x h bytes, 255 = fill and 0 = not, or an empty array if w or h is zero.
     * @throws IllegalArgumentException if a side is negative (a caller bug), if [rgba] is shorter
     *   than `w * h * 4`, or if the reference holds more than [MAX_FILL_PX] pixels. All three are
     *   refused rather than attempted, because the alternative to the third is not a slow fill but
     *   no fill at all: the mask, the two work arrays and the caller's own reference are already
     *   tens of megabytes past the point where an allocation failure is anything but a crash.
     */
    fun fill(
        w: Int,
        h: Int,
        rgba: ByteArray,
        seedX: Int,
        seedY: Int,
        options: FillOptions,
    ): ByteArray {
        val n = checkedPixels(w, h, rgba)
        val region = ByteArray(n)
        if (n == 0) return region
        val seedPixel = seedY * w + seedX
        if (seedX < 0 || seedY < 0 || seedX >= w || seedY >= h) return region

        val test = SeedTest(rgba, seedPixel shl 2, limitSquared(options.tolerance))
        val gap = options.gapClosePx.coerceIn(0, MAX_GAP_PX)
        val workA: ByteArray
        val workB: ByteArray
        if (gap == 0) {
            workA = ByteArray(n)
            workB = ByteArray(n)
            flood(w, h, seedX, seedY, region) { test.within(it) }
        } else {
            workA = ByteArray(n)   // the wall, then the flooded area
            workB = ByteArray(n)   // the dilated wall, then the dilated area
            for (i in 0 until n) if (!test.within(i)) workA[i] = ONE
            // Square dilation of the wall: it now reaches `gap` px into the gap on each side, so a
            // gap up to 2 * gap px is sealed. `region` is the scratch, which is why it is written
            // unconditionally in the branch below.
            dilate(workA, workB, region, w, h, gap)
            if (workB[seedPixel] != ZERO) {
                // Decision 2's fallback. A seed swallowed by the dilated wall has no region at all,
                // and a tap that returns nothing looks like a broken tool, so the plain flood runs.
                // `region` was the scratch a moment ago and the flood reads a non-zero byte as
                // "already visited", so it has to be wiped before it is used as a mask again.
                for (i in 0 until n) region[i] = ZERO
                flood(w, h, seedX, seedY, region) { test.within(it) }
            } else {
                // workA is free again — the wall is not needed, `test` recomputes membership from
                // the reference — so the flooded area lands in the array the wall was using. It is
                // wiped first because the flood reads a non-zero byte as "already visited", and
                // leaving the wall's 1s in there would work only by the accident that every wall
                // pixel is also blocked. That is not an accident worth depending on.
                for (i in 0 until n) workA[i] = ZERO
                flood(w, h, seedX, seedY, workA) { workB[it] == ZERO }
                // Push the area back out over the moat the wall's dilation left, but only onto
                // pixels that were within tolerance to begin with, so the fill reaches the line
                // instead of stopping a dilation's width short of it.
                dilate(workA, workB, region, w, h, gap)
                for (i in 0 until n) {
                    region[i] = if (workB[i] != ZERO && test.within(i)) FILLED else ZERO
                }
            }
        }

        val growPx = options.grow.coerceIn(0, MAX_GROW_PX)
        if (growPx == 0) return region
        // Unrestricted on purpose: this is the step that tucks the fill under the anti-aliased rim
        // of the line, which is exactly where the pixels are NOT within tolerance.
        dilate(region, workA, workB, w, h, growPx)
        return workA
    }

    /**
     * The pixel count, or the three refusals that stand in for it.
     *
     * A side of zero returns 0 rather than throwing: a board of no size is a legal thing to ask
     * about, and this is the same answer `RegionRenderer.render` gives. A NEGATIVE side throws,
     * because that is a caller bug and an empty mask would hide it. The size cap is checked BEFORE
     * the buffer, so an over-large request is refused by arithmetic rather than by an allocation
     * that was never going to succeed.
     */
    private fun checkedPixels(w: Int, h: Int, rgba: ByteArray): Int {
        require(w >= 0 && h >= 0) { "fill: ${w}x$h is a negative size, which is a caller bug" }
        if (w == 0 || h == 0) return 0
        val n = w.toLong() * h.toLong()
        require(n <= MAX_FILL_PX) {
            "fill: ${w}x$h is $n px, over the $MAX_FILL_PX px cap; one fill peaks at " +
                "$MAX_FILL_PEAK_MIB MiB of working memory on top of the reference itself, so pass a " +
                "smaller window or fill the board in strips"
        }
        require(rgba.size.toLong() >= n * 4L) {
            "fill: rgba holds ${rgba.size} B but ${w}x$h needs ${n * 4L} B"
        }
        return n.toInt()
    }

    /**
     * The tolerance as a squared distance in byte units, ready to be compared against an exact Int
     * sum of squared channel deltas. See [MAX_DIST_SQ].
     *
     * NaN becomes 0 rather than poisoning the comparison: every `d <= NaN` is false, so an unclamped
     * NaN would silently return an empty mask and look like a dead tap.
     */
    private fun limitSquared(tolerance: Float): Double {
        val t = if (tolerance.isNaN()) 0f else tolerance.coerceIn(0f, 1f)
        return MAX_DIST_SQ * t.toDouble() * t.toDouble()
    }

    /**
     * The ONE colour predicate in this file, and the only thing that decides whether a pixel joins
     * the region. Every call site — the plain flood, the wall, the gap-closing restriction — asks
     * this, which is the whole reason a fill cannot fringe: there is no second opinion anywhere.
     *
     * The four deltas are premultiplied, squared and summed, so alpha counts. Distance 1.0 is
     * (0,0,0,0) against (255,255,255,255), and the bound passed in is already squared, so this is
     * one multiplication set and one comparison. The sum is at most 4 * 255 * 255 and cannot
     * overflow an Int.
     */
    private class SeedTest(private val rgba: ByteArray, seedOffset: Int, private val limitSq: Double) {
        private val sr = byte255(rgba, seedOffset)
        private val sg = byte255(rgba, seedOffset + 1)
        private val sb = byte255(rgba, seedOffset + 2)
        private val sa = byte255(rgba, seedOffset + 3)

        fun within(pixel: Int): Boolean {
            val o = pixel shl 2
            val dr = byte255(rgba, o) - sr
            val dg = byte255(rgba, o + 1) - sg
            val db = byte255(rgba, o + 2) - sb
            val da = byte255(rgba, o + 3) - sa
            return dr * dr + dg * dg + db * db + da * da <= limitSq
        }
    }

    /** A byte as 0..255. Bytes are signed and `-1 and 0xFF` is 255, not -1. */
    private fun byte255(a: ByteArray, i: Int): Int = a[i].toInt() and 0xFF

    /**
     * 4-connected scanline flood, iterative, never recursive. [out] is the result AND the visited
     * set, so a pixel is written exactly once and the fill cannot cycle.
     *
     * TERMINATION AND MEMORY, both properties of the image rather than of the artwork. A pop that
     * finds new ground writes a run of L pixels and then examines exactly L pixels in each of the
     * two neighbouring rows, so it pushes at most 2L. The runs are disjoint — a pixel is written
     * once — so the sum of all L is at most w * h, which bounds the number of pushes, and every
     * loop iteration pops one. A seed the run above already covered is caught by the `out != ZERO`
     * test and costs nothing, and that check is also what stops the usual quadratic blow-up on a
     * comb: each run is seeded once, not once per pixel.
     *
     * THE STACK IS TWO OR THREE ENTRIES DEEP ON REAL ART, because a pop that fills a row finds the
     * row it came from already full and pushes only the next one away. That is the point of seeding
     * runs rather than pixels, and it is why the bound above is a worst case rather than a bill.
     *
     * UNITS, because this function had the bug that cost JB-2.06a its first test run: `y` and
     * `rowY` are ROW INDICES, and `popped`, `base`, `i`, `p` and every index into [out] are PIXEL
     * OFFSETS. Both are Ints, both are plausible, and passing a pixel offset to something that
     * wants a row index does not fail loudly — `y * w` is just a bigger number, the bounds check
     * rejects it, and the flood quietly fills one row and stops. Hand-derivation did not catch it
     * because every hand-derived EXPECTED VALUE was checked against the same model that produced
     * the bug. The names are the guard: anything multiplied by w is a row index, anything added to
     * `base` is a column.
     */
    private fun flood(w: Int, h: Int, seedX: Int, seedY: Int, out: ByteArray, pass: (Int) -> Boolean) {
        val seed = seedY * w + seedX
        if (!pass(seed)) return
        var stack = IntArray(SEED_STACK)
        var top = 0
        stack[top++] = seed

        fun push(pixel: Int) {
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = pixel
        }

        /**
         * One seed per unvisited run of columns `from`..`to` in ROW INDEX `rowY`, which is a row
         * index and not a pixel offset — see UNITS above. A row off the image seeds nothing.
         */
        fun seedRow(rowY: Int, from: Int, to: Int) {
            if (rowY < 0 || rowY >= h) return
            val base = rowY * w
            var x = from
            while (x <= to) {
                if (out[base + x] != ZERO || !pass(base + x)) {
                    x++
                    continue
                }
                push(base + x)
                // Skip the rest of this run. It is one region, and seeding it again is precisely
                // what turns a scanline fill quadratic.
                do {
                    x++
                } while (x <= to && pass(base + x))
            }
        }

        while (top > 0) {
            val popped = stack[--top]
            if (out[popped] != ZERO) continue
            val y = popped / w
            val base = y * w
            var i = popped
            // Walk to both ends of the run through this pixel. `i > base` is what keeps the left
            // walk on its own row instead of stepping into the row above.
            while (i > base && out[i - 1] == ZERO && pass(i - 1)) i--
            val left = i - base
            while (i < base + w - 1 && out[i + 1] == ZERO && pass(i + 1)) i++
            for (p in base + left..i) out[p] = FILLED
            seedRow(y - 1, left, i - base)
            seedRow(y + 1, left, i - base)
        }
    }

    /**
     * [dst] = [src] grown by [radius] px in all eight directions — a square (Chebyshev) element,
     * which is what "dilate by 2" means to somebody holding a fill tool, and which is why [grow] is
     * allowed to cross a one-pixel diagonal line even though the flood is 4-connected.
     *
     * Separable into one horizontal and one vertical pass, so the cost is linear in the radius and
     * not quadratic in the area of the element. Both [dst] and [tmp] are cleared first, so the
     * caller may hand over arrays it has already been using, and the three are required to be three
     * different arrays — checked rather than assumed, because an aliased pair here produces a fill
     * that is subtly wrong on some pictures and exactly right on others.
     */
    private fun dilate(src: ByteArray, dst: ByteArray, tmp: ByteArray, w: Int, h: Int, radius: Int) {
        require(src !== dst && src !== tmp && dst !== tmp) { "dilate needs three distinct arrays" }
        val n = w * h
        for (i in 0 until n) {
            dst[i] = ZERO
            tmp[i] = ZERO
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = src[row + x]
                if (v == ZERO) continue
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                for (p in row + x0..row + x1) tmp[p] = v
            }
        }
        for (y in 0 until h) {
            val row = y * w
            val y0 = max(0, y - radius) * w
            val y1 = min(h - 1, y + radius) * w
            for (x in 0 until w) {
                val v = tmp[row + x]
                if (v == ZERO) continue
                for (p in y0..y1 step w) dst[p + x] = v
            }
        }
    }
}
