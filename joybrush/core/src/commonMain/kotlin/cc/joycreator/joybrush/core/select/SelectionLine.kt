package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.vector.InkLine

/** Picker geometry paired with its owning plane. Geometry keeps the saved record ID. */
data class SelectionLine(val address: LineAddress, val geometry: InkLine) {
    init {
        require(address.recordId == geometry.id) { "Selection geometry must match its addressed record" }
    }
}
