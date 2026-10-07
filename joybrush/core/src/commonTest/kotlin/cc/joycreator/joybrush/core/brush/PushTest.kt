package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JB-1.06 — the pixel push geometry, and the rule that says which layers a pixel-reading engine may touch. */
class PushTest {

    private fun dab(angle: Float, radius: Float) = Dab(x = 0f, y = 0f, radius = radius, angle = angle)

    private val out = FloatArray(3)

    @Test
    fun aPushMovesAlongTheStrokeByTheAmountTimesTheRadius() {
        // amount 0.2 x radius 10 = 2 px, along angle 0 -> (2, 0); flow = the amount, 0.2.
        Push.offsetFor(dab(0f, 10f), 0.2f, out)
        assertEquals(2f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f); assertEquals(0.2f, out[2], 1e-6f)
        // angle pi/2 turns the same 2 px into (0, 2).
        Push.offsetFor(dab((PI / 2).toFloat(), 10f), 0.2f, out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(2f, out[1], 1e-5f)
    }

    @Test
    fun nothingMovesForAnAmountOfZeroOrANonNumber() {
        for (amount in listOf(0f, -1f, Float.NaN)) {
            Push.offsetFor(dab(0.3f, 10f), amount, out)
            assertEquals(listOf(0f, 0f, 0f), out.toList(), "amount $amount")
        }
        Push.offsetFor(dab(Float.NaN, 10f), 0.2f, out)
        assertEquals(listOf(0f, 0f, 0f), out.toList(), "a NaN angle must not reach the GPU")
    }

    @Test
    fun aHugeAmountIsCappedAtHalfTheRadiusBecauseAnyMoreTearsTheImage() {
        // min(10, MAX_SHIFT 0.5) x radius 10 = 5 px exactly; flow is capped at 1.
        Push.offsetFor(dab(0f, 10f), 10f, out)
        assertEquals(5f, out[0], 1e-5f)
        assertEquals(1f, out[2])
        assertEquals(0.5f, Push.MAX_SHIFT)
    }

    @Test
    fun aPushNeverMovesAnythingAcrossTheStroke() {
        var angle = -PI.toFloat()
        while (angle < PI) {
            Push.offsetFor(dab(angle, 7f), 0.4f, out)
            // The across-stroke direction is (-sin a, cos a); the dot product with the offset is 0.
            val across = out[0] * -sin(angle) + out[1] * cos(angle)
            assertTrue(abs(across) < 1e-5f, "angle $angle: $across")
            angle += 0.37f
        }
    }

    @Test
    fun oneArrayIsReusedForEveryDabSoAHotLoopDoesNotAllocate() {
        val mine = FloatArray(3)
        val keep = mine
        repeat(1000) { Push.offsetFor(dab(it * 0.01f, 8f), 0.3f, mine) }
        assertSame(keep, mine)
        assertTrue(mine[2] == 0.3f)
    }

    // ---- BrushRules (R20, given a home) -----------------------------------------------------------------

    @Test
    fun pixelReadingEnginesAreRefusedOnInkInWordsAndAllowedOnPaint() {
        assertEquals("Smudge reads the paint under it, and an ink layer has none.", BrushRules.refusalFor(ENGINE_SMUDGE, LayerKind.INK))
        assertEquals("Push reads the paint under it, and an ink layer has none.", BrushRules.refusalFor(ENGINE_PUSH, LayerKind.INK))
        assertEquals("Wet paint reads the paint under it, and an ink layer has none.", BrushRules.refusalFor("wet", LayerKind.INK))
        for (engine in listOf(ENGINE_SMUDGE, ENGINE_PUSH, "wet")) assertNull(BrushRules.refusalFor(engine, LayerKind.PAINT))
        // Stamp and fill write and read nothing, so they work on paint and ink. A media layer is the exception (the Lead,
        // step-4 check 1): its look is rendered from its state, so only media brushes and the eraser paint there.
        for (engine in listOf("stamp", ENGINE_FILL)) for (kind in LayerKind.entries.filter { it != LayerKind.MEDIA }) {
            assertNull(BrushRules.refusalFor(engine, kind), "$engine on $kind")
        }
    }
}
