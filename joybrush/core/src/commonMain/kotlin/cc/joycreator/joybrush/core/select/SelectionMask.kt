package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * WHICH pixels a selection covers, and by how much. Nothing else: no gesture, no GL, no
 * transform box (that is JB-2.05b). This is the arithmetic those will draw.
 *
 * COVERAGE, 0..255 per document pixel, sparse by [Tiles.SIZE] x [Tiles.SIZE] byte tiles keyed the
 * way `paint.Tiles` keys everything else. An absent tile is 0 everywhere, which is what makes a
 * lasso over a 2048 square cost 4 MB rather than the whole unbounded canvas.
 *
 * COORDINATES. Pixel (x, y) covers [x, x+1) x [y, y+1) and may be negative, because the document
 * canvas is unbounded and a selection is allowed to start off the top left of it. Every tile index
 * here is a FLOOR division, never a truncating `/`: floorDiv(-1, 256) is -1, `-1 / 256` is 0, and
 * the second one quietly addresses the wrong 256 pixels. [tileOfPx] is that floor division, written
 * out, because it is the single most load-bearing line in the file.
 *
 * IMMUTABLE. Every operation returns a new mask and no byte of an existing one is ever written to,
 * so a caller can hold a selection, keep drawing, and combine the old one afterwards.
 *
 * And immutable from OUTSIDE, which is the half that is easy to promise and easy not to keep.
 * [FULL_TILE] is shared by every mask that has a completely full tile and `writable` copies it
 * before anything is written — but that rule only protects the masks if a caller cannot reach the
 * shared bytes in the first place, and one `val tiles: Map<Long, ByteArray>` was a hole straight
 * through it: a single write into `FULL_TILE` would corrupt every solid mask in the program at
 * once, and nothing in the engine would have said which one to distrust. So the tile map is
 * `internal`, and what crosses the line is [coverage], [bounds], [tileKeys] and [tile] — the last
 * of which hands back a COPY. What it costs is 64 KB a call, which is the price of the promise and
 * of the rule that a caller wanting one pixel calls [coverage] instead.
 */
class SelectionMask internal constructor(internal val tiles: Map<Long, ByteArray>) {

    /** How much of pixel (x, y) is selected, 0..255. An absent tile is 0 — not an error. */
    fun coverage(x: Int, y: Int): Int {
        val key = Tiles.key(tileOfPx(x), tileOfPx(y))
        val tile = tiles[key] ?: return 0
        return tile[indexInTile(x, y)].toInt() and 0xFF
    }

    /**
     * The tight box of every pixel with coverage above zero, or null when the mask is empty.
     *
     * It is the box of the COVERAGE and not of the shape that produced it, so an antialiased edge
     * stretches it by up to a pixel; that is the honest answer for "what is selected" and the only
     * one a marching-ants border can be drawn from.
     */
    fun bounds(): RectPx? {
        if (tiles.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (entry in tiles) {
            val key = entry.key
            val tile = entry.value
            val tileX = Tiles.tx(key) * Tiles.SIZE
            val tileY = Tiles.ty(key) * Tiles.SIZE
            for (row in 0 until Tiles.SIZE) {
                val base = row * Tiles.SIZE
                var first = -1
                var last = -1
                for (col in 0 until Tiles.SIZE) {
                    if ((tile[base + col].toInt() and 0xFF) != 0) {
                        if (first < 0) first = col
                        last = col
                    }
                }
                if (first < 0) continue
                val y = tileY + row
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val lo = tileX + first
                val hi = tileX + last
                if (lo < minX) minX = lo
                if (hi > maxX) maxX = hi
            }
        }
        if (minX > maxX) return null
        return RectPx(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** True when nothing at all is selected. */
    val isEmpty: Boolean
        get() = tiles.isEmpty()

    /**
     * The [Tiles.key] of every tile that holds coverage, as a fresh set the caller owns.
     *
     * The tiles themselves are deliberately not in here, and that is the point: this is how a
     * renderer walks the tiles it needs to draw, with no way to write a byte back through the walk.
     */
    val tileKeys: Set<Long>
        get() = tiles.keys.toSet()

    /**
     * A COPY of one tile's coverage bytes, or null when [key] names a tile that holds nothing.
     *
     * A copy rather than the array, because that array is shared with every other mask holding the
     * same tile — and, when it came out of [finalise], with the process-wide [FULL_TILE]. Copying
     * is what makes "no byte of an existing mask is ever written to" true of a caller as well as of
     * this file.
     */
    fun tile(key: Long): ByteArray? = tiles[key]?.copyOf()

    /**
     * The union, per pixel `max(a, b)`.
     *
     * Max rather than a+b so that adding an overlapping selection cannot push a pixel past 255 and
     * wrap, and so that adding the same selection twice is the same as adding it once — which is
     * what a "grow the selection" gesture expects.
     */
    fun add(o: SelectionMask): SelectionMask {
        if (o.tiles.isEmpty()) return this
        if (tiles.isEmpty()) return o
        val out = HashMap<Long, ByteArray>(tiles.size + o.tiles.size)
        // Copied, not aliased: the next loop writes through these arrays, and this mask is not
        // allowed to change.
        for (entry in tiles) out[entry.key] = entry.value.copyOf()
        for (entry in o.tiles) {
            val b = entry.value
            val a = writable(out, entry.key)
            for (i in b.indices) {
                val bv = b[i].toInt() and 0xFF
                if (bv > (a[i].toInt() and 0xFF)) a[i] = b[i]
            }
        }
        return SelectionMask(finalise(out))
    }

    /**
     * The difference: `a * (255 - b) / 255`, rounded to nearest.
     *
     * MULTIPLYING rather than setting a pixel to 0 is the whole point. Subtracting a soft-edged
     * lasso from a hard-edged rectangle has to leave a soft edge, and `a - b` clamped at 0 would
     * leave a hard one. 0 stays 0 and 255 stays 255 exactly, since 255 * 255 / 255 is 255.
     */
    fun subtract(o: SelectionMask): SelectionMask {
        if (tiles.isEmpty()) return EMPTY
        if (o.tiles.isEmpty()) return this
        val out = HashMap<Long, ByteArray>(tiles.size + o.tiles.size)
        for (entry in tiles) out[entry.key] = entry.value.copyOf()
        for (entry in o.tiles) {
            val b = entry.value
            val a = out[entry.key] ?: continue
            for (i in b.indices) {
                val av = a[i].toInt() and 0xFF
                if (av == 0) continue
                val bv = b[i].toInt() and 0xFF
                if (bv == 0) continue
                a[i] = ((av * (255 - bv) + 127) / 255).toByte()
            }
        }
        return SelectionMask(finalise(out))
    }

    /**
     * The intersection: `a * b / 255`, rounded to nearest — the same rule as [subtract] with the
     * complement, and the reason intersecting a soft selection with a hard one is soft.
     *
     * THE LOOP IS OVER `o`, NOT OVER `this`, and that is the whole difference between an
     * intersection and a "mostly this". A tile of `o` that `this` does not have is `a AND 0` over
     * 65536 pixels, which is 0 across the tile, so it contributes NOTHING and is left out; a tile of
     * `this` that `o` does not have is dropped the same way, because it is not in the loop at all.
     * Starting from a copy of `this` and skipping keys `o` does not have — which is what the loop
     * did before — quietly returns the first operand for any pair that does not share a tile, so
     * two lassoes on opposite sides of the canvas intersected to the whole of the first one, and
     * `a.intersect(b)` and `b.intersect(a)` were different answers to the same question.
     *
     * [subtract] iterates the same way and is NOT the same bug: there a key missing from `o` means
     * "subtract nothing", and `a - 0` is `a`, so keeping it is the arithmetic rather than a
     * shortcut around it. The identities differ; the loop shape is allowed to.
     */
    fun intersect(o: SelectionMask): SelectionMask {
        if (tiles.isEmpty() || o.tiles.isEmpty()) return EMPTY
        val out = HashMap<Long, ByteArray>(minOf(tiles.size, o.tiles.size))
        for (entry in o.tiles) {
            val mine = tiles[entry.key] ?: continue
            // Copied here rather than up front: a tile dropped by `continue` is never written to,
            // so it never needed a copy at all.
            val a = mine.copyOf()
            val b = entry.value
            for (i in b.indices) {
                val av = a[i].toInt() and 0xFF
                if (av == 0) continue
                // No special cases for the ends: av * 0 + 127 is 127, which is 0 in 255ths, and
                // av * 255 + 127 is av * 255 + 127, which divides back to av.
                a[i] = ((av * (b[i].toInt() and 0xFF) + 127) / 255).toByte()
            }
            out[entry.key] = a
        }
        return SelectionMask(finalise(out))
    }

    /**
     * `255 - a` inside [within] and 0 outside it. The box is a rectangle rather than "the rest of the
     * document" because the document has no rest: inverting over an unbounded canvas is not a
     * picture, and the caller has to say how far it meant.
     *
     * A box of no size in either direction is an empty selection, not an error, which is the same
     * answer [intersect] gives for an empty operand. A box whose far corner is off the end of Int
     * is a caller bug and says so, because `y until y + h` with the sum wrapped round is an empty
     * range and would return [EMPTY] looking exactly like a box that selects nothing.
     *
     * @throws IllegalArgumentException if the box's far corner is not an Int pixel, which the
     *   arithmetic below cannot survive and which no document can ask for.
     */
    fun invert(within: RectPx): SelectionMask {
        if (within.w <= 0 || within.h <= 0) return EMPTY
        require(within.fitsIntPixels()) {
            "invert: ${within.w}x${within.h} at ${within.x},${within.y} runs off the end of an Int pixel"
        }
        val out = HashMap<Long, ByteArray>()
        // Safe to add in Int now, and only now: `require` above has proved neither sum wraps.
        val yEnd = within.y + within.h
        val xEnd = within.x + within.w
        for (y in within.y until yEnd) {
            for (x in within.x until xEnd) {
                val c = 255 - coverage(x, y)
                if (c == 0) continue
                val arr = writable(out, Tiles.key(tileOfPx(x), tileOfPx(y)))
                arr[indexInTile(x, y)] = c.toByte()
            }
        }
        return SelectionMask(finalise(out))
    }

    companion object {

        /** Nothing selected. Also the answer to every piece of rubbish [polygon] is handed. */
        val EMPTY: SelectionMask = SelectionMask(emptyMap())

        /**
         * A closed polygon filled with the NON-ZERO winding rule, antialiased 4 x 4. The work is
         * [Lasso]'s; this is only the door.
         *
         * A polygon of fewer than three points, one holding a NaN or an infinity, or one whose
         * bounding box is more than [MAX_SELECT_SPAN] px in either direction, is [EMPTY] rather
         * than an exception: every caller of a lasso is a gesture, and a gesture is a person.
         */
        fun polygon(points: List<Pt>): SelectionMask = Lasso.rasterise(points)

        /**
         * A rectangle, as a polygon of its four corners.
         *
         * Which is not an implementation convenience but a guarantee: a polygon of integer corners
         * sampled at (i + 0.5) / 4 has every sample of every pixel wholly inside or wholly outside,
         * so `rect` is EXACTLY 255 inside and EXACTLY 0 outside. The marquee, which is a rectangle
         * while the finger is down, is therefore pixel-exact rather than antialiased, which is what
         * makes a selection edge land where the person drew it.
         *
         * @throws IllegalArgumentException if the box's far corner is not an Int pixel: `r.x + r.w`
         *   is an Int sum and would wrap round to a negative right edge, and a rectangle whose right
         *   edge is left of its left one is a selection of nothing rather than a selection.
         */
        fun rect(r: RectPx): SelectionMask {
            if (r.w <= 0 || r.h <= 0) return EMPTY
            require(r.fitsIntPixels()) {
                "rect: a ${r.w}x${r.h} box at ${r.x},${r.y} runs off the end of an Int pixel"
            }
            val left = r.x.toDouble()
            val top = r.y.toDouble()
            // Long, then Double: `r.x + r.w` is an Int sum, and the `require` above is what makes
            // it a sum rather than a wrap.
            val right = (r.x.toLong() + r.w).toDouble()
            val bottom = (r.y.toLong() + r.h).toDouble()
            return polygon(
                listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)),
            )
        }

        /**
         * An ellipse as a polygon of [ELLIPSE_STEPS] points on it, so it inherits the antialiasing
         * and the rubbish handling of [polygon] and has no arithmetic of its own to disagree about.
         *
         * A circle when `rx == ry`. `rotation` is in radians, as everywhere else in the engine
         * (`Shape.Ellipse` says so too). A zero or negative radius, a NaN anywhere, or a box past
         * the cap all come back [EMPTY].
         */
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, rotation: Double): SelectionMask {
            // NEGATED on purpose: `rx <= 0.0` is false for a NaN, so the obvious guard lets a NaN
            // radius through to the polygon, where it happens to die; `!(rx > 0.0)` catches it
            // here, which is what the sentence above this function promises. A negative radius would
            // otherwise not fail at all — `rx * cos(t)` with a negative rx is the same 360-gon
            // turned half a revolution round, a shape with a positive area and nothing to do with
            // the ellipse that was asked for.
            if (!(rx > 0.0) || !(ry > 0.0)) return EMPTY
            val points = ArrayList<Pt>(ELLIPSE_STEPS)
            val cosR = cos(rotation)
            val sinR = sin(rotation)
            for (i in 0 until ELLIPSE_STEPS) {
                val angle = 2.0 * PI * i / ELLIPSE_STEPS
                val ux = rx * cos(angle)
                val uy = ry * sin(angle)
                points.add(Pt(cx + ux * cosR - uy * sinR, cy + ux * sinR + uy * cosR))
            }
            return polygon(points)
        }

        /**
         * A `FloodFill` mask — `w` x `h` bytes, 255 for fill and 0 for not — with its top-left
         * corner at ([originX], [originY]) in document pixels. The origin may be negative.
         *
         * Any byte value is kept, not just 0 and 255, because a caller that has grown a fill
         * (JB-2.06a's `FillOptions.grow`, a feathered boundary) has soft edges to bring with it.
         *
         * @throws IllegalArgumentException if [mask] is shorter than `w * h`, which is a caller bug
         *   rather than a request: unlike a polygon, a byte array has a length to be wrong about.
         *   Or if the placed box runs off the end of Int, since `originX + col` is an Int sum and
         *   would wrap to a negative coordinate and write the bytes into another tile entirely.
         */
        fun fromMask(w: Int, h: Int, mask: ByteArray, originX: Int, originY: Int): SelectionMask {
            if (w <= 0 || h <= 0) return EMPTY
            val n = w.toLong() * h.toLong()
            require(mask.size.toLong() >= n) {
                "fromMask: the mask holds ${mask.size} B but ${w}x$h needs $n B"
            }
            require(originX.toLong() + w <= Int.MAX_VALUE.toLong() &&
                originY.toLong() + h <= Int.MAX_VALUE.toLong()
            ) {
                "fromMask: a ${w}x$h mask at $originX,$originY runs off the end of an Int pixel"
            }
            val out = HashMap<Long, ByteArray>()
            for (row in 0 until h) {
                val y = originY + row
                val ty = tileOfPx(y)
                val rowBase = (y - ty * Tiles.SIZE) * Tiles.SIZE
                for (col in 0 until w) {
                    val v = mask[row * w + col].toInt() and 0xFF
                    if (v == 0) continue
                    val x = originX + col
                    val tx = tileOfPx(x)
                    val arr = writable(out, Tiles.key(tx, ty))
                    arr[rowBase + (x - tx * Tiles.SIZE)] = v.toByte()
                }
            }
            return SelectionMask(finalise(out))
        }

        /**
         * The polygon a circle is made of. 360 points put the flat of each side 0.06 % inside the
         * true curve, which is three orders of magnitude below the sampling error the antialiasing
         * already has, so the point count is not a parameter anybody needs to think about.
         */
        private const val ELLIPSE_STEPS = 360
    }
}

/**
 * The largest bounding box a polygon may have, in either axis. Beyond it [SelectionMask.polygon]
 * returns [SelectionMask.EMPTY] and the caller says "selection too large".
 *
 * 16384 is 64 x 64 tiles, so a legal mask is at most 4 MB of coverage — the same spirit as
 * `render.MAX_REGION_PX` and `fill.MAX_FILL_PX`, and stated here rather than imported so that this
 * file stays arithmetic with no dependency on either of them.
 */
internal const val MAX_SELECT_SPAN = 16384

/** Bytes in one coverage tile: 256 x 256 x 1. */
private const val BYTES_PER_TILE = Tiles.SIZE * Tiles.SIZE

/**
 * True when every pixel of this box is a pixel an `Int` can name — that is, when its far corner
 * `x + w`, `y + h` is still an `Int` rather than wrapped round to the other end of the range.
 *
 * A `RectPx` can hold a box that `Int` coordinates cannot describe: `RectPx(Int.MAX_VALUE - 5, 0,
 * 10, 10)` is a perfectly well formed 10 x 10 box whose right edge is five pixels past the end of
 * `Int`, and `x + w` is not that right edge but a large NEGATIVE number. Nothing built here can
 * answer for such a box — `until` over the wrapped sum is simply empty, which is indistinguishable
 * from a box that selects nothing — so it is checked in Long before anything is added, and the
 * callers refuse rather than return a wrong answer quietly.
 *
 * The lower bound needs no half of the test: `x` and `y` are already Ints, and only a POSITIVE `w`
 * or `h` can walk off the top. `SpriteGridMath` makes the same check on the same argument for the
 * same reason.
 */
private fun RectPx.fitsIntPixels(): Boolean =
    w > 0 && h > 0 &&
        x.toLong() + w <= Int.MAX_VALUE.toLong() &&
        y.toLong() + h <= Int.MAX_VALUE.toLong()

/**
 * The one all-255 tile, shared by every mask that has a completely full one.
 *
 * A 4 MB mask of solid selection is otherwise 4 MB of identical bytes, and a 2048 x 2048 lasso
 * fills a large part of it. It is never written to: [writable] copies it first, which is the same
 * rule a copy-on-write cache uses and costs one array only when something is actually going to
 * change that tile.
 */
internal val FULL_TILE: ByteArray = ByteArray(BYTES_PER_TILE) { 255.toByte() }

/**
 * The array at [key] in [out], ready to be WRITTEN to: a fresh zero tile if the key is absent, and
 * a private copy if it is the shared [FULL_TILE]. An array that is neither is already the caller's,
 * which is how the ops write into their own copies.
 */
internal fun writable(out: HashMap<Long, ByteArray>, key: Long): ByteArray {
    val existing = out[key]
    if (existing === null) {
        val fresh = ByteArray(BYTES_PER_TILE)
        out[key] = fresh
        return fresh
    }
    if (existing === FULL_TILE) {
        val copy = existing.copyOf()
        out[key] = copy
        return copy
    }
    return existing
}

/**
 * The map a finished mask is built from: a tile that ended up all zero is DROPPED (a selection that
 * covers nothing is an empty selection, and a 65536-byte tile of zeros per tile is not free), and a
 * tile that ended up all 255 is the shared [FULL_TILE].
 *
 * Both rules are invisible from outside — `coverage` and `bounds` cannot tell — which is the only
 * property that makes them safe to do.
 */
internal fun finalise(src: Map<Long, ByteArray>): Map<Long, ByteArray> {
    if (src.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Long, ByteArray>(src.size)
    for (entry in src) {
        val tile = entry.value
        if (isAllZero(tile)) continue
        out[entry.key] = if (isAllFull(tile)) FULL_TILE else tile
    }
    return out
}

/** True when the tile holds no coverage at all. Stops at the first byte that does. */
private fun isAllZero(tile: ByteArray): Boolean {
    for (b in tile) if (b.toInt() != 0) return false
    return true
}

/** True when the tile is selected solid, which is what earns it the shared array. */
private fun isAllFull(tile: ByteArray): Boolean {
    if (tile.size != BYTES_PER_TILE) return false
    for (b in tile) if ((b.toInt() and 0xFF) != 255) return false
    return true
}

/**
 * Which tile column or row a document pixel belongs to, FLOORING.
 *
 * `Int` division truncates toward zero, so -1 / 256 is 0 and the strip of pixels from -255 to -1
 * would be filed under tile 0 with negative local indices. `floorDiv` is one comparison on top of
 * it, and it is written out here because "never `/`" is a decision in this spec, not an idiom.
 */
internal fun tileOfPx(px: Int): Int {
    val t = px / Tiles.SIZE
    return if (px % Tiles.SIZE < 0) t - 1 else t
}

/** The offset of pixel (x, y) inside its own tile, row 0 = the tile's top document row. */
internal fun indexInTile(x: Int, y: Int): Int {
    val tx = tileOfPx(x)
    val ty = tileOfPx(y)
    return (y - ty * Tiles.SIZE) * Tiles.SIZE + (x - tx * Tiles.SIZE)
}
