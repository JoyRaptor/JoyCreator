package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.CelParts
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** A transient editable-line scene whose ownership pieces are clipped by [rect] at render time. */
data class TweenComponentPiece(
    /** Physical source cel that owns this world-space piece. */
    val sourceCelId: String,
    /** Physical target cel that owns this world-space piece. */
    val targetCelId: String,
    /** World-space intersection of source and target ownership regions. */
    val rect: RectPx,
    /** Full records are retained; the renderer must clip geometry to [rect], not trim samples here. */
    val records: List<StrokeRecord>,
)

data class TweenComponentLayerScene(
    val layerId: String,
    /** Logical frame cel roots, retained for raster/pixel source selection. */
    val sourceCelId: String,
    val targetCelId: String,
    /** Each entry is a separate identity scope, even when record IDs repeat in another piece. */
    val pieces: List<TweenComponentPiece>,
)

/** Component-aware transient vector view. Raster content remains in the source frame. */
data class TweenComponentScene(
    val frameId: String,
    val targetFrameId: String?,
    val fraction: Float,
    val layers: List<TweenComponentLayerScene>,
)

/**
 * Resolves linked cels using the same [CelParts.resolve] ownership walk used by the document's
 * region resolver. Records stay whole in each scene piece; a renderer applies the piece rectangle
 * as a clip so strokes crossing an ownership edge keep their original geometry.
 */
internal fun buildComponentTweenScene(
    doc: JbDocument,
    boardId: String,
    sample: TweenFrameSample,
    strokeSource: (layerId: String, celId: String) -> List<StrokeRecord>?,
): TweenComponentScene {
    val context = TweenScene.validateSample(doc, boardId, sample)
    val board = context.board
    val sourceFrame = context.sourceFrame
    val targetFrame = context.targetFrame
    val fraction = sample.fraction.toFloat()
    val output = ArrayList<TweenComponentLayerScene>()

    for (layer in doc.layers) {
        val regions = layer.regions.filter { it.boardId == boardId }
        if (regions.size > 1) throw DocException("Layer \"${layer.id}\" has more than one region on board \"$boardId\"")
        val region = regions.singleOrNull() ?: continue
        if (region.held) continue

        val sourceRoot = region.frameCel[sourceFrame.id]
            ?: throw DocException("Layer \"${layer.id}\" has no cel for frame \"${sourceFrame.id}\"")
        val targetFrameId = targetFrame?.id ?: sourceFrame.id
        val targetRoot = region.frameCel[targetFrameId]
            ?: throw DocException("Layer \"${layer.id}\" has no cel for frame \"$targetFrameId\"")
        if (layer.cels.none { it.id == sourceRoot }) throw DocException("Layer \"${layer.id}\" has no cel \"$sourceRoot\"")
        if (layer.cels.none { it.id == targetRoot }) throw DocException("Layer \"${layer.id}\" has no cel \"$targetRoot\"")

        val sourcePieces = CelParts.resolve(layer, sourceRoot, board.rect)
        // At t=0 the source scene is already exact. Do not walk or read target components;
        // keep the source ownership partition so target-only cuts cannot alter its clipping.
        val targetPieces = when {
            fraction == 0f || sourceRoot == targetRoot -> sourcePieces
            else -> CelParts.resolve(layer, targetRoot, board.rect)
        }
        val lineCache = HashMap<String, List<StrokeRecord>>()
        fun lines(celId: String): List<StrokeRecord> = lineCache.getOrPut(celId) {
            val cel = layer.cels.firstOrNull { it.id == celId }
                ?: throw DocException("A linked drawing is missing from \"${layer.name}\"")
            if (cel.strokesFile == null) emptyList()
            else strokeSource(layer.id, cel.id)
                ?: throw DocException("Layer \"${layer.id}\" cel \"${cel.id}\" declares \"${cel.strokesFile}\" but its line records are missing")
        }

        val pieces = ArrayList<TweenComponentPiece>()
        for (sourcePiece in sourcePieces) {
            for (targetPiece in targetPieces) {
                if (fraction == 0f && sourcePiece != targetPiece) continue
                val rect = CelParts.intersection(sourcePiece.rect, targetPiece.rect) ?: continue
                val from = lines(sourcePiece.celId)
                val records = if (fraction == 0f || sourcePiece.celId == targetPiece.celId) from
                else Tween.between(from, lines(targetPiece.celId), fraction)
                pieces += TweenComponentPiece(sourcePiece.celId, targetPiece.celId, rect, records)
            }
        }
        output += TweenComponentLayerScene(layer.id, sourceRoot, targetRoot, pieces)
    }

    return TweenComponentScene(sourceFrame.id, targetFrame?.id, fraction, output)
}
