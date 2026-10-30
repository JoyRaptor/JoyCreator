package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.media.MediaCanvas
import cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine
import cc.joycreator.joybrush.androidkit.gl.media.MediaPaperGl
import cc.joycreator.joybrush.androidkit.gl.media.Tex
import cc.joycreator.joybrush.androidkit.gl.watercolour.GlWatercolourLineRenderer
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourPass
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.media.MediaPaper
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paper.LookEntry
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import kotlin.test.*

/**
 * Host ownership proof, not a GL/pixel oracle. Only allocation metadata and CPU decoding are
 * supplied: paper adoption, borrower enumeration, callback replacement and pin scope cleanup
 * use the production paths. Fake deletion records names rather than invoking GLES.
 */
class PaperTextureRetentionTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)

    private class Fixture {
        val deleted = ArrayList<Int>()
        var deleteFault: ((IntArray) -> Unit)? = null
        val cache = GrainTextures(loadBitmap = { _, _ -> error("Unseeded GPU allocation") },
            deleteTextures = { deleteFault?.invoke(it); deleted.addAll(it.toList()) })
        val engine = GlPaintEngine(grains = cache)
    }

    private fun seed(cache: GrainTextures, key: String, handle: Int) {
        field<MutableMap<String, Int>>(cache, "byName")[key] = handle
        field<MutableMap<String, Int>>(cache, "sizes")[key] = 2048
    }

    private fun paper(id: Int) = ResolvedPaper(
        SurfaceEntry("surface_$id", "Surface $id", "surface_$id.png", 2048, 1f, 1f, 128f, true,
            fluid = "fluid_$id.png"),
        LookEntry("look_$id", "Look $id", "#FFFFFF", "look_$id.png", "#FFFFFF"),
        -1, 1f, 1f, 1f, false)

    private fun seedPaper(f: Fixture, id: Int, handleBase: Int = id * 10): Set<Int> =
        (1..3).map { handleBase + it }.toSet().also {
        seed(f.cache, "paper/look_$id.png", handleBase + 1)
        seed(f.cache, "paper/surface_$id.png", handleBase + 2)
        seed(f.cache, "paper/fluid_$id.png", handleBase + 3)
    }

    private fun adopt(f: Fixture, id: Int) = f.engine.setPaperWith(paper(id)) { p, _, _ ->
        PaperResources.Loaded(p, null, null, emptyList())
    }

    private fun held(f: Fixture): Set<Int> = f.cache.heldTextureHandles()

    @Test fun twoFullIdleTraversalsKeepOnlyCurrentPaperAndLeaveBrushAssetsUntouched() {
        val f = Fixture()
        seed(f.cache, "grain/cloud.png", 9001)
        seed(f.cache, "grain/surface_custom.png", 9002)
        for (visit in 1..90) {
            val id = (visit - 1) % 45 + 1
            val current = seedPaper(f, id, visit * 10)
            adopt(f, id)
            assertEquals(current + setOf(9001, 9002), held(f), "visit $id")
            assertEquals(5, field<Map<String, Int>>(f.cache, "sizes").size)
        }
        assertEquals(89 * 3, f.deleted.size)
        assertEquals(f.deleted.size, f.deleted.toSet().size, "Every retired name is deleted once")
        assertEquals(0, f.engine.collectPaperTextures(), "Repeated collection is idempotent")
        assertFalse(9001 in f.deleted || 9002 in f.deleted)
    }

    @Test fun placeholderAndEvenAnAliasedBrushNameCannotBeDeletedAsPaper() {
        val f = Fixture()
        put(f.cache, "placeholder", 41)
        seed(f.cache, "paper/placeholder_alias.png", 41)
        seed(f.cache, "paper/brush_alias.png", 42)
        seed(f.cache, "grain/cloud.png", 42)
        seed(f.cache, "paper/unused.png", 43)
        assertEquals(1, f.engine.collectPaperTextures())
        assertEquals(listOf(43), f.deleted)
        assertEquals(setOf(41, 42), held(f))
    }

    @Test fun frozenStampAndTuftBorrowersSurviveLaterPaperAdoptions() {
        val f = Fixture()
        val old = seedPaper(f, 1)
        put(f.engine, "look", StampPass.StrokeLook(TipShape(), GrainMath.StrokeGrain(
            GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF), 11, 12,
            paper(1).surface, 1f, PaperResponse(), 1f, null, 13))
        seed(f.cache, "paper/tuft.png", 14)
        put(f.engine, "tuftPaperTex", 14)
        for (id in 2..8) {
            val current = seedPaper(f, id)
            adopt(f, id)
            assertEquals(old + 14 + current, held(f))
        }
        assertTrue(f.deleted.none { it in old || it == 14 })
    }

    @Test fun liveAndSelectedWatercolourCallbacksProtectTheirOwnBorrowedNamesUntilCleared() {
        val f = Fixture()
        val live = WatercolourPass(ShaderLibrary())
        val selected = GlWatercolourLineRenderer(ShaderLibrary())
        val selectedPass = field<WatercolourPass>(selected, "pass")
        val liveHandles = seedPaper(f, 1).toMutableSet()
        val selectedHandles = seedPaper(f, 2)
        live.recolour(floatArrayOf(1f, 0f, 0f), true, liveHandles) { error("No GPU finish in host test") }
        selectedPass.recolour(floatArrayOf(0f, 1f, 0f), true, selectedHandles) { error("No GPU finish in host test") }
        liveHandles.clear() // The caller cannot change the callback's inventory after binding.
        put(f.engine, "wcPass", live)
        put(f.engine, "wcLineRenderer", selected)
        val current = seedPaper(f, 3)
        adopt(f, 3)
        assertEquals((11..13).toSet() + selectedHandles + current, held(f))
        live.clearStroke()
        f.engine.collectPaperTextures()
        assertEquals(selectedHandles + current, held(f))
        selected.forget()
        f.engine.collectPaperTextures()
        assertEquals(current, held(f))
    }

    private fun mediaPaper(surface: Int, fluid: Int) = MediaPaperGl(
        MediaPaper(DoubleArray(256).also { it[128] = 1.0 }, toothMm = .1, compliance = .3,
            sizing = .5, absorbency = .5, capacity = .5, wickSpeed = .4),
        Tex(surface), 1f, 2048f, 128f, 1f, true, Tex(fluid), 1f, 2048f, 128f)

    private fun waterJob(e: MediaLayerEngine, paper: MediaPaperGl): Any {
        val type = e.javaClass.declaredClasses.single { it.simpleName == "WaterJob" }
        return type.declaredConstructors.single().also { it.isAccessible = true }.newInstance(
            floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 1f, 1f), 2, WetConstants(), 1.0,
            0, false, 1f, paper, 20.0, 240.0, listOf(0), null, 0.0,
            emptySet<Long>(), emptySet<Long>(), true)
    }

    @Test fun mediaCanvasAutomaticallyRegistersBakedAndRunningJobBorrowers() {
        val f = Fixture()
        val canvas = MediaCanvas(f.engine, { Long.MAX_VALUE to 0L })
        val layer = MediaLayerEngine()
        put(canvas, "engine", layer)
        seed(f.cache, "paper/baked_surface.png", 21)
        seed(f.cache, "paper/baked_fluid.png", 22)
        seed(f.cache, "paper/job_surface.png", 31)
        seed(f.cache, "paper/job_fluid.png", 32)
        put(layer, "bakedPaper", mediaPaper(21, 22))
        put(layer, "job", waterJob(layer, mediaPaper(31, 32)))
        val current = seedPaper(f, 4)
        adopt(f, 4)
        assertEquals(setOf(21, 22, 31, 32) + current, held(f))
        put(layer, "job", null)
        f.engine.collectPaperTextures()
        assertEquals(setOf(21, 22) + current, held(f))
        canvas.forget() // The still-registered provider now returns an empty inventory.
        f.engine.collectPaperTextures()
        assertEquals(current, held(f))
    }

    @Test fun cachedMediaPaperIsReplacedAtAdoptionWithoutLosingExternalBorrowers() {
        val f = Fixture()
        seedPaper(f, 1); adopt(f, 1)
        val before = f.engine.mediaPaper()
        assertEquals(setOf(12, 13), before.borrowedTextureHandles())
        val owner = Any()
        f.engine.registerPaperTextureBorrower(owner) { before.borrowedTextureHandles() }
        val current = seedPaper(f, 2)
        adopt(f, 2)
        assertEquals(setOf(12, 13) + current, held(f))
        assertNotSame(before, f.engine.mediaPaper())
        f.engine.registerPaperTextureBorrower(owner) { emptySet() }
        f.engine.collectPaperTextures()
        assertEquals(current, held(f))
    }

    @Test fun nestedColdScopesKeepPriorNamesThroughFailureAndRollbackThenRetireTheAttempt() {
        val f = Fixture()
        val prior = seedPaper(f, 1); adopt(f, 1)
        val priorLoaded = field<PaperResources.Loaded>(f.engine, "loadedPaper")
        val priorResolved = field<ResolvedPaper>(f.engine, "resolvedPaper")
        val failure = assertFailsWith<IllegalStateException> {
            f.engine.withPinnedPaperTextures(prior) {
                try {
                    val attempt = seedPaper(f, 2); adopt(f, 2)
                    f.engine.withPinnedPaperTextures(attempt) {
                        seedPaper(f, 3); adopt(f, 3)
                        assertTrue(held(f).containsAll(prior + attempt))
                        error("injected adoption failure")
                    }
                } finally {
                    // Same exact raw-handle restoration performed by cold adoption's catch, while
                    // its production pin scope remains open. The full upload/GL-state path is a GPU gate.
                    put(f.engine, "resolvedPaper", priorResolved)
                    put(f.engine, "loadedPaper", priorLoaded)
                    put(f.engine, "lookTexture", 11)
                    put(f.engine, "surfaceTexture", 12)
                    put(f.engine, "fluidTexture", 13)
                }
            }
        }
        assertEquals("injected adoption failure", failure.message)
        assertEquals(prior, held(f))
        assertSame(priorLoaded, field<PaperResources.Loaded>(f.engine, "loadedPaper"))
        assertTrue(f.deleted.none { it in prior })
        val next = seedPaper(f, 4); adopt(f, 4)
        assertEquals(next, held(f), "The failed scope must not leak a prior-paper pin")
    }

    @Test fun successfulColdScopeReleasesItsPriorPaperAfterAdoption() {
        val f = Fixture()
        val prior = seedPaper(f, 1); adopt(f, 1)
        val current = seedPaper(f, 2)
        assertEquals("accepted", f.engine.withPinnedPaperTextures(prior) {
            adopt(f, 2)
            assertTrue(held(f).containsAll(prior + current))
            "accepted"
        })
        assertEquals(current, held(f))
        assertEquals(prior, f.deleted.toSet())
    }

    @Test fun coldFinalSweepPreservesTheCallersRestoredPaperBindingUntilTheNextOperation() {
        val f = Fixture()
        val prior = seedPaper(f, 1); adopt(f, 1)
        val current = seedPaper(f, 2)
        f.engine.withPinnedPaperTextures(prior, retainAtClose = setOf(11)) { adopt(f, 2) }
        assertEquals(current + 11, held(f))
        assertEquals(setOf(12, 13), f.deleted.toSet())
        f.engine.collectPaperTextures()
        assertEquals(current, held(f))
    }

    @Test fun pinCloseIsIdempotentAndForgettingInvalidatesOldScopesEvenIfNamesAreReused() {
        val f = Fixture()
        seed(f.cache, "paper/old.png", 7)
        val outer = f.cache.pinPaperTextures(setOf(7))
        val inner = f.cache.pinPaperTextures(setOf(7))
        outer.close(); outer.close()
        assertEquals(0, f.cache.collectUnusedPaperTextures { emptySet() })
        inner.close()
        assertEquals(1, f.cache.collectUnusedPaperTextures { emptySet() })
        val stale = f.cache.pinPaperTextures(setOf(7))
        f.cache.forget()
        seed(f.cache, "paper/new.png", 7)
        val current = f.cache.pinPaperTextures(setOf(7))
        stale.close()
        assertEquals(0, f.cache.collectUnusedPaperTextures { emptySet() })
        current.close()
        assertEquals(1, f.cache.collectUnusedPaperTextures { emptySet() })
    }

    @Test fun contextRetirementInsideColdScopeDoesNotCollectReplacementContextNames() {
        val f = Fixture()
        f.engine.initWith {}
        f.engine.withPinnedPaperTextures(setOf(7)) {
            f.engine.initWith {}
            seed(f.cache, "paper/replacement.png", 7)
        }
        assertEquals(setOf(7), held(f))
        assertEquals(emptyList(), f.deleted)
        assertEquals(1, f.engine.collectPaperTextures())
    }

    @Test fun anyBorrowerEnumerationFailurePreventsAllDeletions() {
        val f = Fixture()
        seed(f.cache, "paper/would_retire.png", 21)
        seed(f.cache, "grain/cloud.png", 22)
        f.engine.registerPaperTextureBorrower(Any()) { setOf(22) }
        f.engine.registerPaperTextureBorrower(Any()) { error("borrower unavailable") }
        assertEquals(0, f.engine.collectPaperTextures())
        assertEquals(setOf(21, 22), held(f))
        assertEquals(emptyList(), f.deleted)
        assertEquals(2048, f.cache.sizeFor("would_retire.png", "paper"))
        val snapshot = f.engine.paperTextureCacheSnapshot()
        assertEquals(1L, snapshot.collectionFailures)
        assertEquals("IllegalStateException", snapshot.lastCollectionFailure)
    }

    @Test fun unavailableInventoryDoesNotThrowAfterNormalPaperAdoptionAndCanRecoverLater() {
        val f = Fixture()
        val prior = seedPaper(f, 1); adopt(f, 1)
        val owner = Any()
        f.engine.registerPaperTextureBorrower(owner) { error("inventory unavailable") }
        val current = seedPaper(f, 2)
        adopt(f, 2)
        assertEquals(21, field<Int?>(f.engine, "lookTexture"))
        assertEquals(22, field<Int?>(f.engine, "surfaceTexture"))
        assertEquals(23, field<Int?>(f.engine, "fluidTexture"))
        assertEquals(prior + current, held(f))
        assertEquals(emptyList(), f.deleted)
        assertEquals(1L, f.engine.paperTextureCacheSnapshot().collectionFailures)
        f.engine.registerPaperTextureBorrower(owner) { emptySet() }
        f.engine.collectPaperTextures()
        assertEquals(current, held(f))
    }

    @Test fun throwingDeletionCannotRejectAdoptionOrLeavePossiblyDeletedNamesInLookup() {
        val f = Fixture()
        seedPaper(f, 1); adopt(f, 1)
        f.deleteFault = { names ->
            f.deleted.add(names.first()) // Model failure after a partial driver deletion.
            error("injected partial deletion")
        }
        val current = seedPaper(f, 2)
        adopt(f, 2)
        assertEquals(current, held(f))
        assertEquals(21, field<Int?>(f.engine, "lookTexture"))
        assertEquals(1L, f.engine.paperTextureCacheSnapshot().collectionFailures)
        assertEquals(1, f.deleted.size, "Do not retry names whose deletion status is uncertain")
        assertEquals(0, f.engine.collectPaperTextures())
        f.deleteFault = null
        val reloaded = seedPaper(f, 1, handleBase = 100)
        adopt(f, 1)
        assertEquals(reloaded, held(f), "Revisiting uses fresh allocation metadata, never a possibly deleted name")
    }

    @Test fun diagnosticsAreDetachedImmutableAndCountTheCompleteRgba8MipChain() {
        val f = Fixture()
        seed(f.cache, "paper/full.png", 21)
        seed(f.cache, "grain/cloud.png", 22)
        val snapshot = f.engine.paperTextureCacheSnapshot()
        assertEquals(setOf(21), snapshot.handles)
        assertEquals(mapOf("paper/full.png" to 21), snapshot.handlesByAsset)
        assertEquals(1, snapshot.count)
        assertEquals(22_369_620L, snapshot.estimatedMipBytes)
        f.engine.collectPaperTextures()
        assertEquals(setOf(21), snapshot.handles)
        assertEquals(0, f.engine.paperTextureCacheSnapshot().count)
        assertFailsWith<UnsupportedOperationException> { (snapshot.handles as MutableSet<Int>).add(7) }
        assertFailsWith<UnsupportedOperationException> { (snapshot.handlesByAsset as MutableMap<String, Int>)["paper/other.png"] = 7 }
    }
}
