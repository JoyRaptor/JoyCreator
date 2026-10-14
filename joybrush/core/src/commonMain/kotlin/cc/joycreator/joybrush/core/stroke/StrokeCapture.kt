package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample

/**
 * One pen contact's recording, fed after guide placement and before brush response/smoothing.
 * Header values are frozen at contact, especially the actual live dabber seed and screen zoom.
 * This stores no predicted samples and makes no choices about destination cel or sequence.
 */
class StrokeCapture(
    private val id: String,
    private val brushId: String,
    private val seed: Long,
    private val smoothing: Float,
    private val screenPerDoc: Float,
    private val colorArgb: Int,
) {
    private val samples = ArrayList<PenSample>()
    private var ended = false

    init {
        require(id.isNotBlank() && brushId.isNotBlank()) { "A recording needs stroke and frozen brush identities" }
        require(smoothing.isFinite() && smoothing in 0f..1f) { "A recording needs valid live smoothing" }
        require(screenPerDoc.isFinite() && screenPerDoc > 0f) { "A recording needs the live contact zoom" }
    }

    fun add(placedRaw: PenSample) {
        check(!ended) { "This pen contact has ended" }
        require(!placedRaw.predicted) { "Predicted points are never recorded" }
        samples.add(placedRaw)
    }

    /** Empty contacts have no record. Assignment of seq and destination belongs to the engine transaction. */
    fun finish(): StrokeRecord? {
        check(!ended) { "This pen contact has ended" }
        ended = true
        val raw = samples.toList()
        samples.clear()
        return raw.takeIf { it.isNotEmpty() }?.let {
            StrokeRecord(id, brushId, seed, smoothing, screenPerDoc, it, colorArgb)
        }
    }

    fun cancel() { ended = true; samples.clear() }
}
