package cc.joycreator.joybrush.core.chrome

/** Per-board ephemeral K4 clock. Keep one instance per scene; no timer or paint sample is owned here.
 * Call with monotonic time on pen transitions and scheduled UI frames. Interrupted fades start
 * from the displayed opacity while retaining the locked 120 ms out / 300 ms back durations. */
class BoardChromePenFade {
    private var down = false
    private var since = 0L
    private var startAlpha = 1f
    private var lastTime: Long? = null

    fun apply(input: BoardChromeLayout.Input, nowMs: Long): BoardChromeLayout.Input {
        require(nowMs >= 0 && (lastTime == null || nowMs >= lastTime!!))
        lastTime = nowMs
        if (down != input.penDown) {
            startAlpha = BoardChromeLayout.drawingAlpha(down, nowMs-since, startAlpha)
            down = input.penDown
            since = nowMs
        }
        val elapsed = nowMs-since
        return input.copy(penFadeStartAlpha = startAlpha,
            penDownElapsedMs = if(down) elapsed else input.penDownElapsedMs,
            penLiftElapsedMs = if(down) input.penLiftElapsedMs else elapsed)
    }

    /** Pause/scene disposal stops animation. A resumed resting scene starts fully visible. */
    fun reset() { down=false; since=0; startAlpha=1f; lastTime=null }
}
