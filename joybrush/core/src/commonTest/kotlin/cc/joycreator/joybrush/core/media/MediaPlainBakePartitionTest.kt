package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-NOW-11 — plain-bake sparse/partition equivalence regression (CPU reference only).
 *
 * Existing coverage first (MediaPlainBakeTest, nine tests, read before writing this file):
 * 1. mixedPixelsBakeCopiesLookAndClearsStateExceptCrush — 3 px whole-bake, look copy + clear except crush.
 * 2. thresholdIsStrictlyGreaterThanOneOver255 — exact threshold, adjacent Floats below/above.
 * 3. crushRetainedAsRawBitsWhileFlakesCleared — paper.r NaN payload kept, g/b/a cleared to +0.
 * 4. untouchedPixelsPreserveRawBitsIncludingSignedZeroAndInfinities — -0.0 coverage, infinities kept.
 * 5. missingStoresStayMissingAndEmptyStaysEmpty — sparse keys stay sparse, empty map stays empty.
 * 6. inputsUnchangedAndOutputsAreFreshCopies — immutability plus nonaliasing with mutation isolation.
 * 7. invalidShapesRejected — ground/look/coverage/store shape mismatches throw.
 * 8. invalidCoverageRejectedAndEdgesAccepted — NaN/Inf/out-of-range rejected, -0/0/threshold/1 accepted.
 * 9. unknownStoreIdsRejected — bad IDs throw, all five MediaStores.ALL short names accepted.
 *
 * What this file adds (nothing above is renamed or duplicated): sparse present/absent combinations
 * over all 32 subsets of {p0, p1, paper, w0, w1}; whole-array bake compared against BOTH an
 * independent per-pixel oracle AND concatenated real-API partition bakes at varied split points
 * (including leading/trailing empty partitions through the real bake entry point); zero/full
 * coverage alongside exact-threshold and adjacent-Float edges; arbitrary ground/look byte patterns;
 * untouched nonfinite/raw-bit store values at untouched pixels AND crush-bit preservation at baked
 * pixels; rebake idempotence with identical post-write look/coverage; a 50-iteration deterministic
 * fuzz (fixed LCG seed, no platform randomness); invalid inputs in a partition context; and an
 * intentionally-broken-oracle negative control proving single-byte/single-bit deviations are caught.
 *
 * Bounds: every bake in this file uses at most 8 pixels (largest fixtures are 6 and 8 px; the
 * 32-combination sweep uses 5 px per bake), far under the 256-pixel bound; the fuzz runs exactly
 * 50 deterministic iterations. Production has no dedicated partition entry point, so a "partition"
 * here is a contiguous pixel slice baked through the real MediaPlainBake.bake API and
 * concatenated — equivalence holds because the contract rule is per-pixel independent. The
 * empty-partition contract is inferred from the shape checks (zero-length arrays satisfy every
 * require()) and exercised through the real entry point. CPU-only reference validation: this file
 * proves nothing about GPU plain-writing integration, rendering, water settling, or slice 2.
 *
 * The oracle below is written directly from the contract paragraph (coverage strictly greater than
 * exactly 1f/255f bakes; paper keeps crush r bit-exact and clears g/b/a; other stores clear to +0;
 * everything else is preserved bit-exact), using its own 1f/255f literal rather than the
 * production constant. The production constant is pinned bit-equal to that literal separately, so
 * a threshold change breaks this suite loudly instead of silently following production.
 */
class MediaPlainBakePartitionTest {

    // ---- Independent contract pins (literals, not production references) ----
    private val thr: Float = 1f / 255f
    private val justAbove: Float = Float.fromBits(thr.toRawBits() + 1)
    private val justBelow: Float = Float.fromBits(thr.toRawBits() - 1)

    // ---- Assertion helpers (raw bits, so -0.0 vs +0.0 and NaN payloads are checked) ----
    private fun assertFloatBits(expected: FloatArray, actual: FloatArray, what: String) {
        assertEquals(expected.size, actual.size, "$what size")
        for (i in expected.indices) {
            assertEquals(
                expected[i].toRawBits(),
                actual[i].toRawBits(),
                "$what[$i]: expected bits ${expected[i].toRawBits()} (${expected[i]}) vs ${actual[i].toRawBits()} (${actual[i]})",
            )
        }
    }

    private fun assertGround(expected: ByteArray, actual: ByteArray, what: String = "ground") {
        assertEquals(expected.size, actual.size, "$what size")
        assertTrue(
            expected.contentEquals(actual),
            "$what bytes: expected ${expected.map { it.toInt() and 0xFF }} vs ${actual.map { it.toInt() and 0xFF }}",
        )
    }

    private fun bitsEqual(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) return false
        return true
    }

    // ---- Independent per-pixel oracle (hand-written from the contract, never calls bake) ----
    private fun oracleGround(ground: ByteArray, look: ByteArray, coverage: FloatArray): ByteArray {
        val pixels = coverage.size
        val out = ByteArray(pixels * 4)
        for (i in 0 until pixels) {
            val b = i * 4
            if (coverage[i] > thr) {
                out[b] = look[b]
                out[b + 1] = look[b + 1]
                out[b + 2] = look[b + 2]
                out[b + 3] = look[b + 3]
            } else {
                out[b] = ground[b]
                out[b + 1] = ground[b + 1]
                out[b + 2] = ground[b + 2]
                out[b + 3] = ground[b + 3]
            }
        }
        return out
    }

    private fun oracleStore(name: String, input: FloatArray, coverage: FloatArray): FloatArray {
        val pixels = coverage.size
        val out = FloatArray(pixels * 4)
        for (i in 0 until pixels) {
            val f = i * 4
            if (coverage[i] > thr) {
                if (name == "paper") {
                    out[f] = input[f] // crush (r) stays bit-exact; plain assignment copies raw bits.
                    out[f + 1] = 0f
                    out[f + 2] = 0f
                    out[f + 3] = 0f
                } else {
                    out[f] = 0f
                    out[f + 1] = 0f
                    out[f + 2] = 0f
                    out[f + 3] = 0f
                }
            } else {
                out[f] = input[f]
                out[f + 1] = input[f + 1]
                out[f + 2] = input[f + 2]
                out[f + 3] = input[f + 3]
            }
        }
        return out
    }

    // ---- Slicing helpers (manual loops; no assumption about stdlib slice overloads) ----
    private fun sliceBytes(a: ByteArray, fromPixel: Int, toPixel: Int): ByteArray {
        val out = ByteArray((toPixel - fromPixel) * 4)
        for (i in out.indices) out[i] = a[fromPixel * 4 + i]
        return out
    }

    private fun sliceFloats(a: FloatArray, fromPixel: Int, toPixel: Int): FloatArray {
        val out = FloatArray((toPixel - fromPixel) * 4)
        for (i in out.indices) out[i] = a[fromPixel * 4 + i]
        return out
    }

    private fun sliceCoverage(c: FloatArray, fromPixel: Int, toPixel: Int): FloatArray {
        val out = FloatArray(toPixel - fromPixel)
        for (i in out.indices) out[i] = c[fromPixel + i]
        return out
    }

    /**
     * Bakes each contiguous pixel range [bounds[k], bounds[k+1]) through the real
     * MediaPlainBake.bake entry point — including empty ranges, which go through as zero-length
     * arrays per the real API shape contract — and concatenates the results in order.
     */
    private fun bakeConcatenated(
        ground: ByteArray,
        look: ByteArray,
        coverage: FloatArray,
        stores: Map<String, FloatArray>,
        bounds: IntArray,
    ): MediaPlainBake.BakeResult {
        check(bounds.size >= 2) { "need at least one partition, got ${bounds.size} bounds" }
        check(bounds[0] == 0 && bounds[bounds.size - 1] == coverage.size) {
            "bounds must span [0, ${coverage.size}], got ${bounds.toList()}"
        }
        for (k in 0 until bounds.size - 1) check(bounds[k] <= bounds[k + 1]) {
            "bounds must be non-decreasing, got ${bounds.toList()}"
        }
        val pixels = coverage.size
        check(pixels <= 256) { "fixture $pixels pixels exceeds 256 bound" }
        val catGround = ByteArray(pixels * 4)
        val catStores = LinkedHashMap<String, FloatArray>(stores.size)
        for ((name, _) in stores) catStores[name] = FloatArray(pixels * 4)
        for (k in 0 until bounds.size - 1) {
            val from = bounds[k]
            val to = bounds[k + 1]
            val sliceStores = LinkedHashMap<String, FloatArray>(stores.size)
            for ((name, values) in stores) sliceStores[name] = sliceFloats(values, from, to)
            val part = MediaPlainBake.bake(
                sliceBytes(ground, from, to),
                sliceBytes(look, from, to),
                sliceCoverage(coverage, from, to),
                sliceStores,
            )
            assertEquals(sliceStores.keys, part.stores.keys, "partition $k keeps store keys")
            for (i in part.ground.indices) catGround[from * 4 + i] = part.ground[i]
            for ((name, arr) in catStores) {
                val p = part.stores.getValue(name)
                assertEquals((to - from) * 4, p.size, "partition $k store $name length")
                for (i in p.indices) arr[from * 4 + i] = p[i]
            }
        }
        return MediaPlainBake.BakeResult(catGround, catStores)
    }

    /**
     * Core equivalence check: whole-array actual vs independent oracle (bytes and raw bits),
     * direct rule spot-checks (covered ground, clearing, crush, untouched bits), input
     * immutability, result nonaliasing, and concatenated partition bakes at varied splits
     * (including empty edge partitions) vs both oracle and whole.
     */
    private fun checkWholeAndPartitions(
        ground: ByteArray,
        look: ByteArray,
        coverage: FloatArray,
        stores: Map<String, FloatArray>,
        label: String,
    ) {
        val pixels = coverage.size
        check(pixels <= 256) { "$label: fixture $pixels pixels exceeds 256 bound" }
        val groundSnap = ground.copyOf()
        val lookSnap = look.copyOf()
        val covSnap = coverage.copyOf()
        val storeSnaps = LinkedHashMap<String, FloatArray>(stores.size)
        for ((name, values) in stores) storeSnaps[name] = values.copyOf()

        val expGround = oracleGround(ground, look, coverage)
        val expStores = LinkedHashMap<String, FloatArray>(stores.size)
        for ((name, values) in stores) expStores[name] = oracleStore(name, values, coverage)

        val whole = MediaPlainBake.bake(ground, look, coverage, stores)
        assertGround(expGround, whole.ground, "$label whole ground")
        assertEquals(expStores.keys, whole.stores.keys, "$label whole store keys")
        for ((name, exp) in expStores) assertFloatBits(exp, whole.stores.getValue(name), "$label whole $name")

        // Direct rule spot-checks, independent of the oracle helpers above.
        for (i in 0 until pixels) {
            val baked = coverage[i] > thr
            val b = i * 4
            val f = i * 4
            if (baked) {
                for (c in 0 until 4) {
                    assertEquals(look[b + c], whole.ground[b + c], "$label pixel $i ground byte $c takes look")
                }
                for ((name, _) in stores) {
                    val arr = whole.stores.getValue(name)
                    if (name == "paper") {
                        assertEquals(
                            storeSnaps.getValue(name)[f].toRawBits(), arr[f].toRawBits(),
                            "$label pixel $i crush r bit-exact",
                        )
                        for (c in 1..3) {
                            assertEquals(0, arr[f + c].toRawBits(), "$label pixel $i paper ch $c cleared to +0")
                        }
                    } else {
                        for (c in 0..3) {
                            assertEquals(0, arr[f + c].toRawBits(), "$label pixel $i $name ch $c cleared to +0")
                        }
                    }
                }
            } else {
                for (c in 0 until 4) {
                    assertEquals(groundSnap[b + c], whole.ground[b + c], "$label pixel $i ground byte $c preserved")
                }
                for ((name, _) in stores) {
                    val arr = whole.stores.getValue(name)
                    val snap = storeSnaps.getValue(name)
                    for (c in 0..3) {
                        assertEquals(
                            snap[f + c].toRawBits(), arr[f + c].toRawBits(),
                            "$label pixel $i $name ch $c untouched raw bits",
                        )
                    }
                }
            }
        }

        // Inputs unchanged; outputs alias no input.
        assertTrue(groundSnap.contentEquals(ground), "$label ground input unchanged")
        assertTrue(lookSnap.contentEquals(look), "$label look input unchanged")
        assertTrue(covSnap.contentEquals(coverage), "$label coverage input unchanged")
        for ((name, snap) in storeSnaps) assertFloatBits(snap, stores.getValue(name), "$label $name input unchanged")
        assertTrue(whole.ground !== ground && whole.ground !== look, "$label ground output is fresh")
        assertTrue(whole.stores !== stores, "$label stores map is fresh")
        for ((name, values) in stores) {
            assertTrue(whole.stores.getValue(name) !== values, "$label $name output is fresh")
        }

        // Varied split points, always including empty edge partitions through the real API.
        val splitSets = mutableListOf<IntArray>()
        splitSets.add(intArrayOf(0, pixels))
        if (pixels >= 1) {
            splitSets.add(intArrayOf(0, 1, pixels))
            splitSets.add(intArrayOf(0, pixels - 1, pixels))
        }
        if (pixels >= 2) {
            splitSets.add(intArrayOf(0, pixels / 2, pixels))
            splitSets.add(intArrayOf(0, 0, pixels))
            splitSets.add(intArrayOf(0, pixels, pixels))
        }
        if (pixels >= 3) splitSets.add(intArrayOf(0, 1, pixels - 1, pixels))
        if (pixels >= 4) splitSets.add(intArrayOf(0, 2, 3, pixels))
        for ((s, bounds) in splitSets.withIndex()) {
            val cat = bakeConcatenated(ground, look, coverage, stores, bounds)
            assertGround(expGround, cat.ground, "$label split $s ${bounds.toList()} ground")
            assertEquals(expStores.keys, cat.stores.keys, "$label split $s keys")
            for ((name, exp) in expStores) {
                assertFloatBits(exp, cat.stores.getValue(name), "$label split $s $name vs oracle")
            }
            assertGround(whole.ground, cat.ground, "$label split $s matches whole ground")
            for ((name, _) in expStores) {
                assertFloatBits(
                    whole.stores.getValue(name), cat.stores.getValue(name),
                    "$label split $s $name matches whole",
                )
            }
        }
    }

    /** Rebaking with the same post-write look/coverage must be stable (whole and partitioned). */
    private fun checkIdempotent(
        ground: ByteArray,
        look: ByteArray,
        coverage: FloatArray,
        stores: Map<String, FloatArray>,
        label: String,
    ) {
        val pixels = coverage.size
        check(pixels <= 256) { "$label: fixture $pixels pixels exceeds 256 bound" }
        val first = MediaPlainBake.bake(ground, look, coverage, stores)
        val second = MediaPlainBake.bake(first.ground, look, coverage, first.stores)
        assertGround(first.ground, second.ground, "$label idempotent ground")
        assertEquals(first.stores.keys, second.stores.keys, "$label idempotent store keys")
        for ((name, arr) in first.stores) {
            assertFloatBits(arr, second.stores.getValue(name), "$label idempotent $name")
        }
        val bounds = if (pixels >= 2) intArrayOf(0, pixels / 2, pixels) else intArrayOf(0, pixels)
        val cat = bakeConcatenated(first.ground, look, coverage, first.stores, bounds)
        assertGround(first.ground, cat.ground, "$label partitioned rebake ground stable")
        for ((name, arr) in first.stores) {
            assertFloatBits(arr, cat.stores.getValue(name), "$label partitioned rebake $name stable")
        }
    }

    // ---- Fixed deterministic PRNG (LCG, no platform randomness) ----
    private class Prng(var s: Int) {
        fun next(n: Int): Int {
            s = s * 1103515245 + 12345
            return (s ushr 16) % n
        }
    }

    @Test fun allStorePresenceCombinationsAgree() {
        // Pins the combination space, then sweeps every present/absent subset of the five stores.
        assertEquals(listOf("p0", "p1", "paper", "w0", "w1"), MediaStores.ALL, "combination space")
        val ground = byteArrayOf(
            10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120,
            130.toByte(), 140.toByte(), 150.toByte(), 160.toByte(), 0, 127, 128.toByte(), 255.toByte(),
        )
        val look = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        )
        // zero, exact threshold, just above, full, just below: untouched/baked mix in every bake.
        val coverage = floatArrayOf(0f, thr, justAbove, 1f, justBelow)
        // Tricky raw bits at untouched slots (px 0/1/4); baked slots (px 2/3) clear except crush.
        val edge20 = floatArrayOf(
            -0.0f, Float.fromBits(0x7FC00001), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.fromBits(0x7FC00002), -0.0f, 5f, -0.0f,
            111f, 112f, 113f, 114f,
            Float.fromBits(0x7FC0000B), 222f, 223f, 224f,
            Float.MIN_VALUE, Float.MAX_VALUE, -1f, Float.fromBits(0x7FC0000C),
        )
        for (mask in 0 until 32) {
            val stores = LinkedHashMap<String, FloatArray>(5)
            for ((bit, name) in MediaStores.ALL.withIndex()) {
                if (((mask shr bit) and 1) == 1) stores[name] = edge20.copyOf()
            }
            checkWholeAndPartitions(ground, look, coverage, stores, "stores mask $mask")
        }
    }

    @Test fun thresholdEdgesAndZeroFullPartitionEquivalence() {
        assertEquals(thr.toRawBits(), MediaPlainBake.COVERAGE_THRESHOLD.toRawBits(), "threshold is exactly 1f/255f")
        assertTrue(justAbove > thr && justBelow < thr, "adjacent Floats straddle the threshold")
        val ground = byteArrayOf(
            10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120,
            0, 127, 128.toByte(), 255.toByte(), 1, 3, 5, 7,
            200.toByte(), 201.toByte(), 202.toByte(), 203.toByte(),
        )
        val look = byteArrayOf(
            2, 4, 6, 8, 12, 14, 16, 18, 22, 24, 26, 28, 32, 34, 36, 38, 42, 44, 46, 48, 52, 54, 56, 58,
        )
        // 0, -0.0, exact threshold, just below (all preserve) vs just above, full (both bake).
        val coverage = floatArrayOf(0f, -0.0f, thr, justBelow, justAbove, 1f)
        val shared = floatArrayOf(
            -0.0f, Float.fromBits(0x7FC00011), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            7f, -0.0f, Float.fromBits(0x7FC00012), 8f,
            9f, 10f, 11f, 12f,
            Float.MIN_VALUE, Float.MAX_VALUE, -3f, Float.fromBits(0x7FC00013),
            101f, 102f, 103f, 104f,
            Float.fromBits(0x7FC00014), 202f, 203f, 204f,
        )
        val paper = floatArrayOf(
            -0.0f, Float.fromBits(0x7FC00021), 5f, 6f,
            Float.fromBits(0x7FC00022), 15f, 16f, 17f,
            25f, 26f, 27f, 28f,
            35f, Float.fromBits(0x7FC00023), 37f, 38f,
            Float.fromBits(0x7FC00024), 302f, 303f, 304f,
            -44f, 402f, 403f, 404f,
        )
        val stores = linkedMapOf(
            "p0" to shared.copyOf(),
            "p1" to shared.copyOf(),
            "paper" to paper,
            "w0" to shared.copyOf(),
            "w1" to shared.copyOf(),
        )
        checkWholeAndPartitions(ground, look, coverage, stores, "threshold edges 6px")
        checkIdempotent(ground, look, coverage, stores, "threshold edges 6px")
    }

    @Test fun arbitraryGroundLookAndUntouchedRawBits() {
        // Sparse: p1 and w0 absent; every other store present with hostile raw bits.
        val ground = byteArrayOf(
            255.toByte(), 0, 128.toByte(), 1, 2, 253.toByte(), 127, 129.toByte(),
            17, 34, 51, 68, 85, 102.toByte(), 119.toByte(), 136.toByte(),
            200.toByte(), 100, 50, 25, 0, 0, 0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
            7, 77, 177.toByte(), 207.toByte(),
        )
        val look = byteArrayOf(
            3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45, 48,
            51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84, 87, 90, 93, 96,
        )
        val coverage = floatArrayOf(0f, thr, justBelow, justAbove, 1f, -0.0f, 0.75f, 0.01f)
        val p0 = floatArrayOf(
            Float.fromBits(0x7FC00101), -0.0f, Float.NEGATIVE_INFINITY, 1f,
            2f, Float.fromBits(0x7FC00102), -0.0f, 4f,
            Float.MAX_VALUE, Float.MIN_VALUE, -9f, Float.fromBits(0x7FC00103),
            301f, 302f, 303f, 304f, 401f, 402f, 403f, 404f,
            Float.fromBits(0x7FC00104), 502f, Float.POSITIVE_INFINITY, -0.0f,
            601f, 602f, 603f, 604f, 701f, 702f, 703f, 704f,
        )
        val paper = floatArrayOf(
            11f, 12f, 13f, 14f, 21f, 22f, 23f, 24f,
            31f, Float.fromBits(0x7FC00105), 33f, 34f,
            Float.fromBits(0x7FC00106), 502f, 503f, 504f,
            -5f, 602f, 603f, 604f, 611f, 612f, 613f, 614f,
            711f, 712f, 713f, 714f, 811f, 812f, 813f, 814f,
        )
        val w1 = p0.copyOf()
        val stores = linkedMapOf("p0" to p0, "paper" to paper, "w1" to w1)
        checkWholeAndPartitions(ground, look, coverage, stores, "sparse arbitrary 8px")
        checkIdempotent(ground, look, coverage, stores, "sparse arbitrary 8px")
    }

    @Test fun deterministicFuzzWholeEqualsOracleAndSplits() {
        val prng = Prng(0x1A2B3C4D)
        for (iter in 0 until 50) {
            val pixels = 1 + prng.next(8) // 1..8 px; every bake stays far under the 256 bound.
            val ground = ByteArray(pixels * 4) { prng.next(256).toByte() }
            val look = ByteArray(pixels * 4) { prng.next(256).toByte() }
            val coverage = FloatArray(pixels) {
                when (prng.next(8)) {
                    0 -> 0f
                    1 -> -0.0f
                    2 -> thr
                    3 -> justBelow
                    4 -> justAbove
                    5 -> 0.5f
                    6 -> 1f
                    else -> prng.next(1001).toFloat() / 1000f // valid 0..1 uniform steps.
                }
            }
            val mask = prng.next(32)
            val stores = LinkedHashMap<String, FloatArray>(5)
            for ((bit, name) in MediaStores.ALL.withIndex()) {
                if (((mask shr bit) and 1) == 1) {
                    stores[name] = FloatArray(pixels * 4) {
                        when (prng.next(10)) {
                            0 -> 0f
                            1 -> -0.0f
                            2 -> 1f
                            3 -> -1f
                            4 -> 42.5f
                            5 -> Float.MAX_VALUE
                            6 -> Float.MIN_VALUE
                            7 -> Float.POSITIVE_INFINITY
                            8 -> Float.NEGATIVE_INFINITY
                            else -> Float.fromBits(0x7FC00001 + prng.next(4))
                        }
                    }
                }
            }
            checkWholeAndPartitions(ground, look, coverage, stores, "fuzz $iter")
            if (iter % 10 == 0) checkIdempotent(ground, look, coverage, stores, "fuzz $iter")
        }
    }

    @Test fun emptyPartitionsFollowRealApi() {
        // Pure empty bakes through the real entry point: no stores, and all stores as empty slices.
        val emptyBare = MediaPlainBake.bake(ByteArray(0), ByteArray(0), FloatArray(0), emptyMap())
        assertEquals(0, emptyBare.ground.size, "empty ground stays empty")
        assertTrue(emptyBare.stores.isEmpty(), "empty map stays empty")
        val emptyFullStores = LinkedHashMap<String, FloatArray>(5)
        for (name in MediaStores.ALL) emptyFullStores[name] = FloatArray(0)
        val emptyFull = MediaPlainBake.bake(ByteArray(0), ByteArray(0), FloatArray(0), emptyFullStores)
        assertEquals(0, emptyFull.ground.size, "empty ground stays empty with stores")
        assertEquals(MediaStores.ALL.toSet(), emptyFull.stores.keys, "empty slices keep store keys")
        for ((name, arr) in emptyFull.stores) assertEquals(0, arr.size, "empty slice $name stays empty")

        // Empty partitions concatenated around a real payload equal the whole bake and the oracle.
        val ground = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val look = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val coverage = floatArrayOf(0f, 1f, justBelow)
        val stores = linkedMapOf(
            "p0" to floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f),
            "paper" to floatArrayOf(21f, 22f, 23f, 24f, 25f, 26f, 27f, 28f, 29f, 30f, 31f, 32f),
        )
        val whole = MediaPlainBake.bake(ground, look, coverage, stores)
        val expGround = oracleGround(ground, look, coverage)
        for (bounds in listOf(intArrayOf(0, 0, 3), intArrayOf(0, 3, 3), intArrayOf(0, 0, 3, 3), intArrayOf(0, 0))) {
            // intArrayOf(0, 0) is the degenerate all-empty concatenation; the rest pin leading/trailing empties.
            val cat = if (bounds[bounds.size - 1] == 0 && coverage.size != 0 && bounds.size == 2) {
                // Degenerate shape cannot span a 3 px payload; verified separately below on truly empty inputs.
                bakeConcatenated(ByteArray(0), ByteArray(0), FloatArray(0), emptyMap(), bounds)
            } else {
                bakeConcatenated(ground, look, coverage, stores, bounds)
            }
            if (bounds[bounds.size - 1] == 3) {
                assertGround(expGround, cat.ground, "empty-edged ${bounds.toList()} ground")
                assertGround(whole.ground, cat.ground, "empty-edged ${bounds.toList()} matches whole")
                for ((name, _) in stores) {
                    assertFloatBits(
                        whole.stores.getValue(name), cat.stores.getValue(name),
                        "empty-edged ${bounds.toList()} $name matches whole",
                    )
                }
            } else {
                assertEquals(0, cat.ground.size, "all-empty concatenation stays empty")
            }
        }

        // An empty partition slice with wrongly sized store data must fail, not silently shift pixels.
        assertFailsWith<IllegalArgumentException>("empty partition with 1 px store must fail") {
            MediaPlainBake.bake(ByteArray(0), ByteArray(0), FloatArray(0), mapOf("p0" to FloatArray(4)))
        }
    }

    @Test fun bakeTwiceIsIdempotent() {
        val ground = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16)
        val look = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 17, 18, 19, 20)
        val coverage = floatArrayOf(0f, justAbove, thr, 1f)
        val stores = linkedMapOf(
            "p0" to floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f),
            "p1" to floatArrayOf(17f, 18f, 19f, 20f, 21f, 22f, 23f, 24f, 25f, 26f, 27f, 28f, 29f, 30f, 31f, 32f),
            "paper" to floatArrayOf(
                33f, 34f, 35f, 36f, Float.fromBits(0x7FC00201), 42f, 43f, 44f,
                45f, 46f, 47f, 48f, -7f, 54f, 55f, 56f,
            ),
            "w0" to floatArrayOf(61f, 62f, 63f, 64f, 65f, 66f, 67f, 68f, 69f, 70f, 71f, 72f, 73f, 74f, 75f, 76f),
            "w1" to floatArrayOf(81f, 82f, 83f, 84f, 85f, 86f, 87f, 88f, 89f, 90f, 91f, 92f, 93f, 94f, 95f, 96f),
        )
        checkWholeAndPartitions(ground, look, coverage, stores, "idempotence 4px")
        checkIdempotent(ground, look, coverage, stores, "idempotence 4px")
    }

    @Test fun inputsImmutableAndResultsFresh() {
        val ground = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val look = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val coverage = floatArrayOf(1f, 0f, justAbove)
        val p0In = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f)
        val paperIn = floatArrayOf(13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 21f, 22f, 23f, 24f)
        val stores = linkedMapOf("p0" to p0In, "paper" to paperIn)
        val groundSnap = ground.copyOf()
        val lookSnap = look.copyOf()
        val covSnap = coverage.copyOf()
        val p0Snap = p0In.copyOf()
        val paperSnap = paperIn.copyOf()

        val whole = MediaPlainBake.bake(ground, look, coverage, stores)
        val cat = bakeConcatenated(ground, look, coverage, stores, intArrayOf(0, 1, 3))
        assertGround(whole.ground, cat.ground, "partitioned bake matches whole")
        for ((name, _) in stores) {
            assertFloatBits(whole.stores.getValue(name), cat.stores.getValue(name), "$name partitioned matches whole")
        }

        // Snapshots still equal inputs after whole and partitioned bakes.
        assertTrue(groundSnap.contentEquals(ground), "ground input unchanged")
        assertTrue(lookSnap.contentEquals(look), "look input unchanged")
        assertTrue(covSnap.contentEquals(coverage), "coverage input unchanged")
        assertFloatBits(p0Snap, p0In, "p0 input unchanged")
        assertFloatBits(paperSnap, paperIn, "paper input unchanged")

        // Fresh, non-aliasing outputs in both paths.
        assertTrue(whole.ground !== ground && whole.ground !== look, "whole ground is fresh")
        assertTrue(cat.ground !== ground && cat.ground !== whole.ground, "concatenated ground is fresh")
        assertTrue(whole.stores !== stores && cat.stores !== stores, "store maps are fresh")
        for ((name, values) in stores) {
            assertTrue(whole.stores.getValue(name) !== values, "whole $name is fresh")
            assertTrue(cat.stores.getValue(name) !== values, "concatenated $name is fresh")
            assertTrue(cat.stores.getValue(name) !== whole.stores.getValue(name), "$name paths do not alias")
        }

        // Mutation isolation both directions.
        whole.ground[0] = 99.toByte()
        whole.stores.getValue("p0")[0] = 99f
        assertTrue(groundSnap.contentEquals(ground), "input ground survives output mutation")
        assertEquals(1f.toRawBits(), p0In[0].toRawBits(), "input p0 survives output mutation")
        ground[0] = 77.toByte()
        p0In[0] = 77f
        assertEquals(99.toByte(), whole.ground[0], "output ground stable after input mutation")
        assertEquals(99f.toRawBits(), whole.stores.getValue("p0")[0].toRawBits(), "output p0 stable after input mutation")
        ground[0] = groundSnap[0]
        p0In[0] = p0Snap[0]
    }

    @Test fun invalidCoverageShapeAndStoreIdsRejected() {
        val ground1 = byteArrayOf(10, 20, 30, 40)
        val look1 = byteArrayOf(1, 2, 3, 4)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, -0.001f, 1.000001f, 2f)) {
            assertFailsWith<IllegalArgumentException>("coverage $bad must fail") {
                MediaPlainBake.bake(ground1, look1, floatArrayOf(bad), emptyMap())
            }
        }
        for (good in listOf(-0.0f, 0f, thr, 1f)) {
            MediaPlainBake.bake(ground1, look1, floatArrayOf(good), emptyMap())
        }

        val okGround = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val okLook = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val okCoverage = floatArrayOf(0f, 1f)
        assertFailsWith<IllegalArgumentException>("ground not RGBA8 must fail") {
            MediaPlainBake.bake(byteArrayOf(1, 2, 3, 4, 5), byteArrayOf(1, 2, 3, 4, 5), floatArrayOf(0f))
        }
        assertFailsWith<IllegalArgumentException>("look mismatch must fail") {
            MediaPlainBake.bake(okGround, byteArrayOf(1, 2, 3, 4), okCoverage, emptyMap())
        }
        assertFailsWith<IllegalArgumentException>("short coverage must fail") {
            MediaPlainBake.bake(okGround, okLook, floatArrayOf(0f), emptyMap())
        }
        assertFailsWith<IllegalArgumentException>("long coverage must fail") {
            MediaPlainBake.bake(okGround, okLook, floatArrayOf(0f, 0f, 0f), emptyMap())
        }
        assertFailsWith<IllegalArgumentException>("short store must fail") {
            MediaPlainBake.bake(okGround, okLook, okCoverage, mapOf("p0" to floatArrayOf(1f, 2f, 3f, 4f)))
        }
        assertFailsWith<IllegalArgumentException>("long store must fail") {
            MediaPlainBake.bake(okGround, okLook, okCoverage, mapOf("p0" to FloatArray(12) { it.toFloat() }))
        }
        for (bad in listOf("g", "px", "P0", "paper ", "", "layer#p0", "w2")) {
            assertFailsWith<IllegalArgumentException>("store $bad must fail") {
                MediaPlainBake.bake(ground1, look1, floatArrayOf(0f), mapOf(bad to floatArrayOf(1f, 2f, 3f, 4f)))
            }
        }
        MediaPlainBake.bake(
            ground1, look1, floatArrayOf(0f),
            mapOf(
                "p0" to floatArrayOf(1f, 2f, 3f, 4f),
                "p1" to floatArrayOf(1f, 2f, 3f, 4f),
                "paper" to floatArrayOf(1f, 2f, 3f, 4f),
                "w0" to floatArrayOf(1f, 2f, 3f, 4f),
                "w1" to floatArrayOf(1f, 2f, 3f, 4f),
            ),
        )

        // Partition context: a slice that forgets to slice its stores must fail loudly, not shift pixels.
        val ground2 = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val look2 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val coverage2 = floatArrayOf(0f, 1f)
        assertFailsWith<IllegalArgumentException>("unsliced store with sliced coverage must fail") {
            MediaPlainBake.bake(
                sliceBytes(ground2, 0, 1), sliceBytes(look2, 0, 1), sliceCoverage(coverage2, 0, 1),
                mapOf("p0" to FloatArray(8) { it.toFloat() }),
            )
        }
    }

    @Test fun brokenOracleIsDetectable() {
        // Negative control: proves this suite is not a tautology. A one-byte ground error and a
        // one-bit store error must both be caught by the exact byte/bit comparisons above.
        // (If the implementation ever produced these, the same assertions would fail the suite.)
        val ground = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val look = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val coverage = floatArrayOf(0f, 1f, justBelow)
        val p0In = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, -0.0f, Float.fromBits(0x7FC00031), 13f, 14f)
        val paperIn = floatArrayOf(
            21f, 22f, 23f, 24f, Float.fromBits(0x7FC00032), 26f, 27f, 28f, 31f, 32f, 33f, 34f,
        )
        val stores = linkedMapOf("p0" to p0In, "paper" to paperIn)
        val actual = MediaPlainBake.bake(ground, look, coverage, stores)
        val expGround = oracleGround(ground, look, coverage)
        val expP0 = oracleStore("p0", p0In, coverage)
        val expPaper = oracleStore("paper", paperIn, coverage)

        // Sanity: the honest oracle passes.
        assertGround(expGround, actual.ground, "sanity ground")
        assertFloatBits(expP0, actual.stores.getValue("p0"), "sanity p0")
        assertFloatBits(expPaper, actual.stores.getValue("paper"), "sanity paper")
        assertEquals(
            0x7FC00032, actual.stores.getValue("paper")[4].toRawBits(),
            "sanity: baked crush keeps NaN payload",
        )

        // Corrupt one baked ground byte: must differ, and assertGround must throw.
        val badGround = expGround.copyOf()
        badGround[4] = (badGround[4] + 1).toByte()
        assertFalse(badGround.contentEquals(actual.ground), "one-byte ground corruption must be visible")
        var caughtGround = false
        try {
            assertGround(badGround, actual.ground, "negative control ground")
        } catch (e: AssertionError) {
            caughtGround = true
        }
        assertTrue(caughtGround, "assertGround catches a one-byte bake error")

        // Corrupt one cleared store bit: must differ, and assertFloatBits must throw.
        val badP0 = expP0.copyOf()
        badP0[4] = Float.fromBits(badP0[4].toRawBits() xor 1)
        assertFalse(bitsEqual(badP0, actual.stores.getValue("p0")), "one-bit store corruption must be visible")
        var caughtStore = false
        try {
            assertFloatBits(badP0, actual.stores.getValue("p0"), "negative control p0")
        } catch (e: AssertionError) {
            caughtStore = true
        }
        assertTrue(caughtStore, "assertFloatBits catches a one-bit bake error")

        // Corrupt one crush bit: crush preservation is checked, not assumed.
        val badPaper = expPaper.copyOf()
        badPaper[4] = Float.fromBits(badPaper[4].toRawBits() xor 0xFF)
        assertFalse(bitsEqual(badPaper, actual.stores.getValue("paper")), "one-payload crush corruption must be visible")
        var caughtCrush = false
        try {
            assertFloatBits(badPaper, actual.stores.getValue("paper"), "negative control paper")
        } catch (e: AssertionError) {
            caughtCrush = true
        }
        assertTrue(caughtCrush, "assertFloatBits catches a crush-payload bake error")
    }
}
