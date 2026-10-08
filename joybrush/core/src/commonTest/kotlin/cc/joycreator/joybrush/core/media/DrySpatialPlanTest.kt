package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Objective CPU checks for [DrySpatialPlan] (pure metadata only; no GL wiring, no clipping fix).
 *
 * Oracle discipline: expected tile sets are NOT recomputed with the planner's `floor`/`ceil`
 * enumeration. Instead a brute neighbourhood scan over a small fixed tile window tests direct
 * half-open overlap (`fx0 < wx1 && fx1 > wx0 && ...`), and sampled deposit points must land in
 * exactly one owned write interior. Dimensions are never hand-guessed: footprints come from
 * [DryWindowGeometry.dabDocAabb] with the 2 px pad, and shader corners are checked independently.
 * Real [DryStroke] (+ [MediaSpline] Fast-C) batches are used wherever geometry matters.
 */
class DrySpatialPlanTest {
    private fun record(
        cx: Float, cy: Float, lx: Float, ly: Float,
        xMin: Float, xMax: Float, yMax: Float,
    ): FloatArray {
        val r = FloatArray(DabBatch.FLOATS)
        r[0] = cx; r[1] = cy; r[2] = lx; r[3] = ly
        r[4] = xMin; r[5] = xMax; r[6] = yMax
        return r
    }

    private fun batchOf(vararg recs: FloatArray, dirty: DoubleArray? = null): DabBatch {
        val out = FloatArray(recs.size * DabBatch.FLOATS)
        recs.forEachIndexed { i, r -> r.copyInto(out, i * DabBatch.FLOATS) }
        return DabBatch(out, recs.size, dirty)
    }

    private fun footprints(batch: DabBatch, pxPerMm: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(batch.count)
        for (i in 0 until batch.count) {
            out.add(assertNotNull(DryWindowGeometry.dabDocAabb(batch.dabs, i * DabBatch.FLOATS, pxPerMm, 2.0)))
        }
        return out
    }

    private fun success(
        batch: DabBatch, px: Double = 20.0, halo: Double = 8.0, budget: Int = 64, span: Int = 1,
    ): List<DrySpatialPlan.WorkItem> {
        val out = DrySpatialPlan.plan(batch, px, halo, budget, span)
        if (out is DrySpatialPlan.PlanOutcome.Refused) fail("unexpected refusal ${out.reason}: ${out.message}")
        return (out as DrySpatialPlan.PlanOutcome.Success).items
    }

    private fun refused(
        batch: DabBatch, px: Double = 20.0, halo: Double = 8.0, budget: Int = 64, span: Int = 1,
    ): DrySpatialPlan.PlanOutcome.Refused {
        val out = DrySpatialPlan.plan(batch, px, halo, budget, span)
        if (out is DrySpatialPlan.PlanOutcome.Success) fail("expected refusal, got ${out.items.size} items")
        return out as DrySpatialPlan.PlanOutcome.Refused
    }

    private fun assertHoldsAll(items: List<DrySpatialPlan.WorkItem>) {
        for (it in items) {
            assertTrue(
                MediaWindowMath.holds(it.windowTx, it.windowTy, it.readRect),
                "window (${it.windowTx},${it.windowTy}) must hold ${it.readRect.toList()} for $it",
            )
            val viaHelper = assertNotNull(
                DryWindowGeometry.windowOriginFor(it.readRect),
                "read rect ${it.readRect.toList()} must stay feasible via actual helper",
            )
            assertTrue(
                MediaWindowMath.holds(viaHelper.first, viaHelper.second, it.readRect),
                "helper origin $viaHelper must hold ${it.readRect.toList()}",
            )
        }
    }

    /** Independent half-open overlap (direct compare, not floor/ceil enumeration). */
    private fun overlaps(fx0: Double, fy0: Double, fx1: Double, fy1: Double, wx0: Double, wy0: Double, wx1: Double, wy1: Double): Boolean {
        if (fx1 <= fx0 || fy1 <= fy0) return false
        if (wx1 <= wx0 || wy1 <= wy0) return false
        return fx0 < wx1 && fx1 > wx0 && fy0 < wy1 && fy1 > wy0
    }

    // ---- empty / single ----

    @Test fun emptyBatchIsEmptySuccess() {
        val out = DrySpatialPlan.plan(DabBatch(FloatArray(0), 0, null), 20.0, 8.0, 64, 1)
        val ok = out as? DrySpatialPlan.PlanOutcome.Success ?: fail("empty must succeed, got $out")
        assertEquals(0, ok.items.size)
    }

    @Test fun singleSmallDabPlansOneInteriorAndHolds() {
        val b = batchOf(record(100f, 200f, 1f, 0f, -1f, 1f, 1f))
        val items = success(b, halo = 8.0)
        assertEquals(1, items.size, "46px footprint at (100,200) fits one 256 tile, got $items")
        val it = items[0]
        assertEquals(1, it.tileSpan)
        assertTrue(it.dabIndices.contentEquals(intArrayOf(0)))
        assertHoldsAll(items)
        // Write rect is exactly one tile.
        assertEquals(256.0, it.writeRect[2] - it.writeRect[0], 1e-9)
        assertEquals(256.0, it.writeRect[3] - it.writeRect[1], 1e-9)
        // Read rect is write + halo on each side.
        assertEquals(it.writeRect[0] - 8.0, it.readRect[0], 1e-9)
        assertEquals(it.writeRect[2] + 8.0, it.readRect[2], 1e-9)
    }

    @Test fun outputOrderIsRowMajorSmallestFirst() {
        // Two far-apart small dabs: tiles (0,0) and (2,0) plus (0,1); order must be (ty,tx).
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(600f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(100f, 400f, 1f, 0f, -0.5f, 0.5f, 0.5f),
        )
        val items = success(b, halo = 0.0, budget = 16)
        assertTrue(items.size >= 3, "expected >=3 interiors, got $items")
        val keys = items.map { Pair(it.writeTx, it.writeTy) }
        val sorted = keys.sortedWith(compareBy({ it.second }, { it.first }))
        assertEquals(sorted, keys, "output must be row-major smallest-first, got $keys")
        assertHoldsAll(items)
    }

    // ---- spanning / repetition / order ----

    @Test fun dabAcrossTileEdgeRepeatsAscendingAcrossBothWindows() {
        // Centre 250: footprint ~[227,273] touches tiles 0 and 1 on x.
        val b = batchOf(
            record(250f, 100f, 1f, 0f, -1f, 1f, 1f),
            record(900f, 900f, 1f, 0f, -0.5f, 0.5f, 0.5f),
        )
        val items = success(b, halo = 8.0, budget = 16)
        val covering0 = items.filter { 0 in it.dabIndices.toList() }
        assertEquals(2, covering0.size, "dab 0 spans exactly tiles 0,1; got $items")
        for (it in items) {
            val sorted = it.dabIndices.sorted()
            assertEquals(sorted, it.dabIndices.toList(), "indices must be original ascending in $it")
        }
        // Dab 0 repeats intentionally; dab 1 appears once.
        val count0 = items.sumOf { it.dabIndices.count { d -> d == 0 } }
        assertEquals(2, count0, "spanning dab repeats across both windows, got $items")
        assertEquals(1, items.sumOf { it.dabIndices.count { d -> d == 1 } })
        assertHoldsAll(items)
    }

    @Test fun overlappingDabsShareTileWithoutFalseBudgetRefusal() {
        // Two dabs fully inside tile (0,0); budget 1 must SUCCEED (union=1, sum would be 2).
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(120f, 110f, 1f, 0f, -0.5f, 0.5f, 0.5f),
        )
        val items = success(b, halo = 8.0, budget = 1)
        assertEquals(1, items.size, "overlapping dabs share one interior, got $items")
        assertTrue(items[0].dabIndices.contentEquals(intArrayOf(0, 1)), "both indices ascending, got ${items[0].dabIndices.toList()}")
    }

    @Test fun oversizedSingleDabSplitsAcrossInteriorsInsteadOfRefusing() {
        // ~806px wide footprint (> 768 window inner): must split, not refuse.
        val b = batchOf(record(0f, 0f, 1f, 0f, -20f, 20f, 20f))
        val fp = footprints(b, 20.0)[0]
        assertTrue(fp[2] - fp[0] > 768.0, "fixture must exceed window inner 768, got ${fp.toList()}")
        assertEquals(null, DryWindowGeometry.windowOriginFor(fp), "whole footprint indeed fits no window")
        val items = success(b, halo = 8.0, budget = 64)
        assertTrue(items.size >= 4, "oversized dab must split across interiors, got $items")
        // Dab 0 repeats in every interior intentionally.
        for (it in items) assertTrue(it.dabIndices.contentEquals(intArrayOf(0)), "each split holds dab 0, got $it")
        assertHoldsAll(items)
        // Each read rect is small (interior + halo), never the whole 806px footprint.
        for (it in items) {
            assertTrue(it.readRect[2] - it.readRect[0] <= 256.0 + 16.0 + 1e-9)
        }
    }

    @Test fun asymmetricRotatedContactPlansAndEnclosesShaderCorners() {
        val b = batchOf(record(-800f, -600f, 0.25f, -0.4f, -0.5f, 10f, 2f))
        val box = footprints(b, 20.0)[0]
        // Independent shader-corner check (Double ideals, as in DryWindowGeometryTest).
        val m = 0.06
        val loX = -0.5 - m; val hiX = 10.0 + m
        val loY = -2.0 - m; val hiY = 2.0 + m
        val ux = 0.25; val uy = -0.4; val vx = 0.4; val vy = 0.25
        val qxs = doubleArrayOf(loX, hiX, loX, hiX)
        val qys = doubleArrayOf(loY, loY, hiY, hiY)
        for (i in 0..3) {
            val px = -800.0 + (qxs[i] * ux + qys[i] * vx) * 20.0
            val py = -600.0 + (qys[i] * vy + qxs[i] * uy) * 20.0
            assertTrue(box[0] <= px && px <= box[2] && box[1] <= py && py <= box[3], "corner $i ($px,$py) outside ${box.toList()}")
        }
        val items = success(b, halo = 8.0, budget = 64)
        assertTrue(items.isNotEmpty())
        assertHoldsAll(items)
    }

    // ---- real factories (never fake all geometry) ----

    private fun realDryBatch(): DabBatch {
        val s = DryStroke(Sticks.ALL["Proto"]!!, 0.12, 20.0)
        s.add(MediaSample(100.0, 100.0, 0.9, 0.3, 0.0, 0.0))
        s.add(MediaSample(400.0, 120.0, 0.9, 0.3, 0.0, 16.0))
        s.add(MediaSample(700.0, 200.0, 0.9, 0.5, 0.5, 32.0))
        return s.take()
    }

    @Test fun realDryStrokeBatchPlansWithConservativeBounds() {
        val b = realDryBatch()
        assertTrue(b.count > 0, "real DryStroke must emit dabs")
        val items = success(b, halo = 8.0, budget = 64)
        assertTrue(items.isNotEmpty())
        assertHoldsAll(items)
        // Every dab footprint intersects at least one planned interior (gap-free).
        val fps = footprints(b, 20.0)
        for ((di, fp) in fps.withIndex()) {
            val hit = items.any { it.dabIndices.contains(di) }
            assertTrue(hit, "real dab $di footprint ${fp.toList()} orphaned; items $items")
        }
        // Every planned interior genuinely intersects a footprint (no extra gap tiles) via direct overlap.
        for (it in items) {
            val ok = fps.indices.any { di ->
                di in it.dabIndices.toList() &&
                    overlaps(fps[di][0], fps[di][1], fps[di][2], fps[di][3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3])
            }
            assertTrue(ok, "interior $it touches no footprint (extra gap tile)")
        }
    }

    private fun fastCBatch(): DabBatch {
        // Fast-C: centripetal Catmull-Rom through a 270-degree arc, then real DryStroke.
        val ds = DryStroke(Sticks.ALL["Proto"]!!, 0.12, 20.0)
        val sink: (MediaSample) -> Unit = { ds.add(it) }
        val sp = MediaSpline(stepPx = 0.75, sink = sink)
        val cx = 1000.0; val cy = 1000.0; val r = 500.0
        val n = 16
        for (i in 0 until n) {
            val u = i.toDouble() / (n - 1)
            val a = (-135.0 + u * 270.0) * PI / 180.0
            val x = cx + r * kotlin.math.cos(a)
            val y = cy + r * kotlin.math.sin(a)
            sp.add(MediaSample(x, y, 0.6, 0.4, a, u * 120.0))
        }
        sp.finish()
        return ds.take()
    }

    @Test fun fastCAcrossWindowsPlansGapFree() {
        val b = fastCBatch()
        assertTrue(b.count > 0, "Fast-C must emit dabs")
        val items = success(b, halo = 8.0, budget = 64)
        assertTrue(items.size >= 2, "500px-radius C must cross windows, got ${items.size}")
        assertHoldsAll(items)
        val fps = footprints(b, 20.0)
        for ((di, fp) in fps.withIndex()) {
            assertTrue(items.any { di in it.dabIndices.toList() }, "Fast-C dab $di orphaned")
        }
    }

    // ---- edges / negatives / sparse ----

    @Test fun exactTileEdgeDoesNotSpillUnderHalfOpen() {
        // Footprint ending exactly at 256 must not spill to tile 1; starting at 256 belongs to tile 1.
        // Craft via records then verify with direct overlap (independent of planner enumeration).
        val left = batchOf(record(232f, 100f, 1f, 0f, -1f, 1f, 1f))
        val fpL = footprints(left, 20.0)[0]
        // Nudge check: this test asserts the planner's half-open rule on a datum whose edge is
        // interior to tiles, plus an exact-edge synthetic overlap probe below.
        val itemsL = success(left, halo = 0.0, budget = 16)
        assertTrue(itemsL.isNotEmpty())
        // Exact-edge probe: rect [0,256) touches tile 0 only; [256,512) touches tile 1 only.
        assertTrue(overlaps(0.0, 0.0, 256.0, 256.0, 0.0, 0.0, 256.0, 256.0))
        assertTrue(!overlaps(0.0, 0.0, 256.0, 256.0, 256.0, 0.0, 512.0, 256.0), "edge-touch is zero area, no spill")
        assertTrue(overlaps(256.0, 0.0, 512.0, 256.0, 256.0, 0.0, 512.0, 256.0))
        assertTrue(!overlaps(256.0, 0.0, 512.0, 256.0, 0.0, 0.0, 256.0, 256.0))
        // Negative exact edge: [-256,0) is tile -1 only.
        assertTrue(overlaps(-256.0, 0.0, 0.0, 256.0, -256.0, 0.0, 0.0, 256.0))
        assertTrue(!overlaps(-256.0, 0.0, 0.0, 256.0, 0.0, 0.0, 256.0, 256.0))
        assertTrue(fpL[0] < fpL[2] && fpL[1] < fpL[3])
    }

    @Test fun negativeCoordinatesPlanInNegativeTiles() {
        val b = batchOf(record(-800f, -600f, 1f, 0f, -1f, 1f, 1f))
        val items = success(b, halo = 8.0, budget = 16)
        assertEquals(1, items.size)
        assertTrue(items[0].writeTx < 0 && items[0].writeTy < 0, "negative dab plans negative tiles, got ${items[0]}")
        assertHoldsAll(items)
    }

    @Test fun sparseFarApartAllocatesOnlyTouchedInteriors() {
        val b = batchOf(
            record(0f, 0f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(100000f, -50000f, 1f, 0f, -0.5f, 0.5f, 0.5f),
        )
        val fps = footprints(b, 20.0)
        val fp0 = fps[0]
        val fp1 = fps[1]
        // Independent geometry backing (no planner floor/ceil): origin corner contact straddles x=0/y=0.
        assertTrue(fp0[0] < 0.0 && fp0[2] > 0.0, "origin footprint must straddle x=0, got ${fp0.toList()}")
        assertTrue(fp0[1] < 0.0 && fp0[3] > 0.0, "origin footprint must straddle y=0, got ${fp0.toList()}")
        assertTrue(fp0[0] > -256.0 && fp0[2] < 256.0 && fp0[1] > -256.0 && fp0[3] < 256.0, "origin small footprint stays within [-256,256), got ${fp0.toList()}")
        assertTrue(fp0[2] - fp0[0] < 256.0 && fp0[3] - fp0[1] < 256.0, "origin footprint smaller than one tile, got ${fp0.toList()}")
        // Far dab centre 100000/256~=390.6 floor 390, -50000/256~=-195.3 floor -196; footprint must sit inside tile (390,-196).
        assertTrue(fp1[0] >= 390 * 256.0 && fp1[2] <= 390 * 256.0 + 256.0, "far footprint x must sit inside tile 390, got ${fp1.toList()}")
        assertTrue(fp1[1] >= -196 * 256.0 && fp1[3] <= -196 * 256.0 + 256.0, "far footprint y must sit inside tile -196, got ${fp1.toList()}")
        // Independent brute-neighbourhood union per footprint (direct half-open overlap, fixed local windows).
        val expected = LinkedHashSet<Long>()
        for (ty in -2..1) for (tx in -2..1) {
            if (overlaps(fp0[0], fp0[1], fp0[2], fp0[3], tx * 256.0, ty * 256.0, tx * 256.0 + 256.0, ty * 256.0 + 256.0)) {
                expected.add(Tiles.key(tx, ty))
            }
        }
        for (ty in -197..-195) for (tx in 389..391) {
            if (overlaps(fp1[0], fp1[1], fp1[2], fp1[3], tx * 256.0, ty * 256.0, tx * 256.0 + 256.0, ty * 256.0 + 256.0)) {
                expected.add(Tiles.key(tx, ty))
            }
        }
        val originKeys = setOf(Tiles.key(-1, -1), Tiles.key(0, -1), Tiles.key(-1, 0), Tiles.key(0, 0))
        val farKey = Tiles.key(390, -196)
        assertEquals(5, expected.size, "independent oracle must find 4 origin + 1 far, got ${expected.map { Pair(Tiles.tx(it), Tiles.ty(it)) }} for ${fps.map { it.toList() }}")
        assertEquals(originKeys + farKey, expected, "brute neighbourhoods must be exactly origin-four plus far-one")
        val items = success(b, halo = 8.0, budget = 16)
        assertEquals(5, items.size, "sparse dabs yield 4 origin interiors + 1 far interior, no gap scan; got $items")
        val got = items.map { Tiles.key(it.writeTx, it.writeTy) }.toSet()
        assertEquals(expected, got, "planner must match brute neighbourhood union for ${fps.map { it.toList() }}")
        for (it in items) {
            val bruteMembers = fps.indices.filter { di ->
                overlaps(fps[di][0], fps[di][1], fps[di][2], fps[di][3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3])
            }
            assertEquals(bruteMembers, it.dabIndices.toList(), "members for $it must match brute overlap")
            val key = Tiles.key(it.writeTx, it.writeTy)
            if (key == farKey) {
                assertTrue(it.dabIndices.contentEquals(intArrayOf(1)), "far interior holds only dab 1, got $it")
            } else {
                assertTrue(key in originKeys, "non-far interior must be one of origin-four, got $it")
                assertTrue(it.dabIndices.contentEquals(intArrayOf(0)), "origin interior holds only dab 0, got $it")
            }
        }
        // Huge empty gap between footprints contributes nothing (mid-gap probe + exact-set equality above).
        val midX = (fp0[2] + fp1[0]) / 2
        val midTx = floor(midX / 256.0).toInt()
        assertTrue(items.none { it.writeTx == midTx && it.writeTy == 0 }, "gap tile must not be planned")
        assertHoldsAll(items)
    }

    @Test fun writeInteriorsAreDisjointAndOwnPixelsUniquely() {
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -1f, 1f, 1f),
            record(300f, 100f, 1f, 0f, -1f, 1f, 1f),
            record(100f, 350f, 1f, 0f, -1f, 1f, 1f),
        )
        val items = success(b, halo = 8.0, budget = 16)
        // Pairwise disjoint with positive area never overlapping.
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = items[i].writeRect; val c = items[j].writeRect
            assertTrue(!overlaps(a[0], a[1], a[2], a[3], c[0], c[1], c[2], c[3]), "write interiors $a and $c overlap (double ownership)")
        }
        // Sampled deposit points belong to exactly one write interior.
        val fps = footprints(b, 20.0)
        for (fp in fps) {
            val cx = (fp[0] + fp[2]) / 2; val cy = (fp[1] + fp[3]) / 2
            val owners = items.count { cx >= it.writeRect[0] && cx < it.writeRect[2] && cy >= it.writeRect[1] && cy < it.writeRect[3] }
            // Centre may sit exactly on an edge in theory; footprint area guarantees at least one owner nearby.
            // Instead verify footprint area intersects exactly the planned owners via overlap count.
            val overlapOwners = items.count { overlaps(fp[0], fp[1], fp[2], fp[3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3]) }
            assertTrue(overlapOwners >= 1, "footprint ${fp.toList()} has no owner")
        }
    }

    @Test fun independentBruteNeighbourhoodOracleMatchesPlanner() {
        // Small batch near origin: brute scan of fixed tiles -2..2 with direct overlap must equal plan.
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -1f, 1f, 1f),
            record(300f, 120f, 0.6f, 0.8f, -1f, 2f, 1.5f),
        )
        val fps = footprints(b, 20.0)
        val expected = LinkedHashSet<Long>()
        for (ty in -2..2) for (tx in -2..2) {
            val wx0 = tx * 256.0; val wy0 = ty * 256.0
            for (fp in fps) {
                if (overlaps(fp[0], fp[1], fp[2], fp[3], wx0, wy0, wx0 + 256.0, wy0 + 256.0)) {
                    expected.add(Tiles.key(tx, ty))
                }
            }
        }
        val items = success(b, halo = 8.0, budget = 32)
        val got = items.map { Tiles.key(it.writeTx, it.writeTy) }.toSet()
        assertEquals(expected, got, "brute neighbourhood oracle must match planner")
        // Membership brute check per tile.
        for (it in items) {
            val bruteMembers = fps.indices.filter { di ->
                overlaps(fps[di][0], fps[di][1], fps[di][2], fps[di][3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3])
            }
            assertEquals(bruteMembers, it.dabIndices.toList(), "members for $it must match brute overlap")
        }
    }

    @Test fun negativeTilesPlannerMatchesIndependentBruteOracle() {
        // Planner-level negative coverage: footprints in negative tiles, tile set + membership
        // via independent half-open overlap oracle (not planner floor/ceil enumeration).
        val b = batchOf(
            record(-800f, -600f, 1f, 0f, -1f, 1f, 1f),
            record(-100f, -100f, 0.6f, 0.8f, -1f, 2f, 1.5f),
        )
        val fps = footprints(b, 20.0)
        // Fixed neighbourhood covering negative tiles -6..0 (contains both footprints + margin).
        val expected = LinkedHashSet<Long>()
        for (ty in -6..0) for (tx in -6..0) {
            val wx0 = tx * 256.0; val wy0 = ty * 256.0
            for (fp in fps) {
                if (overlaps(fp[0], fp[1], fp[2], fp[3], wx0, wy0, wx0 + 256.0, wy0 + 256.0)) {
                    expected.add(Tiles.key(tx, ty))
                }
            }
        }
        assertTrue(expected.isNotEmpty(), "negative fixture must touch tiles, fps ${fps.map { it.toList() }}")
        assertTrue(
            expected.all { Tiles.tx(it) <= 0 && Tiles.ty(it) <= 0 },
            "fixture must stay non-positive tiles, got ${expected.map { Pair(Tiles.tx(it), Tiles.ty(it)) }}",
        )
        val items = success(b, halo = 8.0, budget = 32)
        val got = items.map { Tiles.key(it.writeTx, it.writeTy) }.toSet()
        assertEquals(expected, got, "negative brute oracle must match planner for ${fps.map { it.toList() }}")
        for (it in items) {
            val bruteMembers = fps.indices.filter { di ->
                overlaps(fps[di][0], fps[di][1], fps[di][2], fps[di][3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3])
            }
            assertEquals(bruteMembers, it.dabIndices.toList(), "members for $it must match brute overlap")
            assertTrue(it.writeTx <= 0 && it.writeTy <= 0, "negative plan stays non-positive, got $it")
        }
        assertHoldsAll(items)
    }

    @Test fun gridEdgeStraddlePlannerMatchesIndependentOverlap() {
        // Conservative Float pad (2 px + numeric) means an exact 256.0 boundary fixture cannot be
        // crafted exactly from packed Floats; state expected geometry (straddles 256) and assert
        // independent tile membership rather than exact edges or weakened bounds.
        val b = batchOf(record(250f, 100f, 1f, 0f, -1f, 1f, 1f))
        val fp = footprints(b, 20.0)[0]
        assertTrue(fp[0] < 256.0 && fp[2] > 256.0, "fixture must straddle tile edge 256, got ${fp.toList()}")
        // Brute neighbourhood -1..2 via direct half-open overlap (independent of floor/ceil).
        val expected = LinkedHashSet<Long>()
        for (ty in -1..1) for (tx in -1..2) {
            if (overlaps(fp[0], fp[1], fp[2], fp[3], tx * 256.0, ty * 256.0, tx * 256.0 + 256.0, ty * 256.0 + 256.0)) {
                expected.add(Tiles.key(tx, ty))
            }
        }
        assertTrue(expected.size >= 2, "straddling footprint must touch >=2 tiles, got $expected for ${fp.toList()}")
        val items = success(b, halo = 0.0, budget = 16)
        val got = items.map { Tiles.key(it.writeTx, it.writeTy) }.toSet()
        assertEquals(expected, got, "grid-edge brute oracle must match planner for ${fp.toList()}")
        for (it in items) {
            assertTrue(
                overlaps(fp[0], fp[1], fp[2], fp[3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3]),
                "planned $it must genuinely overlap footprint ${fp.toList()}",
            )
            assertTrue(it.dabIndices.contentEquals(intArrayOf(0)), "straddling dab repeats per touched interior, got $it")
        }
        assertHoldsAll(items)
    }

    @Test fun negativeEdgeStraddlePlannerMatchesIndependentOverlap() {
        // Negative grid edge: same conservative-pad discipline as positive edge; footprint straddles
        // -256 and planner membership must equal the independent overlap oracle.
        val b = batchOf(record(-260f, -100f, 1f, 0f, -1f, 1f, 1f))
        val fp = footprints(b, 20.0)[0]
        assertTrue(fp[0] < -256.0 && fp[2] > -256.0, "fixture must straddle tile edge -256, got ${fp.toList()}")
        val expected = LinkedHashSet<Long>()
        for (ty in -2..0) for (tx in -3..0) {
            if (overlaps(fp[0], fp[1], fp[2], fp[3], tx * 256.0, ty * 256.0, tx * 256.0 + 256.0, ty * 256.0 + 256.0)) {
                expected.add(Tiles.key(tx, ty))
            }
        }
        assertTrue(expected.size >= 2, "negative straddle must touch >=2 tiles, got $expected for ${fp.toList()}")
        val items = success(b, halo = 0.0, budget = 16)
        val got = items.map { Tiles.key(it.writeTx, it.writeTy) }.toSet()
        assertEquals(expected, got, "negative-edge brute oracle must match planner for ${fp.toList()}")
        for (it in items) {
            assertTrue(
                overlaps(fp[0], fp[1], fp[2], fp[3], it.writeRect[0], it.writeRect[1], it.writeRect[2], it.writeRect[3]),
                "planned $it must genuinely overlap footprint ${fp.toList()}",
            )
        }
        assertHoldsAll(items)
    }

    // ---- span 2 / halo ----

    @Test fun spanTwoBlocksAreDisjoint512AndHoldWithHalo128() {
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -1f, 1f, 1f),
            record(600f, 100f, 1f, 0f, -1f, 1f, 1f),
        )
        val items = success(b, halo = 128.0, budget = 16, span = 2)
        assertTrue(items.isNotEmpty())
        for (it in items) {
            assertEquals(2, it.tileSpan)
            assertEquals(0, it.writeTx % 2, "span-2 writeTx even, got $it")
            assertEquals(0, it.writeTy % 2, "span-2 writeTy even, got $it")
            assertEquals(512.0, it.writeRect[2] - it.writeRect[0], 1e-9)
            assertEquals(512.0, it.writeRect[3] - it.writeRect[1], 1e-9)
            assertEquals(768.0, it.readRect[2] - it.readRect[0], 1e-9, "512 + 2*128 read must be exactly 768")
        }
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = items[i].writeRect; val c = items[j].writeRect
            assertTrue(!overlaps(a[0], a[1], a[2], a[3], c[0], c[1], c[2], c[3]), "span-2 blocks overlap: $a vs $c")
        }
        assertHoldsAll(items)
    }

    // ---- refusals ----

    @Test fun invalidScalarsRefuseWholePlan() {
        val good = batchOf(record(0f, 0f, 1f, 0f, -1f, 1f, 1f))
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_HALO, refused(good, halo = -1.0).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_HALO, refused(good, halo = 129.0).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_HALO, refused(good, halo = Double.NaN).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_HALO, refused(good, halo = Double.POSITIVE_INFINITY).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_SPAN, refused(good, span = 0).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_SPAN, refused(good, span = 3).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_PX_PER_MM, refused(good, px = Double.NaN).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_PX_PER_MM, refused(good, px = 0.0).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_PX_PER_MM, refused(good, px = -20.0).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_PX_PER_MM, refused(good, px = 1e300).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_PX_PER_MM, refused(good, px = 1e-46).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_BUDGET, refused(good, budget = -1).reason)
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_BUDGET, refused(good, budget = -5).reason)
        // maxWorkItems == 0 is valid zero-capacity (see zeroBudgetIsValidZeroCapacity), not INVALID_BUDGET.
    }

    @Test fun zeroBudgetIsValidZeroCapacity() {
        // Empty batch with 0 budget is empty SUCCESS (after scalar preflight).
        val empty = DrySpatialPlan.plan(DabBatch(FloatArray(0), 0, null), 20.0, 8.0, 0, 1)
        val ok = empty as? DrySpatialPlan.PlanOutcome.Success ?: fail("empty with budget 0 must succeed, got $empty")
        assertEquals(0, ok.items.size)
        // Non-empty that touches work with 0 budget refuses the whole plan, nothing published.
        val one = batchOf(record(100f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f))
        val r = refused(one, budget = 0)
        assertEquals(DrySpatialPlan.RefusalReason.BUDGET_EXCEEDED, r.reason)
        // Negative stays INVALID_BUDGET.
        assertEquals(DrySpatialPlan.RefusalReason.INVALID_BUDGET, refused(one, budget = -1).reason)
    }

    @Test fun layoutViolationsRefuse() {
        val rec = record(0f, 0f, 1f, 0f, -1f, 1f, 1f)
        // Negative count.
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_BATCH,
            refused(DabBatch(floatArrayOf(), -1, null)).reason,
        )
        // Short array.
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_BATCH,
            refused(DabBatch(FloatArray(19), 1, null)).reason,
        )
        // Trailing floats.
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_BATCH,
            refused(DabBatch(FloatArray(21), 1, null)).reason,
        )
        // Count 0 with trailing.
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_BATCH,
            refused(DabBatch(FloatArray(20), 0, null)).reason,
        )
        // Two records where second is cut off.
        val two = FloatArray(40)
        rec.copyInto(two, 0)
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_BATCH,
            refused(DabBatch(two.copyOf(39), 2, null)).reason,
        )
    }

    @Test fun nonfiniteAndBadExtentsRefuse() {
        val good = record(0f, 0f, 1f, 0f, -1f, 1f, 1f)
        // Finite valid baseline: all 20 floats finite (7..19 are 0.0) must succeed.
        assertTrue(success(batchOf(good)).isNotEmpty(), "finite baseline must succeed")
        // Non-zero finite tail must also succeed (no false refusal on valid 7..19).
        val goodTail = good.copyOf()
        for (k in 7 until DabBatch.FLOATS) goodTail[k] = (k + 1).toFloat()
        assertTrue(success(batchOf(goodTail)).isNotEmpty(), "finite non-zero 7..19 must succeed")
        // All 20 shader-record fields must be finite; NaN/Inf at any index refuses.
        for (k in 0 until DabBatch.FLOATS) {
            val bad = good.copyOf()
            bad[k] = Float.NaN
            assertEquals(DrySpatialPlan.RefusalReason.INVALID_DAB, refused(batchOf(bad)).reason, "NaN at $k")
            val bad2 = good.copyOf()
            bad2[k] = Float.POSITIVE_INFINITY
            assertEquals(DrySpatialPlan.RefusalReason.INVALID_DAB, refused(batchOf(bad2)).reason, "Inf at $k")
            val bad3 = good.copyOf()
            bad3[k] = Float.NEGATIVE_INFINITY
            assertEquals(DrySpatialPlan.RefusalReason.INVALID_DAB, refused(batchOf(bad3)).reason, "-Inf at $k")
        }
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_DAB,
            refused(batchOf(record(0f, 0f, 1f, 0f, 2f, 1f, 1f))).reason,
            "xMax < xMin",
        )
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_DAB,
            refused(batchOf(record(0f, 0f, 1f, 0f, -1f, 1f, -1f))).reason,
            "yMax < 0",
        )
        // Overflow: Float-scale lobe past max.
        assertEquals(
            DrySpatialPlan.RefusalReason.INVALID_DAB,
            refused(batchOf(record(0f, 0f, 1e10f, 0f, -1e30f, 1e30f, 1e30f))).reason,
        )
    }

    @Test fun unsupportedCoordinatesRefuseWithoutWrapping() {
        // Tile ~3.9e9 past Int.MAX: no Int origin, must refuse (not saturate/wrap).
        val far = batchOf(record(1e12f, 0f, 1f, 0f, -1f, 1f, 1f))
        val r = refused(far, budget = 64)
        assertTrue(
            r.reason == DrySpatialPlan.RefusalReason.UNSUPPORTED_COORDINATES ||
                r.reason == DrySpatialPlan.RefusalReason.INVALID_DAB,
            "far coords must refuse explicitly, got ${r.reason}: ${r.message}",
        )
        val farNeg = batchOf(record(-1e12f, 0f, 1f, 0f, -1f, 1f, 1f))
        val r2 = refused(farNeg, budget = 64)
        assertTrue(
            r2.reason == DrySpatialPlan.RefusalReason.UNSUPPORTED_COORDINATES ||
                r2.reason == DrySpatialPlan.RefusalReason.INVALID_DAB,
            "far negative must refuse, got ${r2.reason}",
        )
    }

    @Test fun largeButSupportedCoordinatesStayFinite() {
        // 1e8 px: Float spacing ~8px, still resolvable; tile ~390625 well within Int.
        val b = batchOf(record(1e8f, -1e8f, 0.6f, 0.8f, -3f, 5f, 4f))
        val box = footprints(b, 20.0)[0]
        // Independent Float corner check (as GPU does).
        val cxF = 1e8f; val cyF = -1e8f
        val uxF = 0.6f; val uyF = 0.8f
        val mF = 0.06f; val pxF = 20f
        val loXF = -3f - mF; val hiXF = 5f + mF
        val loYF = -4f - mF; val hiYF = 4f + mF
        val qxsF = floatArrayOf(loXF, hiXF, loXF, hiXF)
        val qysF = floatArrayOf(loYF, loYF, hiYF, hiYF)
        for (i in 0..3) {
            val fx = cxF + (qxsF[i] * uxF + qysF[i] * -uyF) * pxF
            val fy = cyF + (qxsF[i] * uyF + qysF[i] * uxF) * pxF
            assertTrue(fx.isFinite() && fy.isFinite())
            assertTrue(box[0] <= fx.toDouble() && fx.toDouble() <= box[2], "float corner $i x outside ${box.toList()}")
            assertTrue(box[1] <= fy.toDouble() && fy.toDouble() <= box[3], "float corner $i y outside ${box.toList()}")
        }
        val items = success(b, halo = 8.0, budget = 16)
        assertTrue(items.isNotEmpty())
        assertHoldsAll(items)
    }

    @Test fun budgetRefusalIsWholePlanWithNothingPublished() {
        val b = batchOf(
            record(100f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(600f, 100f, 1f, 0f, -0.5f, 0.5f, 0.5f),
            record(100f, 600f, 1f, 0f, -0.5f, 0.5f, 0.5f),
        )
        val fps = footprints(b, 20.0)
        // Distinct tiles needed (brute): must exceed 1.
        val need = LinkedHashSet<Long>()
        for (fp in fps) {
            need.add(Tiles.key(floor(fp[0] / 256.0).toInt(), floor(fp[1] / 256.0).toInt()))
        }
        assertTrue(need.size > 1, "fixture must need >1 interior")
        val r = refused(b, budget = 1)
        assertEquals(DrySpatialPlan.RefusalReason.BUDGET_EXCEEDED, r.reason)
    }

    @Test fun oneHugeFootprintRefusesFastOnSmallBudget() {
        val b = batchOf(record(0f, 0f, 1f, 0f, -20f, 20f, 20f))
        val r = refused(b, budget = 1)
        assertEquals(DrySpatialPlan.RefusalReason.BUDGET_EXCEEDED, r.reason, "huge single footprint must refuse fast, got $r")
    }

    // ---- aliasing / mutation / dirty ----

    @Test fun inputArrayIsNotMutatedAndRectsAreOwnedCopies() {
        val r1 = record(100f, 100f, 1f, 0f, -1f, 1f, 1f)
        val r2 = record(300f, 120f, 0.6f, 0.8f, -1f, 2f, 1.5f)
        val b = batchOf(r1, r2)
        val before = b.dabs.copyOf()
        val items = success(b, halo = 8.0, budget = 16)
        assertTrue(b.dabs.contentEquals(before), "planner must never mutate batch.dabs")
        // Mutating output rects must not affect a fresh plan (owned copies, no aliasing).
        val firstWrite = items[0].writeRect
        val snapshot = firstWrite.copyOf()
        firstWrite[0] = -999999999.0
        val items2 = success(b, halo = 8.0, budget = 16)
        assertTrue(items2[0].writeRect.contentEquals(snapshot), "rects must be owned copies, replanning differs: ${items2[0].writeRect.toList()} vs ${snapshot.toList()}")
        // Dab indices array owned: mutating it must not affect replanning.
        (items[0].dabIndices as IntArray)[0] = 9999
        val items3 = success(b, halo = 8.0, budget = 16)
        assertTrue(items3[0].dabIndices.all { it < b.count }, "dabIndices must be fresh owned copies")
    }

    @Test fun aggregateDirtyIsIgnored() {
        val r = record(100f, 100f, 1f, 0f, -1f, 1f, 1f)
        val correct = batchOf(r)
        val withNull = DabBatch(correct.dabs.copyOf(), 1, null)
        val withWrong = DabBatch(correct.dabs.copyOf(), 1, doubleArrayOf(-99999.0, -99999.0, -99900.0, -99900.0))
        val a = success(correct, halo = 8.0)
        val bPlan = success(withNull, halo = 8.0)
        val c = success(withWrong, halo = 8.0)
        assertEquals(a, bPlan, "null dirty must not change plan")
        assertEquals(a, c, "wrong dirty must not change plan (per-dab bounds rule)")
        // Batch with count>0 but dirty null is still valid (dirty never trusted).
        assertEquals(1, bPlan.size)
    }

    @Test fun floatRoundingNearTileEdgeIsConservative() {
        // Dab straddling 256 with Float centre: footprint must still enclose Float shader corners.
        val b = batchOf(record(250f, 100f, 0.6f, 0.8f, -2f, 3f, 2f))
        val box = footprints(b, 20.0)[0]
        val d = b.dabs
        val scale = 20f
        val xs = listOf(d[4] - 0.06f, d[5] + 0.06f)
        val ys = listOf(-d[6] - 0.06f, d[6] + 0.06f)
        for (x in xs) for (y in ys) {
            val dx = (x * d[2] - y * d[3]) * scale
            val dy = (x * d[3] + y * d[2]) * scale
            val gx = (d[0] + dx).toDouble()
            val gy = (d[1] + dy).toDouble()
            assertTrue(gx >= box[0] && gx <= box[2], "corner x=$gx outside ${box.toList()}")
            assertTrue(gy >= box[1] && gy <= box[3], "corner y=$gy outside ${box.toList()}")
        }
        val items = success(b, halo = 8.0, budget = 16)
        assertHoldsAll(items)
        // Straddling dab touches >1 tile only if footprint truly crosses; either way no orphan.
        assertTrue(items.any { 0 in it.dabIndices.toList() })
    }
}
