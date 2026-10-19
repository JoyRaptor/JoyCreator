package cc.joycreator.joybrush.android

import cc.joycreator.joybrush.core.media.WaterAB
import org.junit.Assert.*
import org.junit.Test

/** The owner's watercolour comparison switch: today's water is the default, and the row steps through every choice. */
class WaterTestTest {
    @Test fun anUnknownOrMissingChoiceIsTodaysWater() {
        assertEquals(WaterAB(), WaterTest.of(null).ab)
        assertEquals(WaterAB(), WaterTest.of("gone").ab)
    }

    @Test fun tappingStepsThroughEveryChoiceAndBackToToday() {
        val seen = generateSequence(WaterTest.of(null)) { WaterTest.next(it.id) }.take(WaterTest.CHOICES.size + 1).map { it.id }.toList()
        assertEquals(WaterTest.CHOICES.map { it.id } + "today", seen)
    }

    @Test fun eachChoiceSetsWhatItSays() {
        assertTrue(WaterTest.of("a").ab.brushFirst)
        assertEquals(10.0, WaterTest.of("s").ab.sleepAfterQuietSec!!, 0.0)
        assertTrue(WaterTest.of("flatnr").ab.let { it.flatLookWhileWet && it.noRelief })
    }
}
