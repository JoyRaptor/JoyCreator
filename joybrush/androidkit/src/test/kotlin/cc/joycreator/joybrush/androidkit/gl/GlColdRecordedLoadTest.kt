package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.androidkit.io.MediaTileKey
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.*

/** No GL calls: fresh-root admission must refuse before it changes engine ownership or history. */
class GlColdRecordedLoadTest {
    private data class Snapshot(
        val ready: Boolean,
        val root: Any?,
        val scene: Any?,
        val layers: Map<String, Any>,
        val tiles: Map<String, Map<Long, Int>>,
        val cels: Map<String, Map<String, Map<Long, Int>>>,
        val document: Any?,
        val paper: Any?,
        val undo: Any?,
        val redo: Any?,
        val undoable: Boolean,
        val redoable: Boolean,
        val owners: Set<Int>,
        val layerPool: List<Int>,
        val strokePool: List<Int>,
        val floatPool: List<Int>,
        val strokeTiles: Map<Long, Int>,
        val framePreviews: Map<String, String>,
        val tileBoard: String?,
        val fading: Boolean,
        val mediaStroke: Any?,
        val mediaHeld: Set<Any?>,
    )

    private fun plan(withTile: Boolean = false, withMedia: Boolean = false,
                     paper: Paper = Paper()): ColdRecordedLoadPlan.Prepared {
        var id = 0
        val base = DocOps.newDocument("cold", "Cold", 64, 64) { "cold-${id++}" }
        val layer = base.layers.single()
        val cel = layer.cels.single().copy(strokesFile = "layers/${layer.id}/${layer.cels.single().id}/strokes.jbs",
            tiles = if(withTile) listOf("0_0") else emptyList())
        val native = Layer("native", "Native", LayerKind.PAINT,
            cels = listOf(Cel("native-cel", floatTiles = listOf("0_0"))))
        val doc = base.copy(paper = paper, layers = listOf(layer.copy(cels = listOf(cel))) +
            if(withMedia) listOf(native) else emptyList())
        return ColdRecordedLoadPlan.prepare(JbContents(
            doc = doc,
            tiles = if(withTile) mapOf(Triple(layer.id,cel.id,"0_0") to ByteArray(TILE_BYTES)) else emptyMap(),
            strokes = mapOf((layer.id to cel.id) to emptyList()),
            mediaTiles = if(withMedia) mapOf(MediaTileKey("native","native-cel","0_0","g") to ByteArray(TILE_BYTES)) else emptyMap(),
        ))
    }

    private fun engine() = GlPaintEngine().also { it.initWith {} }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)

    private fun snapshot(e: GlPaintEngine): Snapshot {
        val root = field<Any?>(e, "publishedPaint")
        val layers = if (root == null) field<MutableMap<String, Any>>(e, "localLayers")
            else field<MutableMap<String, Any>>(root, "nativeLayers")
        val undo = e.undo.peekUndo()
        val redo = e.undo.peekRedo()
        return Snapshot(
            ready = e.ready,
            root = root,
            scene = field(e, "localRecordedScene"),
            layers = layers.toMap(),
            tiles = layers.mapValues { (_, layer) -> field<MutableMap<Long, Int>>(layer, "tiles").toMap() },
            cels = layers.mapValues { (_, layer) ->
                field<MutableMap<String, HashMap<Long, Int>>>(layer, "cels").mapValues { it.value.toMap() }
            },
            document = e.boardDocument,
            paper = e.documentPaper,
            undo = undo,
            redo = redo,
            undoable = e.undo.canUndo,
            redoable = e.undo.canRedo,
            owners = e.protectedRecordedTextureHandles(),
            layerPool = field<ArrayDeque<Int>>(e, "freeLayerTex").toList(),
            strokePool = field<ArrayDeque<Int>>(e, "freeStrokeTex").toList(),
            floatPool = field<ArrayDeque<Int>>(e, "freeFloatTex").toList(),
            strokeTiles = field<MutableMap<Long, Int>>(e, "strokeTiles").toMap(),
            framePreviews = field(e, "framePreviews"),
            tileBoard = e.tileBoardId,
            fading = e.watercolourAnimating,
            mediaStroke = field(e, "mediaStroke"),
            mediaHeld = field<MutableSet<Any?>>(e, "mediaStrokeHeld").toSet(),
        )
    }

    private fun refusesWithoutMutation(e: GlPaintEngine, input: ColdRecordedLoadPlan.Prepared = plan()) {
        val before = snapshot(e)
        assertFails { e.loadFreshRecordedArtwork(input) }
        assertEquals(before, snapshot(e), "A refused cold load must leave every engine owner and cursor unchanged")
    }

    @Test fun uninitializedEngineRefusesAndFailedReinitializationClearsReady() {
        val unready = GlPaintEngine()
        assertFalse(unready.ready)
        refusesWithoutMutation(unready)

        val e = engine()
        assertTrue(e.ready)
        assertFails { e.initWith { error("simulated context initialization failure") } }
        assertFalse(e.ready, "A failed reinitialization must not leave the replacement context marked ready")
        refusesWithoutMutation(e)
    }

    @Test fun occupiedLayerNativeDocumentAndNativePixelsRefuseBeforeMutation() {
        val layerOnly = engine().also { it.addLayer("existing") }
        refusesWithoutMutation(layerOnly)

        var id = 0
        val doc = DocOps.newDocument("occupied", "Occupied", 64, 64) { "occupied-${id++}" }
        val native = engine().also {
            it.addLayer(doc.layers.single().id)
            it.setBoardDocument(doc)
        }
        refusesWithoutMutation(native)

        val withPixels = engine().also {
            it.addLayer(doc.layers.single().id)
            val layers = field<MutableMap<String, Any>>(it, "localLayers")
            val tiles = field<MutableMap<Long, Int>>(layers.getValue(doc.layers.single().id), "tiles")
            tiles[7L] = 701
        }
        refusesWithoutMutation(withPixels)
    }

    @Test fun undoOnlyAndRedoOnlyHistoryRefuseWithoutMovingEitherCursor() {
        val undoOnly = engine()
        undoOnly.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("old", 0L, 11, 12))))
        refusesWithoutMutation(undoOnly)

        val redoOnly = engine()
        redoOnly.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("old", 0L, 21, 22))))
        assertNotNull(redoOnly.undo.undo()) // Bookkeeping fixture: the redo branch now owns the step.
        assertFalse(redoOnly.undo.canUndo)
        assertTrue(redoOnly.undo.canRedo)
        refusesWithoutMutation(redoOnly)
    }

    @Test fun existingLocalSceneAndBoundPublicationRefuseWithoutReplacingOwnership() {
        val local = engine()
        val scene = local.createSavedRecordedScene(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        refusesWithoutMutation(local)
        assertSame(scene, field<Any?>(local, "localRecordedScene"))

        val bound = engine()
        bound.createSavedRecordedScene(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        bound.bindRecordedPaintPublication()
        assertTrue(bound.hasJointPaintPublication)
        refusesWithoutMutation(bound)
        assertTrue(bound.hasJointPaintPublication)
    }

    @Test fun activeStrokeFadePlaybackPeriodicityAndMediaOwnershipRefuseBeforeMutation() {
        val stroke = engine().also { it.addLayer("paint") }
        val layer = field<MutableMap<String, Any>>(stroke, "localLayers").getValue("paint")
        put(stroke, "strokeLayer", layer)
        refusesWithoutMutation(stroke)

        val fading = engine().also { put(it, "wcFading", true) }
        refusesWithoutMutation(fading)

        val playback = engine().also { put(it, "framePreviews", mapOf("board" to "frame")) }
        refusesWithoutMutation(playback)

        val periodic = engine().also { put(it, "tileBoardId", "canvas") }
        refusesWithoutMutation(periodic)

        val media = engine().also { it.beginMediaStroke() }
        refusesWithoutMutation(media)

        val mediaHeld = engine().also {
            field<MutableSet<Any?>>(it, "mediaStrokeHeld").add(Triple("media", null, 9L))
        }
        refusesWithoutMutation(mediaHeld)
    }

    @Test fun orphanStrokeBufferRefusesBeforeMutation() {
        val e = engine()
        field<MutableMap<Long, Int>>(e, "strokeTiles")[3L] = 303
        refusesWithoutMutation(e)
    }

    @Test fun uploadAndMediaBudgetsRefuseBeforeGlOrDocumentMutation() {
        val small = GlPaintEngine(undoBudgetBytes = 1).also { it.initWith {} }
        val before = snapshot(small)
        assertTrue(assertFailsWith<IllegalArgumentException> {
            small.loadFreshRecordedArtwork(plan(withTile = true))
        }.message.orEmpty().contains("upload budget"))
        assertEquals(before, snapshot(small))
        val media = engine().also { it.mediaBudgetBytes = 0 }
        val beforeMedia = snapshot(media)
        assertTrue(assertFailsWith<IllegalArgumentException> {
            media.loadFreshRecordedArtwork(plan(withMedia = true))
        }.message.orEmpty().contains("media budget"))
        assertEquals(beforeMedia, snapshot(media))
    }

    @Test fun unknownPaperRefusesBeforeChangingEmptyEnginePaperOrOwners() {
        val e = engine()
        val before = snapshot(e)
        assertTrue(assertFailsWith<IllegalArgumentException> {
            e.loadFreshRecordedArtwork(plan(paper = Paper(textureId = "missing-cold-surface")))
        }.message.orEmpty().contains("actual paper"))
        assertEquals(before, snapshot(e))
    }
}
