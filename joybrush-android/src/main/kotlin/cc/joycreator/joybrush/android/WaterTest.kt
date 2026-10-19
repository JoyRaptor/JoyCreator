package cc.joycreator.joybrush.android

import cc.joycreator.joybrush.core.media.WaterAB

/**
 * The owner's watercolour comparison (October 18): the ways the water can trade work for speed, side by side on the
 * phone. "today" is the shipped water; the ⋯ menu's "Water test" row steps through the rest, the bench takes `jb_water`.
 */
internal object WaterTest {
    class Choice(val id: String, val label: String, val about: String, val ab: WaterAB)

    val CHOICES = listOf(
        Choice("today", "Today", "Today's water: every water step runs while you paint.", WaterAB()),
        Choice("a", "A · brush first", "The brush stays instant: the water takes one step a frame while the pen is down " +
            "and catches up after you lift.", WaterAB(brushFirst = true)),
        Choice("s", "S · sleeps when still", "Water that has stood still for 10 seconds stops moving until your next " +
            "stroke, tilt or undo.", WaterAB(sleepAfterQuietSec = 10.0)),
        Choice("flat", "Flat look while wet", "While the water moves, its paint is drawn flat (cheaper); the full texture " +
            "comes back when it stops.", WaterAB(flatLookWhileWet = true)),
        Choice("flatnr", "Flat, no relief", "Flat while wet, and watercolour leaves no raised paint at all, so what you " +
            "see wet is what stays.", WaterAB(flatLookWhileWet = true, noRelief = true)),
        Choice("all", "A + S + flat", "All three speed-ups together: the fastest water.",
            WaterAB(brushFirst = true, sleepAfterQuietSec = 10.0, flatLookWhileWet = true)),
    )

    fun of(id: String?): Choice = CHOICES.firstOrNull { it.id == id } ?: CHOICES[0]

    fun next(id: String?): Choice = CHOICES[(CHOICES.indexOf(of(id)) + 1) % CHOICES.size]
}
