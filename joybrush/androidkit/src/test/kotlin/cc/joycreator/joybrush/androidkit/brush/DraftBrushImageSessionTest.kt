package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import kotlin.test.*

/** Outside-only proposed JVM tests. No Android Bitmap/Activity calls. */
class DraftBrushImageSessionTest {
    private fun bytes(alpha:Int=255)=PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,alpha.toByte()))
    private fun pen()=BrushPreset(id="draft.test",name="Draft ink",size=Param(12f))
    private fun owner()=FrozenBrushPngs.snapshot(mapOf("tips/a.png" to bytes(),"tips/b.png" to bytes(99),"dual/d.png" to bytes(50)))
    private fun imagePen()=pen().copy(tip=TipSpec(source="image",image="tips/a.png",
        sampled=SampledTipSpec(listOf("tips/a.png","tips/b.png","tips/a.png"),pick=SamplePick.random,
            invert=true,coverage=SampleCoverage.luminance,rotateJitterDegrees=33f,spacingJitter=.2f),
        dual=DualTipSpec(shape=null,image=DualImageSpec("dual/d.png",SampleCoverage.luminance,true),
            scale=.6f,mode=DualMode.subtract)))
    private class Source(val bytes:ByteArray,val label:String="image.png") {
        var opened=false;var closed=false
        fun picked(reported:Long?=null)=BrushPickedPng(label,reported) {
            opened=true
            object:ByteArrayInputStream(bytes) { override fun close(){closed=true;super.close()} }
        }
    }
    private fun loader(verifier:FrozenBrushAssets.PngVerifier?=null,limits:BrushPngPickerLoader.Limits=BrushPngPickerLoader.Limits())=
        BrushPngPickerLoader(verifier,limits,uuid={UUID.fromString("12345678-1234-1234-1234-123456789abc")})

    @Test fun packagedGrainCatalogUsesProductionNamesAndGuardsCloudSourceImageMetadataToo() {
        assertEquals(listOf("cloud_256.png","cloud_fine_256.png","cloud_512.png"),PackagedBrushGrains.choices().map{it.fileName})
        for(choice in PackagedBrushGrains.choices())for(source in listOf("cloud","image")){
            val p=pen().copy(tipTexture=GrainSpec(enabled=true,source=source,image=choice.fileName))
            assertTrue(DraftBrushImageSession(p,FrozenBrushPngs.empty()).current().resources.paths.isEmpty())
        }
        assertTrue(PackagedBrushGrains.supported(GrainSpec(enabled=true,image=" ")))
        for(source in listOf("cloud","image")){
            val unknown=pen().copy(tipTexture=GrainSpec(enabled=true,source=source,image="picked/not-packaged.png"))
            assertFailsWith<BrushImageDraftException>{DraftBrushImageSession(unknown,FrozenBrushPngs.empty())}
            assertEquals(unknown.tipTexture.copy(enabled=false),DraftBrushImageSession(unknown.copy(tipTexture=
                unknown.tipTexture.copy(enabled=false)),FrozenBrushPngs.empty()).current().recipe.tipTexture)
        }
    }

    @Test fun ordinaryEditsPreserveStableOwnerOrderAndPruneExactUniqueClosure() {
        val session=DraftBrushImageSession(imagePen(),owner());val original=session.current()
        val edit=session.prepareEdit(original.recipe.copy(size=Param(30f)))
        assertSame(original.resources,edit.pair.resources)
        assertEquals(listOf("tips/a.png","tips/b.png","tips/a.png"),edit.pair.recipe.tip.sampled?.images)
        session.acceptPending(edit){true}
        val dualOnly=session.prepareEdit(session.current().recipe.copy(tip=session.current().recipe.tip.copy(
            source="procedural",image=null,sampled=null)))
        assertEquals(setOf("dual/d.png"),dualOnly.pair.resources.paths)
        assertSame(edit.pair,session.current());session.acceptPending(dualOnly){true}
        val plain=session.prepareEdit(pen());assertTrue(plain.pair.resources.paths.isEmpty())
        session.acceptPending(plain){true};assertEquals(0L,session.current().resources.encodedBytes)
    }

    @Test fun addingImagesToAnOlderValidBrushRaisesVersionThroughTheStandardWriter() {
        val session=DraftBrushImageSession(pen().copy(version=1),FrozenBrushPngs.empty())
        val loaded=loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(Source(bytes()).picked()))
        assertTrue(loaded.pair.recipe.version>=VERSION_SAMPLED_TIPS)
        session.stage(loaded);session.acceptPending(loaded){true}
        val dual=BrushPngPickerLoader().prepare(session.requestPick(BrushImageSlot.dual),listOf(Source(bytes()).picked()))
        assertTrue(dual.pair.recipe.version>=VERSION_DUAL_TIP)
    }

    @Test fun primaryPickerPreservesControlsAndPublishesOnlyAfterHostAcceptsPair() {
        val session=DraftBrushImageSession(imagePen(),owner());val original=session.current()
        val a=Source(bytes(12),"../../content:bad.png");val b=Source(bytes(77),"second\nimage.png")
        val loaded=loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(a.picked(),b.picked()))
        assertTrue(a.closed&&b.closed);assertSame(original,session.current());assertNull(session.pending())
        assertEquals(2,loaded.selectedPaths.size);assertTrue(loaded.selectedPaths.all{SampledTips.safePath(it)&&it.startsWith("picked/")})
        assertFalse(loaded.selectedPaths.any{it.contains("bad")||it.contains("content")})
        assertEquals(loaded.selectedPaths,loaded.pair.recipe.tip.sampled?.images)
        assertEquals(imagePen().tip.sampled?.copy(images=loaded.selectedPaths),loaded.pair.recipe.tip.sampled)
        assertEquals(loaded.selectedPaths.toSet()+"dual/d.png",loaded.pair.resources.paths)
        assertContentEquals(a.bytes,loaded.pair.resources.requirePng(loaded.selectedPaths[0]))
        assertEquals("secondimage.png",loaded.displayNames[1])
        val staged=session.stage(loaded)
        assertFalse(session.acceptPending(staged){pair->assertSame(staged.pair,pair);false});assertSame(original,session.current())
        assertFailsWith<IOException>{session.acceptPending(staged){throw IOException("host refused")}}
        assertSame(original,session.current());assertSame(staged,session.pending())
        assertTrue(session.acceptPending(staged){true});assertSame(staged.pair,session.current());assertNull(session.pending())
    }

    @Test fun dualPickerKeepsRepeatedPrimaryAndExistingDualControls() {
        val session=DraftBrushImageSession(imagePen(),owner());val source=Source(bytes(17))
        val loaded=loader().prepare(session.requestPick(BrushImageSlot.dual),listOf(source.picked()))
        assertEquals(imagePen().tip.sampled,loaded.pair.recipe.tip.sampled)
        val expected=imagePen().tip.dual!!.let{it.copy(image=it.image!!.copy(image=loaded.selectedPaths.single()))}
        assertEquals(expected,loaded.pair.recipe.tip.dual)
        assertEquals(setOf("tips/a.png","tips/b.png")+loaded.selectedPaths,loaded.pair.resources.paths)
        assertFalse("dual/d.png" in loaded.pair.resources.paths);assertTrue(source.closed)
    }

    @Test fun malformedOrFailedPreparationCannotReplaceCurrentOrExistingPendingOwner() {
        val session=DraftBrushImageSession(imagePen(),owner());val original=session.current()
        val prior=session.prepareEdit(original.recipe.copy(size=Param(20f)))
        val broken=Source(byteArrayOf(1,2,3))
        assertFails{loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(broken.picked()))}
        assertTrue(broken.closed);assertSame(original,session.current());assertSame(prior,session.pending())
        assertFails{session.prepareEdit(original.recipe.copy(tip=TipSpec(sampled=SampledTipSpec(listOf("missing.png")))))}
        assertSame(prior,session.pending());assertSame(original.resources,session.current().resources)
        val undecodable=Source(bytes())
        assertFails{loader(FrozenBrushAssets.PngVerifier{_,_,_->throw IOException("decode failed")})
            .prepare(session.requestPick(BrushImageSlot.sampled),listOf(undecodable.picked()))}
        assertTrue(undecodable.closed);assertSame(original,session.current());assertSame(prior,session.pending())
    }

    @Test fun oldAsyncResponsesAndForeignSessionsCannotReplaceANewerEdit() {
        val session=DraftBrushImageSession(pen(),FrozenBrushPngs.empty())
        val oldRequest=session.requestPick(BrushImageSlot.sampled)
        val newer=session.requestPick(BrushImageSlot.dual)
        val old=loader().prepare(oldRequest,listOf(Source(bytes()).picked()))
        assertFailsWith<BrushImageDraftException>{session.stage(old)}
        val valid=loader().prepare(newer,listOf(Source(bytes()).picked()));session.stage(valid)
        val other=DraftBrushImageSession(pen(),FrozenBrushPngs.empty())
        assertFailsWith<BrushImageDraftException>{other.stage(valid)}
        val edit=session.prepareEdit(pen().copy(size=Param(19f)))
        assertFailsWith<BrushImageDraftException>{session.stage(valid)}
        session.acceptPending(edit){true};assertEquals(19f,session.current().recipe.size.base)
        assertFailsWith<BrushImageDraftException>{session.acceptPending(valid){true}}
    }

    @Test fun completeEncodedAndPixelBudgetsAreReservedBeforeAnyFullDecode() {
        val session=DraftBrushImageSession(pen(),FrozenBrushPngs.empty());var decodes=0
        val verifier=FrozenBrushAssets.PngVerifier{_,_,_->decodes++}
        val a=Source(bytes());val b=Source(bytes())
        val smallSet=BrushPngPickerLoader.Limits(maxSetBytes=a.bytes.size.toLong()*2-1)
        assertFailsWith<BrushImageDraftException>{loader(verifier,smallSet).prepare(session.requestPick(BrushImageSlot.sampled),listOf(a.picked(),b.picked()))}
        assertTrue(a.closed&&b.closed);assertEquals(0,decodes)
        val pixels=BrushPngPickerLoader.Limits(maxPixels=1)
        assertFailsWith<BrushImageDraftException>{loader(verifier,pixels).prepare(session.requestPick(BrushImageSlot.sampled),
            listOf(Source(bytes()).picked(),Source(bytes()).picked()))}
        assertEquals(0,decodes)
        var allClosed=false
        val first=Source(bytes());val second=Source(bytes())
        loader(FrozenBrushAssets.PngVerifier{_,_,_->allClosed=first.closed&&second.closed;decodes++})
            .prepare(session.requestPick(BrushImageSlot.sampled),listOf(first.picked(),second.picked()))
        assertTrue(allClosed);assertTrue(decodes>0)
    }

    @Test fun retainedPrimaryClosureCountsAgainstDualImportBudgets() {
        val session=DraftBrushImageSession(imagePen(),owner());var decodes=0
        val source=Source(bytes())
        val limit=BrushPngPickerLoader.Limits(maxSetBytes=source.bytes.size.toLong())
        assertFailsWith<BrushImageDraftException>{loader(FrozenBrushAssets.PngVerifier{_,_,_->decodes++},limit)
            .prepare(session.requestPick(BrushImageSlot.dual),listOf(source.picked()))}
        assertFalse(source.opened);assertEquals(0,decodes);assertEquals(owner().paths,session.current().resources.paths)
        val pixels=BrushPngPickerLoader.Limits(maxPixels=3) // a,b,a plus imported dual = four ordered source pixels.
        val next=Source(bytes())
        assertFailsWith<BrushImageDraftException>{loader(FrozenBrushAssets.PngVerifier{_,_,_->decodes++},pixels)
            .prepare(session.requestPick(BrushImageSlot.dual),listOf(next.picked()))}
        assertTrue(next.closed);assertEquals(0,decodes)
    }

    @Test fun impossibleMetadataCountsAndUnsupportedTextureRefuseBeforeOpeningStreams() {
        val session=DraftBrushImageSession(pen(),FrozenBrushPngs.empty());val source=Source(bytes())
        assertFailsWith<BrushImageDraftException>{session.requestPick(BrushImageSlot.tipTexture)}
        assertFailsWith<BrushImageDraftException>{loader().prepare(session.requestPick(BrushImageSlot.dual),listOf(source.picked(),source.picked()))}
        assertFailsWith<BrushImageDraftException>{loader().prepare(session.requestPick(BrushImageSlot.sampled),List(17){source.picked()})}
        assertFailsWith<BrushImageDraftException>{loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(source.picked(Long.MAX_VALUE)))}
        assertFalse(source.opened)
    }

    @Test fun streamFailureAndCancellationCloseOwnedStreamAndKeepCurrentPair() {
        val session=DraftBrushImageSession(pen(),FrozenBrushPngs.empty());val original=session.current();var closed=false
        val throwing=BrushPickedPng("bad.png") { object:java.io.InputStream(){
            override fun read():Int=throw IOException("read failure")
            override fun close(){closed=true}
        } }
        assertFailsWith<IOException>{loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(throwing))}
        assertTrue(closed);assertSame(original,session.current())
        val untouched=Source(bytes())
        assertFailsWith<BrushImageDraftException>{loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(untouched.picked())){true}}
        assertFalse(untouched.opened);assertSame(original,session.current())
    }

    @Test fun acceptedPairsOwnBytesAndDetachedRecipesCannotMutateSessionState() {
        val input=bytes();val original=input.copyOf();val session=DraftBrushImageSession(pen(),FrozenBrushPngs.empty())
        val loaded=loader().prepare(session.requestPick(BrushImageSlot.sampled),listOf(BrushPickedPng("name.png"){ByteArrayInputStream(input)}))
        input.fill(0);session.stage(loaded);session.acceptPending(loaded){true}
        val frozen=session.current().resources.requirePng(loaded.selectedPaths.single());assertContentEquals(original,frozen)
        frozen.fill(0);assertContentEquals(original,session.current().resources.requirePng(loaded.selectedPaths.single()))
        val recipe=session.current().recipe
        (recipe.tip.sampled!!.images as MutableList<String>).clear()
        assertEquals(1,session.current().recipe.tip.sampled!!.images.size)
    }

    @Test fun acceptanceReentryIsRefusedAndPendingCanBeDiscarded() {
        val session=DraftBrushImageSession(imagePen(),owner());val edit=session.prepareEdit(imagePen().copy(size=Param(23f)))
        assertFailsWith<BrushImageDraftException>{session.acceptPending(edit){session.discardPending();true}}
        assertSame(edit,session.pending());assertEquals(12f,session.current().recipe.size.base)
        assertTrue(session.acceptPending(edit){true});session.discardPending();assertNull(session.pending())
    }
}
