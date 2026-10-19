package cc.joycreator.joybrush.core.media

/**
 * The owner's watercolour A/B (October 18), one setting for every candidate; the default is today's behaviour exactly.
 * Each can be tried alone or together; the Lead wires the control (MediaCanvas.waterAB).
 *
 * [brushFirst] Option A ([WaterSchedule]): one water step a frame while the pen is down, the rest paid back after the lift.
 * [sleepAfterQuietSec] Option S ([WaterQuiet]): water that has stood still this long stops being simulated until the next
 *   stroke, tilt or undo; null = never (today: it runs until it dries or the 240 s cap).
 * [flatLookWhileWet] while water runs, its look is drawn without relief (impasto 0: a smaller, cheaper look pass); the full
 *   look is drawn over everywhere the water ran once it stops or sleeps.
 * [noRelief] a watercolour deposits no paint body at all ([WetConstants.noBody] = 1), so it never gets relief: with
 *   [flatLookWhileWet], what shows while wet is what stays.
 */
data class WaterAB(
    val brushFirst: Boolean = false,
    val sleepAfterQuietSec: Double? = null,
    val flatLookWhileWet: Boolean = false,
    val noRelief: Boolean = false,
) {
    /** The water's constants with this A/B applied. */
    fun apply(w: WetConstants): WetConstants = if (noRelief) w.copy(noBody = 1.0) else w
}
