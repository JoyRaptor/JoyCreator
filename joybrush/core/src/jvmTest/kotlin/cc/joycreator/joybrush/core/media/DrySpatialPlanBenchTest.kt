package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JB-NOW-08 — actual spatial-planner workload benchmark (sole owned file; no production edits).
 *
 * SCOPE (this file only):
 * - Calls the REAL [DrySpatialPlan.plan] public API (DrySpatialPlan.kt:168); never transcribed or stubbed.
 * - Generates deterministic valid 20-float [DabBatch] records using the actual record layout
 *   (MediaMath.kt:43-45 `FLOATS = 20`; Stick.kt:207-213 emit order
 *   `[cx, cy, lx, ly, xMin, xMax, yMax, D, ...]`; DryWindowGeometry reads indices 0..6 and the
 *   planner pre-scans all 20 for finiteness). All 20 floats per record are finite; `xMax >= xMin`,
 *   `yMax >= 0` (else the planner refuses with INVALID_DAB by contract).
 * - Bounded scenarios only: 64 / 512 / 4096 dabs; dense overlapping footprints versus a sparse long
 *   path; translated negative coordinates (-8000, -6000 doc px); one oversized contact
 *   (xMin=-20, xMax=20, yMax=20 mm, same magnitudes as the existing oversized correctness test).
 *   Both supported interior spans (1 = 256 px, 2 = 512 px blocks) with halo 0 and 128 doc px
 *   (`DrySpatialPlan.MAX_READ_HALO_DOC_PX`). Realistic `pxPerMm = DOC_PX_PER_MM` (MediaMath.kt:16).
 *   Explicit bounded work-item cap [MAX_WORK_ITEMS]; largest batch is 4096 dabs = 81920 floats
 *   (~320 KiB); no massive allocations. `batch.dirty` is null (the planner never trusts it).
 * - Empty input and a guaranteed low-budget refusal are separate fixtures OUTSIDE the timed
 *   workloads (their own @Tests below).
 *
 * TIMING (desktop metadata planning only):
 * - JVM wall-clock via System.nanoTime around [DrySpatialPlan.plan] only (input is built once
 *   outside the timed region). At least [WARMUP] warmups (discarded) then [REPEATS] measured runs;
 *   reported median and max in milliseconds. No GL, phone, frame-latency or speedup claim; no timing
 *   pass thresholds asserted (flaky by design).
 * - A result checksum derived from the actual plan output is asserted stable across every measured
 *   run and printed, defeating unused-result benchmarking.
 *
 * STRUCTURE (per measured scenario, assertion failures fail the test):
 * - Byte-for-byte input immutability (snapshot before vs after).
 * - Deterministic output against a second actual call (items equality + checksum equality).
 * - Original ascending dab indices per item, in range, strictly ascending.
 * - Owned-interior uniqueness: distinct Tiles keys, pairwise half-open disjoint write rects,
 *   row-major order, write size = span * Tiles.SIZE, read = write +/- halo, verified by the actual
 *   [MediaWindowMath.holds] helper.
 */
class DrySpatialPlanBenchTest {

    private companion object {
        /** Explicit bounded caller cap for every measured success workload (union of touched interiors). */
        const val MAX_WORK_ITEMS = 8192

        /** Warmups discarded before measuring (spec minimum 5). */
        const val WARMUP = 5

        /** Measured repetitions kept (spec minimum 10). */
        const val REPEATS = 10

        /** Realistic shader-uniform doc px per mm (MediaMath.kt:16), used for every scenario. */
        const val PX_PER_MM = DOC_PX_PER_MM

        /** Negative translation vector applied to the NEG scenarios (doc px). */
        const val NEG_DX = -8000.0
        const val NEG_DY = -6000.0

        /** Sparse long-path x step (doc px): 4096 dabs span ~163840 px, ~640 span-1 tiles in x. */
        const val SPARSE_STEP_X = 40.0f
    }

    private enum class Kind { DENSE, SPARSE, OVERSIZED }

    private data class Scenario(
        val id: String,
        val count: Int,
        val kind: Kind,
        val span: Int,
        val halo: Double,
        val baseX: Float,
        val baseY: Float,
        val expectNegativeTile: Boolean,
    )

    private fun scenarios(): List<Scenario> {
        val halo0 = 0.0
        val halo128 = DrySpatialPlan.MAX_READ_HALO_DOC_PX // 128.0, referenced not redefined
        return listOf(
            Scenario("dense-0064-span1-halo000-origin", 64, Kind.DENSE, 1, halo0, 0f, 0f, false),
            Scenario("dense-0064-span2-halo128-origin", 64, Kind.DENSE, 2, halo128, 0f, 0f, false),
            Scenario("sparse-0512-span1-halo000-origin", 512, Kind.SPARSE, 1, halo0, 0f, 0f, false),
            Scenario("sparse-0512-span2-halo128-origin", 512, Kind.SPARSE, 2, halo128, 0f, 0f, false),
            Scenario("dense-0512-neg-span1-halo000-neg8k", 512, Kind.DENSE, 1, halo0, NEG_DX.toFloat(), NEG_DY.toFloat(), true),
            Scenario("dense-0512-neg-span2-halo128-neg8k", 512, Kind.DENSE, 2, halo128, NEG_DX.toFloat(), NEG_DY.toFloat(), true),
            Scenario("dense-4096-span1-halo000-origin", 4096, Kind.DENSE, 1, halo0, 0f, 0f, false),
            Scenario("dense-4096-span2-halo128-origin", 4096, Kind.DENSE, 2, halo128, 0f, 0f, false),
            Scenario("sparse-4096-neg-span1-halo000-neg8k", 4096, Kind.SPARSE, 1, halo0, NEG_DX.toFloat(), NEG_DY.toFloat(), true),
            Scenario("sparse-4096-neg-span2-halo128-neg8k", 4096, Kind.SPARSE, 2, halo128, NEG_DX.toFloat(), NEG_DY.toFloat(), true),
            Scenario("oversized-0064-span1-halo000-origin", 64, Kind.OVERSIZED, 1, halo0, 0f, 0f, false),
            Scenario("oversized-0064-span2-halo128-origin", 64, Kind.OVERSIZED, 2, halo128, 0f, 0f, false),
        )
    }

    /** One valid 20-float record in the actual emit layout; indices 7..19 are finite plausibles. */
    private fun newRecord(
        cx: Float, cy: Float, leanAngleRad: Double,
        xMin: Float, xMax: Float, yMax: Float, pressure: Float,
    ): FloatArray {
        val r = FloatArray(DabBatch.FLOATS)
        r[0] = cx
        r[1] = cy
        r[2] = cos(leanAngleRad).toFloat()
        r[3] = sin(leanAngleRad).toFloat()
        r[4] = xMin
        r[5] = xMax
        r[6] = yMax
        r[7] = 0.1f
        r[8] = 0.5f
        r[9] = 0.6f
        r[10] = 0.7f
        r[11] = 0.5f
        r[12] = 1.0f
        r[13] = 0.0f
        r[14] = 0.0f
        r[15] = 0.01f
        r[16] = 1.0f
        r[17] = 0.0f
        r[18] = 0.0f
        r[19] = pressure
        return r
    }

    private fun denseBatch(count: Int, baseX: Float, baseY: Float): DabBatch {
        val out = FloatArray(count * DabBatch.FLOATS)
        for (i in 0 until count) {
            // Deterministic overlapping footprints inside a ~192 px box (footprint ~46 px at
            // pxPerMm 20, so heavy intentional overlap; union stays a few tiles).
            val cx = baseX + ((i * 53) % 192).toFloat()
            val cy = baseY + ((i * 91) % 192).toFloat()
            val p = 0.5f + ((i % 10) * 0.01f)
            newRecord(cx, cy, i * 0.7, -1f, 1f, 1f, p).copyInto(out, i * DabBatch.FLOATS)
        }
        return DabBatch(out, count, null)
    }

    private fun sparseBatch(count: Int, baseX: Float, baseY: Float): DabBatch {
        val out = FloatArray(count * DabBatch.FLOATS)
        for (i in 0 until count) {
            // Long path along x (40 px steps); y is a bounded deterministic oscillation
            // (~127 px range, so the union stays a narrow band, never a huge bounding-rect scan).
            val cx = baseX + i * SPARSE_STEP_X
            val cy = baseY + ((i * 7) % 128).toFloat() - 64f
            val p = 0.5f + ((i % 10) * 0.01f)
            newRecord(cx, cy, i * 0.35, -1f, 1f, 1f, p).copyInto(out, i * DabBatch.FLOATS)
        }
        return DabBatch(out, count, null)
    }

    private fun oversizedBatch(total: Int, baseX: Float, baseY: Float): DabBatch {
        require(total >= 1) { "oversized batch needs at least the oversized contact" }
        val out = FloatArray(total * DabBatch.FLOATS)
        // Dab 0 is the single oversized contact (~800 px wide at 20 px/mm, splits across
        // interiors instead of refusing, per planner contract); the rest are small dense dabs.
        newRecord(baseX, baseY, 0.0, -20f, 20f, 20f, 0.9f).copyInto(out, 0)
        for (i in 1 until total) {
            val cx = baseX + ((i * 53) % 192).toFloat()
            val cy = baseY + ((i * 91) % 192).toFloat()
            newRecord(cx, cy, i * 0.7, -1f, 1f, 1f, 0.6f).copyInto(out, i * DabBatch.FLOATS)
        }
        return DabBatch(out, total, null)
    }

    private fun buildBatch(s: Scenario): DabBatch = when (s.kind) {
        Kind.DENSE -> denseBatch(s.count, s.baseX, s.baseY)
        Kind.SPARSE -> sparseBatch(s.count, s.baseX, s.baseY)
        Kind.OVERSIZED -> oversizedBatch(s.count, s.baseX, s.baseY)
    }

    /** Output-derived checksum over the whole plan (defeats unused-result benchmarking). */
    private fun checksumOf(items: List<DrySpatialPlan.WorkItem>): Long {
        var h = items.size.toLong() * 0x9E3779B9L
        for (it in items) {
            h = h * 31L + it.writeTx
            h = h * 31L + it.writeTy
            h = h * 31L + it.tileSpan
            for (v in it.writeRect) h = h * 31L + v.toBits()
            for (v in it.readRect) h = h * 31L + v.toBits()
            h = h * 31L + it.windowTx
            h = h * 31L + it.windowTy
            for (d in it.dabIndices) h = h * 31L + d
        }
        return h
    }

    private fun medianOf(s: DoubleArray): Double {
        require(s.isNotEmpty()) { "no samples" }
        val t = s.sorted()
        val n = t.size
        return if (n % 2 == 1) t[n / 2] else (t[n / 2 - 1] + t[n / 2]) / 2.0
    }

    /** Independent half-open overlap (direct compare, not the planner's floor/ceil enumeration). */
    private fun overlaps(
        fx0: Double, fy0: Double, fx1: Double, fy1: Double,
        wx0: Double, wy0: Double, wx1: Double, wy1: Double,
    ): Boolean {
        if (fx1 <= fx0 || fy1 <= fy0) return false
        if (wx1 <= wx0 || wy1 <= wy0) return false
        return fx0 < wx1 && fx1 > wx0 && fy0 < wy1 && fy1 > wy0
    }

    private fun verifySuccessStructure(
        scenarioId: String,
        items: List<DrySpatialPlan.WorkItem>,
        batchCount: Int,
        span: Int,
        halo: Double,
        expectNegativeTile: Boolean,
    ) {
        val writeSide = Tiles.SIZE * span.toDouble() // 256.0 or 512.0, referenced not redefined
        for (it in items) {
            assertEquals(span, it.tileSpan, "$scenarioId: tileSpan for $it")
            for (d in it.dabIndices) {
                assertTrue(d in 0 until batchCount, "$scenarioId: dab index $d out of range in $it")
            }
            for (k in 1 until it.dabIndices.size) {
                assertTrue(
                    it.dabIndices[k - 1] < it.dabIndices[k],
                    "$scenarioId: dab indices must be original ascending in $it",
                )
            }
            assertEquals(writeSide, it.writeRect[2] - it.writeRect[0], 1e-9, "$scenarioId: write width for $it")
            assertEquals(writeSide, it.writeRect[3] - it.writeRect[1], 1e-9, "$scenarioId: write height for $it")
            assertEquals(it.writeRect[0] - halo, it.readRect[0], 1e-9, "$scenarioId: read x0 for $it")
            assertEquals(it.writeRect[1] - halo, it.readRect[1], 1e-9, "$scenarioId: read y0 for $it")
            assertEquals(it.writeRect[2] + halo, it.readRect[2], 1e-9, "$scenarioId: read x1 for $it")
            assertEquals(it.writeRect[3] + halo, it.readRect[3], 1e-9, "$scenarioId: read y1 for $it")
            assertTrue(
                MediaWindowMath.holds(it.windowTx, it.windowTy, it.readRect),
                "$scenarioId: window (${it.windowTx},${it.windowTy}) must hold ${it.readRect.toList()} for $it",
            )
            assertTrue(
                DryWindowGeometry.windowOriginFor(it.readRect) != null,
                "$scenarioId: read rect ${it.readRect.toList()} must stay feasible via actual helper",
            )
        }
        // Owned interiors are unique and pairwise disjoint (no double ownership).
        val keys = items.map { Tiles.key(it.writeTx, it.writeTy) }
        assertEquals(keys.size, keys.toSet().size, "$scenarioId: owned interiors must be unique, got $items")
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = items[i].writeRect
            val c = items[j].writeRect
            assertTrue(
                !overlaps(a[0], a[1], a[2], a[3], c[0], c[1], c[2], c[3]),
                "$scenarioId: write interiors $a and $c overlap (double ownership)",
            )
        }
        // Stable deterministic row-major smallest-first order.
        val order = items.map { Pair(it.writeTy, it.writeTx) }
        assertEquals(
            order.sortedWith(compareBy({ it.first }, { it.second })),
            order,
            "$scenarioId: output must be row-major smallest-first",
        )
        if (expectNegativeTile) {
            assertTrue(
                items.any { it.writeTx < 0 || it.writeTy < 0 },
                "$scenarioId: translated-negative batch must touch a negative tile, got $items",
            )
        }
    }

    @Test fun spatialPlanWorkloadBenchEmitsStableTable() {
        // Sanity-pin the actual producer/API contracts this bench relies on.
        assertEquals(20, DabBatch.FLOATS, "bench assumes the actual 20-float dab record")
        assertEquals(256, Tiles.SIZE, "bench assumes 256 px tiles")
        assertEquals(20.0, PX_PER_MM, 1e-12, "bench uses the realistic DOC_PX_PER_MM")
        assertEquals(128.0, DrySpatialPlan.MAX_READ_HALO_DOC_PX, 1e-12, "halo128 references the actual max halo")

        val specs = scenarios()
        // Contract coverage: every dab count, both patterns, negative translation, oversized,
        // both spans, both halos.
        assertEquals(12, specs.size, "fixed scenario count")
        assertTrue(specs.any { it.count == 64 }, "64-dab scenarios present")
        assertTrue(specs.any { it.count == 512 }, "512-dab scenarios present")
        assertTrue(specs.any { it.count == 4096 }, "4096-dab scenarios present")
        assertTrue(specs.any { it.kind == Kind.DENSE }, "dense overlapping present")
        assertTrue(specs.any { it.kind == Kind.SPARSE }, "sparse long path present")
        assertTrue(specs.any { it.kind == Kind.OVERSIZED }, "oversized contact present")
        assertTrue(specs.any { it.expectNegativeTile }, "negative-translated scenarios present")
        assertTrue(specs.any { it.span == 1 } && specs.any { it.span == 2 }, "both spans present")
        assertTrue(specs.any { it.halo == 0.0 } && specs.any { it.halo == 128.0 }, "halo 0 and 128 present")

        println("DrySpatialPlan workload bench: desktop JVM metadata planning ONLY (no GL, phone, frame latency, or speedup claim).")
        println("units: dabs=records; halo=doc px; cap=maxWorkItems (distinct interiors); timings=ms JVM wall-clock planning via System.nanoTime (input built once outside timing).")
        println("limits: pxPerMm=$PX_PER_MM doc px per mm; cap=$MAX_WORK_ITEMS; warmup=$WARMUP discarded then $REPEATS measured (median+max); largest batch 4096 dabs=81920 floats; no timing thresholds asserted.")

        var printed = 0
        for (s in specs) {
            val batch = buildBatch(s)
            assertEquals(s.count, batch.count, "${s.id}: actual dab count")
            assertEquals(s.count * DabBatch.FLOATS, batch.dabs.size, "${s.id}: packed size")
            val before = batch.dabs.copyOf()

            val first = DrySpatialPlan.plan(batch, PX_PER_MM, s.halo, MAX_WORK_ITEMS, s.span)
            if (first is DrySpatialPlan.PlanOutcome.Refused) {
                fail("${s.id}: measured success workload refused ${first.reason}: ${first.message}")
            }
            val firstItems = (first as DrySpatialPlan.PlanOutcome.Success).items
            verifySuccessStructure(s.id, firstItems, s.count, s.span, s.halo, s.expectNegativeTile)
            val firstChecksum = checksumOf(firstItems)

            // Deterministic output against a second actual call.
            val second = DrySpatialPlan.plan(batch, PX_PER_MM, s.halo, MAX_WORK_ITEMS, s.span)
            if (second is DrySpatialPlan.PlanOutcome.Refused) {
                fail("${s.id}: second call refused ${second.reason}: ${second.message}")
            }
            val secondItems = (second as DrySpatialPlan.PlanOutcome.Success).items
            assertEquals(firstItems, secondItems, "${s.id}: deterministic output against second actual call")
            assertEquals(firstChecksum, checksumOf(secondItems), "${s.id}: checksum stable across calls")

            // Byte-for-byte input immutability across actual planning calls.
            assertTrue(batch.dabs.contentEquals(before), "${s.id}: planner must never mutate batch.dabs")

            // Warmup (discarded), then measured wall-clock runs; every run's output checksum must
            // match, and the kept checksum defeats unused-result benchmarking.
            repeat(WARMUP) { DrySpatialPlan.plan(batch, PX_PER_MM, s.halo, MAX_WORK_ITEMS, s.span) }
            val samples = DoubleArray(REPEATS)
            var keptChecksum = 0L
            for (r in 0 until REPEATS) {
                val t0 = System.nanoTime()
                val out = DrySpatialPlan.plan(batch, PX_PER_MM, s.halo, MAX_WORK_ITEMS, s.span)
                val t1 = System.nanoTime()
                samples[r] = (t1 - t0).toDouble() / 1_000_000.0
                if (out is DrySpatialPlan.PlanOutcome.Refused) {
                    fail("${s.id}: measured run $r refused ${out.reason}: ${out.message}")
                }
                val runItems = (out as DrySpatialPlan.PlanOutcome.Success).items
                val runChecksum = checksumOf(runItems)
                assertEquals(firstChecksum, runChecksum, "${s.id}: run $r checksum must match first plan")
                keptChecksum = keptChecksum xor runChecksum
                assertTrue(batch.dabs.contentEquals(before), "${s.id}: run $r mutated input")
            }
            assertTrue(samples.all { it.isFinite() }, "${s.id}: all timing samples finite")

            val workItems = firstItems.size
            val totalMemberships = firstItems.sumOf { it.dabIndices.size } // intentional duplicates
            val maxPerItem = firstItems.maxOfOrNull { it.dabIndices.size } ?: 0
            val medianMs = medianOf(samples)
            val maxMs = samples.max()
            println(
                "bench ${s.id} dabs=${s.count} span=${s.span} haloDocPx=${s.halo} " +
                    "pxPerMm=$PX_PER_MM cap=$MAX_WORK_ITEMS result=SUCCESS " +
                    "workItems=$workItems totalMemberships=$totalMemberships maxPerItem=$maxPerItem " +
                    "checksum=$keptChecksum medianMs=%.4f maxMs=%.4f".format(medianMs, maxMs),
            )
            printed++
        }
        assertEquals(specs.size, printed, "emitted table must contain every scenario")
    }

    @Test fun emptyInputIsEmptySuccessOutsideMeasuredWorkloads() {
        val out = DrySpatialPlan.plan(DabBatch(FloatArray(0), 0, null), PX_PER_MM, 0.0, MAX_WORK_ITEMS, 1)
        val ok = out as? DrySpatialPlan.PlanOutcome.Success ?: fail("empty batch must succeed, got $out")
        assertEquals(0, ok.items.size, "empty input yields zero work items")
        println("fixture empty dabs=0 span=1 haloDocPx=0.0 pxPerMm=$PX_PER_MM cap=$MAX_WORK_ITEMS result=SUCCESS workItems=0 (NOT_TIMED)")
    }

    @Test fun lowBudgetRefusalPublishesNothing() {
        // Distinct fixture: three far-apart small dabs need >1 interior; budget 1 must refuse the
        // whole plan. Refused carries no items, so no partial result can escape (by type).
        val recs = arrayOf(
            newRecord(100f, 100f, 0.0, -0.5f, 0.5f, 0.5f, 0.6f),
            newRecord(600f, 100f, 0.0, -0.5f, 0.5f, 0.5f, 0.6f),
            newRecord(100f, 600f, 0.0, -0.5f, 0.5f, 0.5f, 0.6f),
        )
        val packed = FloatArray(recs.size * DabBatch.FLOATS)
        recs.forEachIndexed { i, r -> r.copyInto(packed, i * DabBatch.FLOATS) }
        val batch = DabBatch(packed, recs.size, null)
        val out = DrySpatialPlan.plan(batch, PX_PER_MM, 0.0, 1, 1)
        val refused = out as? DrySpatialPlan.PlanOutcome.Refused
            ?: fail("low-budget fixture must refuse, got $out")
        assertEquals(
            DrySpatialPlan.RefusalReason.BUDGET_EXCEEDED,
            refused.reason,
            "low-budget refusal reason, got ${refused.reason}: ${refused.message}",
        )
        // Deterministic refusal on a second actual call.
        val again = DrySpatialPlan.plan(batch, PX_PER_MM, 0.0, 1, 1)
        val refusedAgain = again as? DrySpatialPlan.PlanOutcome.Refused
            ?: fail("second low-budget call must refuse, got $again")
        assertEquals(refused.reason, refusedAgain.reason, "refusal reason deterministic")
        println("fixture low-budget dabs=3 span=1 haloDocPx=0.0 pxPerMm=$PX_PER_MM cap=1 result=REFUSED:${refused.reason} workItems=0 (NOT_TIMED, no partial result escapes)")
    }
}
