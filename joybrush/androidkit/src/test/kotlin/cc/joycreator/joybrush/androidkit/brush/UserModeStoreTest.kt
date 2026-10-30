package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import java.io.IOException
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

/** Proposed pure JVM persistence/transaction tests, outside the canonical source tree. */
class UserModeStoreTest {
    private class Disk : UserModePersistence {
        val directories = mutableListOf<Path>()
        var rejectPublish = false
        var rejectPostPublishSync = false
        private var published = false
        override fun syncDirectory(directory: Path) {
            if (published && rejectPostPublishSync) throw IOException("injected root fsync failure")
            directories.add(directory)
            // Windows cannot open a directory as a FileChannel. This injected durability boundary
            // records the requested fsync; production Android/Linux Nio must provide the real one.
        }
        override fun publishNew(staging: Path, destination: Path) {
            if (rejectPublish) throw AtomicMoveNotSupportedException(staging.toString(),destination.toString(),"injected")
            UserModePersistence.Nio.publishNew(staging,destination);published=true
        }
    }
    private fun <T> withRoot(block:(Path)->T):T {
        val base=Files.createTempDirectory("unibrush-mode-store-test-").toAbsolutePath().normalize()
        try { return block(base.resolve("modes")) }
        finally {
            // This unique test-owned directory is the sole recursive cleanup target.
            Files.walk(base).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { p ->
                check(p.toAbsolutePath().normalize().startsWith(base));Files.deleteIfExists(p)
            } }
        }
    }
    private fun pen()=BrushPreset(id="imported.pen",name="Imported pen",size=Param(12f),author="Original artist",
        license="CC BY 4.0",sourceFormat="abr",extensions=mapOf("import.original" to "untouched"))
    private fun imagePen()=pen().copy(tip=TipSpec(source="image",image="tips/a.png",sampled=SampledTipSpec(listOf("tips/a.png"))))
    private fun png(alpha:Int=255)=FrozenBrushPngs.snapshot(mapOf("tips/a.png" to
        PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,alpha.toByte()))))
    private fun folders(root:Path)=Files.newDirectoryStream(root).use { list ->
        list.map { it.fileName.toString() }.filter { !it.startsWith(".") }.sorted()
    }
    private fun json(root:Path,mode:UserModeDefinition)=Files.readAllBytes(root.resolve(mode.folder).resolve("brush.json"))
    private fun hasIdentity(root:Path)=Files.readAttributes(root,
        java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()!=null
    private fun assertStagingCleanup(root:Path,failure:UserModeStoreException) {
        val stages=Files.newDirectoryStream(root).use { it.filter { p->p.fileName.toString().startsWith(".staging-") }.toList() }
        if(hasIdentity(root))assertTrue(stages.isEmpty(),"Confirmed owned stages must be cleaned")
        else {
            assertTrue(stages.isNotEmpty(),"Unknown identity must preserve the staged paths")
            val cleanupFailures=failure.suppressed.toList()+(failure.cause?.suppressed?.toList()?:emptyList())
            assertTrue(cleanupFailures.any {
                it.message?.contains("could not confirm ownership")==true
            },"Conservative cleanup refusal must be reported")
        }
    }

    @Test fun saveAsAppendsUniqueModesPreservesAttributionAndSurvivesRestart()=withRoot { root ->
        val disk=Disk();val store=UserModeStore(root,persistence=disk)
        assertFalse(Files.exists(root));assertTrue(store.loadDefinitions().definitions.isEmpty());assertFalse(Files.exists(root))
        val a=store.saveAs("Soft ink",pen());val original=json(root,a)
        val b=store.saveAs("Soft ink",a.preset.copy(size=Param(31f)))
        assertNotEquals(a.id,b.id);assertNotEquals(a.folder,b.folder);assertEquals(2,folders(root).size)
        assertContentEquals(original,json(root,a));assertEquals(12f,a.preset.size.base);assertEquals(31f,b.preset.size.base)
        assertEquals("Original artist",b.preset.author);assertEquals("CC BY 4.0",b.preset.license)
        assertEquals("abr",b.preset.sourceFormat);assertEquals("untouched",b.preset.extensions["import.original"])
        assertEquals("user",b.preset.extensions[UserModeStore.FAMILY_KEY]);assertTrue(b.id.startsWith(UserModeStore.ID_PREFIX))
        assertEquals(UUID.fromString(b.folder).toString(),b.folder)
        val reopened=UserModeStore(root,persistence=Disk());val catalog=reopened.loadDefinitions()
        assertTrue(catalog.refused.isEmpty());assertEquals(setOf(a.id,b.id),catalog.definitions.map { it.id }.toSet())
        assertEquals(31f,reopened.select(b.id).preset.size.base);assertEquals(0L,reopened.heldPngBytes())
        assertTrue(disk.directories.any { it.fileName.toString().startsWith(".staging-") })
    }

    @Test fun saveAsKeepsLegacyVersionAndRaisesOnlyVersionsRequiredByNewWords()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk())
        val old=pen().copy(version=1)
        val saved=store.saveAs("Legacy mode",old)
        assertEquals(1,saved.preset.version)
        val reopened=UserModeStore(root,persistence=Disk())
        reopened.loadDefinitions()
        assertEquals(1,reopened.select(saved.id).preset.version)
        val modern=old.copy(stabilizer=cc.joycreator.joybrush.core.input.StabilizerSpec())
        val modernSaved=store.saveAs("Stabilized mode",modern)
        assertEquals(18,modernSaved.preset.version)
        assertNotNull(modernSaved.preset.stabilizer)
        Unit
    }

    @Test fun packagedGrainPersistsAndActiveUnknownImageRefusesRegardlessOfSourceWord()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk())
        for(source in listOf("cloud","image"))assertFailsWith<UserModeStoreException>{store.saveAs("Unknown texture",pen().copy(tipTexture=
            GrainSpec(enabled=true,source=source,image="picked/imported.png")))}
        assertFalse(Files.exists(root))
        val mode=store.saveAs("Fine grain",pen().copy(tipTexture=
            GrainSpec(enabled=true,source="image",image="cloud_fine_256.png")))
        val dormant=store.saveAs("Dormant grain",pen().copy(tipTexture=
            GrainSpec(enabled=false,source="cloud",image="arbitrary-legacy-metadata.png")))
        val reopened=UserModeStore(root,persistence=Disk());reopened.loadDefinitions()
        val selected=reopened.select(mode.id)
        assertEquals("cloud_fine_256.png",selected.preset.tipTexture.image);assertNull(selected.images)
        assertEquals("arbitrary-legacy-metadata.png",reopened.select(dormant.id).preset.tipTexture.image)
    }

    @Test fun metadataLoadsWithoutPngReadsAndSelectionHasOneStableFrozenOwner()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk())
        val a=store.saveAs("Image ink",imagePen(),png());val b=store.saveAs("Faint image",imagePen(),png(128))
        val plain=store.saveAs("Plain ink",pen())
        val reads=mutableListOf<Path>();val reopened=UserModeStore(root,persistence=Disk(),onRead={reads.add(it)})
        val catalog=reopened.loadDefinitions();assertEquals(3,catalog.definitions.size)
        assertTrue(reads.all { it.fileName.toString()=="brush.json" })
        reads.clear();val first=assertNotNull(reopened.select(a.id).images)
        assertTrue(reads.any { it.fileName.toString().endsWith(".png") });val count=reads.size
        assertSame(first,reopened.pngs(a.preset.copy(size=Param(20f))));assertEquals(count,reads.size)
        assertFailsWith<UserModeStoreException>{reopened.pngs(a.preset.copy(tip=a.preset.tip.copy(
            sampled=SampledTipSpec(listOf("other.png")))))}
        reopened.loadDefinitions();assertSame(first,reopened.pngs(a.preset))
        val second=assertNotNull(reopened.select(b.id).images);assertFalse(first.sameBytes(second))
        assertEquals(second.encodedBytes,reopened.heldPngBytes())
        reopened.select(plain.id);assertEquals(0L,reopened.heldPngBytes())
        assertNull(reopened.pngs(a.preset.copy(tip=TipSpec())));assertEquals(0L,reopened.heldPngBytes())
    }

    @Test fun completePrimaryAndDualImageClosureRoundTripsWithoutAnInstalledLibrary()=withRoot { root ->
        val p=imagePen().copy(tip=imagePen().tip.copy(dual=DualTipSpec(shape=null,image=DualImageSpec("dual/b.png"))))
        val both=FrozenBrushPngs.snapshot(mapOf("tips/a.png" to png().requirePng("tips/a.png"),
            "dual/b.png" to PngWriter.encode(1,1,byteArrayOf(-1,-1,-1,100))))
        val mode=UserModeStore(root,persistence=Disk()).saveAs("Broken ink",p,both)
        val reopened=UserModeStore(root,persistence=Disk());reopened.loadDefinitions()
        assertTrue(both.sameBytes(assertNotNull(reopened.select(mode.id).images)))
    }

    @Test fun missingExtraCorruptAndOversizedMetadataRefuseBeforeCreatingRoot()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk())
        assertFailsWith<UserModeStoreException>{store.saveAs("Missing",imagePen())}
        assertFailsWith<UserModeStoreException>{store.saveAs("Extra",pen(),png())}
        val bad=FrozenBrushPngs.snapshot(mapOf("tips/a.png" to byteArrayOf(1,2,3)))
        assertFailsWith<UserModeStoreException>{store.saveAs("Corrupt",imagePen(),bad)}
        val tiny=UserModeStore(root,limits=UserModeStore.Limits(maxModeJsonBytes=128),persistence=Disk())
        assertFailsWith<UserModeStoreException>{tiny.saveAs("Too large",pen().copy(extensions=mapOf("x" to "y".repeat(100_000))))}
        assertFalse(Files.exists(root),"Image and metadata admission must precede all filesystem writes")
    }

    @Test fun UUIDCollisionNeverOverwritesAnEarlierMode()=withRoot { root ->
        val same=UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val store=UserModeStore(root,uuid={same},persistence=Disk());val first=store.saveAs("First",pen())
        val old=json(root,first)
        assertFailsWith<UserModeStoreException>{store.saveAs("Replacement attempt",pen().copy(size=Param(99f)))}
        assertContentEquals(old,json(root,first));assertEquals(listOf(first.folder),folders(root))
    }

    @Test fun destinationAppearingBeforePublicationIsPreservedWithoutCallingThePublisher()=withRoot { root ->
        val same=UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val destination=root.resolve(same.toString());val marker=byteArrayOf(9,8,7)
        var publishCalls=0
        val disk=object:UserModePersistence {
            override fun syncDirectory(directory:Path) { }
            override fun publishNew(staging:Path,destination:Path) { publishCalls++;error("Must not publish over a collision") }
        }
        val store=UserModeStore(root,uuid={same},persistence=disk,checkpoint={phase,_->
            if(phase==UserModeStore.Phase.BEFORE_PUBLISH) {
                Files.createDirectory(destination);Files.write(destination.resolve("foreign.keep"),marker)
            }
        })
        val failure=assertFailsWith<UserModeStoreException>{store.saveAs("Colliding mode",pen())}
        assertEquals(0,publishCalls);assertContentEquals(marker,Files.readAllBytes(destination.resolve("foreign.keep")))
        assertFalse(Files.exists(destination.resolve("brush.json")))
        assertStagingCleanup(root,failure)
    }

    @Test fun CountAndMetadataBudgetsRefuseWithoutLosingRegisteredOldModes()=withRoot { root ->
        val store=UserModeStore(root,limits=UserModeStore.Limits(maxModes=1),persistence=Disk())
        val first=store.saveAs("First",pen());val old=json(root,first)
        assertFailsWith<UserModeStoreException>{store.saveAs("Second",pen())}
        assertContentEquals(old,json(root,first));assertEquals(first.id,store.select(first.id).preset.id)
        val larger=UserModeStore(root,persistence=Disk());larger.loadDefinitions()
        val oversized=root.resolve("12345678-1234-1234-1234-123456789abc");Files.createDirectory(oversized)
        Files.write(oversized.resolve("brush.json"),ByteArray(UserModeStore.MAX_MODE_JSON_BYTES+1){32})
        assertFailsWith<UserModeStoreException>{larger.loadDefinitions()}
        assertContentEquals(old,json(root,first));assertEquals(first.id,larger.select(first.id).preset.id)
        assertTrue(Files.exists(oversized),"A refusal never removes another mode folder")
    }

    @Test fun malformedOneModeDoesNotHideOtherValidModesAndMetadataBudgetIsAggregate()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk());val first=store.saveAs("First",pen());val second=store.saveAs("Second",pen())
        val firstBytes=json(root,first);val secondBytes=json(root,second)
        val bounded=UserModeStore(root,limits=UserModeStore.Limits(maxCatalogJsonBytes=firstBytes.size.toLong()+secondBytes.size-1),persistence=Disk())
        assertFailsWith<UserModeStoreException>{bounded.loadDefinitions()}
        Files.write(root.resolve(second.folder).resolve("brush.json"),"not JSON".toByteArray())
        val reopened=UserModeStore(root,persistence=Disk());val catalog=reopened.loadDefinitions()
        assertEquals(listOf(first.id),catalog.definitions.map { it.id });assertEquals(1,catalog.refused.size)
        assertEquals(first.id,reopened.select(first.id).preset.id);assertContentEquals(firstBytes,json(root,first))
    }

    @Test fun interruptedStageAndUnsupportedAtomicPublishLeaveOldModesUntouched()=withRoot { root ->
        val first=UserModeStore(root,persistence=Disk()).saveAs("First",pen());val old=json(root,first)
        val interrupted=UserModeStore(root,persistence=Disk(),checkpoint={phase,_->
            if(phase==UserModeStore.Phase.AFTER_METADATA_SYNC)throw IOException("injected stage failure")})
        val failure=assertFailsWith<UserModeStoreException>{interrupted.saveAs("Interrupted",imagePen(),png())}
        assertEquals(listOf(first.folder),folders(root))
        assertStagingCleanup(root,failure)
        val unsupported=UserModeStore(root,persistence=Disk().apply{rejectPublish=true})
        assertFailsWith<UserModeStoreException>{unsupported.saveAs("No atomic move",pen())}
        assertContentEquals(old,json(root,first));assertEquals(listOf(first.folder),folders(root))
    }

    @Test fun cleanupDeletesOnlyTrackedStagingFilesAndNeverAnUntrackedSibling()=withRoot { root ->
        val oldStore=UserModeStore(root,persistence=Disk());val first=oldStore.saveAs("First",pen());val old=json(root,first)
        var foreign:Path?=null
        val interrupted=UserModeStore(root,persistence=Disk(),checkpoint={phase,file->
            if(phase==UserModeStore.Phase.AFTER_METADATA_SYNC){
                val path=file.parent.resolve("foreign.keep");foreign=path;Files.write(path,byteArrayOf(42));throw IOException("injected")
            }})
        val failure=assertFailsWith<UserModeStoreException>{interrupted.saveAs("Interrupted",pen())}
        assertContentEquals(byteArrayOf(42),Files.readAllBytes(assertNotNull(foreign)))
        assertEquals(!hasIdentity(root),Files.exists(assertNotNull(foreign).parent.resolve("brush.json")))
        if(!hasIdentity(root))assertStagingCleanup(root,failure)
        assertContentEquals(old,json(root,first));assertEquals(listOf(first.folder),folders(root))
    }

    @Test fun cleanupDoesNotDeleteAReplacementAtAnOriginallyOwnedPath()=withRoot { root ->
        val marker=byteArrayOf(4,2);var replacement:Path?=null;var moved:Path?=null
        val store=UserModeStore(root,persistence=Disk(),checkpoint={phase,file->
            if(phase==UserModeStore.Phase.AFTER_METADATA_SYNC) {
                val keep=file.resolveSibling("moved-original.keep")
                Files.move(file,keep);moved=keep
                Files.write(file,marker,StandardOpenOption.CREATE_NEW);replacement=file
                throw IOException("injected replacement before cleanup")
            }
        })
        val failure=assertFailsWith<UserModeStoreException>{store.saveAs("Interrupted",pen())}
        assertContentEquals(marker,Files.readAllBytes(assertNotNull(replacement)))
        assertTrue(Files.exists(assertNotNull(moved)))
        assertTrue(assertNotNull(failure.cause).suppressed.isNotEmpty(),"Unconfirmed cleanup ownership must be reported")
    }

    @Test fun everyAssetAndPrepublicationFailureCleansOnlyTheNewStage()=withRoot { root ->
        val first=UserModeStore(root,persistence=Disk()).saveAs("First",pen());val old=json(root,first)
        for(stop in listOf(UserModeStore.Phase.AFTER_ASSET_SYNC,UserModeStore.Phase.BEFORE_PUBLISH)) {
            val failing=UserModeStore(root,persistence=Disk(),checkpoint={phase,_->if(phase==stop)throw IOException("injected $stop")})
            val failure=assertFailsWith<UserModeStoreException>{failing.saveAs("Interrupted",imagePen(),png())}
            assertContentEquals(old,json(root,first));assertEquals(listOf(first.folder),folders(root))
            assertStagingCleanup(root,failure)
        }
    }

    @Test fun failureAfterAtomicPublishReportsIdentityAndNeverRollsBackThePublishedFolder()=withRoot { root ->
        val disk=Disk().apply{rejectPostPublishSync=true};val store=UserModeStore(root,persistence=disk)
        val failure=assertFailsWith<UserModeStoreException>{store.saveAs("Published",pen())}
        val id=assertNotNull(failure.publishedId)
        val reopened=UserModeStore(root,persistence=Disk());val definitions=reopened.loadDefinitions().definitions
        assertEquals(listOf(id),definitions.map { it.id });assertEquals(id,reopened.select(id).preset.id)
    }

    @Test fun publisherThrowingAfterRenameStillReportsTheVisibleMode()=withRoot { root ->
        val disk=object:UserModePersistence {
            override fun syncDirectory(directory:Path) { }
            override fun publishNew(staging:Path,destination:Path) {
                UserModePersistence.Nio.publishNew(staging,destination)
                throw IOException("injected publication-return failure")
            }
        }
        val store=UserModeStore(root,persistence=disk)
        val failure=assertFailsWith<UserModeStoreException>{store.saveAs("Published",pen())}
        val id=assertNotNull(failure.publishedId)
        val reopened=UserModeStore(root,persistence=Disk())
        assertEquals(listOf(id),reopened.loadDefinitions().definitions.map{it.id})
        assertEquals(id,reopened.select(id).preset.id)
    }

    @Test fun callerIdsCannotAddressArbitraryFoldersAndCorruptDiskPngRefusesSelection()=withRoot { root ->
        val store=UserModeStore(root,persistence=Disk());val mode=store.saveAs("Image",imagePen(),png())
        assertFailsWith<UserModeStoreException>{store.select("../../outside")}
        val blob=Files.newDirectoryStream(root.resolve(mode.folder).resolve("brush-assets/sha256")).use{it.single()}
        Files.write(blob,ByteArray(Files.size(blob).toInt()){0})
        val reopened=UserModeStore(root,persistence=Disk());assertEquals(1,reopened.loadDefinitions().definitions.size)
        assertFailsWith<UserModeStoreException>{reopened.select(mode.id)}
        assertEquals(0L,reopened.heldPngBytes());assertEquals(listOf(mode.folder),folders(root))
    }
}
