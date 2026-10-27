package cc.joycreator.joybrush.android.bench

import android.content.pm.ApplicationInfo
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.VERSION_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Admission/signature checks only; no GLES execution or native-pixel proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatercolourProbeDispatchTest {
    private val preset = BrushPreset(id = "wash", name = "Wash", version = VERSION_WATERCOLOUR,
        engine = ENGINE_WATERCOLOUR, size = Param(300f), watercolour = WatercolourSpec())
    private val record = StrokeRecord("unused-at-admission", preset.id, 19L, 0f, 1f, emptyList())

    private fun nonDebugCanvas(block: (JbCanvasView) -> Unit) {
        val context: android.app.Application = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        val before = info.flags
        info.flags = before and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val canvas = JbCanvasView(context)
        try { block(canvas) } finally {
            canvas.onPause()
            info.flags = before
        }
    }

    @Test fun ordinaryRejectsEveryOtherProbeModeBeforeCallbackOrGlQueue() = nonDebugCanvas { canvas ->
        val others = listOf("bake", "openCold", "joint", "promotion", "coldLoad", "regional",
            "capturedCopy", "coldPayload", "coldFailures", "capturedJoint", "watercolourEdits")
        for (other in others) {
            var callbacks = 0
            try {
                canvas.runRecordedReplayProbe(preset, record,
                    bake = other == "bake", openCold = other == "openCold", joint = other == "joint",
                    promotion = other == "promotion", coldLoad = other == "coldLoad", regional = other == "regional",
                    capturedCopy = other == "capturedCopy", coldPayload = other == "coldPayload",
                    coldFailures = other == "coldFailures", capturedJoint = other == "capturedJoint",
                    watercolourEdits = other == "watercolourEdits", watercolourOrdinary = true) { callbacks++ }
                fail("ordinary+$other must refuse before non-debug callback")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Ordinary watercolor"))
            }
            assertEquals("$other queued/reported instead of refusing", 0, callbacks)
        }
    }

    @Test fun oldPositionalSignatureAndNewOptInKeepNonDebugGuard() = nonDebugCanvas { canvas ->
        val reports = arrayListOf<String>()
        // Exact original positional signature, including positional last callback.
        canvas.runRecordedReplayProbe(preset, record, false, false, false, false, false,
            false, false, false, false, false, false, { reports += it })
        // Existing cache mode remains separate; ordinary mode requires its explicit option.
        canvas.runRecordedReplayProbe(preset, record, watercolourEdits = true) { reports += it }
        canvas.runRecordedReplayProbe(preset, record, watercolourOrdinary = true) { reports += it }
        assertEquals(3, reports.size)
        reports.forEach { raw ->
            val report = JSONObject(raw)
            assertFalse(report.getBoolean("passed"))
            assertTrue(report.getString("failure").contains("debug only"))
        }
    }

    @Test fun frameDuplicateDiagnosticRefusesBeforeQueuingGlInNonDebugBuild() = nonDebugCanvas { canvas ->
        var callbacks = 0
        canvas.runNativeFrameDuplicateProbe { raw ->
            callbacks++
            val report = JSONObject(raw)
            assertFalse(report.getBoolean("passed"))
            assertTrue(report.getString("failure").contains("debug only"))
        }
        assertEquals(1, callbacks)
    }
}
