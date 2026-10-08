package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure spatial-metadata planner for dry dabs (UNWIRED: no GL, no wiring, no clipping fix).
 *
 * Scope: given an actual packed [DabBatch] (20-float records read via [DryWindowGeometry.dabDocAabb]),
 * produce disjoint owned write interiors plus per-interior read windows and dab membership.
 * The future Root alone wires GL/metadata and clips each dab to the write interior when rendering;
 * this helper never clips, normalises, rewrites, or reorders dabs.
 *
 * Source contracts (read, not duplicated):
 * - Record layout is the 20-float instanced dab the vertex shader reads
 *   (Stick.kt DryStroke.emit builds `[cx, cy, lx, ly, xMin, xMax, yMax, D, ...]`;
 *   DryWindowGeometry.dabDocAabb reads indices 0..6). This planner validates all 20 floats
 *   finite upfront per dab before geometry use (indices 7..19 would otherwise be unchecked).
 * - Conservative per-dab bounds come from [DryWindowGeometry.dabDocAabb] with the source-approved
 *   2 px raster pad (Stick.kt emit `+ 2`; [RASTER_PAD_DOC_PX]). Aggregate `batch.dirty` is NEVER
 *   trusted (it may be null, stale, or thinned/wet-sized).
 * - `pxPerMm` has shader-uniform semantics: the Double is converted to Float once inside
 *   [DryWindowGeometry.dabDocAabb]; non-finite/non-positive or Float-overflow/underflow-to-zero refuses.
 * - Window constants are the existing ones (`MediaWindowMath.PX = 1024`, `MARGIN = 128`,
 *   `Tiles.SIZE = 256`). Referenced here, never redefined.
 * - Tile feasibility uses [DryWindowGeometry.windowOriginFor] and is re-verified with
 *   [MediaWindowMath.holds].
 *
 * Grid:
 * - `writeTileSpan = 1`: write interior is one 256 px tile `[tx*256, (tx+1)*256)`.
 * - `writeTileSpan = 2`: write interior is one 512 px block of 2x2 tiles,
 *   block `(bx, by)` covering tiles `[bx*2, bx*2+1] x [by*2, by*2+1]`,
 *   i.e. doc rect `[bx*512, bx*512+512)`. Blocks partition the world grid (512-aligned),
 *   so interiors are disjoint with no gaps.
 * - Output order is stable deterministic row-major smallest-first: sorted by `(writeTy, writeTx)`.
 * - For span 2, `writeTx/writeTy` are the top-left TILE coords (always even); `tileSpan = 2`.
 *
 * Intersection semantics (half-open, consistent for negative/exact edges):
 * - Write interior `[wx0, wx1) x [wy0, wy1)` intersects footprint `[fx0, fx1] x [fy0, fy1]`
 *   (footprint from [DryWindowGeometry.dabDocAabb] has `x0 <= x1`) iff
 *   `fx0 < wx1 && fx1 > wx0 && fy0 < wy1 && fy1 > wy0` with non-empty footprint area.
 *   An edge touching exactly (`fx1 == wx0` or `fx0 == wx1`) does NOT count (zero-area contact).
 * - Degenerate footprints (`x1 <= x0` or `y1 <= y0`, never produced by the geometry for real
 *   dabs but guarded): treated as touching nothing, so they contribute no work. A single exact
 *   point (`x0 == x1 && y0 == y1`) therefore touches nothing under half-open rules; real dab
 *   boxes always have positive area (margin + numeric pad + 2 px), so no real deposit is orphaned.
 * - Enumeration uses per-dab `floor`/`ceil` ranges (`tx in [floor(fx0/S), ceil(fx1/S)-1]`),
 *   matching the half-open rule above (same as `Tiles.range` / `MediaWindowMath.keysIn`).
 *
 * Membership:
 * - Each work item lists ALL dab indices whose footprint intersects its write interior,
 *   in ORIGINAL ascending order. A dab spanning N interiors repeats in N items INTENTIONALLY
 *   (future Root renders the original dab clipped to each interior; this helper does no clipping).
 * - Dabs are never clipped, normalised, rewritten, or reordered; `batch.dabs` is never mutated.
 * - All rect arrays in the output are owned copies, never aliases of caller memory
 *   (`batch.dabs`, `batch.dirty`, or input rects).
 *
 * Refusal (whole-plan, before partial publication, no silent cap):
 * - Empty batch (`count == 0` with `dabs.size == 0`) is empty SUCCESS (after scalar preflight,
 *   including `maxWorkItems == 0` zero-capacity).
 * - Any other invalid input refuses the WHOLE plan: bad `pxPerMm`/halo/span/budget, inconsistent
 *   `count`/`dabs.size` (negative count, short array, trailing floats), any per-dab non-finite
 *   in the full 20-float shader record (all fields must be finite; geometry checks indices 0..6
 *   and this planner pre-scans all 20 upfront per dab before geometry use, so NaN/Inf in 7..19
 *   is explicit INVALID_DAB), any per-dab [DryWindowGeometry.dabDocAabb] null (`xMax < xMin`,
 *   `yMax < 0`, overflow), tile coordinates needing indices outside Int range, any read rect
 *   unplaceable via [DryWindowGeometry.windowOriginFor], or distinct-interior count exceeding
 *   `maxWorkItems` (including `maxWorkItems == 0` with any touched interior).
 * - Budget is checked on the DEDUPED union, never on the summed overlap count (overlapping dabs
 *   sharing tiles do not falsely refuse). A single dab whose own footprint needs more interiors
 *   than the budget refuses fast without enumerating. No work items escape on refusal.
 *   `maxWorkItems` is the caller operational cap only; no absolute hard cap or silent truncation
 *   is invented (huge budgets are caller responsibility).
 *
 * Oversized dabs: a single valid dab larger than the 768 px window inner capacity is NOT refused
 * (its whole footprint would never fit `windowOriginFor`); it is spatially split across the
 * interiors it touches. Each interior's READ rect (`write + halo`, at most 512 px for span 1
 * or exactly 768 px for span 2 with halo 128) is what must fit the 1024 window, and it always
 * does for valid halo/span except Int-range overflow. The read halo requires the future Root to
 * render the ORIGINAL dab clipped to the write interior; this helper is metadata only.
 */
object DrySpatialPlan {
    /** Source-approved raster pad (Stick.kt `DryStroke.emit` `+ 2` doc px). */
    const val RASTER_PAD_DOC_PX = 2.0

    /** Max read halo: one `MediaWindowMath.MARGIN` (128 doc px). Referenced, never redefined. */
    const val MAX_READ_HALO_DOC_PX = 128.0

    /** Why the whole plan was refused (no partial outputs escape). */
    enum class RefusalReason {
        INVALID_PX_PER_MM,
        INVALID_HALO,
        INVALID_BUDGET,
        INVALID_SPAN,
        INVALID_BATCH,
        INVALID_DAB,
        UNSUPPORTED_COORDINATES,
        BUDGET_EXCEEDED,
        WINDOW_UNPLACEABLE,
    }

    /** One disjoint owned write interior and its metadata. All arrays are owned copies. */
    class WorkItem(
        /** Top-left TILE x (for span 2 always even: `blockX * 2`). */
        val writeTx: Int,
        /** Top-left TILE y (for span 2 always even: `blockY * 2`). */
        val writeTy: Int,
        /** 1 (256 px) or 2 (512 px). */
        val tileSpan: Int,
        /** Owned `[x0, y0, x1, y1]` doc px write rect (half-open `[x0,x1) x [y0,y1)`). */
        val writeRect: DoubleArray,
        /** Owned `[x0, y0, x1, y1]` doc px read rect = write expanded by halo. */
        val readRect: DoubleArray,
        /** Top-left tile of the chosen 1024 window holding [readRect] (verified by `holds`). */
        val windowTx: Int,
        /** Top-left tile of the chosen 1024 window holding [readRect]. */
        val windowTy: Int,
        /** Owned ascending original dab indices intersecting this interior (may repeat across items). */
        val dabIndices: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WorkItem) return false
            return writeTx == other.writeTx && writeTy == other.writeTy && tileSpan == other.tileSpan &&
                writeRect.contentEquals(other.writeRect) && readRect.contentEquals(other.readRect) &&
                windowTx == other.windowTx && windowTy == other.windowTy &&
                dabIndices.contentEquals(other.dabIndices)
        }

        override fun hashCode(): Int {
            var h = writeTx * 31 + writeTy
            h = h * 31 + tileSpan
            h = h * 31 + writeRect.contentHashCode()
            h = h * 31 + readRect.contentHashCode()
            h = h * 31 + windowTx
            h = h * 31 + windowTy
            h = h * 31 + dabIndices.contentHashCode()
            return h
        }

        override fun toString(): String =
            "WorkItem(write=($writeTx,$writeTy)x$tileSpan, writeRect=${writeRect.toList()}, " +
                "readRect=${readRect.toList()}, window=($windowTx,$windowTy), dabs=${dabIndices.toList()})"
    }

    /** Whole-plan outcome: success with disjoint items, or explicit refusal (nothing published). */
    sealed interface PlanOutcome {
        /** Success; `items` are disjoint write interiors in `(writeTy, writeTx)` order (may be empty). */
        data class Success(val items: List<WorkItem>) : PlanOutcome

        /** Whole-plan refusal; no partial items escape. */
        data class Refused(val reason: RefusalReason, val message: String) : PlanOutcome
    }

    /**
     * Plan disjoint write interiors for [batch].
     *
     * @param batch actual packed dabs (`DabBatch.FLOATS` floats per record, `count` records;
     *   all 20 floats per record must be finite, indices 7..19 included).
     * @param pxPerMm shader uniform doc px per mm (Double converted to Float once by the geometry).
     * @param readHaloDocPx explicit finite halo in `[0, 128]` doc px expanded around each interior.
     * @param maxWorkItems caller operational cap: distinct interiors must be `<= maxWorkItems`
     *   (`>= 0`; `0` is valid zero-capacity: empty (no touched interiors) still succeeds,
     *   any touched interior refuses `BUDGET_EXCEEDED`; negative is `INVALID_BUDGET`).
     * @param writeTileSpan 1 (256 px interiors) or 2 (512 px 2x2-block interiors).
     */
    fun plan(
        batch: DabBatch,
        pxPerMm: Double,
        readHaloDocPx: Double,
        maxWorkItems: Int,
        writeTileSpan: Int = 1,
    ): PlanOutcome {
        if (writeTileSpan != 1 && writeTileSpan != 2) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_SPAN,
                "writeTileSpan $writeTileSpan is not 1 or 2",
            )
        }
        if (!readHaloDocPx.isFinite() || readHaloDocPx < 0.0 || readHaloDocPx > MAX_READ_HALO_DOC_PX) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_HALO,
                "readHaloDocPx $readHaloDocPx is not finite in [0, 128]",
            )
        }
        if (!pxPerMm.isFinite() || pxPerMm <= 0.0) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_PX_PER_MM,
                "pxPerMm $pxPerMm is not finite > 0",
            )
        }
        run {
            val pxF = pxPerMm.toFloat()
            if (!pxF.isFinite() || pxF <= 0f) {
                return PlanOutcome.Refused(
                    RefusalReason.INVALID_PX_PER_MM,
                    "pxPerMm $pxPerMm overflows Float uniform to $pxF",
                )
            }
        }
        if (maxWorkItems < 0) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_BUDGET,
                "maxWorkItems $maxWorkItems is not >= 0",
            )
        }

        val count = batch.count
        val dabs = batch.dabs
        if (count < 0) {
            return PlanOutcome.Refused(RefusalReason.INVALID_BATCH, "negative count $count")
        }
        if (count == 0) {
            if (dabs.size != 0) {
                return PlanOutcome.Refused(
                    RefusalReason.INVALID_BATCH,
                    "count 0 but dabs.size ${dabs.size} (trailing records)",
                )
            }
            return PlanOutcome.Success(emptyList())
        }
        val expectedLong = count.toLong() * DabBatch.FLOATS.toLong()
        if (expectedLong > Int.MAX_VALUE.toLong()) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_BATCH,
                "count $count needs $expectedLong floats (beyond Int array range)",
            )
        }
        if (dabs.size != expectedLong.toInt()) {
            return PlanOutcome.Refused(
                RefusalReason.INVALID_BATCH,
                "dabs.size ${dabs.size} != count $count * ${DabBatch.FLOATS} (short/trailing records)",
            )
        }

        // ---- Per-dab bounded cell enumeration + union (never a huge bounding-rect scan).
        // Streaming: each dab's conservative footprint is validated then immediately unioned;
        // no separate footprint array is retained, so no huge pre-allocation precedes budget checks.
        // ----
        val cellPx = (Tiles.SIZE * writeTileSpan).toDouble()
        val budgetLong = maxWorkItems.toLong()
        // cellKey -> member dab indices (ascending by construction: dabs visited 0..count-1).
        val members = LinkedHashMap<Long, MutableList<Int>>()

        for (dabIndex in 0 until count) {
            val offset = dabIndex * DabBatch.FLOATS
            // Complete packed-record validation: geometry checks indices 0..6, but the shader
            // record is 20 floats; all fields must be finite before geometry is used.
            for (k in 0 until DabBatch.FLOATS) {
                val v = dabs[offset + k]
                if (!v.isFinite()) {
                    return PlanOutcome.Refused(
                        RefusalReason.INVALID_DAB,
                        "dab $dabIndex field $k is non-finite ($v; full 20-float shader record must be finite)",
                    )
                }
            }
            val box = DryWindowGeometry.dabDocAabb(dabs, offset, pxPerMm, RASTER_PAD_DOC_PX)
                ?: return PlanOutcome.Refused(
                    RefusalReason.INVALID_DAB,
                    "dab $dabIndex refused by DryWindowGeometry.dabDocAabb " +
                        "(non-finite stored floats, bad extents xMax<xMin/yMax<0, or overflow)",
                )
            if (box.size < 4 || !box[0].isFinite() || !box[1].isFinite() ||
                !box[2].isFinite() || !box[3].isFinite() || box[0] > box[2] || box[1] > box[3]
            ) {
                return PlanOutcome.Refused(
                    RefusalReason.INVALID_DAB,
                    "dab $dabIndex has non-finite/unordered bounds ${box.toList()}",
                )
            }
            val fx0 = box[0]
            val fy0 = box[1]
            val fx1 = box[2]
            val fy1 = box[3]
            if (fx1 <= fx0 || fy1 <= fy0) continue // degenerate: touches nothing (guarded; real boxes positive).

            val cx0d = floor(fx0 / cellPx)
            val cx1d = ceil(fx1 / cellPx) - 1.0
            val cy0d = floor(fy0 / cellPx)
            val cy1d = ceil(fy1 / cellPx) - 1.0
            if (!cx0d.isFinite() || !cx1d.isFinite() || !cy0d.isFinite() || !cy1d.isFinite()) {
                return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "dab $dabIndex footprint ${box.toList()} has non-finite cell range",
                )
            }
            val cx0 = doubleIntegralToIntOrNull(cx0d)
                ?: return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "dab $dabIndex needs cell x $cx0d outside Int range",
                )
            val cx1 = doubleIntegralToIntOrNull(cx1d)
                ?: return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "dab $dabIndex needs cell x $cx1d outside Int range",
                )
            val cy0 = doubleIntegralToIntOrNull(cy0d)
                ?: return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "dab $dabIndex needs cell y $cy0d outside Int range",
                )
            val cy1 = doubleIntegralToIntOrNull(cy1d)
                ?: return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "dab $dabIndex needs cell y $cy1d outside Int range",
                )
            if (cx0 > cx1 || cy0 > cy1) continue // exact-edge empty (half-open): touches nothing.
            val nx = cx1.toLong() - cx0.toLong() + 1L
            val ny = cy1.toLong() - cy0.toLong() + 1L
            if (nx <= 0L || ny <= 0L) continue
            if (nx > Long.MAX_VALUE / ny) {
                return PlanOutcome.Refused(
                    RefusalReason.BUDGET_EXCEEDED,
                    "dab $dabIndex footprint candidate count overflows Long ($nx x $ny)",
                )
            }
            val perDab = nx * ny
            if (perDab > budgetLong) {
                // Fast refusal: this one dab alone needs more distinct interiors than the budget.
                return PlanOutcome.Refused(
                    RefusalReason.BUDGET_EXCEEDED,
                    "dab $dabIndex alone touches $perDab interiors (budget $maxWorkItems)",
                )
            }
            // Bounded enumeration for THIS dab only (<= budget entries). Union dedups overlaps,
            // so overlapping dabs sharing tiles do not falsely refuse on summed counts.
            var cy = cy0
            while (cy <= cy1) {
                var cx = cx0
                while (cx <= cx1) {
                    val key = Tiles.key(cx, cy)
                    val list = members.getOrPut(key) { ArrayList() }
                    // Avoid duplicate index if a dab's own range somehow repeats a cell (never: ranges
                    // are disjoint per dab, but guard anyway); ascending order holds by visit order.
                    if (list.isEmpty() || list.last() != dabIndex) list.add(dabIndex)
                    if (members.size.toLong() > budgetLong) {
                        return PlanOutcome.Refused(
                            RefusalReason.BUDGET_EXCEEDED,
                            "union of touched interiors exceeds budget $maxWorkItems " +
                                "(at dab $dabIndex cell ($cx,$cy))",
                        )
                    }
                    if (cx == cx1) break
                    cx++
                }
                if (cy == cy1) break
                cy++
            }
        }

        if (members.isEmpty()) return PlanOutcome.Success(emptyList())

        // Stable deterministic order: row-major smallest-first (ty, then tx).
        val sortedKeys = members.keys.sortedWith(compareBy({ Tiles.ty(it) }, { Tiles.tx(it) }))

        // ---- Per-interior read rect + window (no partial outputs: refuse whole plan on any failure). ----
        val items = ArrayList<WorkItem>(sortedKeys.size)
        for (key in sortedKeys) {
            val cx = Tiles.tx(key)
            val cy = Tiles.ty(key)
            val writeRect = writeRectFor(cx, cy, writeTileSpan)
                ?: return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "cell ($cx,$cy) write rect overflows",
                )
            val readRect = doubleArrayOf(
                writeRect[0] - readHaloDocPx,
                writeRect[1] - readHaloDocPx,
                writeRect[2] + readHaloDocPx,
                writeRect[3] + readHaloDocPx,
            )
            if (!readRect[0].isFinite() || !readRect[1].isFinite() ||
                !readRect[2].isFinite() || !readRect[3].isFinite() ||
                readRect[0] > readRect[2] || readRect[1] > readRect[3]
            ) {
                return PlanOutcome.Refused(
                    RefusalReason.UNSUPPORTED_COORDINATES,
                    "cell ($cx,$cy) read rect non-finite/unordered ${readRect.toList()}",
                )
            }
            val origin = DryWindowGeometry.windowOriginFor(readRect)
                ?: return PlanOutcome.Refused(
                    RefusalReason.WINDOW_UNPLACEABLE,
                    "cell ($cx,$cy) read rect ${readRect.toList()} fits no 1024 window",
                )
            if (!MediaWindowMath.holds(origin.first, origin.second, readRect)) {
                return PlanOutcome.Refused(
                    RefusalReason.WINDOW_UNPLACEABLE,
                    "cell ($cx,$cy) window $origin does not hold ${readRect.toList()}",
                )
            }
            val wxL: Long
            val wyL: Long
            if (writeTileSpan == 1) {
                wxL = cx.toLong()
                wyL = cy.toLong()
            } else {
                wxL = cx.toLong() * 2L
                wyL = cy.toLong() * 2L
                if (wxL < Int.MIN_VALUE.toLong() || wxL > Int.MAX_VALUE.toLong() ||
                    wyL < Int.MIN_VALUE.toLong() || wyL > Int.MAX_VALUE.toLong()
                ) {
                    return PlanOutcome.Refused(
                        RefusalReason.UNSUPPORTED_COORDINATES,
                        "cell ($cx,$cy) span-2 tile origin overflows Int",
                    )
                }
            }
            val writeTx = wxL.toInt()
            val writeTy = wyL.toInt()
            val dabList = members[key]!!
            items.add(
                WorkItem(
                    writeTx = writeTx,
                    writeTy = writeTy,
                    tileSpan = writeTileSpan,
                    writeRect = writeRect.copyOf(),
                    readRect = readRect.copyOf(),
                    windowTx = origin.first,
                    windowTy = origin.second,
                    dabIndices = dabList.toIntArray(),
                ),
            )
        }
        return PlanOutcome.Success(items)
    }

    private fun doubleIntegralToIntOrNull(v: Double): Int? {
        if (!v.isFinite()) return null
        if (v < -9.223372036854776e18 || v >= 9.223372036854776e18) return null
        val l = v.toLong()
        if (l < Int.MIN_VALUE.toLong() || l > Int.MAX_VALUE.toLong()) return null
        return l.toInt()
    }

    private fun writeRectFor(cx: Int, cy: Int, span: Int): DoubleArray? {
        val step = (Tiles.SIZE * span).toLong()
        val oxL = cx.toLong() * step
        val oyL = cy.toLong() * step
        // oxL/oyL from Int cell * (256|512) always fit Long; convert to Double exactly (< 2^53).
        val x0 = oxL.toDouble()
        val y0 = oyL.toDouble()
        // Checked Long end before Double conversion (guards theoretical overflow).
        val x1L = oxL + step
        val y1L = oyL + step
        // Long overflow check (practically unreachable for Int cells, but explicit).
        if (x1L < oxL || y1L < oyL) return null
        val x1 = x1L.toDouble()
        val y1 = y1L.toDouble()
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        if (x0 > x1 || y0 > y1) return null
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
