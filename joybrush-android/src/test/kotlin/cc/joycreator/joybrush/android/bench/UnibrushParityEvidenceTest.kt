package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.paint.Tiles
import org.junit.Assert.*
import org.junit.Test

/** Negative controls for actual evidence arithmetic, not a fake canvas or a device-run receipt. */
class UnibrushParityEvidenceTest {
    private val bytes = Tiles.SIZE * Tiles.SIZE * 4
    private val key = Tiles.key(-1, 2)
    private fun tile(red: Int = 30) = ByteArray(bytes).also { it[0] = red.toByte(); it[3] = 255.toByte() }
    private fun frame(step: Int, colour: Int = 30) = UnibrushParityEvidence.Frame(mapOf(key to tile(colour)), step, 1)
    private val before get() = UnibrushParityEvidence.Frame(emptyMap(), 0, 0)
    private fun evaluate(a: UnibrushParityEvidence.Frame = frame(1),
                         m: UnibrushParityEvidence.Frame = frame(2, 90),
                         b: UnibrushParityEvidence.Frame = frame(3),
                         baseline: UnibrushParityEvidence.Frame = before) =
        UnibrushParityEvidence.evaluate(baseline, a, m, b)

    @Test fun actualStrokeThenChangedRecolourThenExactReplayIsOnlyASubcaseMatch() {
        val result = evaluate()
        assertTrue(result.reason, result.matchingRecolourReplay)
        assertTrue(result.reason.contains("other U8 gates remain separate"))
    }

    @Test fun emptyAndAllZeroReadbacksCannotPassEvenWithLineMetadataAndCorrectHistory() {
        for (plane in listOf(emptyMap<Long, ByteArray>(), mapOf(key to ByteArray(bytes)))) {
            assertFalse(evaluate(a = frame(1).copy(visible = plane),
                m = frame(2).copy(visible = plane), b = frame(3).copy(visible = plane)).matchingRecolourReplay)
        }
    }

    @Test fun missingPlaneAndTruncatedTilesAreRefusedRatherThanTreatedAsBlank() {
        assertFalse(evaluate(a = frame(1).copy(visible = null)).matchingRecolourReplay)
        assertFalse(evaluate(b = frame(3).copy(visible = mapOf(key to byteArrayOf(1, 2, 3)))).matchingRecolourReplay)
        assertFalse(evaluate(baseline = before.copy(visible = null)).matchingRecolourReplay)
    }

    @Test fun unchangedOpaqueBackgroundIsNotEvidenceOfAStroke() {
        assertFalse(evaluate(baseline = frame(0).copy(lineCount = 0)).matchingRecolourReplay)
    }

    @Test fun noOpRecolourOrClearingToBaselineCannotSupplyTheMiddleControl() {
        assertFalse(evaluate(m = frame(2)).matchingRecolourReplay)
        assertFalse(evaluate(m = frame(2).copy(visible = emptyMap())).matchingRecolourReplay)
        val baseline = frame(0, 90).copy(lineCount = 0)
        assertFalse(evaluate(baseline = baseline).matchingRecolourReplay)
    }

    @Test fun changingOneByteOrTheTileLocationBreaksExactReplay() {
        assertFalse(evaluate(b = frame(3, 31)).matchingRecolourReplay)
        assertFalse(evaluate(b = frame(3).copy(visible = mapOf(Tiles.key(0, 2) to tile()))).matchingRecolourReplay)
    }

    @Test fun eachStrokeAndEditNeedsItsOwnSingleHistoryStep() {
        assertFalse(evaluate(a = frame(0)).matchingRecolourReplay)
        assertFalse(evaluate(m = frame(3, 90)).matchingRecolourReplay)
        assertFalse(evaluate(b = frame(2)).matchingRecolourReplay)
        assertFalse(evaluate(b = frame(-1)).matchingRecolourReplay)
    }

    @Test fun pixelsOnlyOrAdditionalLinesInvalidateThisEditableLineSubcase() {
        assertFalse(evaluate(a = frame(1).copy(lineCount = 0)).matchingRecolourReplay)
        assertFalse(evaluate(m = frame(2, 90).copy(lineCount = 2)).matchingRecolourReplay)
    }

    @Test fun excessTileMetadataIsRefusedBeforePixelComparison() {
        val shared = tile()
        val tooMany = (0..64).associate { Tiles.key(it, 0) to shared }
        assertFalse(evaluate(a = frame(1).copy(visible = tooMany)).matchingRecolourReplay)
    }

    @Test fun allocatedBlankTilesDoNotChangeThePictureOrMutateTheInput() {
        val drawn = frame(1)
        val saved = drawn.visible!!.getValue(key).copyOf()
        val replayed = frame(3).copy(visible = frame(3).visible!! + (Tiles.key(9, -9) to ByteArray(bytes)))
        assertTrue(evaluate(a = drawn, b = replayed).matchingRecolourReplay)
        assertArrayEquals(saved, checkNotNull(drawn.visible).getValue(key))
    }
}
