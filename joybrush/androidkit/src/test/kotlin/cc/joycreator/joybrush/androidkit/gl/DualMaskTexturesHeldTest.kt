package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.*
import kotlin.test.*

/** R8 ownership, failure cleanup and physical graphics-context controls. */
class DualMaskTexturesHeldTest {
    private class FakeGl : SampleMaskGl {
        data class Upload(val texture:Int,val width:Int,val height:Int,val bytes:ByteArray,val repeat:Boolean)
        var cap=4096
        var next=1
        var failUpload=false
        var failDeleteCheck=false
        var afterGenerate:(()->Unit)?=null
        var afterUpload:(()->Unit)?=null
        var afterDelete:(()->Unit)?=null
        val generated=mutableListOf<Int>()
        val uploads=mutableListOf<Upload>()
        val deleted=mutableListOf<Int>()
        val binds=mutableListOf<Pair<Int,Int>>()
        val failDelete=mutableSetOf<Int>()
        override fun maxTextureSize()=cap
        override fun genTexture():Int {
            val name=next++;generated+=name;afterGenerate?.invoke();return name
        }
        override fun uploadR8(texture:Int,width:Int,height:Int,bytes:ByteArray,repeat:Boolean,contextCurrent:()->Boolean) {
            assertTrue(contextCurrent());uploads+=Upload(texture,width,height,bytes.copyOf(),repeat)
            afterUpload?.invoke()
            if(failUpload) throw BrushException("injected upload failure")
        }
        override fun bindTexture(unit:Int,texture:Int) { binds+=unit to texture }
        override fun deleteTexture(texture:Int) {
            if(texture in failDelete) error("injected deletion failure $texture")
            deleted+=texture;afterDelete?.invoke()
        }
        override fun checkError(operation:String) {
            if (failDeleteCheck && operation == "delete dual-tip texture") error("injected GL deletion error")
        }
    }
    private fun mask(seed:Long=1)=DualTips.bake(DualTipSpec(scale=1f,spacing=2f,scatter=0f),seed)

    @Test fun aDriverReportedDeleteErrorKeepsTheNameOwnedUntilSuccessfulRetry() {
        val gl=FakeGl(); val owner=DualMaskTextures(gl,{1L}); owner.create()
        val binding=owner.begin(mask())
        gl.failDeleteCheck=true
        assertFails { owner.end(binding) }
        assertEquals(setOf(1,2),owner.heldTextureHandles())
        val generated=gl.generated.toList()
        assertFailsWith<BrushException> { owner.begin(mask()) }
        assertEquals(generated,gl.generated,"a failed GL delete must not permit a replacement allocation")
        gl.failDeleteCheck=false
        owner.release()
        assertTrue(owner.heldTextureHandles().isEmpty())
        assertEquals(2,gl.deleted.count { it==binding.texture })
    }

    @Test fun oneImmutableMaskUploadsOnceWithRepeatAndLinearSurfaceAndBindsPerBatch() {
        val gl=FakeGl();val owner=DualMaskTextures(gl,{1L});owner.create()
        val mask=mask();val binding=owner.begin(mask)
        assertSame(mask,binding.mask);assertEquals(256,binding.width);assertEquals(256,binding.height)
        assertEquals(2,gl.uploads.size)
        assertFalse(gl.uploads[0].repeat);assertContentEquals(byteArrayOf(-1),gl.uploads[0].bytes)
        assertTrue(gl.uploads[1].repeat);assertEquals(256*256,gl.uploads[1].bytes.size)
        assertContentEquals(mask.bytes(),gl.uploads[1].bytes)
        repeat(3){owner.bindForBatch(binding,4)}
        assertEquals(2,gl.uploads.size);assertEquals(List(3){4 to binding.texture},gl.binds)
        assertFailsWith<BrushException>{owner.begin(mask)}
        assertEquals(setOf(1,2),owner.heldTextureHandles())
    }

    @Test fun disabledUsesWhitePlaceholderAndNoStaleOrForeignBindingCanSelectTexture() {
        val gl=FakeGl();val owner=DualMaskTextures(gl,{1L});owner.create()
        owner.bindForBatch(null,4);assertEquals(4 to 1,gl.binds.last())
        val first=owner.begin(mask())
        assertFailsWith<BrushException>{owner.bindForBatch(DualMaskBinding(first.mask,first.texture),4)}
        owner.end(first);owner.end(first)
        val second=owner.begin(mask(2))
        owner.end(first);owner.bindForBatch(second,4)
        assertEquals(4 to second.texture,gl.binds.last())
        assertFailsWith<BrushException>{owner.bindForBatch(first,4)}
        assertEquals(listOf(2),gl.deleted)
        owner.release();assertEquals(listOf(2,3,1),gl.deleted)
        assertTrue(owner.heldTextureHandles().isEmpty())
    }

    @Test fun realGpuCapRefusesBeforeGeneratingTheMask() {
        val gl=FakeGl().apply{cap=128};val owner=DualMaskTextures(gl,{1L});owner.create()
        assertFailsWith<BrushException>{owner.begin(mask())}
        assertEquals(listOf(1),gl.generated);assertEquals(1,gl.uploads.size)
        assertEquals(setOf(1),owner.heldTextureHandles())
    }

    @Test fun failedUploadDeletesOnlyNewNameAndRetainsDisabledPlaceholder() {
        val gl=FakeGl();val owner=DualMaskTextures(gl,{1L});owner.create();gl.failUpload=true
        assertFailsWith<BrushException>{owner.begin(mask())}
        assertEquals(listOf(2),gl.deleted);assertEquals(setOf(1),owner.heldTextureHandles())
        owner.bindForBatch(null,4);assertEquals(4 to 1,gl.binds.last())
        gl.failUpload=false;val next=owner.begin(mask());assertEquals(3,next.texture)
        owner.end(next);owner.release();assertEquals(listOf(2,3,1),gl.deleted)
    }

    @Test fun failedUploadCleanupRetainsBoundedNameAndBlocksAnotherUploadUntilRelease() {
        val gl=FakeGl();val owner=DualMaskTextures(gl,{1L});owner.create()
        gl.failUpload=true;gl.failDelete+=2
        val failure=assertFailsWith<BrushException>{owner.begin(mask())}
        assertEquals(1,failure.suppressed.size)
        assertEquals(setOf(1,2),owner.heldTextureHandles())
        assertFailsWith<BrushException>{owner.begin(mask())}
        assertEquals(listOf(1,2),gl.generated,"Failed cleanup must not permit unbounded new allocations")
        gl.failUpload=false;gl.failDelete.clear();owner.release()
        assertEquals(listOf(2,1),gl.deleted);assertTrue(owner.heldTextureHandles().isEmpty())
    }

    @Test fun replacementContextAbandonsOldNamesAndStaleEndCannotDeleteNewSet() {
        var epoch=1L;val gl=FakeGl();val owner=DualMaskTextures(gl,{epoch});owner.create()
        val old=owner.begin(mask());epoch=2;owner.create();val current=owner.begin(mask(2))
        owner.end(old);assertTrue(gl.deleted.isEmpty());owner.bindForBatch(current,4)
        assertFailsWith<BrushException>{owner.bindForBatch(old,4)}
        owner.release();assertEquals(listOf(4,3),gl.deleted)
    }

    @Test fun lossInsideGenerationOrUploadDoesNotDeleteOldNamesInNewContext() {
        var epoch=1L;val gl=FakeGl();val owner=DualMaskTextures(gl,{epoch});owner.create()
        gl.afterGenerate={epoch++}
        assertFailsWith<BrushException>{owner.begin(mask())}
        assertEquals(1,gl.uploads.size);assertTrue(gl.deleted.isEmpty());assertTrue(owner.heldTextureHandles().isEmpty())
        gl.afterGenerate=null;owner.create();gl.afterUpload={epoch++}
        assertFailsWith<BrushException>{owner.begin(mask())}
        assertTrue(gl.deleted.isEmpty());assertTrue(owner.heldTextureHandles().isEmpty())
    }

    @Test fun releaseAttemptsBothNamesAndFailedDeletesRemainOwnedForRetry() {
        val gl=FakeGl();val owner=DualMaskTextures(gl,{1L});owner.create();owner.begin(mask())
        gl.failDelete+=setOf(1,2)
        val failure=assertFailsWith<IllegalStateException>{owner.release()}
        assertEquals(1,failure.suppressed.size);assertEquals(setOf(1,2),owner.heldTextureHandles())
        assertFailsWith<BrushException>{owner.begin(mask())}
        gl.failDelete.clear();owner.release();assertEquals(listOf(2,1),gl.deleted)
        assertTrue(owner.heldTextureHandles().isEmpty())
    }

    @Test fun lossDuringFirstReleaseNeverDeletesTheOldPlaceholderAfterReplacement() {
        var epoch=1L;val gl=FakeGl();val owner=DualMaskTextures(gl,{epoch});owner.create();owner.begin(mask())
        gl.afterDelete={epoch++};owner.release()
        assertEquals(listOf(2),gl.deleted);assertTrue(owner.heldTextureHandles().isEmpty())
    }
}
