package cc.joycreator.joybrush.androidkit.gl.media

/**
 * Where a [MediaLayerEngine] lives on ONE layer and how its state travels to and from that layer's stores: what
 * [MediaCanvas] drives. Two hosts: [MediaWindow] (today: a 1024² window that moves under the pen) and [MediaAtlas]
 * (Media Atlas v2: tiles placed freely in slots, loaded where the passes need them). GL thread only.
 */
interface MediaPlace {
    val layerId: String?
    val plane: String?
    /** The tiles this host holds now (the eraser writes only the stores those have). */
    fun heldKeys(): List<Long>
    var stats: cc.joycreator.joybrush.androidkit.bench.FrameStats?
    var legacyLook: Boolean
    var lookHalo: Int
    fun touched(nowMs: Long)
    fun releaseIfIdle(nowMs: Long): Boolean
    fun release()
    fun place(layer: String, plane: String?, docX: Double, docY: Double, resumeWater: Boolean = true)
    fun follow(docRect: DoubleArray, penX: Double, penY: Double)
    /**
     * Media Atlas v2 B3: everything the next frame's passes will ask for, made resident and held at once before any of them
     * draws (writing back older tiles to make room), or refused in words before anything is drawn. The window has nothing
     * to do here: it moves in [follow].
     */
    fun admit(needs: List<MediaLayerEngine.Need>) {}
    fun flush(stores: Boolean = true)
    fun restored(layer: String, plane: String?, keys: Set<Long>): Boolean
    fun lookWritten(layer: String, plane: String?, keys: Set<Long>)
    fun wakeWater()
}
