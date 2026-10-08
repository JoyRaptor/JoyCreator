package cc.joycreator.joybrush.core.paper

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GPU parity fixture for the Tile adapter: [TilePaperSampler] vs `jb_tile_paper.glsl`.
 *
 * What this emits (into `core/build/tile-paper-gpu-fixture.json`, same directory-walk pattern
 * as `PaperRasterFixtureTest`): one explicit 16x16 RGBA texture, then per case every sampler
 * parameter (texel pitch, hex size, rotatable, slope range, seed, footprint, height mean), the
 * document rect AND its texel-unit conversion (all four components x, y, w, h divided by the
 * pitch — converting only the periods is the classic origin bug), per-probe document AND texel
 * coordinates, the expected surface channels from the ACTUAL sampler, the canonical
 * ([HexTile]-direct, tile-disabled) answer at the same point, and two per-probe support flags
 * for the GPU script's mutation oracle.
 *
 * Units contract (matches the [TilePaperSampler] KDoc): the sampler works in SOURCE TEXELS;
 * the shader works in document px and returns slopes per document px. So the fixture carries
 * document-space probes plus `texelPx`, and the GPU script divides the CPU slopes by `texelPx`
 * before comparing. Footprints match by construction: the GPU script drives fragments with an
 * affine document mapping whose step is exactly `texelPx` per fragment, so
 * `|dFdx(docPx / texelPx)| = 1` and the shader's mip level `log2(derivativeScale)` equals the
 * CPU `log2(footprint)` with `footprint = derivativeScale` numerically.
 *
 * Tolerance contract (shared verbatim with `joybrush/tools/tile_paper_gpu_check.js`): one
 * quantum is 1 LSB — `1/255` for height/moment, `slopeRange/127` for slopes. Fine probes
 * (footprint 1, no mips) allow 2 quanta (UNORM decode plus one bilinear in fp32); coarse
 * probes (footprint 8, trilinear over generated mips) allow 4 quanta (plus one mip byte of
 * box-filter rounding). A probe "supports" a mutation when the CPU answers differ by more
 * than twice the coarse tolerance there, so float wobble cannot flip the verdict.
 *
 * What this test asserts (so a zero/constant sampler cannot pass): the texture bytes are
 * non-flat in every channel; the emitted signal is non-constant in slope, height and moment;
 * the canonical read is non-periodic (active differs from canonical somewhere in every
 * case); the active read is exactly periodic (all +/-period translation pairs agree to 2e-6);
 * moments stay valid (`m >= h*h`); each case supports disabling wrap, while ordinary-footprint
 * cases supply mip-mutation sensitivity and coarse cases retain the full parity contract.
 * No hash/blend/wrap maths is copied here: every number comes out of the actual sampler.
 */
class TilePaperGpuFixtureTest {
    private val texSize = 16
    private val hexTexels = 16.0

    /** Explicit synthetic texture: non-flat slopes/height surviving coarse mips, valid moment. */
    private fun syntheticTexture(): PaperTexture {
        val bytes = ByteArray(texSize * texSize * 4)
        for (y in 0 until texSize) for (x in 0 until texSize) {
            val o = (y * texSize + x) * 4
            // Low-frequency signals survive the 2x2 mip used by footprint8. The previous
            // diagonal height wave averaged to identical bytes there, making coarse checks blind.
            bytes[o] = (128.0 + 110.0 * sin(2.0 * PI * x / texSize)).toInt().toByte()
            bytes[o + 1] = (128.0 + 110.0 * sin(2.0 * PI * y / texSize)).toInt().toByte()
            val h = 0.5 + 0.24 * sin(2.0 * PI * x / texSize) + 0.08 * sin(2.0 * PI * y / texSize)
            bytes[o + 2] = (h * 255.0).toInt().coerceIn(0, 255).toByte()
            val leftover = 0.02 * (0.5 + 0.5 * sin(2.0 * PI * (3 * x + y) / texSize))
            bytes[o + 3] = ((h * h + leftover) * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        return PaperTexture(texSize, texSize, bytes)
    }

    private data class Case(
        val texelPx: Double,
        val rotatable: Boolean,
        val seed: Int,
        val slopeRange: Float,
        val heightMean: Float,
        val footprint: Double,
        val docRect: DoubleArray, // document px: x, y, w, h
    )

    private fun cases(): List<Case> {
        // texelPx 2 reuses the unit-tested texel rect (-13.25, -8.75, 37.5, 22.25) in document px;
        // texelPx 1 uses a second arbitrary negative-origin non-square rect.
        val rect2 = doubleArrayOf(-26.5, -17.5, 75.0, 44.5)
        val rect1 = doubleArrayOf(-61.125, -42.375, 45.5, 19.625)
        val out = mutableListOf<Case>()
        for (texelPx in listOf(1.0, 2.0)) {
            val rect = if (texelPx == 2.0) rect2 else rect1
            for (rot in listOf(true, false)) for (fp in listOf(1.0, 8.0)) {
                out.add(
                    Case(
                        texelPx, rot, if (rot) 7 else 0,
                        if (rot) 0.7f else 0.35f, if (rot) 0.47f else 0.5f,
                        fp, rect,
                    ),
                )
            }
        }
        return out
    }

    /** 12 parity probes plus 2 seam-straddling mutation probes (quad crosses the wrap line). */
    private fun probeDocs(c: Case): List<Pair<Double, Double>> {
        val x0 = c.docRect[0]; val y0 = c.docRect[1]
        val w = c.docRect[2]; val h = c.docRect[3]
        val ax = x0 + 0.37 * w; val ay = y0 + 0.71 * h
        val step = c.texelPx
        return listOf(
            ax to ay, // 0 interior
            x0 to (y0 + 0.371 * h), // 1 seam-x edge
            (x0 + 0.417 * w) to y0, // 2 seam-y edge
            x0 to y0, // 3 corner
            (ax + w) to ay, // 4 translated +w
            (ax - w) to ay, // 5 translated -w
            ax to (ay + h), // 6 translated +h
            ax to (ay - h), // 7 translated -h
            (x0 + w) to (y0 + h), // 8 corner +w+h
            (x0 + w) to (y0 + 0.371 * h), // 9 seam-x translated +w
            (x0 + 0.7 * w) to (y0 + 0.2 * h), // 10 second interior
            (ax + w) to (ay - h), // 11 translated +w-h
            // Readback is fragment (0,0): its derivative quad spans offsets 0 and 1,
            // so the seam must lie between those two samples, not elsewhere in the viewport.
            (x0 - 0.5 * step) to (y0 + 0.371 * h), // 12 straddle seam-x
            (x0 + 0.417 * w) to (y0 - 0.5 * step), // 13 straddle seam-y
        )
    }

    private val kinds = listOf(
        "interior", "seam-x", "seam-y", "corner",
        "translated+w", "translated-w", "translated+h", "translated-h",
        "corner+w+h", "seam-x+w", "interior2", "translated+w-h",
        "straddle-x", "straddle-y",
    )
    private val pairs = listOf(0 to 4, 0 to 5, 0 to 6, 0 to 7, 0 to 11, 3 to 8, 1 to 9)

    /** Max over channels of |diff| in units of the coarse tolerance: support-proof comparison. */
    private fun normed(a: FloatArray, b: FloatArray, slopeRange: Float): Double {
        val ts = 4.0 * slopeRange / 127.0
        val th = 4.0 / 255.0
        return maxOf(
            abs(a[0] - b[0]) / ts, abs(a[1] - b[1]) / ts,
            abs(a[2] - b[2]) / th, abs(a[3] - b[3]) / th,
        )
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Double {
        var m = 0.0
        for (q in 0..3) m = maxOf(m, abs(a[q] - b[q]).toDouble())
        return m
    }

    @Test fun writesTilePaperGpuFixture() {
        val tex = syntheticTexture()
        // The texture itself must be non-flat in every channel, or the oracle is blind.
        for (c in 0..3) {
            var lo = 255; var hi = 0
            for (i in c until tex.rgba.size step 4) {
                val v = tex.rgba[i].toInt() and 255
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            assertTrue(hi - lo >= 100, "texture channel $c is too flat: range ${hi - lo}")
        }

        val sb = StringBuilder()
        sb.append("""{"format":"tile-paper-gpu-fixture/1","textureSize":$texSize,"textureRgba":""")
        sb.append(tex.rgba.joinToString(",", "[", "]") { (it.toInt() and 255).toString() })
        sb.append(""","cases":[""")
        var firstCase = true
        var probeTotal = 0
        for (c in cases()) {
            val texelRect = DoubleArray(4) { k -> c.docRect[k] / c.texelPx }
            val docs = probeDocs(c)
            val texels = docs.map { (dx, dy) -> (dx / c.texelPx) to (dy / c.texelPx) }
            val expected = texels.map { (tx, ty) ->
                FloatArray(4).also {
                    TilePaperSampler.sampleSurface(
                        tex, tx, ty, hexTexels, c.rotatable, c.slopeRange, it,
                        TilePaperSampler.Rect(texelRect[0], texelRect[1], texelRect[2], texelRect[3]),
                        c.seed, c.footprint, c.heightMean,
                    )
                }
            }
            val canonical = texels.map { (tx, ty) ->
                FloatArray(4).also {
                    HexTile.sampleSurface(
                        tex, tx, ty, hexTexels, c.rotatable, c.slopeRange, it,
                        c.seed, c.footprint, c.heightMean,
                    )
                }
            }
            val coarse16 = texels.map { (tx, ty) ->
                FloatArray(4).also {
                    TilePaperSampler.sampleSurface(
                        tex, tx, ty, hexTexels, c.rotatable, c.slopeRange, it,
                        TilePaperSampler.Rect(texelRect[0], texelRect[1], texelRect[2], texelRect[3]),
                        c.seed, c.footprint * 16.0, c.heightMean,
                    )
                }
            }
            // Actual production-sampler evidence for fixture sensitivity, before any assertion.
            println("tile-mip-case pitch=${c.texelPx} rot=${c.rotatable} seed=${c.seed} fp=${c.footprint}")
            for (i in expected.indices) {
                println("tile-mip-probe $i expected=${expected[i].joinToString()} coarse16=${coarse16[i].joinToString()} normed=${normed(expected[i], coarse16[i], c.slopeRange)}")
            }
            println("tile-mip-ranges " + (0..3).joinToString { q ->
                (expected.maxOf { it[q].toDouble() } - expected.minOf { it[q].toDouble() }).toString()
            })
            // Per-probe validity: finite, height in range, moment consistent with the blend maths.
            for (e in expected) {
                assertTrue(e.all { it.isFinite() })
                assertTrue(e[2] in 0f..1f)
                assertTrue(e[3] + 1e-6f >= e[2] * e[2])
            }
            // Exact periodicity at every translation pair: a broken wrap cannot hide here.
            for ((i, j) in pairs) {
                assertEquals(0.0, maxAbs(expected[i], expected[j]), 2e-6, "period pair $i,$j")
            }
            // Non-constant signal in slope, height and moment: a zero sampler fails this test.
            fun range(q: Int) = expected.maxOf { it[q].toDouble() } - expected.minOf { it[q].toDouble() }
            assertTrue(range(0) > 0.02 || range(1) > 0.02, "slopes are constant")
            assertTrue(range(2) > 0.02, "height is constant")
            assertTrue(range(3) > 0.005, "moment is constant")
            // Canonical non-periodicity: the plain read must differ from the tile read somewhere,
            // or a wrap-ignoring sampler would pass the fixture.
            assertTrue(
                expected.indices.any { normed(expected[it], canonical[it], c.slopeRange) > 2.0 },
                "canonical read matches the tile read everywhere",
            )
            // Mip support: the x16 footprint must move the answer somewhere, or the
            // wrapped-gradient mutation would be undetectable.
            // Coarse reads can already saturate the source mips. Their parity remains required;
            // mutation sensitivity is established by ordinary-footprint cases instead.
            if (c.footprint == 1.0) assertTrue(
                expected.indices.any { normed(expected[it], coarse16[it], c.slopeRange) > 2.0 },
                "ordinary footprint must discriminate x16 mip mutation",
            )
            val supportsDisabled = expected.indices.map { normed(expected[it], canonical[it], c.slopeRange) > 2.0 }
            val supportsMip = expected.indices.map { c.footprint == 1.0 && normed(expected[it], coarse16[it], c.slopeRange) > 2.0 }
            assertTrue(supportsDisabled.any { it }, "no probe supports the disabled mutation")
            if (c.footprint == 1.0) assertTrue(supportsMip.any { it }, "no ordinary probe supports the mip mutation")

            if (!firstCase) sb.append(",")
            firstCase = false
            sb.append(
                """{"texelPx":${c.texelPx},"hexTexels":$hexTexels,"rotatable":${c.rotatable},""" +
                    """"slopeRange":${c.slopeRange},"seed":${c.seed},"footprint":${c.footprint},""" +
                    """"heightMean":${c.heightMean},""",
            )
            sb.append(""""docRect":${c.docRect.joinToString(",", "[", "]")},""")
            sb.append(""""texelRect":${texelRect.joinToString(",", "[", "]")},""")
            sb.append(""""pairs":${pairs.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }},""")
            sb.append(""""probes":[""")
            for (i in docs.indices) {
                if (i > 0) sb.append(",")
                sb.append(
                    """{"kind":"${kinds[i]}","doc":[${docs[i].first},${docs[i].second}],""" +
                        """"texel":[${texels[i].first},${texels[i].second}],""" +
                        """"expected":${expected[i].joinToString(",", "[", "]")},""" +
                        """"canonical":${canonical[i].joinToString(",", "[", "]")},""" +
                        """"supportsDisabled":${supportsDisabled[i]},"supportsMip":${supportsMip[i]}}""",
                )
            }
            sb.append("]}")
            probeTotal += docs.size
        }
        sb.append("]}")
        assertEquals(8 * 14, probeTotal)

        val core = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        File(core, "build/tile-paper-gpu-fixture.json").apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}
