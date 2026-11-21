package cc.joycreator.joybrush.androidkit.gl.media

/**
 * Codex audit round 4, OIL-STEP: the last page-table neighbourhood handed out, so consecutive paste steps around the same
 * tile under the same table copy it instead of looking up all 25 entries again. [generation] is the table's: whoever
 * changes what a lookup would answer (the residency, the walls) bumps it, and an entry from another generation never serves.
 */
internal class NeighbourMemo {
    var generation = 0L; private set
    private var valid = false
    private var tx = 0; private var ty = 0; private var r = 0; private var walls = false; private var gen = -1L
    private var held = IntArray(0)

    fun changed() { generation++; valid = false }

    /** Fills [out] from the memo and returns true when it holds exactly this question under this generation. */
    fun fill(tx: Int, ty: Int, r: Int, withWalls: Boolean, out: IntArray): Boolean {
        if (!valid || gen != generation || tx != this.tx || ty != this.ty || r != this.r || withWalls != walls) return false
        val n = (2 * r + 1) * (2 * r + 1) * 2
        if (held.size < n || out.size < n) return false
        System.arraycopy(held, 0, out, 0, n)
        return true
    }

    /** What a full lookup just put in [out] for this question. */
    fun remember(tx: Int, ty: Int, r: Int, withWalls: Boolean, out: IntArray) {
        val n = (2 * r + 1) * (2 * r + 1) * 2
        if (held.size < n) held = IntArray(n)
        System.arraycopy(out, 0, held, 0, n)
        this.tx = tx; this.ty = ty; this.r = r; walls = withWalls; gen = generation; valid = true
    }
}
