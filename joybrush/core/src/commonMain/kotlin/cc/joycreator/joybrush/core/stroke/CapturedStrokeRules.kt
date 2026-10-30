package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.paint.UndoLog

/**
 * Numeric/source-kind admission only. An ordinary record retains its existing reader policy.
 * Passing these rules does not certify a brush, footprint, replay engine, document owner or edit.
 * Sensor NaNs mean missing readings; their raw payload bits are valid and must survive the codec.
 */
object CapturedStrokeRules {
    /** Old replay/export consumers must refuse the whole source, never omit just its lines. */
    fun requireOrdinary(records: List<StrokeRecord>) {
        require(records.none { it.capturedPhysicalSource != null }) {
            "Captured physical sources need a clipping and placement aware consumer"
        }
    }

    fun snapshot(record: StrokeRecord): StrokeRecord = record.copy(samples = FrozenList(record.samples))
    fun snapshots(records: List<StrokeRecord>): List<StrokeRecord> = FrozenList(records.map(::snapshot))

    /** NEW edits may move the appearance or change owner metadata; raw source recipes stay exact. */
    fun requireSupportedReplacement(before: StrokeRecord?, after: StrokeRecord?) {
        for (record in listOfNotNull(before, after)) {
            val errors = problems(record)
            require(errors.isEmpty()) { errors.joinToString("; ") }
        }
        if (before == null || after == null) return // Complete add/delete endpoints remain legal.
        val a = before.capturedPhysicalSource
        val b = after.capturedPhysicalSource
        if (a == null && b == null) return
        require(a != null && b != null && a.domain == b.domain && sameRecipe(before, after)) {
            "A captured source edit may change only integer placement, lock or group"
        }
    }

    /** Direction-aware callers use this for NEW only, never reinterpret admitted undo/redo. */
    fun <T : Any> requireNewStep(step: UndoLog.Step<T>) {
        val capturedAddresses = step.lines.filter { it.before?.record?.capturedPhysicalSource != null ||
            it.after?.record?.capturedPhysicalSource != null }.map { Triple(it.layerId, it.celId, it.id) }.toSet()
        val seen = HashSet<Triple<String, String?, String>>()
        step.lines.forEach { change ->
            val address = Triple(change.layerId, change.celId, change.id)
            if(address in capturedAddresses) {
                require(seen.add(address)) { "A new step cannot chain two changes to the same captured line" }
                require(change.id.isNotBlank() && change.layerId.isNotBlank() && (change.celId == null || change.celId.isNotBlank())) {
                    "Captured history needs its full physical address"
                }
                for(line in listOfNotNull(change.before, change.after)) require(line.record.id == change.id &&
                    line.seq > 0 && line.seq == line.record.seq) { "Captured history has mismatched identity or sequence" }
            }
            requireSupportedReplacement(change.before?.record, change.after?.record)
        }
    }

    fun sameRecord(a: StrokeRecord?, b: StrokeRecord?): Boolean {
        if (a == null || b == null) return a == b
        if (a.capturedPhysicalSource == null && b.capturedPhysicalSource == null) return a == b
        return a.capturedPhysicalSource == b.capturedPhysicalSource && a.locked == b.locked &&
            a.groupId == b.groupId && sameRecipe(a, b)
    }

    private fun sameRecipe(a: StrokeRecord, b: StrokeRecord): Boolean =
        a.id == b.id && a.seq == b.seq && a.brushId == b.brushId && a.seed == b.seed &&
            a.colorArgb == b.colorArgb && a.secondaryColorArgb == b.secondaryColorArgb && a.stabilizer == b.stabilizer &&
            a.smoothing.toRawBits() == b.smoothing.toRawBits() &&
            a.screenPerDoc.toRawBits() == b.screenPerDoc.toRawBits() && a.widthScale.toRawBits() == b.widthScale.toRawBits() &&
            a.washFrame == b.washFrame &&
            a.samples.size == b.samples.size && a.samples.indices.all { i ->
                val x = a.samples[i]; val y = b.samples[i]
                x.x.toRawBits() == y.x.toRawBits() && x.y.toRawBits() == y.y.toRawBits() &&
                    x.timeMs.toRawBits() == y.timeMs.toRawBits() && x.pressure.toRawBits() == y.pressure.toRawBits() &&
                    x.tilt.toRawBits() == y.tilt.toRawBits() && x.azimuth.toRawBits() == y.azimuth.toRawBits() &&
                    x.barrel.toRawBits() == y.barrel.toRawBits() && x.tool == y.tool && x.predicted == y.predicted
            }

    private class FrozenList<T>(values: List<T>) : AbstractList<T>() {
        private val items = values.toList()
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
    }

    fun problems(record: StrokeRecord): List<String> {
        if (record.capturedPhysicalSource == null) return emptyList()
        val out = ArrayList<String>()
        record.stabilizer?.let { out += it.problems().map { reason -> "captured stroke: $reason" } }
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
