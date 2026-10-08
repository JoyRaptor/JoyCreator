package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.bench.Bench
import cc.joycreator.joybrush.core.bench.BenchCase
import cc.joycreator.joybrush.core.bench.Verdict
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fast-C desktop bench + geometric fidelity vs an analytic oracle (Lead-approved proposal, sole owned file).
 *
 * SCOPE (only this file is new/owned; no production edits):
 * - Uses ONLY actual public APIs: [MediaInput.sample], [MediaSpline.add]/[MediaSpline.finish],
 *   [DryStroke.add]/[DryStroke.take], [Bench.measure], [stickContact], [DabBatch.FLOATS].
 * - Private [MediaSpline.segment] (MediaSpline.kt:38) and [DryStroke.emit] (Stick.kt:192) are NOT
 *   called directly; stage costs are timed through the public builders instead.
 * - No production files, dependencies, thresholds, brush-feel, phone, GPU, network, or file IO.
 * - Fixtures are fixed analytic inputs (no wall-clock construction). Mutable builders are recreated
 *   per replay; allocation of the per-replay output list/batch IS included in the timed region and
 *   is disclosed wherever timing occurs.
 *
 * FIXTURE CONVENTION (explicit Npoints vs Nsegments):
 * - Npoints includes BOTH endpoints; Nsegments = Npoints - 1.
 * - Sparse densities under test: 8/16/32/64 Npoints (7/15/31/63 Nsegments) plus dense reference.
 * - Dense reference: 361 Npoints (360 Nsegments, 0.75 deg per step over the 270 deg arc). Bounded:
 *   the largest spline subdivision here is a few thousand points and the largest dab batch a few
 *   thousand dabs; no massive arrays are allocated.
 * - REQUIRED geometry: SCREEN radii 250/500/750 px x durations 60/120/240 ms (9 combos), 270 deg "C"
 *   arc from [START_DEG] sweeping [ARC_DEG]. Zoom 1/2/4 explicitly inverts screen->doc so every zoom
 *   retains the REQUIRED apparent (screen) radius.
 * - Units: PenSample/MediaSample x,y are DOCUMENT px. Screen px = doc px * zoom. Apparent radius
 *   = docR * zoom = screenR by construction. Contact lateral extents are mm in [StickContact];
 *   screen px = mm * DOC_PX_PER_MM * zoom. Squeeze [StickContact.d] stays mm and is compared in mm.
 *
 * ORACLE LIMITS (labelled, not hidden):
 * - Dense centerline/pressure/tilt/azimuth are analytic and INDEPENDENT of the production spline.
 *   Tilt mapping mirrors MediaInput.REACH_DEG (68 deg -> PI/2) analytically; it is NOT computed by
 *   calling MediaInput, and a dedicated test asserts the two agree within Float rounding.
 * - Contact comparison uses PRODUCTION [stickContact] on BOTH sides (generated vs dense reference).
 *   It therefore oracles only input interpolation + geometry, NOT contact physics. This limit is
 *   stated in the test names/comments and must not be read as a physics oracle.
 * - Geometric deviation is NOT a raster/GPU oracle. No desktop media GPU/raster oracle is established
 *   here; raster/GPU timing is UNMEASURED (a separate headless real-media GL scope only if Lead
 *   authorizes it). GPU upload is an ESTIMATE (count * FLOATS * 4 bytes); dirty-area overlap is an
 *   ESTIMATE separated from real timings.
 */
class MediaFastCBenchTest {

    // ---- Required fixture constants (fixed, deterministic) ----

    private data class Geom(val screenR: Double, val durationMs: Double, val zoom: Double) {
        val docR: Double get() = screenR / zoom
        fun tag(): String = "R${screenR.toInt()}px_T${durationMs.toInt()}ms_Z${zoom.toInt()}x"
    }

    companion object {
        private val SCREEN_RADII = doubleArrayOf(250.0, 500.0, 750.0)
        private const val ARC_DEG = 270.0
        private const val START_DEG = -135.0 // symmetric "C" opening west, through 0 deg
        private val DURATIONS_MS = doubleArrayOf(60.0, 120.0, 240.0)
        private val ZOOMS = doubleArrayOf(1.0, 2.0, 4.0)
        private const val SCREEN_CX = 1000.0
        private const val SCREEN_CY = 1000.0

        // Npoints convention (includes both endpoints). Dense = 361 pts / 360 segs.
        private const val DENSE_NPOINTS = 361
        private val SPARSE_NPOINTS = intArrayOf(8, 16, 32, 64)

        private const val SPLINE_STEP_PX = 0.75 // pins MediaSpline default (MediaSpline.kt:16,48)
        private const val STICK_NAME = "Proto"
        private const val TOOTH_MM = 0.12 // FIXED bench constant (matches golden paper 0.12); not read from disk
        // DOC_PX_PER_MM (20.0, MediaMath.kt:16) is used directly as DryStroke pxPerMm (doc px per mm).

        // Bench repetition: Bench defaults (warmup 3, repeats 9) + small inner loop to keep desktop bounded.
        private const val WARMUP = 3
        private const val REPEATS = 9
        private const val INNER_REPLAYS = 1 // bounded full geometry/density cost sweep

        // ---- Analytic oracle functions (independent of production spline) ----

        /** High-varying pressure 0.15..0.9 with a mid dip at u=0.5 (u in 0..1 along the arc). */
        private fun pressureAt(u: Double): Double {
            val base = 0.525 + 0.375 * sin(2.0 * PI * u + 0.6)
            val z = (u - 0.5) / 0.07
            val dip = 0.22 * exp(-z * z)
            return (base - dip).coerceIn(0.15, 0.9)
        }

        /** Tilt 0..68 deg (pen-from-normal), full S-Pen reach. u=0 -> 0, u=0.5 -> 68, u=1 -> 0. */
        private fun tiltDegAt(u: Double): Double = 34.0 * (1.0 - cos(2.0 * PI * u))

        /** Changing azimuth in degrees, kept inside (-180,180) so no wrap ambiguity. */
        private fun azimuthDegAt(u: Double): Double = -60.0 + 140.0 * u + 25.0 * sin(4.0 * PI * u)

        private fun azimuthRadAt(u: Double): Double = azimuthDegAt(u) * PI / 180.0

        /** Analytic MediaSample tilt (0..PI/2) mirroring MediaInput REACH_DEG=68 deg analytically. */
        private fun tiltMediaAt(u: Double): Double = tiltDegAt(u) / 68.0 * (PI / 2.0)

        private fun angleDegAt(u: Double): Double = START_DEG + u * ARC_DEG

        /** Document coords of the true circle at path parameter u (independent of spline). */
        private fun docPoint(g: Geom, u: Double): DoubleArray {
            val a = angleDegAt(u) * PI / 180.0
            val screenX = SCREEN_CX + g.screenR * cos(a)
            val screenY = SCREEN_CY + g.screenR * sin(a)
            return doubleArrayOf(screenX / g.zoom, screenY / g.zoom)
        }

        /** Raw PenSamples in DOCUMENT px (Float) with fixed times; endpoints/duration retained. */
        private fun penSamples(g: Geom, nPoints: Int): List<PenSample> {
            require(nPoints >= 2) { "need endpoints, got $nPoints" }
            val out = ArrayList<PenSample>(nPoints)
            for (i in 0 until nPoints) {
                val u = i.toDouble() / (nPoints - 1).toDouble()
                val d = docPoint(g, u)
                out.add(
                    PenSample(
                        x = d[0].toFloat(),
                        y = d[1].toFloat(),
                        timeMs = u * g.durationMs,
                        pressure = pressureAt(u).toFloat(),
                        tilt = (tiltDegAt(u) * PI / 180.0).toFloat(),
                        azimuth = azimuthRadAt(u).toFloat(),
                    ),
                )
            }
            return out
        }

        /** Dense analytic MediaSample reference (Double precision, NOT via MediaInput). */
        private fun denseMedia(g: Geom, nPoints: Int = DENSE_NPOINTS): List<MediaSample> {
            require(nPoints >= 2) { "need endpoints, got $nPoints" }
            val out = ArrayList<MediaSample>(nPoints)
            for (i in 0 until nPoints) {
                val u = i.toDouble() / (nPoints - 1).toDouble()
                val d = docPoint(g, u)
                out.add(MediaSample(d[0], d[1], pressureAt(u), tiltMediaAt(u), azimuthRadAt(u), u * g.durationMs))
            }
            return out
        }

        /** Coarse 16-point (15-segment) analytic polyline staying a POLYLINE (positive control input). */
        private fun coarsePolyline(g: Geom): List<MediaSample> = denseMedia(g, 16)

        private fun allGeoms(): List<Geom> {
            val out = ArrayList<Geom>(27)
            for (r in SCREEN_RADII) for (t in DURATIONS_MS) for (z in ZOOMS) out.add(Geom(r, t, z))
            return out
        }

        // ---- Actual pipeline runners (public APIs only) ----

        private fun splineFromPens(pens: List<PenSample>): List<MediaSample> {
            val out = ArrayList<MediaSample>(pens.size * 4)
            val sp = MediaSpline(stepPx = SPLINE_STEP_PX) { out.add(it) }
            for (p in pens) sp.add(MediaInput.sample(p))
            sp.finish()
            return out
        }

        private fun splineFromMedia(medias: List<MediaSample>): List<MediaSample> {
            val out = ArrayList<MediaSample>(medias.size * 4)
            val sp = MediaSpline(stepPx = SPLINE_STEP_PX) { out.add(it) }
            for (m in medias) sp.add(m)
            sp.finish()
            return out
        }

        private fun dryDabs(splineOut: List<MediaSample>): DabBatch {
            val ds = DryStroke(Sticks.ALL[STICK_NAME]!!, TOOTH_MM, DOC_PX_PER_MM)
            for (s in splineOut) ds.add(s)
            return ds.take()
        }

        private fun combinedDabs(medias: List<MediaSample>): DabBatch {
            val ds = DryStroke(Sticks.ALL[STICK_NAME]!!, TOOTH_MM, DOC_PX_PER_MM)
            val sp = MediaSpline(stepPx = SPLINE_STEP_PX) { ds.add(it) }
            for (m in medias) sp.add(m)
            sp.finish()
            return ds.take()
        }

        // ---- Deterministic folds (Bench.run must return output-derived Long) ----

        private fun checksumSamples(samples: List<MediaSample>): Long {
            var h = 0L
            for (s in samples) {
                h = h * 31L + s.x.toBits()
                h = h * 31L + s.y.toBits()
                h = h * 31L + s.p.toBits()
                h = h * 31L + s.tilt.toBits()
                h = h * 31L + s.az.toBits()
                h = h * 31L + s.t.toBits()
            }
            return h
        }

        private fun checksumBatch(b: DabBatch): Long {
            var h = b.count.toLong() * -0x61c8864680b583ebL // golden-ratio mix of the count
            for (f in b.dabs) h = h * 31L + f.toBits().toLong()
            val dirty = b.dirty
            if (dirty != null) for (d in dirty) h = h * 31L + d.toBits()
            return h
        }

        // ---- Segment-aware screen-pixel distance ----

        private fun pointToSegmentScreenPx(
            px: Double, py: Double,
            ax: Double, ay: Double, bx: Double, by: Double,
            zoom: Double,
        ): Double {
            val abx = (bx - ax) * zoom
            val aby = (by - ay) * zoom
            val apx = (px - ax) * zoom
            val apy = (py - ay) * zoom
            val len2 = abx * abx + aby * aby
            if (len2 < 1e-18) return hypot(apx, apy)
            var w = (apx * abx + apy * aby) / len2
            if (w < 0.0) w = 0.0
            if (w > 1.0) w = 1.0
            val dx = apx - abx * w
            val dy = apy - aby * w
            return hypot(dx, dy)
        }

        private fun maxDevOneWayScreenPx(
            from: List<MediaSample>, segs: List<MediaSample>, zoom: Double,
        ): Double {
            require(segs.size >= 2) { "need >=2 segment points" }
            var worst = 0.0
            for (p in from) {
                var best = Double.MAX_VALUE
                for (i in 0 until segs.size - 1) {
                    val d = pointToSegmentScreenPx(p.x, p.y, segs[i].x, segs[i].y, segs[i + 1].x, segs[i + 1].y, zoom)
                    if (d < best) best = d
                }
                if (best > worst) worst = best
            }
            return worst
        }

        /** Symmetric Hausdorff-style max over segment-aware distances, in SCREEN px. */
        private fun symmetricMaxScreenPx(a: List<MediaSample>, b: List<MediaSample>, zoom: Double): Double =
            maxOf(maxDevOneWayScreenPx(a, b, zoom), maxDevOneWayScreenPx(b, a, zoom))

        private fun exactSagittaScreenPx(screenR: Double): Double {
            val half = (18.0 / 2.0) * PI / 180.0 // 9 deg: half of the 18 deg chord
            return screenR * (1.0 - cos(half))
        }
    }

    // ---- 1. Exact geometry/zoom units + required-case coverage ----

    @Test fun geometryUnitsExactAndAllCasesRepresented() {
        val geoms = allGeoms()
        // 3 radii x 3 durations x 3 zooms = 27; the 9 required screen-geometry/duration combos each at zoom 1.
        assertEquals(27, geoms.size, "3 radii x 3 durations x 3 zooms")
        assertEquals(9, geoms.count { it.zoom == 1.0 }, "9 required cases at zoom 1x")
        for (z in ZOOMS) assertEquals(9, geoms.count { it.zoom == z }, "9 cases at zoom ${z.toInt()}x")

        for (g in geoms) {
            // Explicit screen->doc inversion: doc radius is screen radius divided by zoom.
            assertEquals(g.screenR / g.zoom, g.docR, 1e-12, "${g.tag()} doc radius")
            // Every zoom retains the REQUIRED apparent (screen) radius: docR * zoom == screenR.
            assertEquals(g.screenR, g.docR * g.zoom, 1e-9, "${g.tag()} apparent radius")
            // Spot-check the true circle: start/end/mid lie at exactly screenR in SCREEN px.
            for (u in doubleArrayOf(0.0, 0.5, 1.0)) {
                val d = docPoint(g, u)
                val sx = d[0] * g.zoom
                val sy = d[1] * g.zoom
                val r = hypot(sx - SCREEN_CX, sy - SCREEN_CY)
                assertEquals(g.screenR, r, 1e-9, "${g.tag()} u=$u apparent radius")
            }
            // Npoints vs Nsegments convention: endpoints retained, duration retained, no thinning.
            for (n in SPARSE_NPOINTS) {
                val pens = penSamples(g, n)
                assertEquals(n, pens.size, "${g.tag()} Npoints=$n")
                assertEquals(n - 1, pens.size - 1, "${g.tag()} Nsegments=${n - 1} (Npoints includes both endpoints)")
                assertEquals(0.0, pens.first().timeMs, 1e-12, "${g.tag()} t0")
                assertEquals(g.durationMs, pens.last().timeMs, 1e-9, "${g.tag()} duration retained")
            }
            val dense = denseMedia(g)
            assertEquals(DENSE_NPOINTS, dense.size, "${g.tag()} dense Npoints")
            assertEquals(0.0, dense.first().t, 1e-12, "${g.tag()} dense t0")
            assertEquals(g.durationMs, dense.last().t, 1e-9, "${g.tag()} dense duration")
        }

        // High-varying inputs are actually present in the oracle (pressure dip, full tilt reach, moving az).
        val probe = denseMedia(Geom(500.0, 120.0, 1.0))
        val ps = probe.map { it.p }
        val tilts = probe.map { it.tilt }
        assertTrue((ps.maxOrNull() ?: 0.0) >= 0.89, "pressure reaches ~0.9, got ${ps.maxOrNull()}")
        assertTrue((ps.minOrNull() ?: 1.0) <= 0.16, "pressure reaches ~0.15, got ${ps.minOrNull()}")
        val mid = pressureAt(0.5)
        val side = (pressureAt(0.25) + pressureAt(0.75)) / 2.0
        assertTrue(mid < side - 0.05, "mid dip present: mid=$mid sideAvg=$side")
        assertEquals(0.0, tilts.minOrNull() ?: -1.0, 1e-12, "tilt starts upright")
        assertTrue((tilts.maxOrNull() ?: 0.0) >= PI / 2.0 - 1e-12, "tilt reaches flat (68deg pen -> PI/2 media)")
        val azs = probe.map { it.az * 180.0 / PI }
        assertTrue((azs.maxOrNull() ?: 0.0) - (azs.minOrNull() ?: 0.0) > 60.0, "azimuth changes, range=${azs.maxOrNull()}-${azs.minOrNull()}")
    }

    // ---- 2. MediaInput tilt mapping agrees with the analytic oracle (units check) ----

    @Test fun mediaInputConversionMatchesAnalyticOracleWithinFloatRounding() {
        for (z in ZOOMS) {
            val g = Geom(500.0, 120.0, z)
            val pens = penSamples(g, 32)
            for ((i, p) in pens.withIndex()) {
                val m = MediaInput.sample(p)
                val ref = denseMedia(g, 32)[i]
                // Positions pass through (Float rounding only): tolerance well above 1e-4 doc px.
                assertEquals(ref.x, m.x, 1e-3, "x z=${z.toInt()}x i=$i")
                assertEquals(ref.y, m.y, 1e-3, "y z=${z.toInt()}x i=$i")
                assertEquals(ref.p, m.p, 1e-6, "pressure z=${z.toInt()}x i=$i")
                assertEquals(ref.tilt, m.tilt, 1e-6, "tilt mapping z=${z.toInt()}x i=$i")
                assertEquals(ref.az, m.az, 1e-6, "azimuth z=${z.toInt()}x i=$i")
                assertEquals(ref.t, m.t, 1e-9, "time z=${z.toInt()}x i=$i")
            }
        }
    }

    // ---- 3. Positive control: coarse 15-segment polyline MUST show the exact sagitta ----

    @Test fun coarsePolylinePositiveControlDetectsExactSagitta() {
        // 15 segments over 270 deg = 18 deg per chord; half-angle 9 deg.
        // Exact screen sagitta R*(1-cos 9deg); at 750 px ~= 9.23 px. The control STAYS a polyline:
        // production Catmull-Rom smoothing of coarse input is measured separately in test 4.
        for (screenR in SCREEN_RADII) {
            val g = Geom(screenR, 120.0, 1.0)
            val coarse = coarsePolyline(g) // 16 Npoints / 15 Nsegments, analytic, no spline
            assertEquals(16, coarse.size, "control Npoints=16 (15 segments)")
            val dense = denseMedia(g)
            val measured = symmetricMaxScreenPx(dense, coarse, g.zoom)
            val expected = exactSagittaScreenPx(screenR)
            // Documented numerical sampling error: dense 361-pt reference sags ~0.016 px at 750 px
            // (750*(1-cos 0.375deg)), plus Float-free Double rounding; 0.1 px tolerance covers it.
            assertEquals(expected, measured, 0.1, "R${screenR.toInt()}px sagitta")
            assertTrue(measured > 1.0, "control deviation is detectable, got $measured px at R${screenR.toInt()}px")
        }
        // Pin the headline number: 750 px control is about 9.23 px, so a metric that reported ~0 would be broken.
        val big = symmetricMaxScreenPx(denseMedia(Geom(750.0, 120.0, 1.0)), coarsePolyline(Geom(750.0, 120.0, 1.0)), 1.0)
        assertEquals(9.23, big, 0.15, "750px control ~= 9.23 px")
        assertEquals(750.0 * (1.0 - cos(9.0 * PI / 180.0)), exactSagittaScreenPx(750.0), 1e-12, "sagitta formula")
    }

    // ---- 4. Dense-vs-spline symmetric deviation: REPORTED for every required case, not thresholded ----

    @Test fun denseVsSplineSymmetricDeviationReportedForAllRequiredCases() {
        val lines = ArrayList<String>()
        // 9 required cases at zoom 1x, densities 8/16/32/64 Npoints + dense reference size.
        for (screenR in SCREEN_RADII) {
            for (dur in DURATIONS_MS) {
                val g = Geom(screenR, dur, 1.0)
                val dense = denseMedia(g)
                for (n in SPARSE_NPOINTS) {
                    val pens = penSamples(g, n) // fixture built OUTSIDE any timing
                    val gen = splineFromPens(pens)
                    // Structural, not fidelity, assertions: endpoints/duration retained, subdivision happened.
                    // Position tolerance is 1e-3 doc px (Float rounding via PenSample); time is Double-exact.
                    assertEquals(0.0, gen.first().t, 1e-9, "${g.tag()} N$n t0")
                    assertEquals(dur, gen.last().t, 1e-6, "${g.tag()} N$n duration")
                    assertEquals(dense.first().x, gen.first().x, 1e-3, "${g.tag()} N$n x0")
                    assertEquals(dense.last().x, gen.last().x, 1e-3, "${g.tag()} N$n x1")
                    assertTrue(gen.size >= n, "${g.tag()} N$n spline subdivides (in=$n out=${gen.size})")
                    // Default step 0.75 px amplifies work: report raw in, spline out, and ratio independently.
                    val dev = symmetricMaxScreenPx(dense, gen, g.zoom)
                    assertTrue(dev.isFinite() && dev >= 0.0, "${g.tag()} N$n finite deviation")
                    lines.add(
                        "${g.tag()} Npoints=$n Nsegments=${n - 1} rawIn=$n splineOut=${gen.size} " +
                            "maxDevScreenPx=%.4f".format(dev),
                    )
                }
            }
        }
        // Zoom sweep at the representative geometry: every zoom retains the apparent radius, so the
        // SCREEN-px deviation is comparable across zooms (doc deviation * zoom).
        for (z in ZOOMS) {
            val g = Geom(500.0, 120.0, z)
            val dense = denseMedia(g)
            val gen = splineFromPens(penSamples(g, 32))
            val dev = symmetricMaxScreenPx(dense, gen, g.zoom)
            lines.add("${g.tag()} Npoints=32 zoomCheck maxDevScreenPx=%.4f".format(dev))
        }
        // REPORT without approving any fidelity tradeoff: print for the coordinator log; assert line coverage.
        for (l in lines) println(l)
        assertEquals(9 * SPARSE_NPOINTS.size + ZOOMS.size, lines.size, "reported lines cover 9 cases x densities + zoom sweep")
        // No fidelity threshold asserted here by design (Lead decision, not a test constant).
    }

    // ---- 5. Pressure/tilt/azimuth + contact extents vs dense oracle (oracle limits labelled) ----

    @Test fun pressureTiltAzimuthAndContactAgainstDenseOracleWithLabelledLimits() {
        // ORACLE LIMIT: stickContact (production) is used on BOTH sides, so this oracles only the
        // spline's p/tilt/az interpolation + geometry, NOT contact physics. Do not read it as physics truth.
        val stick = Sticks.ALL[STICK_NAME]!!
        var worstP = 0.0
        var worstTilt = 0.0
        var worstWidthScreenPx = 0.0
        for (screenR in SCREEN_RADII) {
            for (dur in DURATIONS_MS) {
                val g = Geom(screenR, dur, 1.0)
                val gen = splineFromPens(penSamples(g, 32))
                for (s in gen) {
                    val u = (s.t / dur).coerceIn(0.0, 1.0)
                    worstP = maxOf(worstP, abs(s.p - pressureAt(u)))
                    worstTilt = maxOf(worstTilt, abs(s.tilt - tiltMediaAt(u)))
                    val cGen = stickContact(stick, s.tilt, s.p, TOOTH_MM)
                    val cRef = stickContact(stick, tiltMediaAt(u), pressureAt(u), TOOTH_MM)
                    assertTrue(cGen != null && cRef != null, "pressure floor 0.15 keeps contact non-null")
                    // Lateral width in SCREEN px: mm * DOC_PX_PER_MM * zoom.
                    val wGen = maxOf(0.05, minOf(cGen!!.xMax - cGen.xMin, 2.0 * cGen.yMax)) * DOC_PX_PER_MM * g.zoom
                    val wRef = maxOf(0.05, minOf(cRef!!.xMax - cRef.xMin, 2.0 * cRef.yMax)) * DOC_PX_PER_MM * g.zoom
                    worstWidthScreenPx = maxOf(worstWidthScreenPx, abs(wGen - wRef))
                }
            }
        }
        println("contact-oracle-limits: production stickContact on BOTH sides; worst |dp|=%.5f worst |dtilt|=%.6f rad worst |dWidthScreenPx|=%.4f".format(worstP, worstTilt, worstWidthScreenPx))
        assertTrue(worstP.isFinite() && worstTilt.isFinite() && worstWidthScreenPx.isFinite(), "finite channel deviations")
        // The spline linearly interpolates p/tilt between inputs while the oracle is sinusoidal+dip, so
        // subdivided points must deviate slightly: proves the channel comparison is active, not vacuous.
        assertTrue(worstP > 1e-9, "pressure interpolation deviation active (worstP=$worstP)")
        assertTrue(worstWidthScreenPx > 1e-9, "contact-width deviation active (worst=$worstWidthScreenPx screen px)")
    }

    // ---- 6. Deterministic replay: checksums + counts are exact ----

    @Test fun deterministicReplayChecksumsAndCountsAreExact() {
        val g = Geom(500.0, 120.0, 1.0)
        val pens = penSamples(g, 32)
        val medias = pens.map { MediaInput.sample(it) }
        val out1 = splineFromMedia(medias)
        val out2 = splineFromMedia(medias)
        assertEquals(out1.size, out2.size, "spline output count deterministic")
        assertEquals(checksumSamples(out1), checksumSamples(out2), "spline checksum deterministic")

        val b1 = dryDabs(out1)
        val b2 = dryDabs(out1)
        assertEquals(b1.count, b2.count, "dab count deterministic")
        assertEquals(checksumBatch(b1), checksumBatch(b2), "dab checksum deterministic")

        val c1 = combinedDabs(medias)
        val c2 = combinedDabs(medias)
        assertEquals(c1.count, c2.count, "combined dab count deterministic")
        assertEquals(checksumBatch(c1), checksumBatch(c2), "combined checksum deterministic")
        // Separated replay vs combined callback pipeline are independently reported (counts may differ
        // only by collection order effects; both are deterministic — equality is NOT required here).
        println("replay: splineOut=${out1.size} splineChecksum=${checksumSamples(out1)} dabs=${b1.count} combined=${c1.count}")
    }

    // ---- 7. Actual stage costs separated with Bench (no unstable ms assertions) ----

    @Test fun actualStageCostsSeparatedWithBenchMedianAndP95() {
        // All nine required screen geometry/duration cases at zoom1 and every sparse density.
        // Fixtures are built ONCE outside the timed region; each replay recreates its mutable
        // builder (MediaSpline / DryStroke / output list), so allocation IS included and disclosed.
        for (g in allGeoms().filter { it.zoom == 1.0 }) for (nPoints in SPARSE_NPOINTS) {
        val pens = penSamples(g, nPoints)
        val medias = pens.map { MediaInput.sample(it) }
        val splineOut = splineFromMedia(medias) // precomputed outputs replayed into DryStroke separately

        // Stage A: public MediaInput conversion only.
        val inputCase = BenchCase("fastC-input-${g.tag()}-N$nPoints") {
            var h = 0L
            repeat(INNER_REPLAYS) {
                val tmp = ArrayList<MediaSample>(pens.size)
                for (p in pens) tmp.add(MediaInput.sample(p))
                h = h * 31L + checksumSamples(tmp)
            }
            h
        }
        // Stage B: actual spline with a collecting sink (raw in vs spline out reported independently).
        val splineCase = BenchCase("fastC-spline-${g.tag()}-N$nPoints") {
            var h = 0L
            var n = 0
            repeat(INNER_REPLAYS) {
                val tmp = ArrayList<MediaSample>()
                val sp = MediaSpline(stepPx = SPLINE_STEP_PX) { tmp.add(it) }
                for (m in medias) sp.add(m)
                sp.finish()
                n = tmp.size
                h = h * 31L + checksumSamples(tmp)
            }
            h xor n.toLong()
        }
        // Stage C: replay precomputed spline outputs into actual DryStroke.
        val dryCase = BenchCase("fastC-dry-replay-${g.tag()}-N$nPoints") {
            var h = 0L
            var n = 0
            repeat(INNER_REPLAYS) {
                val ds = DryStroke(Sticks.ALL[STICK_NAME]!!, TOOTH_MM, DOC_PX_PER_MM)
                for (s in splineOut) ds.add(s)
                val b = ds.take()
                n = b.count
                h = h * 31L + checksumBatch(b)
            }
            h xor n.toLong()
        }
        // Stage D: combined spline->DryStroke callback pipeline (exposes collection/allocation effects).
        val combinedCase = BenchCase("fastC-combined-${g.tag()}-N$nPoints") {
            var h = 0L
            var n = 0
            repeat(INNER_REPLAYS) {
                val ds = DryStroke(Sticks.ALL[STICK_NAME]!!, TOOTH_MM, DOC_PX_PER_MM)
                val sp = MediaSpline(stepPx = SPLINE_STEP_PX) { ds.add(it) }
                for (m in medias) sp.add(m)
                sp.finish()
                val b = ds.take()
                n = b.count
                h = h * 31L + checksumBatch(b)
            }
            h xor n.toLong()
        }

        val outcomes = listOf(inputCase, splineCase, dryCase, combinedCase).map { c ->
            // Bench accepts Long ticks. Measure in nanoseconds, then explicitly convert ALL kept
            // durations to milliseconds before median/p95/report; the budget remains unset.
            val measured = Bench.measure(c, warmup = WARMUP, repeats = REPEATS, nowMs = System::nanoTime)
            measured.copy(samplesMs = measured.samplesMs.map { it / 1_000_000.0 })
        }
        // Independent counts (outside timing): raw in, spline out/subdivisions, generated dabs, combined.
        val soloDabs = dryDabs(splineOut)
        val soloCombined = combinedDabs(medias)
        println(
            "costs rawIn=${pens.size} splineOut=${splineOut.size} dabs=${soloDabs.count} " +
                "dabChecksum=${checksumBatch(soloDabs)} combined=${soloCombined.count} " +
                "combinedChecksum=${checksumBatch(soloCombined)}",
        )
        println(Bench.report(outcomes, "desktop-jvm (no device; nanosecond ticks converted to ms; allocation included)"))
        // Structural assertions only: samples kept, checksums deterministic across a second measure.
        // NO ms-threshold assertions (unstable); budgets are NaN -> NO_BUDGET by harness design.
        for (o in outcomes) {
            assertEquals(REPEATS, o.samplesMs.size, "${o.name} kept samples")
            assertTrue(o.samplesMs.all { it.isFinite() }, "${o.name} finite samples")
            // Bench.measure returns budget NaN (no plan table here), so the harness verdict is NO_BUDGET
            // by design (Bench.kt:59-63): neither a pass nor a fail. No ms-threshold assertion follows.
            assertEquals(Verdict.NO_BUDGET, o.verdict(), "${o.name} has no budget set")
        }
        val remeasure = Bench.measure(splineCase, warmup = WARMUP, repeats = REPEATS, nowMs = System::nanoTime)
        assertEquals(outcomes[1].checksum, remeasure.checksum, "spline stage checksum deterministic under Bench")
        }
    }

    // ---- 8. GPU upload + dirty estimates are LABELLED estimates; raster/GPU timing UNMEASURED ----

    @Test fun gpuUploadAndDirtyEstimatesAreLabelledAndRasterUnmeasured() {
        // DabBatch.FLOATS is currently 20 floats per instance (MediaMath.kt:45); pin it so the
        // ESTIMATE formula cannot silently drift.
        assertEquals(20, DabBatch.FLOATS, "upload estimate assumes 20 floats per dab")
        val g = Geom(500.0, 120.0, 1.0)
        val batch = dryDabs(splineFromPens(penSamples(g, 32)))
        // ESTIMATED upload bytes (not a timing): count * FLOATS * 4 bytes.
        val estimatedUploadBytes = batch.count.toLong() * DabBatch.FLOATS.toLong() * 4L
        assertEquals(batch.count.toLong() * 80L, estimatedUploadBytes, "estimate formula")
        println("ESTIMATED gpu upload bytes=${estimatedUploadBytes} for dabs=${batch.count} (formula count*20*4, NOT measured)")
        // Dirty rect / window-overlap are ESTIMATES separated from real timings (doc px -> screen px).
        val dirty = batch.dirty
        assertTrue(dirty != null && dirty.size == 4, "dry batch carries a doc-px dirty rect")
        val d = dirty!!
        val areaDocPx = (d[2] - d[0]) * (d[3] - d[1])
        val areaScreenPx = areaDocPx * g.zoom * g.zoom
        assertTrue(areaDocPx >= 0.0 && areaScreenPx >= 0.0, "finite dirty areas")
        println("ESTIMATED dirty docPxArea=%.1f screenPxArea=%.1f rect=%s (estimate, NOT a timing)".format(areaDocPx, areaScreenPx, d.toList()))
        // No desktop media GPU/raster oracle is established here: geometric deviation must NOT be
        // substituted for raster error, and no GL/raster timing is claimed. A headless real-media GL
        // scope is proposed ONLY if Lead authorizes it (no such scope is implemented here).
        // (No assertion on raster: raster/GPU timing is UNMEASURED by design in this file.)
    }
}
