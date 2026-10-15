package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure admission tests: this API must reject unsafe source footprints before GL allocation. */
class CapturedDiagnosticAdmissionTest {
    private fun engine() = GlPaintEngine()

    @Test fun sparseAndAnchoredDabsMatchExistingTileAllocator() {
        val dabs = listOf(
            Dab(x = 12.5f, y = 19.25f, radius = 3f, anchor = 0.6f),
            Dab(x = -4.5f, y = 11f, radius = 2f, anchor = 0.25f),
            Dab(x = 520f, y = -260f, radius = 4f, anchor = 1f),
        )

        assertEquals(Tiles.bucket(dabs).keys, engine().capturedDiagnosticSourceKeys(dabs))
    }

    @Test fun largeFiniteRadiusIsRejectedBeforeTileEnumeration() {
        val dabs = listOf(Dab(x = 10f, y = 10f, radius = 10_000f))

        assertFailsWith<IllegalArgumentException> { engine().capturedDiagnosticSourceKeys(dabs) }
    }

    @Test fun nonfiniteAndOutOfRangeCoordinatesAreRejected() {
        val engine = engine()
        assertFailsWith<IllegalArgumentException> {
            engine.capturedDiagnosticSourceKeys(listOf(Dab(x = Float.NaN, y = 1f, radius = 1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.capturedDiagnosticSourceKeys(listOf(Dab(x = Float.POSITIVE_INFINITY, y = 1f, radius = 1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.capturedDiagnosticSourceKeys(listOf(Dab(x = Float.MAX_VALUE, y = 1f, radius = 1f)))
        }
    }

    @Test fun cumulativeFootprintBudgetRejectsTheSixtyFifthTile() {
        val dabs = (0 until 65).map { i ->
            Dab(x = i * Tiles.SIZE.toFloat() + 16f, y = 16f, radius = 1f)
        }

        assertFailsWith<IllegalArgumentException> { engine().capturedDiagnosticSourceKeys(dabs) }
    }

    @Test fun sourceCountBudgetChecksSizeBeforeReadingOrIterating() {
        val oversized = object : AbstractList<Dab>() {
            override val size: Int get() = 16_385
            override fun get(index: Int): Dab = error("Oversized source list was indexed")
            override fun iterator(): Iterator<Dab> = error("Oversized source list was iterated")
        }

        assertFailsWith<IllegalArgumentException> { engine().capturedDiagnosticSourceKeys(oversized) }
    }
}
