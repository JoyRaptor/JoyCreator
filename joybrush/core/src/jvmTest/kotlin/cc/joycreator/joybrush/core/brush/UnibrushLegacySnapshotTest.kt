package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/** Captured on the unmodified 107748a78 dabber, before D1. Any changed dab bit fails this test. */
class UnibrushLegacySnapshotTest {
    @Test fun oldBrushesKeepEveryDabBit() {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(v: Float) {
            val bits = v.toBits()
            for (shift in 0..24 step 8) digest.update((bits ushr shift).toByte())
        }
        for (version in 1..11) for (follow in listOf(false, true)) for (sensor in 0..3) {
            val brush = BrushPreset(version = version, id = "legacy", name = "Legacy", size = Param(28f,
                listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, .1f), listOf(1f, 1f))))),
                tip = TipSpec(followDirection = follow, angle = Param(13f, listOf(
                    InputCurve(BrushInput.direction, listOf(listOf(0f, -25f), listOf(1f, 25f))),
                    InputCurve(BrushInput.attack, listOf(listOf(0f, 0f), listOf(1f, 12f)))), "add"),
                    hardness = Param(.8f)), sizeJitter = .35f, angleJitter = 24f)
            val dabber = BrushDabber(brush, 314159L)
            for (i in 0..80) {
                val sample = PenSample(x = i * 2f, y = if (i < 40) i.toFloat() else 80f - i,
                    timeMs = i * 4.0, pressure = (i % 13) / 12f,
                    tilt = if (sensor == 0) Float.NaN else .7f,
                    azimuth = if (sensor == 0) Float.NaN else (PI - i * .09).toFloat(),
                    barrel = if (sensor == 2) (i * .17).toFloat() else if (sensor == 3) Float.POSITIVE_INFINITY else Float.NaN)
                val look = dabber.look(sample, i * 2.5f, i)
                listOf(look.radius, look.angle, look.flow, look.cap, look.aspect, look.anchor,
                    look.hardness, look.tipDepth, look.paperDepth, dabber.strokeHardness,
                    dabber.strokeOpacity).forEach(::add)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("2fa83c231a3938bbcb5695597204c6a980db2bf47056d81a557dd2f1bf08a8d3", actual)
    }
}
