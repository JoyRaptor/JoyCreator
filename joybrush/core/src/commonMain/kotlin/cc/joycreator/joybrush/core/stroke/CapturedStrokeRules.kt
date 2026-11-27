package cc.joycreator.joybrush.core.stroke

/**
 * Numeric/source-kind admission only. An ordinary record retains its existing reader policy.
 * Passing these rules does not certify a brush, footprint, replay engine, document owner or edit.
 * Sensor NaNs mean missing readings; their raw payload bits are valid and must survive the codec.
 */
object CapturedStrokeRules {
    fun problems(record: StrokeRecord): List<String> {
        if (record.capturedPhysicalSource == null) return emptyList()
        val out = ArrayList<String>()
        if (record.id.isBlank()) out += "captured stroke id is blank"
        if (record.brushId.isBlank()) out += "captured stroke brush id is blank"
        if (record.groupId?.isBlank() == true) out += "captured stroke group id is blank"
        if (!record.smoothing.isFinite() || record.smoothing !in 0f..1f) out += "captured stroke smoothing is invalid"
        if (!record.screenPerDoc.isFinite() || record.screenPerDoc <= 0f) out += "captured stroke screenPerDoc is invalid"
        if (!record.widthScale.isFinite() || record.widthScale !in StrokeEdit.MIN_WIDTH_SCALE..StrokeEdit.MAX_WIDTH_SCALE) {
            out += "captured stroke widthScale is invalid"
        }
        if (record.seq < 0) out += "captured stroke seq is negative"
        for ((index, sample) in record.samples.withIndex()) {
            if (!sample.isPlaceable || sample.predicted) out += "captured stroke sample $index is not a recorded placeable sample"
            if (!sample.pressure.isFinite() || sample.pressure !in 0f..1f) out += "captured stroke sample $index pressure is invalid"
            if ((!sample.tilt.isFinite() && !sample.tilt.isNaN()) ||
                (!sample.azimuth.isFinite() && !sample.azimuth.isNaN()) ||
                (!sample.barrel.isFinite() && !sample.barrel.isNaN())) {
                out += "captured stroke sample $index sensor channel is infinite"
            }
        }
        return out
    }
}
