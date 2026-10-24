package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.select.SelectionLayerActions
import cc.joycreator.joybrush.core.stroke.LineAddress

/** The Activity only renders these applicable D21 rows; all scoping lives beside Select. */
object SelectLayerMenu {
    data class Model(val selectAll: Set<LineAddress>, val bake: Set<LineAddress>, val flattenLower: Boolean)

    fun model(layerId: String, stack: LayerStack, snapshot: LineSnapshot, selected: Set<LineAddress>,
              visible: List<SelectionHost.Line>, pickLocked: Boolean, doc: JbDocument?): Model {
        val layer = stack[layerId] ?: return Model(emptySet(), emptySet(), false)
        val editable = layer.visible && !layer.locked
        val all = snapshot.planes.flatMap { (plane, records) -> records.map { plane.address(it.id) } }.toSet()
        val selectable = if (editable) visible.filter {
            it.layerId == layerId && (pickLocked || !it.locked) && it.record.capturedPhysicalSource == null
        }.mapTo(LinkedHashSet()) { it.address } else emptySet()
        val bake = if (editable) SelectionLayerActions.bakeScope(layerId, selected, all) else emptySet()
        val frames = if (doc == null) emptySet() else try {
            RegionDocumentOps.paintPlans(doc, emptyMap()).filterValues { it.frames.isNotEmpty() }.keys
        } catch (_: Exception) { return Model(selectable, bake, false) }
        val lower = SelectionLayerActions.flattenLower(stack, layerId) { it in frames }
        return Model(selectable, bake, lower is SelectionLayerActions.Lower.Plan)
    }
}
