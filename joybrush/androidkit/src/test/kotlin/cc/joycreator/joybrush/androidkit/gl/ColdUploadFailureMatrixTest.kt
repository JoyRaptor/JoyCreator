package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.LineArchiveFixture
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColdUploadFailureMatrixTest {
    private fun source(): JbContents {
        val base = LineArchiveFixture.contents()
        val native = Layer("native-plane", "Native", LayerKind.PAINT,
            cels = listOf(Cel("native-cel", tiles = listOf("-1_2", "3_-4"))))
        return base.copy(doc = base.doc.copy(layers = base.doc.layers + native),
            tiles = base.tiles + mapOf(
                Triple("native-plane", "native-cel", "-1_2") to LineArchiveFixture.tile(21),
                Triple("native-plane", "native-cel", "3_-4") to LineArchiveFixture.tile(22),
            ))
    }

    private fun prepared(contents: JbContents = source()): ColdRecordedLoadPlan.Prepared {
        val fixture = ColdPayloadProbeFixture.enrich(contents)
        val archive = ByteArrayOutputStream().also { JbArchive.write(it, fixture.contentsCopy()) }.toByteArray()
        return ColdRecordedLoadPlan.prepare(JbArchive.read(ByteArrayInputStream(archive)))
    }

    @Test fun producesTwentyOneUniqueTargetsAtFirstNumericUploadPerKind() {
        val plan = prepared()
        val firstTargets = ColdUploadFailureMatrix.targets(plan)
        val again = ColdUploadFailureMatrix.targets(plan)
        val kinds = ColdRecordedLoadPlan.Kind.values().toSet()
        val uploadIdsByKind = plan.uploads.groupBy { it.kind }.mapValues { (_, uploads) -> uploads.map { it.id }.sorted() }

        assertEquals(21, firstTargets.size)
        assertEquals(21, firstTargets.toSet().size)
        assertEquals(firstTargets, again)
        assertEquals(kinds, uploadIdsByKind.keys)

        for (kind in ColdRecordedLoadPlan.Kind.values()) {
            val selected = firstTargets.filter { it.uploadId != null && plan.uploads[it.uploadId].kind == kind }
            assertEquals(listOf(
                ColdUploadFailurePhase.AFTER_GENERATE,
                ColdUploadFailurePhase.AFTER_STORAGE,
                ColdUploadFailurePhase.AFTER_UPLOAD,
            ), selected.map { it.phase })
            assertEquals(listOf(uploadIdsByKind.getValue(kind).first()), selected.mapNotNull { it.uploadId }.distinct())
        }

        val globals = firstTargets.filter { it.uploadId == null }
        assertEquals(listOf(
            ColdUploadFailurePhase.AFTER_SAVED_SCENE,
            ColdUploadFailurePhase.AFTER_ROOT_BIND,
            ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE,
        ), globals.map { it.phase })
        assertTrue(firstTargets.all { it.uploadId == null || it.uploadId >= 0 })
    }

    @Test fun targetListAndTargetPhaseIdPairsAreImmutableAndValidated() {
        val targets = ColdUploadFailureMatrix.targets(prepared())
        @Suppress("UNCHECKED_CAST")
        val mutableView = targets as MutableList<ColdUploadFailureTarget>
        assertFails { mutableView.clear() }
        assertFails { mutableView[0] = ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_GENERATE, 0) }

        assertFailsWith<IllegalArgumentException> {
            ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_GENERATE, null)
        }
        assertFailsWith<IllegalArgumentException> {
            ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_SAVED_SCENE, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_UPLOAD, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE, 1)
        }
        assertNotNull(ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE, null))
    }

    @Test fun missingUploadCategoryRefusesMatrixAndUnreadyEngineRefusesBeforeGl() {
        val incomplete = ColdRecordedLoadPlan.prepare(source())
        assertFailsWith<IllegalStateException> { ColdUploadFailureMatrix.targets(incomplete) }

        val plan = prepared()
        val target = ColdUploadFailureMatrix.targets(plan).first()
        assertFailsWith<IllegalStateException> {
            GlPaintEngine().runColdUploadFailureProbe(plan, target)
        }
    }
}
