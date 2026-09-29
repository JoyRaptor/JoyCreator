package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.io.ByteWriter

/**
 * The file's magic. `GIF89a` rather than `GIF87a` because this writes the transparency flag and the
 * NETSCAPE2.0 looping extension, and a `87a` label on a file using either of those is a decoder
 * being asked to guess.
 */
private val GIF_SIGNATURE = "GIF89a".encodeToByteArray()

/** The 11-byte application identifier that marks the looping extension. */
private val NETSCAPE_APP_ID = "NETSCAPE2.0".encodeToByteArray()

/** Index 0 of the global colour table is reserved for "not painted". */
private const val TRANSPARENT_INDEX = 0

/**
 * How many real colours fit alongside [TRANSPARENT_INDEX]. 255, because the table holds 256 slots
 * and one of them is the transparent index.
 */
private const val MAX_PALETTE_COLOURS = 255

/** A GIF colour table may not be smaller than this, and its size field is `log2(size) - 1`. */
private const val MIN_TABLE_SLOTS = 2

/** 2^8, the largest a GIF colour table can be. */
private const val MAX_TABLE_SLOTS = 256

/**
 * Alpha at or above this counts as painted.
 *
 * 128 rather than 255 because a GIF frame has no alpha channel — it has one "this pixel is not
 * there" index — so every alpha below 1 has to collapse to fully transparent, and 128 is where a
 * stroke's soft edge stops being visible. It is the same threshold the decoder tests against in the
 * other direction, so what this encoder calls painted is what a reader will show.
 */
private const val OPAQUE_ALPHA = 128

/**
 * The shortest a frame may be held, in hundredths of a second.
 *
 * A frame shorter than this rounds to 0 or 1, and BOTH of those are read as "as fast as you can"
 * rather than as a number: browsers substitute their own floor (Chrome lands on about 100 ms) and
 * older decoders substitute 0. Either way the frame plays at a speed nobody chose, which is the
 * whole failure this constant exists to prevent. 2 cs is 20 ms and it means what it says.
 *
 * See [delayCentiseconds] for the rounding and the other end of the range.
 */
private const val MIN_DELAY_CS = 2

/** The Graphic Control Extension delay is a u16, so 655.35 s is as long as a frame may be held. */
private const val MAX_DELAY_CS = 0xFFFF

/** The most bytes one image-data sub-block may carry, before its length byte. */
private const val MAX_SUB_BLOCK = 255

/** One past the largest LZW code: codes run 0..4095. */
private const val MAX_LZW_CODE = 4096

/** The most bits any LZW code may occupy. */
private const val MAX_CODE_BITS = 12

/**
 * Disposal method 2, "restore to background".
 *
 * Every frame here is a full-canvas image with a transparent index, so a frame that is not disposed
 * of correctly leaves the previous frame's pixels showing through the transparent parts of the new
 * one — which for an animation is a smear, not a picture. Method 2 is the cheapest disposal that
 * actually clears, and it is the one every decoder implements.
 */
private const val DISPOSAL_BACKGROUND = 2

/** The logical screen descriptor is a u16 in both directions. */
private const val MAX_SCREEN = 0xFFFF

/**
 * Ascending `(r, g, b)` order, as a total order on packed colours.
 *
 * A total order, not a partial one, is the point: the median cut sorts distinct colours with this,
 * so the palette it produces cannot depend on the order the colours were discovered in. Two
 * encoders that scan the same frames in a different order still write the same table.
 */
private val RGB_ORDER: Comparator<Int> = compareBy(
    { it ushr 16 },
    { (it ushr 8) and 0xFF },
    { it and 0xFF },
)

/**
 * One channel of a packed `0xRRGGBB` colour. Channel 0 is red, 1 green, 2 blue.
 *
 * The order matters: the median cut breaks a tie between two equally long axes by taking the LOWER
 * channel index, and `longestAxis` below relies on this numbering.
 */
private fun channelOf(colour: Int, axis: Int): Int = when (axis) {
    0 -> colour ushr 16
    1 -> (colour ushr 8) and 0xFF
    else -> colour and 0xFF
}

/** How many entries a 5-bit-per-channel lookup has: 32 to the third. */
private const val CACHE_SLOTS = 32 * 32 * 32

/**
 * The global colour table, built and then immutable.
 *
 * @param colours packed `0xRRGGBB`, one per slot, `slots` long. Slot [TRANSPARENT_INDEX] is the
 *   transparent one and its RGB is 0 — a reader that honours the transparency flag never shows it.
 * @param slots the table length, a power of two in 2..256. Written into the file as `log2(slots)-1`.
 * @param sizeField the three-bit table-size field, i.e. `log2(slots) - 1`.
 * @param minCodeSize the LZW minimum code size, `max(2, log2(slots))`.
 */
private class Palette(
    val colours: IntArray,
    val slots: Int,
    val sizeField: Int,
    val minCodeSize: Int,
)

/**
 * Builds the global colour table by median cut over every opaque pixel of every frame.
 *
 * Why one table for the whole animation and not one per frame: a frame-to-frame palette change means
 * a local colour table per frame, which roughly doubles the file and — far worse — means a pixel can
 * mean a different colour on different frames. An animation whose colours drift as it loops is not
 * an animation anybody can post.
 *
 * The cut itself: collect the DISTINCT opaque colours with their pixel counts, sort them, and start
 * with one box holding all of them. Repeatedly split the box whose longest channel range is greatest,
 * down that channel, at the weighted median; a box's colour is the weighted mean of what is in it.
 * Ties on a box's longest axis go to the lower channel index (red before green before blue) and ties
 * between boxes go to the earlier box, so the whole thing is a pure function of the pixel multiset.
 *
 * `colours` may be empty — an animation of nothing but fully transparent frames. That is legal and
 * produces a two-slot table holding only the transparent index, because a GIF colour table may not be
 * smaller than two entries.
 */
private fun buildPalette(colours: HashMap<Int, Int>): Palette {
    val distinct = colours.size
    if (distinct == 0) {
        return Palette(IntArray(MIN_TABLE_SLOTS), MIN_TABLE_SLOTS, 0, 2)
    }

    val sorted = ArrayList<Int>(distinct)
    for (key in colours.keys) sorted.add(key)
    sorted.sortWith(RGB_ORDER)
    val keys = IntArray(distinct)
    val weights = IntArray(distinct)
    for (i in 0 until distinct) {
        keys[i] = sorted[i]
        weights[i] = colours.getValue(sorted[i])
    }

    // Each box is the half-open range [from, to) into `keys`, kept as a two-element IntArray so a
    // box can be handed to the stats helpers without a data class for two ints.
    val boxes = ArrayList<IntArray>()
    boxes.add(intArrayOf(0, distinct))
    val wanted = if (distinct < MAX_PALETTE_COLOURS) distinct else MAX_PALETTE_COLOURS

    val min = IntArray(3)
    val max = IntArray(3)
    while (boxes.size < wanted) {
        var bestBox = -1
        var bestAxis = 0
        var bestExtent = -1
        for (b in boxes.indices) {
            val from = boxes[b][0]
            val to = boxes[b][1]
            if (to - from < 2) continue // one colour cannot be split any further
            channelRanges(keys, from, to, min, max)
            val axis = longestAxis(min, max)
            val extent = max[axis] - min[axis]
            // Strictly greater, so a tie keeps the earlier box and the run is order-independent.
            if (extent > bestExtent) {
                bestExtent = extent
                bestAxis = axis
                bestBox = b
            }
        }
        if (bestBox < 0 || bestExtent <= 0) break // every box is a single colour; done

        val from = boxes[bestBox][0]
        val to = boxes[bestBox][1]
        val splitAt = weightedMedian(weights, from, to)
        check(splitAt > from && splitAt < to) {
            "median cut split box [$from,$to) at $splitAt on channel $bestAxis, which does not " +
                "make two non-empty boxes; the colours cannot be reduced any further"
        }
        boxes[bestBox] = intArrayOf(from, splitAt)
        boxes.add(intArrayOf(splitAt, to))
    }

    // Weighted mean per box. Integer division, not floating point: a mean is a number a palette
    // entry turns on, and integer division is the same sum on every platform there is.
    val boxColours = IntArray(boxes.size)
    for (b in boxes.indices) {
        val from = boxes[b][0]
        val to = boxes[b][1]
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var sw = 0L
        for (j in from until to) {
            val c = keys[j]
            val w = weights[j].toLong()
            sr += (c ushr 16).toLong() * w
            sg += ((c ushr 8) and 0xFF).toLong() * w
            sb += (c and 0xFF).toLong() * w
            sw += w
        }
        val r = (sr / sw).toInt()
        val g = (sg / sw).toInt()
        val bl = (sb / sw).toInt()
        boxColours[b] = (r shl 16) or (g shl 8) or bl
    }

    // Round the table up to a power of two, at least two slots and at most 256. `bits` is tracked by
    // hand rather than with a log function so nothing here needs a platform call.
    val needed = boxes.size + 1 // slot 0 is the transparent one
    var slots = MIN_TABLE_SLOTS
    var bits = 1
    while (slots < needed) {
        slots = slots shl 1
        bits++
    }
    // The loop above cannot overshoot, because `boxes.size` is capped at MAX_PALETTE_COLOURS and so
    // `needed` is at most 256. Asserted rather than clamped: a clamp here would leave `bits` and
    // `slots` disagreeing, and a size field computed from one and a table written from the other is
    // a file whose table is the wrong length — the kind of thing that only some readers notice.
    check(slots in MIN_TABLE_SLOTS..MAX_TABLE_SLOTS && slots >= needed) {
        "a table of $needed colours rounded up to $slots slots, which is outside " +
            "$MIN_TABLE_SLOTS..$MAX_TABLE_SLOTS"
    }

    val table = IntArray(slots)
    for (i in boxes.indices) table[i + 1] = boxColours[i]
    // Pad by repeating the last real colour. No pixel ever maps to a padding slot, and repeating
    // beats leaving black: a reader that ignores the transparent flag and reads index 0..n anyway
    // then shows a plausible colour instead of a black band.
    if (boxes.isNotEmpty()) {
        val last = boxColours[boxes.size - 1]
        var i = boxes.size + 1
        while (i < slots) {
            table[i] = last
            i++
        }
    }
    return Palette(table, slots, bits - 1, if (bits < 2) 2 else bits)
}

/** The per-channel min and max of `keys[from until to]`, written into [min] and [max]. */
private fun channelRanges(keys: IntArray, from: Int, to: Int, min: IntArray, max: IntArray) {
    for (axis in 0..2) {
        min[axis] = channelOf(keys[from], axis)
        max[axis] = min[axis]
    }
    for (i in from + 1 until to) {
        val c = keys[i]
        for (axis in 0..2) {
            val v = channelOf(c, axis)
            if (v < min[axis]) min[axis] = v
            if (v > max[axis]) max[axis] = v
        }
    }
}

/** The channel with the greatest range, lower channel index winning a tie. */
private fun longestAxis(min: IntArray, max: IntArray): Int {
    val r = max[0] - min[0]
    val g = max[1] - min[1]
    val b = max[2] - min[2]
    return when {
        r >= g && r >= b -> 0
        g >= b -> 1
        else -> 2
    }
}

/**
 * Where to cut `weights[from until to]` so the two halves each hold about half the PIXELS.
 *
 * A pixel count and not a colour count, because the table is judged on the colours a viewer actually
 * sees: the pixel that happens to cover half the canvas counts as much as the pixel that appears
 * once. The colours are already sorted on the axis being split, so walking the weights in order
 * lands exactly on the median.
 *
 * The result is clamped to `from + 1 .. to - 1`, and that clamp is not defensive padding — it is
 * load-bearing, and without it a perfectly ordinary image throws. The median itself can land ON the
 * end: in a box of two colours where the second one holds most of the pixels, the running total
 * only reaches half at the last element, so the raw median is `to` and one of the two halves would
 * be empty. A split that does not make two non-empty boxes is not a smaller table, it is a loop
 * that makes no progress, so the cut is pulled back to the nearest position that does.
 */
private fun weightedMedian(weights: IntArray, from: Int, to: Int): Int {
    var total = 0L
    for (i in from until to) total += weights[i].toLong()
    val half = (total + 1L) / 2L
    var cumulative = 0L
    var i = from
    while (i < to) {
        cumulative += weights[i].toLong()
        if (cumulative >= half) return (i + 1).coerceIn(from + 1, to - 1)
        i++
    }
    // Unreachable for a non-empty box. Thrown rather than fallen through from, because returning a
    // position outside the box would make the CALLER's check fire with the wrong line number, and a
    // wrong line number is how a real bug goes unfound.
    throw IllegalStateException(
        "weighted median of box [$from,$to) never reached half of its $total pixels",
    )
}

/**
 * Maps a colour to its palette index, memoised in a 32x32x32 table.
 *
 * The cache key is the colour with 3 bits dropped off the bottom of each channel. That makes the
 * lookup a pure function of the KEY, not of whichever pixel happened to reach the table first, so
 * two encoders that see the same frame quantise it identically — and it is the reason the result
 * can be quoted as exact rather than "close enough, it was a cache hit".
 *
 * The cost, stated plainly: the search runs against the key expanded back to 8 bits (each channel a
 * multiple of 8, 0 to 248), so a lookup can be up to 7 per channel away from the colour that was
 * asked about. That is a fifth of a 256-step channel, and the palette entries are themselves means,
 * so the error a viewer sees is the palette's and not the cache's. The alternative — searching all
 * 8 bits and caching by bucket anyway — is both slower to reason about and no more accurate in
 * practice, since the same boundary pixels would land on whichever side of a bucket edge they fell.
 *
 * Ties in the nearest-colour search go to the LOWER index, which is the same tie-break the median
 * cut uses, so the table stays stable under a re-cut.
 */
private class PaletteMapper(private val table: IntArray) {
    private val cache = IntArray(CACHE_SLOTS)

    fun indexOf(r: Int, g: Int, b: Int): Int {
        val key = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
        val hit = cache[key]
        if (hit != 0) return hit
        val found = nearest((r ushr 3) shl 3, (g ushr 3) shl 3, (b ushr 3) shl 3)
        cache[key] = found
        return found
    }

    private fun nearest(r: Int, g: Int, b: Int): Int {
        var best = 1
        var bestDistance = Int.MAX_VALUE
        for (i in 1 until table.size) {
            val c = table[i]
            val dr = ((c ushr 16) and 0xFF) - r
            val dg = ((c ushr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
                if (distance == 0) break
            }
        }
        return best
    }
}

/**
 * A frame's delay in hundredths of a second, the unit the Graphic Control Extension stores.
 *
 * Rounded half up, then clamped to [MIN_DELAY_CS]..[MAX_DELAY_CS]. The clamp at the bottom is the
 * one that matters and it is a decision, not a shrug: a delay of 0 or 1 is not read as a duration by
 * any decoder worth the name, so a sub-10 ms frame would play at a speed the file never stated. 2 cs
 * is 20 ms, the shortest hold every reader agrees on. The clamp at the top is the u16 field running
 * out, and it is applied to the input first so the rounding below cannot overflow.
 */
private fun delayCentiseconds(delayMs: Int): Int {
    val safe = delayMs.coerceIn(0, MAX_DELAY_CS * 10)
    val rounded = (safe + 5) / 10
    return rounded.coerceIn(MIN_DELAY_CS, MAX_DELAY_CS)
}

/**
 * Writes one frame's pixels as a GIF89a animation: one global colour table over every frame, and one
 * full-canvas image per frame.
 *
 * Pure and deterministic, which is the whole point. The same frames in the same order produce the
 * same bytes on the phone, on the PC and in a test — no clock, no locale, no random source, and no
 * hash-map iteration order anywhere in the byte stream. Concretely, that means:
 *
 *  - the palette is median-cut over a colour list SORTED into a total order, so it cannot depend on
 *    the order pixels were counted in, nor on which hash a colour landed under;
 *  - the quantisation cache is a pure function of a 5-bit key, so it cannot depend on visit order
 *    either (see [PaletteMapper]);
 *  - the LZW dictionary is only ever looked up, never walked, so its hash order is not observable;
 *  - every mean is an integer division of an integer sum.
 *
 * Frames are full-canvas with disposal method 2. That costs bytes a delta encoder would save, and it
 * buys the one property an animation has to have: a frame is what that frame looked like, and no
 * viewer is asked to reconstruct it from a neighbour.
 *
 * No dithering. Ordered dithering on a 255-colour table looks better on a still, and on an animation
 * it shimmers, because the pattern is fixed to the screen while the picture moves under it. A flat
 * band is stable; a dither is not.
 *
 * ## Timing
 *
 * [addFrame] takes a delay per frame in milliseconds and this class does nothing else with timing,
 * so it is not coupled to the document model. When it is wired to an animation board, the delays come
 * from `cc.joycreator.joybrush.core.doc.AnimOps.frameStartsMs`: frame `i` is held for
 * `frameStarts[i + 1] - frameStarts[i]` ms (and the last for `totalDurationMs - frameStarts[last]`),
 * which is `holdFrames * 1000.0 / fps` each. Taking the differences rather than recomputing keeps the
 * GIF's total playback time equal to the board's, to the centisecond the format allows. A hold of 0
 * is impossible there — the validator refuses it — and this class would clamp it to 2 cs anyway.
 */
class GifEncoder(val width: Int, val height: Int, val loop: Boolean = true) {

    private class PendingFrame(val rgba: ByteArray, val delayMs: Int)

    private val frames = ArrayList<PendingFrame>()

    init {
        require(width in 1..MAX_SCREEN) { "GIF width must be 1..$MAX_SCREEN, got $width" }
        require(height in 1..MAX_SCREEN) { "GIF height must be 1..$MAX_SCREEN, got $height" }
    }

    /**
     * Adds one frame.
     *
     * @param rgba straight RGBA8, row 0 at the TOP, exactly [width] by [height] — the same layout
     *   the rest of the engine hands around, and the same one [cc.joycreator.joybrush.core.export.SpritePacker]
     *   takes, so a packed sheet and a GIF of the same cells take the same array.
     * @param delayMs how long to hold it, rounded to hundredths of a second and floored at 2 cs
     *   (20 ms). See [delayCentiseconds] for why there is a floor at all.
     *
     * The size is checked HERE rather than at [finish], so a wrong-shaped frame is a failure at the
     * call that got it wrong instead of a mystery twenty frames later.
     *
     * @throws IllegalArgumentException if [rgba] is not exactly `width * height * 4` bytes.
     */
    fun addFrame(rgba: ByteArray, delayMs: Int) {
        // Long arithmetic: width * height * 4 overflows an Int for a large-but-reasonable canvas,
        // and an overflowed size check is a size check that passes.
        val expected = width.toLong() * height.toLong() * 4L
        require(rgba.size.toLong() == expected) {
            "a frame is ${rgba.size} bytes; a ${width}x$height RGBA8 frame is $expected"
        }
        frames.add(PendingFrame(rgba, delayMs))
    }

    /**
     * Builds the whole file.
     *
     * Reads nothing and mutates nothing, so calling it twice returns the same bytes twice — which is
     * the cheapest possible determinism test, and the one the commonTest suite runs.
     *
     * @throws IllegalStateException if no frame has been added. A GIF with no image block is a
     *   legal file that shows nothing, and handing one back to an export button is a confidently
     *   wrong answer; refusing says the same thing out loud.
     */
    fun finish(): ByteArray {
        check(frames.isNotEmpty()) {
            "no frames: there is nothing to animate, so there is no GIF to write. Add a frame first."
        }

        val opaque = HashMap<Int, Int>()
        for (frame in frames) countOpaque(frame.rgba, opaque)
        val palette = buildPalette(opaque)
        val mapper = PaletteMapper(palette.colours)

        val out = ByteWriter()
        out.bytes(GIF_SIGNATURE)

        // Logical screen descriptor. The packed byte is: global table present, colour resolution 7
        // (eight bits per primary, which is what the table below actually holds), no sort flag, and
        // the table size. The resolution field is a hint some old readers used to scale the display,
        // so it is set honestly rather than left at zero.
        //
        // IT IS SEVEN BYTES, and that is the whole of the second bug this file had. The descriptor is
        // width(2), height(2), packed(1), background colour index(1), pixel aspect ratio(1) — the last
        // two are easy to forget because a decoder is supposed to ignore them, and one that does not
        // ignore them reads the colour table two bytes early and then believes every block after it
        // starts somewhere else. GDI+ rejected the file and javax.imageio reported "Unexpected block
        // type 11", which is the NETSCAPE application id length being read as a block introducer.
        // Both are the same two missing bytes seen from two directions.
        out.u16(width)
        out.u16(height)
        out.u8(0x80 or (7 shl 4) or palette.sizeField)
        out.u8(TRANSPARENT_INDEX) // background: the transparent slot, so no background is ever painted
        out.u8(0x00) // pixel aspect ratio: none declared, which is what every encoder writes

        for (i in 0 until palette.slots) {
            val c = palette.colours[i]
            out.u8(c ushr 16)
            out.u8((c ushr 8) and 0xFF)
            out.u8(c and 0xFF)
        }

        if (loop) writeLoopExtension(out)

        for (frame in frames) {
            // Graphic Control Extension: disposal 2 and the transparent flag, so each frame stands
            // on its own and the areas it does not paint are the background rather than the frame
            // before it.
            out.u8(0x21)
            out.u8(0xF9)
            out.u8(0x04)
            out.u8((DISPOSAL_BACKGROUND shl 2) or 0x01)
            out.u16(delayCentiseconds(frame.delayMs))
            out.u8(TRANSPARENT_INDEX)
            out.u8(0x00)

            // Image Descriptor: the whole canvas at the origin, no local colour table, not
            // interlaced. Interlacing would help a progressive-loading preview and would cost a
            // second pass over the pixels for a file that is usually shown whole.
            out.u8(0x2C)
            out.u16(0)
            out.u16(0)
            out.u16(width)
            out.u16(height)
            out.u8(0x00)

            out.u8(palette.minCodeSize)
            writeSubBlocks(out, lzwEncode(quantise(frame.rgba, mapper), palette.minCodeSize))
        }

        out.u8(0x3B)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ the pixels

    /**
     * Every pixel as a palette index, in row-major order.
     *
     * Pixels below [OPAQUE_ALPHA] become [TRANSPARENT_INDEX] and never reach the mapper, which is
     * what keeps a fully transparent frame from inventing a colour for its own emptiness.
     */
    private fun quantise(rgba: ByteArray, mapper: PaletteMapper): IntArray {
        val count = width * height
        val indices = IntArray(count)
        var i = 0
        while (i < count) {
            val at = i shl 2
            if ((rgba[at + 3].toInt() and 0xFF) >= OPAQUE_ALPHA) {
                indices[i] = mapper.indexOf(
                    rgba[at].toInt() and 0xFF,
                    rgba[at + 1].toInt() and 0xFF,
                    rgba[at + 2].toInt() and 0xFF,
                )
            }
            i++
        }
        return indices
    }

    /** Adds every opaque pixel's colour to [into], counted. The count is what weights the cut. */
    private fun countOpaque(rgba: ByteArray, into: HashMap<Int, Int>) {
        val count = width * height
        var i = 0
        while (i < count) {
            val at = i shl 2
            if ((rgba[at + 3].toInt() and 0xFF) >= OPAQUE_ALPHA) {
                val colour = ((rgba[at].toInt() and 0xFF) shl 16) or
                    ((rgba[at + 1].toInt() and 0xFF) shl 8) or
                    (rgba[at + 2].toInt() and 0xFF)
                into[colour] = (into[colour] ?: 0) + 1
            }
            i++
        }
    }

    // ------------------------------------------------------------------ the container blocks

    /**
     * The NETSCAPE2.0 application extension that says how many times to play.
     *
     * Loop count 0 means forever, and it is the only count this writes: [GifEncoder] can loop or not,
     * and "plays 3 times" is a field a decoder may ignore entirely. Omitted altogether when [loop]
     * is false — a file with no extension plays once in every reader, which is the honest way to say
     * it.
     */
    private fun writeLoopExtension(out: ByteWriter) {
        out.u8(0x21)
        out.u8(0xFF)
        out.u8(NETSCAPE_APP_ID.size)
        out.bytes(NETSCAPE_APP_ID)
        out.u8(0x03) // one three-byte sub-block follows
        out.u8(0x01) // ...of which this is the sub-block's ID: the NETSCAPE looping block
        out.u16(0) // 0 = forever
        out.u8(0x00) // block terminator
    }

    /**
     * The compressed image data, in sub-blocks of at most [MAX_SUB_BLOCK] bytes each, then the
     * terminator.
     *
     * Getting this wrong truncates the image rather than failing: a decoder reads a sub-block, gets
     * bytes that are not there, and stops with whatever it had. So `n` is a LENGTH and `offset` is
     * the start of the block, they are kept as separate names, and the loop asserts that it moved.
     */
    private fun writeSubBlocks(out: ByteWriter, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val start = offset
            val remaining = data.size - start
            val n = if (remaining > MAX_SUB_BLOCK) MAX_SUB_BLOCK else remaining
            require(n in 1..MAX_SUB_BLOCK) {
                "sub-block length $n at offset $start of ${data.size} bytes is outside " +
                    "1..$MAX_SUB_BLOCK, so the image data would be malformed"
            }
            out.u8(n)
            var i = 0
            while (i < n) {
                out.u8(data[start + i].toInt() and 0xFF)
                i++
            }
            offset = start + n
            // The stall guard. A loop that writes a chunk and recomputes its cursor as the chunk's
            // LENGTH rather than its END never moves, and the ByteWriter grows until the process
            // dies with no line number anywhere. This is the assertion that turns that into a
            // failure here, with this line in the trace.
            check(offset > start) {
                "the sub-block loop did not advance: wrote $n bytes at offset $start of " +
                    "${data.size} and left the cursor at $offset"
            }
        }
        out.u8(0x00)
    }

    // ------------------------------------------------------------------ LZW

    /**
     * GIF-flavoured LZW: variable-width codes, least-significant bit first, with a clear code at the
     * start and whenever the table fills, and an end-of-information code last.
     *
     * The width rules are the whole of it, and they are the reason a hand-rolled encoder produces a
     * file that some viewers open and others show as garbage — so they are pinned here rather than
     * left to the shape of the loop:
     *
     *  - codes start [minCodeSize] + 1 bits wide; [minCodeSize] is at least 2 whatever the palette
     *    holds, because a code width of one bit has no clear code to speak of and readers reject it;
     *  - the width grows when the next free code becomes `1 shl width` — that is, immediately AFTER
     *    the code that fills the current width is handed out, so the reader, which builds the same
     *    table one entry behind, switches at the same point;
     *  - the width is NOT grown when the next free code reaches 4096. The reader's own rule stops
     *    there too, and growing anyway would put a 13-bit code in the file that no decoder expects.
     *    The table is emptied instead;
     *  - on a full table the clear code goes out BEFORE the current string is reset, so the reader
     *    is standing at a known state when the next code arrives.
     */
    private fun lzwEncode(indices: IntArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        val out = ByteWriter()

        // (prefix, next symbol) -> code. Only ever looked up; never walked, so the hash order cannot
        // reach the byte stream.
        val dictionary = HashMap<Int, Int>()
        var nextCode = clearCode + 2
        var codeSize = minCodeSize + 1
        var bitBuffer = 0
        var bitCount = 0

        fun emit(code: Int, size: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                out.u8(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        // A clear code first, always. Readers that do not expect one handle it; readers that expect
        // one and do not get it start decoding from a table they never built.
        emit(clearCode, codeSize)

        if (indices.isNotEmpty()) {
            var prefix = indices[0]
            var i = 1
            while (i < indices.size) {
                val symbol = indices[i]
                val key = prefix * 256 + symbol
                val known = dictionary[key]
                if (known != null) {
                    prefix = known
                } else {
                    emit(prefix, codeSize)
                    if (nextCode < MAX_LZW_CODE) {
                        dictionary[key] = nextCode
                        nextCode++
                        // THE WIDTH RULE, and it is `>` rather than `>=`, which is the whole of
                        // this bug once. The reader builds the same table one entry LATER than this
                        // writer: the writer hands out a code here, and the reader only gets round to
                        // adding the matching entry when it reads the code AFTER this one. So when
                        // this writer's next free code reaches `1 shl codeSize` the reader is still
                        // one short of it and is reading at the old width — widening here writes a
                        // 4-bit code into a stream the reader is still slicing 3 bits at a time, and
                        // the picture that comes out the other end is noise.
                        //
                        // GDI+ and javax.imageio both disagree with `>=`, and both were used as the
                        // oracle for this: a 4x1 file written either way is the smallest case that
                        // separates them.
                        if (nextCode > (1 shl codeSize) && nextCode < MAX_LZW_CODE) {
                            codeSize++
                        }
                    } else {
                        // Table full: clear it and start over. The width goes back to its opening
                        // value because that is what the reader does, and a width that stayed at 12
                        // would be read as 12.
                        emit(clearCode, codeSize)
                        dictionary.clear()
                        nextCode = clearCode + 2
                        codeSize = minCodeSize + 1
                    }
                    prefix = symbol
                }
                i++
            }
            emit(prefix, codeSize)
        }
        emit(endCode, codeSize)

        if (bitCount > 0) {
            out.u8(bitBuffer and 0xFF)
            bitBuffer = 0
            bitCount = 0
        }
        check(codeSize in (minCodeSize + 1)..MAX_CODE_BITS) {
            "LZW code width ended at $codeSize bits, which is outside " +
                "${minCodeSize + 1}..$MAX_CODE_BITS; the reader would desynchronise here"
        }
        return out.toByteArray()
    }
}
