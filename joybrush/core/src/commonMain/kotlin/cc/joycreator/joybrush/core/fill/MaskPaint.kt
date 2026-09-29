package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.SelectionMask

/**
 * What a mask does to the pixels it covers. Three composites, and the third is the reason the
 * fill pen exists (LEAD_RULINGS R21 — the fill pen is a BRUSH, not a tool; this file is its
 * raster maths, and JB-2.06b's tap fill hands the same function a `FloodFill` mask).
 *
 * NOT SERIALISED. Nothing writes these names to a file, so the R3 version-bump rule does not
 * apply to this enum — unlike `BrushInput`, `BoardKind` and `doc.BlendMode`, which are on disk.
 * What IS on disk is the brush's `blend` field (`"normal" | "erase" | "behind"`, JB-1.08a), and
 * that is a String in `brush.json` with its own version rule; the caller maps it onto one of
 * these three.
 */
enum class MaskPaintMode { FILL, ERASE, BEHIND }

/**
 * PAINTING A [SelectionMask] THROUGH A LAYER'S PREMULTIPLIED TILES — the raster half of the fill
 * pen (JB-2.07a), and of the tap fill, which differs only in where the mask came from.
 *
 * ONE FUNCTION, TWO CALLERS, because they are the same operation. A fill pen stroke is
 * [cc.joycreator.joybrush.core.brush.FillPen.outline] rasterised by
 * [SelectionMask.polygon]; a tap fill is a `FloodFill` region wrapped by
 * [SelectionMask.fromMask]. Both arrive here as a mask and leave as tiles. (Decision 4: the lasso
 * itself is `SelectionMask.polygon` — non-zero winding, 4 x 4 antialiased. No gap closing, no
 * tolerance: a fill covers exactly what was drawn round.)
 *
 * ## THE THREE COMPOSITES
 *
 * Per pixel, with `c` the mask coverage 0..255 and `o` the opacity clamped to 0..1 (NaN is 1),
 * the effective source alpha is `a = c / 255 * o` and the premultiplied source is
 * `S = (r*a, g*a, b*a, 255*a)` from the STRAIGHT sRGB [argb] (whose own alpha is ignored — a
 * colour picker hands back a colour, and a colour has no alpha of its own).
 *
 *  - [MaskPaintMode.FILL] — source-over: `D' = S + D * (1 - a)`. The ordinary case, and the one
 *    that reproduces [cc.joycreator.joybrush.core.render.Blend]'s `NORMAL` exactly: premultiplied
 *    source-over is what `ONE, ONE_MINUS_SRC_ALPHA` is.
 *  - [MaskPaintMode.ERASE] — destination-out: `D' = D * (1 - a)` on ALL FOUR channels including
 *    alpha. The alpha rule is the whole reason it is not a blend term: scaling only the colours
 *    and keeping the alpha leaves a half-transparent grey ghost of what was erased, which is
 *    invisible on the canvas and obvious in a PNG. This is the same arithmetic as
 *    `Blend.ERASE_BELOW` and `RefCanvas.endStroke`, and it agrees with `jb_commit.frag`.
 *  - [MaskPaintMode.BEHIND] — destination-over: `D' = D + S * (1 - D.a/255)`. Paint lands only in
 *    the part of the pixel the layer is not already using, so line art already on the layer stays
 *    on top. This is what makes a fill pen useful on the SAME layer as the drawing, rather than
 *    needing a layer below it and an alpha-lock dance.
 *
 * ## WHAT COMES BACK
 *
 * ONLY THE TILES THAT CHANGED, as their new bytes; `null` means "this tile is now empty, delete
 * it" — the shape `GlPaintEngine.replaceTiles` takes, and the reason this function computes a
 * change set rather than a layer. A tile the paint did not alter is ABSENT from the answer, not
 * present-and-equal, so an erase that lands on empty paper and a `BEHIND` under fully opaque paint
 * both record no undo step at all. That is a property of the RETURN rather than a special case:
 * the code writes a candidate tile, compares it with what was there, and drops it when the two are
 * the same bytes. An absent tile compares equal to an all-zero one, which is why Decision 3 ("a
 * new tile is created only for FILL and BEHIND") needs no branch of its own — an ERASE over empty
 * canvas is unchanged, so it is not reported, so no tile is created.
 *
 * ## WHY THE ROUNDING IS DONE THIS WAY
 *
 * `round255` truncates and then compares the REMAINDER against 0.5, rather than computing
 * `floor(v + 0.5)`. The two are not the same function in binary floating point, and the difference
 * is not a rounding-of-rounding subtlety: `0.49999999999999994 + 0.5` evaluates to exactly `1.0`
 * in IEEE 754 double, so `floor(v + 0.5)` returns 1 for a value that must round to 0. The
 * truncating form is exact for every double, because `v - v.toInt()` is exact whenever `v` is in
 * `[0, 2^52)` — the subtraction of two doubles whose exponents are close is exact, so the
 * remainder is the real remainder and the comparison against 0.5 is a comparison of the real
 * number. `MaskPaintTest.theRoundingIsExactAtTheHalfAndNotOneUlpPastIt` pins it, and the golden
 * table is generated from EXACT RATIONAL arithmetic (Fraction, never binary floating point), so
 * every row is a check that the Double pipeline agrees with a model that cannot make this mistake.
 *
 * ## IMMUTABILITY
 *
 * No byte of [layer] or of [mask] is written to, and no array in the answer is shared with either
 * of them — the undo step keeps the old tile as its snapshot, and a result that aliased the layer
 * would make "undo" restore the paint it was supposed to remove. The mask is read through its
 * `internal` tile map for the same reason [cc.joycreator.joybrush.core.select.Resample] is: a
 * public `tile(key)` hands back a 64 KB COPY per tile, which for a 2048-square fill is 64 MB of
 * garbage to look at bytes nobody changes.
 */
object MaskPaint {

    /**
     * [apply] over a whole layer, the door a fill pen, a tap fill or any other mask consumer goes
     * through. [dest] and [out] are 4-element arrays; [out] must not be [dest].
     *
     * The one place the three composites are written down. [apply] calls it for every pixel it
     * touches, so the per-pixel arithmetic and the tile walk cannot drift apart — and the test
     * suite checks that they do not, over every covered pixel of a real lasso.
     *
     * @param dest the destination pixel, PREMULTIPLIED, channels 0..255. A value outside that
     *   range is clamped rather than refused, because this is a door and a door that throws on a
     *   300 is a door somebody works around.
     * @param out receives the premultiplied result, 0..255.
     */
    fun pixel(
        mode: MaskPaintMode,
        argb: Int,
        opacity: Float,
        coverage: Int,
        dest: IntArray,
        out: IntArray,
    ) {
        require(dest.size >= 4 && out.size >= 4) {
            "a pixel needs 4 channels in and 4 out, got ${dest.size}/${out.size}"
        }
        require(out !== dest) { "out must not be dest: this function writes channels in order" }
        val a = (coverage.coerceIn(0, 255) / 255.0) * opacityOf(opacity)
        val dr = dest[0].coerceIn(0, 255)
        val dg = dest[1].coerceIn(0, 255)
        val db = dest[2].coerceIn(0, 255)
        val da = dest[3].coerceIn(0, 255)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF

        // Alpha FIRST, because the colours are clamped to it below. The order is not a style
        // choice: a colour channel computed after the alpha has nowhere to be clamped to.
        when (mode) {
            MaskPaintMode.FILL -> {
                val keep = 1.0 - a
                out[3] = round255(255.0 * a + da * keep)
                out[0] = round255(r * a + dr * keep)
                out[1] = round255(g * a + dg * keep)
                out[2] = round255(b * a + db * keep)
            }

            MaskPaintMode.ERASE -> {
                val keep = 1.0 - a
                out[3] = round255(da * keep)
                out[0] = round255(dr * keep)
                out[1] = round255(dg * keep)
                out[2] = round255(db * keep)
            }

            MaskPaintMode.BEHIND -> {
                // The room left in the destination, in 0..1, as `(255 - da) / 255` and NOT as
                // `1 - da / 255`. They are the same number and they are not the same DOUBLE:
                // `1 - 254.0/255.0` evaluates to 0.0039215686274509665, which is BELOW 1/255
                // (0.0039215686274509803), so 255 * 0.5 * room lands at 0.4999999999999982 and
                // rounds DOWN to 0 — where the exact answer is 0.5, which rounds up to 1. Doing
                // the subtraction on the INTEGERS first and dividing once is exact at both ends
                // (da = 0 gives exactly 1.0, da = 255 gives exactly 0.0) and is the form the
                // golden table pins.
                //
                // The room is NOT `1 - a`: BEHIND is the one mode that reads the DESTINATION's
                // alpha rather than the mask's, and reading `a` here would make a fill pen paint
                // over the line art it exists to sit behind.
                val room = (255.0 - da) / 255.0
                out[3] = round255(da + 255.0 * a * room)
                out[0] = round255(dr + r * a * room)
                out[1] = round255(dg + g * a * room)
                out[2] = round255(db + b * a * room)
            }
        }

        // THE PREMULTIPLIED INVARIANT, colour <= alpha, enforced rather than assumed. For a
        // well-formed destination and a colour in 0..255 it already holds BEFORE rounding
        // (`r*a + D.r*(1-a) <= 255*a + D.a*(1-a)`), and rounding is monotone, so this is a no-op on
        // well-formed input. It is here for the two cases where it is not: a destination tile
        // that is not premultiplied (a corrupt file, a half-written tile), and a rounding tie.
        // The cost is three comparisons; the alternative is a tile the un-premultiply in the
        // exporter then divides by, which is how a fill ends up as a white halo on export.
        if (out[0] > out[3]) out[0] = out[3]
        if (out[1] > out[3]) out[1] = out[3]
        if (out[2] > out[3]) out[2] = out[3]
    }

    /**
     * Paints [argb] (straight sRGB; its alpha is ignored) at [opacity] through [mask] onto a
     * layer's tiles. Returns ONLY the tiles that changed, as their new contents — `null` means
     * "the tile is now empty, delete it" — ready for `GlPaintEngine.replaceTiles`.
     *
     * @param layer the layer's current tiles (premultiplied RGBA8, [Tiles.SIZE]², keyed by
     *   [Tiles.key]). A tile the mask does not reach is not read, not written and not returned.
     * @param mask which pixels, and by how much. [SelectionMask.EMPTY] paints nothing and returns
     *   nothing.
     * @param opacity 0..1, clamped; a NaN opacity is 1, because a NaN that silently returned
     *   nothing would look exactly like a mask that missed.
     * @return the changed tiles, in the mask's own tile order (so a caller logging a change set
     *   gets the same order twice), `null` for a tile that is now empty.
     * @throws IllegalArgumentException if a tile the mask reaches is not [Tiles.SIZE]² x 4 bytes.
     *   That is a caller bug rather than a request — a short tile has no pixel 40,000 to read, and
     *   every index after it would be a different pixel of the same tile.
     */
    fun apply(
        layer: Map<Long, ByteArray>,
        mask: SelectionMask,
        argb: Int,
        opacity: Float,
        mode: MaskPaintMode,
    ): Map<Long, ByteArray?> {
        if (mask.isEmpty) return emptyMap()
        // An opacity of zero is a no-op in ALL THREE modes, and provably so rather than
        // incidentally: FILL gives `0 + D*1`, ERASE gives `D*1`, BEHIND gives `D + 0`, and `D*1.0`
        // is exactly `D` in IEEE 754. So the comparison below would find every candidate tile
        // byte-identical and report nothing — this is the same answer, found without rasterising
        // a 2048-square mask to discover there was nothing to do.
        if (opacityOf(opacity) <= 0.0) return emptyMap()

        for (entry in mask.tiles) {
            val key = entry.key
            val old = layer[key] ?: continue
            require(old.size == TILE_BYTES) {
                "tile ${Tiles.tx(key)},${Tiles.ty(key)} is ${old.size} bytes, not $TILE_BYTES"
            }
        }

        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val out = LinkedHashMap<Long, ByteArray?>()
        val dest = IntArray(4)
        val painted = IntArray(4)
        for (entry in mask.tiles) {
            val key = entry.key
            val coverage = entry.value
            // An absent tile is transparent, and TRANSPARENT_TILE says so once for the whole
            // module rather than branching on `old == null` inside a quarter of a million
            // iterations. It is never written to, exactly as `SelectionMask.FULL_TILE` is not.
            val old = layer[key] ?: TRANSPARENT_TILE
            val tile = old.copyOf()
            for (i in coverage.indices) {
                val c = coverage[i].toInt() and 0xFF
                if (c == 0) continue
                val at = i * 4
                dest[0] = old[at].toInt() and 0xFF
                dest[1] = old[at + 1].toInt() and 0xFF
                dest[2] = old[at + 2].toInt() and 0xFF
                dest[3] = old[at + 3].toInt() and 0xFF
                pixel(mode, argb, opacity, c, dest, painted)
                tile[at] = painted[0].toByte()
                tile[at + 1] = painted[1].toByte()
                tile[at + 2] = painted[2].toByte()
                tile[at + 3] = painted[3].toByte()
            }
            // THE CHANGE TEST, and the reason the function returns a change set rather than a
            // layer. It is a whole-tile byte comparison rather than a flag, because "did any
            // pixel differ" is a question about 262,144 bytes and answering it wrongly in EITHER
            // direction is a bug with a name: a false negative paints a fill that undo then throws
            // away, and a false positive records an undo step for a fill that did nothing.
            if (tile.contentEquals(old)) continue
            out[key] = if (isAllZero(tile)) null else tile
        }
        return out
    }

    /**
     * The opacity of a fill, as the 0..1 factor it multiplies the coverage by.
     *
     * CLAMPED, not refused: this number comes off a slider, and a slider that overshoots by one
     * step must not take a fill down with it — the same rule `fill.FillOptions.tolerance` follows.
     *
     * AND NaN IS 1, WHICH `coerceIn` ALONE WOULD NOT GIVE. `Double.coerceIn` is
     * `if (this < min) min else if (this > max) max else this`, and every comparison with NaN is
     * false, so an unclamped NaN returns NaN. A NaN then propagates into `a`, every comparison
     * downstream is false, and the fill returns an empty change set — a fill that looks exactly
     * like a tap that missed. One `isNaN` is cheaper than that failure mode.
     */
    private fun opacityOf(opacity: Float): Double =
        if (opacity.isNaN()) 1.0 else opacity.toDouble().coerceIn(0.0, 1.0)

    /**
     * A non-negative double as a 0..255 value, ROUNDED HALF UP and clamped.
     *
     * WHY NOT `floor(v + 0.5)`, which is the form every other rounding in this engine uses
     * ([cc.joycreator.joybrush.core.select.Resample.toByte255], [SelectionMask.subtract]): because it is not the rounding it looks
     * like. `0.49999999999999994 + 0.5` is exactly `1.0` in IEEE 754, so `floor` returns 1 for a
     * value one ULP below the halfway point that must round DOWN. The truncating form cannot make
     * that mistake for any double: `v - v.toInt()` is exact on `[0, 2^52)` (subtracting two nearby
     * doubles is exact), so the remainder compared against 0.5 is the true remainder.
     *
     * This is the one place in the file where the choice was made from a measured disagreement
     * rather than from theory: a sweep of eight million channel values found no case reachable from
     * these formulas, and a sweep of every double within four ULPs of each `k + 0.5` found exactly
     * one — the 0.49999999999999994 above. Unreachable today and one refactor away from reachable,
     * which is the whole argument for the three extra operations.
     *
     * `internal` rather than private so the test can name that case directly instead of hunting
     * for mask inputs that would reach it.
     */
    internal fun round255(v: Double): Int {
        val whole = v.toInt()
        val rounded = if (v - whole >= 0.5) whole + 1 else whole
        return if (rounded < 0) 0 else if (rounded > 255) 255 else rounded
    }

    /** True when the tile holds nothing. Stops at the first byte that does. */
    private fun isAllZero(tile: ByteArray): Boolean {
        for (x in tile) if (x.toInt() != 0) return false
        return true
    }

    // ── constants ──────────────────────────────────────────────────────────────────

    /** Bytes in one RGBA8 tile: [Tiles.SIZE] x [Tiles.SIZE] x 4. The layout `replaceTiles` speaks. */
    private const val TILE_BYTES = Tiles.SIZE * Tiles.SIZE * 4

    /**
     * What a pixel reads as when its layer has no tile: transparent, all four channels 0.
     *
     * A single shared constant rather than a `null` check in the pixel loop, and never written to —
     * `old.copyOf()` is what the loop mutates, so a write here would corrupt every subsequent read
     * of "empty canvas" in the program at once. Same rule, same reason, as `SelectionMask.FULL_TILE`.
     */
    private val TRANSPARENT_TILE = ByteArray(TILE_BYTES)
}
