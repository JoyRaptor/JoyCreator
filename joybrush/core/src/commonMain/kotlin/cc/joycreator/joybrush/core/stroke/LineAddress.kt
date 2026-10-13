package cc.joycreator.joybrush.core.stroke

/** A line's engine/history address. Shared planes use null; frame planes use their cel ID. */
data class LineAddress(val layerId: String, val celId: String?, val recordId: String) {
    init {
        require(layerId.isNotBlank()) { "A line address needs a layer" }
        require(celId == null || celId.isNotBlank()) { "A frame line address needs a cel" }
        require(recordId.isNotBlank()) { "A line address needs a record" }
    }
}

/** Keep ownership paired with each record throughout an edit; list order is independent of saved seq. */
data class AddressedStroke(val address: LineAddress, val record: StrokeRecord) {
    init {
        require(address.recordId == record.id) { "A line edit's address must match its record" }
    }
}
