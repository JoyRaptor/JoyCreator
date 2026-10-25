package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * Editable lines v1 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md): the vocabulary the canvas, the engine and the
 * Select host share. A plane is one layer's cel in ENGINE terms: celId null = the layer's own tiles (a layer with no
 * regions), otherwise the cel the engine keeps those pixels under (animation frames, a regional layer's shared cel).
 */
data class LinePlane(val layerId: String, val celId: String?) {
    fun address(recordId: String) = LineAddress(layerId, celId, recordId)
}

/**
 * An immutable copy of every plane's lines and the drawing's frozen brushes, made on the GL thread after each change
 * and read on the UI thread. [revision] grows with every change, so a holder can tell a stale copy.
 */
class LineSnapshot(
    val revision: Long,
    planes: Map<LinePlane, List<StrokeRecord>>,
    brushes: Map<String, BrushPreset>,
    /** Every plane that holds ITEMS (lines or pixel slabs): its pixels must stay what its items draw (v1.2). */
    itemPlanes: Set<LinePlane> = emptySet(),
    /** Grows only when the kept brushes change ([revision] grows with every line): the pen-down freeze cache's key. */
    val brushRevision: Long = 0L,
    /** Slab tiles held across the drawing (256 KB each): the slab budget's reading. */
    val slabTiles: Int = 0,
    brushAssets: Map<String, FrozenBrushPngs> = emptyMap(),
) {
    val planes: Map<LinePlane, List<StrokeRecord>> = planes.mapValues { it.value.toList() }
    val brushes: Map<String, BrushPreset> = brushes.toMap()
    val brushAssets: Map<String, FrozenBrushPngs> = brushAssets.toMap()
    val itemPlanes: Set<LinePlane> = (itemPlanes + planes.filterValues { it.isNotEmpty() }.keys).toSet()

    /** The plane's lines in drawing order (seq), empty when it has none. */
    fun lines(plane: LinePlane): List<StrokeRecord> = planes[plane].orEmpty()
    fun brush(id: String): BrushPreset? = brushes[id]
    fun hasLines(plane: LinePlane): Boolean = planes[plane]?.isNotEmpty() == true

    /** Does the plane hold lines or pixel slabs? A stroke there that is not a line becomes a slab. */
    fun holdsItems(plane: LinePlane): Boolean = plane in itemPlanes
    fun holdsItemsOnLayer(layerId: String): Boolean = itemPlanes.any { it.layerId == layerId }

    companion object { val EMPTY = LineSnapshot(0L, emptyMap(), emptyMap()) }
}

/** One Select edit; each is ONE undo step (owner: one press, one step). */
sealed class LineEdit {
    /** Every line this edit names. */
    fun addresses(): Collection<LineAddress> = when (this) {
        is Replace -> after.map { it.address }
        is Add -> records.map { it.address }
        is Delete -> ids
        is Bake -> ids
    }

    /** The layers this edit touches (v1.2 H9: a locked or hidden one refuses it). */
    fun layerIds(): Set<String> = addresses().mapTo(LinkedHashSet()) { it.layerId }

    /**
     * Same addresses and seqs (recolour, re-brush, re-weight, reshape, transform). [brushes] = frozen copies a
     * re-brush introduces (FrozenBrushRegistry.freeze against [LineSnapshot.brushes]); records name them by id.
     */
    data class Replace(val after: List<AddressedStroke>, val brushes: Map<String, BrushPreset> = emptyMap(),
                       /** Unibrush U7: the tip pictures of [brushes] that draw from pictures, by brush id. */
                       val assets: Map<String, cc.joycreator.joybrush.core.brush.FrozenBrushPngs> = emptyMap()) : LineEdit()
    /** New lines on top, at vacant addresses (duplicate, paste); the engine assigns their seqs. */
    data class Add(val records: List<AddressedStroke>, val brushes: Map<String, BrushPreset> = emptyMap(),
                   val assets: Map<String, cc.joycreator.joybrush.core.brush.FrozenBrushPngs> = emptyMap()) : LineEdit()
    data class Delete(val ids: Set<LineAddress>) : LineEdit()
    /** Rasterize Down (v1.2 H3): each line becomes a pixel slab at its own seq and its record goes; the pixels stay. */
    data class Bake(val ids: Set<LineAddress>) : LineEdit()
}

/** What an edit did, on the UI thread. [message] says why in words when [ok] is false. */
data class LineEditResult(val ok: Boolean, val message: String? = null)
