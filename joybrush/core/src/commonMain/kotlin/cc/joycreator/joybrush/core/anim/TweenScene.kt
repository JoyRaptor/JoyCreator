package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** One layer's transient vector view for a sampled frame exposure. Raster pixels remain in [sourceCelId]. */
data class TweenLayerScene(
    val layerId: String,
    val sourceCelId: String,
    val targetCelId: String,
    val records: List<StrokeRecord>,
)

/** Pure line-only scene for one sampled exposure; it neither changes nor writes document content. */
data class TweenScene(
    val frameId: String,
    val targetFrameId: String?,
    val fraction: Float,
    val layers: List<TweenLayerScene>,
) {
    companion object {
        /**
         * Build the editable-line view for [sample]. Each layer resolves its own region cels and is tweened
         * independently, so record IDs never match across layers. A null record list is an error only when that
         * cel declares a `strokesFile`; cels without a stream contribute no lines.
         *
         * The source frame remains the pixel/raster source. At t=0, or when the two frame addresses resolve to the
         * same physical cel, source records are returned unchanged and target records are not loaded.
         */
        fun build(
            doc: JbDocument,
            boardId: String,
            sample: TweenFrameSample,
            strokeSource: (layerId: String, celId: String) -> List<StrokeRecord>?,
        ): TweenScene {
            val board = doc.boards.firstOrNull { it.id == boardId }
                ?: throw DocException("No such board \"$boardId\"")
            if (board.kind != BoardKind.ANIMATION) {
                throw DocException("Board \"$boardId\" is not an animation board")
            }
            if (board.frames.map { it.id }.toSet().size != board.frames.size) {
                throw DocException("Board \"$boardId\" has duplicate frame IDs")
            }
            if (board.frames.isEmpty()) {
                throw DocException("Tween sample does not name a frame on board \"$boardId\"")
            }
            val rangeLast = sample.rangeLastFrameIndex ?: board.frames.lastIndex
            if (sample.rangeFirstFrameIndex !in board.frames.indices ||
                rangeLast !in board.frames.indices || rangeLast < sample.rangeFirstFrameIndex ||
                sample.frameIndex !in sample.rangeFirstFrameIndex..rangeLast) {
                throw DocException("Tween sample playback range is invalid on board \"$boardId\"")
            }
            val sourceFrame = board.frames[sample.frameIndex]
            if (sourceFrame.id != sample.frameId) {
                throw DocException("Tween sample frame does not match its index on board \"$boardId\"")
            }
            if (!sample.fraction.isFinite() || sample.fraction < 0.0 || sample.fraction >= 1.0) {
                throw DocException("Tween sample fraction must be in 0 until 1")
            }
            if (sample.tickOffset < 0L || sample.tickOffset >= sourceFrame.holdFrames.toLong()) {
                throw DocException("Tween sample tick is outside frame \"${sourceFrame.id}\"'s hold")
            }
            val expectedFraction = if (sample.nextFrameId == null) 0.0
                else sample.tickOffset.toDouble() / sourceFrame.holdFrames
            if (sample.fraction != expectedFraction) {
                throw DocException("Tween sample fraction does not match its frame tick")
            }

            val targetFrameId = sample.nextFrameId
            val targetFrame = if (targetFrameId == null) {
                if (sample.nextFrameIndex != null) throw DocException("Tween sample has an index without a target frame")
                val shouldHaveTarget = sourceFrame.tween && sourceFrame.holdFrames > 1 &&
                    (sample.frameIndex < rangeLast || sample.loops)
                if (shouldHaveTarget) {
                    throw DocException("Tween sample is missing the next frame on board \"$boardId\"")
                }
                null
            } else {
                val targetIndex = sample.nextFrameIndex
                    ?: throw DocException("Tween sample target frame has no index")
                val expectedIndex = if (sample.frameIndex < rangeLast) sample.frameIndex + 1
                    else if (sample.loops) sample.rangeFirstFrameIndex
                    else throw DocException("Tween sample has a target beyond its playback range on board \"$boardId\"")
                if (targetIndex != expectedIndex || board.frames.getOrNull(targetIndex)?.id != targetFrameId) {
                    throw DocException("Tween sample target is not the next frame on board \"$boardId\"")
                }
                if (!sourceFrame.tween || sourceFrame.holdFrames <= 1) {
                    throw DocException("Frame \"${sourceFrame.id}\" is not set to tween")
                }
                board.frames[targetIndex]
            }

            val fraction = sample.fraction.toFloat()
            val sceneLayers = ArrayList<TweenLayerScene>()
            for (layer in doc.layers) {
                val regions = layer.regions.filter { it.boardId == boardId }
                if (regions.size > 1) throw DocException("Layer \"${layer.id}\" has more than one region on board \"$boardId\"")
                val region = regions.singleOrNull() ?: continue
                if (region.held) continue
                val sourceCelId = region.frameCel[sourceFrame.id]
                    ?: throw DocException("Layer \"${layer.id}\" has no cel for frame \"${sourceFrame.id}\"")
                val actualTargetFrameId = targetFrame?.id ?: sourceFrame.id
                val targetCelId = region.frameCel[actualTargetFrameId]
                    ?: throw DocException("Layer \"${layer.id}\" has no cel for frame \"$actualTargetFrameId\"")
                val sourceCel = layer.cels.firstOrNull { it.id == sourceCelId }
                    ?: throw DocException("Layer \"${layer.id}\" has no cel \"$sourceCelId\"")
                val targetCel = if (targetCelId == sourceCelId) sourceCel else layer.cels.firstOrNull { it.id == targetCelId }
                    ?: throw DocException("Layer \"${layer.id}\" has no cel \"$targetCelId\"")

                val from = readDeclaredLines(layer.id, sourceCel.id, sourceCel.strokesFile, strokeSource)
                val records = if (fraction == 0f || sourceCelId == targetCelId) from
                else {
                    val to = readDeclaredLines(layer.id, targetCel.id, targetCel.strokesFile, strokeSource)
                    Tween.between(from, to, fraction)
                }
                sceneLayers += TweenLayerScene(layer.id, sourceCelId, targetCelId, records)
            }
            return TweenScene(sourceFrame.id, targetFrameId, fraction, sceneLayers)
        }

        private fun readDeclaredLines(
            layerId: String,
            celId: String,
            strokesFile: String?,
            source: (String, String) -> List<StrokeRecord>?,
        ): List<StrokeRecord> {
            if (strokesFile == null) return emptyList()
            return source(layerId, celId)
                ?: throw DocException("Layer \"$layerId\" cel \"$celId\" declares \"$strokesFile\" but its line records are missing")
        }
    }
}
