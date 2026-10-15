package cc.joycreator.joybrush.android.board

import android.content.Context
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Real view/root bookkeeping with fake names; no GPU execution or appearance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasBoundSaveGuardTest {
    private val surfaces = ArrayList<JbCanvasView>()

    @After fun pauseSurfaces() { surfaces.forEach { it.onPause() } }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner)

    private fun invoke(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name\$")) && it.parameterCount == args.size
        }.also { it.isAccessible = true }
        return try { method.invoke(owner, *args) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication() as Context).also { view ->
        surfaces += view
        set(view, "documentName", "Keep this drawing")
        set(view, "pageW", 777)
        set(view, "pageH", 999)
        set(view, "paperRevision", 37L)
        (field(view, "engine") as GlPaintEngine).apply {
            invoke(this, "initWith", { })
            resetDocument()
            addLayer("paint")
        }
    }

    private fun set(view: JbCanvasView, name: String, value: Any?) =
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(view, value)

    private fun engine(view: JbCanvasView) = field(view, "engine") as GlPaintEngine

    private data class StoreState(val identity: Any, val contents: Map<Long, Int>)
    private data class OwnerState(val identity: Any, val stores: Map<String, StoreState>)
    private data class Snapshot(
        val root: Any,
        val coordinator: Any,
        val cursor: Any,
        val layerIds: List<String>,
        val owners: Map<String, OwnerState>,
        val planes: Map<Pair<String, String?>, RecordedCel<Int>>,
        val slabHandles: Map<Pair<String, String?>, Map<Long, List<Pair<Long, Int>>>>,
        val protected: Set<Int>,
        val pools: Map<String, List<Int>>,
        val board: Any?,
        val lineCalls: Long,
        val compositionCalls: Long,
        val viewState: Map<String, Any?>,
        val queues: Map<String, List<Any?>>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun snapshot(view: JbCanvasView): Snapshot {
        val e = engine(view)
        val root = checkNotNull(field(e, "publishedPaint"))
        val coordinator = checkNotNull(field(root, "coordinator"))
        val state = checkNotNull(field(root, "recorded"))
        val planes = field(state, "planes") as Map<Pair<String, String?>, RecordedCel<Int>>
        val nativeLayers = field(root, "nativeLayers") as Map<String, Any>
        val owners = nativeLayers.mapValues { (_, owner) ->
            val stores = linkedMapOf<String, StoreState>()
            val tiles = field(owner, "tiles") as MutableMap<Long, Int>
            stores["tiles"] = StoreState(tiles, tiles.toMap())
            val cels = field(owner, "cels") as Map<String, MutableMap<Long, Int>>
            cels.forEach { (cel, map) -> stores["cel:$cel"] = StoreState(map, map.toMap()) }
            OwnerState(owner, stores)
        }
        val slabs = planes.mapValues { (_, cel) ->
            cel.slabs.mapValues { (_, stack) -> stack.map { it.seq to it.value.texture } }
        }
        val pools = listOf("freeLayerTex", "freeStrokeTex", "freeSmudgeTex", "freeFloatTex")
            .associateWith { (field(e, it) as Collection<Int>).toList() }
        val viewState = listOf("stackUi", "retainedContents", "activeLayer", "editingMask", "paperRevision",
            "pageW", "pageH", "documentName").associateWith { field(view, it) }
        val queues = listOf("frameWork", "afterFrameWork", "pendingPaperVisits").associateWith {
            (field(view, it) as Collection<*>).toList()
        }
        return Snapshot(root, coordinator, e.undo.snapshotCursor(), e.layerIds(), owners, planes, slabs,
            invoke(e, "protectedRecordedTextureHandles") as Set<Int>, pools, e.boardDocument,
            field(e, "recordedLinePreparationCalls") as Long, field(e, "recordedCompositionCalls") as Long, viewState, queues)
    }

    private fun assertUnchanged(view: JbCanvasView, before: Snapshot) {
        val after = snapshot(view)
        assertSame("published root", before.root, after.root)
        assertSame("recorded coordinator", before.coordinator, after.coordinator)
        assertSame("history cursor", before.cursor, after.cursor)
        assertEquals("layer order", before.layerIds, after.layerIds)
        assertEquals("native owners", before.owners.keys, after.owners.keys)
        before.owners.forEach { (id, expected) ->
            val actual = after.owners.getValue(id)
            assertSame("native owner $id", expected.identity, actual.identity)
            assertEquals("native stores $id", expected.stores.keys, actual.stores.keys)
            expected.stores.forEach { (name, store) ->
                val actualStore = actual.stores.getValue(name)
                assertSame("store $id/$name", store.identity, actualStore.identity)
                assertEquals("store contents $id/$name", store.contents, actualStore.contents)
            }
        }
        assertEquals("recorded planes", before.planes.keys, after.planes.keys)
        before.planes.forEach { (address, cel) -> assertSame("plane $address", cel, after.planes[address]) }
        assertEquals("recorded slab ownership", before.slabHandles, after.slabHandles)
        assertEquals("protected texture inventory", before.protected, after.protected)
        assertEquals("texture pools", before.pools, after.pools)
        assertSame("board document", before.board, after.board)
        assertEquals("recorded line replays", before.lineCalls, after.lineCalls)
        assertEquals("recorded compositions", before.compositionCalls, after.compositionCalls)
        assertEquals("view fields", before.viewState, after.viewState)
        assertEquals("queued work", before.queues, after.queues)
    }

    private fun assertReadRefusesWithoutMutation(view: JbCanvasView) {
        val e = engine(view)
        assertNotNull("fixture must have a bound root", field(e, "publishedPaint"))
        val before = snapshot(view)
        val refusal = assertThrows(JbArchiveException::class.java) {
            invoke(view, "readContents", 64, 64)
        }
        assertTrue(refusal.message.orEmpty().contains("physical-source save connection"))
        assertUnchanged(view, before)
    }

    private fun staticDocumentWithTile(): cc.joycreator.joybrush.core.doc.JbDocument {
        val ids = ArrayDeque(listOf("board", "paint", "cel"))
        val base = DocOps.newDocument("bound-save", "Bound save", 256, 256) { ids.removeFirst() }
        return base.copy(layers = base.layers.map { layer ->
            layer.copy(cels = layer.cels.map { cel -> cel.copy(tiles = listOf("0_0")) })
        })
    }

    @Test fun actualPlainSourceTransferIsNotFlattenedByReadContents() {
        val view = surface()
        val e = engine(view)
        val doc = staticDocumentWithTile()
        e.setBoardDocument(doc)
        // The transfer ticket is real; a fake nonzero RGBA8 handle keeps this a bookkeeping-only
        // fixture. initWith{} deliberately does not create GL objects, and readContents must refuse
        // before it could try to read this source.
        val owner = (field(e, "localLayers") as Map<String, Any>).getValue("paint")
        @Suppress("UNCHECKED_CAST")
        val tiles = field(owner, "tiles") as MutableMap<Long, Int>
        tiles[0L] = 501

        val ticket = invoke(e, "preparePlainRecordingPromotion", "paint")!!
        assertEquals(true, invoke(ticket, "canPublish"))
        assertEquals(true, invoke(ticket, "publish"))
        assertNotNull(field(e, "publishedPaint"))
        val root = checkNotNull(field(e, "publishedPaint"))
        @Suppress("UNCHECKED_CAST")
        val planes = field(checkNotNull(field(root, "recorded")), "planes") as Map<Pair<String, String?>, RecordedCel<Int>>
        assertTrue("promoted plane is line-free", planes.getValue("paint" to null).records.isEmpty())
        assertEquals(setOf(0L), planes.getValue("paint" to null).slabs.keys)

        assertReadRefusesWithoutMutation(view)
    }

    @Test fun genericColdRecordedRootWithNoBoardDocumentCannotTakeFlattenFallback() {
        val view = surface()
        val e = engine(view)
        val brush = BrushPreset(id = "frozen", name = "Frozen", size = Param(4f))
        val record = StrokeRecord("line", brush.id, 81L, 0f, 1f,
            listOf(PenSample(10f, 10f, 0.0)), seq = 1L)
        val plane = "paint" to null
        // androidkit is a composite dependency, not this test module's friend. Exercise its
        // actual internal contract through JVM reflection rather than changing visibility.
        val sceneClass = Class.forName("cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene")
        val rendererClass = Class.forName("cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene\$Renderer")
        val fakeRenderer = Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { proxy, method, args ->
            when (method.name) {
                "contextCurrent" -> true
                "line", "look", "release" -> throw AssertionError("The save guard must not render or release")
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Cold save-guard fixture"
                else -> null
            }
        }
        val companion = sceneClass.getDeclaredField("Companion").get(null)
        val scene = invoke(companion, "createSaved",
            mapOf(plane to RecordedCel("paint", null, listOf(record))),
            mapOf(brush.id to brush), e.undo, fakeRenderer,
            mapOf(plane to setOf(0L)), mapOf(plane to mapOf(0L to 900_001)),
            emptySet<Int>())!!
        invoke(e, "registerRecordedScene", scene)
        invoke(e, "bindRecordedPaintPublication")
        assertNull("generic fixture intentionally has no board metadata", e.boardDocument)
        assertNotNull(field(e, "publishedPaint"))
        assertEquals(900_001, invoke(scene, "look", plane, 0L))

        assertReadRefusesWithoutMutation(view)
    }
}
