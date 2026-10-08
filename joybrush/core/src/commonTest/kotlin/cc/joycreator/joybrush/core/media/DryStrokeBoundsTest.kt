package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

class DryStrokeBoundsTest {
    @Test fun realStrokeDirtyBoundsEncloseRotatedShaderCorners() {
        var checked = 0
        var oldBoundsMissed = false
        for (stick in Sticks.ALL.values) for (pxPerMm in listOf(20.0, 80.0)) {
            for (angle in listOf(0.0, PI / 4, PI / 2, 3 * PI / 4, -PI / 4)) {
                for (tilt in listOf(0.0, PI / 4, PI / 2)) {
                    val stroke = DryStroke(stick, 0.12, pxPerMm)
                    stroke.add(MediaSample(-257.25, 511.5, 0.9, tilt, angle, 0.0))
                    stroke.add(MediaSample(-257.25, 511.5, 0.9, tilt, angle, 16.0))
                    val batch = stroke.take()
                    if (batch.count == 0) continue
                    val dirty = batch.dirty!!
                    val scale = pxPerMm.toFloat()
                    for (i in 0 until batch.count) {
                        val o = i * DabBatch.FLOATS
                        val d = batch.dabs
                        val oldR = (max(max(abs(d[o + 4]), abs(d[o + 5])), d[o + 6]) + 0.06f) * scale + 2f
                        val xs = listOf(d[o + 4] - 0.06f, d[o + 5] + 0.06f)
                        val ys = listOf(-d[o + 6] - 0.06f, d[o + 6] + 0.06f)
                        for (x in xs) for (y in ys) {
                            // Independent Float evaluation of jb_dry_dab.vert.
                            val dx = (x * d[o + 2] - y * d[o + 3]) * scale
                            val dy = (x * d[o + 3] + y * d[o + 2]) * scale
                            val gx = (d[o] + dx).toDouble()
                            val gy = (d[o + 1] + dy).toDouble()
                            assertTrue(gx >= dirty[0] && gx <= dirty[2], "corner x=$gx outside ${dirty.toList()}")
                            assertTrue(gy >= dirty[1] && gy <= dirty[3], "corner y=$gy outside ${dirty.toList()}")
                            oldBoundsMissed = oldBoundsMissed || abs(dx) > oldR || abs(dy) > oldR
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue(checked > 0)
        assertTrue(oldBoundsMissed, "fixture must expose the old unrotated radius under-enclosure")
    }
}
