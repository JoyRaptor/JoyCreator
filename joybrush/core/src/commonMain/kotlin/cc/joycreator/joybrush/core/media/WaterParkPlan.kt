package cc.joycreator.joybrush.core.media

/**
 * L2 of the atlas room (tasks/joybrush/specs/ATLAS_ROOM_20261023.md): what a tile with running water keeps, exactly, while
 * it is out of the working atlas. Only the state that carries from one water step to the next, at its own precision: the
 * finished paint (p0, p1, paper), the current water (w0, w1) and its flow (flux), the running water's outflow (run). Not
 * the bakes (made again from the paper as the tile loads), the look and the ground (layer stores), nor any per-step scratch
 * (bead field, input buffers: a tile with brush input not yet in the water is never parked).
 */
object WaterParkPlan {
    /** One parked plane: bytes per texel, and whether it lives at the water's resolution (coarse water: 2 x 2 px a cell). */
    enum class Plane(val bytesPerTexel: Int, val water: Boolean) {
        P0(16, false), P1(16, false), PAPER(16, false),
        W0(16, true), W1(16, true), FLUX(8, true), RUN(4, true),
    }

    /** What one parked tile of [side]² layer px costs, its water planes at cells [wf] px wide. */
    fun bytesPerTile(side: Int = 256, wf: Int = 1): Long = Plane.entries.sumOf { p ->
        val s = if (p.water) side / wf else side
        p.bytesPerTexel.toLong() * s * s
    }

    /** Parked tiles that fit in [budgetBytes], whole units only. */
    fun units(budgetBytes: Long, side: Int = 256, wf: Int = 1): Int = if (budgetBytes <= 0) 0 else (budgetBytes / bytesPerTile(side, wf)).toInt()
}
